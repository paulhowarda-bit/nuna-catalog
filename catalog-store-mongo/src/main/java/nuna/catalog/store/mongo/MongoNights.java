package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.int64;
import static nuna.catalog.store.mongo.Docs.optionalInstant;
import static nuna.catalog.store.mongo.Docs.string;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.MAINTENANCE_JOB;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * The jobs of the maintenance worker's nights, kept for the console (D45, backlog 126) in {@code maintenance_job}, by
 * the job's id, and each night's totals, summed here by an aggregation. A night is its date as {@code YYYY-MM-DD}
 * text, which sorts as the dates do. A report carries no change id and writes no audit record; it is safe to repeat.
 */
final class MongoNights {

    private static final BsonDocument BY_START = new BsonDocument("startedAt", new BsonInt32(1))
            .append("_id", new BsonInt32(1));

    private final Transactions transactions;

    MongoNights(Transactions transactions) {
        this.transactions = transactions;
    }

    void record(MaintenanceJob job, LocalDate keepFrom) {
        transactions.writeWithoutId("maintenance job record", tx -> {
            MongoCollection<BsonDocument> jobs = tx.collection(MAINTENANCE_JOB);
            Optional<MaintenanceJob> kept = tx.find(MAINTENANCE_JOB, byId(uuid(job.id()))).map(MongoNights::job);
            if (kept.isEmpty() || job.replaces(kept.get())) {
                BsonDocument set = new BsonDocument("worker", new BsonString(job.worker()))
                        .append("opens", time(job.opens()))
                        .append("closes", time(job.closes()))
                        .append("slot", job.slot().<BsonValue>map(BsonInt32::new).orElse(BsonNull.VALUE))
                        .append("startedAt", time(kept.map(MaintenanceJob::startedAt)
                                .filter(started -> started.isBefore(job.startedAt())).orElse(job.startedAt())))
                        .append("endedAt", job.endedAt().<BsonValue>map(Docs::time).orElse(BsonNull.VALUE))
                        .append("outcome", new BsonString(job.outcome().name()))
                        .append("filesIn", int64(job.filesIn()))
                        .append("filesOut", int64(job.filesOut()))
                        .append("snapshotsExpired", int64(job.snapshotsExpired()))
                        .append("filesDeleted", int64(job.filesDeleted()))
                        .append("bytesDeleted", int64(job.bytesDeleted()));
                // The night, kind and object stay as first reported.
                BsonDocument first = new BsonDocument("night", new BsonString(job.night().toString()))
                        .append("kind", new BsonString(job.kind().name()))
                        .append("objectId", uuid(job.objectId()));
                jobs.updateOne(tx.session, byId(uuid(job.id())), new BsonDocument("$set", set)
                        .append("$setOnInsert", first), new UpdateOptions().upsert(true));
            }
            jobs.deleteMany(tx.session, new BsonDocument("night", new BsonDocument("$lt",
                    new BsonString(keepFrom.toString()))));
            return true;
        }, written -> true);
    }

    List<MaintenanceNight> nights(LocalDate through, int limit) {
        BsonDocument group = new BsonDocument("_id", new BsonString("$night"))
                .append("opens", new BsonDocument("$min", new BsonString("$opens")))
                .append("closes", new BsonDocument("$max", new BsonString("$closes")))
                .append("tablesCompacted", count(new BsonDocument("$and", new BsonArray(List.of(
                        equal("$kind", "COMPACTION"), equal("$outcome", "SUCCEEDED"))))))
                .append("filesCompacted", sum(new BsonDocument("$cond", new BsonArray(List.of(
                        equal("$kind", "COMPACTION"), new BsonString("$filesIn"), int64(0))))))
                .append("filesWritten", sum(new BsonDocument("$cond", new BsonArray(List.of(
                        equal("$kind", "COMPACTION"), new BsonString("$filesOut"), int64(0))))))
                .append("snapshotsExpired", sum(new BsonString("$snapshotsExpired")))
                .append("filesDeleted", sum(new BsonString("$filesDeleted")))
                .append("bytesDeleted", sum(new BsonString("$bytesDeleted")))
                .append("running", count(equal("$outcome", "RUNNING")))
                .append("succeeded", count(equal("$outcome", "SUCCEEDED")))
                .append("cutOff", count(equal("$outcome", "CUT_OFF")))
                .append("failed", count(equal("$outcome", "FAILED")));
        List<BsonDocument> pipeline = List.of(
                new BsonDocument("$match", new BsonDocument("night", new BsonDocument("$lte",
                        new BsonString(through.toString())))),
                new BsonDocument("$group", group),
                new BsonDocument("$sort", new BsonDocument("_id", new BsonInt32(-1))),
                new BsonDocument("$limit", new BsonInt32(limit)));
        return transactions.read("maintenance nights", () -> collection().aggregate(pipeline)
                .map(MongoNights::night).into(new ArrayList<>()));
    }

    List<MaintenanceJob> jobs(LocalDate night, Set<MaintenanceJob.Kind> kinds) {
        BsonDocument filter = new BsonDocument("night", new BsonString(night.toString())).append("kind",
                in(kinds.stream().map(kind -> new BsonString(kind.name())).toList()));
        return transactions.read("maintenance jobs", () -> collection().find(filter).sort(BY_START)
                .map(MongoNights::job).into(new ArrayList<>()));
    }

    private MongoCollection<BsonDocument> collection() {
        return transactions.database().getCollection(MAINTENANCE_JOB, BsonDocument.class);
    }

    private static BsonDocument equal(String field, String value) {
        return new BsonDocument("$eq", new BsonArray(List.of(new BsonString(field), new BsonString(value))));
    }

    /** How many of the night's jobs meet {@code condition}. */
    private static BsonDocument count(BsonDocument condition) {
        return sum(new BsonDocument("$cond", new BsonArray(List.of(condition, int64(1), int64(0)))));
    }

    private static BsonDocument sum(BsonValue value) {
        return new BsonDocument("$sum", value);
    }

    private static MaintenanceNight night(BsonDocument doc) {
        return new MaintenanceNight(LocalDate.parse(doc.getString("_id").getValue()), instant(doc.get("opens")),
                instant(doc.get("closes")), number(doc, "tablesCompacted"), number(doc, "filesCompacted"),
                number(doc, "filesWritten"), number(doc, "snapshotsExpired"), number(doc, "filesDeleted"),
                number(doc, "bytesDeleted"), number(doc, "running"), number(doc, "succeeded"), number(doc, "cutOff"),
                number(doc, "failed"));
    }

    private static long number(BsonDocument doc, String field) {
        return doc.get(field).asNumber().longValue();
    }

    private static MaintenanceJob job(BsonDocument doc) {
        BsonValue slot = doc.get("slot");
        return new MaintenanceJob(uuid(doc.get("_id")), string(doc, "worker"), LocalDate.parse(string(doc, "night")),
                instant(doc.get("opens")), instant(doc.get("closes")), MaintenanceJob.Kind.valueOf(string(doc, "kind")),
                uuid(doc.get("objectId")), slot == null || slot.isNull() ? Optional.empty()
                        : Optional.of(slot.asInt32().getValue()), instant(doc.get("startedAt")),
                optionalInstant(doc, "endedAt"), MaintenanceJob.Outcome.valueOf(string(doc, "outcome")),
                number(doc, "filesIn"), number(doc, "filesOut"), number(doc, "snapshotsExpired"),
                number(doc, "filesDeleted"), number(doc, "bytesDeleted"));
    }
}
