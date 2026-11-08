package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static fidice.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static fidice.catalog.store.mongo.MongoCatalogStore.DURABILITY_BARRIER;
import static fidice.catalog.store.mongo.MongoCatalogStore.ENGINE;
import static fidice.catalog.store.mongo.MongoCatalogStore.GRANT;
import static fidice.catalog.store.mongo.MongoCatalogStore.HOLD;
import static fidice.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;
import static fidice.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static fidice.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static fidice.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static fidice.catalog.store.mongo.MongoCatalogStore.OUTBOX;
import static fidice.catalog.store.mongo.MongoCatalogStore.OUTBOX_RELAY;
import static fidice.catalog.store.mongo.MongoCatalogStore.POINTER;
import static fidice.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import java.util.List;
import java.util.function.Consumer;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;

/**
 * The MongoDB schema as numbered steps (build-plan.md P6). The {@code schema_version} document
 * records the last step applied and when each step ran. Every step is idempotent, so servers
 * that migrate at the same moment cannot break it; the first to finish a step records it.
 */
final class MongoSchema {

    static final String SCHEMA_VERSION = "schema_version";

    record Step(int version, String description, Consumer<MongoDatabase> apply) {
    }

    private static final BsonDocument ACTIVE = new BsonDocument("state", new BsonString("ACTIVE"));
    private static final BsonDocument DROPPED = new BsonDocument("state", new BsonString("DROPPED"));

    static final List<Step> STEPS = List.of(
            new Step(1, "catalogs", database -> index(database, CATALOG, "catalog_name", true, null, "nameNorm")),
            new Step(2, "commit core", database -> {
                index(database, POINTER, "pointer_key", true, null, "objectId", "branch");
                index(database, JOURNAL, "journal_commit", true, null, "commitId", "objectId", "branch");
                index(database, JOURNAL, "journal_order", false, null, "objectId", "branch", "newVersion");
                index(database, OUTBOX, "outbox_commit", true, null, "commitId", "position");
                index(database, IDEMPOTENCY, "idempotency_key", true, null, "key");
                // findCommit increments this with majority write concern before it answers (5.3).
                database.getCollection(DURABILITY_BARRIER, BsonDocument.class).updateOne(
                        new BsonDocument("_id", new BsonString("barrier")),
                        new BsonDocument("$setOnInsert", new BsonDocument("counter", new BsonInt64(0))),
                        new UpdateOptions().upsert(true));
            }),
            // No index on pointer or journal here: an index build locks its collection exclusively
            // at its start and end, and shared while it drains, and a transaction that waits for a
            // lock longer than maxTransactionLockRequestTimeoutMillis (5 ms) aborts, so every
            // commit of the previous release, still running during the upgrade, would risk failing. Only those that write outbox events or an idempotency result touch a
            // collection indexed here; they may fail cleanly, and can be repeated, while the
            // build takes its lock. No index names a collation, so names compare as binary
            // strings, in code point order.
            new Step(3, "whole contract", database -> {
                index(database, NAMESPACE, "namespace_name", true, null, "parentId", "nameNorm");
                index(database, OBJECT, "object_active_name", true, ACTIVE, "namespaceId", "nameNorm");
                index(database, OBJECT, "object_listing", false, null, "namespaceId", "kind", "state", "nameNorm", "_id");
                index(database, OBJECT, "object_purge", false, DROPPED, "purgeAfter", "_id");
                index(database, PROJECTION_PIECE, "projection_piece_key", true, null, "objectId", "branch", "version", "piece");
                index(database, GRANT, "grant_key", true, null, "securableId", "group", "privilege");
                index(database, GRANT, "grant_group", false, null, "catalogId", "group", "securableId", "privilege");
                index(database, ATTRIBUTE, "attribute_key", true, null, "targetId", "kind");
                index(database, HOLD, "hold_target", false, null, "targetId");
                index(database, ENGINE, "engine_key", true, null, "issuer", "clientId");
                index(database, IDEMPOTENCY, "idempotency_expiry", false, null, "expiresAt");
                index(database, OUTBOX, "outbox_order", false, null, "clock", "_id");
                database.getCollection(OUTBOX_RELAY, BsonDocument.class).updateOne(
                        new BsonDocument("_id", new BsonString(MongoOutbox.RELAY)),
                        new BsonDocument("$setOnInsert", new BsonDocument("relayId", BsonNull.VALUE)
                                .append("leaseUntil", BsonNull.VALUE)),
                        new UpdateOptions().upsert(true));
            }));

    private MongoSchema() {
    }

    /** Applies every step of {@code steps} after the recorded version. {@code database} carries majority read and write concern. */
    static void migrate(MongoDatabase database, List<Step> steps) {
        var versions = database.getCollection(SCHEMA_VERSION, BsonDocument.class);
        BsonDocument schema = new BsonDocument("_id", new BsonString("schema"));
        BsonDocument recorded = versions.find(schema).first();
        int current = recorded == null ? 0 : recorded.getInt32("version").getValue();
        for (Step step : steps) {
            if (step.version() <= current) {
                continue;
            }
            step.apply().accept(database);
            BsonDocument applied = new BsonDocument("version", new BsonInt32(step.version()))
                    .append("description", new BsonString(step.description()))
                    .append("appliedAt", new BsonDateTime(System.currentTimeMillis()));
            try {
                versions.updateOne(schema.clone().append("version", new BsonDocument("$lt", new BsonInt32(step.version()))),
                        new BsonDocument("$set", new BsonDocument("version", new BsonInt32(step.version())))
                                .append("$push", new BsonDocument("applied", applied)),
                        new UpdateOptions().upsert(true));
            } catch (MongoWriteException e) {
                // The filter missed because another server recorded this step first; its upsert then collides.
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
            }
            current = step.version();
        }
    }

    /** An ascending index; {@code partial}, when not null, limits it to the documents that match. */
    private static void index(MongoDatabase database, String collection, String name, boolean unique, BsonDocument partial,
                              String... fields) {
        BsonDocument keys = new BsonDocument();
        for (String field : fields) {
            keys.append(field, new BsonInt32(1));
        }
        IndexOptions options = new IndexOptions().name(name).unique(unique);
        if (partial != null) {
            options.partialFilterExpression(partial);
        }
        database.getCollection(collection, BsonDocument.class).createIndex(keys, options);
    }
}
