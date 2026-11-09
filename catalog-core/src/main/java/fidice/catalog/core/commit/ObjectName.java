package fidice.catalog.core.commit;

import fidice.catalog.store.Name;
import java.util.Objects;
import java.util.UUID;

/**
 * A table or view by name: the route resolves the catalog and namespace, the engine the object.
 * {@code qualified} is how messages name it, e.g. {@code ns.orders}, as iceberg-core prints an identifier.
 */
public record ObjectName(UUID catalogId, UUID namespaceId, Name name, String qualified) {

    public ObjectName {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(namespaceId, "namespaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualified, "qualified");
    }

    /** Named in messages by its own name only. */
    public ObjectName(UUID catalogId, UUID namespaceId, Name name) {
        this(catalogId, namespaceId, name, name.display());
    }
}
