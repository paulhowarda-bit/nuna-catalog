package keel.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>Tables</b> whose resolved policy runs a job, with the catalog's guardrails applied (a hold stops expiry
 *       and orphan removal, a DR freeze every job, and only active tables are listed): snapshot expiry and manifest
 *       rewrite at every scan. While the nightly cleanup window is open (D30, amended 2026-11-15), each table whose
 *       policy turns compaction on is assessed once a night from its manifests, and one whose policy turns orphan
 *       removal on is due an orphan scan, at most once a night and once per orphan interval. Each table is loaded by
 *       name first and left alone unless it is still the table the work list named, by id. Nothing here deletes:
 *       every file a job leaves unreferenced goes to the catalog's pending-deletion list, which holds it for the
 *       database's PITR window (D30 (5), (8)).</li>
 *   <li><b>Compaction</b>: Jobs for the tables that need one, neediest first, as many at once as the settings allow,
 *       each to stop at the window's end ({@link NightlyCompaction}).</li>
 *   <li><b>Orphan scans</b> due tonight, one after another while the window is open. One the window's end cuts short
 *       is not recorded, and its table is scanned again the next night.</li>
 *   <li><b>Purges</b>: each dropped object past its window is claimed, everything under its location deleted, and
 *       the purge completed (D30 (6)). A worker that died part-way gets its own claim back and starts again.</li>
 *   <li><b>Deletions</b> the catalog lists as due: deleted with the worker's own storage identity, then reported
 *       done.</li>
 * </ol>
 *
 * <p>Outside the window nothing heavy starts, after a restart too: expiry, manifest rewrite, purges and deletions
 * run round the clock. The first scan after the window closes ends the night: it counts the Jobs the window's end
 * cut off and the needy tables left over.
 *
 * <p>Every job is idempotent: a scan that runs again after a crash, or sees the effect of its own last commit,
 * finds less to do or records files already pending, which the catalog keeps as they were. One table, purge or
 * file group failing is reported and the scan moves on.
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
    private final int compactionJobs;
    /** The night of each table's last orphan scan not cut short, by table id; empty after a restart. */
    private final Map<UUID, LocalDate> orphanScans = new ConcurrentHashMap<>();
    /** The window's opening now under way, or the last one until a scan has closed it; none before the first. */
    private NightlyCompaction tonight;
    /** The tables this scan found due an orphan scan. */
    private final List<Management.TableWork> orphansDue = new ArrayList<>();
    private final WorkerMetrics metrics;

    /**
     * @param window         the nightly cleanup window: compaction and orphan scans start only inside it
     * @param orphanInterval the least time between two orphan scans of one table, rounded up to whole nights
     * @param compactionJobs how many compaction Jobs run at once
     */
    Worker(Management management, Catalogs catalogs, Storage storage, Compactor compactor, Clock clock,
           CleanupWindow window, Duration orphanInterval, int compactionJobs, MeterRegistry meters) {
        this.management = management;
        this.catalogs = catalogs;
        this.storage = storage;
        this.compactor = compactor;
        this.clock = clock;
        this.window = window;
        this.orphanInterval = orphanInterval;
        this.compactionJobs = compactionJobs;
        this.metrics = new WorkerMetrics(meters, clock);
    }

    /** The worker the settings describe. */
    static Worker of(WorkerSettings settings, Clock clock, MeterRegistry meters) {
        TokenSource tokens = TokenSource.of(settings.token(), clock);
        Management management = new Management(settings.catalogUri(), tokens, HttpClient.newHttpClient(),
                settings.pageSize());
        Compactor compactor = settings.compaction().enabled()
                ? new KubernetesJobs(settings.compaction(), settings.catalogUri(), tokens, clock)
                : Compactor.NONE;
        return new Worker(management, new Catalogs(settings.catalogUri(), tokens, Map.of()),
                Storage.of(settings.storage()), compactor, clock, settings.cleanupWindow(), settings.orphanInterval(),
                settings.compaction().maxJobs(), meters);
    }

    /**
     * One scan: the tables, the night's compaction Jobs and orphan scans, then the purges, then the due deletions;
     * recorded as meters ({@link WorkerMetrics}).
     */
    ScanReport runOnce() {
        ScanReport report = new ScanReport();
        metrics.scan(report, () -> {
            Instant now = clock.instant();
            Optional<CleanupWindow.Night> night = window.night(now);
            if (tonight != null && !night.equals(Optional.of(tonight.night))) {
                tonight.close(report);
                tonight = null;
            }
            if (night.isPresent() && tonight == null) {
                tonight = new NightlyCompaction(night.get(), compactor, compactionJobs, metrics);
            }
            orphansDue.clear();
            pages("the tables section", management::tables, tables -> tables.forEach(t -> maintain(t, now, report)),
                    report);
            compact(report);
            scanOrphans(now, report);
            pages("the purges section", management::purges, purges -> purges.forEach(p -> purge(p, report)), report);
            pages("the deletions section", management::deletions, due -> delete(due, report), report);
        });
        return report;
    }

    private <T> void pages(String what, Function<Optional<String>, Management.Page<T>> list,
                           Consumer<List<T>> each, ScanReport report) {
        Optional<String> token = Optional.empty();
        do {
            Management.Page<T> page;
            try {
                page = list.apply(token);
            } catch (RuntimeException e) {
                LOG.warn("Cannot read {} of the work list", what, e);
                report.failed(ScanReport.Failure.WORK_LIST, what, e);
                return;
            }
            each.accept(page.items());
            token = page.nextToken();
        } while (token.isPresent());
    }

    // ---- tables ----

    /**
     * One table's jobs that are due: expiry and manifest rewrite now; in the window, its compaction assessment now and
     * its orphan scan noted for after the night's Jobs have started. Package-private so a test can hand it a stale
     * work item.
     */
    void maintain(Management.TableWork work, Instant now, ScanReport report) {
        report.tables++;
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
            job(ScanReport.Failure.SNAPSHOT_EXPIRY, "expiring snapshots of", work, report, () -> {
                Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                report.snapshotsExpired += TableJobs.expire(table, now, work.duration("snapshot-expiry"
                        + ".max-snapshot-age"), (int) work.count("snapshot-expiry.min-snapshots-to-keep"),
                        unreferenced::add);
                record(work, table, unreferenced, report);
            });
        }
        if (rewrite) {
            job(ScanReport.Failure.MANIFEST_REWRITE, "rewriting manifests of", work, report, () -> {
                Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                report.manifestsRewritten += TableJobs.rewriteManifests(table,
                        work.count("manifest-rewrite.min-manifests"), unreferenced::add);
                record(work, table, unreferenced, report);
            });
        }
        if (assess && open().isPresent()) {
            job(ScanReport.Failure.COMPACTION, "assessing compaction of", work, report,
                    () -> tonight.assess(work, table, report));
        }
        if (orphans) {
            orphansDue.add(work);
        }
    }

    /**
     * The table as the work list named it, loaded by name; none when it cannot be loaded or another table now has the
     * name, each reported.
     */
    private Optional<Table> load(Management.TableWork work, ScanReport report) {
        Table table;
        try {
            table = catalogs.load(work.catalog(), work.namespace(), work.name());
        } catch (RuntimeException e) {
            LOG.warn("Cannot load table {} ({})", work.name(), work.id(), e);
            report.failed(ScanReport.Failure.TABLE_LOAD, "loading " + work.id(), e);
            return Optional.empty();
        }
        if (!work.id().toString().equals(table.uuid().toString())) {
            // Dropped, renamed or unregistered, and another table now has the name, since the work list was read:
            // its own policy is not this one, so nothing here runs on it. The next scan sees it as it is.
            report.failed(ScanReport.Failure.TABLE_CHANGED, "table " + work.id() + " is now another table, "
                    + table.uuid());
            return Optional.empty();
        }
        return Optional.of(table);
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
            job(ScanReport.Failure.ORPHAN_SCAN, "scanning for orphans of", work, report, () -> {
                LocalDate previous = orphanScans.put(work.id(), night.date());
                List<String> found;
                try {
                    found = OrphanScan.find(table, storage, now.minus(work.duration("orphan-removal.older-than")),
                            () -> night.contains(clock.instant()), () -> { });
                } catch (OrphanScan.CutShort e) {
                    // Not recorded: the table is scanned again the next night.
                    if (previous == null) {
                        orphanScans.remove(work.id());
                    } else {
                        orphanScans.put(work.id(), previous);
                    }
                    report.orphanScansCutShort++;
                    LOG.info("The window's end cut short the orphan scan of table {} ({})", work.name(), work.id());
                    return;
                }
                report.orphanScans++;
                report.orphansFound += found.size();
                record(work, table, found, report);
            });
        }
    }

    private static void job(ScanReport.Failure kind, String what, Management.TableWork work, ScanReport report,
                            Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            LOG.warn("Failed {} table {} ({})", what, work.name(), work.id(), e);
            report.failed(kind, what + " " + work.id(), e);
        }
    }

    /**
     * Hands files a job left unreferenced to the catalog's pending-deletion list. Only those beneath the table's own
     * location as written, no segment empty, {@code .} or {@code ..}: the catalog refuses any other, which would
     * refuse the whole batch, and a file elsewhere is not the worker's to delete.
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
        management.recordDeletions(work.catalog(), work.id(), beneath);
        report.filesRecorded += beneath.size();
    }

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

    private void purge(Management.PurgeWork purge, ScanReport report) {
        try {
            Management.Claim claim = management.claimPurge(purge.catalog(), purge.id());
            if (claim != Management.Claim.CLAIMED) {
                report.purgesSkipped++;
                return;
            }
            String root = purge.location().endsWith("/") ? purge.location() : purge.location() + "/";
            List<String> batch = new ArrayList<>(Management.BATCH);
            for (FileInfo file : storage.list(root)) {
                batch.add(file.location());
                if (batch.size() == Management.BATCH) {
                    storage.delete(batch);
                    report.filesDeleted += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                storage.delete(batch);
                report.filesDeleted += batch.size();
            }
            management.completePurge(purge.catalog(), purge.id());
            report.purged++;
        } catch (RuntimeException e) {
            LOG.warn("Failed purging {} ({})", purge.name(), purge.id(), e);
            report.failed(ScanReport.Failure.PURGE, "purging " + purge.id(), e);
        }
    }

    // ---- pending deletions ----

    private void delete(List<Management.DeletionWork> due, ScanReport report) {
        Map<List<Object>, List<Management.DeletionWork>> byObject = new LinkedHashMap<>();
        due.stream().filter(deletion -> !deletion.catalog().isEmpty()).forEach(deletion -> byObject.computeIfAbsent(
                List.of(deletion.catalog(), deletion.objectId()), key -> new ArrayList<>()).add(deletion));
        byObject.forEach((key, deletions) -> {
            String catalog = (String) key.get(0);
            UUID object = (UUID) key.get(1);
            try {
                storage.delete(deletions.stream().map(Management.DeletionWork::location).toList());
                management.completeDeletions(catalog, object,
                        deletions.stream().map(Management.DeletionWork::id).toList());
                report.filesDeleted += deletions.size();
            } catch (RuntimeException e) {
                LOG.warn("Failed deleting {} files of {}", deletions.size(), object, e);
                report.failed(ScanReport.Failure.DELETION, "deleting files of " + object, e);
            }
        });
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
