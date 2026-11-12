package keel.catalog.objectstore;

/** A storage location is malformed or lies outside the storage profile's allowed prefixes (D21). */
public final class LocationRejectedException extends RuntimeException {

    private final String location;

    LocationRejectedException(String location, String reason) {
        super("location \"" + location + "\" rejected: " + reason);
        this.location = location;
    }

    /** The location as it was presented. */
    public String location() {
        return location;
    }
}
