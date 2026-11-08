package fidice.catalog.store.oracle;

import static fidice.catalog.store.oracle.Jdbc.exists;
import static fidice.catalog.store.oracle.Jdbc.first;
import static fidice.catalog.store.oracle.Jdbc.query;
import static fidice.catalog.store.oracle.Jdbc.update;

import fidice.catalog.store.Change;
import fidice.catalog.store.Name;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Page;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.PageToken;
import fidice.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Namespaces (D28). A create locks its parent and a drop locks the namespace, so a child create
 * and its parent's drop serialize; Oracle has no shared row lock, so creates under one parent
 * serialize too. The unique (parent, normalized name) key makes a create wait for an open create
 * of the same name.
 */
final class OracleNamespaces {

    private static final String COLUMNS = "id, catalog_id, parent_id, name_norm, name, version";

    private final Transactions transactions;

    OracleNamespaces(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult create(NamespaceRecord namespace, Change change) {
        return transactions.change(change, "namespace create", connection -> {
            if (namespace.parentId().equals(namespace.catalogId())) {
                if (!exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR UPDATE", namespace.catalogId())) {
                    return new WriteResult.Missing(WriteResult.Subject.CATALOG);
                }
            } else if (!exists(connection, "SELECT 1 FROM namespace WHERE id = ? AND catalog_id = ? FOR UPDATE",
                    namespace.parentId(), namespace.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            try {
                update(connection, "INSERT INTO namespace (id, catalog_id, parent_id, name_norm, name, version)"
                                + " VALUES (?, ?, ?, ?, ?, 1)", namespace.id(), namespace.catalogId(), namespace.parentId(),
                        namespace.name().normalized(), namespace.name().display());
            } catch (SQLIntegrityConstraintViolationException e) {
                if (!Jdbc.violates(e, "NAMESPACE_NAME_UK")) {
                    throw e;
                }
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
            List<Object> parameters = new ArrayList<>(List.of(parentId));
            String keyset = after.isPresent() ? " AND " + Jdbc.greater(List.of("name_norm"), List.of(after.get()), parameters)
                    : "";
            parameters.add(page.size() + 1);
            List<NamespaceRecord> namespaces = select(connection, "parent_id = ?" + keyset
                    + " ORDER BY name_norm FETCH FIRST ? ROWS ONLY", parameters.toArray());
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

    WriteResult drop(UUID namespaceId, Change change) {
        return transactions.change(change, "namespace drop", connection -> {
            Optional<UUID> catalogId = first(connection, "SELECT catalog_id FROM namespace WHERE id = ? FOR UPDATE",
                    row -> Jdbc.uuid(row, "catalog_id"), namespaceId);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            if (exists(connection, "SELECT 1 FROM namespace WHERE parent_id = ?", namespaceId)
                    || exists(connection, "SELECT 1 FROM catalog_object WHERE namespace_id = ? AND state = 'ACTIVE'",
                    namespaceId)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY);
            }
            if (Guards.held(connection, namespaceId, catalogId.get(), Guards.pathOf(connection, namespaceId))) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            update(connection, "DELETE FROM namespace_property WHERE namespace_id = ?", namespaceId);
            update(connection, "DELETE FROM grant_record WHERE securable_id = ?", namespaceId);
            update(connection, "DELETE FROM governance_attribute WHERE target_id = ?", namespaceId);
            update(connection, "DELETE FROM namespace WHERE id = ?", namespaceId);
            return WriteResult.APPLIED;
        });
    }

    private static Optional<NamespaceRecord> read(Connection connection, UUID namespaceId) throws SQLException {
        List<NamespaceRecord> found = select(connection, "id = ?", namespaceId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** Namespaces matching {@code where}, with their properties, read in the caller's snapshot. */
    private static List<NamespaceRecord> select(Connection connection, String where, Object... parameters)
            throws SQLException {
        record Row(UUID id, UUID catalogId, UUID parentId, Name name, long version) {
        }
        List<Row> rows = query(connection, "SELECT " + COLUMNS + " FROM namespace WHERE " + where,
                row -> new Row(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "parent_id"),
                        new Name(Jdbc.text(row, "name_norm"), Jdbc.text(row, "name")), row.getLong("version")),
                parameters);
        Map<UUID, Map<String, String>> properties = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Object> ids = new ArrayList<>();
            String in = Jdbc.in("namespace_id", rows.stream().map(Row::id).toList(), ids);
            query(connection, "SELECT namespace_id, key, value FROM namespace_property WHERE " + in, row -> {
                properties.computeIfAbsent(Jdbc.uuid(row, "namespace_id"), id -> new LinkedHashMap<>())
                        .put(Jdbc.text(row, "key"), Jdbc.text(row, "value"));
                return null;
            }, ids.toArray());
        }
        return rows.stream().map(row -> new NamespaceRecord(row.catalogId(), row.id(), row.parentId(), row.name(),
                properties.getOrDefault(row.id(), Map.of()), row.version())).toList();
    }

    private static void insertProperties(Connection connection, UUID namespaceId, Map<String, String> properties)
            throws SQLException {
        Jdbc.batch(connection, "INSERT INTO namespace_property (namespace_id, key, value) VALUES (?, ?, ?)",
                properties.entrySet().stream().map(property -> new Object[] {namespaceId, property.getKey(),
                        property.getValue()}).toList());
    }
}
