package fidice.catalog.store;

import java.util.Arrays;
import java.util.Objects;

/**
 * One version of a pointer: where the metadata file is, its SHA-256 (hex) and the compact
 * projection stored beside it (D5). Versions start at 1 and grow by 1 per commit.
 *
 * <p>The projection is copied in and out, so the record stays immutable, and compared by content.
 */
public record Pointer(String metadataLocation, long version, String sha256, byte[] projection) {

    public Pointer {
        Objects.requireNonNull(metadataLocation, "metadataLocation");
        Objects.requireNonNull(sha256, "sha256");
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1, got " + version);
        }
        projection = projection.clone();
    }

    @Override
    public byte[] projection() {
        return projection.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pointer that
                && version == that.version
                && metadataLocation.equals(that.metadataLocation)
                && sha256.equals(that.sha256)
                && Arrays.equals(projection, that.projection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadataLocation, version, sha256, Arrays.hashCode(projection));
    }

    @Override
    public String toString() {
        return "Pointer[metadataLocation=" + metadataLocation + ", version=" + version
                + ", sha256=" + sha256 + ", projection=" + projection.length + " bytes]";
    }
}
