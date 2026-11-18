package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.after;
import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.GRANT;

import com.mongodb.client.MongoCollection;
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
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
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Grants. A grant marks its securable, so a concurrent drop or purge of the securable conflicts
 * with it. A revoke marks the securable too, so a grant put back later stamps higher.
 */
final class MongoGrants {

    private final Transactions transactions;

    MongoGrants(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(Grant grant, String grantedBy, Change change) {
        return transactions.change(change, "grant", tx -> {
            Optional<BsonDocument> securable = Guards.live(tx, grant.securable());
            if (securable.isEmpty() || !Guards.catalogOf(grant.securable(), securable.get()).equals(grant.catalogId())) {
                return Guards.missing(grant.securable());
            }
            if (tx.exists(GRANT, key(grant))) {
                return WriteResult.UNCHANGED;
            }
            tx.see(securable.get());
            tx.mark(Guards.collection(grant.securable().kind()), securable.get());
            tx.collection(GRANT).insertOne(tx.session, new BsonDocument("catalogId", uuid(grant.catalogId()))
                    .append("securableKind", new BsonString(grant.securable().kind().name()))
                    .append("securableId", uuid(grant.securable().id()))
                    .append("group", new BsonString(grant.group()))
                    .append("privilege", new BsonString(grant.privilege()))
                    .append("grantedBy", new BsonString(grantedBy))
                    .append("grantedAt", time(change.at()))
                    .append("clock", tx.clock()));
            return WriteResult.APPLIED;
        });
    }

    WriteResult revoke(Grant grant, Change change) {
        return transactions.change(change, "revoke", tx -> {
            Optional<BsonDocument> found = tx.find(GRANT, key(grant)
                    .append("securableKind", new BsonString(grant.securable().kind().name()))
                    .append("catalogId", uuid(grant.catalogId())));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.GRANT);
            }
            Optional<BsonDocument> securable = Guards.record(tx, grant.securable());
            securable.ifPresent(tx::see);
            tx.see(found.get());
            securable.ifPresent(doc -> tx.mark(Guards.collection(grant.securable().kind()), doc));
            tx.collection(GRANT).deleteOne(tx.session, byId(found.get().get("_id")));
            return WriteResult.APPLIED;
        });
    }

    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
        List<BsonValue> ids = securables.stream().<BsonValue>map(securable -> uuid(securable.id())).toList();
        List<BsonValue> names = groups.stream().<BsonValue>map(BsonString::new).toList();
        return transactions.read("grant check", () -> grants().find(new BsonDocument("securableId", in(ids))
                        .append("group", in(names))).map(MongoGrants::grant).into(new ArrayList<>()))
                .stream().filter(found -> securables.contains(found.grant().securable())).toList();
    }

    Page<GrantRecord> listOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 3, parts -> List.of(new BsonString(parts.get(0)),
                uuid(UUID.fromString(parts.get(1))), new BsonString(parts.get(2))));
        List<BsonValue> names = groups.stream().<BsonValue>map(BsonString::new).toList();
        return transactions.read("grant listing", () -> {
            BsonDocument filter = new BsonDocument("catalogId", uuid(catalogId)).append("group", in(names));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    Docs.after(List.of("group", "securableId", "privilege"), values)))));
            List<GrantRecord> found = grants().find(filter).sort(ascending("group", "securableId", "privilege"))
                    .limit(page.size() + 1).map(MongoGrants::grant).into(new ArrayList<>());
            return page(found, page, record -> PageToken.encode(record.grant().group(),
                    record.grant().securable().id().toString(), record.grant().privilege()));
        });
    }

    Page<GrantRecord> listOn(Securable securable, PageRequest page) {
        Optional<List<BsonValue>> after = after(page, 2,
                parts -> List.of(new BsonString(parts.get(0)), new BsonString(parts.get(1))));
        return transactions.read("grant listing", () -> {
            BsonDocument filter = new BsonDocument("securableId", uuid(securable.id()))
                    .append("securableKind", new BsonString(securable.kind().name()));
            after.ifPresent(values -> filter.append("$and", new BsonArray(List.of(
                    Docs.after(List.of("group", "privilege"), values)))));
            List<GrantRecord> found = grants().find(filter).sort(ascending("group", "privilege"))
                    .limit(page.size() + 1).map(MongoGrants::grant).into(new ArrayList<>());
            return page(found, page, record -> PageToken.encode(record.grant().group(), record.grant().privilege()));
        });
    }

    private static BsonDocument key(Grant grant) {
        return new BsonDocument("securableId", uuid(grant.securable().id()))
                .append("group", new BsonString(grant.group()))
                .append("privilege", new BsonString(grant.privilege()));
    }

    private MongoCollection<BsonDocument> grants() {
        return transactions.database().getCollection(GRANT, BsonDocument.class);
    }

    private static GrantRecord grant(BsonDocument doc) {
        Securable securable = new Securable(Securable.Kind.valueOf(doc.getString("securableKind").getValue()),
                uuid(doc.get("securableId")));
        return new GrantRecord(new Grant(uuid(doc.get("catalogId")), securable, doc.getString("group").getValue(),
                doc.getString("privilege").getValue()), doc.getString("grantedBy").getValue(),
                instant(doc.get("grantedAt")));
    }
}
