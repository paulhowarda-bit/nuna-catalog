package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.first;

import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
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
     * Share-locks the securable, so a concurrent drop or purge waits for this write, and reports
     * it Missing when it does not exist (purged objects included) or lies in another catalog.
     */
    static Optional<WriteResult> missing(Connection connection, Securable securable, Optional<UUID> catalogId)
            throws SQLException {
        return missing(connection, securable, catalogId, "FOR SHARE");
    }

    /**
     * Locks the securable for update, as a hold or a DR freeze placed on it does: a purge claim and a removal from refs
     * share-lock every level whose holds and freezes they read ({@link #lockLevels}), so one in flight commits before
     * the placement and one after it reads the hold (backlog 171). Missing as {@link #missing(Connection, Securable,
     * Optional)} answers it.
     */
    static Optional<WriteResult> missingForUpdate(Connection connection, Securable securable) throws SQLException {
        return missing(connection, securable, Optional.empty(), "FOR UPDATE");
    }

    private static Optional<WriteResult> missing(Connection connection, Securable securable, Optional<UUID> catalogId,
                                                 String lock) throws SQLException {
        Optional<UUID> owner = switch (securable.kind()) {
            case CATALOG -> first(connection, "SELECT id FROM catalog WHERE id = ? " + lock,
                    row -> Jdbc.uuid(row, "id"), securable.id());
            case NAMESPACE -> first(connection, "SELECT catalog_id FROM namespace WHERE id = ? " + lock,
                    row -> Jdbc.uuid(row, "catalog_id"), securable.id());
            case TABLE, VIEW -> first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ? AND kind = ?"
                    + " AND state <> 'PURGED' " + lock, row -> Jdbc.uuid(row, "catalog_id"),
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
     * Share-locks the namespaces of {@code path}, in id order, before a write reads the holds and DR freezes above a
     * record it has locked (backlog 171). A hold or freeze placed on one of them locks it for update ({@link
     * #missingForUpdate}), so the two take turns. The caller has locked the catalog's row first, which a hold or freeze
     * on the catalog locks for update too.
     */
    static void lockLevels(Connection connection, List<UUID> path) throws SQLException {
        Jdbc.query(connection, "SELECT id FROM namespace WHERE id = ANY(?) ORDER BY id FOR SHARE", row -> true,
                (Object) path.toArray(UUID[]::new));
    }

    /** Whether a legal hold is placed on the record itself, any namespace in {@code path}, or its catalog (D24). */
    static boolean held(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        return Jdbc.exists(connection, "SELECT 1 FROM legal_hold WHERE target_id = ANY(?)",
                (Object) targets.toArray(UUID[]::new));
    }

    /** Whether a DR freeze is set on the record itself, any namespace in {@code path}, or its catalog. */
    static boolean frozen(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        return Jdbc.exists(connection, "SELECT 1 FROM governance_attribute WHERE kind = ? AND target_id = ANY(?)",
                GovernanceAttribute.Kind.DR_FREEZE.name(), targets.toArray(UUID[]::new));
    }

    /**
     * Whether the record is regulated, by a flag on it, any namespace in {@code path} or its catalog (D27), or
     * carries a row/column-policy flag itself (D20).
     */
    static boolean flagged(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        return Jdbc.exists(connection, "SELECT 1 FROM governance_attribute WHERE value = ?"
                        + " AND ((kind = ? AND target_id = ANY(?)) OR (kind = ? AND target_id = ?))", "true",
                GovernanceAttribute.Kind.REGULATED.name(), targets.toArray(UUID[]::new),
                GovernanceAttribute.Kind.ROW_COLUMN_POLICY.name(), id);
    }

    /** The holder of a name a write just failed to take; it committed, since the write waited for it. */
    static WriteResult nameTaken(Connection connection, String sql, Object... parameters) throws SQLException {
        return new WriteResult.NameTaken(first(connection, sql, row -> row.getObject(1, UUID.class), parameters)
                .orElseThrow(() -> new StoreException("the holder of a taken name vanished; retry", null)));
    }
}
