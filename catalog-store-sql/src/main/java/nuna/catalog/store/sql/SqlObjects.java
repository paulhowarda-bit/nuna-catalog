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
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tables and views (D25). Names are unique among active objects through a partial unique index; a
 * write that takes a name waits for an open writer of it. Writes lock the object for update and
 * share-lock the namespace they put it in.
 */
final class SqlObjects {

    static final String COLUMNS = "id, catalog_id, namespace_id, name_norm, name, kind, state, dropped_at,"
            + " purge_after, location_root, purge_claimant";
    /**
     * No legal hold and no DR freeze on object {@code o}, a namespace above it or its catalog: what the
     * purge queue and the due pending deletions ask of an object (D24, D30, D37).
     */
    static final String UNCOVERED = "NOT EXISTS (SELECT 1 FROM legal_hold h WHERE h.target_id = o.id"
            + " OR h.target_id = o.catalog_id OR h.target_id = ANY(o.namespace_path))"
            + " AND NOT EXISTS (SELECT 1 FROM governance_attribute a WHERE a.kind = 'DR_FREEZE'"
            + " AND (a.target_id = o.id OR a.target_id = o.catalog_id OR a.target_id = ANY(o.namespace_path)))";
    private static final String ACTIVE_HOLDER = "SELECT id FROM catalog_object WHERE namespace_id = ? AND name_norm = ?"
            + " AND state = 'ACTIVE'";
    private static final String UNIQUE_VIOLATION = "23505";
    /** No ref's cut names the object (D33): a ref keeps a dropped table's files (P11). */
    static final String UNREFERENCED = "NOT EXISTS (SELECT 1 FROM ref_entry e WHERE e.object_id = o.id)";

    private final Transactions transactions;

    SqlObjects(Transactions transactions) {
        this.transactions = transactions;
    }

