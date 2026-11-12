package keel.catalog.store;

/**
 * The versioned storage format (D8.7). {@link CommitStore#migrate} applies the steps; every step
 * keeps the previous release's statements working, so nodes on version N serve while N+1 is
 * applied.
 */
public interface SchemaStore {

    SchemaVersion schemaVersion();
}
