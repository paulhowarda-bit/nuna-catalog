package keel.maintenance;

import static keel.maintenance.WorkerMetricsTest.counter;
import static keel.maintenance.WorkerMetricsTest.failures;
import static keel.maintenance.WorkerMetricsTest.lastSuccess;
import static keel.maintenance.WorkerMetricsTest.scans;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's meters after real scans of a catalog's work list (backlog 40). A scan that expires snapshots, rewrites
 * manifests, finds an orphan, starts a compaction, purges a dropped table and skips one another worker holds is
 * counted as its report says, timed as a success, and dated by the worker's clock. A scan that cannot read the work
 * list (its token source fails) is timed as a failure and counted by kind, and leaves the last success where it was;
 * the next good scan moves it. No meter is labelled with anything but a failure's kind or a scan's outcome.
 */
class ScanMetricsTest {

    /** The soft-drop window the server keeps a dropped table for (keel.rest.drop-retention, 30 days by default). */
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
        TokenSource workerTokens = catalog.workerTokens();
        TokenSource tokens = new TokenSource() {
            @Override
            public String token() {
                if (signedOut.get()) {
                    throw new IllegalStateException("the identity provider did not answer");
                }
                return workerTokens.token();
            }

            @Override
            public Map<String, String> clientProperties() {
                return workerTokens.clientProperties();
            }
        };
        Compactor compactor = table -> Compactor.Launch.STARTED;
        try (RESTCatalog writer = catalog.writer();
             Worker worker = catalog.worker(clock, compactor, Storage.of(catalog.storageProfiles()), tokens, meters)) {
            TableIdentifier id = TestCatalog.table(writer, "metered");
            Table table = writer.loadTable(id);
            // Three appends: three snapshots and three small data manifests.
            for (String name : new String[] {"a", "b", "c"}) {
                table.newAppend().appendFile(TestCatalog.dataFile(table, name)).commit();
            }
            catalog.put(table.location() + "/data/orphan.parquet", "never committed");
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1", "manifest-rewrite.enabled", "true",
                    "manifest-rewrite.min-manifests", "2", "orphan-removal.enabled", "true",
                    "orphan-removal.older-than", "P8D", "compaction.enabled", "true"));
            String purged = drop(writer, "purged");
            String theirs = drop(writer, "theirs");
            catalog.advance(PAST_THE_WINDOW, clock);
            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs
                    + "/purge-claim", null), 200);

            Instant before = clock.instant();
            ScanReport report = worker.runOnce();
            Instant after = clock.instant();

            assertThat(report.failuresByKind).doesNotContainKey(ScanReport.Failure.WORK_LIST);
            assertThat(catalog.state(purged)).isEqualTo("PURGED");
            for (WorkerMetrics.Count count : WorkerMetrics.COUNTS) {
                assertThat(counter(meters, count.name())).as(count.name()).isEqualTo(count.of().applyAsInt(report));
            }
            // At least what this test set up; other tests' leftovers in the shared catalog may add to it.
            assertThat(counter(meters, "keel.maintenance.tables")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.snapshots.expired")).isGreaterThanOrEqualTo(2);
            assertThat(counter(meters, "keel.maintenance.manifests.rewritten")).isGreaterThanOrEqualTo(3);
            assertThat(counter(meters, "keel.maintenance.orphan.scans")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.orphans.found")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.compactions.started")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.files.recorded")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.purges.completed")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.purges.skipped")).isGreaterThanOrEqualTo(1);
            assertThat(counter(meters, "keel.maintenance.files.deleted")).isGreaterThanOrEqualTo(2);
            for (ScanReport.Failure kind : ScanReport.Failure.values()) {
                assertThat(failures(meters, kind)).as(kind.label)
                        .isEqualTo((double) report.failuresByKind.getOrDefault(kind, 0));
            }
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(1);
            assertThat(scans(meters, WorkerMetrics.FAILURE)).isZero();
            assertThat(meters.get(WorkerMetrics.SCAN).tag(WorkerMetrics.OUTCOME, WorkerMetrics.SUCCESS).timer()
                    .totalTime(TimeUnit.MILLISECONDS)).as("timed").isPositive();
            double succeeded = lastSuccess(meters);
            assertThat(succeeded).as("dated by the worker's clock")
                    .isBetween(before.toEpochMilli() / 1000.0, after.toEpochMilli() / 1000.0);

            signedOut.set(true);
            catalog.advance(Duration.ofHours(1), clock);
            ScanReport unread = worker.runOnce();

            assertThat(unread.failuresByKind).isEqualTo(Map.of(ScanReport.Failure.WORK_LIST, 3));
            assertThat(failures(meters, ScanReport.Failure.WORK_LIST)).isEqualTo(3);
            assertThat(scans(meters, WorkerMetrics.FAILURE)).isEqualTo(1);
            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(1);
            assertThat(lastSuccess(meters)).as("no scan succeeded since").isEqualTo(succeeded);

            signedOut.set(false);
            catalog.advance(Duration.ofHours(1), clock);
            before = clock.instant();
            worker.runOnce();
            after = clock.instant();

            assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(2);
            assertThat(lastSuccess(meters)).isBetween(before.toEpochMilli() / 1000.0, after.toEpochMilli() / 1000.0);
            WorkerMetricsTest.assertBoundedLabels(meters);

            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs + "/purge", null),
                    204);
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
}
