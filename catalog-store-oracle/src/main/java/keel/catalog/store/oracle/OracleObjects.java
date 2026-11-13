package keel.catalog.store.oracle;

import static keel.catalog.store.oracle.Jdbc.first;
import static keel.catalog.store.oracle.Jdbc.instant;
import static keel.catalog.store.oracle.Jdbc.query;
import static keel.catalog.store.oracle.Jdbc.update;

import keel.catalog.store.CatalogObject;
import keel.catalog.store.Change;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitResult;
import keel.catalog.store.Name;
import keel.catalog.store.ObjectCreation;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.Pointer;
import keel.catalog.store.StoreException;
import keel.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tables and views (D25). Names are unique among active objects through a function-based unique
 * index that holds active rows only; a write that takes a name waits for an open writer of it, and
 * a failed statement rolls back only itself. Writes lock the object and the namespace they put it
 * in (FOR UPDATE: Oracle has no shared row lock).
 */
final class OracleObjects {

    private static final String COLUMNS = "id, catalog_id, namespace_id, name_norm, name, kind, state, dropped_at,"
            + " purge_after, location_root";
    private static final String ACTIVE_NAME_INDEX = "CATALOG_OBJECT_ACTIVE_NAME";
    /** The active-name lookup, written with the unique index's own expressions so it can use the index. */
    private static final String ACTIVE_NAME = "CASE WHEN state = 'ACTIVE' THEN namespace_id END = ?"
            + " AND CASE WHEN state = 'ACTIVE' THEN name_norm END = ?";

    private final Transactions transactions;

    OracleObjects(Transactions transactions) {
        this.transactions = transactions;
    }

    /**
     * The object, its first pointer and that commit's records, in one transaction (section 9 a).
     * Its COMMIT is the first commit's, so an unknown outcome is decided by findCommit with
     * Transaction Guard, as a commit's is.
     */
    WriteResult create(ObjectCreation creation) {
        CatalogObject object = creation.object();
        return transactions.write(creation.firstCommit().commitId(), "object create", Transactions.Outcome.REMEMBER,
                connection -> {
                    Optional<List<UUID>> path = namespacePath(connection, object.namespaceId(), object.catalogId());
                    if (path.isEmpty()) {
                        return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
                    }
                    try {
                        update(connection, "INSERT INTO catalog_object (id, catalog_id, namespace_id, name_norm, name, kind,"
                                        + " state, location_root) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                                object.id(), object.catalogId(), object.namespaceId(), object.name().normalized(),
                                object.name().display(), object.kind().name(), object.locationRoot());
                    } catch (SQLIntegrityConstraintViolationException e) {
                        if (!Jdbc.violates(e, ACTIVE_NAME_INDEX)) {
                            throw e;
                        }
                        return Guards.nameTaken(connection, "SELECT id FROM catalog_object WHERE " + ACTIVE_NAME,
                                object.namespaceId(), object.name().normalized());
                    }
                    writePath(connection, object.id(), path.get());
                    if (!(Pointers.apply(connection, creation.firstCommit()) instanceof CommitResult.Committed)) {
                        throw new StoreException("object " + object.id() + " already has a pointer", null);
                    }
                    return WriteResult.APPLIED;
                }, WriteResult.APPLIED::equals);
    }

    Optional<CatalogObject> find(UUID namespaceId, String normalizedName) {
        return transactions.autocommit("object lookup", connection -> first(connection, "SELECT " + COLUMNS
                + " FROM catalog_object WHERE " + ACTIVE_NAME, OracleObjects::object, namespaceId, normalizedName));
    }

    Optional<CatalogObject> read(UUID objectId) {
        return transactions.autocommit("object read", connection -> first(connection,
                "SELECT " + COLUMNS + " FROM catalog_object WHERE id = ?", OracleObjects::object, objectId));
    }

