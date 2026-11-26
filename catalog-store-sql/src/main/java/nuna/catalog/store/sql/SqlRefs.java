package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.exists;
import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A catalog's branches and tags (D33): the ref record and its cut, {@code ref_entry}. A create locks the catalog's
 * record for update, the lock on the catalog's refs, so two creates take turns counting P11's limit; it share-locks the
 * ref it is made from, so a deletion of that ref waits. The cut is one INSERT ... SELECT: under READ COMMITTED a
 * statement reads one snapshot on PostgreSQL and CockroachDB alike, so the cut holds every multi-table transaction
 * whole, and every table with the namespace that held it. Renewal and deletion lock the ref for update; a deletion
 * then deletes the ref's own pointers, their pieces, its cut and the record. A commit on a branch locks the ref for
 * share, and a merge for update, before any pointer ({@link #lock}); a merge then ends the branch as a deletion does.
 */
final class SqlRefs {

    private static final String COLUMNS = "id, catalog_id, name_norm, name, kind, created_at, created_by, from_ref,"
            + " from_name, expires_at, description";
    private static final String ENTRY_COLUMNS = "ref_id, object_id, kind, parent_id, name_norm, name, version,"
            + " metadata_location, sha256";
    private static final WriteResult MISSING = new WriteResult.Missing(WriteResult.Subject.REF);
    /** Every namespace, and every active table and view with its main pointer, of one catalog. */
    private static final String CUT_OF_MAIN = "INSERT INTO ref_entry (" + ENTRY_COLUMNS + ")"
            + " SELECT CAST(? AS UUID), n.id, 'NAMESPACE', n.parent_id, n.name_norm, n.name, CAST(NULL AS BIGINT),"
            + " CAST(NULL AS TEXT), CAST(NULL AS TEXT) FROM namespace n WHERE n.catalog_id = ?"
            + " UNION ALL SELECT CAST(? AS UUID), o.id, o.kind, o.namespace_id, o.name_norm, o.name, p.version,"
            + " p.metadata_location, p.sha256 FROM catalog_object o JOIN pointer p ON p.object_id = o.id AND p.branch = ?"
            + " WHERE o.catalog_id = ? AND o.state = 'ACTIVE'";
    /** Another ref's cut, with that ref's own pointer where it has one. */
    private static final String CUT_OF_REF = "INSERT INTO ref_entry (" + ENTRY_COLUMNS + ")"
            + " SELECT CAST(? AS UUID), e.object_id, e.kind, e.parent_id, e.name_norm, e.name,"
            + " COALESCE(p.version, e.version), COALESCE(p.metadata_location, e.metadata_location),"
            + " COALESCE(p.sha256, e.sha256) FROM ref_entry e"
            + " LEFT JOIN pointer p ON p.object_id = e.object_id AND p.branch = ? WHERE e.ref_id = ?";
    /** The objects of a ref's cut, which are the only objects a ref's own pointers can name (D33, question 1). */
    private static final String CUT_OBJECTS = "SELECT object_id FROM ref_entry WHERE ref_id = ?";

    private final Transactions transactions;

    SqlRefs(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult create(Ref ref, RefLimits limits, Change change) {
        return transactions.change(change, "ref create", connection -> {
            if (!exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR UPDATE", ref.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.CATALOG);
            }
            long held = first(connection, "SELECT count(*) FROM ref WHERE catalog_id = ? AND kind = ?"
                    + " AND (expires_at IS NULL OR expires_at > ?)", row -> row.getLong(1), ref.catalogId(),
                    ref.kind().name(), change.at()).orElseThrow();
            int inserted = update(connection, "INSERT INTO ref (catalog_id, name_norm, name, kind, expires_at, id,"
                            + " created_at, created_by, from_ref, from_name, description)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (catalog_id, name_norm) DO NOTHING", ref.catalogId(), ref.name().normalized(),
                    ref.name().display(), ref.kind().name(), ref.expiresAt().orElse(null), ref.id(), ref.createdAt(),
                    ref.createdBy(), ref.fromRef().orElse(null), ref.fromName(), ref.description().orElse(null));
            if (inserted == 0) {
                return Guards.nameTaken(connection, "SELECT id FROM ref WHERE catalog_id = ? AND name_norm = ?",
                        ref.catalogId(), ref.name().normalized());
            }
            if (ref.fromRef().isPresent() && !open(connection, ref.fromRef().get(), ref.catalogId(), change.at())) {
                return MISSING;
            }
            if (!limits.admitsAnother(ref.kind(), held)) {
                return new WriteResult.Refused(WriteResult.Reason.REF_LIMIT);
            }
            if (ref.fromRef().isEmpty()) {
                update(connection, CUT_OF_MAIN, ref.id(), ref.catalogId(), ref.id(), PointerKey.MAIN, ref.catalogId());
            } else {
                update(connection, CUT_OF_REF, ref.id(), ref.fromRef().get().toString(), ref.fromRef().get());
            }
            return WriteResult.APPLIED;
        });
    }

    /** Share-locks the ref a new one is made from; whether it is a ref of the catalog not past its expiry. */
    private static boolean open(Connection connection, UUID refId, UUID catalogId, Instant now) throws SQLException {
        return exists(connection, "SELECT 1 FROM ref WHERE id = ? AND catalog_id = ?"
                + " AND (expires_at IS NULL OR expires_at > ?) FOR SHARE", refId, catalogId, now);
    }

    Optional<Ref> read(UUID refId) {
        return transactions.autocommit("ref read", connection -> first(connection,
                "SELECT " + COLUMNS + " FROM ref WHERE id = ?", SqlRefs::ref, refId));
    }

    Optional<Ref> find(UUID catalogId, String normalizedName) {
        return transactions.autocommit("ref lookup", connection -> first(connection,
                "SELECT " + COLUMNS + " FROM ref WHERE catalog_id = ? AND name_norm = ?", SqlRefs::ref, catalogId,
                normalizedName));
    }

    long countOwn(UUID refId) {
        return transactions.autocommit("ref own pointer count", connection -> first(connection,
                "SELECT count(*) AS own FROM pointer WHERE branch = ? AND object_id IN (" + CUT_OBJECTS + ")",
                row -> row.getLong("own"), refId.toString(), refId).orElseThrow());
    }

    List<RefPointers> listPointers(UUID objectId) {
        return transactions.autocommit("ref pointers of an object", connection -> query(connection,
                "SELECT r.id, r.catalog_id, r.name_norm, r.name, r.kind, r.created_at, r.created_by, r.from_ref,"
                        + " r.from_name, r.expires_at, r.description, e.version, e.metadata_location, e.sha256,"
                        + " p.version AS head_version, p.metadata_location AS head_location, p.sha256 AS head_sha256"
                        + " FROM ref_entry e JOIN ref r ON r.id = e.ref_id"
                        + " LEFT JOIN pointer p ON p.object_id = e.object_id AND p.branch = CAST(e.ref_id AS TEXT)"
                        + " WHERE e.object_id = ? ORDER BY r.name_norm",
                SqlRefs::pointers, objectId));
    }

    Page<Ref> list(UUID catalogId, PageRequest page) {
        Optional<String> after = Jdbc.after(page, 1, List::getFirst);
        return transactions.autocommit("ref listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(catalogId));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND name_norm > ?";
                parameters.add(after.get());
            }
            parameters.add(page.size() + 1);
            List<Ref> refs = query(connection, "SELECT " + COLUMNS + " FROM ref WHERE catalog_id = ?" + keyset
                    + " ORDER BY name_norm LIMIT ?", SqlRefs::ref, parameters.toArray());
            return Jdbc.page(refs, page, ref -> PageToken.encode(ref.name().normalized()));
        });
    }

    private record Locked(Ref.Kind kind, Instant expiresAt) {
    }

    WriteResult renew(UUID refId, Instant expiresAt, Change change) {
        return transactions.change(change, "ref renewal", connection -> {
            Optional<Locked> locked = first(connection, "SELECT kind, expires_at FROM ref WHERE id = ? FOR UPDATE",
                    row -> new Locked(Ref.Kind.valueOf(row.getString("kind")), instant(row, "expires_at")), refId);
            if (locked.isEmpty()) {
                return MISSING;
            }
            if (locked.get().kind() == Ref.Kind.TAG) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_A_BRANCH);
            }
            if (!locked.get().expiresAt().isAfter(change.at())) {
                return MISSING;
            }
            if (locked.get().expiresAt().equals(expiresAt)) {
                return WriteResult.UNCHANGED;
            }
            update(connection, "UPDATE ref SET expires_at = ? WHERE id = ?", expiresAt, refId);
            return WriteResult.APPLIED;
        });
    }

    WriteResult delete(UUID refId, Change change) {
        return transactions.change(change, "ref delete", connection -> {
            if (!exists(connection, "SELECT 1 FROM ref WHERE id = ? FOR UPDATE", refId)) {
                return MISSING;
            }
            end(connection, refId);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Deletes a ref locked for update: its own pointers and their pieces, its cut, the grants that name it (backlog
     * 113) and its record. Its journal rows stay. A deletion and a merge both end a ref this way.
     */
    static void end(Connection connection, UUID refId) throws SQLException {
        String branch = refId.toString();
        update(connection, "DELETE FROM pointer WHERE branch = ? AND object_id IN (" + CUT_OBJECTS + ")", branch, refId);
        update(connection, "DELETE FROM projection_piece WHERE branch = ? AND object_id IN (" + CUT_OBJECTS + ")",
                branch, refId);
        update(connection, "DELETE FROM ref_entry WHERE ref_id = ?", refId);
        update(connection, "DELETE FROM ref_grant WHERE ref_id = ?", refId);
        update(connection, "DELETE FROM ref WHERE id = ?", refId);
    }

    /**
     * Takes an object out of refs (backlog 156). Locks the catalog's record for share, which a create locks for
     * update, so no ref is made meanwhile; then each ref for update, in id order, as a deletion locks it, so a commit on
     * the branch in flight lands first and a later one finds the object outside the cut; then every pointer of the
     * object, by branch, as unregister and a commit lock them before the object (backlog 171's review: unregister locks
     * the pointers and then the object, so the other order deadlocked with it); then the object for update, as a purge
     * claim locks it; then the namespaces above it for share (backlog 171), before its holds and freezes are
     * read: a hold or freeze placed on the catalog or one of those namespaces locks it for update, so the two take
     * turns. Then deletes each branch's own pointer for the object with its pieces, and the object's entry in each
     * ref's cut.
     */
    WriteResult remove(UUID objectId, Set<UUID> refIds, boolean every, Change change) {
        return transactions.change(change, "ref removal", connection -> {
            Optional<UUID> catalog = first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ?"
                    + " AND state <> 'PURGED'", row -> Jdbc.uuid(row, "catalog_id"), objectId);
            if (catalog.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            // The lock on the catalog's refs: the catalog exists while an object of it does.
            exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR SHARE", catalog.get());
            UUID[] ids = refIds.stream().sorted().toArray(UUID[]::new);
            int locked = 0;
            for (UUID refId : ids) {
                if (exists(connection, "SELECT 1 FROM ref WHERE id = ? AND catalog_id = ? FOR UPDATE", refId,
                        catalog.get())) {
                    locked++;
                }
            }
            Pointers.lockObject(connection, objectId);
            Optional<List<UUID>> path = first(connection, "SELECT namespace_path FROM catalog_object WHERE id = ?"
                    + " AND state <> 'PURGED' FOR UPDATE", row -> Jdbc.uuids(row, "namespace_path"), objectId);
            if (path.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Guards.lockLevels(connection, path.get());
            if (Guards.held(connection, objectId, catalog.get(), path.get())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(connection, objectId, catalog.get(), path.get())) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            long named = first(connection, "SELECT count(*) AS named FROM ref_entry WHERE object_id = ?"
                    + " AND ref_id = ANY(?) AND kind <> 'NAMESPACE'", row -> row.getLong("named"), objectId, ids)
                    .orElseThrow();
            if (locked != ids.length || named != ids.length) {
                return MISSING;
            }
            if (every && exists(connection, "SELECT 1 FROM ref_entry WHERE object_id = ? AND NOT (ref_id = ANY(?))",
                    objectId, ids)) {
                return new WriteResult.Refused(WriteResult.Reason.REFERENCED);
            }
            String[] branches = Stream.of(ids).map(UUID::toString).toArray(String[]::new);
            update(connection, "DELETE FROM pointer WHERE object_id = ? AND branch = ANY(?)", objectId, branches);
            update(connection, "DELETE FROM projection_piece WHERE object_id = ? AND branch = ANY(?)", objectId,
                    branches);
            update(connection, "DELETE FROM ref_entry WHERE object_id = ? AND ref_id = ANY(?)", objectId, ids);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Locks the ref a commit lands on or ends, before any pointer (D33, backlog 103): for share for a commit on the
     * branch, so commits on one branch do not wait for each other while a merge or a deletion, which lock it for
     * update, waits for them; for update for a merge. Then checks it, as the lock returns it: a branch not past its
     * expiry at the batch's time. A commit on the branch swaps only objects of the branch's cut, which never changes
     * while the ref lives. A merge holds every own pointer of the branch: with the ref locked for update no commit on
     * the branch is in flight, so an own pointer the merge does not hold landed after the merge read the branch, and
     * ending the branch would lose it.
     *
     * @return empty when the commit may go on; else what it answers: {@code Inactive(REF)}, {@code Inactive(OBJECT)}
     *         naming a swap outside the cut, or {@code Conflict} naming an own pointer the merge does not hold
     */
    static Optional<CommitResult> lock(Connection connection, CommitBatch batch) throws SQLException {
        RefLock lock = batch.ref().orElseThrow();
        Optional<Locked> locked = first(connection, "SELECT kind, expires_at FROM ref WHERE id = ? FOR "
                        + (lock instanceof RefLock.Hold ? "SHARE" : "UPDATE"),
                row -> new Locked(Ref.Kind.valueOf(row.getString("kind")), instant(row, "expires_at")), lock.refId());
        if (locked.isEmpty() || locked.get().kind() != Ref.Kind.BRANCH
                || !locked.get().expiresAt().isAfter(batch.committedAt())) {
            return Optional.of(new CommitResult.Inactive(batch.refKey(), CommitResult.Subject.REF));
        }
        switch (lock) {
            case RefLock.Hold hold -> {
                for (PointerSwap swap : batch.swaps()) {
                    if (!exists(connection, "SELECT 1 FROM ref_entry WHERE ref_id = ? AND object_id = ?"
                            + " AND kind <> 'NAMESPACE'", hold.refId(), swap.key().objectId())) {
                        return Optional.of(new CommitResult.Inactive(swap.key()));
                    }
                }
            }
            case RefLock.End end -> {
                Set<UUID> held = batch.holds().stream().map(h -> h.key().objectId()).collect(Collectors.toSet());
                String branch = end.refId().toString();
                for (UUID objectId : query(connection, "SELECT object_id FROM pointer WHERE branch = ? AND object_id IN ("
                        + CUT_OBJECTS + ") ORDER BY object_id", row -> Jdbc.uuid(row, "object_id"), branch, end.refId())) {
                    if (!held.contains(objectId)) {
                        PointerKey key = new PointerKey(objectId, branch);
                        return Optional.of(new CommitResult.Conflict(key, Pointers.read(connection, key)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    Optional<RefEntry> findNamespace(UUID refId, List<String> normalizedPath) {
        if (normalizedPath.isEmpty()) {
            return Optional.empty();
        }
        return transactions.snapshot("ref namespace lookup", connection -> namespace(connection, refId, normalizedPath));
    }

    Optional<RefEntry> resolve(UUID refId, List<String> normalizedNamespacePath, String normalizedName) {
        if (normalizedNamespacePath.isEmpty()) {
            return Optional.empty();
        }
        return transactions.snapshot("ref name lookup", connection -> {
            Optional<RefEntry> namespace = namespace(connection, refId, normalizedNamespacePath);
            if (namespace.isEmpty()) {
                return Optional.empty();
            }
            return first(connection, "SELECT " + ENTRY_COLUMNS + " FROM ref_entry WHERE ref_id = ? AND parent_id = ?"
                    + " AND kind IN ('TABLE', 'VIEW') AND name_norm = ?", SqlRefs::entry, refId, namespace.get().id(),
                    normalizedName);
        });
    }

    /** The namespace at the end of {@code path} in the ref's cut, walked down from its catalog. */
    private static Optional<RefEntry> namespace(Connection connection, UUID refId, List<String> path)
            throws SQLException {
        Optional<UUID> catalog = first(connection, "SELECT catalog_id FROM ref WHERE id = ?",
                row -> Jdbc.uuid(row, "catalog_id"), refId);
        if (catalog.isEmpty()) {
            return Optional.empty();
        }
        UUID parent = catalog.get();
        Optional<RefEntry> found = Optional.empty();
        for (String name : path) {
            found = first(connection, "SELECT " + ENTRY_COLUMNS + " FROM ref_entry WHERE ref_id = ? AND parent_id = ?"
                    + " AND kind = 'NAMESPACE' AND name_norm = ?", SqlRefs::entry, refId, parent, name);
            if (found.isEmpty()) {
                return found;
            }
            parent = found.get().id();
        }
        return found;
    }

    Page<RefEntry> listNamespaces(UUID refId, UUID parentId, PageRequest page) {
        Optional<String> after = Jdbc.after(page, 1, List::getFirst);
        return transactions.autocommit("ref namespace listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(refId, parentId));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND name_norm > ?";
                parameters.add(after.get());
            }
            parameters.add(page.size() + 1);
            List<RefEntry> entries = query(connection, "SELECT " + ENTRY_COLUMNS + " FROM ref_entry WHERE ref_id = ?"
                    + " AND parent_id = ? AND kind = 'NAMESPACE'" + keyset + " ORDER BY name_norm LIMIT ?",
                    SqlRefs::entry, parameters.toArray());
            return Jdbc.page(entries, page, entry -> PageToken.encode(entry.name().normalized()));
        });
    }

    Page<RefEntry> listObjects(UUID refId, UUID namespaceId, CatalogObject.Kind kind, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2,
                parts -> List.of(parts.get(0), UUID.fromString(parts.get(1))));
        return transactions.autocommit("ref object listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(refId, namespaceId, RefEntry.of(kind).name()));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (name_norm, object_id) > (?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<RefEntry> entries = query(connection, "SELECT " + ENTRY_COLUMNS + " FROM ref_entry WHERE ref_id = ?"
                    + " AND parent_id = ? AND kind = ?" + keyset + " ORDER BY name_norm, object_id LIMIT ?",
                    SqlRefs::entry, parameters.toArray());
            return Jdbc.page(entries, page,
                    entry -> PageToken.encode(entry.name().normalized(), entry.id().toString()));
        });
    }

    private static Ref ref(ResultSet row) throws SQLException {
        return new Ref(Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "id"),
                new Name(row.getString("name_norm"), row.getString("name")), Ref.Kind.valueOf(row.getString("kind")),
                instant(row, "created_at"), row.getString("created_by"), Optional.ofNullable(Jdbc.uuid(row, "from_ref")),
                row.getString("from_name"), Optional.ofNullable(instant(row, "expires_at")),
                Optional.ofNullable(row.getString("description")));
    }

    /**
     * One row of {@link #listPointers}. {@code wasNull} answers for the last column read, so the head's version is
     * judged before the ref's columns are read: read after them, it would judge the ref's expiry instead, and a branch
     * with no pointer of its own would be read as having one.
     */
    static RefPointers pointers(ResultSet row) throws SQLException {
        RefEntry.At cut = new RefEntry.At(row.getLong("version"), row.getString("metadata_location"),
                row.getString("sha256"));
        long version = row.getLong("head_version");
        Optional<RefEntry.At> head = row.wasNull() ? Optional.empty()
                : Optional.of(new RefEntry.At(version, row.getString("head_location"), row.getString("head_sha256")));
        return new RefPointers(ref(row), cut, head);
    }

    private static RefEntry entry(ResultSet row) throws SQLException {
        long version = row.getLong("version");
        Optional<RefEntry.At> pointer = row.wasNull() ? Optional.empty()
                : Optional.of(new RefEntry.At(version, row.getString("metadata_location"), row.getString("sha256")));
        return new RefEntry(Jdbc.uuid(row, "ref_id"), Jdbc.uuid(row, "object_id"),
                RefEntry.Kind.valueOf(row.getString("kind")), Jdbc.uuid(row, "parent_id"),
                new Name(row.getString("name_norm"), row.getString("name")), pointer);
    }
}
