package nuna.maintenance;

import io.micrometer.common.KeyValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A table's maintenance on request (backlog 114; D30, amended 2026-11-20): the jobs the work list's {@code requests}
 * section carries, taken at the scan that reads them, inside the nightly cleanup window or outside it.
 *
 * <p>A requested compaction: the table is assessed and the assessment reported, then a Job is started whatever the
 * assessment says, named after the table and the time asked ({@link Compactor.Run#requested}), to stop at the
 * request's deadline, {@code nuna.maintenance.request-deadline} after it starts. It takes one of the compaction slots
 * the night's Jobs use ({@link Slots}), so no more than {@code max-jobs} run at once; while every slot is taken, or a
 * Job of the table holds one, the request waits for a later scan. With compaction off it waits.
 *
 * <p>A requested orphan scan runs in the scan, as the night's do, until it is done or its deadline passes; one cut
 * short records nothing.
 *
 * <p>Each is reported as a job of the night the window last opened, with {@code requested-at}: the catalog clears the
 * request at the first such report, so the next scan does not take it again. Kept in memory: a worker restarted while
 * a requested Job runs does not report its end, as for the night's Jobs.
 */
final class RequestedWork {

    private static final Logger LOG = LoggerFactory.getLogger(RequestedWork.class);

    /** Hands files a job left unreferenced to the catalog's pending-deletion list ({@code Worker.record}). */
    @FunctionalInterface
    interface Recorder {
        void record(Management.TableWork work, Table table, Collection<String> files, ScanReport report);
    }

    /** A requested Job running: its run, the night it counts to, when it started or was found, its slot. */
    private record Running(Management.RequestWork request, Compactor.Run run, CleanupWindow.Night night,
                           Instant started, int slot, Optional<Long> assessed) {
    }

    private final Compactor compactor;
    private final Slots slots;
    private final Duration deadline;
    private final Storage storage;
    private final CleanupWindow window;
    private final HealthReports health;
    private final ScanSpans spans;
    private final Clock clock;
    private final BiFunction<Management.TableWork, ScanReport, Optional<Table>> loader;
    private final Recorder recorder;
    /** The requested Jobs not yet seen to finish, by table. */
    private final Map<UUID, Running> running = new LinkedHashMap<>();

    /**
     * @param deadline how long a requested job may run
     * @param loader   loads a table afresh; none when it cannot, which it reports
     */
    RequestedWork(Compactor compactor, Slots slots, Duration deadline, Storage storage, CleanupWindow window,
                  HealthReports health, ScanSpans spans, Clock clock,
                  BiFunction<Management.TableWork, ScanReport, Optional<Table>> loader, Recorder recorder) {
        this.compactor = compactor;
        this.slots = slots;
        this.deadline = deadline;
        this.storage = storage;
        this.window = window;
        this.health = health;
        this.spans = spans;
        this.clock = clock;
        this.loader = loader;
        this.recorder = recorder;
    }

    /**
     * Reports the requested Jobs that have ended, then starts a Job for each requested compaction while a slot is
     * free. One that cannot be assessed or started is counted as a {@code compaction} failure, and its request waits.
     */
    void compact(List<Management.RequestWork> requests, ScanReport report) {
        running.values().removeIf(job -> finished(job, report));
        if (compactor == Compactor.NONE) {
            return;
        }
        for (Management.RequestWork request : requests) {
            if (!Management.RequestWork.COMPACTION.equals(request.job())) {
                continue;
            }
            if (!slots.free()) {
                return;
            }
            if (!slots.holds(request.table().id())) {
                start(request, report);
            }
        }
    }

    private void start(Management.RequestWork request, ScanReport report) {
        Management.TableWork work = request.table();
        Optional<Table> loaded = loader.apply(work, report);
        if (loaded.isEmpty()) {
            return;
        }
        Table table = loaded.get();
        Compactor.Run run = Compactor.Run.requested(request.requestedAt(), clock.instant().plus(deadline));
        Compactor.Launch launch;
        CompactionNeed need;
        try {
            Instant at = clock.instant();
            need = spans.part(ScanSpans.Part.COMPACTION_ASSESSMENT, KeyValues.empty(),
                    () -> CompactionNeed.assess(table, work.policy()), assessed -> ScanSpans.OK);
            report.tablesAssessed++;
            health.assessed(work, need, at, report);
            launch = spans.part(ScanSpans.Part.COMPACTION_LAUNCH, KeyValues.empty(),
                    () -> compactor.launch(work, need, run), launched -> launched.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            LOG.warn("Failed starting the requested compaction of table {} ({})", work.name(), work.id(), e);
            report.failed(ScanReport.Failure.COMPACTION, "starting the requested compaction of " + work.id(), e);
            return;
        }
        if (launch == Compactor.Launch.OFF) {
            return;
        }
        if (launch == Compactor.Launch.STARTED) {
            report.compactionsStarted++;
            LOG.info("Started the compaction {} asked for of table {} ({}), {}, to stop by {}", request.requestedBy(),
                    work.name(), work.id(), need, run.deadline());
        }
        Instant at = clock.instant();
        Running job = new Running(request, run, window.last(at), at, slots.take(work.id()),
                Optional.ofNullable(table.currentSnapshot()).map(Snapshot::snapshotId));
        running.put(work.id(), job);
        health.compaction(work, at, Optional.empty(), Compactor.State.RUNNING, report);
        health.job(id(work, run), job.night(), HealthReports.Job.COMPACTION, work.id(), Optional.of(job.slot()), at,
                Optional.empty(), NightlyCompaction.label(Compactor.State.RUNNING), HealthReports.Counts.NONE,
                Optional.of(request.requestedAt()), report);
    }

    /** Whether the Job has finished, reporting how; one that cannot be read is taken as still running. */
    private boolean finished(Running job, ScanReport report) {
        Management.TableWork work = job.request().table();
        Compactor.State state;
        try {
            state = compactor.state(work, job.run());
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the requested compaction Job of table {} ({})", work.name(), work.id(), e);
            return false;
        }
        if (state == Compactor.State.RUNNING) {
            return false;
        }
        if (state == Compactor.State.CUT_OFF) {
            report.compactionsCutOff++;
        }
        LOG.info("The requested compaction Job of table {} ({}) ended: {}", work.name(), work.id(), state);
        slots.release(job.slot());
        Instant now = clock.instant();
        health.compaction(work, job.started(), Optional.of(now), state, report);
        HealthReports.Counts counts = loader.apply(work, report)
                .map(loaded -> NightlyCompaction.compacted(loaded, job.assessed(), job.started()))
                .orElse(HealthReports.Counts.NONE);
        health.job(id(work, job.run()), job.night(), HealthReports.Job.COMPACTION, work.id(), Optional.of(job.slot()),
                job.started(), Optional.of(now), NightlyCompaction.label(state), counts,
                Optional.of(job.request().requestedAt()), report);
        return true;
    }

    /** The job id of a requested Job: one per Job name, whichever worker reports it. */
    static UUID id(Management.TableWork table, Compactor.Run run) {
        return UUID.nameUUIDFromBytes(KubernetesJobs.jobName(table, run).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs each requested orphan scan, one after another, each until it is done or its deadline passes. Before each,
     * {@code between} runs: the worker starts the night's Jobs whose slots have freed.
     */
    void scanOrphans(List<Management.RequestWork> requests, Runnable between, ScanReport report) {
        for (Management.RequestWork request : requests) {
            if (Management.RequestWork.ORPHAN_SCAN.equals(request.job())) {
                between.run();
                scan(request, report);
            }
        }
    }

    private void scan(Management.RequestWork request, ScanReport report) {
        Management.TableWork work = request.table();
        Optional<Table> loaded = loader.apply(work, report);
        if (loaded.isEmpty()) {
            return;
        }
        Table table = loaded.get();
        Instant started = clock.instant();
        Instant until = started.plus(deadline);
        CleanupWindow.Night night = window.last(started);
        UUID id = UUID.randomUUID();
        Optional<Instant> requested = Optional.of(request.requestedAt());
        health.job(id, night, HealthReports.Job.ORPHAN_SCAN, work.id(), Optional.empty(), started, Optional.empty(),
                NightlyCompaction.label(Compactor.State.RUNNING), HealthReports.Counts.NONE, requested, report);
        String outcome;
        try {
            outcome = spans.part(ScanSpans.Part.ORPHAN_SCAN, KeyValues.empty(), () -> {
                List<String> found;
                try {
                    found = OrphanScan.find(table, storage, started.minus(work.duration("orphan-removal.older-than")),
                            () -> clock.instant().isBefore(until), () -> { });
                } catch (OrphanScan.CutShort e) {
                    report.orphanScansCutShort++;
                    LOG.info("The request's deadline cut short the orphan scan of table {} ({})", work.name(),
                            work.id());
                    return "cut-off";
                }
                report.orphanScans++;
                report.orphansFound += found.size();
                recorder.record(work, table, found, report);
                return "succeeded";
            }, done -> ScanSpans.OK);
        } catch (RuntimeException e) {
            LOG.warn("Failed the requested orphan scan of table {} ({})", work.name(), work.id(), e);
            report.failed(ScanReport.Failure.ORPHAN_SCAN, "scanning for orphans of " + work.id(), e);
            outcome = "failed";
        }
        health.job(id, night, HealthReports.Job.ORPHAN_SCAN, work.id(), Optional.empty(), started,
                Optional.of(clock.instant()), outcome, HealthReports.Counts.NONE, requested, report);
    }
}
