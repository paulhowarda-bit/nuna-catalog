package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.Docs.eq;
import static fidice.catalog.store.mongo.Docs.instant;
import static fidice.catalog.store.mongo.Docs.time;
import static fidice.catalog.store.mongo.Docs.uuid;
import static fidice.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;
import static fidice.catalog.store.mongo.MongoCatalogStore.OUTBOX;

import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.StoreException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;

/** The records every commit and change writes beside its own: outbox events and the idempotency result. */
final class ChangeRecords {

    private ChangeRecords() {
    }

    /**
     * Writes the records of the commit or change {@code id} made at {@code at}. Its outbox entries
     * take the change's stamp, so of two changes to one record the later one's events sort after
     * the earlier's (section 9 b); entries of one stamp sort by id, in the order written.
     */
    static void write(Tx tx, Supplier<ObjectId> entryIds, UUID id, Instant at, List<OutboxEvent> outbox,
                      Optional<IdempotencyResult> idempotency) {
        List<BsonDocument> entries = new ArrayList<>();
        for (int position = 0; position < outbox.size(); position++) {
            entries.add(new BsonDocument("_id", new org.bson.BsonObjectId(entryIds.get()))
                    .append("commitId", uuid(id))
                    .append("position", new BsonInt32(position))
                    .append("createdAt", time(at))
                    .append("type", new BsonString(outbox.get(position).type()))
                    .append("payload", new BsonString(outbox.get(position).payloadJson()))
                    .append("clock", tx.clock()));
        }
        if (!entries.isEmpty()) {
            tx.collection(OUTBOX).insertMany(tx.session, entries);
        }
        if (idempotency.isPresent()) {
            writeResult(tx, id, at, idempotency.get());
        }
    }

    /**
     * Completes the claim held with {@code id}, or records the result when no live record exists
     * (D10). Anything else fails the whole write cleanly. A concurrent writer of the key conflicts
     * with this write, and the transaction runs again.
     */
    private static void writeResult(Tx tx, UUID id, Instant at, IdempotencyResult result) {
        Optional<BsonDocument> row = tx.find(IDEMPOTENCY, eq("key", new BsonString(result.key())));
        if (row.isEmpty()) {
            tx.collection(IDEMPOTENCY).insertOne(tx.session, done(result, id).append("key", new BsonString(result.key())));
            return;
        }
        BsonDocument held = row.get();
        boolean done = isDone(held);
        BsonValue holder = held.get("changeId");
        boolean expired = !instant(held.get("expiresAt")).isAfter(at);
        if (!expired && (done || holder == null || holder.isNull() || !id.equals(uuid(holder)))) {
            throw new StoreException("idempotency key " + result.key() + " is "
                    + (done ? "already done" : "held by another request"), null);
        }
        complete(tx, id, result);
    }

    /** Turns the key's record into the final answer of {@code id}. */
    static void complete(Tx tx, UUID id, IdempotencyResult result) {
        tx.collection(IDEMPOTENCY).updateOne(tx.session, eq("key", new BsonString(result.key())),
                new BsonDocument("$set", done(result, id)).append("$unset", new BsonDocument("leaseUntil", new BsonString(""))));
    }

    /** A record holds a final answer once it has a status; a claim has none. */
    static boolean isDone(BsonDocument record) {
        BsonValue status = record.get("status");
        return status != null && !status.isNull();
    }

    private static BsonDocument done(IdempotencyResult result, UUID id) {
        return new BsonDocument("principal", new BsonString(result.principal()))
                .append("requestHash", new BsonString(result.requestHash()))
                .append("status", new BsonInt32(result.status()))
                .append("body", new BsonString(result.body()))
                .append("expiresAt", time(result.expiresAt()))
                .append("changeId", uuid(id));
    }
}
