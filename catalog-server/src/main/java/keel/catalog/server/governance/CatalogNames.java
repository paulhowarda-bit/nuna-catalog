package keel.catalog.server.governance;

import keel.catalog.core.Names;
import keel.catalog.server.storage.StorageSettings;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The names of the catalogs this deployment declares ({@code keel.storage.catalogs}), by catalog id: the
 * maintenance worker's lists span every catalog, and it calls each object's routes under its catalog's name.
 */
@Component
class CatalogNames {

    private final CatalogStore store;
    private final StorageSettings storage;
    private volatile Map<UUID, String> names = Map.of();

    CatalogNames(CatalogStore store, StorageSettings storage) {
        this.store = store;
        this.storage = storage;
    }

    /** The declared name of the catalog, or null when no declared catalog has that id. */
    String name(UUID catalogId) {
        String name = names.get(catalogId);
        if (name == null) {
            Map<UUID, String> found = new HashMap<>();
            for (String declared : storage.catalogs().keySet()) {
                store.findCatalog(Names.normalize(declared)).map(CatalogRecord::id)
                        .ifPresent(id -> found.put(id, declared));
            }
            names = Map.copyOf(found);
            name = found.get(catalogId);
        }
        return name;
    }
}
