package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.after;
import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.optionalInstant;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.POINTER;
import static nuna.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF_ENTRY;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * A catalog's branches and tags (D33): the ref document and its cut, {@code ref_entry}. A cut can be larger than one
 * transaction should hold, so a create reads it in a snapshot session, at one cluster time, which holds every
 * multi-table transaction whole and every table with the namespace that held it; writes it in batches; and then, in
 * one transaction, inserts the ref. The ref becomes visible only with its whole cut, and an entry counts only while
 * its ref exists. That transaction marks the catalog, the lock on the catalog's refs, so of two creates counting
 * P11's limit one conflicts and counts again; and it marks the ref it is made from, so a deletion of that ref
 * conflicts with it. A create that ends otherwise than applied deletes the entries it wrote, unless its ref exists.
 * A commit on a branch marks the ref, and a merge deletes it, in the commit's transaction ({@link #openBranch}).
 */
final class MongoRefs {

    /** Entries written, and object ids looked up, per round trip. */
    private static final int BATCH = 1_000;
    private static final String NAMESPACE_KIND = RefEntry.Kind.NAMESPACE.name();
    private static final WriteResult MISSING = new WriteResult.Missing(WriteResult.Subject.REF);

    private final Transactions transactions;

    MongoRefs(Transactions transactions) {
        this.transactions = transactions;
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return transactions.database().getCollection(name, BsonDocument.class);
    }

    /** What the snapshot read found: an outcome that ends the create, or the cut. */
    private record Read(Optional<WriteResult> outcome, List<BsonDocument> entries) {
    }

