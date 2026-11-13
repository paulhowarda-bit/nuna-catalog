package keel.maintenance;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *       and orphan removal, a DR freeze every job, and only active tables are listed): snapshot expiry, manifest
 *       rewrite, an orphan scan at most once per orphan interval, and a compaction Job at most once per compaction
 *       interval. Nothing here deletes: every file a job leaves unreferenced goes to the catalog's pending-deletion
 *       list, which holds it for the database's PITR window (D30 (5), (8)).</li>
 *   <li><b>Purges</b>: each dropped object past its window is claimed, everything under its location deleted, and
 *       the purge completed (D30 (6)). A worker that died part-way gets its own claim back and starts again.</li>
 *   <li><b>Deletions</b> the catalog lists as due: deleted with the worker's own storage identity, then reported
 *       done.</li>
 * </ol>
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
    private final Duration orphanInterval;
    private final Duration compactionInterval;
    /** When each table's last orphan scan and compaction started, by table id; empty after a restart. */
    private final Map<UUID, Instant> orphanScans = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> compactions = new ConcurrentHashMap<>();

    Worker(Management management, Catalogs catalogs, Storage storage, Compactor compactor, Clock clock,
           Duration orphanInterval, Duration compactionInterval) {
        this.management = management;
        this.catalogs = catalogs;
        this.storage = storage;
        this.compactor = compactor;
        this.clock = clock;
        this.orphanInterval = orphanInterval;
        this.compactionInterval = compactionInterval;
    }

    /** The worker the settings describe. */
    static Worker of(WorkerSettings settings, Clock clock) {
        TokenSource tokens = TokenSource.of(settings.token(), clock);
        Management management = new Management(settings.catalogUri(), tokens, HttpClient.newHttpClient(),
                settings.pageSize());
        Compactor compactor = settings.compaction().enabled()
                ? new KubernetesJobs(settings.compaction(), settings.compactionInterval(), settings.catalogUri(), tokens)
                : Compactor.NONE;
        return new Worker(management, new Catalogs(settings.catalogUri(), tokens, Map.of()),
                Storage.of(settings.storage()), compactor, clock, settings.orphanInterval(),
                settings.compactionInterval());
    }

    /** One scan: the tables, then the purges, then the due deletions. */
    ScanReport runOnce() {
        ScanReport report = new ScanReport();
        Instant now = clock.instant();
        pages("the tables section", management::tables, tables -> tables.forEach(t -> maintain(t, now, report)), report);
        pages("the purges section", management::purges, purges -> purges.forEach(p -> purge(p, report)), report);
        pages("the deletions section", management::deletions, due -> delete(due, report), report);
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
                report.failed(what, e);
                return;
            }
            each.accept(page.items());
            token = page.nextToken();
        } while (token.isPresent());
    }

    // ---- tables ----

    private void maintain(Management.TableWork work, Instant now, ScanReport report) {
        report.tables++;
        boolean expire = work.runs("snapshot-expiry.enabled");
        boolean rewrite = work.runs("manifest-rewrite.enabled");
        boolean orphans = work.runs("orphan-removal.enabled") && due(orphanScans, work.id(), orphanInterval, now);
        boolean compact = work.runs("compaction.enabled") && compactor != Compactor.NONE
                && due(compactions, work.id(), compactionInterval, now);
        if (expire || rewrite || orphans) {
            Table table;
            try {
                table = catalogs.load(work.catalog(), work.namespace(), work.name());
            } catch (RuntimeException e) {
                LOG.warn("Cannot load table {} ({})", work.name(), work.id(), e);
                report.failed("loading " + work.id(), e);
                return;
            }
            if (!work.id().toString().equals(table.uuid().toString())) {
                // Dropped and created again under the name since the work list was read: the next scan sees it.
                report.failures.add("table " + work.id() + " is now another table, " + table.uuid());
                return;
            }
            if (expire) {
                job("expiring snapshots of", work, report, () -> {
                    Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                    report.snapshotsExpired += TableJobs.expire(table, now, work.duration("snapshot-expiry"
                            + ".max-snapshot-age"), (int) work.count("snapshot-expiry.min-snapshots-to-keep"),
                            unreferenced::add);
                    record(work, table, unreferenced, report);
                });
            }
            if (rewrite) {
                job("rewriting manifests of", work, report, () -> {
                    Set<String> unreferenced = ConcurrentHashMap.newKeySet();
                    report.manifestsRewritten += TableJobs.rewriteManifests(table,
                            work.count("manifest-rewrite.min-manifests"), unreferenced::add);
                    record(work, table, unreferenced, report);
                });
            }
            if (orphans) {
                job("scanning for orphans of", work, report, () -> {
                    orphanScans.put(work.id(), now);
                    List<String> found = OrphanScan.find(table, storage,
                            now.minus(work.duration("orphan-removal.older-than")), () -> { });
                    report.orphanScans++;
                    report.orphansFound += found.size();
                    record(work, table, found, report);
                });
            }
        }
        if (compact) {
            job("launching compaction of", work, report, () -> {
                Compactor.Launch launch = compactor.launch(work);
                compactions.put(work.id(), now);
                if (launch == Compactor.Launch.STARTED) {
                    report.compactionsStarted++;
                }
            });
        }
    }

    private static boolean due(Map<UUID, Instant> last, UUID table, Duration interval, Instant now) {
        Instant then = last.get(table);
        return then == null || !now.isBefore(then.plus(interval));
    }

    private static void job(String what, Management.TableWork work, ScanReport report, Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            LOG.warn("Failed {} table {} ({})", what, work.name(), work.id(), e);
            report.failed(what + " " + work.id(), e);
        }
    }

    /**
     * Hands files a job left unreferenced to the catalog's pending-deletion list. Only those under the table's own
     * location: the catalog refuses any other, and a file elsewhere is not the worker's to delete.
     */
    private void record(Management.TableWork work, Table table, Collection<String> files, ScanReport report) {
        if (files.isEmpty()) {
            return;
        }
        String root = table.location().endsWith("/") ? table.location() : table.location() + "/";
        List<String> beneath = new ArrayList<>();
        for (String file : files) {
            if (file.startsWith(root)) {
                beneath.add(file);
            } else {
                report.filesOutside++;
            }
        }
        management.recordDeletions(work.catalog(), work.id(), beneath);
        report.filesRecorded += beneath.size();
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
            report.failed("purging " + purge.id(), e);
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
                report.failed("deleting files of " + object, e);
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
