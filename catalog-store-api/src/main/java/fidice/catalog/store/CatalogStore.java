package fidice.catalog.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalog's storage interface (docs/storage-design.md section 3). Every backend implements
 * it, every method is one atomic unit, and pointer reads go to the primary/leader only (D8.3).
 * M1 holds the commit core; M2 adds the other operation groups.
 */
public interface CatalogStore {

    /** Finds a catalog by its normalized name, reading from the primary. */
    Optional<CatalogRecord> findCatalog(String normalizedName);

    /** Reads the current pointer from the primary. */
    Optional<Pointer> readPointer(PointerKey key);

    /**
     * Swaps every pointer in the batch and writes its journal, outbox and idempotency records,
     * all or nothing.
     *
     * @return {@code Committed}, or {@code Conflict} when an expected pointer did not match
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
