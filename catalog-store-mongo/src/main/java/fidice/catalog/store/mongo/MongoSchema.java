package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static fidice.catalog.store.mongo.MongoCatalogStore.DURABILITY_BARRIER;
import static fidice.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;
import static fidice.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static fidice.catalog.store.mongo.MongoCatalogStore.OUTBOX;
import static fidice.catalog.store.mongo.MongoCatalogStore.POINTER;

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

    static final List<Step> STEPS = List.of(
            new Step(1, "catalogs", database -> index(database, CATALOG, "catalog_name", true, "nameNorm")),
            new Step(2, "commit core", database -> {
                index(database, POINTER, "pointer_key", true, "objectId", "branch");
                index(database, JOURNAL, "journal_commit", true, "commitId", "objectId", "branch");
                index(database, JOURNAL, "journal_order", false, "objectId", "branch", "newVersion");
                index(database, OUTBOX, "outbox_commit", true, "commitId", "position");
                index(database, IDEMPOTENCY, "idempotency_key", true, "key");
                // findCommit increments this with majority write concern before it answers (5.3).
                database.getCollection(DURABILITY_BARRIER, BsonDocument.class).updateOne(
                        new BsonDocument("_id", new BsonString("barrier")),
                        new BsonDocument("$setOnInsert", new BsonDocument("counter", new BsonInt64(0))),
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

    private static void index(MongoDatabase database, String collection, String name, boolean unique, String... fields) {
        BsonDocument keys = new BsonDocument();
        for (String field : fields) {
            keys.append(field, new BsonInt32(1));
        }
        database.getCollection(collection, BsonDocument.class).createIndex(keys, new IndexOptions().name(name).unique(unique));
    }
}
