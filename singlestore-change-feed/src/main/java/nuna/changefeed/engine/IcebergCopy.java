package nuna.changefeed.engine;

import nuna.changefeed.engine.ChangeBatch.Change;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.ClusteredEqualityDeleteWriter;
import org.apache.iceberg.io.DeleteWriteResult;
import org.apache.iceberg.io.FanoutDataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Iceberg copy of a SingleStore table, and the only writer of it.
 *
 * <p>Every commit records, in its snapshot summary, the change-feed offsets it covers (one per SingleStore
 * partition). Data and offsets land in the same atomic catalog commit, so after any failure the feed resumes from
 * the table itself, and nothing is copied twice or skipped. Each commit also checks that no one else committed
 * since the snapshot it started from: a second writer (a task that should have stopped) fails instead of applying
 * the same changes again.
 *
 * <p>Inserts and updates are written as data files. A key that existed before the batch (its first event in the
 * batch is an update or delete) gets an equality delete on the primary key, which removes its earlier rows. The
 * equality delete is written in the row's partition, so the partition's source columns must be part of the key
 * (as Flink's Iceberg sink requires for upserts).
 */
final class IcebergCopy {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergCopy.class);

    static final String SOURCE = "nuna.changefeed.source";
    static final String OFFSETS = "nuna.changefeed.offsets";
    static final String COMMIT_ID = "nuna.changefeed.commit-id";
    static final String KIND = "nuna.changefeed.kind";
    /**
     * On a re-snapshot, the offsets the copy had reached when SingleStore no longer held its log from there: changes
     * after them and before the snapshot were not seen one by one, only their result.
     */
    static final String GAP_AFTER = "nuna.changefeed.gap-after";
    /** Rows the commit wrote, and keys it deleted (not counting keys whose rows it replaced). */
    static final String ROWS = "nuna.changefeed.rows";
    static final String KEYS_DELETED = "nuna.changefeed.keys-deleted";
    /** Parquet's default row group is 128 MB, the whole heap SingleStore may give a connector. */
    static final String ROW_GROUP_BYTES = String.valueOf(8L << 20);

    /**
     * What a commit did, for the receipt the pipeline writes into SingleStore; {@code gapAfter} is null but on a
     * re-snapshot. Read from the snapshot alone, so a receipt sent again is the same row.
     */
    record Receipt(long snapshotId, String kind, long rows, long deletedKeys, String offsets, String gapAfter, long committedAt) {

        static Receipt of(Snapshot snapshot) {
            Map<String, String> summary = snapshot.summary();
            return new Receipt(snapshot.snapshotId(), summary.get(KIND), Long.parseLong(summary.getOrDefault(ROWS, "0")),
                    Long.parseLong(summary.getOrDefault(KEYS_DELETED, "0")), summary.get(OFFSETS), summary.get(GAP_AFTER),
                    snapshot.timestampMillis());
        }
    }

    private final Table table;
    private final SourceTable source;
    private final Schema schema;
    private final Schema keySchema;
    /** For each column of {@link #keySchema}, its position in a change's values. */
    private final int[] keyColumns;
    private final GenericFileWriterFactory writers;
    private final long targetFileBytes;
    /** The last snapshot this writer saw as its own; every commit checks nothing landed after it. */
    private Long base;
    private int attempt;

    private IcebergCopy(Table table, SourceTable source) {
        this.table = table;
        this.source = source;
        this.schema = table.schema();
        this.keySchema = schema.select(source.primaryKey());
        this.keyColumns = keySchema.columns().stream()
                .mapToInt(f -> schema.columns().indexOf(schema.findField(f.fieldId()))).toArray();
        this.writers = new GenericFileWriterFactory.Builder(table)
                .dataFileFormat(FileFormat.PARQUET)
                .deleteFileFormat(FileFormat.PARQUET)
                .equalityFieldIds(keySchema.columns().stream().mapToInt(Types.NestedField::fieldId).toArray())
                .equalityDeleteRowSchema(keySchema)
                .build();
        this.targetFileBytes = PropertyUtil.propertyAsLong(table.properties(),
                TableProperties.WRITE_TARGET_FILE_SIZE_BYTES, TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT);
        Snapshot current = table.currentSnapshot();
        this.base = current == null ? null : current.snapshotId();
    }

    /**
     * Loads the copy, or creates it from the SingleStore table: same columns in the same order, the primary key as
     * Iceberg's identifier fields. Columns added at the end of the SingleStore table since are added to the copy;
     * any other difference stops the feed.
     */
    static IcebergCopy open(Catalog catalog, FeedConfig config, SourceTable source) {
        Table table;
        try {
            table = catalog.loadTable(config.icebergTable());
        } catch (NoSuchTableException e) {
            table = create(catalog, config, source);
        }
        String writtenFrom = table.properties().get(SOURCE);
        if (!config.source().equals(writtenFrom)) {
            throw new IllegalStateException(config.icebergTable() + " is not the change-feed copy of " + config.source()
                    + " (its " + SOURCE + " is " + writtenFrom + ")");
        }
        evolve(table, source);
        if (table.specs().size() != 1) {
            throw new IllegalStateException(config.icebergTable() + " has " + table.specs().size() + " partition specs; "
                    + "an equality delete applies only to files of its own spec, so the copy keeps one");
        }
        Set<String> key = Set.copyOf(source.primaryKey());
        for (PartitionField field : table.spec().fields()) {
            String column = table.schema().findColumnName(field.sourceId());
            if (!key.contains(column)) {
                throw new IllegalStateException(config.icebergTable() + " is partitioned on " + column + ", which is not "
                        + "in the primary key " + source.primaryKey() + ": a delete carries only the key, so its "
                        + "partition would be unknown");
            }
        }
        return new IcebergCopy(table, source);
    }

    private static Table create(Catalog catalog, FeedConfig config, SourceTable source) {
        List<Types.NestedField> fields = new ArrayList<>();
        int id = 1;
        for (SourceTable.Column column : source.columns()) {
            fields.add(Types.NestedField.builder().withId(id++).isOptional(column.nullable()).withName(column.name())
                    .ofType(column.icebergType()).build());
        }
        Set<Integer> keyIds = source.primaryKey().stream()
                .map(name -> fields.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow().fieldId())
                .collect(Collectors.toSet());
        Schema schema = new Schema(fields, keyIds);
        if (catalog instanceof SupportsNamespaces namespaces) {
            String[] levels = config.icebergTable().namespace().levels();
            for (int i = 1; i <= levels.length; i++) {
                Namespace namespace = Namespace.of(Arrays.copyOf(levels, i));
                if (!namespaces.namespaceExists(namespace)) {
                    namespaces.createNamespace(namespace);
                }
            }
        }
        Map<String, String> properties = new HashMap<>();
        properties.put(TableProperties.FORMAT_VERSION, String.valueOf(config.formatVersion()));
        properties.put(TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES, ROW_GROUP_BYTES);
        properties.put(TableProperties.DELETE_PARQUET_ROW_GROUP_SIZE_BYTES, ROW_GROUP_BYTES);
        properties.put(SOURCE, config.source());
        LOG.info("creating {} from {}", config.icebergTable(), config.source());
        return catalog.buildTable(config.icebergTable(), schema)
                .withPartitionSpec(partitionSpec(schema, config.partitionBy()))
                .withProperties(properties)
                .create();
    }

    private static final Pattern TRANSFORM = Pattern.compile("(\\w+)\\(\\s*(\\w+)\\s*(?:,\\s*(\\d+)\\s*)?\\)|(\\w+)");

    /** {@code day(trade_ts), bucket(symbol, 8)}: identity, year, month, day, hour, bucket and truncate. */
    static PartitionSpec partitionSpec(Schema schema, String partitionBy) {
        PartitionSpec.Builder spec = PartitionSpec.builderFor(schema);
        if (partitionBy.isEmpty()) {
            return spec.build();
        }
        for (String part : partitionBy.split(",(?![^(]*\\))")) {
            Matcher m = TRANSFORM.matcher(part.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("cannot read partition " + part.strip());
            }
            if (m.group(4) != null) {
                spec.identity(m.group(4));
                continue;
            }
            String column = m.group(2);
            switch (m.group(1)) {
                case "year" -> spec.year(column);
                case "month" -> spec.month(column);
                case "day" -> spec.day(column);
                case "hour" -> spec.hour(column);
                case "bucket" -> spec.bucket(column, Integer.parseInt(m.group(3)));
                case "truncate" -> spec.truncate(column, Integer.parseInt(m.group(3)));
                default -> throw new IllegalArgumentException("unknown partition transform " + m.group(1));
            }
        }
        return spec.build();
    }

    private static void evolve(Table table, SourceTable source) {
        List<Types.NestedField> copy = table.schema().columns();
        if (copy.size() > source.columns().size()) {
            throw new IllegalStateException(table.name() + " has columns SingleStore no longer has: " + copy);
        }
        for (int i = 0; i < copy.size(); i++) {
            SourceTable.Column column = source.columns().get(i);
            Types.NestedField field = copy.get(i);
            if (!field.name().equals(column.name()) || !field.type().equals(column.icebergType())
                    || field.isOptional() != column.nullable()) {
                throw new IllegalStateException(table.name() + " column " + (i + 1) + " is " + field + " but SingleStore's is "
                        + column.name() + " " + column.icebergType() + (column.nullable() ? "" : " not null")
                        + "; only columns added at the end are carried over");
            }
        }
        if (!table.schema().identifierFieldNames().equals(Set.copyOf(source.primaryKey()))) {
            throw new IllegalStateException(table.name() + " is keyed on " + table.schema().identifierFieldNames()
                    + " but SingleStore's primary key is " + source.primaryKey());
        }
        if (copy.size() == source.columns().size()) {
            return;
        }
        UpdateSchema update = table.updateSchema();
        for (SourceTable.Column column : source.columns().subList(copy.size(), source.columns().size())) {
            if (!column.nullable()) {
                throw new IllegalStateException(source.table() + "." + column.name() + " was added NOT NULL; rows "
                        + "already in Iceberg would have no value for it");
            }
            update.addColumn(column.name(), column.icebergType());
        }
        LOG.info("adding {} to {}", source.columns().subList(copy.size(), source.columns().size()).stream()
                .map(SourceTable.Column::name).toList(), table.name());
        update.commit();
    }

    /** The offsets the last commit covered, one per partition (null where none), or all null if never written. */
    String[] offsets() {
        String[] offsets = new String[source.partitions()];
        Snapshot current = table.currentSnapshot();
        if (current == null) {
            return offsets;
        }
        String recorded = current.summary().get(OFFSETS);
        if (recorded == null) {
            throw new IllegalStateException(table.name() + " snapshot " + current.snapshotId()
                    + " was not committed by the change feed: it records no offsets");
        }
        String[] parts = recorded.split(",", -1);
        if (parts.length != offsets.length) {
            throw new IllegalStateException(table.name() + " records " + parts.length + " offsets but "
                    + source.database() + " has " + offsets.length + " partitions");
        }
        for (int i = 0; i < parts.length; i++) {
            offsets[i] = parts[i].isEmpty() ? null : parts[i];
        }
        return offsets;
    }

    /**
     * Receipts of the feed's commits after snapshot {@code after}, oldest first: what a failed pipeline batch may have
     * lost. All the commits still in the table's history when {@code after} is null or no longer in it.
     */
    List<Receipt> receiptsAfter(Long after) {
        List<Receipt> receipts = new ArrayList<>();
        Snapshot snapshot = table.currentSnapshot();
        while (snapshot != null && (after == null || snapshot.snapshotId() != after)) {
            if (snapshot.summary().containsKey(KIND)) {
                receipts.add(Receipt.of(snapshot));
            }
            snapshot = snapshot.parentId() == null ? null : table.snapshot(snapshot.parentId());
        }
        return receipts.reversed();
    }

    /** A writer for a snapshot's rows, whose files replace the whole copy when committed. */
    SnapshotWriter snapshotWriter() {
        return new SnapshotWriter();
    }

    final class SnapshotWriter {
        private final FanoutDataWriter<Record> data;
        private final PartitionKey partition = new PartitionKey(table.spec(), schema);
        private final InternalRecordWrapper wrapper = new InternalRecordWrapper(schema.asStruct());
        private long rows;

        private SnapshotWriter() {
            data = new FanoutDataWriter<>(writers, outputFiles(UUID.randomUUID().toString()), table.io(), targetFileBytes);
        }

        void write(Object[] values) {
            Record record = record(values);
            data.write(record, table.spec(), partitionOf(partition, wrapper, record));
            rows++;
        }

        /**
         * Replaces everything in the copy with the snapshot's rows. {@code gapAfter} is the offsets the copy had when
         * SingleStore no longer held them (a re-snapshot), or null for the first snapshot.
         */
        Receipt commit(String[] offsets, String[] gapAfter) {
            close(data);
            OverwriteFiles overwrite = table.newOverwrite().overwriteByRowFilter(Expressions.alwaysTrue());
            data.result().dataFiles().forEach(overwrite::addFile);
            // Without a starting snapshot, Iceberg checks the table's whole history: an empty table must still be empty.
            if (base != null) {
                overwrite.validateFromSnapshot(base);
            }
            overwrite.conflictDetectionFilter(Expressions.alwaysTrue()).validateNoConflictingData().validateNoConflictingDeletes();
            if (gapAfter != null) {
                overwrite.set(GAP_AFTER, join(gapAfter));
            }
            return IcebergCopy.this.commit(overwrite, UUID.randomUUID().toString(), gapAfter == null ? "snapshot" : "resnapshot",
                    offsets, rows, 0);
        }

        void abort() {
            close(data);
        }
    }

    /** Commits a batch: new rows as data files, earlier rows of updated and deleted keys removed by equality deletes. */
    Receipt commit(ChangeBatch batch) {
        String commitId = UUID.randomUUID().toString();
        OutputFileFactory files = outputFiles(commitId);
        FanoutDataWriter<Record> data = new FanoutDataWriter<>(writers, files, table.io(), targetFileBytes);
        PartitionKey rowPartition = new PartitionKey(table.spec(), schema);
        InternalRecordWrapper rowWrapper = new InternalRecordWrapper(schema.asStruct());
        PartitionKey keyPartition = new PartitionKey(table.spec(), keySchema);
        InternalRecordWrapper keyWrapper = new InternalRecordWrapper(keySchema.asStruct());
        // The clustered writer wants each partition's deletes together.
        Map<StructLike, List<Record>> deletes = new LinkedHashMap<>();
        long rows = 0;
        long deletedKeys = 0;
        for (Change change : batch.changes()) {
            if (!change.deleted()) {
                Record record = record(change.values());
                data.write(record, table.spec(), partitionOf(rowPartition, rowWrapper, record));
                rows++;
            }
            if (change.replacesEarlier()) {
                Record key = GenericRecord.create(keySchema);
                for (int i = 0; i < keyColumns.length; i++) {
                    key.set(i, change.values()[keyColumns[i]]);
                }
                StructLike partition = partitionOf(keyPartition, keyWrapper, key);
                deletes.computeIfAbsent(partition, p -> new ArrayList<>()).add(key);
                if (change.deleted()) {
                    deletedKeys++;
                }
            }
        }
        close(data);
        List<DeleteFile> deleteFiles = List.of();
        if (!deletes.isEmpty()) {
            ClusteredEqualityDeleteWriter<Record> writer =
                    new ClusteredEqualityDeleteWriter<>(writers, files, table.io(), targetFileBytes);
            deletes.forEach((partition, keys) -> keys.forEach(key -> writer.write(key, table.spec(), partition)));
            close(writer);
            DeleteWriteResult result = writer.result();
            deleteFiles = result.deleteFiles();
        }
        RowDelta delta = table.newRowDelta();
        List<DataFile> dataFiles = data.result().dataFiles();
        dataFiles.forEach(delta::addRows);
        deleteFiles.forEach(delta::addDeletes);
        if (base != null) {
            delta.validateFromSnapshot(base);
        }
        delta.conflictDetectionFilter(Expressions.alwaysTrue()).validateNoConflictingDataFiles().validateNoConflictingDeleteFiles();
        return commit(delta, commitId, "changes", batch.offsets(), rows, deletedKeys);
    }

    /**
     * Commits with the offsets and a commit id in the summary. If the commit's answer is lost or it fails, the table
     * is read back: a snapshot carrying the commit id means it landed. Otherwise the failure stands, the task stops,
     * and the next start resumes from the offsets the table holds.
     */
    private Receipt commit(SnapshotUpdate<?> update, String commitId, String kind, String[] offsets, long rows, long keysDeleted) {
        update.set(OFFSETS, join(offsets));
        update.set(COMMIT_ID, commitId);
        update.set(KIND, kind);
        update.set(ROWS, String.valueOf(rows));
        update.set(KEYS_DELETED, String.valueOf(keysDeleted));
        try {
            update.commit();
            base = table.currentSnapshot().snapshotId();
        } catch (RuntimeException e) {
            Long landed = landed(commitId);
            if (landed == null) {
                throw e;
            }
            LOG.warn("commit {} to {} failed with {} but landed as snapshot {}", commitId, table.name(), e, landed);
            base = landed;
        }
        return Receipt.of(table.snapshot(base));
    }

    private Long landed(String commitId) {
        table.refresh();
        Snapshot snapshot = table.currentSnapshot();
        for (int i = 0; snapshot != null && i < 20; i++) {
            if (commitId.equals(snapshot.summary().get(COMMIT_ID))) {
                return snapshot.snapshotId();
            }
            snapshot = snapshot.parentId() == null ? null : table.snapshot(snapshot.parentId());
        }
        return null;
    }

    private OutputFileFactory outputFiles(String operationId) {
        return OutputFileFactory.builderFor(table, 1, ++attempt).format(FileFormat.PARQUET).operationId(operationId).build();
    }

    private Record record(Object[] values) {
        Record record = GenericRecord.create(schema);
        for (int i = 0; i < values.length; i++) {
            record.set(i, values[i]);
        }
        return record;
    }

    private static StructLike partitionOf(PartitionKey key, InternalRecordWrapper wrapper, Record record) {
        if (key.size() == 0) {
            return null;
        }
        key.partition(wrapper.wrap(record));
        return key.copy();
    }

    private static String join(String[] offsets) {
        return Arrays.stream(offsets).map(o -> o == null ? "" : o).collect(Collectors.joining(","));
    }

    private static void close(java.io.Closeable writer) {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
