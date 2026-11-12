package keel.catalog.objectstore;

/** A metadata file's bytes no longer hash to the SHA-256 the pointer recorded (D5, D26). */
public final class MetadataIntegrityException extends RuntimeException {

    private final String location;
    private final String expectedSha256;
    private final String actualSha256;

    MetadataIntegrityException(String location, String expectedSha256, String actualSha256) {
        super("metadata file " + location + " has SHA-256 " + actualSha256 + ", the pointer recorded "
                + expectedSha256);
        this.location = location;
        this.expectedSha256 = expectedSha256;
        this.actualSha256 = actualSha256;
    }

    public String location() {
        return location;
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    public String actualSha256() {
        return actualSha256;
    }
}
