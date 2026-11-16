package keel.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * The worker's meters (docs/observability/worker-metrics.md): what each scan did, how long it took, and when one last
 * succeeded. A scan succeeds when it read the whole work list; a job that fails in it is counted by kind, and the scan
 * still succeeds, as the worker did scan.
 *
 * <p>Every meter is registered when the worker starts, so a dashboard or an alert sees zeros before the first scan,
 * not nothing. A label holds only a bounded value, a failure's kind or a scan's outcome: never a table, namespace or
 * file, which would make a series per table and may be sensitive. A name keeps one set of label keys.
 */
final class WorkerMetrics {

    static final String PREFIX = "keel.maintenance.";
    /** A timer: each scan's duration, by outcome. */
    static final String SCAN = PREFIX + "scan";
    /** A gauge: when the last successful scan ended, in seconds since the epoch; 0 until one has. */
    static final String LAST_SUCCESS = PREFIX + "scan.last.success";
    /** A counter: failed jobs and work list reads, by kind. */
    static final String FAILURES = PREFIX + "failures";
    static final String OUTCOME = "outcome";
    static final String SUCCESS = "success";
    static final String FAILURE = "failure";
    static final String KIND = "kind";

    /** A counter of one count a scan report keeps. */
    record Count(String name, String description, ToIntFunction<ScanReport> of) {
    }

    /** Every count a scan report keeps, in its order; a new count is one line here. */
    static final List<Count> COUNTS = List.of(
            new Count(PREFIX + "tables", "Tables the work list named", report -> report.tables),
            new Count(PREFIX + "snapshots.expired", "Snapshots expired", report -> report.snapshotsExpired),
            new Count(PREFIX + "manifests.rewritten", "Manifests rewritten", report -> report.manifestsRewritten),
            new Count(PREFIX + "orphan.scans", "Orphan scans of a table's location", report -> report.orphanScans),
            new Count(PREFIX + "orphans.found", "Unreachable files an orphan scan found old enough to delete",
                    report -> report.orphansFound),
            new Count(PREFIX + "compactions.started", "Compaction Jobs started", report -> report.compactionsStarted),
            new Count(PREFIX + "files.recorded", "Files handed to the catalog's pending-deletion list",
                    report -> report.filesRecorded),
            new Count(PREFIX + "files.outside", "Unreferenced files left alone, outside their table's location",
                    report -> report.filesOutside),
            new Count(PREFIX + "purges.completed", "Dropped objects purged", report -> report.purged),
            new Count(PREFIX + "purges.skipped", "Purges another worker holds, or the catalog refused or rate-limited",
                    report -> report.purgesSkipped),
            new Count(PREFIX + "files.deleted", "Files deleted: due pending deletions and purged objects' files",
                    report -> report.filesDeleted));

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Count, Counter> counts = new LinkedHashMap<>();
    private final Map<ScanReport.Failure, Counter> failures = new EnumMap<>(ScanReport.Failure.class);
    private final Timer succeeded;
    private final Timer failed;
    /** When the last successful scan ended, in seconds since the epoch; 0 until one has. */
    private volatile double lastSuccess;

    /**
     * @param clock the worker's clock, which dates a successful scan
     */
    WorkerMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (Count count : COUNTS) {
            counts.put(count, Counter.builder(count.name()).description(count.description()).register(registry));
        }
        for (ScanReport.Failure kind : ScanReport.Failure.values()) {
            failures.put(kind, Counter.builder(FAILURES).description("Failed jobs and work list reads, by kind")
                    .tag(KIND, kind.label).register(registry));
        }
        succeeded = scanTimer(SUCCESS);
        failed = scanTimer(FAILURE);
        Gauge.builder(LAST_SUCCESS, this, metrics -> metrics.lastSuccess).strongReference(true).baseUnit("seconds")
                .description("When the last successful scan ended, in seconds since the epoch; 0 until one has")
                .register(registry);
    }

    private Timer scanTimer(String outcome) {
        return Timer.builder(SCAN).description("Each scan's duration; it fails when it cannot read the work list")
                .tag(OUTCOME, outcome).register(registry);
    }

    /**
     * Runs one scan, which fills {@code report}, and records it: its counts and failures, its duration and outcome,
     * and, when it succeeded, when it ended. A scan that throws is recorded as failed, with what it counted so far.
     */
    void scan(ScanReport report, Runnable scan) {
        long start = registry.config().clock().monotonicTime();
        boolean ended = false;
        try {
            scan.run();
            ended = true;
        } finally {
            boolean success = ended && !report.failuresByKind.containsKey(ScanReport.Failure.WORK_LIST);
            counts.forEach((count, counter) -> counter.increment(count.of().applyAsInt(report)));
            report.failuresByKind.forEach((kind, times) -> failures.get(kind).increment(times));
            (success ? succeeded : failed).record(registry.config().clock().monotonicTime() - start,
                    TimeUnit.NANOSECONDS);
            if (success) {
                lastSuccess = clock.millis() / 1000.0;
            }
        }
    }
}
