package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry of a ref's cut (D33, item 26's choice 1 and question 1 (b)): a namespace, table or view as it was when the
 * ref was made. {@code id} is the namespace's or object's id; {@code parentId} is the namespace that held it, or the
 * catalog's id for a top-level namespace, as {@link NamespaceRecord#parentId()} is. A table or view also keeps the
 * pointer the ref took for it ({@code pointer}): its main pointer, or the source ref's own pointer where a ref is made
 * from a branch that has one. A namespace keeps none. The cut never changes once made.
 */
public record RefEntry(UUID refId, UUID id, Kind kind, UUID parentId, Name name, Optional<At> pointer) {

    public enum Kind { NAMESPACE, TABLE, VIEW }

    /** The version, metadata file and its SHA-256 of the pointer a ref took for a table or view. */
    public record At(long version, String metadataLocation, String sha256) {

        public At {
            Objects.requireNonNull(metadataLocation, "metadataLocation");
            Objects.requireNonNull(sha256, "sha256");
            if (version < 1) {
                throw new IllegalArgumentException("version starts at 1, got " + version);
            }
        }
    }

    public RefEntry {
        Objects.requireNonNull(refId, "refId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pointer, "pointer");
        if (pointer.isPresent() == (kind == Kind.NAMESPACE)) {
            throw new IllegalArgumentException("a table or view keeps a pointer and a namespace none");
        }
    }

    /** The kind of entry an object of {@code kind} has. */
    public static Kind of(CatalogObject.Kind kind) {
        return kind == CatalogObject.Kind.TABLE ? Kind.TABLE : Kind.VIEW;
    }
}
