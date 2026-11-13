package keel.catalog.store.mongo;

import static keel.catalog.store.mongo.Docs.after;
import static keel.catalog.store.mongo.Docs.ascending;
import static keel.catalog.store.mongo.Docs.byId;
import static keel.catalog.store.mongo.Docs.in;
import static keel.catalog.store.mongo.Docs.instant;
import static keel.catalog.store.mongo.Docs.page;
import static keel.catalog.store.mongo.Docs.time;
import static keel.catalog.store.mongo.Docs.uuid;
import static keel.catalog.store.mongo.Docs.uuids;
import static keel.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static keel.catalog.store.mongo.MongoCatalogStore.PENDING_DELETION;

import com.mongodb.client.MongoCursor;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.Change;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PendingDeletion;
import keel.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Files the maintenance worker deletes once the database's PITR window has passed (D30). Recording
 * marks the object, so a purge completing at the same moment conflicts with it and one of them
 * retries; the purge then deletes them with the object.
 */
final class MongoDeletions {

    private static final BsonArray LIVE = new BsonArray(List.of(
            new BsonString(CatalogObject.State.ACTIVE.name()), new BsonString(CatalogObject.State.DROPPED.name())));

    private final Transactions transactions;

    MongoDeletions(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, List<String> locations, Instant notBefore, Change change) {
        return transactions.change(change, "deletions record", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId))
                    .append("state", new BsonDocument("$in", LIVE)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            tx.see(object.get());
            tx.mark(OBJECT, object.get());
            if (!locations.isEmpty()) {
                List<BsonDocument> documents = new ArrayList<>();
                for (String location : locations) {
                    documents.add(byId(uuid(UUID.randomUUID())).append("objectId", uuid(objectId))
                            .append("catalogId", object.get().get("catalogId"))
                            .append("location", new BsonString(location))
                            .append("notBefore", time(notBefore))
                            .append("recordedAt", time(change.at()))
                            .append("clock", tx.clock()));
                }
                tx.collection(PENDING_DELETION).insertMany(tx.session, documents);
            }
            return WriteResult.APPLIED;
        });
    }

    /**
     * The due deletions of objects still active or dropped, in order, without those of objects under a legal
     * hold or a DR freeze. The deletions are read in order and their objects checked a chunk at a time, all in
     * one snapshot.
     */
    Page<PendingDeletion> listDue(Instant now, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 2,
                parts -> List.of(time(Instant.parse(parts.get(0))), uuid(UUID.fromString(parts.get(1)))));
        return transactions.snapshot("pending deletion scan", session -> {
            BsonDocument filter = new BsonDocument("notBefore", new BsonDocument("$lte", time(now)));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    Docs.after(List.of("notBefore", "_id"), values)))));
            List<PendingDeletion> due = new ArrayList<>();
            List<BsonDocument> chunk = new ArrayList<>();
            try (MongoCursor<BsonDocument> deletions = transactions.database()
                    .getCollection(PENDING_DELETION, BsonDocument.class).find(session, filter)
                    .sort(ascending("notBefore", "_id")).batchSize(MongoObjects.PURGE_CHUNK).iterator()) {
                while (due.size() <= page.size() && deletions.hasNext()) {
                    chunk.add(deletions.next());
                    if (chunk.size() == MongoObjects.PURGE_CHUNK || !deletions.hasNext()) {
                        List<UUID> objectIds = chunk.stream().map(deletion -> uuid(deletion.get("objectId")))
                                .distinct().toList();
                        Map<BsonValue, BsonDocument> live = new HashMap<>();
                        transactions.database().getCollection(OBJECT, BsonDocument.class)
                                .find(session, new BsonDocument("_id", in(uuids(objectIds).getValues()))
                                        .append("state", new BsonDocument("$in", LIVE)))
                                .forEach(object -> live.put(object.get("_id"), object));
                        Set<BsonValue> covered = live.isEmpty() ? Set.of()
                                : MongoObjects.covered(transactions, session, List.copyOf(live.values()));
                        for (BsonDocument deletion : chunk) {
                            BsonDocument object = live.get(deletion.get("objectId"));
                            if (object != null && MongoObjects.holdTargets(object).stream().noneMatch(covered::contains)) {
                                due.add(deletion(deletion));
                            }
                        }
                        chunk.clear();
                    }
                }
            }
            return page(due, page,
                    deletion -> PageToken.encode(deletion.notBefore().toString(), deletion.id().toString()));
        });
    }

    WriteResult complete(UUID objectId, List<UUID> ids, Change change) {
        return transactions.change(change, "deletions complete", tx -> {
            BsonDocument filter = new BsonDocument("objectId", uuid(objectId)).append("_id", in(uuids(ids).getValues()));
            List<BsonDocument> found = tx.collection(PENDING_DELETION).find(tx.session, filter).into(new ArrayList<>());
            if (found.isEmpty()) {
                return WriteResult.UNCHANGED;
            }
            found.forEach(tx::see);
            tx.collection(PENDING_DELETION).deleteMany(tx.session, filter);
            return WriteResult.APPLIED;
        });
    }

    private static PendingDeletion deletion(BsonDocument doc) {
        return new PendingDeletion(uuid(doc.get("_id")), uuid(doc.get("objectId")), uuid(doc.get("catalogId")),
                doc.getString("location").getValue(), instant(doc.get("notBefore")), instant(doc.get("recordedAt")));
    }
}
