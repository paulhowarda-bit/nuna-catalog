package keel.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the maintenance worker needs beyond the other groups (D30): every active table of the
 * deployment, to judge which are due for maintenance, and the durable list of files it deletes once
 * the database's PITR window has passed. Writes behave as in {@link NamespaceStore}.
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
}
