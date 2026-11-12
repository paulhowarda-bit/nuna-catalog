package keel.catalog.store;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A namespace, at any depth (D28). {@code parentId} is the parent namespace's id, or the catalog's
 * id for a top-level namespace. {@code version} starts at 1 and grows by 1 per properties update.
 */
public record NamespaceRecord(UUID catalogId, UUID id, UUID parentId, Name name,
                              Map<String, String> properties, long version) {

    public NamespaceRecord {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(name, "name");
        properties = Map.copyOf(properties);
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1, got " + version);
        }
    }
}
