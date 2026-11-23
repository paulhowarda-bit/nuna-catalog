package nuna.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A catalog's branches and tags (D33, item 26's design): the ref record and its cut. A ref is made from now, from
 * {@code main} or from another ref's current head, never at a past time (choice 2). Its cut is read in one consistent
 * read, so it never holds part of a multi-table transaction, and never a table without the namespace that held it.
 * Names in a cut compare as every name does (D8.6). Writes behave as in {@link NamespaceStore}: each is one atomic
 * unit with its {@link Change}.
 *
 * <p>The store keeps a branch past its expiry until it is deleted, and answers for it like any other ref: its readers
 * check {@link Ref#expiredAt}. Only the writes below that name a branch's expiry refuse one past it.
 */
public interface RefStore {

    /**
     * Makes {@code ref} and its cut in one transaction. From {@code main} the cut is every namespace of the catalog and
     * every active table and view with its main pointer. From another ref it is that ref's cut, with that ref's own
     * pointer for a table or view where it has one. P11's limit on refs of {@code ref}'s kind is checked under a lock
     * on the catalog's refs, so of two creates at once only one can take the last place; an open branch is one not
     * past its expiry at {@code change.at()}. A name held by any ref of the catalog, a branch past its expiry
     * included, is taken: deleting it frees it. The outcomes below are checked in the order given, so a retried create
     * names its own id whatever has happened since.
     *
     * @return {@code Applied}; {@code Missing(CATALOG)}; {@code NameTaken} (a ref of the catalog holds the normalized
     *         name); {@code Missing(REF)} (the ref it is made from is not a ref of the catalog, or is a branch past its
     *         expiry at {@code change.at()}); or {@code Refused(REF_LIMIT)} (the catalog holds as many open refs of
     *         the kind as {@code limits} allows)
     */
    WriteResult createRef(Ref ref, RefLimits limits, Change change);

    /** The ref with this id. */
    Optional<Ref> readRef(UUID refId);

    /** The catalog's ref holding {@code normalizedName}. */
    Optional<Ref> findRef(UUID catalogId, String normalizedName);

    /** The catalog's refs, by normalized name. */
    Page<Ref> listRefs(UUID catalogId, PageRequest page);

    /**
     * Sets a branch's expiry to {@code expiresAt} (P11; choice 11 caps it, which the caller applies).
     *
     * @return {@code Applied}; {@code Unchanged} (the branch already expires then); {@code Missing(REF)} (no such
     *         ref, or a branch past its expiry at {@code change.at()}); or {@code Refused(NOT_A_BRANCH)} (a tag, which
     *         never expires)
     */
    WriteResult renewRef(UUID refId, Instant expiresAt, Change change);

    /**
     * Deletes a ref with its cut and every pointer keyed by its id, their projection pieces included, in one
     * transaction; the name is free at once. A branch's journal rows stay, keyed by its id. The ref is locked before
     * any pointer, for update, so a deletion waits for a commit on the branch in flight ({@link RefLock.Hold}) and
     * deletes what it wrote, or the commit waits and is refused. A merge ends a branch the same way
     * ({@link RefLock.End}).
     *
     * @return {@code Applied} or {@code Missing(REF)}
     */
    WriteResult deleteRef(UUID refId, Change change);

    /**
     * How many tables and views of its cut the ref has its own pointer for: those a branch's commits have changed. A
     * commit that would give a branch more than {@code nuna.refs.max-merge-tables} is refused (item 26's choice 9), so
     * a merge always fits in one transaction. 0 for a tag, and for a ref the store does not have.
     */
    long countOwnPointers(UUID refId);

    /**
     * Every ref whose cut names the table or view, by the ref's normalized name: the ref, the pointer its cut took for
     * it, and for a branch its own pointer for it when it has one. A branch past its expiry counts until it is deleted;
     * on MongoDB an entry counts only while its ref exists.
     */
    List<RefPointers> listRefPointers(UUID objectId);

    /** The namespace at {@code normalizedPath} in the ref's cut, from the catalog down. */
    Optional<RefEntry> findRefNamespace(UUID refId, List<String> normalizedPath);

    /**
     * The table or view named {@code normalizedName} in the namespace at {@code normalizedNamespacePath} in the ref's
     * cut, as {@link CatalogObjectStore#findObject} finds one on main.
     */
    Optional<RefEntry> resolveOnRef(UUID refId, List<String> normalizedNamespacePath, String normalizedName);

    /** The namespaces directly under {@code parentId} (a namespace or the catalog) in the ref's cut, by normalized name. */
    Page<RefEntry> listRefNamespaces(UUID refId, UUID parentId, PageRequest page);

    /** The tables or views of one kind in a namespace in the ref's cut, by normalized name, then id. */
    Page<RefEntry> listRefObjects(UUID refId, UUID namespaceId, CatalogObject.Kind kind, PageRequest page);
}
