package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.after;
import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.GRANT;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF;
import static nuna.catalog.store.mongo.MongoCatalogStore.REF_GRANT;

import com.mongodb.client.MongoCollection;
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantGroup;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Grants. A grant marks its securable, so a concurrent drop or purge of the securable conflicts
 * with it. A revoke marks the securable too, so a grant put back later stamps higher. A conditional
 * grant also marks its catalog, so of two made at once in one catalog, the later to write conflicts
 * and runs again on a snapshot that holds the other's grant.
 *
 * <p>A grant that names a branch (backlog 113) lives in {@code ref_grant}, the others in {@code grant}, so a server
 * of the previous release never reads one as a grant on every ref. Its {@code refScope} is the branch's id; reads
 * give the others {@link #EVERY_REF}. A put on a branch marks the branch, as a deletion or merge deletes it, so of a
 * put and an ending made at once the later to write conflicts and runs again, and none leaves a grant on a branch
 * that is gone.
 */
final class MongoGrants {

    /** The {@code refScope} read for a grant that holds on every ref, sorting before every branch id. */
    static final String EVERY_REF = "*";

    private final Transactions transactions;

    MongoGrants(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(Grant grant, String grantedBy, Change change) {
        return transactions.change(change, "grant", tx -> insert(tx, grant, grantedBy, change, Optional.empty()));
    }

    /** Reads the group's grants in the catalog from the transaction's snapshot; the catalog's mark settles any race. */
    WriteResult putIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits) {
        return transactions.change(change, "grant", tx -> {
            Optional<BsonDocument> catalog = tx.find(CATALOG, byId(uuid(grant.catalogId())));
            if (catalog.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.CATALOG);
            }
            BsonDocument ofGroup = new BsonDocument("catalogId", uuid(grant.catalogId()))
                    .append("group", new BsonString(grant.group()));
            List<String> held = tx.collection(GRANT).find(tx.session, ofGroup)
                    .map(doc -> doc.getString("privilege").getValue()).into(new ArrayList<>());
            tx.collection(REF_GRANT).find(tx.session, ofGroup)
                    .map(doc -> doc.getString("privilege").getValue()).into(held);
            return admits.test(held) ? insert(tx, grant, grantedBy, change, catalog)
                    : new WriteResult.Refused(WriteResult.Reason.NOT_ADMITTED);
        });
    }

    /** Puts the grant, marking its securable and, when given, its catalog's document. */
    private static WriteResult insert(Tx tx, Grant grant, String grantedBy, Change change,
                                      Optional<BsonDocument> catalog) {
        Optional<BsonDocument> securable = Guards.live(tx, grant.securable());
        if (securable.isEmpty() || !Guards.catalogOf(grant.securable(), securable.get()).equals(grant.catalogId())) {
            return Guards.missing(grant.securable());
        }
        Optional<BsonDocument> branch = Optional.empty();
        if (grant.branch().isPresent()) {
            branch = tx.find(REF, byId(uuid(grant.branch().get())).append("catalogId", uuid(grant.catalogId()))
                    .append("kind", new BsonString("BRANCH")));
            if (branch.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.REF);
            }
        }
        if (tx.exists(collection(grant), key(grant))) {
            return WriteResult.UNCHANGED;
        }
        tx.see(securable.get());
        catalog.ifPresent(tx::see);
        branch.ifPresent(tx::see);
        tx.mark(Guards.collection(grant.securable().kind()), securable.get());
        catalog.ifPresent(doc -> tx.mark(CATALOG, doc));
        branch.ifPresent(doc -> tx.mark(REF, doc));
        BsonDocument doc = new BsonDocument("catalogId", uuid(grant.catalogId()))
                .append("securableKind", new BsonString(grant.securable().kind().name()))
                .append("securableId", uuid(grant.securable().id()))
                .append("group", new BsonString(grant.group()))
                .append("privilege", new BsonString(grant.privilege()))
                .append("grantedBy", new BsonString(grantedBy))
                .append("grantedAt", time(change.at()))
                .append("clock", tx.clock());
        grant.branch().ifPresent(ref -> doc.append("refScope", new BsonString(ref.toString())));
        tx.collection(collection(grant)).insertOne(tx.session, doc);
        return WriteResult.APPLIED;
    }

    WriteResult revoke(Grant grant, Change change) {
        return transactions.change(change, "revoke", tx -> {
            Optional<BsonDocument> found = tx.find(collection(grant), key(grant)
                    .append("securableKind", new BsonString(grant.securable().kind().name()))
                    .append("catalogId", uuid(grant.catalogId())));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.GRANT);
            }
            Optional<BsonDocument> securable = Guards.record(tx, grant.securable());
            securable.ifPresent(tx::see);
            tx.see(found.get());
            securable.ifPresent(doc -> tx.mark(Guards.collection(grant.securable().kind()), doc));
            tx.collection(collection(grant)).deleteOne(tx.session, byId(found.get().get("_id")));
            return WriteResult.APPLIED;
        });
    }

    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
        List<BsonValue> ids = securables.stream().<BsonValue>map(securable -> uuid(securable.id())).toList();
        List<BsonValue> names = groups.stream().<BsonValue>map(BsonString::new).toList();
        BsonDocument filter = new BsonDocument("securableId", in(ids)).append("group", in(names));
        return transactions.read("grant check", () -> {
            List<GrantRecord> found = grants().find(filter).map(MongoGrants::grant).into(new ArrayList<>());
            return refGrants().find(filter).map(MongoGrants::grant).into(found);
        }).stream().filter(found -> securables.contains(found.grant().securable())).toList();
    }

    Page<GrantRecord> listOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 4, parts -> List.of(new BsonString(parts.get(0)),
                uuid(UUID.fromString(parts.get(1))), new BsonString(parts.get(2)), new BsonString(parts.get(3))));
        List<BsonValue> names = groups.stream().<BsonValue>map(BsonString::new).toList();
        return transactions.read("grant listing", () -> {
            BsonDocument filter = new BsonDocument("catalogId", uuid(catalogId)).append("group", in(names));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    atOrAfter(List.of("group", "securableId", "privilege"), values.subList(0, 3))))));
            BsonDocument keyset = after.map(values -> Docs.after(List.of("group", "securableId", "privilege",
                    "refScope"), values)).orElse(new BsonDocument());
            List<GrantRecord> found = union(filter, keyset, ascending("group", "securableId", "privilege", "refScope"),
                    page.size() + 1);
            return page(found, page, record -> PageToken.encode(record.grant().group(),
                    record.grant().securable().id().toString(), record.grant().privilege(), refScope(record.grant())));
        });
    }

    Page<GrantRecord> listOn(Securable securable, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 3, parts -> List.of(new BsonString(parts.get(0)),
                new BsonString(parts.get(1)), new BsonString(parts.get(2))));
        return transactions.read("grant listing", () -> {
            BsonDocument filter = new BsonDocument("securableId", uuid(securable.id()))
                    .append("securableKind", new BsonString(securable.kind().name()));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    atOrAfter(List.of("group", "privilege"), values.subList(0, 2))))));
            BsonDocument keyset = after.map(values -> Docs.after(List.of("group", "privilege", "refScope"), values))
                    .orElse(new BsonDocument());
            List<GrantRecord> found = union(filter, keyset, ascending("group", "privilege", "refScope"),
                    page.size() + 1);
            return page(found, page, record -> PageToken.encode(record.grant().group(), record.grant().privilege(),
                    refScope(record.grant())));
        });
    }

    /** Groups in order on {@code grant_group}, each folded with every privilege it holds in the catalog. */
    Page<GrantGroup> listGroups(UUID catalogId, PageRequest page) {
        Optional<String> after = after(page, 1, List::getFirst);
        return transactions.read("grant group listing", () -> {
            BsonDocument match = new BsonDocument("catalogId", uuid(catalogId));
            after.ifPresent(group -> match.append("group", new BsonDocument("$gt", new BsonString(group))));
            List<GrantGroup> found = grants().aggregate(List.of(
                            new BsonDocument("$match", match),
                            new BsonDocument("$unionWith", new BsonDocument("coll", new BsonString(REF_GRANT))
                                    .append("pipeline", new BsonArray(List.of(new BsonDocument("$match", match))))),
                            new BsonDocument("$sort", new BsonDocument("group", new BsonInt32(1))),
                            new BsonDocument("$group", new BsonDocument("_id", new BsonString("$group"))
                                    .append("privileges", new BsonDocument("$addToSet", new BsonString("$privilege")))),
                            new BsonDocument("$sort", new BsonDocument("_id", new BsonInt32(1))),
                            new BsonDocument("$limit", new BsonInt32(page.size() + 1))))
                    .map(doc -> new GrantGroup(doc.getString("_id").getValue(), doc.getArray("privileges").stream()
                            .map(privilege -> privilege.asString().getValue()).toList()))
                    .into(new ArrayList<>());
            return page(found, page, group -> PageToken.encode(group.group()));
        });
    }

    /**
     * Both collections' grants that match {@code filter}, the {@code grant} collection's with {@code refScope}
     * {@link #EVERY_REF}, then those past {@code keyset}, in {@code sort} order, at most {@code limit}. The filter
     * holds the keyset's leading fields, so each collection reads from its index.
     */
    private List<GrantRecord> union(BsonDocument filter, BsonDocument keyset, BsonDocument sort, int limit) {
        return grants().aggregate(List.of(
                        new BsonDocument("$match", filter),
                        new BsonDocument("$addFields", new BsonDocument("refScope", new BsonString(EVERY_REF))),
                        new BsonDocument("$unionWith", new BsonDocument("coll", new BsonString(REF_GRANT))
                                .append("pipeline", new BsonArray(List.of(new BsonDocument("$match", filter))))),
                        new BsonDocument("$match", keyset),
                        new BsonDocument("$sort", sort),
                        new BsonDocument("$limit", new BsonInt32(limit))))
                .map(MongoGrants::grant).into(new ArrayList<>());
    }

    /** Documents at or past {@code values} in the order of {@code fields}: the keyset's leading fields, for an index. */
    private static BsonDocument atOrAfter(List<String> fields, List<BsonValue> values) {
        BsonDocument at = new BsonDocument();
        for (int i = 0; i < fields.size(); i++) {
            at.append(fields.get(i), values.get(i));
        }
        return new BsonDocument("$or", new BsonArray(List.of(Docs.after(fields, values), at)));
    }

    private static BsonDocument key(Grant grant) {
        BsonDocument key = new BsonDocument("securableId", uuid(grant.securable().id()))
                .append("group", new BsonString(grant.group()))
                .append("privilege", new BsonString(grant.privilege()));
        grant.branch().ifPresent(ref -> key.append("refScope", new BsonString(ref.toString())));
        return key;
    }

    private static String collection(Grant grant) {
        return grant.branch().isPresent() ? REF_GRANT : GRANT;
    }

    private static String refScope(Grant grant) {
        return grant.branch().map(UUID::toString).orElse(EVERY_REF);
    }

    private MongoCollection<BsonDocument> grants() {
        return transactions.database().getCollection(GRANT, BsonDocument.class);
    }

    private MongoCollection<BsonDocument> refGrants() {
        return transactions.database().getCollection(REF_GRANT, BsonDocument.class);
    }

    private static GrantRecord grant(BsonDocument doc) {
        Securable securable = new Securable(Securable.Kind.valueOf(doc.getString("securableKind").getValue()),
                uuid(doc.get("securableId")));
        String scope = doc.getString("refScope", new BsonString(EVERY_REF)).getValue();
        Optional<UUID> branch = scope.equals(EVERY_REF) ? Optional.empty() : Optional.of(UUID.fromString(scope));
        return new GrantRecord(new Grant(uuid(doc.get("catalogId")), securable, doc.getString("group").getValue(),
                doc.getString("privilege").getValue(), branch), doc.getString("grantedBy").getValue(),
                instant(doc.get("grantedAt")));
    }
}
