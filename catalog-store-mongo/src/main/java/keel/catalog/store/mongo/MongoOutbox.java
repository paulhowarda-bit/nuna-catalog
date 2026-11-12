package keel.catalog.store.mongo;

import static keel.catalog.store.mongo.Docs.after;
import static keel.catalog.store.mongo.Docs.ascending;
import static keel.catalog.store.mongo.Docs.in;
import static keel.catalog.store.mongo.Docs.instant;
import static keel.catalog.store.mongo.Docs.int64;
import static keel.catalog.store.mongo.Docs.keyFilter;
import static keel.catalog.store.mongo.Docs.page;
import static keel.catalog.store.mongo.Docs.string;
import static keel.catalog.store.mongo.Docs.time;
import static keel.catalog.store.mongo.Docs.uuid;
import static keel.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static keel.catalog.store.mongo.MongoCatalogStore.OUTBOX;
import static keel.catalog.store.mongo.MongoCatalogStore.OUTBOX_RELAY;

import com.mongodb.client.MongoCollection;
import keel.catalog.store.JournalEntry;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.OutboxBatch;
import keel.catalog.store.OutboxEntry;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PointerKey;
import keel.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;

/**
 * The outbox relay and the journal. One relay holds the lease on the {@code outbox_relay}
 * document at a time. Entries are claimed in Lamport clock order, then by id (section 9 b).
 */
final class MongoOutbox {

    static final String RELAY = "relay";

    private final Transactions transactions;

    MongoOutbox(Transactions transactions) {
        this.transactions = transactions;
    }

    /**
     * Takes or renews the lease in one single-document write, then reads the first entries. A
     * majority read: an entry it returns can no longer be rolled back by a failover.
     */
    Optional<OutboxBatch> claim(UUID relayId, Instant now, Duration lease, int limit) {
        return transactions.read("outbox claim", () -> {
            BsonDocument free = new BsonDocument("_id", new BsonString(RELAY)).append("$or", new BsonArray(List.of(
                    new BsonDocument("relayId", BsonNull.VALUE),
                    new BsonDocument("relayId", uuid(relayId)),
                    new BsonDocument("leaseUntil", new BsonDocument("$lte", time(now))))));
            BsonDocument taken = collection(OUTBOX_RELAY).findOneAndUpdate(free, new BsonDocument("$set",
                    new BsonDocument("relayId", uuid(relayId)).append("leaseUntil", time(now.plus(lease)))));
            if (taken == null) {
                return Optional.<OutboxBatch>empty();
            }
            List<OutboxEntry> entries = collection(OUTBOX).find().sort(ascending("clock", "_id")).limit(limit)
                    .map(doc -> new OutboxEntry(doc.getObjectId("_id").getValue().toHexString(), uuid(doc.get("commitId")),
                            instant(doc.get("createdAt")), doc.getString("type").getValue(), doc.getString("payload").getValue()))
                    .into(new ArrayList<>());
            return Optional.of(new OutboxBatch(relayId, entries));
        });
    }

    /**
     * Deletes relayed entries if {@code relayId} still holds the lease. The lease check writes
     * to the lease document, so a relay taking the lease over meanwhile conflicts with it.
     */
    WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now) {
        List<BsonValue> ids = entryIds.stream().<BsonValue>map(id -> new BsonObjectId(new ObjectId(id))).toList();
        return transactions.writeWithoutId("outbox relay", tx -> {
            BsonDocument holder = new BsonDocument("_id", new BsonString(RELAY)).append("relayId", uuid(relayId))
                    .append("leaseUntil", new BsonDocument("$gt", time(now)));
            if (tx.collection(OUTBOX_RELAY).updateOne(tx.session, holder,
                    new BsonDocument("$inc", new BsonDocument("marks", new BsonInt32(1)))).getMatchedCount() == 0) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            tx.collection(OUTBOX).deleteMany(tx.session, new BsonDocument("_id", in(ids)));
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Page<JournalRecord> readJournal(PointerKey key, Instant from, Instant to, PageRequest page) {
        Optional<Long> after = after(page, 1, parts -> Long.valueOf(parts.getFirst()));
        return transactions.read("journal read", () -> {
            BsonDocument filter = keyFilter(key).append("committedAt",
                    new BsonDocument("$gte", time(from)).append("$lt", time(to)));
            after.ifPresent(version -> filter.append("newVersion", new BsonDocument("$gt", int64(version))));
            List<JournalRecord> records = collection(JOURNAL).find(filter).sort(ascending("newVersion"))
                    .limit(page.size() + 1).map(doc -> new JournalRecord(uuid(doc.get("commitId")), key,
                            instant(doc.get("committedAt")), new JournalEntry(doc.getString("principal").getValue(),
                            string(doc, "endUser"), string(doc, "engine"), doc.getString("requirements").getValue(),
                            doc.getString("updates").getValue()), Optional.ofNullable(string(doc, "oldLocation")),
                            doc.getString("newLocation").getValue(), doc.getInt64("newVersion").getValue()))
                    .into(new ArrayList<>());
            return page(records, page, record -> PageToken.encode(Long.toString(record.newVersion())));
        });
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return transactions.database().getCollection(name, BsonDocument.class);
    }
}
