package keel.maintenance;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One night's compaction (D30, amended 2026-11-15). In the cleanup window each table whose policy turns compaction on
 * is assessed once, from its current manifests ({@link CompactionNeed}); a table that needs compaction joins the
 * night's queue. Jobs start from the queue's head, tables with equality deletes first and then the furthest past
 * their thresholds, at most {@code maxJobs} running at once, and only while the window is open; each runs to the
 * window's end at the latest. Once the window has closed, {@link #close} counts the Jobs its end cut off and the
 * needy tables that got none, which are assessed again the next night.
 *
 * <p>Kept in memory: a worker restarted in the window assesses its tables again, and a table whose Job ran before the
 * restart finds tonight's Job already there and starts no second one.
 */
final class NightlyCompaction {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyCompaction.class);

    /** A table waiting for its Job. */
    private record Queued(Management.TableWork table, CompactionNeed need) {
    }

    /** The queue's order: the neediest first ({@link CompactionNeed#NEEDIEST_FIRST}), then by name. */
    private static final Comparator<Queued> ORDER = Comparator.comparing(Queued::need, CompactionNeed.NEEDIEST_FIRST)
            .thenComparing(queued -> queued.table().catalog())
            .thenComparing(queued -> String.join(".", queued.table().namespace()))
            .thenComparing(queued -> queued.table().name());

    final CleanupWindow.Night night;
    private final Compactor compactor;
    private final int maxJobs;
    private final WorkerMetrics metrics;
    private final Set<UUID> assessed = new HashSet<>();
    private final List<Queued> queue = new ArrayList<>();
    /** Tonight's Jobs not yet seen to finish, by table. */
    private final Map<UUID, Management.TableWork> running = new LinkedHashMap<>();

    NightlyCompaction(CleanupWindow.Night night, Compactor compactor, int maxJobs, WorkerMetrics metrics) {
        this.night = night;
        this.compactor = compactor;
        this.maxJobs = maxJobs;
        this.metrics = metrics;
    }

    /** Whether the table has been assessed tonight. */
    boolean assessed(UUID table) {
        return assessed.contains(table);
    }

    /** Reads the table's current manifests and queues it if it needs compaction. */
    void assess(Management.TableWork work, Table table, ScanReport report) {
        CompactionNeed need = CompactionNeed.assess(table, work.policy());
        assessed.add(work.id());
        report.tablesAssessed++;
        need.reasons().forEach(reason -> report.needs.merge(reason, 1, Integer::sum));
        metrics.assessed(work, need);
        if (need.needed()) {
            LOG.info("Table {} ({}) needs compaction: {}", work.name(), work.id(), need);
            queue.add(new Queued(work, need));
        }
    }

    /**
     * Starts Jobs from the head of the queue while fewer than {@code maxJobs} run and the window is open. A Job
     * that cannot be started is counted as a {@code compaction} failure and stays queued, to be tried again.
     */
    void launch(Clock clock, ScanReport report) {
        running.entrySet().removeIf(entry -> finished(entry.getValue(), report));
        queue.sort(ORDER);
        List<Queued> failed = new ArrayList<>();
        while (running.size() < maxJobs && !queue.isEmpty() && night.contains(clock.instant())) {
            Queued next = queue.removeFirst();
            Management.TableWork table = next.table();
            try {
                switch (compactor.launch(table, next.need(), night)) {
                    case STARTED -> {
                        report.compactionsStarted++;
                        running.put(table.id(), table);
                        LOG.info("Started compaction of table {} ({}), {}, to stop by {}", table.name(), table.id(),
                                next.need(), night.closes());
                    }
                    case EXISTS -> running.put(table.id(), table);
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
    }

    /**
     * Whether the table's Job has finished, logging how; a Job that cannot be read is taken as still running. One
     * stopped at its deadline already, by a cluster clock ahead of the worker's, counts as cut off.
     */
    private boolean finished(Management.TableWork table, ScanReport report) {
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
        return true;
    }

    /**
     * Ends the night, once its window has closed: every Job still running or stopped at its deadline was cut off by
     * the window's end; every table still queued is left over. A table not assessed tonight loses its gauge.
     */
    void close(ScanReport report) {
        for (Management.TableWork table : running.values()) {
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
            }
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