    WriteResult create(Ref ref, RefLimits limits, Change change) {
        Read read = transactions.snapshot("ref cut", session -> cut(session, ref, change.at()));
        if (read.outcome().isPresent()) {
            return read.outcome().get();
        }
        WriteResult result;
        try {
            outsideTransaction("ref cut write", () -> {
                discard(ref.id());
                write(read.entries());
            });
            result = transactions.change(change, "ref create", tx -> insert(tx, ref, limits, change.at()));
        } catch (StoreException e) {
            try {
                discard(ref.id());
            } catch (MongoException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        if (!result.equals(WriteResult.APPLIED)) {
            outsideTransaction("ref cut discard", () -> discard(ref.id()));
        }
        return result;
    }

    /** Writes that need no transaction: nothing reads a cut before its ref exists. */
    private static void outsideTransaction(String what, Runnable body) {
        try {
            body.run();
        } catch (MongoException e) {
            throw Transactions.failed(what + " failed", e);
        }
    }

    /**
     * The checks a create can answer before it writes, in the order {@link nuna.catalog.store.RefStore#createRef} gives
     * them, then the cut, all read at one cluster time.
     */
    private Read cut(ClientSession session, Ref ref, Instant now) {
        if (collection(CATALOG).find(session, byId(uuid(ref.catalogId()))).first() == null) {
            return new Read(Optional.of(new WriteResult.Missing(WriteResult.Subject.CATALOG)), List.of());
        }
        BsonDocument holder = collection(REF).find(session, nameFilter(ref)).first();
        if (holder != null) {
            return new Read(Optional.of(new WriteResult.NameTaken(uuid(holder.get("_id")))), List.of());
        }
        if (ref.fromRef().isPresent()) {
            BsonDocument from = collection(REF).find(session, byId(uuid(ref.fromRef().get()))
                    .append("catalogId", uuid(ref.catalogId()))).first();
            if (from == null || expired(from, now)) {
                return new Read(Optional.of(MISSING), List.of());
            }
            return new Read(Optional.empty(), cutOfRef(session, ref.id(), ref.fromRef().get()));
        }
        return new Read(Optional.empty(), cutOfMain(session, ref.id(), ref.catalogId()));
    }

    /** Every namespace, and every active table and view with its main pointer, of one catalog. */
    private List<BsonDocument> cutOfMain(ClientSession session, UUID refId, UUID catalogId) {
        List<BsonDocument> entries = new ArrayList<>();
        collection(NAMESPACE).find(session, new BsonDocument("catalogId", uuid(catalogId))).forEach(namespace ->
                entries.add(entry(refId, namespace.get("_id"), NAMESPACE_KIND, namespace.get("parentId"),
                        namespace.get("nameNorm"), namespace.get("name"), null)));
        List<BsonDocument> objects = collection(OBJECT).find(session, new BsonDocument("catalogId", uuid(catalogId))
                .append("state", new BsonString("ACTIVE"))).into(new ArrayList<>());
        Map<BsonValue, BsonDocument> pointers = pointers(session, objects.stream().map(o -> o.get("_id")).toList(),
                PointerKey.MAIN);
        for (BsonDocument object : objects) {
            BsonDocument pointer = pointers.get(object.get("_id"));
            if (pointer != null) {
                entries.add(entry(refId, object.get("_id"), object.getString("kind").getValue(), object.get("namespaceId"),
                        object.get("nameNorm"), object.get("name"), pointer));
            }
        }
        return entries;
    }

    /** Another ref's cut, with that ref's own pointer where it has one. */
    private List<BsonDocument> cutOfRef(ClientSession session, UUID refId, UUID fromRef) {
        List<BsonDocument> cut = collection(REF_ENTRY).find(session, new BsonDocument("refId", uuid(fromRef)))
                .into(new ArrayList<>());
        Map<BsonValue, BsonDocument> own = pointers(session, cut.stream()
                .filter(e -> !e.getString("kind").getValue().equals(NAMESPACE_KIND)).map(e -> e.get("objectId")).toList(),
                fromRef.toString());
        List<BsonDocument> entries = new ArrayList<>();
        for (BsonDocument from : cut) {
            BsonDocument pointer = own.getOrDefault(from.get("objectId"), from);
            entries.add(entry(refId, from.get("objectId"), from.getString("kind").getValue(), from.get("parentId"),
                    from.get("nameNorm"), from.get("name"),
                    from.getString("kind").getValue().equals(NAMESPACE_KIND) ? null : pointer));
        }
        return entries;
    }

    /** The pointers of {@code objectIds} on {@code branch}, by object id, read in batches. */
    private Map<BsonValue, BsonDocument> pointers(ClientSession session, List<BsonValue> objectIds, String branch) {
        Map<BsonValue, BsonDocument> found = new HashMap<>();
        for (int from = 0; from < objectIds.size(); from += BATCH) {
            List<BsonValue> chunk = objectIds.subList(from, Math.min(objectIds.size(), from + BATCH));
            collection(POINTER).find(session, new BsonDocument("objectId", Docs.in(chunk))
                            .append("branch", new BsonString(branch)))
                    .projection(new BsonDocument("objectId", new BsonInt32(1)).append("version", new BsonInt32(1))
                            .append("metadataLocation", new BsonInt32(1)).append("sha256", new BsonInt32(1)))
                    .forEach(pointer -> found.put(pointer.get("objectId"), pointer));
        }
        return found;
    }

    /** A cut entry; {@code pointer}, null for a namespace, carries version, metadataLocation and sha256. */
    private static BsonDocument entry(UUID refId, BsonValue objectId, String kind, BsonValue parentId, BsonValue nameNorm,
                                      BsonValue name, BsonDocument pointer) {
        return new BsonDocument("refId", uuid(refId)).append("objectId", objectId).append("kind", new BsonString(kind))
                .append("parentId", parentId).append("nameNorm", nameNorm).append("name", name)
                .append("version", pointer == null ? BsonNull.VALUE : pointer.get("version"))
                .append("metadataLocation", pointer == null ? BsonNull.VALUE : pointer.get("metadataLocation"))
                .append("sha256", pointer == null ? BsonNull.VALUE : pointer.get("sha256"));
    }

    private void write(List<BsonDocument> entries) {
        for (int from = 0; from < entries.size(); from += BATCH) {
            collection(REF_ENTRY).insertMany(entries.subList(from, Math.min(entries.size(), from + BATCH)));
        }
    }

    /** Deletes the entries of a ref that does not exist: what a create left that never inserted its ref. */
    private void discard(UUID refId) {
        if (collection(REF).find(byId(uuid(refId))).first() == null) {
            collection(REF_ENTRY).deleteMany(new BsonDocument("refId", uuid(refId)));
        }
    }

    /** The create's transaction: the checks again, in the same order, under the marks, then the ref. */
    private static WriteResult insert(Tx tx, Ref ref, RefLimits limits, Instant now) {
        Optional<BsonDocument> catalog = tx.find(CATALOG, byId(uuid(ref.catalogId())));
        if (catalog.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.CATALOG);
        }
        Optional<BsonDocument> holder = tx.find(REF, nameFilter(ref));
        if (holder.isPresent()) {
            return new WriteResult.NameTaken(uuid(holder.get().get("_id")));
        }
        Optional<BsonDocument> from = Optional.empty();
        if (ref.fromRef().isPresent()) {
            from = tx.find(REF, byId(uuid(ref.fromRef().get())).append("catalogId", uuid(ref.catalogId())));
            if (from.isEmpty() || expired(from.get(), now)) {
                return MISSING;
            }
        }
        long held = tx.collection(REF).countDocuments(tx.session, new BsonDocument("catalogId", uuid(ref.catalogId()))
                .append("kind", new BsonString(ref.kind().name()))
                .append("$or", new BsonArray(List.of(new BsonDocument("expiresAt", BsonNull.VALUE),
                        new BsonDocument("expiresAt", new BsonDocument("$gt", time(now)))))));
        if (!limits.admitsAnother(ref.kind(), held)) {
            return new WriteResult.Refused(WriteResult.Reason.REF_LIMIT);
        }
        tx.see(catalog.get());
        from.ifPresent(tx::see);
        tx.mark(CATALOG, catalog.get());
        from.ifPresent(doc -> tx.mark(REF, doc));
        tx.collection(REF).insertOne(tx.session, byId(uuid(ref.id()))
                .append("catalogId", uuid(ref.catalogId()))
                .append("nameNorm", new BsonString(ref.name().normalized()))
                .append("name", new BsonString(ref.name().display()))
                .append("kind", new BsonString(ref.kind().name()))
                .append("expiresAt", ref.expiresAt().<BsonValue>map(Docs::time).orElse(BsonNull.VALUE))
                .append("createdAt", time(ref.createdAt()))
                .append("createdBy", new BsonString(ref.createdBy()))
                .append("fromRef", ref.fromRef().<BsonValue>map(Docs::uuid).orElse(BsonNull.VALUE))
                .append("fromName", new BsonString(ref.fromName()))
                .append("clock", tx.clock()));
        return WriteResult.APPLIED;
    }

    private static BsonDocument nameFilter(Ref ref) {
        return new BsonDocument("catalogId", uuid(ref.catalogId())).append("nameNorm", new BsonString(ref.name().normalized()));
    }

    /** Whether a ref document is a branch past its expiry at {@code now}. */
    private static boolean expired(BsonDocument ref, Instant now) {
        return optionalInstant(ref, "expiresAt").map(expiresAt -> !expiresAt.isAfter(now)).orElse(false);
    }

    Optional<Ref> read(UUID refId) {
        return transactions.read("ref read", () -> Optional.ofNullable(collection(REF).find(byId(uuid(refId))).first())
                .map(MongoRefs::ref));
    }

    Optional<Ref> find(UUID catalogId, String normalizedName) {
        return transactions.read("ref lookup", () -> Optional.ofNullable(collection(REF).find(
                new BsonDocument("catalogId", uuid(catalogId)).append("nameNorm", new BsonString(normalizedName))).first())
                .map(MongoRefs::ref));
    }

    Page<Ref> list(UUID catalogId, PageRequest page) {
        Optional<String> after = after(page, 1, List::getFirst);
        return transactions.read("ref listing", () -> {
            BsonDocument filter = new BsonDocument("catalogId", uuid(catalogId));
            after.ifPresent(name -> filter.append("nameNorm", new BsonDocument("$gt", new BsonString(name))));
            List<Ref> found = collection(REF).find(filter).sort(ascending("nameNorm")).limit(page.size() + 1)
                    .map(MongoRefs::ref).into(new ArrayList<>());
            return page(found, page, ref -> PageToken.encode(ref.name().normalized()));
        });
    }

    WriteResult renew(UUID refId, Instant expiresAt, Change change) {
        return transactions.change(change, "ref renewal", tx -> {
            Optional<BsonDocument> found = tx.find(REF, byId(uuid(refId)));
            if (found.isEmpty()) {
                return MISSING;
            }
            if (found.get().getString("kind").getValue().equals(Ref.Kind.TAG.name())) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_A_BRANCH);
            }
            if (expired(found.get(), change.at())) {
                return MISSING;
            }
            if (instant(found.get().get("expiresAt")).toEpochMilli() == expiresAt.toEpochMilli()) {
                return WriteResult.UNCHANGED;
            }
            tx.see(found.get());
            tx.collection(REF).updateOne(tx.session, byId(uuid(refId)), new BsonDocument("$set",
                    new BsonDocument("expiresAt", time(expiresAt)).append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    WriteResult delete(UUID refId, Change change) {
        return transactions.change(change, "ref delete", tx -> {
            Optional<BsonDocument> found = tx.find(REF, byId(uuid(refId)));
            if (found.isEmpty()) {
                return MISSING;
            }
            tx.see(found.get());
            end(tx, refId, seeOwnPointers(tx, refId).cut());
            return WriteResult.APPLIED;
        });
    }

    /** A ref's tables and views, and those of them its own pointers name. */
    record Owned(List<BsonValue> cut, Set<UUID> pointed) {
    }

    /**
     * The tables and views of a ref's cut, the only objects its own pointers can name (D33, question 1), after seeing
     * each own pointer: what {@link #end} deletes. Call before the change's stamp is fixed.
     */
    static Owned seeOwnPointers(Tx tx, UUID refId) {
        List<BsonValue> objectIds = tx.collection(REF_ENTRY).find(tx.session, new BsonDocument("refId", uuid(refId))
                        .append("kind", new BsonDocument("$ne", new BsonString(NAMESPACE_KIND))))
                .projection(new BsonDocument("objectId", new BsonInt32(1)))
                .map(entry -> entry.get("objectId")).into(new ArrayList<>());
        BsonString branch = new BsonString(refId.toString());
        Set<UUID> pointed = new HashSet<>();
        for (int from = 0; from < objectIds.size(); from += BATCH) {
            BsonDocument own = new BsonDocument("objectId", Docs.in(objectIds.subList(from,
                    Math.min(objectIds.size(), from + BATCH)))).append("branch", branch);
            tx.collection(POINTER).find(tx.session, own)
                    .projection(new BsonDocument("clock", new BsonInt32(1)).append("objectId", new BsonInt32(1)))
                    .forEach(pointer -> pointed.add(uuid(tx.see(pointer).get("objectId"))));
        }
        return new Owned(objectIds, pointed);
    }

    /**
     * The first own pointer of the branch, in key order, that a merge does not hold: a commit on the branch landed
     * after the merge read it, and ending the branch would lose it, so the merge answers a conflict on it.
     */
    static Optional<PointerKey> unheld(CommitBatch batch, Owned owned) {
        Set<UUID> held = new HashSet<>();
        batch.holds().forEach(hold -> held.add(hold.key().objectId()));
        String branch = batch.ref().orElseThrow().refId().toString();
        return owned.pointed().stream().filter(objectId -> !held.contains(objectId))
                .map(objectId -> new PointerKey(objectId, branch)).sorted().findFirst();
    }

    /**
     * Deletes a ref seen in the transaction: its own pointers of {@code objectIds} ({@link #seeOwnPointers}) and their
     * pieces, its cut and its document. Its journal entries stay. A deletion and a merge both end a ref this way.
     */
    static void end(Tx tx, UUID refId, List<BsonValue> objectIds) {
        BsonString branch = new BsonString(refId.toString());
        for (int from = 0; from < objectIds.size(); from += BATCH) {
            BsonDocument own = new BsonDocument("objectId", Docs.in(objectIds.subList(from,
                    Math.min(objectIds.size(), from + BATCH)))).append("branch", branch);
            tx.collection(POINTER).deleteMany(tx.session, own);
            tx.collection(PROJECTION_PIECE).deleteMany(tx.session, own);
        }
        tx.collection(REF_ENTRY).deleteMany(tx.session, new BsonDocument("refId", uuid(refId)));
        tx.collection(REF).deleteOne(tx.session, byId(uuid(refId)));
    }

    /**
     * The branch a commit lands on or ends (D33, backlog 103), read in the commit's transaction and seen; empty when it
     * is gone, a tag, or past its expiry at the batch's time. Call before the change's stamp is fixed. MongoDB has no
     * shared locks, so a commit on the branch then marks it, as a commit marks its objects, and a merge deletes it:
     * either write conflicts with a merge, a deletion or a renewal at the same moment, and with another commit on the
     * branch, so commits on one branch take turns.
     */
    static Optional<BsonDocument> openBranch(Tx tx, CommitBatch batch) {
        Optional<BsonDocument> found = tx.find(REF, byId(uuid(batch.ref().orElseThrow().refId())));
        if (found.isEmpty() || !found.get().getString("kind").getValue().equals(Ref.Kind.BRANCH.name())
                || expired(found.get(), batch.committedAt())) {
            return Optional.empty();
        }
        return Optional.of(tx.see(found.get()));
    }

    /**
     * The key of the first swap of a commit on a branch whose object is not a table or view of the branch's cut. The
     * cut counts, since the transaction read its ref; it never changes while the ref lives.
     */
    static Optional<PointerKey> outsideCut(Tx tx, CommitBatch batch) {
        BsonValue refId = uuid(batch.ref().orElseThrow().refId());
        for (PointerSwap swap : batch.swaps()) {
            if (!tx.exists(REF_ENTRY, new BsonDocument("refId", refId).append("objectId", uuid(swap.key().objectId()))
                    .append("kind", new BsonDocument("$ne", new BsonString(NAMESPACE_KIND))))) {
                return Optional.of(swap.key());
            }
        }
        return Optional.empty();
    }

    /**
     * Those of {@code objectIds} that a ref's cut names (D33): an entry counts only while its ref exists, so a cut
     * left by a create that never inserted its ref names nothing. Read in {@code session}.
     */
    static Set<BsonValue> referenced(MongoDatabase database, ClientSession session, List<BsonValue> objectIds) {
        Map<BsonValue, List<BsonValue>> refsOf = new HashMap<>();
        database.getCollection(REF_ENTRY, BsonDocument.class).find(session, new BsonDocument("objectId",
                        Docs.in(objectIds)).append("kind", new BsonDocument("$ne", new BsonString(NAMESPACE_KIND))))
                .projection(new BsonDocument("objectId", new BsonInt32(1)).append("refId", new BsonInt32(1)))
                .forEach(entry -> refsOf.computeIfAbsent(entry.get("refId"), ref -> new ArrayList<>())
                        .add(entry.get("objectId")));
        Set<BsonValue> referenced = new HashSet<>();
        if (!refsOf.isEmpty()) {
            database.getCollection(REF, BsonDocument.class)
                    .find(session, new BsonDocument("_id", Docs.in(new ArrayList<>(refsOf.keySet()))))
                    .projection(new BsonDocument("_id", new BsonInt32(1)))
                    .forEach(ref -> referenced.addAll(refsOf.get(ref.get("_id"))));
        }
        return referenced;
    }

    Optional<RefEntry> findNamespace(UUID refId, List<String> normalizedPath) {
        if (normalizedPath.isEmpty()) {
            return Optional.empty();
        }
        return transactions.snapshot("ref namespace lookup", session -> namespace(session, refId, normalizedPath));
    }

    Optional<RefEntry> resolve(UUID refId, List<String> normalizedNamespacePath, String normalizedName) {
        if (normalizedNamespacePath.isEmpty()) {
            return Optional.empty();
        }
        return transactions.snapshot("ref name lookup", session -> {
            Optional<RefEntry> namespace = namespace(session, refId, normalizedNamespacePath);
            if (namespace.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(collection(REF_ENTRY).find(session, new BsonDocument("refId", uuid(refId))
                    .append("parentId", uuid(namespace.get().id()))
                    .append("kind", new BsonDocument("$ne", new BsonString(NAMESPACE_KIND)))
                    .append("nameNorm", new BsonString(normalizedName))).first()).map(MongoRefs::entry);
        });
    }

    /** The namespace at the end of {@code path} in the ref's cut, walked down from its catalog. */
    private Optional<RefEntry> namespace(ClientSession session, UUID refId, List<String> path) {
        BsonDocument ref = collection(REF).find(session, byId(uuid(refId))).first();
        if (ref == null) {
            return Optional.empty();
        }
        BsonValue parent = ref.get("catalogId");
        BsonDocument found = null;
        for (String name : path) {
            found = collection(REF_ENTRY).find(session, new BsonDocument("refId", uuid(refId)).append("parentId", parent)
                    .append("kind", new BsonString(NAMESPACE_KIND)).append("nameNorm", new BsonString(name))).first();
            if (found == null) {
                return Optional.empty();
            }
            parent = found.get("objectId");
        }
        return Optional.of(entry(found));
    }

    Page<RefEntry> listNamespaces(UUID refId, UUID parentId, PageRequest page) {
        Optional<String> after = after(page, 1, List::getFirst);
        return transactions.read("ref namespace listing", () -> {
            BsonDocument filter = new BsonDocument("refId", uuid(refId)).append("parentId", uuid(parentId))
                    .append("kind", new BsonString(NAMESPACE_KIND));
            after.ifPresent(name -> filter.append("nameNorm", new BsonDocument("$gt", new BsonString(name))));
            List<RefEntry> found = collection(REF_ENTRY).find(filter).sort(ascending("nameNorm")).limit(page.size() + 1)
                    .map(MongoRefs::entry).into(new ArrayList<>());
            return page(found, page, entry -> PageToken.encode(entry.name().normalized()));
        });
    }

    Page<RefEntry> listObjects(UUID refId, UUID namespaceId, CatalogObject.Kind kind, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 2,
                parts -> List.of(new BsonString(parts.get(0)), uuid(UUID.fromString(parts.get(1)))));
        return transactions.read("ref object listing", () -> {
            BsonDocument filter = new BsonDocument("refId", uuid(refId)).append("parentId", uuid(namespaceId))
                    .append("kind", new BsonString(RefEntry.of(kind).name()));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    Docs.after(List.of("nameNorm", "objectId"), values)))));
            List<RefEntry> found = collection(REF_ENTRY).find(filter).sort(ascending("nameNorm", "objectId"))
                    .limit(page.size() + 1).map(MongoRefs::entry).into(new ArrayList<>());
            return page(found, page, entry -> PageToken.encode(entry.name().normalized(), entry.id().toString()));
        });
    }

