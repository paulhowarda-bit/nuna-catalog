package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The DR replication watermark (D37, amended 2026-11-20), one row per table or view in {@code dr_watermark}. A
 * recording carries no change id and writes no audit record; it is safe to repeat. It share-locks the object, so a
 * drop waits, and a purge's completion deletes the row. The watermark only rises: the upsert replaces a lower one only.
 */
final class SqlDr {

    private static final WriteResult BEHIND = new WriteResult.Refused(WriteResult.Reason.WATERMARK_BEHIND);
    private static final WriteResult AHEAD = new WriteResult.Refused(WriteResult.Reason.WATERMARK_AHEAD);
    private static final String WATERMARK = "w.version AS w_version, w.metadata_location AS w_location,"
            + " w.recorded_at AS w_recorded_at";

    private final Transactions transactions;

    SqlDr(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, DrWatermark watermark) {
        return transactions.writeAndRetryOnFailure("DR watermark record", connection -> {
            if (!Jdbc.exists(connection, "SELECT 1 FROM catalog_object WHERE id = ? AND state = 'ACTIVE'"
                    + " AND kind IN ('TABLE', 'VIEW') FOR SHARE", objectId)) {
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
            int written = update(connection, "INSERT INTO dr_watermark AS w (object_id, version, metadata_location,"
                            + " recorded_at) VALUES (?, ?, ?, ?) ON CONFLICT (object_id) DO UPDATE"
                            + " SET version = excluded.version, metadata_location = excluded.metadata_location,"
                            + " recorded_at = excluded.recorded_at WHERE w.version < excluded.version",
                    objectId, watermark.version(), watermark.metadataLocation(), watermark.recordedAt());
            if (written == 1) {
                return WriteResult.APPLIED;
            }
            long kept = first(connection, "SELECT version FROM dr_watermark WHERE object_id = ?", row -> row.getLong(1),
                    objectId).orElseThrow(() -> new SQLException("the dr_watermark row the upsert kept is gone"));
            return kept == watermark.version() ? WriteResult.UNCHANGED : BEHIND;
        }, WriteResult.APPLIED::equals);
    }

    Optional<DrWatermark> read(UUID objectId) {
        return transactions.autocommit("DR watermark read", connection -> first(connection, "SELECT " + WATERMARK
                + " FROM dr_watermark w WHERE w.object_id = ?", SqlDr::watermark, objectId).flatMap(found -> found));
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
            List<DrState> behind = query(connection, "SELECT " + SqlObjects.COLUMNS + ", p.version AS p_version,"
                    + " p.metadata_location AS p_location, " + WATERMARK + " FROM catalog_object o"
                    + " JOIN pointer p ON p.object_id = o.id AND p.branch = ?"
                    + " LEFT JOIN dr_watermark w ON w.object_id = o.id"
                    + " WHERE o.state = 'ACTIVE' AND o.kind IN ('TABLE', 'VIEW')"
                    + " AND (w.version IS NULL OR w.version < p.version)" + keyset
                    + " ORDER BY o.id LIMIT ?", row -> new DrState(SqlObjects.object(row), row.getLong("p_version"),
                    row.getString("p_location"), watermark(row)), parameters.toArray());
            return Jdbc.page(behind, page, state -> PageToken.encode(state.object().id().toString()));
        });
    }

    private static Optional<DrWatermark> watermark(ResultSet row) throws SQLException {
        long version = row.getLong("w_version");
        if (row.wasNull()) {
            return Optional.empty();
        }
        return Optional.of(new DrWatermark(version, row.getString("w_location"), instant(row, "w_recorded_at")));
    }
}
