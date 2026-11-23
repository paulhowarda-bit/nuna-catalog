package nuna.maintenance;

import io.micrometer.common.KeyValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.iceberg.DataOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One night's compaction (D30, amended 2026-11-15). In the cleanup window each table whose policy turns compaction on
 * is assessed once, from its current manifests ({@link CompactionNeed}); a table that needs compaction joins the
 * night's queue. Jobs start from the queue's head, tables with equality deletes first and then the furthest past
 * their thresholds, while one of the compaction slots is free ({@link Slots}, shared with the requested Jobs of backlog
 * 114) and the window is open; each runs to the window's end at the latest. A table whose requested Job runs waits in
 * the queue until it ends. Once the window has closed, {@link #close} counts the Jobs its end cut off and the
 * needy tables that got none, which are assessed again the next night.
 *
 * <p>Kept in memory: a worker restarted in the window assesses its tables again, and a table whose Job ran before the
 * restart finds tonight's Job already there and starts no second one.
 *
 * <p>Each assessment is reported to the catalog as it is made, and each Job as it starts and as it ends (D38): a Job
 * found already there is reported as starting when it was found.
 *
 * <p>Each Job is also reported as a job of the night (backlog 126), under an id made from its Job's name, so a worker
 * restarted reports it again under the same id: with its slot, the lowest of {@code 1..maxJobs} no other running Job
 * holds, requested ones included, and once it has ended, the data files it replaced and those it wrote
 * ({@link #compacted}).
 */
final class NightlyCompaction {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyCompaction.class);

    /** A Job's own commits are looked for no further back than this before it started: the clocks may differ. */
    static final Duration SKEW = Duration.ofHours(1);

    /**
     * A table waiting for its Job.
     *
     * @param assessed the table's current snapshot when it was assessed, from which its Job's commits are counted
     */
    private record Queued(Management.TableWork table, CompactionNeed need, Optional<Long> assessed) {
    }

    /** A Job running: when it started or was found, its slot, and the snapshot its commits are counted from. */
    private record Running(Management.TableWork table, Instant started, int slot, Optional<Long> assessed) {
    }

    /** The queue's order: the neediest first ({@link CompactionNeed#NEEDIEST_FIRST}), then by name. */
    private static final Comparator<Queued> ORDER = Comparator.comparing(Queued::need, CompactionNeed.NEEDIEST_FIRST)
            .thenComparing(queued -> queued.table().catalog())
            .thenComparing(queued -> String.join(".", queued.table().namespace()))
            .thenComparing(queued -> queued.table().name());

    final CleanupWindow.Night night;
    private final Compactor compactor;
    private final Slots slots;
    private final WorkerMetrics metrics;
    private final ScanSpans spans;
    private final HealthReports health;
    private final Clock clock;
    private final BiFunction<Management.TableWork, ScanReport, Optional<Table>> loader;
    private final Set<UUID> assessed = new HashSet<>();
    private final List<Queued> queue = new ArrayList<>();
    /** Tonight's Jobs not yet seen to finish, by table. */
    private final Map<UUID, Running> running = new LinkedHashMap<>();

    /**
     * @param loader loads a table afresh, to count what its Job committed; none when it cannot, which it reports
     */
    NightlyCompaction(CleanupWindow.Night night, Compactor compactor, Slots slots, WorkerMetrics metrics,
                      ScanSpans spans, HealthReports health, Clock clock,
                      BiFunction<Management.TableWork, ScanReport, Optional<Table>> loader) {
        this.night = night;
        this.compactor = compactor;
        this.slots = slots;
        this.metrics = metrics;
        this.spans = spans;
        this.health = health;
        this.clock = clock;
        this.loader = loader;
    }

    /** Whether the table has been assessed tonight. */
    boolean assessed(UUID table) {
        return assessed.contains(table);
    }

    /** Reads the table's current manifests, reports what they show, and queues it if it needs compaction. */
    void assess(Management.TableWork work, Table table, ScanReport report) {
        Instant at = clock.instant();
        CompactionNeed need = CompactionNeed.assess(table, work.policy());
        assessed.add(work.id());
        report.tablesAssessed++;
        need.reasons().forEach(reason -> report.needs.merge(reason, 1, Integer::sum));
        metrics.assessed(work, need);
        health.assessed(work, need, at, report);
        if (need.needed()) {
            LOG.info("Table {} ({}) needs compaction: {}", work.name(), work.id(), need);
            queue.add(new Queued(work, need,
                    Optional.ofNullable(table.currentSnapshot()).map(Snapshot::snapshotId)));
        }
    }

    /**
     * Starts Jobs from the head of the queue while a slot is free and the window is open, passing over a table whose
     * requested Job holds a slot. A Job that cannot be started is counted as a {@code compaction} failure and stays
     * queued, to be tried again. Each launch is a part of the scan ({@link ScanSpans}): {@code started},
     * {@code exists} or {@code off}.
     */
    void launch(Clock clock, ScanReport report) {
        running.values().removeIf(job -> finished(job, report));
        queue.sort(ORDER);
        List<Queued> failed = new ArrayList<>();
        List<Queued> busy = new ArrayList<>();
        while (slots.free() && !queue.isEmpty() && night.contains(clock.instant())) {
            Queued next = queue.removeFirst();
            Management.TableWork table = next.table();
            if (slots.holds(table.id())) {
                busy.add(next);
                continue;
            }
            try {
                Compactor.Launch launch = spans.part(ScanSpans.Part.COMPACTION_LAUNCH, KeyValues.empty(),
                        () -> compactor.launch(table, next.need(), night),
                        launched -> launched.name().toLowerCase(Locale.ROOT));
                switch (launch) {
                    case STARTED -> {
                        report.compactionsStarted++;
                        started(next, clock.instant(), report);
                        LOG.info("Started compaction of table {} ({}), {}, to stop by {}", table.name(), table.id(),
                                next.need(), night.closes());
                    }
                    case EXISTS -> started(next, clock.instant(), report);
                    case OFF -> {
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed launching compaction of table {} ({})", table.name(), table.id(), e);
                report.failed(ScanReport.Failure.COMPACTION, "launching compaction of " + table.id(), e);
                failed.add(next);
            }
        }
        queue.addAll(failed);
        queue.addAll(busy);
    }

    /**
     * Whether the table's Job has finished, logging how; a Job that cannot be read is taken as still running. One
     * stopped at its deadline already, by a cluster clock ahead of the worker's, counts as cut off.
     */
    private boolean finished(Running job, ScanReport report) {
        Management.TableWork table = job.table();
        Compactor.State state;
        try {
            state = compactor.state(table, night);
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the compaction Job of table {} ({})", table.name(), table.id(), e);
            return false;
        }
        switch (state) {
            case RUNNING -> {
                return false;
            }
            case FAILED -> LOG.warn("The compaction Job of table {} ({}) failed", table.name(), table.id());
            case CUT_OFF -> {
                report.compactionsCutOff++;
                LOG.info("The window's end cut off the compaction Job of table {} ({})", table.name(), table.id());
            }
            case SUCCEEDED -> LOG.info("The compaction Job of table {} ({}) succeeded", table.name(), table.id());
        }
        slots.release(job.slot());
        ended(job, state, report);
        return true;
    }

    /** Notes a Job running, started or found at {@code at}, in the lowest free slot, and reports it. */
    private void started(Queued queued, Instant at, ScanReport report) {
        Management.TableWork table = queued.table();
        int slot = slots.take(table.id());
        running.put(table.id(), new Running(table, at, slot, queued.assessed()));
        health.compaction(table, at, Optional.empty(), Compactor.State.RUNNING, report);
        health.job(id(table, night), night, HealthReports.Job.COMPACTION, table.id(), Optional.of(slot), at,
                Optional.empty(), label(Compactor.State.RUNNING), HealthReports.Counts.NONE, report);
    }

    /** Reports how a Job ended, now, with what it committed. */
    private void ended(Running job, Compactor.State state, ScanReport report) {
        Management.TableWork table = job.table();
        Instant now = clock.instant();
        health.compaction(table, job.started(), Optional.of(now), state, report);
        HealthReports.Counts counts = loader.apply(table, report)
                .map(loaded -> compacted(loaded, job.assessed(), job.started()))
                .orElse(HealthReports.Counts.NONE);
        health.job(id(table, night), night, HealthReports.Job.COMPACTION, table.id(), Optional.of(job.slot()),
                job.started(), Optional.of(now), label(state), counts, report);
    }

    /** The night's job id of the table's Job: one per Job name, whichever worker reports it. */
    static UUID id(Management.TableWork table, CleanupWindow.Night night) {
        return UUID.nameUUIDFromBytes(KubernetesJobs.jobName(table, night).getBytes(StandardCharsets.UTF_8));
    }

    static String label(Compactor.State state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * What a table's compaction committed: the data files its {@code replace} snapshots on main took out, and those
     * they wrote, from the current snapshot back to the one the table had when it was assessed, and no further back
     * than {@link #SKEW} before the Job started. Another writer's own rewrite in that time counts too.
     */
    static HealthReports.Counts compacted(Table table, Optional<Long> assessed, Instant started) {
        long in = 0;
        long out = 0;
        long floor = started.minus(SKEW).toEpochMilli();
        Snapshot snapshot = table.currentSnapshot();
        while (snapshot != null && !assessed.equals(Optional.of(snapshot.snapshotId()))
                && snapshot.timestampMillis() >= floor) {
            if (DataOperations.REPLACE.equals(snapshot.operation())) {
                in += count(snapshot, SnapshotSummary.DELETED_FILES_PROP);
                out += count(snapshot, SnapshotSummary.ADDED_FILES_PROP);
            }
            snapshot = snapshot.parentId() == null ? null : table.snapshot(snapshot.parentId());
        }
        return HealthReports.Counts.files(in, out);
    }

    private static long count(Snapshot snapshot, String key) {
        String value = snapshot.summary() == null ? null : snapshot.summary().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * Ends the night, once its window has closed: every Job still running or stopped at its deadline was cut off by
     * the window's end; every table still queued is left over. A table not assessed tonight loses its gauge.
     */
    void close(ScanReport report) {
        for (Running job : running.values()) {
            slots.release(job.slot());
            Management.TableWork table = job.table();
            Compactor.State state;
            try {
                state = compactor.state(table, night);
            } catch (RuntimeException e) {
                LOG.warn("Cannot read the compaction Job of table {} ({})", table.name(), table.id(), e);
                continue;
            }
            if (state == Compactor.State.RUNNING || state == Compactor.State.CUT_OFF) {
                report.compactionsCutOff++;
                LOG.info("The window's end cut off the compaction Job of table {} ({})", table.name(), table.id());
                state = Compactor.State.CUT_OFF;
            }
            ended(job, state, report);
        }
        queue.sort(ORDER);
        for (Queued left : queue) {
            report.tablesLeftOver++;
            LOG.info("Table {} ({}) needed compaction and got no Job before the window closed: {}", left.table().name(),
                    left.table().id(), left.need());
        }
        metrics.keepOnly(assessed);
    }
}
