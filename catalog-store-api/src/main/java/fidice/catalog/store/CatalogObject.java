package fidice.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A table or view. Its id is kept for life, through renames, drops and undrops (D25). A drop sets
 * {@code droppedAt} and fixes {@code purgeAfter}; {@code locationRoot} is the catalog-assigned
 * storage path (D28).
 */
public record CatalogObject(UUID catalogId, UUID id, UUID namespaceId, Name name, Kind kind, State state,
                            Optional<Instant> droppedAt, Optional<Instant> purgeAfter, String locationRoot) {

    public enum Kind { TABLE, VIEW }

    /** Active objects hold their name; dropped ones can be undropped; purged ones are history. */
    public enum State { ACTIVE, DROPPED, PURGED }

    public CatalogObject {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(namespaceId, "namespaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(locationRoot, "locationRoot");
        if (droppedAt.isPresent() != (state != State.ACTIVE) || purgeAfter.isPresent() != droppedAt.isPresent()) {
            throw new IllegalArgumentException("droppedAt and purgeAfter are set exactly when the object is not active");
        }
    }

    public static CatalogObject active(UUID catalogId, UUID id, UUID namespaceId, Name name, Kind kind,
                                       String locationRoot) {
        return new CatalogObject(catalogId, id, namespaceId, name, kind, State.ACTIVE, Optional.empty(),
                Optional.empty(), locationRoot);
    }

    /** The object's pointer on the main branch. */
    public PointerKey mainKey() {
        return new PointerKey(id, PointerKey.MAIN);
    }

    public Securable securable() {
        return new Securable(kind == Kind.TABLE ? Securable.Kind.TABLE : Securable.Kind.VIEW, id);
    }
}
