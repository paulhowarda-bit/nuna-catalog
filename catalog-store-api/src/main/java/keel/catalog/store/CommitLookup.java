package keel.catalog.store;

/**
 * The definite answer of {@link CatalogStore#findCommit}. When the backend cannot decide,
 * findCommit throws {@link StoreException} instead, and the server answers CommitStateUnknown (D9).
 */
public enum CommitLookup {
    COMMITTED,
    NOT_COMMITTED
}
