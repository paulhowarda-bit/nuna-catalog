package nuna.maintenance;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
            entry("nuna.maintenance.tables", 2.0),
            entry("nuna.maintenance.snapshots.expired", 3.0),
            entry("nuna.maintenance.manifests.rewritten", 5.0),
            entry("nuna.maintenance.orphan.scans", 7.0),
            entry("nuna.maintenance.orphans.found", 11.0),
            entry("nuna.maintenance.compactions.started", 13.0),
            entry("nuna.maintenance.files.recorded", 17.0),
            entry("nuna.maintenance.files.outside", 19.0),
            entry("nuna.maintenance.purges.completed", 23.0),
            entry("nuna.maintenance.purges.skipped", 29.0),
            entry("nuna.maintenance.files.deleted", 31.0),
            entry("nuna.maintenance.tables.assessed", 37.0),
            entry("nuna.maintenance.compactions.cut.off", 41.0),
            entry("nuna.maintenance.tables.left.over", 43.0),
            entry("nuna.maintenance.refs.warned", 61.0),
            entry("nuna.maintenance.refs.ended", 67.0));

    /** What {@link #counted} puts in a report as tables needing compaction, by reason. */
    private static final Map<CompactionNeed.Reason, Double> NEEDS = Map.of(CompactionNeed.Reason.SMALL_FILES, 47.0,
            CompactionNeed.Reason.DELETES, 53.0, CompactionNeed.Reason.EQUALITY_DELETES, 59.0);

    /** What {@link #counted} puts in a report as files kept for a catalog ref, by its kind (D33). */
    private static final Map<Management.RefKind, Double> KEPT = Map.of(Management.RefKind.BRANCH, 71.0,
            Management.RefKind.TAG, 73.0);

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
        for (CompactionNeed.Reason reason : CompactionNeed.Reason.values()) {
            assertThat(needing(meters, reason)).as(reason.label).isZero();
        }
        for (Management.RefKind kind : Management.RefKind.values()) {
            assertThat(kept(meters, kind)).as(kind.label).isZero();
        }
        assertThat(scans(meters, WorkerMetrics.SUCCESS)).isZero();
        assertThat(scans(meters, WorkerMetrics.FAILURE)).isZero();
        assertThat(lastSuccess(meters)).isZero();
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).as("no table assessed yet").isEmpty();
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
        NEEDS.forEach((reason, value) -> assertThat(needing(meters, reason)).as(reason.label).isEqualTo(value));
        KEPT.forEach((kind, value) -> assertThat(kept(meters, kind)).as(kind.label).isEqualTo(value));
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
        NEEDS.forEach((reason, value) -> assertThat(needing(meters, reason)).as(reason.label).isEqualTo(2 * value));
        KEPT.forEach((kind, value) -> assertThat(kept(meters, kind)).as(kind.label).isEqualTo(2 * value));
        assertThat(failures(meters, ScanReport.Failure.DELETION)).isEqualTo(2);
    }

    /**
     * The one meter labelled by table (D30, amended 2026-11-15): a needy table's gauge reads its score under its
     * current catalog, namespace and name, from its first needy assessment; it moves to a new name when the table is
     * renamed, and goes when the table no longer needs compaction or is no longer assessed.
     */
    @Test
    void aNeedyTableHasAGaugeOfItsOwn() {
        WorkerMetrics metrics = new WorkerMetrics(meters, clock);
        UUID ordersId = UUID.randomUUID();
        UUID eventsId = UUID.randomUUID();
        Management.TableWork orders = table(ordersId, List.of("sales", "eu"), "orders");
        Management.TableWork events = table(eventsId, List.of("web"), "events");

        metrics.assessed(orders, new CompactionNeed(20, 10, 0, 0, 5, 0.3, 536870912));
        metrics.assessed(events, new CompactionNeed(20, 0, 0, 0, 5, 0.3, 536870912));
        assertThat(tableNeed(meters, "orders")).as("ten small files at a threshold of five").isEqualTo(2.0);
        assertThat(meters.get(WorkerMetrics.TABLE_NEED).gauge().getId().getTags()).extracting(Tag::getKey,
                Tag::getValue).containsExactlyInAnyOrder(tuple(WorkerMetrics.CATALOG, "lake"),
                tuple(WorkerMetrics.NAMESPACE, "sales.eu"), tuple(WorkerMetrics.TABLE, "orders"));
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).as("events needs none").hasSize(1);

        metrics.assessed(orders, new CompactionNeed(20, 0, 8, 0, 5, 0.2, 536870912));
        assertThat(tableNeed(meters, "orders")).as("8 delete files on 20 data files, at a threshold of 0.2")
                .isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        Management.TableWork renamed = table(ordersId, List.of("sales", "eu"), "orders_v2");
        metrics.assessed(renamed, new CompactionNeed(20, 15, 0, 0, 5, 0.3, 536870912));
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).singleElement()
                .satisfies(gauge -> assertThat(gauge.getId().getTag(WorkerMetrics.TABLE)).isEqualTo("orders_v2"))
                .satisfies(gauge -> assertThat(gauge.value()).isEqualTo(3.0));

        metrics.assessed(events, new CompactionNeed(20, 0, 0, 1, 5, 0.3, 536870912));
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).hasSize(2);
        metrics.keepOnly(Set.of(eventsId));
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).as("orders not assessed tonight").singleElement()
                .satisfies(gauge -> assertThat(gauge.getId().getTag(WorkerMetrics.TABLE)).isEqualTo("events"));
        metrics.assessed(events, new CompactionNeed(20, 0, 0, 0, 5, 0.3, 536870912));
        assertThat(meters.find(WorkerMetrics.TABLE_NEED).gauges()).as("compacted: no need").isEmpty();
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
        assertThat(counter(meters, "nuna.maintenance.tables")).as("what it did is counted").isEqualTo(2);

        clock.now = T3;
        ScanReport thrown = new ScanReport();
        assertThatThrownBy(() -> metrics.scan(thrown, () -> {
            thrown.tables++;
            throw new IllegalStateException("a bug");
        })).hasMessage("a bug");
        assertThat(lastSuccess(meters)).as("the scan threw").isEqualTo(T1.toEpochMilli() / 1000.0);
        assertThat(scans(meters, WorkerMetrics.FAILURE)).isEqualTo(2);
        assertThat(counter(meters, "nuna.maintenance.tables")).isEqualTo(3);

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
        metrics.assessed(table(UUID.fromString(table), List.of("ns_sales"), "orders"),
                new CompactionNeed(20, 10, 0, 0, 5, 0.3, 536870912));

        assertBoundedLabels(meters);
    }

    /**
     * Every nuna.maintenance meter has no label but {@code kind} (a failure's), {@code outcome} (a scan's),
     * {@code reason} (a need for compaction) and {@code ref.kind} (a catalog ref's, never its name), save {@value WorkerMetrics#TABLE_NEED}, which has exactly
     * {@code catalog}, {@code namespace} and {@code table}.
     */
    static void assertBoundedLabels(MeterRegistry meters) {
        Map<String, Set<String>> bounded = Map.of(
                WorkerMetrics.KIND, Arrays.stream(ScanReport.Failure.values()).map(kind -> kind.label)
                        .collect(Collectors.toSet()),
                WorkerMetrics.OUTCOME, Set.of(WorkerMetrics.SUCCESS, WorkerMetrics.FAILURE),
                WorkerMetrics.REASON, Arrays.stream(CompactionNeed.Reason.values()).map(reason -> reason.label)
                        .collect(Collectors.toSet()),
                WorkerMetrics.REF_KIND, Set.of("branch", "tag"));
        for (Meter meter : meters.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith(WorkerMetrics.PREFIX)) {
                continue;
            }
            if (id.getName().equals(WorkerMetrics.TABLE_NEED)) {
                assertThat(id.getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder(WorkerMetrics.CATALOG,
                        WorkerMetrics.NAMESPACE, WorkerMetrics.TABLE);
                continue;
            }
            for (Tag tag : id.getTags()) {
                assertThat(tag.getKey()).as("a label of %s", id.getName()).isIn(bounded.keySet());
                assertThat(tag.getValue()).as("%s of %s", tag.getKey(), id.getName())
                        .isIn(bounded.get(tag.getKey()));
            }
        }
    }

    @Test
    void theDocsListEveryMeter() throws IOException {
        WorkerMetrics metrics = new WorkerMetrics(meters, clock);
        metrics.assessed(table(UUID.randomUUID(), List.of("sales"), "orders"),
                new CompactionNeed(20, 10, 0, 0, 5, 0.3, 536870912));
        String docs = Files.readString(Path.of(System.getProperty("repo.root"), "docs", "observability",
                "worker-metrics.md"));

        Set<String> names = meters.getMeters().stream().map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith(WorkerMetrics.PREFIX)).collect(Collectors.toSet());
        assertThat(names).allSatisfy(name -> assertThat(docs).as(name).contains("`" + name + "`"));
        // The counts, the scan timer, the last success, failures, tables needing compaction, the table gauge and the
        // files kept for refs.
        assertThat(names).hasSize(COUNTED.size() + 6);
    }

    /** Puts {@link #COUNTED}'s and {@link #NEEDS}' numbers in a report, as a scan's jobs would. */
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
        report.tablesAssessed += 37;
        report.compactionsCutOff += 41;
        report.tablesLeftOver += 43;
        report.refsWarned += 61;
        report.refsEnded += 67;
        report.kept(Map.of(Management.RefKind.BRANCH, 71, Management.RefKind.TAG, 73));
        NEEDS.forEach((reason, tables) -> report.needs.merge(reason, tables.intValue(), Integer::sum));
    }

    private static Management.TableWork table(UUID id, List<String> namespace, String name) {
        return new Management.TableWork("lake", id, namespace, name, "s3://lake/tables/" + name,
                Map.of("compaction.enabled", "true"));
    }

    static double needing(MeterRegistry meters, CompactionNeed.Reason reason) {
        return meters.get(WorkerMetrics.NEEDING).tag(WorkerMetrics.REASON, reason.label).counter().count();
    }

    static double kept(MeterRegistry meters, Management.RefKind kind) {
        return meters.get(WorkerMetrics.KEPT).tag(WorkerMetrics.REF_KIND, kind.label).counter().count();
    }

    static double tableNeed(MeterRegistry meters, String table) {
        return meters.get(WorkerMetrics.TABLE_NEED).tag(WorkerMetrics.TABLE, table).gauge().value();
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
