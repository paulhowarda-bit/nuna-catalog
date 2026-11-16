package keel.catalog.store.mongo;

import static keel.catalog.store.mongo.Docs.after;
import static keel.catalog.store.mongo.Docs.ascending;
import static keel.catalog.store.mongo.Docs.byId;
import static keel.catalog.store.mongo.Docs.in;
import static keel.catalog.store.mongo.Docs.instant;
import static keel.catalog.store.mongo.Docs.optionalInstant;
import static keel.catalog.store.mongo.Docs.page;
import static keel.catalog.store.mongo.Docs.time;
import static keel.catalog.store.mongo.Docs.uuid;
import static keel.catalog.store.mongo.Docs.uuidList;
import static keel.catalog.store.mongo.Docs.uuids;
import static keel.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static keel.catalog.store.mongo.MongoCatalogStore.GRANT;
import static keel.catalog.store.mongo.MongoCatalogStore.HOLD;
import static keel.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static keel.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static keel.catalog.store.mongo.MongoCatalogStore.PENDING_DELETION;
import static keel.catalog.store.mongo.MongoCatalogStore.POINTER;
import static keel.catalog.store.mongo.MongoCatalogStore.TABLE_HEALTH;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.Change;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitResult;
import keel.catalog.store.Name;
import keel.catalog.store.ObjectCreation;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.StoreException;
import keel.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Tables and views. An object document keeps the ids of every namespace above it, so the hold
 * check is one query. Taking a name in a namespace (create, rename, undrop) marks the
 * namespace, so a concurrent drop of the namespace, or a concurrent writer of the same name,
 * conflicts; the partial unique index on active names is the backstop.
 */
final class MongoObjects {

    /** Dropped objects whose holds are checked at once when listing purge candidates. */
    static final int PURGE_CHUNK = 100;
    private static final String ACTIVE = CatalogObject.State.ACTIVE.name();
    private static final String DROPPED = CatalogObject.State.DROPPED.name();
    private static final String PURGING = CatalogObject.State.PURGING.name();

    private final Transactions transactions;

