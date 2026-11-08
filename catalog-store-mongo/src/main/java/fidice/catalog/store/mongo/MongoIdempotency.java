package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.Docs.ascending;
import static fidice.catalog.store.mongo.Docs.byId;
import static fidice.catalog.store.mongo.Docs.eq;
import static fidice.catalog.store.mongo.Docs.in;
import static fidice.catalog.store.mongo.Docs.instant;
import static fidice.catalog.store.mongo.Docs.string;
import static fidice.catalog.store.mongo.Docs.time;
import static fidice.catalog.store.mongo.Docs.uuid;
import static fidice.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;

import com.mongodb.client.MongoCollection;
import fidice.catalog.store.IdempotencyClaim;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.KeyState;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Idempotency keys (D10). A claim, completion or release is a transaction that commits with
 * majority write concern. Reads outside a transaction use majority read concern, so a key read
 * as done is durable: no failover can take back the answer a replay is given.
 */
final class MongoIdempotency {

    private static final Duration POLL = Duration.ofMillis(50);

    private final Transactions transactions;

    MongoIdempotency(Transactions transactions) {
        this.transactions = transactions;
    }

    private static KeyState state(BsonDocument row, String key, Instant now) {
        Instant expiresAt = instant(row.get("expiresAt"));
        if (!expiresAt.isAfter(now)) {
            return KeyState.ABSENT;
        }
        if (ChangeRecords.isDone(row)) {
            String body = string(row, "body");
            return new KeyState.Done(new IdempotencyResult(key, row.getString("principal").getValue(),
                    row.getString("requestHash").getValue(), row.getNumber("status").intValue(),
                    body == null ? "" : body, expiresAt));
        }
        return new KeyState.InFlight(uuid(row.get("changeId")), instant(row.get("leaseUntil")));
    }

    /**
     * Claims the key in a transaction. A done key found by the transaction's snapshot may not be
     * on a majority yet, so the claim commits its transaction, which wrote nothing, with majority
     * write concern: only then are the snapshot's reads majority-committed, and the answer it
     * returns is durable before it is returned.
     */
    KeyState claim(IdempotencyClaim claim) {
        return transactions.write(claim.changeId(), "idempotency claim", tx -> {
            BsonDocument key = eq("key", new BsonString(claim.key()));
            Optional<BsonDocument> row = tx.find(IDEMPOTENCY, key);
            if (row.isEmpty()) {
                tx.collection(IDEMPOTENCY).insertOne(tx.session, key.clone()
                        .append("principal", new BsonString(claim.principal()))
                        .append("requestHash", new BsonString(claim.requestHash()))
                        .append("changeId", uuid(claim.changeId()))
                        .append("leaseUntil", time(claim.leaseUntil()))
                        .append("expiresAt", time(claim.expiresAt())));
                return KeyState.CLAIMED;
            }
            BsonDocument held = row.get();
            KeyState state = state(held, claim.key(), claim.now());
            BsonDocument lease = new BsonDocument("changeId", uuid(claim.changeId()))
                    .append("leaseUntil", time(claim.leaseUntil()))
                    .append("expiresAt", time(claim.expiresAt()));
            if (state.equals(KeyState.ABSENT)) {
                tx.collection(IDEMPOTENCY).updateOne(tx.session, byId(held.get("_id")), new BsonDocument("$set",
                        lease.append("principal", new BsonString(claim.principal()))
                                .append("requestHash", new BsonString(claim.requestHash())))
                        .append("$unset", new BsonDocument("status", new BsonString("")).append("body", new BsonString(""))));
                return KeyState.CLAIMED;
            }
            if (!held.getString("principal").getValue().equals(claim.principal())
                    || !held.getString("requestHash").getValue().equals(claim.requestHash())) {
                return KeyState.MISMATCH;
            }
            if (state instanceof KeyState.Done) {
                return state;
            }
            if (instant(held.get("leaseUntil")).isAfter(claim.now())) {
                return state;
            }
            tx.collection(IDEMPOTENCY).updateOne(tx.session, byId(held.get("_id")), new BsonDocument("$set", lease));
            return KeyState.CLAIMED;
        }, state -> state.equals(KeyState.CLAIMED) || state instanceof KeyState.Done);
    }

    KeyState read(String key, Instant now) {
        return transactions.read("idempotency read", () -> Optional.ofNullable(keys().find(eq("key", new BsonString(key))).first())
                .map(row -> state(row, key, now)).orElse(KeyState.ABSENT));
    }

    KeyState await(String key, Duration limit) {
        Instant deadline = Instant.now().plus(limit);
        while (true) {
            Instant now = Instant.now();
            KeyState state = read(key, now);
            if (!(state instanceof KeyState.InFlight held) || !held.leaseUntil().isAfter(now) || now.isAfter(deadline)) {
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
        return transactions.write(changeId, "idempotency completion", tx -> {
            WriteResult holder = holder(tx, result.key(), changeId);
            if (holder.equals(WriteResult.APPLIED)) {
                ChangeRecords.complete(tx, changeId, result);
            }
            return holder;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult release(String key, UUID changeId) {
        return transactions.write(changeId, "idempotency release", tx -> {
            WriteResult holder = holder(tx, key, changeId);
            if (holder.equals(WriteResult.APPLIED)) {
                tx.collection(IDEMPOTENCY).deleteOne(tx.session, eq("key", new BsonString(key)));
            }
            return holder;
        }, WriteResult.APPLIED::equals);
    }

    /** Deletes up to {@code limit} expired keys, oldest first. It carries no id: a failure is safe to repeat. */
    int expire(Instant now, int limit) {
        return transactions.read("idempotency expiry", () -> {
            BsonDocument expired = new BsonDocument("expiresAt", new BsonDocument("$lte", time(now)));
            List<BsonValue> keys = keys().find(expired).sort(ascending("expiresAt")).limit(limit)
                    .projection(new BsonDocument("key", new BsonInt32(1)))
                    .map(row -> row.get("key")).into(new ArrayList<>());
            if (keys.isEmpty()) {
                return 0;
            }
            return (int) keys().deleteMany(expired.clone().append("key", in(keys))).getDeletedCount();
        });
    }

    private static WriteResult holder(Tx tx, String key, UUID changeId) {
        Optional<BsonDocument> row = tx.find(IDEMPOTENCY, eq("key", new BsonString(key)));
        if (row.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.KEY);
        }
        BsonValue holder = row.get().get("changeId");
        if (ChangeRecords.isDone(row.get()) || holder == null || !changeId.equals(uuid(holder))) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        return WriteResult.APPLIED;
    }

    private MongoCollection<BsonDocument> keys() {
        return transactions.database().getCollection(IDEMPOTENCY, BsonDocument.class);
    }
}
