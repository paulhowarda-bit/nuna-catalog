package nuna.catalog.store;

import java.util.Objects;
import java.util.UUID;

/** A catalog's boundary (D28): one storage profile, one KMS key set, one legal entity. */
public record CatalogSettings(UUID storageProfileId, String kmsKeySet, String legalEntity) {

    public CatalogSettings {
        Objects.requireNonNull(storageProfileId, "storageProfileId");
        Objects.requireNonNull(kmsKeySet, "kmsKeySet");
        Objects.requireNonNull(legalEntity, "legalEntity");
    }
}
