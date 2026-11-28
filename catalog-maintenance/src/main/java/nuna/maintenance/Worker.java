package nuna.maintenance;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.java11.instrument.binder.jdk.MicrometerHttpClient;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One scan of the catalog's work list (D30 (2)), as {@link #runOnce} does it:
 *
 * <ol>
 *   <li><b>Storage prefixes</b> (item 256): where the worker's own identity lists and deletes at this scan, as the
 *       catalog gives them; when it reaches none, the read having failed or named none, the orphan scans, purges and
 *       deletions below wait for a scan that reaches some.</li>
 *   <li><b>Refs</b> (D33, choice 11): each branch whose expiry is near is warned of, and each past it ended, so an
 *       ended branch's files are no longer kept at this scan's jobs.</li>
 *   <li><b>Tables</b> whose resolved policy runs a job, with the catalog's guardrails applied (a hold stops expiry
 *       and orphan removal, a DR freeze every job, and only active tables are listed): snapshot expiry and manifest
 *       rewrite at every scan. While the nightly cleanup window is open (D30, amended 2026-11-15), each table whose
 *       policy turns compaction on is assessed once a night from its manifests, and one whose policy turns orphan
 *       removal on is due an orphan scan, at most once a night and once per orphan interval. Each table is loaded by
 *       name first and left alone unless it is still the table the work list named, by id. Nothing here deletes:
 *       every file a job leaves unreferenced goes to the catalog's pending-deletion list, which holds it for the
 *       database's PITR window (D30 (5), (8)), unless a catalog ref, a branch or tag, still reaches it (D33, item
 *       26's choice 14, {@link RefReach}).</li>
 *   <li><b>Compaction</b>: Jobs for the tables that need one, neediest first, as many at once as the settings allow,
 *       each to stop at the window's end ({@link NightlyCompaction}).</li>
 *   <li><b>Requests</b> (backlog 114): the jobs callers asked for, inside the window or outside it. Each requested
 *       compaction starts its Job before the night's queue gets a slot, each requested orphan scan runs before the
 *       night's, each to stop at the request's deadline ({@link RequestedWork}).</li>
 *   <li><b>Orphan scans</b> due tonight, one after another while the window is open. One the window's end cuts short
 *       is not recorded, and its table is scanned again the next night.</li>
 *   <li><b>Purges</b>: each dropped object past its window is claimed, everything under its location deleted, and
 *       the purge completed (D30 (6)). A worker that died part-way gets its own claim back and starts again.</li>
 *   <li><b>Deletions</b> the catalog lists as due: each checked first against the refs made since it was recorded,
 *       then deleted with the worker's own storage identity and reported done; one such a ref reaches is reported
 *       done without being deleted.</li>
 * </ol>
 *
 * <p>Outside the window nothing heavy starts but what was requested, after a restart too: expiry, manifest rewrite,
 * purges and deletions run round the clock. The first scan after the window closes ends the night: it counts the Jobs the window's end
 * cut off and the needy tables left over.
 *
 * <p>Every job is idempotent: a scan that runs again after a crash, or sees the effect of its own last commit,
 * finds less to do or records files already pending, which the catalog keeps as they were. One table, purge or
 * file group failing is reported and the scan moves on.
 *
 * <p>The scan and each of its parts are spans ({@link ScanSpans}), and its calls to the catalog carry their trace.
 *
 * <p>Each job is reported to the catalog as a job of the night it counts to (backlog 126, {@link HealthReports}):
 * each compaction Job, orphan scan and purge on its own, and each table's expiry, manifest rewrite and deletions added
 * up over the night.
 */
