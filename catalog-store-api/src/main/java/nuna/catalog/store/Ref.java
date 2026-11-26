package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A catalog's branch or tag (D33). A ref keeps its point as a cut: each namespace, table and view of the catalog, with
 * each table's and view's main pointer and name, as of the moment it was made ({@link RefEntry}, item 26's choice 1
 * and question 1 (b)). It is made from {@code main} or from another ref of the catalog, whose id and name at the time
 * it keeps ({@code fromRef}, {@code fromName}). A branch's own pointers and journal rows are keyed by the ref's id,
 * never its name ({@link #key}), so a name used again never finds an old branch's rows (choice 3).
 *
 * <p>Names compare as every name does (D8.6): normalized for uniqueness and order, display as given. They hold no
 * {@code @} and no {@code /}, so the prefix {@code catalog@ref} parses one way, and {@code main} is reserved: it is
 * always there and is not a ref record (choice 4). A branch expires at {@code expiresAt} and is gone from then on;
 * a tag never expires (P11).
 *
 * <p>{@code description} is the purpose its maker gave it, if any (backlog 167): one line of plain text, kept as given
 * and never changed. The route bounds it ({@code RefRoutes}); the record checks only that it is not blank.
 */
public record Ref(UUID catalogId, UUID id, Name name, Kind kind, Instant createdAt, String createdBy,
                  Optional<UUID> fromRef, String fromName, Optional<Instant> expiresAt,
                  Optional<String> description) {

    public enum Kind { BRANCH, TAG }

    /** The name of the catalog's main line, which every ref is made from or descends from. */
    public static final String MAIN = PointerKey.MAIN;

    public Ref {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(fromRef, "fromRef");
        Objects.requireNonNull(fromName, "fromName");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(description, "description");
        checkName(name);
        if (description.isPresent() && description.get().isBlank()) {
            throw new IllegalArgumentException("a ref's description, when it has one, is not blank");
        }
        if (expiresAt.isPresent() != (kind == Kind.BRANCH)) {
            throw new IllegalArgumentException("a branch expires and a tag never does");
        }
        if (fromRef.isEmpty() != fromName.equals(MAIN)) {
            throw new IllegalArgumentException("a ref is made from main, named " + MAIN + ", or from another ref,"
                    + " named by its own name");
        }
    }

    /**
     * Refuses a name a ref cannot take: one holding {@code @} or {@code /}, or {@code main} in any case.
     *
     * @throws IllegalArgumentException naming the rule
     */
    public static void checkName(Name name) {
        for (String form : new String[] {name.normalized(), name.display()}) {
            if (form.indexOf('@') >= 0 || form.indexOf('/') >= 0) {
                throw new IllegalArgumentException("a ref's name holds no @ and no /: " + name.display());
            }
        }
        if (name.normalized().equals(MAIN)) {
            throw new IllegalArgumentException(MAIN + " is the catalog's main line, not a ref's name");
        }
    }

    /** The key of this ref's own pointer for an object: the object's id and the ref's id, never its name. */
    public PointerKey key(UUID objectId) {
        return new PointerKey(objectId, id.toString());
    }

    /** Whether this is a branch at or past its expiry at {@code now}: such a branch is gone. */
    public boolean expiredAt(Instant now) {
        return expiresAt.map(expiry -> !expiry.isAfter(now)).orElse(false);
    }
}
