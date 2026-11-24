package nuna.catalog.store;

/**
 * The whole storage interface (build-plan 2.3, docs/storage-design.md section 9): the commit core
 * and every other operation group. A backend is supported when it implements this and passes
 * every suite of the shared test-jar (D8.9).
 */
public interface CatalogStore extends CommitStore, NamespaceStore, CatalogObjectStore, GrantStore,
        IdempotencyStore, OutboxStore, GovernanceStore, JournalStore, SchemaStore, MaintenanceStore, DrStore, RefStore,
        RowColumnRuleStore {
}
