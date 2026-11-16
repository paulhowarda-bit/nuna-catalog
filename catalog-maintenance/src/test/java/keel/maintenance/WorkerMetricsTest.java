package keel.maintenance;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The worker's meters (backlog 40), without a catalog: every meter reads zero before the first scan; a scan adds each
 * count its report keeps and each failure by its kind, and is timed by its outcome; only a scan that read the whole
 * work list moves the last success, and a scan that throws is still counted; labels hold only a kind or an outcome;
 * docs/observability/worker-metrics.md lists every meter. {@link ScanMetricsTest} runs real scans.
 */
class WorkerMetricsTest {

    private static final Instant T1 = Instant.parse("2026-11-15T01:00:00.250Z");
    private static final Instant T2 = T1.plusSeconds(600);
    private static final Instant T3 = T2.plusSeconds(600);
    private static final Instant T4 = T3.plusSeconds(600);

    /** What {@link #counted} puts in a report, by meter: a different number for each count. */
    private static final Map<String, Double> COUNTED = Map.ofEntries(
            entry("keel.maintenance.tables", 2.0),
            entry("keel.maintenance.snapshots.expired", 3.0),
            entry("keel.maintenance.manifests.rewritten", 5.0),
            entry("keel.maintenance.orphan.scans", 7.0),
            entry("keel.maintenance.orphans.found", 11.0),
            entry("keel.maintenance.compactions.started", 13.0),
            entry("keel.maintenance.files.recorded", 17.0),
            entry("keel.maintenance.files.outside", 19.0),
            entry("keel.maintenance.purges.completed", 23.0),
            entry("keel.maintenance.purges.skipped", 29.0),
            entry("keel.maintenance.files.deleted", 31.0));

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final SetClock clock = new SetClock();

    @Test
    void everyMeterReadsZeroBeforeTheFirstScan() {
        new WorkerMetrics(meters, clock);

        assertThat(WorkerMetrics.COUNTS).extracting(WorkerMetrics.Count::name)
                .containsExactlyInAnyOrderElementsOf(COUNTED.keySet());
        COUNTED.keySet().forEach(name -> assertThat(counter(meters, name)).as(name).isZero());
        for (ScanReport.Failure kind : ScanReport.Failure.values()) {
            assertThat(failures(meters, kind)).as(kind.label).isZero();
        }
        assertThat(scans(meters, WorkerMetrics.SUCCESS)).isZero();
        assertThat(scans(meters, WorkerMetrics.FAILURE)).isZero();
        assertThat(lastSuccess(meters)).isZero();
    }

    @Test
    void aScanAddsEachCountAndEachFailureByItsKind() {
        WorkerMetrics metrics = new WorkerMetrics(meters, clock);

        metrics.scan(new ScanReport(), () -> { });
        assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(1);
        COUNTED.keySet().forEach(name -> assertThat(counter(meters, name)).as(name).isZero());

        ScanReport report = new ScanReport();
        metrics.scan(report, () -> {
            counted(report);
            for (ScanReport.Failure kind : ScanReport.Failure.values()) {
                if (kind != ScanReport.Failure.WORK_LIST) {
                    report.failed(kind, "some " + kind.label, new IllegalStateException("refused"));
                }
            }
            report.failed(ScanReport.Failure.DELETION, "deleting files of another object");
        });
        COUNTED.forEach((name, value) -> assertThat(counter(meters, name)).as(name).isEqualTo(value));
        for (ScanReport.Failure kind : ScanReport.Failure.values()) {
            assertThat(failures(meters, kind)).as(kind.label).isEqualTo(switch (kind) {
                case WORK_LIST -> 0;
                case DELETION -> 2;
                default -> 1;
            });
        }
        assertThat(scans(meters, WorkerMetrics.SUCCESS)).as("job failures do not fail a scan").isEqualTo(2);

        ScanReport again = new ScanReport();
        metrics.scan(again, () -> counted(again));
        COUNTED.forEach((name, value) -> assertThat(counter(meters, name)).as(name).isEqualTo(2 * value));
        assertThat(failures(meters, ScanReport.Failure.DELETION)).isEqualTo(2);
    }

