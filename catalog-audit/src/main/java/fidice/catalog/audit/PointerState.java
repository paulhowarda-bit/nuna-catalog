package fidice.catalog.audit;

import java.util.Objects;

/**
 * One version of a table or view pointer, as an audit record shows it (D5).
 *
 * @param metadataLocation where the metadata file is
 * @param version          the pointer version, from 1
 * @param sha256           the metadata file's SHA-256, hex
 */
public record PointerState(String metadataLocation, long version, String sha256) {

    public PointerState {
        Objects.requireNonNull(metadataLocation, "metadataLocation");
        Objects.requireNonNull(sha256, "sha256");
    }
}
