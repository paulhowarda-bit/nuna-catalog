package nuna.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * Takes a table or view out of refs (D33, amended 2026-11-22; backlog 156), so a dropped one's files can be purged
     * once no ref names it. In one transaction it deletes the object's entry in the cut of each ref of {@code refIds},
     * and each branch's own pointer for it with that pointer's projection pieces; a branch's journal rows stay, keyed by
     * its id, as a deletion leaves them. Reads of the object through those refs then find nothing, as for an object
     * their cut never held, and a commit on such a branch that swaps it is refused ({@link RefLock.Hold}).
     *
     * <p>The catalog's refs are locked as a create locks them, so no ref is made meanwhile; each ref of {@code refIds}
     * is locked as a deletion locks it, so a commit on a branch in flight lands first; and the object is locked as a
     * purge claim locks it, its holds and freezes read as a claim reads them. The outcomes below are checked in the
     * order given, once every lock is held.
     *
     * @param refIds the refs to take it out of, at least one
     * @param every  whether {@code refIds} must be every ref whose cut names the object, a branch past its expiry
     *               included
     * @return {@code Applied}; {@code Missing(OBJECT)} (no such table or view, or a purged one); {@code Refused(HELD)}
     *         or {@code Refused(FROZEN)} (a legal hold or a DR freeze on the object, a namespace above it or its
     *         catalog); {@code Missing(REF)} (a ref of {@code refIds} is not a ref of the object's catalog, or its cut
     *         does not name the object); or {@code Refused(REFERENCED)} ({@code every}, and a ref outside
     *         {@code refIds} names it)
     */
    WriteResult removeFromRefs(UUID objectId, Set<UUID> refIds, boolean every, Change change);

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
