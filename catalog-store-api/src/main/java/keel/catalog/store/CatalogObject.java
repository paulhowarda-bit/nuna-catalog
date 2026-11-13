package keel.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A table or view. Its id is kept for life, through renames, drops, undrops and unregistering (D25,
 * D13). A drop sets {@code droppedAt} and fixes {@code purgeAfter}; an unregister sets
 * {@code droppedAt} alone, to when it was unregistered, since an unregistered object is never purged.
 * {@code locationRoot} is the catalog-assigned storage path (D28).
 */
public record CatalogObject(UUID catalogId, UUID id, UUID namespaceId, Name name, Kind kind, State state,
                            Optional<Instant> droppedAt, Optional<Instant> purgeAfter, String locationRoot) {

    public enum Kind { TABLE, VIEW }

    /**
     * Active objects hold their name; dropped ones can be undropped; purged ones are history.
     * Unregistered ones are history too, but keep their files: the catalog stopped tracking them and
     * deletes nothing (D13). Registering a dropped or unregistered object's own metadata file brings
     * it back as active, with its id.
     */
    public enum State { ACTIVE, DROPPED, PURGED, UNREGISTERED }

    public CatalogObject {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(namespaceId, "namespaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(locationRoot, "locationRoot");
        if (droppedAt.isPresent() != (state != State.ACTIVE)
                || purgeAfter.isPresent() != (state == State.DROPPED || state == State.PURGED)) {
            throw new IllegalArgumentException("droppedAt is set exactly when the object is not active, and"
                    + " purgeAfter exactly when it was dropped");
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
