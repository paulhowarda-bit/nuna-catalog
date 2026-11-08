package fidice.catalog.store.sql;

import static fidice.catalog.store.sql.Jdbc.first;

import fidice.catalog.store.Securable;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.WriteResult;
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
        Optional<UUID> owner = switch (securable.kind()) {
            case CATALOG -> first(connection, "SELECT id FROM catalog WHERE id = ? FOR SHARE",
                    row -> Jdbc.uuid(row, "id"), securable.id());
            case NAMESPACE -> first(connection, "SELECT catalog_id FROM namespace WHERE id = ? FOR SHARE",
                    row -> Jdbc.uuid(row, "catalog_id"), securable.id());
            case TABLE, VIEW -> first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ? AND kind = ?"
                    + " AND state <> 'PURGED' FOR SHARE", row -> Jdbc.uuid(row, "catalog_id"),
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

    /** Whether a legal hold is placed on the record itself, any namespace in {@code path}, or its catalog (D24). */
    static boolean held(Connection connection, UUID id, UUID catalogId, List<UUID> path) throws SQLException {
        List<UUID> targets = new ArrayList<>(path);
        targets.add(id);
        targets.add(catalogId);
        return Jdbc.exists(connection, "SELECT 1 FROM legal_hold WHERE target_id = ANY(?)",
                (Object) targets.toArray(UUID[]::new));
    }

    /** The holder of a name a write just failed to take; it committed, since the write waited for it. */
    static WriteResult nameTaken(Connection connection, String sql, Object... parameters) throws SQLException {
        return new WriteResult.NameTaken(first(connection, sql, row -> row.getObject(1, UUID.class), parameters)
                .orElseThrow(() -> new StoreException("the holder of a taken name vanished; retry", null)));
    }
}
