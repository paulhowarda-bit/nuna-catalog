package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.audittables.EventsReader.Busy;
import nuna.audittables.EventsReader.Cursor;
import nuna.audittables.EventsReader.Page;
import nuna.audittables.EventsReader.Query;
import nuna.audittables.EventsReader.Summary;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.LogStamp;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.io.FanoutDataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.junit.jupiter.api.Test;

/**
 * The audit table's read side (D45, backlog 125) against Iceberg's in-memory catalog, the table written by the
 * writer's own {@link EventsTable}: a catalog's records newest first, filtered, a page at a time with no row given
 * twice or skipped, also when a page runs out of its budget, and the counts of a span.
 */
class EventsReaderTest {

    private static final String TOPIC = "nuna.catalog.audit";
    private static final Namespace NAMESPACE = Namespace.of("audit");
    private static final Instant T0 = Instant.parse("2026-11-21T07:00:00Z");
    private static final String READ = "nuna.catalog.read";
    private static final String COMMIT = "nuna.catalog.commit";
    private static final String DENIAL = "nuna.catalog.denial";
    private static final UUID RUN = UUID.fromString("a0000000-0000-4000-8000-000000000272");

    private final FaultyCatalog catalog = new FaultyCatalog();
    private final EventsTable events = EventsTable.open(catalog, NAMESPACE, TOPIC);
    private final Map<Integer, Long> offsets = new HashMap<>();

    @Test
    void givesACatalogsRecordsNewestFirstByTimeThenPartitionThenOffset() {
        AuditRow a = row(10, READ, "ana", "lake", 0, List.of("sales", "orders"));
        AuditRow b = row(30, COMMIT, "svc", "lake", 1, List.of("sales", "orders"));
        AuditRow c = row(30, READ, "ana", "lake", 2, List.of("sales", "orders"));
        AuditRow d = row(20, READ, "ana", "other", 0, List.of("hr", "staff"));
        AuditRow e = row(30, DENIAL, "tom", "lake", 2, List.of("sales"));
        append(a, b);
        append(c, d, e);

        Page page = reader(EventsReader.DEFAULT_SCAN_BUDGET).page(query(50));

        assertThat(page.rows()).containsExactly(e, c, b, a);
        assertThat(page.next()).isEmpty();
        assertThat(page.searchedTo()).isEmpty();
        assertThat(page.rows().getFirst().data()).as("the whole record, its payload included").isEqualTo(e.data());
    }

    @Test
    void pagesGiveEveryRowOnceAndInOrderWhileTheBudgetLasts() {
        List<AuditRow> all = randomRows(5, 37, new Random(7));

        assertThat(readAll(reader(EventsReader.DEFAULT_SCAN_BUDGET), query(7))).containsExactlyElementsOf(newestFirst(all));
    }

    @Test
    void pagesGiveEveryRowOnceAndInOrderWhenEachPageRunsOutOfBudget() {
        List<AuditRow> all = randomRows(6, 25, new Random(11));

        List<AuditRow> read = readAll(reader(10), query(9));

        assertThat(read).containsExactlyElementsOf(newestFirst(all));
    }

    @Test
    void aPageOutOfBudgetSaysHowFarItSearchedAndTheNextSearchesOn() {
        // One rare record, oldest of all, under many records about another table, each batch a file of its own. The
        // path is no column's value, so no file or row group is passed over for it: every row is read.
        AuditRow rare = row(1, DENIAL, "tom", "lake", 0, List.of("hr", "staff"));
        append(rare);
        for (int file = 0; file < 4; file++) {
            List<AuditRow> batch = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                batch.add(row(100 + file * 100 + i, READ, "svc", "lake", i % 3, List.of("sales", "orders")));
            }
            append(batch.toArray(AuditRow[]::new));
        }
        EventsReader reader = reader(25);
        Query toms = new Query("lake", Set.of(), null, List.of("hr"), null, null, Optional.empty(), 50);

        Page first = reader.page(toms);

