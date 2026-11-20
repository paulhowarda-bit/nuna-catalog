package nuna.catalog.store.oracle;

import static nuna.catalog.store.oracle.Jdbc.first;
import static nuna.catalog.store.oracle.Jdbc.instant;
import static nuna.catalog.store.oracle.Jdbc.query;
import static nuna.catalog.store.oracle.OracleGovernance.upsert;

import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The DR replication watermark (D37, amended 2026-11-20), one row per table or view in {@code dr_watermark}. A
 * recording carries no change id and writes no audit record; it is safe to repeat. It locks the object's row (Oracle
 * has no shared row lock), so a drop waits, and a purge's completion deletes the row. The watermark only rises: the
 * merge replaces a lower one only.
 */
final class OracleDr {

    private static final WriteResult BEHIND = new WriteResult.Refused(WriteResult.Reason.WATERMARK_BEHIND);
    private static final WriteResult AHEAD = new WriteResult.Refused(WriteResult.Reason.WATERMARK_AHEAD);
    private static final String WATERMARK = "w.version w_version, w.metadata_location w_location,"
            + " w.recorded_at w_recorded_at";

    private final Transactions transactions;

    OracleDr(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, DrWatermark watermark) {
        return transactions.writeAndRetryOnFailure("DR watermark record", connection -> {
            if (!Jdbc.exists(connection, "SELECT 1 FROM catalog_object WHERE id = ? AND state = 'ACTIVE'"
                    + " AND kind IN ('TABLE', 'VIEW') FOR UPDATE", objectId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Optional<Long> pointer = first(connection, "SELECT version FROM pointer WHERE object_id = ? AND branch = ?",
                    row -> row.getLong(1), objectId, PointerKey.MAIN);
            if (pointer.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (watermark.version() > pointer.get()) {
                return AHEAD;
            }
            int written = raise(connection, objectId, watermark);
            if (written == 1) {
                return WriteResult.APPLIED;
            }
            long kept = first(connection, "SELECT version FROM dr_watermark WHERE object_id = ?", row -> row.getLong(1),
                    objectId).orElseThrow(() -> new SQLException("the dr_watermark row the merge kept is gone"));
            return kept == watermark.version() ? WriteResult.UNCHANGED : BEHIND;
        }, WriteResult.APPLIED::equals);
    }

    /**
     * Keeps {@code watermark} in place of a lower one, in the caller's transaction: the report's write, and a DR
     * rewind's in its commit ({@link Pointers}). Returns the rows merged, 0 when the same or a higher one is kept.
     */
    static int raise(Connection connection, UUID objectId, DrWatermark watermark) throws SQLException {
        return upsert(connection, "dr_watermark_pk", "MERGE INTO dr_watermark t USING (SELECT ? object_id,"
                        + " ? version, ? metadata_location, ? recorded_at FROM dual) s ON (t.object_id = s.object_id)"
                        + " WHEN MATCHED THEN UPDATE SET t.version = s.version,"
                        + " t.metadata_location = s.metadata_location, t.recorded_at = s.recorded_at"
                        + " WHERE t.version < s.version"
                        + " WHEN NOT MATCHED THEN INSERT (object_id, version, metadata_location, recorded_at)"
                        + " VALUES (s.object_id, s.version, s.metadata_location, s.recorded_at)",
                objectId, watermark.version(), watermark.metadataLocation(), watermark.recordedAt());
    }

    Optional<DrWatermark> read(UUID objectId) {
        return transactions.autocommit("DR watermark read", connection -> first(connection, "SELECT " + WATERMARK
                + " FROM dr_watermark w WHERE w.object_id = ?", OracleDr::watermark, objectId).flatMap(found -> found));
    }

    Page<DrState> listBehind(PageRequest page) {
        Optional<UUID> after = Jdbc.after(page, 1, parts -> UUID.fromString(parts.get(0)));
        return transactions.autocommit("DR watermarks behind", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(PointerKey.MAIN));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND o.id > ?";
                parameters.add(after.get());
            }
            parameters.add(page.size() + 1);
            List<DrState> behind = query(connection, "SELECT " + OracleObjects.COLUMNS + ", p.version p_version,"
                    + " p.metadata_location p_location, " + WATERMARK + " FROM catalog_object o"
                    + " JOIN pointer p ON p.object_id = o.id AND p.branch = ?"
                    + " LEFT JOIN dr_watermark w ON w.object_id = o.id"
                    + " WHERE o.state = 'ACTIVE' AND o.kind IN ('TABLE', 'VIEW')"
                    + " AND (w.version IS NULL OR w.version < p.version)" + keyset
                    + " ORDER BY o.id FETCH FIRST ? ROWS ONLY", row -> new DrState(OracleObjects.object(row),
                    row.getLong("p_version"), Jdbc.text(row, "p_location"), watermark(row)), parameters.toArray());
            return Jdbc.page(behind, page, state -> PageToken.encode(state.object().id().toString()));
        });
    }

    private static Optional<DrWatermark> watermark(ResultSet row) throws SQLException {
        if (row.getObject("w_version") == null) {
            return Optional.empty();
        }
        return Optional.of(new DrWatermark(row.getLong("w_version"), Jdbc.text(row, "w_location"),
                instant(row, "w_recorded_at")));
    }
}
