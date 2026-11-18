package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.optionalInstant;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.string;
import static nuna.catalog.store.mongo.Docs.text;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.MAINTENANCE_SCAN;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.TABLE_HEALTH;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * What the maintenance worker reports of its own work, kept for the console (D38): each table's latest assessment and
 * last compaction in {@code table_health}, by the table's id, and the recent scans in {@code maintenance_scan}. The
 * reports carry no change id and write no audit record; each is safe to repeat. Recording marks the table, so a drop
 * at the same moment conflicts with it and one of them retries; a purge's completion deletes its health. The neediest
 * are read in order and their tables checked a chunk at a time, in one snapshot.
 */
final class MongoHealth {

    private static final int CHUNK = MongoObjects.PURGE_CHUNK;
    private static final BsonDocument NEEDIEST = new BsonDocument("urgency", new BsonInt32(1))
            .append("score", new BsonInt32(-1)).append("_id", new BsonInt32(1));
    private static final BsonDocument LAST_STARTED_FIRST = new BsonDocument("startedAt", new BsonInt32(-1))
            .append("_id", new BsonInt32(-1));
    private static final String ACTIVE = CatalogObject.State.ACTIVE.name();
    private static final String SUCCEEDED = MaintenanceScan.Outcome.SUCCEEDED.name();

    private final Transactions transactions;

