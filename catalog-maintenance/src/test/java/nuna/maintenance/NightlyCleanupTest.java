package nuna.maintenance;

import static nuna.maintenance.WorkerMetricsTest.counter;
import static nuna.maintenance.WorkerMetricsTest.tableNeed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker in its nightly cleanup window (D30, amended 2026-11-15), its clock set by the test to the night of 27
 * March 2027 in London, when the clocks go forward: the window, 23:00 to 02:00, opens at 23:00 GMT and closes at
 * 02:00 BST, 01:00 UTC, an hour short.
 *
 * <ul>
 *   <li>Outside the window nothing heavy starts, no compaction and no orphan scan, and a worker restarted in the day
 *       starts neither; one restarted in the window finds tonight's Job and starts no second.</li>
 *   <li>In the window each table is assessed once a night; Jobs start for the needy tables only, equality deletes
 *       first and then the neediest, as many at once as the settings allow, each to stop at the window's end. The
 *       first scan after it counts the Jobs it cut off and the needy tables left over; the next night starts
 *       again. The catalog keeps each assessment, how each Job stands or ended, and each scan, as the worker
 *       reports them (D38).</li>
 *   <li>An orphan scan the window's end cuts short is not recorded, so the table is scanned the next night, though
 *       the orphan interval is two.</li>
 * </ul>
 */
class NightlyCleanupTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final CleanupWindow WINDOW = new CleanupWindow(LocalTime.of(23, 0), LocalTime.of(2, 0), LONDON);
    /** 23:00 GMT on 27 March 2027. */
    private static final Instant OPENS = Instant.parse("2027-03-27T23:00:00Z");
    /** 02:00 BST on 28 March 2027: the clocks went from 01:00 GMT to 02:00 BST an instant before. */
    private static final Instant CLOSES = Instant.parse("2027-03-28T01:00:00Z");
    /** 23:00 BST on 28 March 2027. */
    private static final Instant NEXT_OPENS = Instant.parse("2027-03-28T22:00:00Z");

    private final TestCatalog catalog = TestCatalog.get();
    private final FakeClock clock = new FakeClock();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    /** The tables {@link #compacted} made, by id. */
    private final Map<UUID, TableIdentifier> names = new HashMap<>();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void nothingHeavyStartsOutsideTheWindowNorAfterARestartInTheDay() throws Exception {
        StubCompactor compactor = new StubCompactor(Compactor.State.RUNNING);
        try (RESTCatalog writer = catalog.writer()) {
            TableIdentifier id = TestCatalog.table(writer, "nightly");
            Table table = writer.loadTable(id);
            appendSmallFiles(table, 5);
            String orphan = table.location() + "/data/orphan.parquet";
            catalog.put(orphan, "never committed");
            catalog.policy(id, Map.of("compaction.enabled", "true", "orphan-removal.enabled", "true",
                    "orphan-removal.older-than", "P8D"));
            UUID uuid = UUID.fromString(table.uuid().toString());

            try (Worker worker = worker(compactor, Storage.of(catalog.storageProfiles()), Duration.ofDays(1))) {
                for (Instant day : List.of(Instant.parse("2027-03-27T12:00:00Z"), OPENS.minusSeconds(1))) {
                    ScanReport report = at(day, worker);
                    assertThat(report.tablesAssessed).as("assessed at %s", day).isZero();
                    assertThat(report.orphanScans + report.orphanScansCutShort).as("scanned at %s", day).isZero();
                }
                assertThat(compactor.launched).isEmpty();
                assertThat(catalog.pending(uuid.toString())).isEmpty();

                ScanReport opened = at(OPENS, worker);
                assertThat(opened.tablesAssessed).isEqualTo(1);
                assertThat(opened.compactionsStarted).isEqualTo(1);
                assertThat(opened.orphanScans).isEqualTo(1);
                assertThat(catalog.pending(uuid.toString())).containsExactly(orphan);

                ScanReport later = at(OPENS.plus(Duration.ofMinutes(30)), worker);
                assertThat(later.tablesAssessed + later.compactionsStarted + later.orphanScans).as("once a night")
                        .isZero();
            }
            try (Worker restarted = worker(compactor, Storage.of(catalog.storageProfiles()), Duration.ofDays(1))) {
                ScanReport report = at(Instant.parse("2027-03-28T00:30:00Z"), restarted);
                assertThat(report.tablesAssessed).as("restarted in the window: assessed again").isEqualTo(1);
                assertThat(report.compactionsStarted).as("tonight's Job is there").isZero();
                assertThat(compactor.launches(uuid)).isEqualTo(1);
            }
            try (Worker restarted = worker(compactor, Storage.of(catalog.storageProfiles()), Duration.ofDays(1))) {
                ScanReport report = at(Instant.parse("2027-03-28T12:00:00Z"), restarted);
                assertThat(report.tablesAssessed).as("restarted in the day").isZero();
                assertThat(report.orphanScans + report.orphanScansCutShort).isZero();
                assertThat(compactor.launches(uuid)).isEqualTo(1);
            }
        }
    }

    @Test
    void theNeediestStartFirstAndTheWindowsEndStopsThem() throws Exception {
        StubCompactor compactor = new StubCompactor(Compactor.State.RUNNING);
        try (RESTCatalog writer = catalog.writer();
             Worker worker = worker(compactor, Storage.of(catalog.storageProfiles()), Duration.ofDays(1))) {
            // Its four small files count for nothing at a threshold of 100, its one delete file is a quarter of its
            // data files, under the share: its equality delete alone puts it first.
            Table withDeletes = compacted(writer, "equality", 4, Map.of("compaction.min-input-files", "100"));
            String deletes = withDeletes.location() + "/data/equality-deletes.parquet";
            catalog.put(deletes, "id = 1");
            withDeletes.newRowDelta().addDeletes(FileMetadata.deleteFileBuilder(withDeletes.spec())
                    .ofEqualityDeletes(1).withFormat(FileFormat.PARQUET).withPath(deletes).withFileSizeInBytes(6)
                    .withRecordCount(1).build()).commit();
            UUID equality = id(withDeletes);
            UUID many = id(compacted(writer, "many_small", 10, Map.of()));
            UUID some = id(compacted(writer, "some_small", 6, Map.of()));
            UUID fine = id(compacted(writer, "fine", 1, Map.of()));

            ScanReport opened = at(OPENS, worker);
            assertThat(opened.tablesAssessed).isEqualTo(4);
            assertThat(opened.needs).containsOnly(entry(CompactionNeed.Reason.SMALL_FILES, 2),
                    entry(CompactionNeed.Reason.EQUALITY_DELETES, 1));
            assertThat(opened.compactionsStarted).isEqualTo(1);
            assertThat(compactor.order()).as("equality deletes first, whatever the score").containsExactly(equality);
            assertThat(compactor.launched.getFirst().night().closes()).as("the Job's deadline: the window's end, the"
                    + " clocks gone forward").isEqualTo(CLOSES);
            assertThat(tableNeed(meters, "many_small")).as("ten small files at a threshold of five").isEqualTo(2.0);
            assertThat(tableNeed(meters, "some_small")).isEqualTo(1.2);
            assertThat(meters.find(WorkerMetrics.TABLE_NEED).tag(WorkerMetrics.TABLE, "fine").gauge())
                    .as("no need, no series").isNull();
            // The catalog keeps each assessment and the Job's start, as the worker reports them (D38).
            JsonNode assessed = health(equality).path("assessment");
            assertAbout(assessed.path("assessed-at"), OPENS);
            assertThat(assessed.path("reasons").toString()).isEqualTo("[\"equality-deletes\"]");
            assertThat(assessed.path("data-files").asLong()).isEqualTo(4);
            assertThat(assessed.path("small-files").asLong()).as("four, too few to rewrite at a threshold of 100")
                    .isZero();
            assertThat(assessed.path("equality-delete-files").asLong()).isEqualTo(1);
            assertThat(assessed.path("delete-files").asLong()).isEqualTo(1);
            assertThat(health(equality).at("/last-compaction/outcome").asText()).isEqualTo("running");
            assertAbout(health(equality).at("/last-compaction/started-at"), OPENS);
            assertThat(health(many).at("/assessment/score").asDouble()).isEqualTo(2.0);
            assertThat(health(many).at("/assessment/small-files").asLong()).isEqualTo(10);
            assertThat(health(many).at("/assessment/small-file-share").asDouble()).isEqualTo(1.0);
            assertThat(health(fine).at("/assessment/needs-compaction").asBoolean(true)).isFalse();
            assertThat(health(fine).at("/assessment/reasons").isEmpty()).isTrue();
            JsonNode scanned = catalog.scan(opened.id);
            assertThat(scanned.path("worker").asText()).isEqualTo(TestCatalog.WORKER);
            assertThat(scanned.path("outcome").asText()).isEqualTo("succeeded");
            assertAbout(scanned.path("started-at"), OPENS);
            assertAbout(scanned.path("ended-at"), OPENS);

            assertThat(at(OPENS.plus(Duration.ofMinutes(30)), worker).compactionsStarted).as("one Job at a time")
                    .isZero();
            compactor.finish(equality, Compactor.State.SUCCEEDED);
            ScanReport midnight = at(Instant.parse("2027-03-28T00:00:00Z"), worker);
            assertThat(midnight.compactionsStarted).isEqualTo(1);
            assertThat(compactor.order()).as("then the neediest").containsExactly(equality, many);
            assertThat(health(equality).at("/last-compaction/outcome").asText()).isEqualTo("succeeded");
            assertAbout(health(equality).at("/last-compaction/started-at"), OPENS);
            assertAbout(health(equality).at("/last-compaction/ended-at"), Instant.parse("2027-03-28T00:00:00Z"));
            assertThat(at(CLOSES.minusSeconds(1), worker).compactionsStarted).isZero();

            ScanReport closed = at(CLOSES, worker);
            assertThat(closed.compactionsStarted).as("the window closed").isZero();
            assertThat(closed.compactionsCutOff).as("many_small's Job, still running").isEqualTo(1);
            assertThat(closed.tablesLeftOver).as("some_small, which got no Job").isEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.compactions.cut.off")).isEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.tables.left.over")).isEqualTo(1);
            assertThat(compactor.launches(some)).isZero();
            assertThat(health(many).at("/last-compaction/outcome").asText()).isEqualTo("cut-off");
            assertAbout(health(many).at("/last-compaction/ended-at"), CLOSES);
            assertThat(health(some).at("/assessment/needs-compaction").asBoolean()).isTrue();
            assertThat(health(some).has("last-compaction")).as("never compacted").isFalse();

            ScanReport next = at(NEXT_OPENS, worker);
            assertThat(next.tablesAssessed).as("the next night, assessed again").isEqualTo(4);
            assertThat(compactor.order()).containsExactly(equality, many, equality);
            assertAbout(health(some).at("/assessment/assessed-at"), NEXT_OPENS);
            assertThat(health(equality).at("/last-compaction/outcome").asText()).as("tonight's Job").isEqualTo(
                    "running");
            assertAbout(health(equality).at("/last-compaction/started-at"), NEXT_OPENS);
        }
    }

    @Test
    void anOrphanScanTheWindowsEndCutsShortIsScannedAgainTheNextNight() throws Exception {
        AtomicReference<String> cutAt = new AtomicReference<>();
        Storage storage = Storage.of(catalog.storageProfiles());
        // The window's end comes as the scan lists the table's files.
        Storage closing = new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                String cut = cutAt.get();
                if (cut != null && prefix.startsWith(cut)) {
                    clock.set(CLOSES);
                }
                return storage.list(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                storage.delete(locations);
            }

            @Override
            public void close() {
                storage.close();
            }
        };
        try (RESTCatalog writer = catalog.writer(); Worker worker = worker(Compactor.NONE, closing,
                Duration.ofDays(2))) {
            TableIdentifier id = TestCatalog.table(writer, "scanned");
            Table table = writer.loadTable(id);
            table.newAppend().appendFile(TestCatalog.dataFile(table, "live")).commit();
            String orphan = table.location() + "/data/orphan.parquet";
            catalog.put(orphan, "never committed");
            catalog.policy(id, Map.of("orphan-removal.enabled", "true", "orphan-removal.older-than", "P8D"));
            String object = table.uuid().toString();

            cutAt.set(table.location());
            ScanReport cut = at(OPENS, worker);
            assertThat(cut.orphanScansCutShort).isEqualTo(1);
            assertThat(cut.orphanScans).isZero();
            assertThat(catalog.pending(object)).isEmpty();
            cutAt.set(null);

            ScanReport next = at(NEXT_OPENS, worker);
            assertThat(next.orphanScans).as("not recorded: scanned again the next night").isEqualTo(1);
            assertThat(catalog.pending(object)).containsExactly(orphan);
            assertThat(at(NEXT_OPENS.plus(Duration.ofDays(1)), worker).orphanScans).as("an orphan interval of two"
                    + " nights").isZero();
            assertThat(at(NEXT_OPENS.plus(Duration.ofDays(2)), worker).orphanScans).isEqualTo(1);
        }
    }

    private Worker worker(Compactor compactor, Storage storage, Duration orphanInterval) {
        return catalog.worker(clock, compactor, storage, catalog.workerTokens(), meters, WINDOW, orphanInterval, 1);
    }

    /** Sets the worker's clock and runs one scan. */
    private ScanReport at(Instant instant, Worker worker) {
        clock.set(instant);
        return worker.runOnce();
    }

    /** A table of {@code files} small data files whose policy turns compaction on. */
    private Table compacted(RESTCatalog writer, String name, int files, Map<String, String> policy) {
        TableIdentifier id = TestCatalog.table(writer, name);
        Table table = writer.loadTable(id);
        appendSmallFiles(table, files);
        Map<String, String> compaction = new HashMap<>(policy);
        compaction.put("compaction.enabled", "true");
        catalog.policy(id, compaction);
        names.put(id(table), id);
        return table;
    }

    /** The table's health as the catalog keeps it from the worker's reports (D38). */
    private JsonNode health(UUID table) {
        return catalog.health(names.get(table));
    }

    /** The instant the catalog keeps, within the minute after {@code at}: the worker's clock runs on once set. */
    private static void assertAbout(JsonNode instant, Instant at) {
        assertThat(Instant.parse(instant.asText())).isBetween(at, at.plus(Duration.ofMinutes(1)));
    }

    private static UUID id(Table table) {
        return UUID.fromString(table.uuid().toString());
    }

    private static void appendSmallFiles(Table table, int files) {
        AppendFiles append = table.newAppend();
        for (int i = 0; i < files; i++) {
            append.appendFile(TestCatalog.dataFile(table, "small-" + i));
        }
        append.commit();
    }
}
