package nuna.catalog.store.oracle;

import static nuna.catalog.store.oracle.Jdbc.first;
import static nuna.catalog.store.oracle.Jdbc.instant;
import static nuna.catalog.store.oracle.Jdbc.update;

import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency keys (D10). A claim is a row without a status; the commit or change carrying the
 * key's result completes it (ChangeRecords). Every Done answer is made durable first, as
 * findCommit's COMMITTED is: Oracle shows a commit before its redo is written.
 */
final class OracleIdempotency {

    private static final Duration POLL = Duration.ofMillis(50);

    private final Transactions transactions;

    OracleIdempotency(Transactions transactions) {
        this.transactions = transactions;
    }

    private record KeyRow(String principal, String requestHash, Integer status, String body, Instant expiresAt,
                          UUID changeId, Instant leaseUntil) {

        static KeyRow of(ResultSet row) throws SQLException {
            int status = row.getInt("status");
            Integer done = row.wasNull() ? null : status;
            return new KeyRow(Jdbc.text(row, "principal"), Jdbc.text(row, "request_hash"),
                    done, Jdbc.text(row, "body"), instant(row, "expires_at"),
                    Jdbc.uuid(row, "change_id"), instant(row, "lease_until"));
        }

        /** A done row's NULL body is an empty one the previous release wrote ('' is NULL in Oracle). */
        KeyState state(String key, Instant now) {
            if (!expiresAt.isAfter(now)) {
                return KeyState.ABSENT;
            }
            if (status != null) {
                return new KeyState.Done(new IdempotencyResult(key, principal, requestHash, status, body == null ? "" : body,
                        expiresAt));
            }
            return new KeyState.InFlight(changeId, leaseUntil);
        }
    }

    private static final String SELECT = "SELECT principal, request_hash, status, body, expires_at, change_id, lease_until"
            + " FROM idempotency WHERE key = ?";

    /**
     * Locks the key's row, waiting for an open writer of it. An insert that finds the key taken
     * waited for its inserter, so the row it reads next is committed. A Done answer is durable
     * too: the row lock is written to the block, so the transaction has redo, and its COMMIT
     * returns only once the redo before it, the answer's included, is written.
     */
    KeyState claim(IdempotencyClaim claim) {
        return transactions.write(claim.changeId(), "idempotency claim", Transactions.Outcome.FORGET, connection -> {
            Optional<KeyRow> row = first(connection, SELECT + " FOR UPDATE", KeyRow::of, claim.key());
            if (row.isEmpty()) {
                try {
                    update(connection, "INSERT INTO idempotency (key, principal, request_hash, change_id, lease_until,"
                                    + " expires_at) VALUES (?, ?, ?, ?, ?, ?)", claim.key(), claim.principal(),
                            claim.requestHash(), claim.changeId(), claim.leaseUntil(), claim.expiresAt());
                    return KeyState.CLAIMED;
                } catch (SQLIntegrityConstraintViolationException e) {
                    if (!Jdbc.violates(e, "IDEMPOTENCY_PK")) {
                        throw e;
                    }
                }
                row = first(connection, SELECT + " FOR UPDATE", KeyRow::of, claim.key());
                if (row.isEmpty()) {
                    throw new StoreException("idempotency key " + claim.key() + " was deleted while claimed; retry", null);
                }
            }
            KeyRow held = row.get();
            KeyState state = held.state(claim.key(), claim.now());
            if (state.equals(KeyState.ABSENT)) {
                update(connection, "UPDATE idempotency SET principal = ?, request_hash = ?, status = NULL, body = NULL,"
                                + " change_id = ?, lease_until = ?, expires_at = ? WHERE key = ?", claim.principal(),
                        claim.requestHash(), claim.changeId(), claim.leaseUntil(), claim.expiresAt(), claim.key());
                return KeyState.CLAIMED;
            }
            if (!held.principal().equals(claim.principal()) || !held.requestHash().equals(claim.requestHash())) {
                return KeyState.MISMATCH;
            }
            if (state instanceof KeyState.Done || held.leaseUntil().isAfter(claim.now())) {
                return state;
            }
            update(connection, "UPDATE idempotency SET change_id = ?, lease_until = ?, expires_at = ? WHERE key = ?",
                    claim.changeId(), claim.leaseUntil(), claim.expiresAt(), claim.key());
            return KeyState.CLAIMED;
        }, state -> state.equals(KeyState.CLAIMED) || state instanceof KeyState.Done);
    }

    KeyState read(String key, Instant now) {
        KeyState state = readUnconfirmed(key, now);
        if (state instanceof KeyState.Done) {
            transactions.awaitDurable("idempotency read of " + key);
        }
        return state;
    }

    KeyState await(String key, Duration limit) {
        Instant deadline = Instant.now().plus(limit);
        while (true) {
            Instant now = Instant.now();
            KeyState state = readUnconfirmed(key, now);
            if (!(state instanceof KeyState.InFlight held) || !held.leaseUntil().isAfter(now) || now.isAfter(deadline)) {
                if (state instanceof KeyState.Done) {
                    transactions.awaitDurable("idempotency wait for " + key);
                }
                return state;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException("interrupted waiting for idempotency key " + key, e);
            }
        }
    }

    WriteResult complete(IdempotencyResult result, UUID changeId) {
        return transactions.write(changeId, "idempotency completion", Transactions.Outcome.FORGET, connection -> {
            WriteResult holder = holder(connection, result.key(), changeId);
            if (holder.equals(WriteResult.APPLIED)) {
                ChangeRecords.complete(connection, changeId, result);
            }
            return holder;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult release(String key, UUID changeId) {
        return transactions.write(changeId, "idempotency release", Transactions.Outcome.FORGET, connection -> {
            WriteResult holder = holder(connection, key, changeId);
            if (holder.equals(WriteResult.APPLIED)) {
                update(connection, "DELETE FROM idempotency WHERE key = ?", key);
            }
            return holder;
        }, WriteResult.APPLIED::equals);
    }

    int expire(Instant now, int limit) {
        return transactions.writeAndRetryOnFailure("idempotency expiry", connection -> update(connection,
                "DELETE FROM idempotency WHERE key IN (SELECT key FROM idempotency WHERE expires_at <= ?"
                        + " ORDER BY expires_at FETCH FIRST ? ROWS ONLY)", now, limit), deleted -> true);
    }

    private KeyState readUnconfirmed(String key, Instant now) {
        return transactions.autocommit("idempotency read", connection -> first(connection, SELECT, KeyRow::of, key)
                .map(row -> row.state(key, now)).orElse(KeyState.ABSENT));
    }

    /** Applied when the claim held with {@code changeId} is still open, after locking its row. */
    private static WriteResult holder(Connection connection, String key, UUID changeId) throws SQLException {
        Optional<KeyRow> row = first(connection, SELECT + " FOR UPDATE", KeyRow::of, key);
        if (row.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.KEY);
        }
        if (row.get().status() != null || !changeId.equals(row.get().changeId())) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        return WriteResult.APPLIED;
    }
}
