package fidice.catalog.store;

/** The newest storage-format step applied to the backend (D8.7). */
public record SchemaVersion(int version, String description) {
}