    MongoObjects(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult create(ObjectCreation creation) {
        CatalogObject object = creation.object();
        CommitBatch first = creation.firstCommit();
        return transactions.write(first.commitId(), "object create", tx -> {
            Optional<BsonDocument> namespace = namespace(tx, object.namespaceId(), object.catalogId());
            if (namespace.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            Optional<WriteResult> taken = nameTaken(tx, object.namespaceId(), object.name(), Optional.empty());
            if (taken.isPresent()) {
                return taken.get();
            }
            tx.see(namespace.get());
            Pointers.see(tx, first);
            tx.mark(NAMESPACE, namespace.get());
            tx.collection(OBJECT).insertOne(tx.session, byId(uuid(object.id()))
                    .append("catalogId", uuid(object.catalogId()))
                    .append("namespaceId", uuid(object.namespaceId()))
                    .append("namespacePath", uuids(path(namespace.get())))
                    .append("nameNorm", new BsonString(object.name().normalized()))
                    .append("name", new BsonString(object.name().display()))
                    .append("kind", new BsonString(object.kind().name()))
                    .append("state", new BsonString(ACTIVE))
                    .append("droppedAt", BsonNull.VALUE)
                    .append("purgeAfter", BsonNull.VALUE)
                    .append("locationRoot", new BsonString(object.locationRoot()))
                    .append("purgeClaimant", BsonNull.VALUE)
                    .append("clock", tx.clock()));
            if (Pointers.swap(tx, first, transactions.pieceBytes()).isPresent()) {
                throw new StoreException("object " + object.id() + " already has a pointer", null);
            }
            Pointers.records(tx, first, transactions.entryIds());
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Optional<CatalogObject> find(UUID namespaceId, String normalizedName) {
        return transactions.read("object lookup", () -> Optional.ofNullable(objects().find(
                new BsonDocument("namespaceId", uuid(namespaceId)).append("nameNorm", new BsonString(normalizedName))
                        .append("state", new BsonString(ACTIVE))).first()).map(MongoObjects::object));
    }

    Optional<CatalogObject> read(UUID objectId) {
        return transactions.read("object read", () -> Optional.ofNullable(objects().find(byId(uuid(objectId))).first())
                .map(MongoObjects::object));
    }

    Page<CatalogObject> list(UUID namespaceId, CatalogObject.Kind kind, CatalogObject.State state, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 2,
                parts -> List.of(new BsonString(parts.get(0)), uuid(UUID.fromString(parts.get(1)))));
        return transactions.read("object listing", () -> {
            BsonDocument filter = new BsonDocument("namespaceId", uuid(namespaceId))
                    .append("kind", new BsonString(kind.name())).append("state", new BsonString(state.name()));
            after.ifPresent(values -> filter.append("$and", new org.bson.BsonArray(List.of(
                    Docs.after(List.of("nameNorm", "_id"), values)))));
            List<CatalogObject> found = objects().find(filter).sort(ascending("nameNorm", "_id")).limit(page.size() + 1)
                    .map(MongoObjects::object).into(new ArrayList<>());
            return page(found, page, object -> PageToken.encode(object.name().normalized(), object.id().toString()));
        });
    }

    WriteResult rename(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object rename", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(ACTIVE)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(tx, object.get(), namespaceId, name, new BsonDocument(), new BsonDocument());
        });
    }

    WriteResult drop(UUID objectId, Instant purgeAfter, Change change) {
        return transactions.change(change, "object drop", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(ACTIVE)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (held(tx, object.get())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            tx.see(object.get());
            tx.collection(OBJECT).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("state", new BsonString(DROPPED)).append("droppedAt", time(change.at()))
                            .append("purgeAfter", time(purgeAfter)).append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    /**
     * Commits mark the object, so a commit that read the object active and this write conflict: one of
     * them retries, and a commit that retries finds the object unregistered. The holds, DR freezes and
     * regulated or row/column-policy flags are read in the same snapshot, as a drop reads holds.
     */
    WriteResult unregister(UUID objectId, Pointer expected, Change change) {
        return transactions.change(change, "object unregister", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(ACTIVE)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Optional<BsonDocument> main = tx.find(POINTER, Docs.keyFilter(new PointerKey(objectId, PointerKey.MAIN)));
            if (main.isEmpty() || main.get().getInt64("version").getValue() != expected.version()
                    || !main.get().getString("metadataLocation").getValue().equals(expected.metadataLocation())) {
                return new WriteResult.VersionMismatch(main.map(p -> p.getInt64("version").getValue()).orElse(0L));
            }
            if (held(tx, object.get())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(tx, objectId, uuid(object.get().get("catalogId")),
                    uuidList(object.get().get("namespacePath")))) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            if (Guards.flagged(tx, objectId, uuid(object.get().get("catalogId")),
                    uuidList(object.get().get("namespacePath")))) {
                return new WriteResult.Refused(WriteResult.Reason.FLAGGED);
            }
            tx.see(object.get());
            tx.collection(OBJECT).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("state", new BsonString(CatalogObject.State.UNREGISTERED.name()))
                            .append("droppedAt", time(change.at())).append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    /** Puts the object at the name as undrop does, and writes the batch, in the batch's transaction. */
    WriteResult revive(UUID objectId, CatalogObject.State from, UUID namespaceId, Name name, CommitBatch batch) {
        return transactions.write(batch.commitId(), "object revive", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(from.name())));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Pointers.see(tx, batch);
            WriteResult placed = place(tx, object.get(), namespaceId, name,
                    new BsonDocument("state", new BsonString(ACTIVE)).append("droppedAt", BsonNull.VALUE)
                            .append("purgeAfter", BsonNull.VALUE), new BsonDocument());
            if (!placed.equals(WriteResult.APPLIED)) {
                return placed;
            }
            Optional<CommitResult.Conflict> conflict = Pointers.swap(tx, batch, transactions.pieceBytes());
            if (conflict.isPresent()) {
                return new WriteResult.VersionMismatch(conflict.get().actual().map(Pointer::version).orElse(0L));
            }
            Pointers.records(tx, batch, transactions.entryIds());
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    WriteResult undrop(UUID objectId, UUID namespaceId, Name name, Change change) {
        return transactions.change(change, "object undrop", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(DROPPED)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            return place(tx, object.get(), namespaceId, name,
                    new BsonDocument("state", new BsonString(ACTIVE)).append("droppedAt", BsonNull.VALUE)
                            .append("purgeAfter", BsonNull.VALUE), new BsonDocument());
        });
    }

    /**
     * Objects dropped long enough ago, in purge order, without those under a legal hold or a DR
     * freeze, and every claimed object, whatever came after its claim. The dropped and claimed
     * objects are read in order and their holds and freezes checked a chunk at a time, all in one
     * snapshot.
     */
    Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 2,
                parts -> List.of(time(Instant.parse(parts.get(0))), uuid(UUID.fromString(parts.get(1)))));
        return transactions.snapshot("purge scan", session -> {
            BsonDocument filter = new BsonDocument("state", in(List.of(new BsonString(DROPPED), new BsonString(PURGING))))
                    .append("purgeAfter", new BsonDocument("$lte", time(now)));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    Docs.after(List.of("purgeAfter", "_id"), values)))));
            List<CatalogObject> eligible = new ArrayList<>();
            List<BsonDocument> chunk = new ArrayList<>();
            try (MongoCursor<BsonDocument> dropped = objects().find(session, filter).sort(ascending("purgeAfter", "_id"))
                    .batchSize(PURGE_CHUNK).iterator()) {
                while (eligible.size() <= page.size() && dropped.hasNext()) {
                    chunk.add(dropped.next());
                    if (chunk.size() == PURGE_CHUNK || !dropped.hasNext()) {
                        Set<BsonValue> covered = covered(transactions, session, chunk);
                        for (BsonDocument object : chunk) {
                            if (object.getString("state").getValue().equals(PURGING)
                                    || holdTargets(object).stream().noneMatch(covered::contains)) {
                                eligible.add(object(object));
                            }
                        }
                        chunk.clear();
                    }
                }
            }
            return page(eligible, page,
                    object -> PageToken.encode(object.purgeAfter().orElseThrow().toString(), object.id().toString()));
        });
    }

