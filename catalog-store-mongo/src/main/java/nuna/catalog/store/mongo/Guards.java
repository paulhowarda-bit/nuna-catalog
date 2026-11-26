package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.HOLD;
import static nuna.catalog.store.mongo.MongoCatalogStore.NAMESPACE;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/** Checks shared by the operation groups: securables that must exist, and legal holds. */
final class Guards {

    private Guards() {
    }

    static String collection(Securable.Kind kind) {
        return switch (kind) {
            case CATALOG -> CATALOG;
            case NAMESPACE -> NAMESPACE;
            case TABLE, VIEW -> OBJECT;
        };
    }

    /** The document of a securable that exists: a catalog, a namespace, or a table or view not purged. */
    static Optional<BsonDocument> live(Tx tx, Securable securable) {
        BsonDocument filter = byId(uuid(securable.id()));
        if (securable.kind() == Securable.Kind.TABLE || securable.kind() == Securable.Kind.VIEW) {
            filter.append("kind", new BsonString(securable.kind().name()))
                    .append("state", new BsonDocument("$ne", new BsonString(CatalogObject.State.PURGED.name())));
        }
        return tx.find(collection(securable.kind()), filter);
    }

    /** The document of the record {@code target} names, in whatever state, to mark it. */
    static Optional<BsonDocument> record(Tx tx, Securable target) {
        return tx.find(collection(target.kind()), byId(uuid(target.id())));
    }

    static UUID catalogOf(Securable securable, BsonDocument doc) {
        return securable.kind() == Securable.Kind.CATALOG ? securable.id() : uuid(doc.get("catalogId"));
    }

    static WriteResult missing(Securable securable) {
        return new WriteResult.Missing(switch (securable.kind()) {
            case CATALOG -> WriteResult.Subject.CATALOG;
            case NAMESPACE -> WriteResult.Subject.NAMESPACE;
            case TABLE, VIEW -> WriteResult.Subject.OBJECT;
        });
    }

    /**
     * The namespaces of {@code path} that exist, each seen: the levels above a record that a purge claim and a removal
     * from refs mark, with the catalog, after reading the holds and DR freezes on them (backlog 171). A hold or freeze
     * placed on one marks it too, so the two conflict and the one that retries reads the other's write. Call before the
     * change's stamp is fixed.
     */
    static List<BsonDocument> seeNamespaces(Tx tx, List<UUID> path) {
        List<BsonValue> ids = new ArrayList<>();
        path.forEach(namespace -> ids.add(uuid(namespace)));
        return tx.collection(NAMESPACE).find(tx.session, new BsonDocument("_id", in(ids))).map(tx::see)
                .into(new ArrayList<>());
    }

    /** Whether a DR freeze is set on the record, on any namespace above it, or on its catalog. */
    static boolean frozen(Tx tx, UUID id, UUID catalogId, List<UUID> path) {
        List<BsonValue> targets = new ArrayList<>();
        path.forEach(namespace -> targets.add(uuid(namespace)));
        targets.add(uuid(id));
        targets.add(uuid(catalogId));
        return tx.exists(ATTRIBUTE, new BsonDocument("targetId", in(targets))
                .append("kind", new BsonString(GovernanceAttribute.Kind.DR_FREEZE.name())));
    }

    /**
     * Whether the record is regulated, by a flag on it, on any namespace above it or on its catalog (D27), or
     * carries a row/column-policy flag itself (D20).
     */
    static boolean flagged(Tx tx, UUID id, UUID catalogId, List<UUID> path) {
        List<BsonValue> targets = new ArrayList<>();
        path.forEach(namespace -> targets.add(uuid(namespace)));
        targets.add(uuid(id));
        targets.add(uuid(catalogId));
        return tx.exists(ATTRIBUTE, new BsonDocument("value", new BsonString("true")).append("$or", new BsonArray(List.of(
                new BsonDocument("kind", new BsonString(GovernanceAttribute.Kind.REGULATED.name()))
                        .append("targetId", in(targets)),
                new BsonDocument("kind", new BsonString(GovernanceAttribute.Kind.ROW_COLUMN_POLICY.name()))
                        .append("targetId", uuid(id))))));
    }

    /** Whether a legal hold sits on the record, on any namespace above it, or on its catalog. */
    static boolean held(Tx tx, UUID id, UUID catalogId, List<UUID> path) {
        List<BsonValue> targets = new ArrayList<>();
        path.forEach(namespace -> targets.add(uuid(namespace)));
        targets.add(uuid(id));
        targets.add(uuid(catalogId));
        return tx.exists(HOLD, new BsonDocument("targetId", in(targets)));
    }
}
