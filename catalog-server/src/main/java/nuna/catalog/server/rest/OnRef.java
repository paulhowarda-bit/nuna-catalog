package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.Ref;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a route's prefix names (D33, item 26's choice 4): a catalog, and the branch or tag a request reads through, or
 * the catalog's main line. The prefix is {@code catalog@ref}; one with no {@code @} names main, as {@code catalog@main}
 * does. A configured catalog's name holds no {@code @} (the server refuses one at startup) and a ref's name none
 * either ({@link Ref#checkName}), so a prefix parses one way.
 *
 * <p>Reads on a ref resolve names as of its cut; creates, drops, renames, registrations and namespace changes happen
 * on main only and are refused on a ref; a tag takes no write at all (D33, as amended 2026-11-20; choice 5).
 */
public record OnRef(CatalogRecord catalog, Optional<Ref> ref) {

    /** What separates a catalog's name from a ref's in a prefix. */
    public static final char AT = '@';

    public OnRef {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(ref, "ref");
    }

    public static OnRef main(CatalogRecord catalog) {
        return new OnRef(catalog, Optional.empty());
    }

    /** The catalog's part of a prefix: all of it when it holds no {@code @}. */
    public static String catalogName(String prefix) {
        int at = prefix.indexOf(AT);
        return at < 0 ? prefix : prefix.substring(0, at);
    }

    /** The ref's part of a prefix, after its first {@code @}; empty when there is none, or it names main. */
    public static Optional<String> refName(String prefix) {
        int at = prefix.indexOf(AT);
        if (at < 0) {
            return Optional.empty();
        }
        String name = prefix.substring(at + 1);
        return Names.normalize(name).equals(Ref.MAIN) ? Optional.empty() : Optional.of(name);
    }

    public boolean onMain() {
        return ref.isEmpty();
    }

    public boolean tag() {
        return ref.map(r -> r.kind() == Ref.Kind.TAG).orElse(false);
    }

    /** The ref's id, which a read's ETag carries; empty on main. */
    public Optional<UUID> refId() {
        return ref.map(Ref::id);
    }

    /** What audit records name as the branch a request went through: main, or the branch's or tag's name. */
    public String branch() {
        return ref.map(r -> r.name().display()).orElse(Ref.MAIN);
    }

    /** The prefix {@code /v1/config} hands a client: the catalog's normalized name, then the ref's after an {@code @}. */
    public String prefix() {
        return catalog.normalizedName() + ref.map(r -> AT + r.name().normalized()).orElse("");
    }

    /**
     * The catalog, for a create, drop, rename, register, unregister or namespace change, which happens on main only.
     * On a ref it is refused with 400 naming the ref (D33, as amended 2026-11-20; choice 5).
     *
     * @param operation the refused operation, as the REST spec names it
     */
    CatalogRecord mainOnly(String operation) {
        if (ref.isPresent()) {
            throw refused(ref.get(), operation, "creates, drops, renames, registrations and namespace changes happen"
                    + " on main only");
        }
        return catalog;
    }

    /**
     * The catalog, for a commit to a table or a replace of a view. A tag refuses it with 400 naming the tag (choice 5);
     * a branch, until commits on a branch are served (item 105).
     *
     * @param operation the refused operation, as the REST spec names it
     */
    CatalogRecord forCommit(String operation) {
        if (ref.isPresent()) {
            throw refused(ref.get(), operation, "commits on a branch are not served yet");
        }
        return catalog;
    }

    /** 400 naming the ref: a tag for being read-only, a branch for {@code why}. */
    private CatalogError refused(Ref on, String operation, String why) {
        return on.kind() == Ref.Kind.TAG
                ? CatalogError.badRequest(operation + " is refused on tag " + on.name().display() + " of catalog "
                        + catalog.name() + ": a tag is read-only")
                : CatalogError.badRequest(operation + " is refused on branch " + on.name().display() + " of catalog "
                        + catalog.name() + ": " + why);
    }
}
