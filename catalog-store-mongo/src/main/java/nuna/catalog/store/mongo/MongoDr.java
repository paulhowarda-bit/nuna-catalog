package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.int64;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.string;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.DR_WATERMARK;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.POINTER;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * The DR replication watermark (D37, amended 2026-11-20), one document per table or view in {@code dr_watermark}, by
 * the object's id. A recording carries no change id and writes no audit record; it is safe to repeat. It marks the
 * object, so a drop at the same moment conflicts with it and one of them retries; a purge's completion deletes it.
 * The objects behind are read in id order and their pointers and watermarks fetched a chunk at a time, in one
 * snapshot.
 */
final class MongoDr {

    private static final int CHUNK = MongoObjects.PURGE_CHUNK;
    private static final WriteResult BEHIND = new WriteResult.Refused(WriteResult.Reason.WATERMARK_BEHIND);
    private static final WriteResult AHEAD = new WriteResult.Refused(WriteResult.Reason.WATERMARK_AHEAD);
    private static final BsonString ACTIVE = new BsonString(CatalogObject.State.ACTIVE.name());
    private static final BsonDocument TABLES_AND_VIEWS = in(List.of(new BsonString(CatalogObject.Kind.TABLE.name()),
            new BsonString(CatalogObject.Kind.VIEW.name())));

    private final Transactions transactions;

    MongoDr(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, DrWatermark watermark) {
        return transactions.writeWithoutId("DR watermark record", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", ACTIVE)
                    .append("kind", TABLES_AND_VIEWS));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Optional<BsonDocument> pointer = tx.find(POINTER, Docs.keyFilter(new PointerKey(objectId, PointerKey.MAIN)));
            if (pointer.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (watermark.version() > pointer.get().getInt64("version").getValue()) {
                return AHEAD;
            }
            Optional<Long> kept = tx.find(DR_WATERMARK, byId(uuid(objectId)))
                    .map(doc -> doc.getInt64("version").getValue());
            if (kept.isPresent() && kept.get() >= watermark.version()) {
                return kept.get() == watermark.version() ? WriteResult.UNCHANGED : BEHIND;
            }
            tx.see(object.get());
            tx.mark(OBJECT, object.get());
            tx.collection(DR_WATERMARK).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("version", int64(watermark.version()))
                            .append("metadataLocation", new BsonString(watermark.metadataLocation()))
                            .append("recordedAt", time(watermark.recordedAt()))),
                    new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Optional<DrWatermark> read(UUID objectId) {
        return transactions.read("DR watermark read", () -> Optional.ofNullable(collection(DR_WATERMARK)
                .find(byId(uuid(objectId))).first()).map(MongoDr::watermark));
    }

    Page<DrState> listBehind(PageRequest page) {
        Optional<BsonValue> after = Docs.after(page, 1, parts -> uuid(UUID.fromString(parts.get(0))));
        return transactions.snapshot("DR watermarks behind", session -> {
            BsonDocument filter = new BsonDocument("state", ACTIVE).append("kind", TABLES_AND_VIEWS);
            after.ifPresent(id -> filter.append("_id", new BsonDocument("$gt", id)));
            List<DrState> behind = new ArrayList<>();
            List<BsonDocument> chunk = new ArrayList<>();
            try (MongoCursor<BsonDocument> objects = collection(OBJECT).find(session, filter)
                    .sort(Docs.ascending("_id")).batchSize(CHUNK).iterator()) {
                while (behind.size() <= page.size() && objects.hasNext()) {
                    chunk.add(objects.next());
                    if (chunk.size() == CHUNK || !objects.hasNext()) {
                        List<BsonValue> ids = chunk.stream().map(doc -> doc.get("_id")).toList();
                        Map<BsonValue, BsonDocument> pointers = new HashMap<>();
                        collection(POINTER).find(session, new BsonDocument("objectId", in(ids))
                                        .append("branch", new BsonString(PointerKey.MAIN)))
                                .forEach(pointer -> pointers.put(pointer.get("objectId"), pointer));
                        Map<BsonValue, BsonDocument> watermarks = new HashMap<>();
                        collection(DR_WATERMARK).find(session, new BsonDocument("_id", in(ids)))
                                .forEach(watermark -> watermarks.put(watermark.get("_id"), watermark));
                        for (BsonDocument object : chunk) {
                            BsonDocument pointer = pointers.get(object.get("_id"));
                            if (pointer == null || behind.size() > page.size()) {
                                continue;
                            }
                            DrState state = new DrState(MongoObjects.object(object),
                                    pointer.getInt64("version").getValue(), string(pointer, "metadataLocation"),
                                    Optional.ofNullable(watermarks.get(object.get("_id"))).map(MongoDr::watermark));
                            if (state.behind() > 0) {
                                behind.add(state);
                            }
                        }
                        chunk.clear();
                    }
                }
            }
            return page(behind, page, state -> PageToken.encode(state.object().id().toString()));
        });
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return transactions.database().getCollection(name, BsonDocument.class);
    }

    private static DrWatermark watermark(BsonDocument doc) {
        return new DrWatermark(doc.getInt64("version").getValue(), string(doc, "metadataLocation"),
                instant(doc.get("recordedAt")));
    }
}