        assertThat(first.rows()).isEmpty();
        assertThat(first.searchedTo()).isPresent();
        assertThat(first.next()).isPresent();
        List<AuditRow> found = readAll(reader, toms);
        assertThat(found).containsExactly(rare);
    }

    @Test
    void anObjectsRecordsAreThoseWithItsIdWhateverItWasCalled() {
        // An object's Audit tab (backlog 197): by its id, so its records before a rename are its own, and another
        // table later given its old name is not.
        UUID orders = UUID.randomUUID();
        AuditRow before = row(10, READ, "ana", "lake", 0, List.of("sales", "orders"), orders);
        AuditRow after = row(12, COMMIT, "svc", "lake", 1, List.of("sales", "orders_v1"), orders);
        AuditRow namesake = row(13, READ, "ana", "lake", 0, List.of("sales", "orders"), UUID.randomUUID());
        AuditRow elsewhere = row(14, READ, "ana", "pond", 2, List.of("sales", "orders_v1"), orders);
        append(before, after, namesake, elsewhere);
        EventsReader reader = reader(EventsReader.DEFAULT_SCAN_BUDGET);

        Page page = reader.page(new Query("lake", Set.of(), null, List.of(), null, null, Optional.empty(), 50,
                orders.toString()));

        assertThat(page.rows()).containsExactly(after, before);
        assertThat(page.next()).isEmpty();
    }

    @Test
    void filtersByTypePrincipalPathAndTime() {
        AuditRow orders = row(10, READ, "ana", "lake", 0, List.of("Sales", "Orders"));
        AuditRow refunds = row(11, COMMIT, "svc", "lake", 0, List.of("sales", "eu", "refunds"));
        AuditRow namespace = row(12, COMMIT, "svc", "lake", 1, List.of("sales"));
        AuditRow sibling = row(13, READ, "ana", "lake", 1, List.of("sales2", "orders"));
        AuditRow denied = row(14, DENIAL, "tom", "lake", 2, List.of("sales", "orders"));
        AuditRow nothing = row(15, "nuna.catalog.auth-failure", null, "lake", 2, null);
        append(orders, refunds, namespace, sibling, denied, nothing);
        EventsReader reader = reader(EventsReader.DEFAULT_SCAN_BUDGET);

        assertThat(reader.page(new Query("lake", Set.of(READ, DENIAL), null, List.of(), null, null, Optional.empty(), 50))
                .rows()).containsExactly(denied, sibling, orders);
        assertThat(reader.page(new Query("lake", Set.of(), "svc", List.of(), null, null, Optional.empty(), 50))
                .rows()).containsExactly(namespace, refunds);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of("SALES"), null, null, Optional.empty(), 50))
                .rows()).as("a namespace, everything in it at any depth, compared as names are")
                .containsExactly(denied, namespace, refunds, orders);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of("sales", "orders"), null, null,
                Optional.empty(), 50)).rows()).containsExactly(denied, orders);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of(), at(11), at(14), Optional.empty(), 50))
                .rows()).as("from inclusive, to exclusive").containsExactly(sibling, namespace, refunds);
    }

    @Test
    void anEmptyTableHasNoPage() {
        assertThat(reader(EventsReader.DEFAULT_SCAN_BUDGET).page(query(50)).rows()).isEmpty();
        assertThat(reader(EventsReader.DEFAULT_SCAN_BUDGET).snapshotId()).isEmpty();
    }

    @Test
    void countsASpanByTypeInTheDeploymentAndEachCatalogWithItsBusiestObjects() {
        UUID orders = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        append(row(5, READ, "ana", "lake", 0, List.of("sales", "orders"), orders),
                row(10, READ, "ana", "lake", 0, List.of("sales", "orders"), orders),
                row(11, READ, "bob", "lake", 1, List.of("sales", "orders"), orders),
                row(12, COMMIT, "svc-etl", "lake", 1, List.of("sales", "orders"), orders),
                row(13, COMMIT, "svc-etl", "lake", 1, List.of("sales", "orders"), orders));
        append(row(14, COMMIT, "ana", "lake", 2, List.of("sales", "orders"), orders),
                row(15, DENIAL, "tom", "lake", 2, List.of("hr", "staff"), staff),
                row(16, READ, "ana", "Other", 0, List.of("x", "y"), UUID.randomUUID()),
                row(17, "nuna.catalog.auth-failure", null, null, 0, null, null),
                row(99, READ, "ana", "lake", 0, List.of("sales", "orders"), orders));

        Summary summary = reader(EventsReader.DEFAULT_SCAN_BUDGET).summary(at(10), at(99), 1);

        assertThat(summary.byType()).containsExactlyInAnyOrderEntriesOf(Map.of(READ, 3L, COMMIT, 3L, DENIAL, 1L,
                "nuna.catalog.auth-failure", 1L));
        assertThat(summary.catalogs()).containsOnlyKeys("lake", "other");
        assertThat(summary.catalogs().get("lake").byType()).containsExactlyInAnyOrderEntriesOf(Map.of(READ, 2L,
                COMMIT, 3L, DENIAL, 1L));
        assertThat(summary.catalogs().get("lake").busiest()).containsExactly(new Busy("TABLE", orders.toString(),
                List.of("sales", "orders"), 5, 2, "svc-etl"));
    }

    @Test
    void aPageTokenNamesItsCursorAndNothingElseIsOne() {
        Cursor cursor = new Cursor(1_763_708_400_000_000L, 3, 18_224_113L);

        assertThat(Cursor.parse(cursor.token())).isEqualTo(cursor);
        assertThatThrownBy(() -> Cursor.parse("not-a-token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsNoFileOutsideTheTablesLocation() {
        InMemoryFileIO io = new InMemoryFileIO();
        io.addFile("s3://audit/nuna_audit/events-1/data/a.parquet", new byte[] {1});
        io.addFile("s3://warehouse/lake/orders/data/b.parquet", new byte[] {2});
        EventsReader.Confined confined = new EventsReader.Confined(io, "s3://audit/nuna_audit/events-1");

        assertThat(confined.newInputFile("s3://audit/nuna_audit/events-1/data/a.parquet").exists()).isTrue();
        assertThatThrownBy(() -> confined.newInputFile("s3://warehouse/lake/orders/data/b.parquet"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not under its location");
        assertThatThrownBy(() -> confined.newInputFile("s3://audit/nuna_audit/events-10/data/c.parquet", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> confined.newInputFile("s3://audit/nuna_audit/events-1/../events-2/d.parquet"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers ----

    @Test
    void aRowGivesTheRunThatLoggedItAndItsNumberThere() {
        AuditRow topic = row(10, READ, "ana", "lake", 0, List.of("sales", "orders"));
        AuditRow logged = stamped(row(20, READ, "ana", "lake", -1, List.of("sales", "orders")), RUN, 7);
        append(topic, logged);

        Page page = reader(EventsReader.DEFAULT_SCAN_BUDGET).page(query(50));

        assertThat(page.rows()).containsExactly(logged, topic);
        assertThat(page.rows().getFirst().logStamp()).contains(new LogStamp(RUN, 7));
        assertThat(page.rows().getLast().logStamp()).as("an older server's copy carries no stamp").isEmpty();
    }

    @Test
    void aTableMadeBeforeTheLogColumnsIsGivenThemAndItsEarlierRowsReadNullThere() throws IOException {
        FaultyCatalog older = new FaultyCatalog();
        TableIdentifier id = TableIdentifier.of(NAMESPACE, EventsTable.NAME);
        older.createNamespace(NAMESPACE);
        Schema first = new Schema(EventsTable.SCHEMA.columns().stream()
                .filter(c -> !c.name().startsWith("log_")).toList());
        Table made = older.buildTable(id, first).withPartitionSpec(EventsTable.SPEC)
                .withProperty(TableProperties.FORMAT_VERSION, "2").create();
        // A row as the writer wrote it before backlog 272: a data file without the two columns.
        GenericRecord before = GenericRecord.create(made.schema());
        before.setField("event_id", "before-272");
        before.setField("time", at(10).atOffset(ZoneOffset.UTC));
        before.setField("type", READ);
        before.setField("source", "urn:nuna:deployment:test");
        before.setField("principal", "ana");
        before.setField("catalog", "lake");
        before.setField("data", "{}");
        before.setField("topic_partition", -1);
        before.setField("topic_offset", 1L);
        FanoutDataWriter<org.apache.iceberg.data.Record> files = new FanoutDataWriter<>(
                new GenericFileWriterFactory.Builder(made).dataFileFormat(FileFormat.PARQUET).build(),
                OutputFileFactory.builderFor(made, 1, 1).format(FileFormat.PARQUET).build(), made.io(), 1 << 20);
        PartitionKey partition = new PartitionKey(made.spec(), made.schema());
        partition.partition(new InternalRecordWrapper(made.schema().asStruct()).wrap(before));
        try (files) {
            files.write(before, made.spec(), partition.copy());
        }
        AppendFiles append = made.newAppend();
        files.result().dataFiles().forEach(append::appendFile);
        append.set(EventsTable.OFFSETS + LogWriter.SOURCE, "-1:2").commit();

        EventsTable opened = EventsTable.open(older, NAMESPACE, LogWriter.SOURCE);
        assertThat(older.loadTable(id).schema().findField("log_run").fieldId()).isEqualTo(20);
        assertThat(older.loadTable(id).schema().findField("log_seq").fieldId()).isEqualTo(21);
        assertThat(EventsTable.open(older, NAMESPACE, LogWriter.SOURCE)).as("opened again as it is").isNotNull();
        AuditRow after = stamped(row(20, READ, "ana", "lake", -1, List.of("sales", "orders")), RUN, 3);
        opened.reload();
        opened.append(List.of(after), Map.of(-1, 3L));

        Page page = EventsReader.of(older.loadTable(id), EventsReader.DEFAULT_SCAN_BUDGET).page(query(50));

        assertThat(page.rows()).extracting(AuditRow::eventId).containsExactly(after.eventId(), "before-272");
        assertThat(page.rows().getFirst().logStamp()).contains(new LogStamp(RUN, 3));
        assertThat(page.rows().getLast().logRun()).isNull();
        assertThat(page.rows().getLast().logSequence()).isNull();
    }

    private static AuditRow stamped(AuditRow row, UUID run, long number) {
        return new AuditRow(row.eventId(), row.time(), row.type(), row.source(), row.principal(), row.endUser(),
                row.engine(), row.requestId(), row.catalog(), row.objectKind(), row.objectId(), row.objectPath(),
                row.operation(), row.privilege(), row.reason(), row.data(), row.topicPartition(), row.topicOffset(),
                run.toString(), number);
    }

    private EventsReader reader(long budget) {
        Table table = catalog.loadTable(TableIdentifier.of(NAMESPACE, EventsTable.NAME));
        return EventsReader.of(table, budget);
    }

    private static Query query(int limit) {
        return new Query("lake", Set.of(), null, List.of(), null, null, Optional.empty(), limit);
    }

    /** Every page, following the cursors, a bounded number of times. */
    private static List<AuditRow> readAll(EventsReader reader, Query first) {
        List<AuditRow> read = new ArrayList<>();
        Query query = first;
        for (int pages = 0; pages < 1000; pages++) {
            Page page = reader.page(query);
            read.addAll(page.rows());
            if (page.next().isEmpty()) {
                return read;
            }
            query = new Query(query.catalog(), query.types(), query.principal(), query.path(), query.from(),
                    query.to(), page.next(), query.limit());
        }
        throw new AssertionError("the feed did not end within 1,000 pages");
    }

    /** {@code files} appends of {@code each} rows, times drawn so some repeat across partitions. */
    private List<AuditRow> randomRows(int files, int each, Random random) {
        List<AuditRow> all = new ArrayList<>();
        for (int file = 0; file < files; file++) {
            List<AuditRow> batch = new ArrayList<>();
            for (int i = 0; i < each; i++) {
                batch.add(row(random.nextInt(60), READ, "ana", "lake", random.nextInt(3), List.of("sales", "orders")));
            }
            append(batch.toArray(AuditRow[]::new));
            all.addAll(batch);
        }
        return all;
    }

    private static List<AuditRow> newestFirst(List<AuditRow> rows) {
        return rows.stream().sorted(Comparator.comparing(AuditRow::time).thenComparingInt(AuditRow::topicPartition)
                .thenComparingLong(AuditRow::topicOffset).reversed()).toList();
    }

    /** One commit, one file per day's partition. */
    private void append(AuditRow... rows) {
        events.reload();
        events.append(List.of(rows), Map.copyOf(offsets));
    }

    private AuditRow row(int second, String type, String principal, String catalogName, int partition,
                         List<String> path) {
        return row(second, type, principal, catalogName, partition, path, path == null ? null : UUID.randomUUID());
    }

    private AuditRow row(int second, String type, String principal, String catalogName, int partition,
                         List<String> path, UUID object) {
        long offset = offsets.merge(partition, 1L, Long::sum) - 1;
        String id = UUID.randomUUID().toString();
        return new AuditRow(id, at(second), type, "urn:nuna:deployment:test", principal, null,
                principal == null ? null : "spark", "request-" + id, catalogName,
                path == null ? null : "TABLE", object == null ? null : object.toString(), path, null, null, null,
                "{\"n\":\"" + id + "\"}", partition, offset, null, null);
    }

    private static Instant at(int second) {
        return T0.plusSeconds(second);
    }
}
