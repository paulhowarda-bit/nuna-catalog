package keel.catalog.objectstore;

/** A metadata file that is not in the store; the message is iceberg-core's wording for a missing file. */
public final class MetadataNotFoundException extends RuntimeException {

    private final String location;

    MetadataNotFoundException(String location, Throwable cause) {
        super("Failed to open input stream for file: " + location, cause);
        this.location = location;
    }

    public String location() {
        return location;
    }
}
