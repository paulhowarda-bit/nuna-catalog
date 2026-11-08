package fidice.catalog.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tables and views (build-plan 2.3, D25). Tables and views share one name space per namespace;
 * names are unique among active objects by their normalized form (storage-design 9 d). Writes
 * behave as in {@link NamespaceStore}.
 */
public interface CatalogObjectStore {

    /**
     * Creates the object and its first pointer, journal entry, outbox events and idempotency result
     * in one transaction (storage-design 9 a). An unknown outcome is resolved with
     * {@link CommitStore#findCommit} on the first commit's id and the object's main pointer.
     *
     * @return {@code Applied}, {@code NameTaken}, or {@code Missing(NAMESPACE)}
     * @throws CommitOutcomeUnknownException carrying the first commit's id
     */
    WriteResult createObject(ObjectCreation creation);

    /** The active object holding {@code normalizedName} in the namespace. */
    Optional<CatalogObject> findObject(UUID namespaceId, String normalizedName);

    /** The object in any state. */
    Optional<CatalogObject> readObject(UUID objectId);

    /** Objects of one kind and state in a namespace, by normalized name, then id. */
    Page<CatalogObject> listObjects(UUID namespaceId, CatalogObject.Kind kind, CatalogObject.State state,
                                    PageRequest page);

    /**
     * Moves an active object to {@code name} in {@code namespaceId}. A case-only rename keeps the
     * normalized name and changes the display name.
     *
     * @return {@code Applied}, {@code NameTaken}, {@code Missing(OBJECT)} or {@code Missing(NAMESPACE)}
     */
    WriteResult renameObject(UUID objectId, UUID namespaceId, Name name, Change change);

    /**
     * Soft-drops an active object at {@code change.at()}; {@code purgeAfter} is fixed now (D25).
     * The name is free at once; the id, pointers, journal and grants stay.
     *
     * @return {@code Applied}, {@code Missing(OBJECT)} (no active object), or {@code Refused(HELD)}
     */
    WriteResult dropObject(UUID objectId, Instant purgeAfter, Change change);

    /**
     * Makes a dropped object active again as {@code name} in {@code namespaceId}, keeping its id.
     *
     * @return {@code Applied}, {@code NameTaken}, {@code Missing(OBJECT)} (not dropped) or
     *         {@code Missing(NAMESPACE)}
     */
    WriteResult undropObject(UUID objectId, UUID namespaceId, Name name, Change change);

    /** Dropped objects whose purge-after time is at or before {@code now}, not held, by purge-after then id. */
    Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page);

    /**
     * Marks a dropped object purged, after its files are gone, and deletes the grants on it.
     * Judged at {@code change.at()}.
     *
     * @return {@code Applied}, {@code Missing(OBJECT)} (not dropped), {@code Refused(NOT_DUE)} or
     *         {@code Refused(HELD)}
     */
    WriteResult purgeObject(UUID objectId, Change change);
}
