package fidice.catalog.objectstore;

/**
 * A metadata file the catalog has written: the two values the pointer records (D5).
 *
 * @param location the file's {@code s3://} location
 * @param sha256   SHA-256 of the file's bytes, lower-case hex
 */
public record StoredMetadata(String location, String sha256) {
}
