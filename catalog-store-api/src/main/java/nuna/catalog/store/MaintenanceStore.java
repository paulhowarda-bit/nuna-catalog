package nuna.catalog.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the maintenance worker needs beyond the other groups (D30): every active table of the
 * deployment, to judge which are due for maintenance, and the durable list of files it deletes once
 * the database's PITR window has passed. Writes behave as in {@link NamespaceStore}. And what the
 * worker reports of its own work, which the catalog keeps for the console (D38): each table's latest
 * assessment and last compaction, and its recent scans.
 */
public interface MaintenanceStore {

    /** Active objects of one kind, in every catalog, by id. */
    Page<CatalogObject> listActiveObjects(CatalogObject.Kind kind, PageRequest page);

    /**
     * Records files of an active or dropped object for deletion at or after {@code notBefore}, each
     * with a new id, at {@code change.at()}. A file already pending for the object is skipped and keeps
     * its earlier time, so the worker's scans, which find the same unreferenced files until they are
     * deleted, record each once.
     *
     * @return {@code Applied}; {@code Unchanged}, writing nothing, when every file was already pending;
     *         or {@code Missing(OBJECT)} when no active or dropped object has that id
     */
    WriteResult recordDeletions(UUID objectId, List<String> locations, Instant notBefore, Change change);

    /**
     * Pending deletions whose {@code notBefore} is at or before {@code now}, by {@code notBefore} then
     * id, of active or dropped objects that no legal hold or DR freeze covers. An object being purged,
     * purged or unregistered has none listed: a purge deletes every file, an unregister deletes nothing.
     */
    Page<PendingDeletion> listDueDeletions(Instant now, PageRequest page);

    /**
     * Removes the object's pending deletions named by {@code ids}, once their files are gone; an id
     * already removed, or of another object, is skipped.
     *
     * @return {@code Applied}, or {@code Unchanged} when none of {@code ids} was pending for the object
     */
    WriteResult completeDeletions(UUID objectId, List<UUID> ids, Change change);

    // ---- the worker's state, kept for the console's health dashboard (D38) ----
    //
    // These writes carry no change id and no audit record: they are the worker's reports of what it found and did,
    // not changes to the catalog (D23 audits changes). Each is safe to repeat, so a failure is clean.

    /**
     * Keeps the worker's assessment of an active table as its latest, in place of an earlier one; how its last
     * compaction went is kept. An assessment older than the one kept changes nothing, so a report repeated late never
     * overwrites a newer one. The object is share-locked, so a drop at the same moment waits; a purge's completion
     * deletes what is kept.
     *
     * @return {@code Applied}; {@code Unchanged} when a newer assessment is kept; or {@code Missing(OBJECT)} when no
     *         active table has that id
     */
    WriteResult recordAssessment(UUID objectId, TableAssessment assessment);

    /**
     * Keeps how an active table's compaction went as its last, in place of an earlier one; its assessment is kept. A
     * compaction that started before the one kept changes nothing, nor does one reported running once the same one
     * has ended.
     *
     * @return {@code Applied}; {@code Unchanged} when a later compaction, or this one ended, is kept; or
     *         {@code Missing(OBJECT)} when no active table has that id
     */
    WriteResult recordCompaction(UUID objectId, CompactionRun compaction);

    /** The table's health, in any state; empty when there is no such object or the worker reported nothing of it. */
    Optional<TableHealth> readTableHealth(UUID objectId);

    /**
     * The active tables whose latest assessment found them needing compaction, neediest first ({@link TableHealth.Rank}):
     * equality deletes first, then the highest score, then by id.
     */
    Page<TableHealth> listNeediestTables(PageRequest page);

    /**
     * Records a scan's start, or its end, by its id; a scan that has ended keeps its end. The groups and storage it
     * reports are kept with it, the end's in place of the start's (backlog 250). Then deletes every scan but the
     * {@code keep} that started last and the last that succeeded, so the catalog keeps recent failures and the last
     * success, however long ago.
     */
    void recordScan(MaintenanceScan scan, int keep);

    /**
     * The scans kept, the last started first. The first with storage holds what the worker last reached
     * ({@link MaintenanceScan#covering}); deletes since a time are the sum over the scans since.
     */
    List<MaintenanceScan> listScans();

    // ---- the worker's nights, kept for the console's overview and maintenance pages (D45, backlog 126) ----

    /**
     * Keeps a job's report by its id ({@link MaintenanceJob#replaces}: a report repeated late, or a running report of a
     * job that has ended, changes nothing; the job keeps its earlier start). The report's other fields replace the
     * kept ones, but for its night, kind and object, which stay as first reported. Then deletes every job of a night
     * before {@code keepFrom}. Like the other reports, it carries no change id and writes no audit record.
     */
    void recordMaintenanceJob(MaintenanceJob job, LocalDate keepFrom);

    /**
     * The nights on or before {@code through} that have a job, newest first, at most {@code limit}, each with its
     * totals summed over its jobs.
     */
    List<MaintenanceNight> listMaintenanceNights(LocalDate through, int limit);

    /** The night's jobs of these kinds, by start, then id. */
    List<MaintenanceJob> listMaintenanceJobs(LocalDate night, Set<MaintenanceJob.Kind> kinds);

    // ---- maintenance on request (D30, amended 2026-11-20; backlog 114) ----

    /**
     * Keeps a request to run a job of an active table now, writing the change's records with it. One per table and
     * job: while one waits, asking again changes nothing; one the worker has started is replaced. The table is
     * share-locked, so a drop at the same moment waits; a purge's completion deletes the table's requests.
     *
     * @return {@code Applied}; {@code Unchanged}, writing nothing, when a request for the job waits already; or
     *         {@code Missing(OBJECT)} when no active table has that id
     */
    WriteResult requestMaintenance(MaintenanceRequest request, Change change);

    /** The table's requests, waiting or started, one per job at most, by job; none for an unknown id. */
    List<MaintenanceRequest> listMaintenanceRequests(UUID objectId);

    /**
     * The waiting requests of active tables, in every catalog, by the time asked, then table, then job: the work
     * list's requested section.
     */
    Page<MaintenanceRequest> listWaitingRequests(PageRequest page);

    /**
     * Marks the table's waiting request for {@code job}, made at {@code requestedAt}, started at {@code startedAt}, as
     * the worker reports it. Like the worker's other reports it carries no change id and writes no audit record, and
     * it is safe to repeat.
     *
     * @return {@code Applied}; or {@code Unchanged} when no request of that time waits for the job
     */
    WriteResult startMaintenanceRequest(UUID objectId, MaintenanceRequest.Job job, Instant requestedAt,
                                        Instant startedAt);
}
