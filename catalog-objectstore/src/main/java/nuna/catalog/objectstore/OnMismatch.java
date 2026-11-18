package nuna.catalog.objectstore;

/** What a read does when the file's SHA-256 differs from the one the pointer recorded (D5, D26). */
public enum OnMismatch {
    /** Regulated tables: refuse the file with {@link MetadataIntegrityException}. */
    FAIL,
    /** Other tables: return the file marked not intact. */
    FLAG
}
