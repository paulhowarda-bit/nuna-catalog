package nuna.maintenance;

import static nuna.maintenance.WorkerMetricsTest.counter;
import static nuna.maintenance.WorkerMetricsTest.failures;
import static nuna.maintenance.WorkerMetricsTest.lastSuccess;
import static nuna.maintenance.WorkerMetricsTest.scans;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The worker's meters after real scans of a catalog's work list (backlog 40):
 *
 * <ol>
 *   <li>a scan that expires snapshots, rewrites manifests, finds an orphan, finds a table needing compaction for its
 *       small files and starts its Job, purges a dropped table and skips one another worker holds is counted as its
 *       report says, timed as a success and dated by the worker's clock; the needy table has its gauge;</li>
 *   <li>a scan that cannot read the work list (the worker's token source fails) is timed as a failure, counted as
 *       {@code work-list} failures, and leaves the last success where it was; the next good scan moves it; the
 *       catalog, which the worker could not reach either, keeps no record of it ({@code report} failures);</li>
 *   <li>a scan whose store fails its orphan scan, purge and deletions counts each failure by its kind, and still
 *       succeeds: it read the whole work list.</li>
 * </ol>
 *
 * No meter is labelled with anything but a failure's kind, a scan's outcome or a reason for compaction, save the needy
 * tables' gauge.
 */
@Tag("heavy")
class ScanMetricsTest {

    /** The soft-drop window the server keeps a dropped table for (nuna.rest.drop-retention, 30 days by default). */
    private static final Duration PAST_THE_WINDOW = Duration.ofDays(31);

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void scansAreCountedTimedAndTheLastSuccessKept() throws Exception {
        MeterRegistry meters = new SimpleMeterRegistry();
        FakeClock clock = new FakeClock();
        AtomicBoolean signedOut = new AtomicBoolean();
        AtomicBoolean storeDown = new AtomicBoolean();
        StubCompactor compactor = new StubCompactor();
        List<ScanReport> reports = new ArrayList<>();
        try (RESTCatalog writer = catalog.writer();
             Worker worker = catalog.worker(clock, compactor, failing(catalog.workerStorage(), storeDown),
                     failing(catalog.workerTokens(), signedOut), meters)) {
            TableIdentifier id = TestCatalog.table(writer, "metered");
            Table table = writer.loadTable(id);
            // Three appends: three snapshots, three small data manifests and three small data files, enough to need
            // compaction at this policy's threshold.
            for (String name : new String[] {"a", "b", "c"}) {
                table.newAppend().appendFile(TestCatalog.dataFile(table, name)).commit();
            }
            catalog.put(table.location() + "/data/orphan.parquet", "never committed");
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1", "manifest-rewrite.enabled", "true",
                    "manifest-rewrite.min-manifests", "2", "orphan-removal.enabled", "true",
                    "orphan-removal.older-than", "P8D", "compaction.enabled", "true",
                    "compaction.min-input-files", "3"));
            String purged = drop(writer, "purged");
            String theirs = drop(writer, "theirs");
            catalog.advance(PAST_THE_WINDOW, clock);
            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs
                    + "/purge-claim", null), 200);

            // 1. Everything a scan does.
            Instant before = clock.instant();
            reports.add(worker.runOnce());
            Instant after = clock.instant();

            assertThat(reports.getLast().failuresByKind).doesNotContainKey(ScanReport.Failure.WORK_LIST);
            assertThat(catalog.state(purged)).isEqualTo("PURGED");
            assertCounted(meters, reports);
            // At least what this test set up; other tests' leftovers in the shared catalog may add to it.
            assertThat(counter(meters, "nuna.maintenance.tables")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.snapshots.expired")).isGreaterThanOrEqualTo(2);
            assertThat(counter(meters, "nuna.maintenance.manifests.rewritten")).isGreaterThanOrEqualTo(3);
            assertThat(counter(meters, "nuna.maintenance.orphan.scans")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.orphans.found")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.tables.assessed")).isGreaterThanOrEqualTo(1);
            assertThat(meters.get(WorkerMetrics.NEEDING).tag(WorkerMetrics.REASON,
                    CompactionNeed.Reason.SMALL_FILES.label).counter().count()).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.compactions.started")).isGreaterThanOrEqualTo(1);
            assertThat(meters.get(WorkerMetrics.TABLE_NEED).tag(WorkerMetrics.TABLE, "metered").gauge().value())
                    .as("three small files at a threshold of three").isEqualTo(1.0);
            assertThat(counter(meters, "nuna.maintenance.files.recorded")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.purges.completed")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.purges.skipped")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "nuna.maintenance.files.deleted")).isGreaterThanOrEqualTo(2);
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(1);
            assertThat(scans(meters, WorkerMetrics.FAILURE)).isZero();
            assertThat(meters.get(WorkerMetrics.SCAN).tag(WorkerMetrics.OUTCOME, WorkerMetrics.SUCCESS).timer()
                    .totalTime(TimeUnit.MILLISECONDS)).as("timed").isPositive();
            double succeeded = lastSuccess(meters);
            assertThat(succeeded).as("dated by the worker's clock")
                    .isBetween(before.toEpochMilli() / 1000.0, after.toEpochMilli() / 1000.0);

            // 2. The work list unread, then read again.
            signedOut.set(true);
            catalog.advance(Duration.ofHours(1), clock);
            reports.add(worker.runOnce());

            assertThat(reports.getLast().failuresByKind).as("nor read its storage prefixes, nor report its start and end")
                    .isEqualTo(Map.of(ScanReport.Failure.WORK_LIST, 5, ScanReport.Failure.STORAGE_PREFIXES, 1,
                            ScanReport.Failure.REPORT, 2));
            assertThat(catalog.scan(reports.getLast().id).isMissingNode()).isTrue();
            assertCounted(meters, reports);
            assertThat(failures(meters, ScanReport.Failure.WORK_LIST)).isEqualTo(5);
            assertThat(scans(meters, WorkerMetrics.FAILURE)).isEqualTo(1);
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(1);
            assertThat(lastSuccess(meters)).as("no scan succeeded since").isEqualTo(succeeded);

            signedOut.set(false);
            catalog.advance(Duration.ofHours(1), clock);
            before = clock.instant();
            reports.add(worker.runOnce());
            after = clock.instant();

            assertCounted(meters, reports);
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(2);
            assertThat(lastSuccess(meters)).isBetween(before.toEpochMilli() / 1000.0, after.toEpochMilli() / 1000.0);

            // 3. Jobs failing: the orphan scan of "metered" is due again, the files the first scan recorded are due
            // for deletion, and a newly dropped table for its purge, all on a store that fails.
            String unpurgeable = drop(writer, "unpurgeable");
            storeDown.set(true);
            catalog.advance(PAST_THE_WINDOW, clock);
            before = clock.instant();
            reports.add(worker.runOnce());
            after = clock.instant();

            assertThat(reports.getLast().failuresByKind).containsKeys(ScanReport.Failure.ORPHAN_SCAN,
                    ScanReport.Failure.PURGE, ScanReport.Failure.DELETION)
                    .doesNotContainKey(ScanReport.Failure.WORK_LIST);
            assertCounted(meters, reports);
            assertThat(failures(meters, ScanReport.Failure.ORPHAN_SCAN)).isGreaterThanOrEqualTo(1);
            assertThat(failures(meters, ScanReport.Failure.PURGE)).isGreaterThanOrEqualTo(1);
            assertThat(failures(meters, ScanReport.Failure.DELETION)).isGreaterThanOrEqualTo(1);
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).as("failed jobs, the work list read").isEqualTo(3);
            assertThat(lastSuccess(meters)).isBetween(before.toEpochMilli() / 1000.0, after.toEpochMilli() / 1000.0);
            // The catalog keeps the scan as the worker reported it (D38): succeeded, its failed jobs by kind.
            JsonNode kept = catalog.scan(reports.getLast().id);
            assertThat(kept.path("outcome").asText()).isEqualTo("succeeded");
            assertThat(kept.at("/failures/orphan-scan").asInt()).isEqualTo(
                    reports.getLast().failuresByKind.get(ScanReport.Failure.ORPHAN_SCAN));
            assertThat(kept.at("/failures/purge").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(kept.at("/failures/deletion").asInt()).isGreaterThanOrEqualTo(1);
            WorkerMetricsTest.assertBoundedLabels(meters);

            // The store back: the claimed purge resumes, and the other worker finishes its own.
            storeDown.set(false);
            catalog.advance(Duration.ofHours(1), clock);
            worker.runOnce();
            assertThat(catalog.state(unpurgeable)).isEqualTo("PURGED");
            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs + "/purge", null),
                    204);
        }
    }

    /** Every count and every kind of failure as the reports, summed, say. */
    private static void assertCounted(MeterRegistry meters, List<ScanReport> reports) {
        for (WorkerMetrics.Count count : WorkerMetrics.COUNTS) {
            assertThat(counter(meters, count.name())).as(count.name())
                    .isEqualTo(reports.stream().mapToInt(count.of()).sum());
        }
        for (ScanReport.Failure kind : ScanReport.Failure.values()) {
            assertThat(failures(meters, kind)).as(kind.label)
                    .isEqualTo(reports.stream().mapToInt(report -> report.failuresByKind.getOrDefault(kind, 0)).sum());
        }
    }

    /** A table with a committed file, dropped without purge; its id. */
    private static String drop(RESTCatalog writer, String name) {
        TableIdentifier id = TestCatalog.table(writer, name);
        Table table = writer.loadTable(id);
        table.newAppend().appendFile(TestCatalog.dataFile(table, "a")).commit();
        assertThat(writer.dropTable(id, false)).isTrue();
        return table.uuid().toString();
    }

    /** {@code tokens}, failing while {@code down} is set, as an identity provider that does not answer. */
    private static TokenSource failing(TokenSource tokens, AtomicBoolean down) {
        return new TokenSource() {
            @Override
            public String token() {
                if (down.get()) {
                    throw new IllegalStateException("the identity provider did not answer");
                }
                return tokens.token();
            }

            @Override
            public Map<String, String> clientProperties() {
                return tokens.clientProperties();
            }
        };
    }

    /** {@code storage}, failing every listing and deletion while {@code down} is set. */
    private static Storage failing(Storage storage, AtomicBoolean down) {
        return new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                answerUnlessDown();
                return storage.list(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                answerUnlessDown();
                storage.delete(locations);
            }

            @Override
            public Prefixes reach(List<Management.StorageProfile> profiles) {
                return storage.reach(profiles);
            }

            @Override
            public void close() {
                storage.close();
            }

            private void answerUnlessDown() {
                if (down.get()) {
                    throw new IllegalStateException("the store did not answer");
                }
            }
        };
    }
}
