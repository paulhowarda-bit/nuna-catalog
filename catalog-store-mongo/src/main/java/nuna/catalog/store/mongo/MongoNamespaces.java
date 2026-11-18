package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.after;
import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.int64;
import static nuna.catalog.store.mongo.Docs.page;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.Docs.uuidList;
import static nuna.catalog.store.mongo.Docs.uuids;
import static nuna.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.GRANT;
import static nuna.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Namespaces. A namespace document embeds its properties and the ids of the namespaces above it.
 * A new namespace marks its parent namespace, so a concurrent drop of the parent conflicts with
 * it. A catalog is never removed, so a top-level namespace does not mark it, and creates under
 * one catalog do not conflict. The drop of a namespace marks its parent, catalog included, so a
 * namespace created later under the same name stamps higher than the drop.
 */
final class MongoNamespaces {

    private final Transactions transactions;

    MongoNamespaces(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult create(NamespaceRecord namespace, Change change) {
        return transactions.change(change, "namespace create", tx -> {
            boolean topLevel = namespace.parentId().equals(namespace.catalogId());
            Optional<BsonDocument> parent = topLevel
                    ? tx.find(CATALOG, byId(uuid(namespace.catalogId())))
                    : tx.find(NAMESPACE, byId(uuid(namespace.parentId())).append("catalogId", uuid(namespace.catalogId())));
            if (parent.isEmpty()) {
                return new WriteResult.Missing(topLevel ? WriteResult.Subject.CATALOG : WriteResult.Subject.NAMESPACE);
            }
            List<UUID> ancestors = new ArrayList<>();
            if (!topLevel) {
                ancestors.addAll(uuidList(parent.get().get("ancestors")));
                ancestors.add(namespace.parentId());
            }
            Optional<BsonDocument> holder = tx.find(NAMESPACE, new BsonDocument("parentId", uuid(namespace.parentId()))
                    .append("nameNorm", new BsonString(namespace.name().normalized())));
            if (holder.isPresent()) {
                return new WriteResult.NameTaken(uuid(holder.get().get("_id")));
            }
            tx.see(parent.get());
            if (!topLevel) {
                tx.mark(NAMESPACE, parent.get());
            }
            tx.collection(NAMESPACE).insertOne(tx.session, byId(uuid(namespace.id()))
                    .append("catalogId", uuid(namespace.catalogId()))
                    .append("parentId", uuid(namespace.parentId()))
                    .append("ancestors", uuids(ancestors))
                    .append("nameNorm", new BsonString(namespace.name().normalized()))
                    .append("name", new BsonString(namespace.name().display()))
                    .append("version", int64(1))
                    .append("properties", properties(namespace.properties()))
                    .append("clock", tx.clock()));
            return WriteResult.APPLIED;
        });
    }

    Optional<NamespaceRecord> find(UUID catalogId, List<String> normalizedPath) {
        if (normalizedPath.isEmpty()) {
            return Optional.empty();
        }
        return transactions.snapshot("namespace lookup", session -> {
            MongoCollection<BsonDocument> namespaces = transactions.database().getCollection(NAMESPACE, BsonDocument.class);
            BsonValue parent = uuid(catalogId);
            BsonDocument found = null;
            for (String name : normalizedPath) {
                found = namespaces.find(session, new BsonDocument("parentId", parent)
                        .append("nameNorm", new BsonString(name))).first();
                if (found == null) {
                    return Optional.empty();
                }
                parent = found.get("_id");
            }
            return Optional.of(namespace(found));
        });
    }

    Optional<NamespaceRecord> read(UUID namespaceId) {
        return transactions.read("namespace read", () -> Optional.ofNullable(namespaces().find(byId(uuid(namespaceId))).first())
                .map(MongoNamespaces::namespace));
    }

    Page<NamespaceRecord> list(UUID parentId, PageRequest page) {
        Optional<String> after = after(page, 1, List::getFirst);
        return transactions.read("namespace listing", () -> {
            BsonDocument filter = new BsonDocument("parentId", uuid(parentId));
            after.ifPresent(name -> filter.append("nameNorm", new BsonDocument("$gt", new BsonString(name))));
            List<NamespaceRecord> found = namespaces().find(filter).sort(ascending("nameNorm")).limit(page.size() + 1)
                    .map(MongoNamespaces::namespace).into(new ArrayList<>());
            return page(found, page, namespace -> PageToken.encode(namespace.name().normalized()));
        });
    }

    WriteResult updateProperties(UUID namespaceId, long expectedVersion, Map<String, String> properties, Change change) {
        return transactions.change(change, "namespace properties update", tx -> {
            Optional<BsonDocument> namespace = tx.find(NAMESPACE, byId(uuid(namespaceId)));
            if (namespace.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            long version = namespace.get().getInt64("version").getValue();
            if (version != expectedVersion) {
                return new WriteResult.VersionMismatch(version);
            }
            tx.see(namespace.get());
            tx.collection(NAMESPACE).updateOne(tx.session, byId(uuid(namespaceId)), new BsonDocument("$set",
                    new BsonDocument("properties", properties(properties)).append("version", int64(version + 1))
                            .append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    WriteResult drop(UUID namespaceId, Change change) {
        return transactions.change(change, "namespace drop", tx -> {
            Optional<BsonDocument> found = tx.find(NAMESPACE, byId(uuid(namespaceId)));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.NAMESPACE);
            }
            BsonDocument namespace = found.get();
            if (tx.exists(NAMESPACE, new BsonDocument("parentId", uuid(namespaceId)))
                    || tx.exists(OBJECT, new BsonDocument("namespaceId", uuid(namespaceId))
                    .append("state", new BsonString(CatalogObject.State.ACTIVE.name())))) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY);
            }
            UUID catalogId = uuid(namespace.get("catalogId"));
            if (Guards.held(tx, namespaceId, catalogId, uuidList(namespace.get("ancestors")))) {
                return new WriteResult.Refused(WriteResult.Reason.HELD);
            }
            UUID parentId = uuid(namespace.get("parentId"));
            String parentCollection = parentId.equals(catalogId) ? CATALOG : NAMESPACE;
            Optional<BsonDocument> parent = tx.find(parentCollection, byId(uuid(parentId)));
            parent.ifPresent(tx::see);
            tx.see(namespace);
            parent.ifPresent(doc -> tx.mark(parentCollection, doc));
            tx.collection(GRANT).deleteMany(tx.session, new BsonDocument("securableId", uuid(namespaceId)));
            tx.collection(ATTRIBUTE).deleteMany(tx.session, new BsonDocument("targetId", uuid(namespaceId)));
            tx.collection(NAMESPACE).deleteOne(tx.session, byId(uuid(namespaceId)));
            return WriteResult.APPLIED;
        });
    }

    private MongoCollection<BsonDocument> namespaces() {
        return transactions.database().getCollection(NAMESPACE, BsonDocument.class);
    }

    private static BsonArray properties(Map<String, String> properties) {
        BsonArray array = new BsonArray();
        properties.forEach((key, value) -> array.add(new BsonDocument("key", new BsonString(key))
                .append("value", new BsonString(value))));
        return array;
    }

    private static NamespaceRecord namespace(BsonDocument doc) {
        Map<String, String> properties = new HashMap<>();
        for (BsonValue property : doc.getArray("properties")) {
            properties.put(property.asDocument().getString("key").getValue(),
                    property.asDocument().getString("value").getValue());
        }
        return new NamespaceRecord(uuid(doc.get("catalogId")), uuid(doc.get("_id")), uuid(doc.get("parentId")),
                new Name(doc.getString("nameNorm").getValue(), doc.getString("name").getValue()), properties,
                doc.getInt64("version").getValue());
    }
}
