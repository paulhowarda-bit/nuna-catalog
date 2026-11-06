package fidice.catalog.store;

import java.util.Optional;

/**
 * The catalog's storage interface. Every backend implements it, and every operation is one
 * atomic unit (build-plan.md 2.3). M0 holds catalog lookup only; M1 designs the rest against
 * all three backend families at once.
 */
public interface CatalogStore {

    /** Finds a catalog by its normalized name, reading from the primary. */
    Optional<CatalogRecord> findCatalog(String normalizedName);
}