    /** The object, its first pointer and that commit's records, in one transaction (section 9 a). */
    WriteResult create(ObjectCreation creation) {
        CatalogObject object = creation.object();
        return transactions.write(creation.firstCommit().commitId(), "object create", connection -> {
            Optional<List<UUID>> path = namespacePath(connection, object.namespaceId(), object.catalogId());
            if (path.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            int inserted = update(connection, "INSERT INTO catalog_object (id, catalog_id, namespace_id, namespace_path,"
                            + " name_norm, name, kind, state, location_root) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)"
                            + " ON CONFLICT (namespace_id, name_norm) WHERE state = 'ACTIVE' DO NOTHING",
                    object.id(), object.catalogId(), object.namespaceId(), path.get().toArray(UUID[]::new),
                    object.name().normalized(), object.name().display(), object.kind().name(), object.locationRoot());
            if (inserted == 0) {
                return Guards.nameTaken(connection, ACTIVE_HOLDER, object.namespaceId(), object.name().normalized());
            }
            if (!(Pointers.apply(connection, creation.firstCommit()) instanceof CommitResult.Committed)) {
                throw new StoreException("object " + object.id() + " already has a pointer", null);
            }
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Optional<CatalogObject> find(UUID namespaceId, String normalizedName) {
        return transactions.autocommit("object lookup", connection -> first(connection, "SELECT " + COLUMNS
                + " FROM catalog_object WHERE namespace_id = ? AND name_norm = ? AND state = 'ACTIVE'",
                SqlObjects::object, namespaceId, normalizedName));
    }

    Optional<CatalogObject> read(UUID objectId) {
        return transactions.autocommit("object read", connection -> first(connection,
                "SELECT " + COLUMNS + " FROM catalog_object WHERE id = ?", SqlObjects::object, objectId));
    }

    Page<CatalogObject> list(UUID namespaceId, CatalogObject.Kind kind, CatalogObject.State state, PageRequest page) {
        Optional<List<String>> after = Jdbc.after(page, 2, parts -> List.of(parts.get(0), UUID.fromString(parts.get(1)).toString()));
        return transactions.autocommit("object listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(namespaceId, kind.name(), state.name()));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (name_norm, id) > (?, CAST(? AS UUID))";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<CatalogObject> objects = query(connection, "SELECT " + COLUMNS + " FROM catalog_object"
                    + " WHERE namespace_id = ? AND kind = ? AND state = ?" + keyset + " ORDER BY name_norm, id LIMIT ?",
                    SqlObjects::object, parameters.toArray());
            return Jdbc.page(objects, page, object -> PageToken.encode(object.name().normalized(), object.id().toString()));
        });
    }

    WriteResult rename(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object rename", connection -> {
            Optional<Placed> locked = lock(connection, objectId, CatalogObject.State.ACTIVE);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(connection, objectId, locked.get().catalogId(), namespaceId, name, "");
        });
    }

    WriteResult drop(UUID objectId, Instant purgeAfter, Change change) {
        return transactions.change(change, "object drop", connection -> {
            Optional<Held> locked = lockForRetention(connection, objectId, CatalogObject.State.ACTIVE);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (Guards.held(connection, objectId, locked.get().catalogId(), locked.get().path())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            update(connection, "UPDATE catalog_object SET state = 'DROPPED', dropped_at = ?, purge_after = ? WHERE id = ?",
                    change.at(), purgeAfter, objectId);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Locks the object's pointers, as a commit locks them, then the object: a commit either landed
     * before this, and the main pointer is not {@code expected}, or waits and then finds the object
     * unregistered. The holds, DR freezes and regulated or row/column-policy flags are checked under the
     * object's lock, as a drop checks holds.
     */
    WriteResult unregister(UUID objectId, Pointer expected, Change change) {
        return transactions.change(change, "object unregister", connection -> {
            Optional<Pointers.Locked> main = Pointers.lockObject(connection, objectId);
            Optional<Held> locked = lockForRetention(connection, objectId, CatalogObject.State.ACTIVE);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (main.isEmpty() || !main.get().is(expected)) {
                return new WriteResult.VersionMismatch(main.map(Pointers.Locked::version).orElse(0L));
            }
            if (Guards.held(connection, objectId, locked.get().catalogId(), locked.get().path())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(connection, objectId, locked.get().catalogId(), locked.get().path())) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            if (Guards.flagged(connection, objectId, locked.get().catalogId(), locked.get().path())) {
                return new WriteResult.Refused(WriteResult.Reason.FLAGGED);
            }
            update(connection, "UPDATE catalog_object SET state = 'UNREGISTERED', dropped_at = ? WHERE id = ?",
                    change.at(), objectId);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Locks the batch's pointers, then the object, as unregister does, and compares its namespace under
     * that lock; puts the object at the name as undrop does, and writes the batch, all in the batch's
     * transaction.
     */
    WriteResult revive(UUID objectId, CatalogObject.State from, UUID namespaceId, Name name, CommitBatch batch) {
        return transactions.write(batch.commitId(), "object revive", connection -> {
            Optional<CommitResult.Conflict> conflict = Pointers.lock(connection, batch);
            if (conflict.isPresent()) {
                return new WriteResult.VersionMismatch(conflict.get().actual().map(Pointer::version).orElse(0L));
            }
            Optional<Placed> locked = lock(connection, objectId, from);
            if (locked.isEmpty() || !locked.get().namespaceId().equals(namespaceId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            WriteResult placed = place(connection, objectId, locked.get().catalogId(), namespaceId, name,
                    ", state = 'ACTIVE', dropped_at = NULL, purge_after = NULL");
            if (placed.equals(WriteResult.APPLIED)) {
                Pointers.record(connection, batch);
            }
            return placed;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult undrop(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object undrop", connection -> {
            Optional<Placed> locked = lock(connection, objectId, CatalogObject.State.DROPPED);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(connection, objectId, locked.get().catalogId(), namespaceId, name,
                    ", state = 'ACTIVE', dropped_at = NULL, purge_after = NULL");
        });
    }

    Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2,
                parts -> List.of(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1))));
        return transactions.autocommit("purge scan", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(now));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (purge_after, id) > (?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<CatalogObject> objects = query(connection, "SELECT " + COLUMNS + " FROM catalog_object o"
                    + " WHERE state IN ('DROPPED', 'PURGING') AND purge_after <= ?" + keyset
                    + " AND (state = 'PURGING' OR (" + UNCOVERED + " AND " + UNREFERENCED + "))"
                    + " ORDER BY purge_after, id LIMIT ?",
                    SqlObjects::object, parameters.toArray());
            return Jdbc.page(objects, page,
                    object -> PageToken.encode(object.purgeAfter().orElseThrow().toString(), object.id().toString()));
        });
    }

    /**
     * Locks the dropped or claimed object, then judges the claim under the lock: the holds and DR freezes
     * are read as a drop reads holds, so one placed before the claim refuses it. So are the refs whose cut
     * names it: a ref made from another copies its source's cut under a share lock on the source, so a
     * ref that comes to name the object committed before its source's deletion, and this read sees it.
     * The held levels are locked too, for share, before holds are read (backlog 171): the catalog's row
     * first, as every write that locks it does, then the object, then the namespaces above it. A hold
     * or freeze placed on any of them locks it for update, so it commits first and refuses the claim,
     * or waits for the claim.
     */
    WriteResult claimPurge(UUID objectId, String claimant, Change change) {
        return transactions.change(change, "purge claim", connection -> {
            Optional<UUID> catalog = first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ?",
                    row -> Jdbc.uuid(row, "catalog_id"), objectId);
            if (catalog.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR SHARE", catalog.get());
            Optional<Claim> locked = lockForPurge(connection, objectId);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Claim claim = locked.get();
            if (claim.claimant() != null) {
                return claim.claimant().equals(claimant) ? WriteResult.UNCHANGED
                        : new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            if (claim.purgeAfter().isAfter(change.at())) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_DUE);
            }
            Guards.lockLevels(connection, claim.path());
            if (Guards.held(connection, objectId, claim.catalogId(), claim.path())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(connection, objectId, claim.catalogId(), claim.path())) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            if (exists(connection, "SELECT 1 FROM ref_entry WHERE object_id = ?", objectId)) {
                return new WriteResult.Refused(WriteResult.Reason.REFERENCED);
            }
            update(connection, "UPDATE catalog_object SET state = 'PURGING', purge_claimant = ? WHERE id = ?",
                    claimant, objectId);
            return WriteResult.APPLIED;
        });
    }

    WriteResult completePurge(UUID objectId, String claimant, Change change) {
        return transactions.change(change, "purge complete", connection -> {
            Optional<Claim> locked = lockForPurge(connection, objectId);
            if (locked.isEmpty() || locked.get().claimant() == null) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (!locked.get().claimant().equals(claimant)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            update(connection, "UPDATE catalog_object SET state = 'PURGED', purge_claimant = NULL WHERE id = ?", objectId);
            update(connection, "DELETE FROM grant_record WHERE securable_id = ?", objectId);
            update(connection, "DELETE FROM row_column_rule WHERE table_id = ?", objectId);
            update(connection, "DELETE FROM pending_deletion WHERE object_id = ?", objectId);
            update(connection, "DELETE FROM table_health WHERE object_id = ?", objectId);
            update(connection, "DELETE FROM maintenance_request WHERE object_id = ?", objectId);
            update(connection, "DELETE FROM dr_watermark WHERE object_id = ?", objectId);
            return WriteResult.APPLIED;
        });
    }

    Page<CatalogObject> listActive(CatalogObject.Kind kind, PageRequest page) {
        Optional<UUID> after = Jdbc.after(page, 1, parts -> UUID.fromString(parts.get(0)));
        return transactions.autocommit("active object scan", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(kind.name()));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND id > ?";
                parameters.add(after.get());
            }
            parameters.add(page.size() + 1);
            List<CatalogObject> objects = query(connection, "SELECT " + COLUMNS + " FROM catalog_object"
                    + " WHERE state = 'ACTIVE' AND kind = ?" + keyset + " ORDER BY id LIMIT ?",
                    SqlObjects::object, parameters.toArray());
            return Jdbc.page(objects, page, object -> PageToken.encode(object.id().toString()));
        });
    }

    /**
     * Puts a locked object at {@code name} in {@code namespaceId}, with any extra assignments. A
     * name taken meanwhile surfaces as a unique violation, after waiting for its open writer; the
     * savepoint keeps the transaction usable to report the holder.
     */
    private static WriteResult place(Connection connection, UUID objectId, UUID catalogId, UUID namespaceId, Name name,
                                     String assignments) throws SQLException {
        Optional<List<UUID>> path = namespacePath(connection, namespaceId, catalogId);
        if (path.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            update(connection, "UPDATE catalog_object SET namespace_id = ?, namespace_path = ?, name_norm = ?, name = ?"
                            + assignments + " WHERE id = ?",
                    namespaceId, path.get().toArray(UUID[]::new), name.normalized(), name.display(), objectId);
        } catch (SQLException e) {
            if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw e;
            }
            connection.rollback(savepoint);
            return Guards.nameTaken(connection, ACTIVE_HOLDER, namespaceId, name.normalized());
        }
        connection.releaseSavepoint(savepoint);
        return WriteResult.APPLIED;
    }

    /** The namespace and its ancestors, share-locked; empty when it is missing or in another catalog. */
    private static Optional<List<UUID>> namespacePath(Connection connection, UUID namespaceId, UUID catalogId)
            throws SQLException {
        return first(connection, "SELECT ancestors FROM namespace WHERE id = ? AND catalog_id = ? FOR SHARE", row -> {
            List<UUID> path = new ArrayList<>(Jdbc.uuids(row, "ancestors"));
            path.add(namespaceId);
            return path;
        }, namespaceId, catalogId);
    }

    /** Where a locked object is. */
    private record Placed(UUID catalogId, UUID namespaceId) {
    }

    private static Optional<Placed> lock(Connection connection, UUID objectId, CatalogObject.State state)
            throws SQLException {
        return first(connection, "SELECT catalog_id, namespace_id FROM catalog_object WHERE id = ? AND state = ?"
                + " FOR UPDATE", row -> new Placed(Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "namespace_id")),
                objectId, state.name());
    }

    private record Held(UUID catalogId, List<UUID> path) {
    }

    private static Optional<Held> lockForRetention(Connection connection, UUID objectId, CatalogObject.State state)
            throws SQLException {
        return first(connection, "SELECT catalog_id, namespace_path FROM catalog_object"
                + " WHERE id = ? AND state = ? FOR UPDATE", row -> new Held(Jdbc.uuid(row, "catalog_id"),
                Jdbc.uuids(row, "namespace_path")), objectId, state.name());
    }

    /** A dropped or claimed object, locked: {@code claimant} is null until it is claimed. */
    private record Claim(UUID catalogId, List<UUID> path, Instant purgeAfter, String claimant) {
    }

    private static Optional<Claim> lockForPurge(Connection connection, UUID objectId) throws SQLException {
        return first(connection, "SELECT catalog_id, namespace_path, purge_after, purge_claimant FROM catalog_object"
                + " WHERE id = ? AND state IN ('DROPPED', 'PURGING') FOR UPDATE", row -> new Claim(
                Jdbc.uuid(row, "catalog_id"), Jdbc.uuids(row, "namespace_path"), instant(row, "purge_after"),
                row.getString("purge_claimant")), objectId);
    }

    static CatalogObject object(ResultSet row) throws SQLException {
        return new CatalogObject(Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "id"), Jdbc.uuid(row, "namespace_id"),
                new Name(row.getString("name_norm"), row.getString("name")),
                CatalogObject.Kind.valueOf(row.getString("kind")), CatalogObject.State.valueOf(row.getString("state")),
                Optional.ofNullable(instant(row, "dropped_at")), Optional.ofNullable(instant(row, "purge_after")),
                row.getString("location_root"), Optional.ofNullable(row.getString("purge_claimant")));
    }
}