    @Test
    void onlyAScanThatReadTheWholeWorkListMovesTheLastSuccess() {
        WorkerMetrics metrics = new WorkerMetrics(meters, clock);

        clock.now = T1;
        metrics.scan(new ScanReport(), () -> { });
        assertThat(lastSuccess(meters)).isEqualTo(T1.toEpochMilli() / 1000.0);

        clock.now = T2;
        ScanReport unread = new ScanReport();
        metrics.scan(unread, () -> {
            counted(unread);
            for (String section : new String[] {"the tables section", "the purges section", "the deletions section"}) {
                unread.failed(ScanReport.Failure.WORK_LIST, section, new IllegalStateException("401"));
            }
        });
        assertThat(lastSuccess(meters)).as("the work list was not read").isEqualTo(T1.toEpochMilli() / 1000.0);
        assertThat(scans(meters, WorkerMetrics.FAILURE)).isEqualTo(1);
        assertThat(failures(meters, ScanReport.Failure.WORK_LIST)).isEqualTo(3);
        assertThat(counter(meters, "keel.maintenance.tables")).as("what it did is counted").isEqualTo(2);

        clock.now = T3;
        ScanReport thrown = new ScanReport();
        assertThatThrownBy(() -> metrics.scan(thrown, () -> {
            thrown.tables++;
            throw new IllegalStateException("a bug");
        })).hasMessage("a bug");
        assertThat(lastSuccess(meters)).as("the scan threw").isEqualTo(T1.toEpochMilli() / 1000.0);
        assertThat(scans(meters, WorkerMetrics.FAILURE)).isEqualTo(2);
        assertThat(counter(meters, "keel.maintenance.tables")).isEqualTo(3);

        clock.now = T4;
        metrics.scan(new ScanReport(), () -> { });
        assertThat(lastSuccess(meters)).isEqualTo(T4.toEpochMilli() / 1000.0);
        assertThat(scans(meters, WorkerMetrics.SUCCESS)).isEqualTo(2);
    }

    @Test
    void labelsHoldOnlyAKindOrAnOutcome() {
        WorkerMetrics metrics = new WorkerMetrics(meters, clock);
        String table = "8c2b6f0e-3d1a-4c55-9a43-6f1a7e0b9d21";
        ScanReport report = new ScanReport();
        metrics.scan(report, () -> {
            counted(report);
            report.failed(ScanReport.Failure.TABLE_LOAD, "loading " + table, new IllegalStateException("ns_sales"));
            report.failed(ScanReport.Failure.TABLE_CHANGED, "table " + table + " is now another table");
            report.failed(ScanReport.Failure.DELETION, "deleting files of " + table,
                    new IllegalStateException("s3://lake/tables/ns_sales/orders/data/a.parquet"));
            report.failed(ScanReport.Failure.WORK_LIST, "the purges section", new IllegalStateException("503"));
        });

        assertBoundedLabels(meters);
    }

    /** Every keel.maintenance meter has no label but {@code kind} (a failure's) and {@code outcome} (a scan's). */
    static void assertBoundedLabels(MeterRegistry meters) {
        Set<String> kinds = Arrays.stream(ScanReport.Failure.values()).map(kind -> kind.label)
                .collect(Collectors.toSet());
        Set<String> outcomes = Set.of(WorkerMetrics.SUCCESS, WorkerMetrics.FAILURE);
        for (Meter meter : meters.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith(WorkerMetrics.PREFIX)) {
                continue;
            }
            for (Tag tag : id.getTags()) {
                assertThat(tag.getKey()).as("a label of %s", id.getName()).isIn(WorkerMetrics.KIND,
                        WorkerMetrics.OUTCOME);
                assertThat(tag.getValue()).as("%s of %s", tag.getKey(), id.getName())
                        .isIn(tag.getKey().equals(WorkerMetrics.KIND) ? kinds : outcomes);
            }
        }
    }

    @Test
    void theDocsListEveryMeter() throws IOException {
        new WorkerMetrics(meters, clock);
        String docs = Files.readString(Path.of(System.getProperty("repo.root"), "docs", "observability",
                "worker-metrics.md"));

        Set<String> names = meters.getMeters().stream().map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith(WorkerMetrics.PREFIX)).collect(Collectors.toSet());
        assertThat(names).allSatisfy(name -> assertThat(docs).as(name).contains("`" + name + "`"));
        assertThat(names).hasSize(COUNTED.size() + 3);
    }

    /** Puts {@link #COUNTED}'s numbers in a report, as a scan's jobs would. */
    private static void counted(ScanReport report) {
        report.tables += 2;
        report.snapshotsExpired += 3;
        report.manifestsRewritten += 5;
        report.orphanScans += 7;
        report.orphansFound += 11;
        report.compactionsStarted += 13;
        report.filesRecorded += 17;
        report.filesOutside += 19;
        report.purged += 23;
        report.purgesSkipped += 29;
        report.filesDeleted += 31;
    }

    static double counter(MeterRegistry meters, String name) {
        return meters.get(name).counter().count();
    }

    static double failures(MeterRegistry meters, ScanReport.Failure kind) {
        return meters.get(WorkerMetrics.FAILURES).tag(WorkerMetrics.KIND, kind.label).counter().count();
    }

    static long scans(MeterRegistry meters, String outcome) {
        return meters.get(WorkerMetrics.SCAN).tag(WorkerMetrics.OUTCOME, outcome).timer().count();
    }

    static double lastSuccess(MeterRegistry meters) {
        return meters.get(WorkerMetrics.LAST_SUCCESS).gauge().value();
    }

    /** A clock that reads what the test sets. */
    private static final class SetClock extends Clock {

        volatile Instant now = T1;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
