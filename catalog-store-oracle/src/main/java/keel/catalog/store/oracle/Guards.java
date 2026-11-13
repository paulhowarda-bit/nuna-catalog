package keel.catalog.store.oracle;

import static keel.catalog.store.oracle.Jdbc.first;

import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.Securable;
import keel.catalog.store.StoreException;
import keel.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Checks shared by the write paths: that a securable exists, who holds a name, and legal holds. */
final class Guards {

    private Guards() {
    }

    /**
     * Locks the securable, so a concurrent drop or purge waits for this write, and reports it
     * Missing when it does not exist (purged objects included) or lies in another catalog. Oracle
     * has no shared row lock, so this is FOR UPDATE: writes that guard one securable serialize.
     */
    static Optional<WriteResult> missing(Connection connection, Securable securable, Optional<UUID> catalogId)
            throws SQLException {
        Optional<UUID> owner = switch (securable.kind()) {
            case CATALOG -> first(connection, "SELECT id FROM catalog WHERE id = ? FOR UPDATE",
                    row -> Jdbc.uuid(row, "id"), securable.id());
            case NAMESPACE -> first(connection, "SELECT catalog_id FROM namespace WHERE id = ? FOR UPDATE",
                    row -> Jdbc.uuid(row, "catalog_id"), securable.id());
            case TABLE, VIEW -> first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ? AND kind = ?"
                    + " AND state <> 'PURGED' FOR UPDATE", row -> Jdbc.uuid(row, "catalog_id"),
                    securable.id(), securable.kind().name());
        };
        if (owner.isPresent() && catalogId.map(owner.get()::equals).orElse(true)) {
            return Optional.empty();
        }
        return Optional.of(new WriteResult.Missing(switch (securable.kind()) {
            case CATALOG -> WriteResult.Subject.CATALOG;
            case NAMESPACE -> WriteResult.Subject.NAMESPACE;
            case TABLE, VIEW -> WriteResult.Subject.OBJECT;
        }));
    }

    /**
     * The namespace and its ancestors, walking parent_id up to the catalog. Namespaces never move,
     * and one with children cannot be dropped, so the walk from a locked namespace is stable.
     */
    static List<UUID> pathOf(Connection connection, UUID namespaceId) throws SQLException {
        return Jdbc.query(connection, "SELECT id FROM namespace START WITH id = ? CONNECT BY id = PRIOR parent_id",
                row -> Jdbc.uuid(row, "id"), namespaceId);
    }

    /** Whether a legal hold is placed on the record itself, any namespace in {@code path}, or its catalog (D24). */
    static boolean held(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        List<Object> parameters = new ArrayList<>();
        String in = Jdbc.in("target_id", targets, parameters);
        return Jdbc.exists(connection, "SELECT 1 FROM legal_hold WHERE " + in, parameters.toArray());
    }

    /** Whether a DR freeze is set on the record itself, any namespace in {@code path}, or its catalog. */
    static boolean frozen(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        List<Object> parameters = new ArrayList<>();
        parameters.add(GovernanceAttribute.Kind.DR_FREEZE.name());
        String in = Jdbc.in("target_id", targets, parameters);
        return Jdbc.exists(connection, "SELECT 1 FROM governance_attribute WHERE kind = ? AND " + in,
                parameters.toArray());
    }

    /**
     * Whether the record is regulated, by a flag on it, any namespace in {@code path} or its catalog (D27), or
     * carries a row/column-policy flag itself (D20).
     */
    static boolean flagged(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        List<Object> parameters = new ArrayList<>();
        parameters.add("true");
        parameters.add(GovernanceAttribute.Kind.REGULATED.name());
        String in = Jdbc.in("target_id", targets, parameters);
        parameters.add(GovernanceAttribute.Kind.ROW_COLUMN_POLICY.name());
        parameters.add(id);
        return Jdbc.exists(connection, "SELECT 1 FROM governance_attribute WHERE value = ?"
                + " AND ((kind = ? AND " + in + ") OR (kind = ? AND target_id = ?))", parameters.toArray());
    }

    /** The holder of a name a write just failed to take; it committed, since the write waited for it. */
    static WriteResult nameTaken(Connection connection, String sql, Object... parameters) throws SQLException {
        return new WriteResult.NameTaken(first(connection, sql, row -> Jdbc.uuid(row, 1), parameters)
                .orElseThrow(() -> new StoreException("the holder of a taken name vanished; retry", null)));
    }
}
