package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.optionalInstant;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.string;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.Docs.uuids;
import static nuna.catalog.store.mongo.MongoCatalogStore.MAINTENANCE_REQUEST;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Requests to run a table's compaction or orphan scan now (D30, amended 2026-11-20; backlog 114), in
 * {@code maintenance_request}: one document per table and job ({@code objectId}, {@code job}), waiting until the
 * worker reports the job started. A request marks the table, so a drop at the same moment conflicts with it and one of
 * them retries; a purge's completion deletes the table's requests. The waiting are read in order and their tables
 * checked a chunk at a time, in one snapshot.
 */
final class MongoRequests {

    private static final String ACTIVE = CatalogObject.State.ACTIVE.name();
    private static final List<String> ORDER = List.of("requestedAt", "objectId", "job");

    private final Transactions transactions;

    MongoRequests(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult request(MaintenanceRequest request, Change change) {
        return transactions.change(change, "maintenance request", tx -> {
            Optional<BsonDocument> table = tx.find(OBJECT, byId(uuid(request.objectId()))
                    .append("state", new BsonString(ACTIVE))
                    .append("kind", new BsonString(CatalogObject.Kind.TABLE.name())));
            if (table.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            BsonDocument key = key(request.objectId(), request.job());
            // A request waiting already is the one asked for; a started one gives way to this.
            Optional<BsonDocument> kept = tx.find(MAINTENANCE_REQUEST, key);
            if (kept.isPresent() && optionalInstant(kept.get(), "startedAt").isEmpty()) {
                return WriteResult.UNCHANGED;
            }
            tx.see(table.get());
            tx.mark(OBJECT, table.get());
            tx.collection(MAINTENANCE_REQUEST).updateOne(tx.session, key, new BsonDocument("$set",
                    new BsonDocument("requestedBy", new BsonString(request.requestedBy()))
                            .append("requestedAt", time(request.requestedAt()))
                            .append("startedAt", BsonNull.VALUE)), new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        });
    }

    List<MaintenanceRequest> list(UUID objectId) {
        return transactions.read("maintenance requests read", () -> collection().find(
                new BsonDocument("objectId", uuid(objectId))).sort(ascending("job")).map(MongoRequests::request)
                .into(new ArrayList<>()));
    }

    Page<MaintenanceRequest> listWaiting(PageRequest page) {
        Optional<List<BsonValue>> after = Docs.after(page, 3, parts -> List.of(time(Instant.parse(parts.get(0))),
                uuid(UUID.fromString(parts.get(1))), new BsonString(MaintenanceRequest.Job.valueOf(parts.get(2)).name())));
        return transactions.snapshot("waiting maintenance requests", session -> {
            BsonDocument filter = new BsonDocument("startedAt", BsonNull.VALUE);
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(Docs.after(ORDER, values)))));
            List<MaintenanceRequest> waiting = new ArrayList<>();
            List<BsonDocument> chunk = new ArrayList<>();
            try (MongoCursor<BsonDocument> requests = collection().find(session, filter)
                    .sort(ascending(ORDER.toArray(String[]::new))).batchSize(MongoObjects.PURGE_CHUNK).iterator()) {
                while (waiting.size() <= page.size() && requests.hasNext()) {
                    chunk.add(requests.next());
                    if (chunk.size() == MongoObjects.PURGE_CHUNK || !requests.hasNext()) {
                        List<UUID> ids = chunk.stream().map(doc -> uuid(doc.get("objectId"))).distinct().toList();
                        Set<BsonValue> active = new HashSet<>();
                        transactions.database().getCollection(OBJECT, BsonDocument.class).find(session,
                                        new BsonDocument("_id", in(uuids(ids).getValues()))
                                                .append("state", new BsonString(ACTIVE)))
                                .forEach(table -> active.add(table.get("_id")));
                        for (BsonDocument doc : chunk) {
                            if (active.contains(doc.get("objectId"))) {
                                waiting.add(request(doc));
                            }
                        }
                        chunk.clear();
                    }
                }
            }
            return page(waiting, page, MaintenanceRequest::token);
        });
    }

    WriteResult start(UUID objectId, MaintenanceRequest.Job job, Instant requestedAt, Instant startedAt) {
        return transactions.writeWithoutId("maintenance request start", tx -> tx.collection(MAINTENANCE_REQUEST)
                .updateOne(tx.session, key(objectId, job).append("requestedAt", time(requestedAt))
                        .append("startedAt", BsonNull.VALUE), new BsonDocument("$set",
                        new BsonDocument("startedAt", time(startedAt)))).getModifiedCount() == 0
                ? WriteResult.UNCHANGED : WriteResult.APPLIED, WriteResult.APPLIED::equals);
    }

    private static BsonDocument key(UUID objectId, MaintenanceRequest.Job job) {
        return new BsonDocument("objectId", uuid(objectId)).append("job", new BsonString(job.name()));
    }

    private MongoCollection<BsonDocument> collection() {
        return transactions.database().getCollection(MAINTENANCE_REQUEST, BsonDocument.class);
    }

    private static MaintenanceRequest request(BsonDocument doc) {
        return new MaintenanceRequest(uuid(doc.get("objectId")), MaintenanceRequest.Job.valueOf(string(doc, "job")),
                string(doc, "requestedBy"), instant(doc.get("requestedAt")), optionalInstant(doc, "startedAt"));
    }
}
