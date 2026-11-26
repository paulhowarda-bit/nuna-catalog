package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.exists;
import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.Change;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Namespaces (D28). A create share-locks its parent and a drop locks the namespace for update, so
 * a child create and its parent's drop serialize. The unique (parent, normalized name) index makes
 * a create wait for an open create of the same name. A drop is refused while a ref's cut names a
 * table or view of the namespace, in any state, since a read through the ref takes its governance
 * from the namespace (D33, backlog 157).
 */
final class SqlNamespaces {

    private static final String COLUMNS = "n.id, n.catalog_id, n.parent_id, n.name_norm, n.name, n.version";

    private final Transactions transactions;

    SqlNamespaces(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult create(NamespaceRecord namespace, Change change) {
        return transactions.change(change, "namespace create", connection -> {
            List<UUID> ancestors;
            if (namespace.parentId().equals(namespace.catalogId())) {
                if (!exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR SHARE", namespace.catalogId())) {
                    return new WriteResult.Missing(WriteResult.Subject.CATALOG);
                }
                ancestors = List.of();
            } else {
                Optional<List<UUID>> parent = first(connection, "SELECT ancestors FROM namespace WHERE id = ?"
                        + " AND catalog_id = ? FOR SHARE", row -> Jdbc.uuids(row, "ancestors"),
                        namespace.parentId(), namespace.catalogId());
                if (parent.isEmpty()) {
                    return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
                }
                ancestors = new ArrayList<>(parent.get());
                ancestors.add(namespace.parentId());
            }
            int inserted = update(connection, "INSERT INTO namespace (id, catalog_id, parent_id, ancestors, name_norm,"
                            + " name, version) VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT (parent_id, name_norm) DO NOTHING",
                    namespace.id(), namespace.catalogId(), namespace.parentId(), ancestors.toArray(UUID[]::new),
                    namespace.name().normalized(), namespace.name().display());
            if (inserted == 0) {
                return Guards.nameTaken(connection, "SELECT id FROM namespace WHERE parent_id = ? AND name_norm = ?",
                        namespace.parentId(), namespace.name().normalized());
            }
            insertProperties(connection, namespace.id(), namespace.properties());
            return WriteResult.APPLIED;
        });
    }

    Optional<NamespaceRecord> find(UUID catalogId, List<String> normalizedPath) {
        return transactions.snapshot("namespace lookup", connection -> {
            UUID parent = catalogId;
            for (String name : normalizedPath) {
                Optional<UUID> child = first(connection, "SELECT id FROM namespace WHERE parent_id = ? AND name_norm = ?",
                        row -> Jdbc.uuid(row, "id"), parent, name);
                if (child.isEmpty()) {
                    return Optional.empty();
                }
                parent = child.get();
            }
            return normalizedPath.isEmpty() ? Optional.empty() : read(connection, parent);
        });
    }

    Optional<NamespaceRecord> read(UUID namespaceId) {
        return transactions.snapshot("namespace read", connection -> read(connection, namespaceId));
    }

    Page<NamespaceRecord> list(UUID parentId, PageRequest page) {
        Optional<String> after = Jdbc.after(page, 1, List::getFirst);
        return transactions.snapshot("namespace listing", connection -> {
            List<NamespaceRecord> namespaces = select(connection, "n.parent_id = ?"
                    + (after.isPresent() ? " AND n.name_norm > ?" : "") + " ORDER BY n.name_norm LIMIT ?",
                    after.isPresent() ? new Object[] {parentId, after.get(), page.size() + 1}
                            : new Object[] {parentId, page.size() + 1});
            return Jdbc.page(namespaces, page, namespace -> PageToken.encode(namespace.name().normalized()));
        });
    }

    WriteResult updateProperties(UUID namespaceId, long expectedVersion, Map<String, String> properties, Change change) {
        return transactions.change(change, "namespace properties update", connection -> {
            Optional<Long> version = first(connection, "SELECT version FROM namespace WHERE id = ? FOR UPDATE",
                    row -> row.getLong(1), namespaceId);
            if (version.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            if (version.get() != expectedVersion) {
                return new WriteResult.VersionMismatch(version.get());
            }
            update(connection, "DELETE FROM namespace_property WHERE namespace_id = ?", namespaceId);
            insertProperties(connection, namespaceId, properties);
            update(connection, "UPDATE namespace SET version = version + 1 WHERE id = ?", namespaceId);
            return WriteResult.APPLIED;
        });
    }

    private record Locked(UUID catalogId, List<UUID> ancestors) {
    }

    WriteResult drop(UUID namespaceId, Change change) {
        return transactions.change(change, "namespace drop", connection -> {
            Optional<Locked> locked = first(connection, "SELECT catalog_id, ancestors FROM namespace WHERE id = ? FOR UPDATE",
                    row -> new Locked(Jdbc.uuid(row, "catalog_id"), Jdbc.uuids(row, "ancestors")), namespaceId);
            if (locked.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            if (exists(connection, "SELECT 1 FROM namespace WHERE parent_id = ?", namespaceId)
                    || exists(connection, "SELECT 1 FROM catalog_object WHERE namespace_id = ? AND state = 'ACTIVE'",
                    namespaceId)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY);
            }
            if (exists(connection, "SELECT 1 FROM catalog_object o JOIN ref_entry e ON e.object_id = o.id"
                    + " WHERE o.namespace_id = ?", namespaceId)) {
                return new WriteResult.Refused(WriteResult.Reason.REFERENCED);
            }
            if (Guards.held(connection, namespaceId, locked.get().catalogId(), locked.get().ancestors())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            update(connection, "DELETE FROM namespace_property WHERE namespace_id = ?", namespaceId);
            update(connection, "DELETE FROM grant_record WHERE securable_id = ?", namespaceId);
            update(connection, "DELETE FROM ref_grant WHERE securable_id = ?", namespaceId);
            update(connection, "DELETE FROM governance_attribute WHERE target_id = ?", namespaceId);
            update(connection, "DELETE FROM namespace WHERE id = ?", namespaceId);
            return WriteResult.APPLIED;
        });
    }

    private static Optional<NamespaceRecord> read(Connection connection, UUID namespaceId) throws SQLException {
        List<NamespaceRecord> found = select(connection, "n.id = ?", namespaceId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** Namespaces matching {@code where}, with their properties, read in the caller's snapshot. */
    private static List<NamespaceRecord> select(Connection connection, String where, Object... parameters)
            throws SQLException {
        record Row(UUID id, UUID catalogId, UUID parentId, Name name, long version) {
        }
        List<Row> rows = query(connection, "SELECT " + COLUMNS + " FROM namespace n WHERE " + where,
                row -> new Row(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "parent_id"),
                        new Name(row.getString("name_norm"), row.getString("name")), row.getLong("version")), parameters);
        Map<UUID, Map<String, String>> properties = new HashMap<>();
        if (!rows.isEmpty()) {
            String sql = "SELECT namespace_id, key, value FROM namespace_property WHERE namespace_id = ANY(?)";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                Jdbc.bind(connection, select, (Object) rows.stream().map(Row::id).toArray(UUID[]::new));
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        properties.computeIfAbsent(Jdbc.uuid(row, "namespace_id"), id -> new LinkedHashMap<>())
                                .put(row.getString("key"), row.getString("value"));
                    }
                }
            }
        }
        return rows.stream().map(row -> new NamespaceRecord(row.catalogId(), row.id(), row.parentId(), row.name(),
                properties.getOrDefault(row.id(), Map.of()), row.version())).toList();
    }

    private static void insertProperties(Connection connection, UUID namespaceId, Map<String, String> properties)
            throws SQLException {
        String sql = "INSERT INTO namespace_property (namespace_id, key, value) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> property : properties.entrySet()) {
                insert.setObject(1, namespaceId);
                insert.setString(2, property.getKey());
                insert.setString(3, property.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
