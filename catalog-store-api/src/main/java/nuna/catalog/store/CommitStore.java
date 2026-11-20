package nuna.catalog.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The commit core of the storage interface (docs/storage-design.md sections 3 and 9). Every
 * method is one atomic unit, and pointer reads go to the primary/leader only (D8.3).
 * {@link CatalogStore} adds the other operation groups.
 */
public interface CommitStore {

    /** Finds a catalog by its normalized name, reading from the primary. */
    Optional<CatalogRecord> findCatalog(String normalizedName);

    /** Reads the current pointer from the primary. */
    Optional<Pointer> readPointer(PointerKey key);

    /**
     * Swaps every pointer in the batch and writes its journal, outbox and idempotency records,
     * and the DR watermark of each swap that records one ({@link PointerSwap#drWatermark}), all or
     * nothing. A batch with a ref ({@link CommitBatch#ref}) locks it before any pointer and checks it
     * first; a merge ({@link RefLock.End}) also deletes the branch, its cut and its own pointers.
     *
     * @return {@code Committed}; {@code Inactive(REF)} when the batch's ref is gone, a tag, or a branch
     * past its expiry at {@code committedAt}; {@code Inactive(OBJECT)} when a commit on a branch swaps
     * an object outside the branch's cut; {@code Conflict} when a merge does not hold an own pointer of
     * the branch (these three are checked first, in that order); {@code Conflict} when an expected
     * pointer did not match; or {@code Inactive(OBJECT)} when a swapped pointer's object exists and is
     * not active (an object the store has no record of, as in the suite's bare pointers, is not checked)
     * @throws CommitOutcomeUnknownException when the transaction may or may not have committed
     * @throws StoreException                when it failed cleanly before COMMIT was sent
     */
    CommitResult commit(CommitBatch batch);

    /**
     * Decides whether a commit whose outcome was unknown committed durably. Waits until no
     * transaction that could still commit it is in flight.
     *
     * @param anyKeyInBatch the key of any swap in that commit's batch
     * @throws StoreException when the backend cannot decide
     */
    CommitLookup findCommit(UUID commitId, PointerKey anyKeyInBatch);

    /** Durability settings that make the catalog unsafe; empty when safe (D8.2). */
    List<SafetyFinding> selfCheck();

    /** Brings the backend's schema to the current version. */
    void migrate();
}