final class Worker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Worker.class);

    private final Management management;
    private final Catalogs catalogs;
    private final Storage storage;
    private final Compactor compactor;
    private final Clock clock;
    private final CleanupWindow window;
    private final Duration orphanInterval;
    /** The compaction slots, shared by the night's Jobs and the requested ones. */
    private final Slots slots;
    /** The night of each table's last orphan scan not cut short, by table id; empty after a restart. */
    private final Map<UUID, LocalDate> orphanScans = new ConcurrentHashMap<>();
    /** The window's opening now under way, or the last one until a scan has closed it; none before the first. */
    private NightlyCompaction tonight;
    /** The tables this scan found due an orphan scan. */
    private final List<Management.TableWork> orphansDue = new ArrayList<>();
    /** The storage prefixes as last logged: those of the last scan that read them; none before the first. */
    private Prefixes logged;
    private final WorkerMetrics metrics;
    private final ScanSpans spans;
    private final HealthReports health;
    private final RequestedWork requested;

    /**
     * @param window         the nightly cleanup window: compaction and orphan scans start only inside it
     * @param orphanInterval the least time between two orphan scans of one table, rounded up to whole nights
     * @param requestDeadline how long a job run on request may run (backlog 114)
     * @param compactionJobs how many compaction Jobs run at once, the night's and the requested
     * @param observations   where the scans and their parts are observed: spans and timers
     */
    Worker(Management management, Catalogs catalogs, Storage storage, Compactor compactor, Clock clock,
           CleanupWindow window, Duration orphanInterval, Duration requestDeadline, int compactionJobs,
           MeterRegistry meters, ObservationRegistry observations) {
        this.management = management;
        this.catalogs = catalogs;
        this.storage = storage;
        this.compactor = compactor;
        this.clock = clock;
        this.window = window;
        this.orphanInterval = orphanInterval;
        this.slots = new Slots(compactionJobs);
        this.metrics = new WorkerMetrics(meters, clock);
        this.spans = new ScanSpans(observations);
        this.health = new HealthReports(management, spans, window);
        this.requested = new RequestedWork(compactor, slots, requestDeadline, storage, window, health, spans, clock,
                this::load, this::record);
    }

    /**
     * The worker the settings describe. Each of its calls to the catalog and its identity provider is observed: a span
     * within the scan's, whose trace it carries as {@code traceparent}, and an {@code http.client.requests} timer.
     */
    static Worker of(WorkerSettings settings, Clock clock, MeterRegistry meters, ObservationRegistry observations,
                     TraceHeaders trace) {
        HttpClient http = MicrometerHttpClient.instrumentationBuilder(HttpClient.newHttpClient(), meters)
                .observationRegistry(observations).uriMapper(Management::route).build();
        TokenSource tokens = TokenSource.of(settings.token(), clock, http);
        Management management = new Management(settings.catalogUri(), tokens, http, settings.pageSize());
        OwnStorage storage = Storage.of(settings.storage());
        Compactor compactor = settings.compaction().enabled()
                ? new KubernetesJobs(settings.compaction(), settings.catalogUri(), tokens, settings.storage(),
                        storage::store, clock)
                : Compactor.NONE;
        return new Worker(management, new Catalogs(settings.catalogUri(), tokens, storage.clientProperties(), trace),
                storage, compactor, clock, settings.cleanupWindow(), settings.orphanInterval(),
                settings.requestDeadline(), settings.compaction().maxJobs(), meters, observations);
    }

    /**
     * One scan: the tables, the requested jobs, the night's compaction Jobs and orphan scans, then the purges, then
     * the due deletions;
     * recorded as meters ({@link WorkerMetrics}) and spans ({@link ScanSpans}), and reported to the catalog as it
     * starts and ends (D38).
     */
    ScanReport runOnce() {
        ScanReport report = new ScanReport();
        spans.scan(report, () -> metrics.scan(report, () -> scan(report.id, report)));
        return report;
    }

    private void scan(UUID id, ScanReport report) {
        Instant now = clock.instant();
        // The prefixes first, so that the scan's start reports what the store lets the worker list beneath those the
        // catalog gives at this scan (backlog 250). A scan that could not read them reports no storage at all, so the
        // catalog does not take it for one that read them and reaches none (item 275).
        Optional<Prefixes> reached = reach(report);
        boolean reaches = reached.filter(prefixes -> !prefixes.isEmpty()).isPresent();
        health.scanStarted(id, now, reached.map(read -> storage.check()), report);
        boolean ended = false;
        Optional<String> error = Optional.empty();
        try {
            Optional<CleanupWindow.Night> night = window.night(now);
            if (tonight != null && !night.equals(Optional.of(tonight.night))) {
                tonight.close(report);
                tonight = null;
            }
            if (night.isPresent() && tonight == null) {
                tonight = new NightlyCompaction(night.get(), compactor, slots, metrics, spans, health,
                        clock, this::load);
            }
            orphansDue.clear();
            pages(Management.Section.REFS, management::refs, refs -> refs.forEach(r -> expire(r, report)), report);
            pages(Management.Section.TABLES, management::tables,
                    tables -> tables.forEach(t -> maintain(t, now, report)), report);
            List<Management.RequestWork> requests = new ArrayList<>();
            pages(Management.Section.REQUESTS, management::requests, requests::addAll, report);
            report.closed += (int) requests.stream().filter(r -> r.table().closed()).count();
            requested.compact(requests, report);
            compact(report);
            if (reaches) {
                requested.scanOrphans(requests, () -> compact(report), report);
                scanOrphans(now, report);
            }
            // Read whole even when the storage reaches nothing, as a scan that succeeds reads the work list.
            pages(Management.Section.PURGES, management::purges, purges -> {
                if (reaches) {
                    purges.forEach(p -> purge(p, report));
                }
            }, report);
            pages(Management.Section.DELETIONS, management::deletions, due -> {
                if (reaches) {
                    delete(due, report);
                }
            }, report);
            ended = true;
        } catch (RuntimeException e) {
            error = Optional.of(e.toString());
            throw e;
        } finally {
            // As WorkerMetrics judges it: a scan succeeds when it read the whole work list.
            health.scanEnded(id, now, clock.instant(),
                    ended && !report.failuresByKind.containsKey(ScanReport.Failure.WORK_LIST), error,
                    reached.map(read -> storage.reach()), report);
        }
    }

    /** Reads one section of the work list, page by page, each read a part of the scan, and hands on each page. */
    private <T> void pages(Management.Section section, Function<Optional<String>, Management.Page<T>> list,
                           Consumer<List<T>> each, ScanReport report) {
        String name = section.name().toLowerCase(Locale.ROOT);
        String what = "the " + name + " section";
        Optional<String> token = Optional.empty();
        do {
            Management.Page<T> page;
            try {
                Optional<String> from = token;
                page = spans.part(ScanSpans.Part.WORK_LIST, KeyValues.of(ScanSpans.SECTION, name),
                        () -> list.apply(from), read -> ScanSpans.OK);
            } catch (RuntimeException e) {
                LOG.warn("Cannot read {} of the work list", what, e);
                report.failed(ScanReport.Failure.WORK_LIST, what, e);
                return;
            }
            each.accept(page.items());
            token = page.nextToken();
        } while (token.isPresent());
    }

    // ---- storage prefixes ----

    /**
     * Reads the storage prefixes the catalog gives the worker at this scan (item 256; D30, amended 2026-11-24) and
     * hands them to its storage, which then lists and deletes strictly beneath them alone. When the read fails, the
     * storage reaches nothing, no earlier answer kept; when it reaches nothing, read or not, this scan's orphan scans,
     * purges and deletions wait for a scan that reaches some. Each profile or prefix the worker refuses is a failure
     * of the scan. The prefix set is logged at the first read and whenever it changes, each new prefix at WARN: the
     * one record of where the worker deletes that the catalog cannot suppress.
     *
     * @return the prefixes the storage reaches this scan, none when the read failed
     */
    private Optional<Prefixes> reach(ScanReport report) {
        List<Management.StorageProfile> profiles;
        try {
            profiles = management.storageProfiles();
        } catch (RuntimeException e) {
            storage.reach(List.of());
            LOG.warn("Cannot read the storage prefixes the catalog gives the worker: it lists and deletes nothing"
                    + " this scan", e);
            report.failed(ScanReport.Failure.STORAGE_PREFIXES, "reading the storage prefixes", e);
            return Optional.empty();
        }
        Prefixes reached = storage.reach(profiles);
        logChanges(reached);
        reached.refused().forEach(refused -> report.failed(ScanReport.Failure.STORAGE_PREFIXES, refused));
        if (reached.isEmpty()) {
            LOG.warn("The catalog gives the worker no storage prefix it reaches: it lists and deletes nothing this"
                    + " scan");
        }
        return Optional.of(reached);
    }

    /** Logs the prefix set at the first read, then each prefix added (WARN) or gone, and each new refusal (WARN). */
    private void logChanges(Prefixes reached) {
        Prefixes before = logged;
        logged = reached;
        if (before == null) {
            LOG.info("The worker lists and deletes strictly beneath the storage prefixes the catalog gives it: {}",
                    reached);
        } else {
            reached.prefixes().stream().filter(prefix -> !before.prefixes().contains(prefix)).forEach(prefix ->
                    LOG.warn("The catalog gives the worker a new storage prefix: it now lists and deletes strictly"
                            + " beneath {}", prefix));
            before.prefixes().stream().filter(prefix -> !reached.prefixes().contains(prefix)).forEach(prefix ->
                    LOG.info("The catalog no longer gives the worker the storage prefix {}: it lists and deletes there"
                            + " no more", prefix));
        }
        reached.refused().stream().filter(refused -> before == null || !before.refused().contains(refused))
                .forEach(refused -> LOG.warn("The worker refuses {}", refused));
    }

    // ---- refs ----

    /**
     * Warns of a branch whose expiry is near, or ends one past it (D33, choice 11), as the work list's refs section
     * says. Read before the tables, so an ended branch's files are no longer kept at this scan's jobs. One that was
     * renewed, ended or warned of since the list was read is left alone; a failure is reported and the scan goes on.
     */
    private void expire(Management.RefExpiry ref, ScanReport report) {
        try {
            if (Management.RefExpiry.END.equals(ref.action())) {
                if (management.endRef(ref.id())) {
                    report.refsEnded++;
                    LOG.info("Ended branch {} ({}) of {}, past its expiry {}", ref.name(), ref.id(), ref.catalog(),
                            ref.expiresAt());
                }
            } else if (management.warnRef(ref.id())) {
                report.refsWarned++;
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to {} branch {} ({})", ref.action(), ref.name(), ref.id(), e);
            report.failed(ScanReport.Failure.REFS, ref.action() + " of ref " + ref.id(), e);
        }
    }

    // ---- tables ----

    /**
     * One table's jobs that are due: expiry and manifest rewrite now; in the window, its compaction assessment now and
     * its orphan scan noted for after the night's Jobs have started. Package-private so a test can hand it a stale
     * work item.
     */
    void maintain(Management.TableWork work, Instant now, ScanReport report) {
        report.tables++;
        if (work.closed()) {
            report.closed++;
        }
        boolean expire = work.runs("snapshot-expiry.enabled");
        boolean rewrite = work.runs("manifest-rewrite.enabled");
        Optional<CleanupWindow.Night> open = open();
        boolean orphans = open.isPresent() && work.runs("orphan-removal.enabled")
                && orphanScanDue(work.id(), open.get());
        boolean assess = open.isPresent() && work.runs("compaction.enabled") && compactor != Compactor.NONE
                && !tonight.assessed(work.id());
        if (!(expire || rewrite || assess)) {
            if (orphans) {
                orphansDue.add(work);
            }
            return;
        }
        Optional<Table> loaded = load(work, report);
        if (loaded.isEmpty()) {
            return;
        }
        Table table = loaded.get();
        if (expire) {
            Instant started = clock.instant();
            AtomicLong expired = new AtomicLong();
            boolean done = job(ScanReport.Failure.SNAPSHOT_EXPIRY, ScanSpans.Part.SNAPSHOT_EXPIRY,
                    "expiring snapshots of", work, report, () -> {
                Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                expired.set(TableJobs.expire(table, now, work.duration("snapshot-expiry"
                        + ".max-snapshot-age"), (int) work.count("snapshot-expiry.min-snapshots-to-keep"),
                        unreferenced::add));
                report.snapshotsExpired += expired.get();
                record(work, table, unreferenced, report);
            });
            health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, work.id(), started, clock.instant(), !done,
                    HealthReports.Counts.expired(expired.get()), report);
        }
        if (rewrite) {
            Instant started = clock.instant();
            AtomicLong in = new AtomicLong();
            AtomicLong out = new AtomicLong();
            boolean done = job(ScanReport.Failure.MANIFEST_REWRITE, ScanSpans.Part.MANIFEST_REWRITE,
                    "rewriting manifests of", work, report, () -> {
                Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                in.set(TableJobs.rewriteManifests(table, work.count("manifest-rewrite.min-manifests"),
                        unreferenced::add));
                report.manifestsRewritten += in.get();
                if (in.get() > 0) {
                    out.set(created(table));
                }
                record(work, table, unreferenced, report);
            });
            health.ran(HealthReports.Job.MANIFEST_REWRITE, work.id(), started, clock.instant(), !done,
                    HealthReports.Counts.files(in.get(), out.get()), report);
        }
        if (assess && open().isPresent()) {
            job(ScanReport.Failure.COMPACTION, ScanSpans.Part.COMPACTION_ASSESSMENT, "assessing compaction of", work,
                    report, () -> tonight.assess(work, table, report));
        }
        if (orphans) {
            orphansDue.add(work);
        }
    }

    /** How many manifests the table's last commit, a manifest rewrite, wrote. */
    private static long created(Table table) {
        String created = table.currentSnapshot().summary().get("manifests-created");
        return created == null ? 0 : Long.parseLong(created);
    }

    /**
     * The table as the work list named it, loaded by name; none when it cannot be loaded or another table now has the
     * name, each reported, or when the work list says it is closed until classified, which is named, not loaded.
     */
    private Optional<Table> load(Management.TableWork work, ScanReport report) {
        if (work.closed()) {
            // Closed until classified (D27): the catalog would refuse the load and audit the refusal at every scan,
            // so the worker leaves the table alone, names it, and the gauge of closed tables shows it (backlog 334).
            report.skippedClosed(work);
            return Optional.empty();
        }
        Table table;
        try {
            table = spans.part(ScanSpans.Part.TABLE_LOAD, KeyValues.empty(),
                    () -> catalogs.load(work.catalog(), work.namespace(), work.name()),
                    loaded -> sameTable(work, loaded) ? "loaded" : "changed");
        } catch (RuntimeException e) {
            LOG.warn("Cannot load table {} ({})", work.name(), work.id(), e);
            report.failed(ScanReport.Failure.TABLE_LOAD, work, "loading", e);
            return Optional.empty();
        }
        if (!sameTable(work, table)) {
            // Dropped, renamed or unregistered, and another table now has the name, since the work list was read:
            // its own policy is not this one, so nothing here runs on it. The next scan sees it as it is.
            report.failed(ScanReport.Failure.TABLE_CHANGED, work, "changed", "table " + work.id()
                    + " is now another table, " + table.uuid());
            return Optional.empty();
        }
        return Optional.of(table);
    }

    /** Whether the table loaded is the one the work list named, by id. */
    private static boolean sameTable(Management.TableWork work, Table table) {
        return work.id().toString().equals(table.uuid().toString());
    }

    /** Tonight's opening of the cleanup window, if it is open now. */
    private Optional<CleanupWindow.Night> open() {
        return tonight != null && tonight.night.contains(clock.instant()) ? Optional.of(tonight.night)
                : Optional.empty();
    }

    /** Whether the table is due an orphan scan tonight: none yet tonight, and the orphan interval's nights passed. */
    private boolean orphanScanDue(UUID table, CleanupWindow.Night night) {
        LocalDate last = orphanScans.get(table);
        long nights = Math.max(1, (orphanInterval.toSeconds() + Duration.ofDays(1).toSeconds() - 1)
                / Duration.ofDays(1).toSeconds());
        return last == null || !night.date().isBefore(last.plusDays(nights));
    }

    /** Starts the night's compaction Jobs that can start now; see {@link NightlyCompaction#launch}. */
    void compact(ScanReport report) {
        if (tonight != null) {
            tonight.launch(clock, report);
        }
    }

    /**
     * The orphan scans this scan found due, one table after another while the window is open. Before each, Jobs whose
     * slots have freed are started, so a long run of scans does not hold compaction back.
     */
    private void scanOrphans(Instant now, ScanReport report) {
        for (Management.TableWork work : orphansDue) {
            Optional<CleanupWindow.Night> open = open();
            if (open.isEmpty()) {
                return;
            }
            compact(report);
            Optional<Table> loaded = load(work, report);
            if (loaded.isEmpty()) {
                continue;
            }
            Table table = loaded.get();
            CleanupWindow.Night night = open.get();
            Instant started = clock.instant();
            boolean[] cutShort = {false};
            boolean done = job(ScanReport.Failure.ORPHAN_SCAN, ScanSpans.Part.ORPHAN_SCAN, "scanning for orphans of",
                    work, report, () -> {
                List<String> found;
                try {
                    found = OrphanScan.find(table, storage, now.minus(work.duration("orphan-removal.older-than")),
                            () -> night.contains(clock.instant()), () -> { });
                } catch (OrphanScan.CutShort e) {
                    // Not recorded: the table is scanned again the next night.
                    report.orphanScansCutShort++;
                    cutShort[0] = true;
                    LOG.info("The window's end cut short the orphan scan of table {} ({})", work.name(), work.id());
                    return;
                }
                report.orphanScans++;
                report.orphansFound += found.size();
                record(work, table, found, report);
                // Tonight's only once it is done: a scan that failed is tried again at the next scan (backlog 5).
                orphanScans.put(work.id(), night.date());
            });
            health.job(UUID.randomUUID(), night, HealthReports.Job.ORPHAN_SCAN, work.id(), Optional.empty(), started,
                    Optional.of(clock.instant()), !done ? "failed" : cutShort[0] ? "cut-off" : "succeeded",
                    HealthReports.Counts.NONE, report);
        }
    }

    /**
     * One job on a table, as a part of the scan; a failure is reported as {@code kind}, and the scan goes on.
     *
     * @return whether it ran without failing
     */
    private boolean job(ScanReport.Failure kind, ScanSpans.Part part, String what, Management.TableWork work,
                        ScanReport report, Runnable job) {
        try {
            spans.part(part, job);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Failed {} table {} ({})", what, work.name(), work.id(), e);
            report.failed(kind, work, what, e);
            return false;
        }
    }

    /**
     * Hands files a job left unreferenced to the catalog's pending-deletion list. Only those beneath the table's own
     * location as written, no segment empty, {@code .} or {@code ..}: the catalog refuses any other, which would
     * refuse the whole batch, and a file elsewhere is not the worker's to delete.
     *
     * <p>Only files no catalog ref reaches either (D33, item 26's choice 14): each ref the work list named for the
     * table is read, and the record names those refs. When the catalog answers that a ref naming the table was made
     * since, it hands back the table's refs now, and the files are checked against those, up to {@link #REF_READS}
     * times in all.
     */
    private void record(Management.TableWork work, Table table, Collection<String> files, ScanReport report) {
        if (files.isEmpty()) {
            return;
        }
        String root = table.location().endsWith("/") ? table.location() : table.location() + "/";
        List<String> beneath = new ArrayList<>();
        for (String file : files) {
            if (beneath(file, root)) {
                beneath.add(file);
            } else {
                report.filesOutside++;
            }
        }
        // Main's own snapshots reach none of the files, which its last job left unreferenced: not read again.
        Set<Long> onMain = new HashSet<>();
        table.snapshots().forEach(snapshot -> onMain.add(snapshot.snapshotId()));
        List<Management.RefWork> refs = work.refs();
        for (int read = 1; ; read++) {
            RefReach.Split split = RefReach.split(beneath, refs, table.io(), onMain);
            try {
                management.recordDeletions(work.catalog(), work.id(), split.unreached(),
                        refs.stream().map(Management.RefWork::id).toList());
            } catch (Management.RefsChanged e) {
                if (read == REF_READS) {
                    throw e;
                }
                refs = e.refs;
                continue;
            }
            report.filesRecorded += split.unreached().size();
            report.kept(split.kept());
            return;
        }
    }

    /** How many times a record of deletions reads the table's refs before it gives up until the next scan. */
    static final int REF_READS = 3;

    /** The catalog's own test for a recorded file ({@code MaintenanceRoutes.beneath}). */
    static boolean beneath(String location, String root) {
        if (!location.startsWith(root) || location.length() == root.length()) {
            return false;
        }
        for (String segment : location.substring(root.length()).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    // ---- purges ----

    /** One purge, reported as a job of the night once claimed: its files and bytes deleted, failed or not. */
    private void purge(Management.PurgeWork purge, ScanReport report) {
        Instant started = clock.instant();
        Purging purging = new Purging();
        try {
            spans.part(ScanSpans.Part.PURGE, KeyValues.empty(), () -> purged(purge, purging, report),
                    outcome -> outcome);
        } catch (RuntimeException e) {
            LOG.warn("Failed purging {} ({})", purge.name(), purge.id(), e);
            report.failed(ScanReport.Failure.PURGE, "purging " + purge.id(), e);
            purging.failed = true;
        }
        if (purging.claimed) {
            health.job(UUID.randomUUID(), window.last(started), HealthReports.Job.PURGE, purge.id(),
                    Optional.empty(), started, Optional.of(clock.instant()), purging.failed ? "failed" : "succeeded",
                    HealthReports.Counts.deleted(purging.files, purging.bytes), report);
        }
    }

    /** How a purge went so far. */
    private static final class Purging {
        boolean claimed;
        boolean failed;
        long files;
        long bytes;
    }

    /** Claims the object's purge, deletes its files and completes it: {@code purged}, or {@code skipped}. */
    private String purged(Management.PurgeWork purge, Purging purging, ScanReport report) {
        Management.Claim claim = management.claimPurge(purge.catalog(), purge.id());
        if (claim != Management.Claim.CLAIMED) {
            report.purgesSkipped++;
            return "skipped";
        }
        purging.claimed = true;
        String root = purge.location().endsWith("/") ? purge.location() : purge.location() + "/";
        List<String> batch = new ArrayList<>(Management.BATCH);
        long bytes = 0;
        for (FileInfo file : storage.list(root)) {
            batch.add(file.location());
            bytes += file.size();
            if (batch.size() == Management.BATCH) {
                storage.delete(batch);
                deleted(batch.size(), bytes, purging, report);
                batch.clear();
                bytes = 0;
            }
        }
        if (!batch.isEmpty()) {
            storage.delete(batch);
            deleted(batch.size(), bytes, purging, report);
        }
        management.completePurge(purge.catalog(), purge.id());
        report.purged++;
        return "purged";
    }

    private static void deleted(int files, long bytes, Purging purging, ScanReport report) {
        report.filesDeleted += files;
        purging.files += files;
        purging.bytes += bytes;
    }

    // ---- pending deletions ----

    private void delete(List<Management.DeletionWork> due, ScanReport report) {
        Map<List<Object>, List<Management.DeletionWork>> byObject = new LinkedHashMap<>();
        due.stream().filter(deletion -> !deletion.catalog().isEmpty()).forEach(deletion -> byObject.computeIfAbsent(
                List.of(deletion.catalog(), deletion.objectId()), key -> new ArrayList<>()).add(deletion));
        byObject.forEach((key, deletions) -> {
            String catalog = (String) key.get(0);
            UUID object = (UUID) key.get(1);
            Instant started = clock.instant();
            AtomicLong bytes = new AtomicLong();
            AtomicLong deleted = new AtomicLong();
            boolean failed = false;
            try {
                spans.part(ScanSpans.Part.DELETION, () -> {
                    Rechecked checked = recheck(catalog, object, deletions, report);
                    List<String> locations = checked.delete().stream().map(Management.DeletionWork::location).toList();
                    if (!locations.isEmpty()) {
                        locations.forEach(location -> bytes.addAndGet(size(location)));
                        storage.delete(locations);
                    }
                    List<UUID> done = new ArrayList<>();
                    checked.delete().forEach(deletion -> done.add(deletion.id()));
                    checked.keep().forEach(deletion -> done.add(deletion.id()));
                    if (!done.isEmpty()) {
                        management.completeDeletions(catalog, object, done);
                    }
                    deleted.set(locations.size());
                    report.filesDeleted += locations.size();
                    report.kept(checked.kept());
                });
            } catch (RuntimeException e) {
                LOG.warn("Failed deleting {} files of {}", deletions.size(), object, e);
                report.failed(ScanReport.Failure.DELETION, "deleting files of " + object, e);
                failed = true;
            }
            health.ran(HealthReports.Job.DELETION, object, started, clock.instant(), failed,
                    failed ? HealthReports.Counts.NONE : HealthReports.Counts.deleted(deleted.get(), bytes.get()),
                    report);
        });
    }

    /** One object's due deletions as the recheck splits them: to delete, to complete without deleting, kept by kind. */
    private record Rechecked(List<Management.DeletionWork> delete, List<Management.DeletionWork> keep,
                             Map<Management.RefKind, Integer> kept) {
    }

    /**
     * Checks one object's due deletions against the refs made since each was recorded (D33, choice 14), whose metadata
     * it reads with the table's own access, the worker's identity reading nothing: one such a ref reaches is completed
     * without being deleted, the ref keeping it. While the table cannot be loaded, being dropped or failing to load,
     * those deletions wait; a dropped table's files go with its purge, which a ref naming it also stops. A deletion no
     * ref made since names is deleted.
     */
    private Rechecked recheck(String catalog, UUID object, List<Management.DeletionWork> deletions,
                              ScanReport report) {
        List<Management.DeletionWork> later = deletions.stream().filter(deletion -> !deletion.refs().isEmpty())
                .toList();
        if (later.isEmpty()) {
            return new Rechecked(deletions, List.of(), Map.of());
        }
        List<Management.DeletionWork> delete = new ArrayList<>(deletions.stream()
                .filter(deletion -> deletion.refs().isEmpty()).toList());
        Optional<Table> table = later.getFirst().table().flatMap(named -> load(new Management.TableWork(catalog,
                object, named.namespace(), named.name(), "", Map.of()), report));
        if (table.isEmpty()) {
            LOG.info("{} due files of {} wait: refs made since they were recorded name it, and it cannot be loaded",
                    later.size(), object);
            return new Rechecked(delete, List.of(), Map.of());
        }
        Map<UUID, Management.RefWork> refs = new LinkedHashMap<>();
        later.forEach(deletion -> deletion.refs().forEach(ref -> refs.putIfAbsent(ref.id(), ref)));
        RefReach.Split split = RefReach.split(later.stream().map(Management.DeletionWork::location).toList(),
                List.copyOf(refs.values()), table.get().io(), Set.of());
        Set<String> unreached = Set.copyOf(split.unreached());
        List<Management.DeletionWork> keep = new ArrayList<>();
        later.forEach(deletion -> (unreached.contains(deletion.location()) ? delete : keep).add(deletion));
        return new Rechecked(delete, keep, split.kept());
    }

    /**
     * A file's size as the store lists it, by its own key: the worker's identity may list and delete, never read.
     * Nothing when it is gone already or cannot be listed: its deletion goes on.
     */
    private long size(String location) {
        String wanted = Storage.normalize(location);
        try {
            for (FileInfo file : storage.list(location)) {
                if (Storage.normalize(file.location()).equals(wanted)) {
                    return file.size();
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Cannot list {} to size it", location, e);
        }
        return 0;
    }

    @Override
    public void close() {
        try {
            catalogs.close();
        } finally {
            storage.close();
        }
    }
}
