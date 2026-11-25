package nuna.audittables;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

import nuna.catalog.audit.AuditRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FanoutDataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;

/**
 * The queryable audit table, {@code events} (D23, amended 2026-11-20), as its writer sees it: format v2, so every
 * reader in the matrix reads it, partitioned by the day of each record's time, one row per audit record.
 *
 * <p>Every commit records, in its snapshot summary, the next offset to read in each partition of the audit topic,
 * so rows and offsets land in one atomic catalog commit and the writer resumes from the table itself. Rows the writer
 * took from the catalog's log over OTLP ({@link LogWriter}) are in no topic: their {@code topic_partition} is
 * {@value LogWriter#PARTITION} and their {@code topic_offset} a number the writer gives each, unique and rising, and
 * their commits record, under the source name {@value LogWriter#SOURCE}, the next such number. Each commit
 * also checks that no rows were added since the snapshot it started from: a second writer fails instead of writing
 * the same records again. Compaction's and manifest rewrites' snapshots add no rows, so they never stop it.
 *
 * <p>A row from the log also carries the server run that logged its record and its number there, {@code log_run} and
 * {@code log_seq} (backlog 272), which place it in the run's archive chain. The two columns came after the table's
 * first: a table made without them is given them when the writer opens it, so its earlier rows read null there.
 */
final class EventsTable {

    static final String NAME = "events";
    /** The summary property holding the offsets reached in the topic named after it: {@code 0:15,1:3}. */
    static final String OFFSETS = "nuna.audit-tables.offsets.";
    /** The summary property counting the rows a commit added. */
    static final String ROWS = "nuna.audit-tables.rows";

    /** The columns the table was first made with; a table is still made with these, then given {@link #LOGGED}. */
    private static final Schema FIRST = new Schema(
            required(1, "event_id", Types.StringType.get()),
            required(2, "time", Types.TimestampType.withZone()),
            required(3, "type", Types.StringType.get()),
            required(4, "source", Types.StringType.get()),
            optional(5, "principal", Types.StringType.get()),
            optional(6, "end_user", Types.StringType.get()),
            optional(7, "engine", Types.StringType.get()),
            optional(8, "request_id", Types.StringType.get()),
            optional(9, "catalog", Types.StringType.get()),
            optional(10, "object_kind", Types.StringType.get()),
            optional(11, "object_id", Types.StringType.get()),
            optional(12, "object_path", Types.ListType.ofRequired(19, Types.StringType.get())),
            optional(13, "operation", Types.StringType.get()),
            optional(14, "privilege", Types.StringType.get()),
            optional(15, "reason", Types.StringType.get()),
            required(16, "data", Types.StringType.get()),
            required(17, "topic_partition", Types.IntegerType.get()),
            required(18, "topic_offset", Types.LongType.get()));
    /**
     * Where a record from the log was logged (backlog 272), added after the first columns with the ids a table made
     * with those is given next, as every reader projects by id.
     */
    private static final List<Types.NestedField> LOGGED = List.of(
            optional(20, "log_run", Types.StringType.get(), "the server run that logged the record (nunarun)"),
            optional(21, "log_seq", Types.LongType.get(), "the record's number in that run (nunaseq)"));
    static final Schema SCHEMA = new Schema(Stream.concat(FIRST.columns().stream(), LOGGED.stream()).toList());
    static final PartitionSpec SPEC = PartitionSpec.builderFor(FIRST).day("time").build();

    private final Table table;
    private final String offsetsKey;
    private final GenericFileWriterFactory writers;
    private final long targetFileBytes;
    /** The snapshot the next commit starts from: no rows may be added after it but the commit's own. */
    private Long base;
    private int attempt;

    private EventsTable(Table table, String topic) {
        this.table = table;
        this.offsetsKey = OFFSETS + topic;
        this.writers = new GenericFileWriterFactory.Builder(table).dataFileFormat(FileFormat.PARQUET).build();
        this.targetFileBytes = PropertyUtil.propertyAsLong(table.properties(),
                TableProperties.WRITE_TARGET_FILE_SIZE_BYTES, TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT);
    }

    /**
     * Loads {@code <namespace>.events}, or creates it and its namespace; refuses a table with other columns.
     *
     * @param topic the audit topic the rows come from, or {@value LogWriter#SOURCE} for the log over OTLP: the name
     *              its commits record their offsets under
     */
    static <C extends Catalog & SupportsNamespaces> EventsTable open(C catalog, Namespace namespace, String topic) {
        TableIdentifier id = TableIdentifier.of(namespace, NAME);
        Table table;
        try {
            table = catalog.loadTable(id);
        } catch (NoSuchTableException e) {
            if (!catalog.namespaceExists(namespace)) {
                try {
                    catalog.createNamespace(namespace);
                } catch (AlreadyExistsException created) {
                    // Made since we looked.
                }
            }
            table = catalog.buildTable(id, FIRST).withPartitionSpec(SPEC)
                    .withProperty(TableProperties.FORMAT_VERSION, "2").create();
        }
        Table opened = table;
        List<Types.NestedField> absent = LOGGED.stream().filter(c -> opened.schema().findField(c.name()) == null)
                .toList();
        if (!absent.isEmpty()) {
            UpdateSchema update = table.updateSchema();
            absent.forEach(c -> update.addColumn(c.name(), c.type(), c.doc()));
            update.commit();
        }
        for (Types.NestedField expected : SCHEMA.columns()) {
            Types.NestedField column = table.schema().findField(expected.name());
            if (column == null || column.fieldId() != expected.fieldId()
                    || !column.type().toString().equals(expected.type().toString())
                    || column.isOptional() != expected.isOptional()) {
                throw new IllegalStateException(id + " is not the audit table this writer writes: its column "
                        + expected.name() + " is " + column + ", not " + expected);
            }
        }
        return new EventsTable(table, topic);
    }

