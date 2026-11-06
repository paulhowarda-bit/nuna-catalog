package fidice.catalog.store;

import java.util.UUID;

/**
 * One catalog: a storage, KMS and legal-entity boundary (D28).
 *
 * @param id             stable identifier
 * @param normalizedName the name used for uniqueness and lookup
 * @param name           the name as given, for display
 */
public record CatalogRecord(UUID id, String normalizedName, String name) {
}