    /**
     * The targets among those of {@code objects} (each object, the namespaces above it and its catalog) that
     * carry a legal hold or a DR freeze, as {@code session} reads them (D24, D30, D37).
     */
    static Set<BsonValue> covered(Transactions transactions, ClientSession session, List<BsonDocument> objects) {
        List<BsonValue> targets = new ArrayList<>();
        for (BsonDocument object : objects) {
            targets.addAll(holdTargets(object));
        }
        Set<BsonValue> covered = new HashSet<>();
        transactions.database().getCollection(HOLD, BsonDocument.class)
                .find(session, new BsonDocument("targetId", in(targets)))
                .projection(new BsonDocument("targetId", new BsonInt32(1)))
                .forEach(hold -> covered.add(hold.get("targetId")));
        transactions.database().getCollection(ATTRIBUTE, BsonDocument.class)
                .find(session, new BsonDocument("targetId", in(targets))
                        .append("kind", new BsonString(GovernanceAttribute.Kind.DR_FREEZE.name())))
                .projection(new BsonDocument("targetId", new BsonInt32(1)))
                .forEach(freeze -> covered.add(freeze.get("targetId")));
        return covered;
    }

    /**
     * Claims a dropped object's purge for {@code claimant}. The claim writes the object, so a hold or DR
     * freeze placed on the object at the same moment conflicts with it; holds and freezes above the
     * object are read in the same snapshot, as a drop reads holds.
     */
    WriteResult claimPurge(UUID objectId, String claimant, Change change) {
        return transactions.change(change, "purge claim", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId))
                    .append("state", in(List.of(new BsonString(DROPPED), new BsonString(PURGING)))));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Optional<String> holder = claimant(object.get());
            if (holder.isPresent()) {
                return holder.get().equals(claimant) ? WriteResult.UNCHANGED
                        : new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            if (instant(object.get().get("purgeAfter")).isAfter(change.at())) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_DUE);
            }
            if (held(tx, object.get())) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            if (Guards.frozen(tx, objectId, uuid(object.get().get("catalogId")),
                    uuidList(object.get().get("namespacePath")))) {
                return new WriteResult.Refused(WriteResult.Reason.FROZEN);
            }
            tx.see(object.get());
            tx.collection(OBJECT).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("state", new BsonString(PURGING)).append("purgeClaimant", new BsonString(claimant))
                            .append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    /** Marks a claimed object purged and deletes its grants, its pending deletions and its health. */
    WriteResult completePurge(UUID objectId, String claimant, Change change) {
        return transactions.change(change, "purge complete", tx -> {
            Optional<BsonDocument> object = tx.find(OBJECT, byId(uuid(objectId)).append("state", new BsonString(PURGING)));
            if (object.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (!claimant(object.get()).orElseThrow().equals(claimant)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            tx.see(object.get());
            tx.collection(OBJECT).updateOne(tx.session, byId(uuid(objectId)), new BsonDocument("$set",
                    new BsonDocument("state", new BsonString(CatalogObject.State.PURGED.name()))
                            .append("purgeClaimant", BsonNull.VALUE).append("clock", tx.clock())));
            tx.collection(GRANT).deleteMany(tx.session, new BsonDocument("securableId", uuid(objectId)));
            tx.collection(PENDING_DELETION).deleteMany(tx.session, new BsonDocument("objectId", uuid(objectId)));
            tx.collection(TABLE_HEALTH).deleteOne(tx.session, byId(uuid(objectId)));
            return WriteResult.APPLIED;
        });
    }

    Page<CatalogObject> listActive(CatalogObject.Kind kind, PageRequest page) {
        Optional<BsonValue> after = after(page, 1, parts -> uuid(UUID.fromString(parts.get(0))));
        return transactions.read("active object scan", () -> {
            BsonDocument filter = new BsonDocument("state", new BsonString(ACTIVE))
                    .append("kind", new BsonString(kind.name()));
            after.ifPresent(id -> filter.append("_id", new BsonDocument("$gt", id)));
            List<CatalogObject> found = objects().find(filter).sort(ascending("_id")).limit(page.size() + 1)
                    .map(MongoObjects::object).into(new ArrayList<>());
            return page(found, page, object -> PageToken.encode(object.id().toString()));
        });
    }

    /** Moves the object to {@code name} in the namespace, setting {@code fields} too. */
    private static WriteResult place(Tx tx, BsonDocument object, UUID namespaceId, Name name, BsonDocument fields,
                                     BsonDocument unset) {
        UUID objectId = uuid(object.get("_id"));
        Optional<BsonDocument> namespace = namespace(tx, namespaceId, uuid(object.get("catalogId")));
        if (namespace.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
        }
        Optional<WriteResult> taken = nameTaken(tx, namespaceId, name, Optional.of(objectId));
        if (taken.isPresent()) {
            return taken.get();
        }
        tx.see(namespace.get());
        tx.see(object);
        tx.mark(NAMESPACE, namespace.get());
        BsonDocument set = new BsonDocument("namespaceId", uuid(namespaceId))
                .append("namespacePath", uuids(path(namespace.get())))
                .append("nameNorm", new BsonString(name.normalized()))
                .append("name", new BsonString(name.display()))
                .append("clock", tx.clock());
        set.putAll(fields);
        BsonDocument update = new BsonDocument("$set", set);
        if (!unset.isEmpty()) {
            update.append("$unset", unset);
        }
        tx.collection(OBJECT).updateOne(tx.session, byId(uuid(objectId)), update);
        return WriteResult.APPLIED;
    }

    private static Optional<BsonDocument> namespace(Tx tx, UUID namespaceId, UUID catalogId) {
        return tx.find(NAMESPACE, byId(uuid(namespaceId)).append("catalogId", uuid(catalogId)));
    }

    /** The active holder of {@code name} in the namespace, other than {@code self}. */
    private static Optional<WriteResult> nameTaken(Tx tx, UUID namespaceId, Name name, Optional<UUID> self) {
        BsonDocument filter = new BsonDocument("namespaceId", uuid(namespaceId))
                .append("nameNorm", new BsonString(name.normalized()))
                .append("state", new BsonString(ACTIVE));
        self.ifPresent(id -> filter.append("_id", new BsonDocument("$ne", uuid(id))));
        return tx.find(OBJECT, filter).map(holder -> new WriteResult.NameTaken(uuid(holder.get("_id"))));
    }

    private static List<UUID> path(BsonDocument namespace) {
        List<UUID> path = new ArrayList<>(uuidList(namespace.get("ancestors")));
        path.add(uuid(namespace.get("_id")));
        return path;
    }

    private static boolean held(Tx tx, BsonDocument object) {
        return Guards.held(tx, uuid(object.get("_id")), uuid(object.get("catalogId")),
                uuidList(object.get("namespacePath")));
    }

    private static Optional<String> claimant(BsonDocument object) {
        BsonValue claimant = object.get("purgeClaimant");
        return claimant == null || claimant.isNull() ? Optional.empty() : Optional.of(claimant.asString().getValue());
    }

    static List<BsonValue> holdTargets(BsonDocument object) {
        List<BsonValue> targets = new ArrayList<>(object.getArray("namespacePath").getValues());
        targets.add(object.get("_id"));
        targets.add(object.get("catalogId"));
        return targets;
    }

    private MongoCollection<BsonDocument> objects() {
        return transactions.database().getCollection(OBJECT, BsonDocument.class);
    }

    static CatalogObject object(BsonDocument doc) {
        return new CatalogObject(uuid(doc.get("catalogId")), uuid(doc.get("_id")), uuid(doc.get("namespaceId")),
                new Name(doc.getString("nameNorm").getValue(), doc.getString("name").getValue()),
                CatalogObject.Kind.valueOf(doc.getString("kind").getValue()),
                CatalogObject.State.valueOf(doc.getString("state").getValue()),
                optionalInstant(doc, "droppedAt"), optionalInstant(doc, "purgeAfter"),
                doc.getString("locationRoot").getValue(), claimant(doc));
    }
}