    /**
     * Reads the table again and returns the offsets its last commit recorded, the next to read in each partition;
     * none for a table never written. The next commit starts from the snapshot read here.
     *
     * @throws IllegalStateException if the table holds rows but no snapshot in its history records offsets: snapshot
     *                               expiry removed them, and the writer will not guess where to start
     */
    Map<Integer, Long> reload() {
        Optional<Map<Integer, Long>> recorded = reloadRecorded();
        if (recorded.isEmpty() && table.currentSnapshot() != null) {
            throw new IllegalStateException(table.name() + " holds rows, but no snapshot left in its history records"
                    + " the offsets they reached (" + offsetsKey + "): snapshot expiry removed the writer's last"
                    + " commit. Keep the audit catalog's snapshots longer than the writer may be down.");
        }
        return recorded.orElse(Map.of());
    }

    /**
     * Reads the table again and returns the offsets the last commit of this source recorded, if any snapshot in its
     * history holds them. The next commit starts from the snapshot read here.
     */
    Optional<Map<Integer, Long>> reloadRecorded() {
        table.refresh();
        Snapshot current = table.currentSnapshot();
        base = current == null ? null : current.snapshotId();
        for (Snapshot snapshot = current; snapshot != null;
             snapshot = snapshot.parentId() == null ? null : table.snapshot(snapshot.parentId())) {
            String recorded = snapshot.summary().get(offsetsKey);
            if (recorded != null) {
                return Optional.of(parse(recorded));
            }
        }
        return Optional.empty();
    }

    /** The event ids of the rows whose record time is at or after {@code since}, as the table holds them now. */
    Set<String> eventIdsSince(Instant since) {
        Set<String> ids = new HashSet<>();
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, since);
        try (CloseableIterable<Record> rows = IcebergGenerics.read(table).select("event_id", "time")
                .where(Expressions.greaterThanOrEqual("time", micros)).build()) {
            rows.forEach(row -> ids.add((String) row.getField("event_id")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }

    /**
     * Writes the rows as Parquet files and commits them with {@code offsets}, every partition's next offset, in one
     * commit.
     *
     * @throws org.apache.iceberg.exceptions.ValidationException if rows were added since the snapshot this writer
     *                                                           last read or committed
     */
    void append(List<AuditRow> rows, Map<Integer, Long> offsets) {
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, ++attempt).format(FileFormat.PARQUET)
                .operationId(UUID.randomUUID().toString()).build();
        FanoutDataWriter<Record> writer = new FanoutDataWriter<>(writers, files, table.io(), targetFileBytes);
        PartitionKey partition = new PartitionKey(table.spec(), table.schema());
        InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
        try (writer) {
            for (AuditRow row : rows) {
                Record record = record(row);
                partition.partition(wrapper.wrap(record));
                writer.write(record, table.spec(), partition.copy());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RowDelta delta = table.newRowDelta();
        writer.result().dataFiles().forEach(delta::addRows);
        // Without a starting snapshot Iceberg checks the table's whole history: an empty table must still be empty.
        if (base != null) {
            delta.validateFromSnapshot(base);
        }
        delta.conflictDetectionFilter(Expressions.alwaysTrue()).validateNoConflictingDataFiles();
        delta.set(offsetsKey, format(offsets));
        delta.set(ROWS, String.valueOf(rows.size()));
        delta.commit();
        base = table.currentSnapshot().snapshotId();
    }

    private Record record(AuditRow row) {
        GenericRecord record = GenericRecord.create(table.schema());
        record.setField("event_id", row.eventId());
        record.setField("time", row.time().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
        record.setField("type", row.type());
        record.setField("source", row.source());
        record.setField("principal", row.principal());
        record.setField("end_user", row.endUser());
        record.setField("engine", row.engine());
        record.setField("request_id", row.requestId());
        record.setField("catalog", row.catalog());
        record.setField("object_kind", row.objectKind());
        record.setField("object_id", row.objectId());
        record.setField("object_path", row.objectPath());
        record.setField("operation", row.operation());
        record.setField("privilege", row.privilege());
        record.setField("reason", row.reason());
        record.setField("data", row.data());
        record.setField("topic_partition", row.topicPartition());
        record.setField("topic_offset", row.topicOffset());
        record.setField("log_run", row.logRun());
        record.setField("log_seq", row.logSequence());
        return record;
    }

    static String format(Map<Integer, Long> offsets) {
        return new TreeMap<>(offsets).entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
    }

    static Map<Integer, Long> parse(String recorded) {
        Map<Integer, Long> offsets = new TreeMap<>();
        if (!recorded.isEmpty()) {
            for (String part : recorded.split(",")) {
                String[] pair = part.split(":", 2);
                offsets.put(Integer.parseInt(pair[0]), Long.parseLong(pair[1]));
            }
        }
        return offsets;
    }
}
