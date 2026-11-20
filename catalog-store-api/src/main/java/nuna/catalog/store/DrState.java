package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;

/**
 * An active table or view as the DR verifier sees it (D37): its main pointer's version and metadata file, and its
 * DR watermark, if it has one.
 */
public record DrState(CatalogObject object, long version, String metadataLocation, Optional<DrWatermark> watermark) {

    public DrState {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(metadataLocation, "metadataLocation");
        Objects.requireNonNull(watermark, "watermark");
    }

    /** How many versions of the pointer are above the watermark: every version, when there is none. */
    public long behind() {
        return version - watermark.map(DrWatermark::version).orElse(0L);
    }
}
