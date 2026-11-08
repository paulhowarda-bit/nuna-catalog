package fidice.catalog.core.commit;

import fidice.catalog.store.Name;
import java.util.Objects;
import java.util.UUID;

/** A table or view by name: the route resolves the catalog and namespace, the engine the object. */
public record ObjectName(UUID catalogId, UUID namespaceId, Name name) {

    public ObjectName {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(namespaceId, "namespaceId");
        Objects.requireNonNull(name, "name");
    }
}
