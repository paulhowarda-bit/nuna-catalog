package fidice.catalog.store.sql;

import static fidice.catalog.store.sql.Jdbc.first;
import static fidice.catalog.store.sql.Jdbc.instant;
import static fidice.catalog.store.sql.Jdbc.update;
import static fidice.catalog.store.sql.Jdbc.utc;

import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.StoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The records every commit and change writes beside its own: outbox events and the idempotency result. */
final class ChangeRecords {

    private ChangeRecords() {
    }

    /**
     * Writes the records of the commit or change {@code id} made at {@code at}. Outbox rows take
     * their sequence numbers here, after the change has locked what it touched, so of two changes
     * to one record the later one's events sort after the earlier's (section 9 b).
     */
    static void write(Connection connection, UUID id, Instant at, List<OutboxEvent> outbox,
                      Optional<IdempotencyResult> idempotency) throws SQLException {
        String sql = "INSERT INTO outbox (commit_id, created_at, type, payload) VALUES (?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (OutboxEvent event : outbox) {
                insert.setObject(1, id);
                insert.setObject(2, utc(at));
                insert.setString(3, event.type());
                insert.setObject(4, event.payloadJson(), Types.OTHER);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        if (idempotency.isPresent()) {
            writeResult(connection, id, at, idempotency.get());
        }
    }

    private record KeyRow(boolean done, UUID changeId, Instant expiresAt) {
    }

    /**
     * Completes the claim held with {@code id}, or records the result when no live record exists
     * (D10). Anything else fails the whole write cleanly. The row lock waits for an open writer of
     * the key; an insert racing another insert of the key fails on the primary key.
     */
    private static void writeResult(Connection connection, UUID id, Instant at, IdempotencyResult result)
            throws SQLException {
        Optional<KeyRow> row = first(connection,
                "SELECT status, change_id, expires_at FROM idempotency WHERE key = ? FOR UPDATE",
                rows -> new KeyRow(rows.getObject("status") != null, Jdbc.uuid(rows, "change_id"),
                        instant(rows, "expires_at")), result.key());
        if (row.isEmpty()) {
            update(connection, "INSERT INTO idempotency (key, principal, request_hash, status, body, expires_at, change_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", result.key(), result.principal(), result.requestHash(),
                    result.status(), result.body(), result.expiresAt(), id);
            return;
        }
        KeyRow held = row.get();
        boolean expired = !held.expiresAt().isAfter(at);
        if (!expired && (held.done() || !id.equals(held.changeId()))) {
            throw new StoreException("idempotency key " + result.key() + " is "
                    + (held.done() ? "already done" : "held by another request"), null);
        }
        complete(connection, id, result);
    }

    /** Turns the key's row into the final answer of {@code id}. */
    static int complete(Connection connection, UUID id, IdempotencyResult result) throws SQLException {
        return update(connection, "UPDATE idempotency SET principal = ?, request_hash = ?, status = ?, body = ?,"
                        + " expires_at = ?, change_id = ?, lease_until = NULL WHERE key = ?",
                result.principal(), result.requestHash(), result.status(), result.body(), result.expiresAt(), id,
                result.key());
    }
}