    MongoHealth(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult recordAssessment(UUID objectId, TableAssessment assessment) {
        return transactions.writeWithoutId("assessment record", tx -> {
            Optional<BsonDocument> table = activeTable(tx, objectId);
            if (table.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Optional<Instant> kept = tx.find(TABLE_HEALTH, byId(uuid(objectId)))
                    .flatMap(health -> optionalInstant(health, "assessedAt"));
            if (kept.isPresent() && kept.get().isAfter(assessment.assessedAt())) {
                return WriteResult.UNCHANGED;
            }
            tx.see(table.get());
            tx.mark(OBJECT, table.get());
            tx.collection(TABLE_HEALTH).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("assessedAt", time(assessment.assessedAt()))
                            .append("dataFiles", new BsonInt64(assessment.dataFiles()))
                            .append("smallFiles", new BsonInt64(assessment.smallFiles()))
                            .append("positionDeleteFiles", new BsonInt64(assessment.positionDeleteFiles()))
                            .append("deletionVectors", new BsonInt64(assessment.deletionVectors()))
                            .append("equalityDeleteFiles", new BsonInt64(assessment.equalityDeleteFiles()))
                            .append("reasons", new BsonArray(assessment.reasons().stream().sorted()
                                    .<BsonValue>map(BsonString::new).toList()))
                            .append("score", new BsonDouble(assessment.score()))
                            .append("needed", BsonBoolean.valueOf(assessment.needsCompaction()))
                            .append("urgency", new BsonInt32(TableHealth.Rank.urgency(assessment)))),
                    new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult recordCompaction(UUID objectId, CompactionRun compaction) {
        return transactions.writeWithoutId("compaction record", tx -> {
            Optional<BsonDocument> table = activeTable(tx, objectId);
            if (table.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            // A later compaction replaces this one; the same one replaces it only while it was running.
            Optional<BsonDocument> health = tx.find(TABLE_HEALTH, byId(uuid(objectId)));
            Optional<Instant> started = health.flatMap(kept -> optionalInstant(kept, "compactionStartedAt"));
            if (started.isPresent() && (started.get().isAfter(compaction.startedAt())
                    || (started.get().equals(compaction.startedAt())
                    && optionalInstant(health.get(), "compactionEndedAt").isPresent()))) {
                return WriteResult.UNCHANGED;
            }
            tx.see(table.get());
            tx.mark(OBJECT, table.get());
            tx.collection(TABLE_HEALTH).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("compactionStartedAt", time(compaction.startedAt()))
                            .append("compactionEndedAt", compaction.endedAt().<BsonValue>map(Docs::time)
                                    .orElse(BsonNull.VALUE))
                            .append("compactionOutcome", new BsonString(compaction.outcome().name()))),
                    new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Optional<TableHealth> read(UUID objectId) {
        return transactions.snapshot("table health read", session -> {
            BsonDocument health = collection(TABLE_HEALTH).find(session, byId(uuid(objectId))).first();
            if (health == null) {
                return Optional.empty();
            }
            BsonDocument table = collection(OBJECT).find(session, byId(uuid(objectId))).first();
            return Optional.ofNullable(table).map(found -> health(found, health));
        });
    }

    Page<TableHealth> listNeediest(PageRequest page) {
        Optional<TableHealth.Rank> after = Docs.after(page, 3, TableHealth.Rank::parse);
        return transactions.snapshot("neediest tables", session -> {
            BsonDocument filter = new BsonDocument("needed", BsonBoolean.TRUE);
            after.ifPresent(rank -> {
                BsonInt32 urgency = new BsonInt32(rank.urgency());
                BsonDouble score = new BsonDouble(rank.score());
                filter.append("$or", new BsonArray(List.of(
                        new BsonDocument("urgency", new BsonDocument("$gt", urgency)),
                        new BsonDocument("urgency", urgency).append("score", new BsonDocument("$lt", score)),
                        new BsonDocument("urgency", urgency).append("score", score)
                                .append("_id", new BsonDocument("$gt", uuid(rank.objectId()))))));
            });
            List<TableHealth> tables = new ArrayList<>();
            List<BsonDocument> chunk = new ArrayList<>();
            try (MongoCursor<BsonDocument> health = collection(TABLE_HEALTH).find(session, filter).sort(NEEDIEST)
                    .batchSize(CHUNK).iterator()) {
                while (tables.size() <= page.size() && health.hasNext()) {
                    chunk.add(health.next());
                    if (chunk.size() == CHUNK || !health.hasNext()) {
                        Map<BsonValue, BsonDocument> active = new HashMap<>();
                        collection(OBJECT).find(session, new BsonDocument("_id",
                                        in(chunk.stream().map(doc -> doc.get("_id")).toList()))
                                        .append("state", new BsonString(ACTIVE)))
                                .forEach(table -> active.put(table.get("_id"), table));
                        for (BsonDocument doc : chunk) {
                            BsonDocument table = active.get(doc.get("_id"));
                            if (table != null) {
                                tables.add(health(table, doc));
                            }
                        }
                        chunk.clear();
                    }
                }
            }
            return page(tables, page, table -> table.rank().token());
        });
    }

    void recordScan(MaintenanceScan scan, int keep) {
        transactions.writeWithoutId("scan record", tx -> {
            MongoCollection<BsonDocument> scans = tx.collection(MAINTENANCE_SCAN);
            Optional<BsonDocument> kept = tx.find(MAINTENANCE_SCAN, byId(uuid(scan.id())));
            if (kept.isEmpty() || optionalInstant(kept.get(), "endedAt").isEmpty()) {
                BsonDocument failures = new BsonDocument();
                scan.failures().forEach((kind, count) -> failures.append(kind, new BsonInt32(count)));
                scans.updateOne(tx.session, byId(uuid(scan.id())), new BsonDocument("$set",
                        new BsonDocument("worker", new BsonString(scan.worker()))
                                .append("startedAt", time(scan.startedAt()))
                                .append("endedAt", scan.endedAt().<BsonValue>map(Docs::time).orElse(BsonNull.VALUE))
                                .append("outcome", text(scan.outcome().map(Enum::name).orElse(null)))
                                .append("failures", failures)
                                .append("error", text(scan.error().orElse(null)))),
                        new UpdateOptions().upsert(true));
            }
            List<BsonValue> keeping = new ArrayList<>();
            scans.find(tx.session).sort(LAST_STARTED_FIRST).limit(keep)
                    .projection(new BsonDocument("_id", new BsonInt32(1))).forEach(doc -> keeping.add(doc.get("_id")));
            BsonDocument lastSuccess = scans.find(tx.session, new BsonDocument("outcome", new BsonString(SUCCEEDED)))
                    .sort(LAST_STARTED_FIRST).projection(new BsonDocument("_id", new BsonInt32(1))).first();
            if (lastSuccess != null) {
                keeping.add(lastSuccess.get("_id"));
            }
            scans.deleteMany(tx.session, new BsonDocument("_id", new BsonDocument("$nin", new BsonArray(keeping))));
            return true;
        }, written -> true);
    }

    List<MaintenanceScan> listScans() {
        return transactions.read("scan listing", () -> collection(MAINTENANCE_SCAN).find().sort(LAST_STARTED_FIRST)
                .map(MongoHealth::scan).into(new ArrayList<>()));
    }

    /** The object, if it is an active table, as this transaction's snapshot shows it. */
    private static Optional<BsonDocument> activeTable(Tx tx, UUID objectId) {
        return tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(ACTIVE))
                .append("kind", new BsonString(CatalogObject.Kind.TABLE.name())));
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return transactions.database().getCollection(name, BsonDocument.class);
    }

    private static TableHealth health(BsonDocument table, BsonDocument doc) {
        Optional<TableAssessment> assessment = optionalInstant(doc, "assessedAt").map(assessed -> new TableAssessment(
                assessed, doc.getInt64("dataFiles").getValue(), doc.getInt64("smallFiles").getValue(),
                doc.getInt64("positionDeleteFiles").getValue(), doc.getInt64("deletionVectors").getValue(),
                doc.getInt64("equalityDeleteFiles").getValue(), reasons(doc), doc.getDouble("score").getValue()));
        Optional<CompactionRun> compaction = optionalInstant(doc, "compactionStartedAt").map(started ->
                new CompactionRun(started, optionalInstant(doc, "compactionEndedAt"),
                        CompactionRun.Outcome.valueOf(string(doc, "compactionOutcome"))));
        return new TableHealth(MongoObjects.object(table), assessment, compaction);
    }

    private static MaintenanceScan scan(BsonDocument doc) {
        Map<String, Integer> failures = new LinkedHashMap<>();
        doc.getDocument("failures").forEach((kind, count) -> failures.put(kind, count.asInt32().getValue()));
        return new MaintenanceScan(uuid(doc.get("_id")), string(doc, "worker"), instant(doc.get("startedAt")),
                optionalInstant(doc, "endedAt"),
                Optional.ofNullable(string(doc, "outcome")).map(MaintenanceScan.Outcome::valueOf), failures,
                Optional.ofNullable(string(doc, "error")));
    }

    private static Set<String> reasons(BsonDocument doc) {
        return doc.getArray("reasons").stream().map(reason -> reason.asString().getValue()).collect(Collectors.toSet());
    }
}
