package nuna.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * The worker's meters (docs/observability/worker-metrics.md): what each scan did, how long it took, and when one last
 * succeeded. A scan succeeds when it read the whole work list; a job that fails in it is counted by kind, and the scan
 * still succeeds, as the worker did scan.
 *
 * <p>Every meter is registered when the worker starts, so a dashboard or an alert sees zeros before the first scan,
 * not nothing. A label holds only a bounded value, a failure's kind, a scan's outcome, a reason for compaction or a
 * ref's kind: never a table, namespace, file or ref's name, which would make a series per table and may be sensitive.
 * The one exception, which Paul accepted (D30, amended 2026-11-15), is {@value #TABLE_NEED}: a series for every table
 * that needs
 * compaction, labelled with its catalog, namespace and name, registered when its assessment finds it needy. A name
 * keeps one set of label keys.
 */
final class WorkerMetrics {

    static final String PREFIX = "nuna.maintenance.";
    /** A timer: each scan's duration, by outcome. */
    static final String SCAN = PREFIX + "scan";
    /** A gauge: when the last successful scan ended, in seconds since the epoch; 0 until one has. */
    static final String LAST_SUCCESS = PREFIX + "scan.last.success";
    /** A counter: failed jobs and work list reads, by kind. */
    static final String FAILURES = PREFIX + "failures";
    /** A counter: tables found needing compaction, by reason. */
    static final String NEEDING = PREFIX + "tables.needing.compaction";
    /**
     * A gauge per table that needed compaction at its last assessment, labelled with its catalog, namespace and name:
     * how far past its thresholds it was. The one meter labelled by table, uncapped (D30, amended 2026-11-15).
     */
    static final String TABLE_NEED = PREFIX + "table.compaction.need";
    /** A counter: files left alone because a catalog ref reaches them, by the ref's kind (D33). */
    static final String KEPT = PREFIX + "files.kept";
    /** A ref's kind, never its name (D33, item 26's choice 13), as the catalog's own meters label it. */
    static final String REF_KIND = "ref.kind";
    static final String OUTCOME = "outcome";
    static final String SUCCESS = "success";
    static final String FAILURE = "failure";
    static final String KIND = "kind";
    static final String REASON = "reason";
    static final String CATALOG = "catalog";
    static final String NAMESPACE = "namespace";
    static final String TABLE = "table";

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
            new Count(PREFIX + "tables.assessed", "Tables whose manifests were read to judge whether they need"
                    + " compaction", report -> report.tablesAssessed),
            new Count(PREFIX + "compactions.started", "Compaction Jobs started", report -> report.compactionsStarted),
            new Count(PREFIX + "compactions.cut.off", "Compaction Jobs the cleanup window's end stopped",
                    report -> report.compactionsCutOff),
            new Count(PREFIX + "tables.left.over", "Tables that needed compaction and got no Job before the cleanup"
                    + " window closed", report -> report.tablesLeftOver),
            new Count(PREFIX + "files.recorded", "Files handed to the catalog's pending-deletion list",
                    report -> report.filesRecorded),
            new Count(PREFIX + "files.outside", "Unreferenced files left alone, outside their table's location",
                    report -> report.filesOutside),
            new Count(PREFIX + "purges.completed", "Dropped objects purged", report -> report.purged),
            new Count(PREFIX + "purges.skipped", "Purges another worker holds, or the catalog refused or rate-limited",
                    report -> report.purgesSkipped),
            new Count(PREFIX + "files.deleted", "Files deleted: due pending deletions and purged objects' files",
                    report -> report.filesDeleted),
            new Count(PREFIX + "refs.warned", "Branches whose expiry warning the worker sent",
                    report -> report.refsWarned),
            new Count(PREFIX + "refs.ended", "Branches past their expiry the worker ended",
                    report -> report.refsEnded));

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Count, Counter> counts = new LinkedHashMap<>();
    private final Map<ScanReport.Failure, Counter> failures = new EnumMap<>(ScanReport.Failure.class);
    private final Map<CompactionNeed.Reason, Counter> needing = new EnumMap<>(CompactionNeed.Reason.class);
    private final Map<Management.RefKind, Counter> kept = new EnumMap<>(Management.RefKind.class);
    /** Each needy table's gauge, by table id. */
    private final Map<UUID, TableNeed> tableNeeds = new ConcurrentHashMap<>();
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
        for (CompactionNeed.Reason reason : CompactionNeed.Reason.values()) {
            needing.put(reason, Counter.builder(NEEDING).description("Tables found needing compaction, by reason")
                    .tag(REASON, reason.label).register(registry));
        }
        for (Management.RefKind kind : Management.RefKind.values()) {
            kept.put(kind, Counter.builder(KEPT).description("Files left alone because a catalog ref reaches them:"
                    + " unreferenced on main and not recorded, or due and not deleted").tag(REF_KIND, kind.label)
                    .register(registry));
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
            report.needs.forEach((reason, tables) -> needing.get(reason).increment(tables));
            report.kept.forEach((kind, files) -> kept.get(kind).increment(files));
            (success ? succeeded : failed).record(registry.config().clock().monotonicTime() - start,
                    TimeUnit.NANOSECONDS);
            if (success) {
                lastSuccess = clock.millis() / 1000.0;
            }
        }
    }

    /**
     * Records a table's assessment: a needy table's gauge reads its score, under its current name; a table that no
     * longer needs compaction loses its gauge.
     */
    void assessed(Management.TableWork table, CompactionNeed need) {
        if (!need.needed()) {
            forget(table.id());
            return;
        }
        Tags tags = Tags.of(CATALOG, table.catalog(), NAMESPACE, String.join(".", table.namespace()), TABLE,
                table.name());
        TableNeed held = tableNeeds.get(table.id());
        if (held != null && !held.gauge.getId().getTags().equals(tags.stream().toList())) {
            forget(table.id());
            held = null;
        }
        if (held == null) {
            TableNeed fresh = new TableNeed();
            fresh.gauge = Gauge.builder(TABLE_NEED, fresh, gauge -> gauge.score).tags(tags).strongReference(true)
                    .description("How far past its compaction thresholds a table was at its last assessment (2 is"
                            + " twice its threshold); one series per table that needed compaction")
                    .register(registry);
            tableNeeds.put(table.id(), fresh);
            held = fresh;
        }
        held.score = need.score();
    }

    /** Drops the gauges of tables not among {@code tables}: dropped, or no longer compacted. */
    void keepOnly(Set<UUID> tables) {
        tableNeeds.keySet().stream().filter(id -> !tables.contains(id)).toList().forEach(this::forget);
    }

    private void forget(UUID table) {
        TableNeed held = tableNeeds.remove(table);
        if (held != null) {
            registry.remove(held.gauge);
        }
    }

    /** One table's gauge and the score it reads. */
    private static final class TableNeed {

        volatile double score;
        Gauge gauge;
    }
}
