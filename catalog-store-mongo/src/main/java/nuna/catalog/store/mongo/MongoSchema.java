package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.DURABILITY_BARRIER;
import static nuna.catalog.store.mongo.MongoCatalogStore.ENGINE;
import static nuna.catalog.store.mongo.MongoCatalogStore.GRANT;
import static nuna.catalog.store.mongo.MongoCatalogStore.HOLD;
import static nuna.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;
import static nuna.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static nuna.catalog.store.mongo.MongoCatalogStore.DR_WATERMARK;
import static nuna.catalog.store.mongo.MongoCatalogStore.MAINTENANCE_SCAN;
import static nuna.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.OUTBOX;
import static nuna.catalog.store.mongo.MongoCatalogStore.OUTBOX_RELAY;
import static nuna.catalog.store.mongo.MongoCatalogStore.PENDING_DELETION;
import static nuna.catalog.store.mongo.MongoCatalogStore.POINTER;
import static nuna.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF_ENTRY;
import static nuna.catalog.store.mongo.MongoCatalogStore.TABLE_HEALTH;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import java.util.List;
import java.util.function.Consumer;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
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
    private static final BsonDocument PURGE_QUEUE = new BsonDocument("state", new BsonDocument("$in",
            new BsonArray(List.of(new BsonString("DROPPED"), new BsonString("PURGING")))));
    /** The server's code for dropping an index that does not exist. */
    private static final int INDEX_NOT_FOUND = 27;
    private static final int NAMESPACE_EXISTS = 48;

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
            }),
            // v1.x (D13): objects gain the final state UNREGISTERED. A state is a plain string, and
            // no index or partial filter names it, so nothing is built; the step is recorded so the
            // schema version marks the first release that writes it.
            new Step(4, "unregistered objects", database -> {
            }),
            // v1.x (D30): a purge is claimed first (PURGING, with purgeClaimant), and the purge scan
            // reads dropped and claimed objects, so its index covers both. pending_deletion holds the
            // files the maintenance worker deletes once the PITR window has passed (notBefore).
            new Step(5, "purge claims and pending deletions", database -> {
                index(database, OBJECT, "object_purge_queue", false, PURGE_QUEUE, "purgeAfter", "_id");
                try {
                    database.getCollection(OBJECT, BsonDocument.class).dropIndex("object_purge");
                } catch (MongoCommandException e) {
                    if (e.getErrorCode() != INDEX_NOT_FOUND) {
                        throw e;
                    }
                }
                index(database, PENDING_DELETION, "pending_deletion_due", false, null, "notBefore", "_id");
                index(database, PENDING_DELETION, "pending_deletion_object", false, null, "objectId");
            }),
            // A file is pending deletion once per object: every orphan scan finds the same unreferenced files
            // again until they are deleted, and recording skips a location already pending.
            new Step(6, "pending deletions once per file", database -> {
                try {
                    database.getCollection(PENDING_DELETION, BsonDocument.class).dropIndex("pending_deletion_object");
                } catch (MongoCommandException e) {
                    if (e.getErrorCode() != INDEX_NOT_FOUND) {
                        throw e;
                    }
                }
                index(database, PENDING_DELETION, "pending_deletion_object", true, null, "objectId", "location");
            }),
            // v1.x (D38): what the maintenance worker reports of its own work, for the console's health dashboard.
            // table_health holds each table's latest assessment and last compaction, by the table's id; the neediest
            // are read by urgency (equality deletes first), then the highest score. maintenance_scan holds the
            // recent scans. Both collections are new and no server of the previous release touches them, so
            // building their indexes locks nothing it uses.
            new Step(7, "table health and maintenance scans", database -> {
                database.getCollection(TABLE_HEALTH, BsonDocument.class).createIndex(
                        new BsonDocument("urgency", new BsonInt32(1)).append("score", new BsonInt32(-1))
                                .append("_id", new BsonInt32(1)),
                        new IndexOptions().name("table_health_neediest")
                                .partialFilterExpression(new BsonDocument("needed", BsonBoolean.TRUE)));
                index(database, MAINTENANCE_SCAN, "maintenance_scan_started", false, null, "startedAt", "_id");
            }),
            // v1.x (D37, amended 2026-11-20): each table's and view's DR watermark, by the object's id. The
            // collection is made here, outside any transaction, since a transaction's first write to a collection
            // that does not exist fails on some deployments; no server of the previous release touches it.
            new Step(8, "DR watermarks", database -> {
                try {
                    database.createCollection(DR_WATERMARK);
                } catch (MongoCommandException e) {
                    if (e.getErrorCode() != NAMESPACE_EXISTS) {
                        throw e;
                    }
                }
            }),
            // D26 (amended 2026-11-20, backlog 98): a storage profile records its KMS key, kmsKey, null when it names
            // none. A profile stored before this step has no kmsKey and reads as naming none until a server of this
            // release records it; servers of the previous release $set the fields they know and leave kmsKey as it
            // is. No index names it, so nothing is built; the step is recorded so the schema version marks the first
            // release that writes it.
            new Step(9, "storage profile KMS keys", database -> {
            }),
            // v1.x (D33, backlog 102): a catalog's branches and tags. ref holds each ref by its id, with the catalog's
            // unique (catalog, normalized name) key the SQL stores have had since V3; ref_entry holds each ref's cut:
            // every namespace, and every active table and view with its main pointer, as of the ref's point. A cut is
            // written in batches before its ref, so an entry counts only while its ref exists. Both collections are
            // new and no server of the previous release touches them, so building their indexes locks nothing it uses.
            new Step(10, "refs and their cuts", database -> {
                index(database, REF, "ref_name", true, null, "catalogId", "nameNorm");
                index(database, REF_ENTRY, "ref_entry_key", true, null, "refId", "objectId");
                index(database, REF_ENTRY, "ref_entry_listing", false, null, "refId", "parentId", "kind", "nameNorm",
                        "objectId");
                index(database, REF_ENTRY, "ref_entry_object", false, null, "objectId");
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