    Page<CatalogObject> list(UUID namespaceId, CatalogObject.Kind kind, CatalogObject.State state, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2, parts -> List.of(parts.get(0), UUID.fromString(parts.get(1))));
        return transactions.autocommit("object listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(namespaceId, kind.name(), state.name()));
            String keyset = after.isPresent()
                    ? " AND " + Jdbc.greater(List.of("name_norm", "id"), after.get(), parameters) : "";
            parameters.add(page.size() + 1);
            List<CatalogObject> objects = query(connection, "SELECT " + COLUMNS + " FROM catalog_object"
                    + " WHERE namespace_id = ? AND kind = ? AND state = ?" + keyset
                    + " ORDER BY name_norm, id FETCH FIRST ? ROWS ONLY", OracleObjects::object, parameters.toArray());
            return Jdbc.page(objects, page, object -> PageToken.encode(object.name().normalized(), object.id().toString()));
        });
    }

    WriteResult rename(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object rename", connection -> {
            Optional<UUID> catalogId = lock(connection, objectId, CatalogObject.State.ACTIVE);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(connection, objectId, catalogId.get(), namespaceId, name, "");
        });
    }

    WriteResult drop(UUID objectId, Instant purgeAfter, Change change) {
        return transactions.change(change, "object drop", connection -> {
            Optional<Held> locked = lockForRetention(connection, objectId, CatalogObject.State.ACTIVE);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (Guards.held(connection, objectId, locked.get().catalogId(), path(connection, objectId))) {
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
     * unregistered. The holds and DR freezes are checked under the object's lock, as a drop checks holds.
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
            if (Guards.held(connection, objectId, locked.get().catalogId(), path(connection, objectId))) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(connection, objectId, locked.get().catalogId(), path(connection, objectId))) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            update(connection, "UPDATE catalog_object SET state = 'UNREGISTERED', dropped_at = ? WHERE id = ?",
                    change.at(), objectId);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Locks the batch's pointers, then the object, as unregister does; puts the object at the name as
     * undrop does, and writes the batch, all in the batch's transaction.
     */
    WriteResult revive(UUID objectId, CatalogObject.State from, UUID namespaceId, Name name, CommitBatch batch) {
        return transactions.write(batch.commitId(), "object revive", Transactions.Outcome.REMEMBER,
                connection -> {
            Optional<CommitResult.Conflict> conflict = Pointers.lock(connection, batch);
            if (conflict.isPresent()) {
                return new WriteResult.VersionMismatch(conflict.get().actual().map(Pointer::version).orElse(0L));
            }
            Optional<UUID> catalogId = lock(connection, objectId, from);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            WriteResult placed = place(connection, objectId, catalogId.get(), namespaceId, name,
                    ", state = 'ACTIVE', dropped_at = NULL, purge_after = NULL");
            if (placed.equals(WriteResult.APPLIED)) {
                Pointers.record(connection, batch);
            }
            return placed;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult undrop(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object undrop", connection -> {
            Optional<UUID> catalogId = lock(connection, objectId, CatalogObject.State.DROPPED);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(connection, objectId, catalogId.get(), namespaceId, name,
                    ", state = 'ACTIVE', dropped_at = NULL, purge_after = NULL");
        });
    }

    Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2,
                parts -> List.of(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1))));
        return transactions.autocommit("purge scan", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(now));
            String keyset = after.isPresent()
                    ? " AND " + Jdbc.greater(List.of("o.purge_after", "o.id"), after.get(), parameters) : "";
            parameters.add(page.size() + 1);
            List<CatalogObject> objects = query(connection, "SELECT " + COLUMNS + " FROM catalog_object o"
                    + " WHERE o.state = 'DROPPED' AND o.purge_after <= ?" + keyset
                    + " AND NOT EXISTS (SELECT 1 FROM legal_hold h WHERE h.target_id IN (o.id, o.catalog_id))"
                    + " AND NOT EXISTS (SELECT 1 FROM object_path p JOIN legal_hold h ON h.target_id = p.namespace_id"
                    + " WHERE p.object_id = o.id) ORDER BY o.purge_after, o.id FETCH FIRST ? ROWS ONLY",
                    OracleObjects::object, parameters.toArray());
            return Jdbc.page(objects, page,
                    object -> PageToken.encode(object.purgeAfter().orElseThrow().toString(), object.id().toString()));
        });
    }

    WriteResult purge(UUID objectId, Change change) {
        return transactions.change(change, "object purge", connection -> {
            Optional<Held> locked = lockForRetention(connection, objectId, CatalogObject.State.DROPPED);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (locked.get().purgeAfter().isAfter(change.at())) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_DUE);
            }
            if (Guards.held(connection, objectId, locked.get().catalogId(), path(connection, objectId))) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            update(connection, "UPDATE catalog_object SET state = 'PURGED' WHERE id = ?", objectId);
            update(connection, "DELETE FROM grant_record WHERE securable_id = ?", objectId);
            return WriteResult.APPLIED;
        });
    }

    /**
     * Puts a locked object at {@code name} in {@code namespaceId}, with any extra assignments. A
     * name taken meanwhile surfaces as a unique violation, after waiting for its open writer; the
     * failed statement rolls back alone, so the transaction stays usable to report the holder.
     */
    private static WriteResult place(Connection connection, UUID objectId, UUID catalogId, UUID namespaceId, Name name,
                                     String assignments) throws SQLException {
        Optional<List<UUID>> path = namespacePath(connection, namespaceId, catalogId);
        if (path.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
        }
        try {
            update(connection, "UPDATE catalog_object SET namespace_id = ?, name_norm = ?, name = ?" + assignments
                    + " WHERE id = ?", namespaceId, name.normalized(), name.display(), objectId);
        } catch (SQLIntegrityConstraintViolationException e) {
            if (!Jdbc.violates(e, ACTIVE_NAME_INDEX)) {
                throw e;
            }
            return Guards.nameTaken(connection, "SELECT id FROM catalog_object WHERE " + ACTIVE_NAME,
                    namespaceId, name.normalized());
        }
        update(connection, "DELETE FROM object_path WHERE object_id = ?", objectId);
        writePath(connection, objectId, path.get());
        return WriteResult.APPLIED;
    }

    /** The namespace and its ancestors, the namespace locked; empty when it is missing or in another catalog. */
    private static Optional<List<UUID>> namespacePath(Connection connection, UUID namespaceId, UUID catalogId)
            throws SQLException {
        if (!Jdbc.exists(connection, "SELECT 1 FROM namespace WHERE id = ? AND catalog_id = ? FOR UPDATE",
                namespaceId, catalogId)) {
            return Optional.empty();
        }
        return Optional.of(Guards.pathOf(connection, namespaceId));
    }

    private static void writePath(Connection connection, UUID objectId, List<UUID> path) throws SQLException {
        Jdbc.batch(connection, "INSERT INTO object_path (object_id, namespace_id) VALUES (?, ?)",
                path.stream().map(namespaceId -> new Object[] {objectId, namespaceId}).toList());
    }

    /** The namespaces that held the object when it was last placed, kept after they are dropped. */
    private static List<UUID> path(Connection connection, UUID objectId) throws SQLException {
        return query(connection, "SELECT namespace_id FROM object_path WHERE object_id = ?",
                row -> Jdbc.uuid(row, "namespace_id"), objectId);
    }

    private static Optional<UUID> lock(Connection connection, UUID objectId, CatalogObject.State state)
            throws SQLException {
        return first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ? AND state = ? FOR UPDATE",
                row -> Jdbc.uuid(row, "catalog_id"), objectId, state.name());
    }

    private record Held(UUID catalogId, Instant purgeAfter) {
    }

    private static Optional<Held> lockForRetention(Connection connection, UUID objectId, CatalogObject.State state)
            throws SQLException {
        return first(connection, "SELECT catalog_id, purge_after FROM catalog_object WHERE id = ? AND state = ?"
                + " FOR UPDATE", row -> new Held(Jdbc.uuid(row, "catalog_id"), instant(row, "purge_after")),
                objectId, state.name());
    }

    private static CatalogObject object(ResultSet row) throws SQLException {
        return new CatalogObject(Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "id"), Jdbc.uuid(row, "namespace_id"),
                new Name(Jdbc.text(row, "name_norm"), Jdbc.text(row, "name")),
                CatalogObject.Kind.valueOf(row.getString("kind")), CatalogObject.State.valueOf(row.getString("state")),
                Optional.ofNullable(instant(row, "dropped_at")), Optional.ofNullable(instant(row, "purge_after")),
                Jdbc.text(row, "location_root"));
    }
}