    private static Ref ref(BsonDocument doc) {
        BsonValue fromRef = doc.get("fromRef");
        return new Ref(uuid(doc.get("catalogId")), uuid(doc.get("_id")),
                new Name(doc.getString("nameNorm").getValue(), doc.getString("name").getValue()),
                Ref.Kind.valueOf(doc.getString("kind").getValue()), instant(doc.get("createdAt")),
                doc.getString("createdBy").getValue(),
                fromRef == null || fromRef.isNull() ? Optional.empty() : Optional.of(uuid(fromRef)),
                doc.getString("fromName").getValue(), optionalInstant(doc, "expiresAt"));
    }

    private static RefEntry entry(BsonDocument doc) {
        BsonValue version = doc.get("version");
        Optional<RefEntry.At> pointer = version == null || version.isNull() ? Optional.empty()
                : Optional.of(new RefEntry.At(version.asNumber().longValue(), doc.getString("metadataLocation").getValue(),
                doc.getString("sha256").getValue()));
        return new RefEntry(uuid(doc.get("refId")), uuid(doc.get("objectId")),
                RefEntry.Kind.valueOf(doc.getString("kind").getValue()), uuid(doc.get("parentId")),
                new Name(doc.getString("nameNorm").getValue(), doc.getString("name").getValue()), pointer);
    }
}
