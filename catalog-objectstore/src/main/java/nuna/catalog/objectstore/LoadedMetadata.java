package nuna.catalog.objectstore;

/**
 * A metadata file as read from the store.
 *
 * @param location the file's {@code s3://} location
 * @param json     the file's content, decoded as UTF-8
 * @param sha256   SHA-256 of the bytes read, lower-case hex
 * @param intact   whether {@code sha256} equals the hash the pointer recorded; false only under
 *                 {@link OnMismatch#FLAG}, and the caller then raises the alert
 */
public record LoadedMetadata(String location, String json, String sha256, boolean intact) {
}
