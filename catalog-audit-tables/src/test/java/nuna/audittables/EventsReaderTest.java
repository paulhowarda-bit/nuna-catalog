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
import java.util.Arrays;
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
        Query toms = new Query("lake", Set.of(), null, List.of("hr"), null, null, null, null, Optional.empty(), 50);

        Page first = reader.page(toms);

        assertThat(first.rows()).isEmpty();
        assertThat(first.searchedTo()).isPresent();
        assertThat(first.next()).isPresent();
        List<AuditRow> found = readAll(reader, toms);
        assertThat(found).containsExactly(rare);
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

        assertThat(reader.page(new Query("lake", Set.of(READ, DENIAL), null, List.of(), null, null, null, null,
                Optional.empty(), 50)).rows()).containsExactly(denied, sibling, orders);
        assertThat(reader.page(new Query("lake", Set.of(), "svc", List.of(), null, null, null, null,
                Optional.empty(), 50)).rows()).containsExactly(namespace, refunds);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of("SALES"), null, null, null, null,
                Optional.empty(), 50)).rows()).as("a namespace, everything in it at any depth, compared as names are")
                .containsExactly(denied, namespace, refunds, orders);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of("sales", "orders"), null, null, null, null,
                Optional.empty(), 50)).rows()).containsExactly(denied, orders);
        assertThat(reader.page(new Query("lake", Set.of(), null, List.of(), null, null, at(11), at(14),
                Optional.empty(), 50)).rows()).as("from inclusive, to exclusive").containsExactly(sibling, namespace,
                refunds);
    }

    @Test
    void filtersByTheRefARecordWentThroughOrIsAbout() {
        // Backlog 169: commits and reads name the ref in branch, and a ref record its ref's name.
        AuditRow onMain = through(row(10, READ, "ana", "lake", 0, List.of("sales", "orders")), "main");
        AuditRow read = through(row(11, READ, "ana", "lake", 0, List.of("sales", "orders")), "Q3-Close");
        AuditRow commit = through(row(12, COMMIT, "svc", "lake", 1, List.of("sales", "orders")), "q3-close");
        AuditRow made = about(row(13, "nuna.catalog.ref", "ana", "lake", 1, List.of()), "Q3-close", UUID.randomUUID());
        AuditRow other = through(row(14, READ, "ana", "lake", 2, List.of("sales", "orders")), "q4");
        AuditRow grant = row(15, "nuna.catalog.grant-change", "gina", "lake", 2, List.of());
        AuditRow elsewhere = through(row(16, READ, "ana", "other", 2, List.of("sales", "orders")), "q3-close");
        append(onMain, read, commit, made, other, grant, elsewhere);
        EventsReader reader = reader(EventsReader.DEFAULT_SCAN_BUDGET);

        assertThat(reader.page(byBranch("q3-close")).rows())
                .as("through the ref or about it, compared as names are, in this catalog only")
                .containsExactly(made, commit, read);
        assertThat(reader.page(byBranch("MAIN")).rows()).containsExactly(onMain);
        assertThat(reader.page(byBranch("q5")).rows()).isEmpty();
        assertThat(readAll(reader(2), byBranch("Q3-CLOSE"))).as("every page, a budget of two rows each")
                .containsExactly(made, commit, read);
    }

    @Test
    void filtersByTheObjectOrRefARecordIsAbout() {
        // Backlog 169: a table's own records by object_id, and the ref records that name it in their tables (a table
        // taken out of a ref, item 156, or moved by a merge) by subject_ids.
        UUID orders = UUID.randomUUID();
        UUID tag = UUID.randomUUID();
        AuditRow read = row(10, READ, "ana", "lake", 0, List.of("sales", "orders"), orders);
        AuditRow otherTable = row(11, READ, "ana", "lake", 0, List.of("sales", "refunds"), UUID.randomUUID());
        AuditRow made = about(row(12, "nuna.catalog.ref", "ana", "lake", 1, List.of()), "q3", tag);
        AuditRow removed = about(row(13, "nuna.catalog.ref", "sam", "lake", 1, List.of()), "q3", tag, orders);
        AuditRow otherRef = about(row(14, "nuna.catalog.ref", "ana", "lake", 2, List.of()), "q4", UUID.randomUUID());
        AuditRow elsewhere = row(15, READ, "ana", "other", 2, List.of("sales", "orders"), orders);
        append(read, otherTable, made, removed, otherRef, elsewhere);
        EventsReader reader = reader(EventsReader.DEFAULT_SCAN_BUDGET);

        assertThat(reader.page(bySubject(orders)).rows()).containsExactly(removed, read);
        assertThat(reader.page(bySubject(tag)).rows()).containsExactly(removed, made);
        assertThat(reader.page(new Query("lake", Set.of("nuna.catalog.ref"), null, List.of(), null,
                orders.toString(), null, null, Optional.empty(), 50)).rows())
                .as("who took the table out of which ref, and when").singleElement().satisfies(row -> {
                    assertThat(row.principal()).isEqualTo("sam");
                    assertThat(row.branch()).isEqualTo("q3");
                    assertThat(row.subjectIds()).containsExactly(tag.toString(), orders.toString());
                });
        assertThat(reader.page(bySubject(UUID.randomUUID())).rows()).isEmpty();
        assertThat(readAll(reader(2), bySubject(orders))).containsExactly(removed, read);
    }

    private static Query byBranch(String branch) {
        return new Query("lake", Set.of(), null, List.of(), branch, null, null, null, Optional.empty(), 50);
    }

    private static Query bySubject(UUID subject) {
        return new Query("lake", Set.of(), null, List.of(), null, subject.toString(), null, null, Optional.empty(),
                50);
    }

    private static AuditRow through(AuditRow row, String branch) {
        return new AuditRow(row.eventId(), row.time(), row.type(), row.source(), row.principal(), row.endUser(),
                row.engine(), row.requestId(), row.catalog(), row.objectKind(), row.objectId(), row.objectPath(),
                row.operation(), row.privilege(), row.reason(), row.data(), row.topicPartition(), row.topicOffset(),
                row.logRun(), row.logSequence(), branch, null);
    }

    /** A ref record of the ref {@code ref}, named {@code name}, naming {@code tables}; its object is the catalog. */
    private static AuditRow about(AuditRow row, String name, UUID ref, UUID... tables) {
        List<String> subjects = new ArrayList<>(List.of(ref.toString()));
        Arrays.stream(tables).map(UUID::toString).forEach(subjects::add);
        return new AuditRow(row.eventId(), row.time(), row.type(), row.source(), row.principal(), row.endUser(),
                row.engine(), row.requestId(), row.catalog(), "CATALOG", UUID.randomUUID().toString(), List.of(),
                row.operation(), row.privilege(), row.reason(), row.data(), row.topicPartition(), row.topicOffset(),
                row.logRun(), row.logSequence(), name, subjects);
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
        long written = madeBefore(older, false, "before-272");

        EventsTable opened = EventsTable.open(older, NAMESPACE, LogWriter.SOURCE);
        assertThat(older.loadTable(id).schema().findField("log_run").fieldId()).isEqualTo(20);
        assertThat(older.loadTable(id).schema().findField("log_seq").fieldId()).isEqualTo(21);
        assertThat(older.loadTable(id).schema().findField("branch").fieldId()).isEqualTo(22);
        assertThat(older.loadTable(id).schema().findField("subject_ids").fieldId()).isEqualTo(23);
        assertThat(older.loadTable(id).currentSnapshot().snapshotId()).as("no file rewritten").isEqualTo(written);
        assertThat(EventsTable.open(older, NAMESPACE, LogWriter.SOURCE)).as("opened again as it is").isNotNull();
        AuditRow after = stamped(row(20, READ, "ana", "lake", -1, List.of("sales", "orders")), RUN, 3);
        opened.reload();
        opened.append(List.of(after), Map.of(-1, 3L));

        Page page = EventsReader.of(older.loadTable(id), EventsReader.DEFAULT_SCAN_BUDGET).page(query(50));

        assertThat(page.rows()).extracting(AuditRow::eventId).containsExactly(after.eventId(), "before-272");
        assertThat(page.rows().getFirst().logStamp()).contains(new LogStamp(RUN, 3));
        assertThat(page.rows().getLast().logRun()).isNull();
        assertThat(page.rows().getLast().logSequence()).isNull();
        assertThat(page.rows().getLast().branch()).isNull();
        assertThat(page.rows().getLast().subjectIds()).isNull();
    }

    @Test
    void aTableMadeBeforeTheRefColumnsIsGivenThemAndItsEarlierRowsMatchNoRefOrSubject() throws IOException {
        // Backlog 169: a table backlog 272 gave its log columns gains branch and subject_ids in place.
        FaultyCatalog older = new FaultyCatalog();
        TableIdentifier id = TableIdentifier.of(NAMESPACE, EventsTable.NAME);
        long written = madeBefore(older, true, "before-169");

        EventsTable opened = EventsTable.open(older, NAMESPACE, LogWriter.SOURCE);
        Table evolved = older.loadTable(id);
        assertThat(evolved.schema().findField("log_run").fieldId()).isEqualTo(20);
        assertThat(evolved.schema().findField("branch").fieldId()).isEqualTo(22);
        assertThat(evolved.schema().findField("subject_ids").fieldId()).isEqualTo(23);
        assertThat(evolved.schema().findField("subject_ids").type().asListType().elementId()).isEqualTo(24);
        assertThat(evolved.currentSnapshot().snapshotId()).as("no file rewritten").isEqualTo(written);
        UUID ref = UUID.randomUUID();
        AuditRow after = about(row(20, "nuna.catalog.ref", "ana", "lake", -1, List.of()), "q3", ref);
        opened.reload();
        opened.append(List.of(after), Map.of(-1, 3L));
        EventsReader reader = EventsReader.of(older.loadTable(id), EventsReader.DEFAULT_SCAN_BUDGET);

        assertThat(reader.page(query(50)).rows()).extracting(AuditRow::eventId)
                .containsExactly(after.eventId(), "before-169");
        assertThat(reader.page(query(50)).rows().getLast().branch()).isNull();
        assertThat(reader.page(byBranch("q3")).rows()).containsExactly(after);
        assertThat(reader.page(bySubject(ref)).rows()).containsExactly(after);
    }

    /**
     * Makes the table as the writer made it before backlog 272 ({@code logged} false) or before backlog 169, with one
     * row written then, and returns the snapshot that wrote it.
     */
    private static long madeBefore(FaultyCatalog older, boolean logged, String eventId) throws IOException {
        TableIdentifier id = TableIdentifier.of(NAMESPACE, EventsTable.NAME);
        older.createNamespace(NAMESPACE);
        Schema first = new Schema(EventsTable.SCHEMA.columns().stream().filter(c -> c.fieldId() < 20).toList());
        Table made = older.buildTable(id, first).withPartitionSpec(EventsTable.SPEC)
                .withProperty(TableProperties.FORMAT_VERSION, "2").create();
        if (logged) {
            made.updateSchema().addColumn("log_run", org.apache.iceberg.types.Types.StringType.get())
                    .addColumn("log_seq", org.apache.iceberg.types.Types.LongType.get()).commit();
            made.refresh();
        }
        // A row as the writer wrote it then: a data file without the later columns.
        GenericRecord before = GenericRecord.create(made.schema());
        before.setField("event_id", eventId);
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
        return older.loadTable(id).currentSnapshot().snapshotId();
    }

    private static AuditRow stamped(AuditRow row, UUID run, long number) {
        return new AuditRow(row.eventId(), row.time(), row.type(), row.source(), row.principal(), row.endUser(),
                row.engine(), row.requestId(), row.catalog(), row.objectKind(), row.objectId(), row.objectPath(),
                row.operation(), row.privilege(), row.reason(), row.data(), row.topicPartition(), row.topicOffset(),
                run.toString(), number, row.branch(), row.subjectIds());
    }

    private EventsReader reader(long budget) {
        Table table = catalog.loadTable(TableIdentifier.of(NAMESPACE, EventsTable.NAME));
        return EventsReader.of(table, budget);
    }

    private static Query query(int limit) {
        return new Query("lake", Set.of(), null, List.of(), null, null, null, null, Optional.empty(), limit);
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
            query = new Query(query.catalog(), query.types(), query.principal(), query.path(), query.branch(),
                    query.subject(), query.from(),
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
                "{\"n\":\"" + id + "\"}", partition, offset, null, null, null, null);
    }

    private static Instant at(int second) {
        return T0.plusSeconds(second);
    }
}
