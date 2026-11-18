package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.in;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.string;
import static nuna.catalog.store.mongo.Docs.text;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static nuna.catalog.store.mongo.MongoCatalogStore.CATALOG;
import static nuna.catalog.store.mongo.MongoCatalogStore.ENGINE;
import static nuna.catalog.store.mongo.MongoCatalogStore.HOLD;
import static nuna.catalog.store.mongo.MongoCatalogStore.STORAGE_PROFILE;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Catalogs, storage profiles, governance attributes, legal holds and trusted engines.
 * Attributes and holds mark their target, as grants do. A trusted engine has no parent record to
 * mark, so a removed engine stays as a tombstone that a later put sees and stamps above.
 */
final class MongoGovernance {

    private static final BsonDocument NOT_REMOVED = new BsonDocument("removed", new BsonDocument("$ne", BsonBoolean.TRUE));

    private final Transactions transactions;

    MongoGovernance(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult createCatalog(CatalogRecord catalog, CatalogSettings settings, Change change) {
        return transactions.change(change, "catalog create", tx -> {
            Optional<BsonDocument> profile = tx.find(STORAGE_PROFILE, byId(uuid(settings.storageProfileId())));
            if (profile.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.STORAGE_PROFILE);
            }
            Optional<BsonDocument> holder = tx.find(CATALOG, new BsonDocument("nameNorm", new BsonString(catalog.normalizedName())));
            if (holder.isPresent()) {
                return new WriteResult.NameTaken(uuid(holder.get().get("_id")));
            }
            // A storage profile is never removed, so the catalog sees it for its stamp but need not mark it.
            tx.see(profile.get());
            tx.collection(CATALOG).insertOne(tx.session, byId(uuid(catalog.id()))
                    .append("nameNorm", new BsonString(catalog.normalizedName()))
                    .append("name", new BsonString(catalog.name()))
                    .append("storageProfileId", uuid(settings.storageProfileId()))
                    .append("kmsKeySet", new BsonString(settings.kmsKeySet()))
                    .append("legalEntity", new BsonString(settings.legalEntity()))
                    .append("clock", tx.clock()));
            return WriteResult.APPLIED;
        });
    }

    Optional<CatalogSettings> catalogSettings(UUID catalogId) {
        return transactions.read("catalog settings read", () -> Optional.ofNullable(collection(CATALOG).find(
                        byId(uuid(catalogId)).append("storageProfileId", new BsonDocument("$exists", BsonBoolean.TRUE))).first())
                .map(doc -> new CatalogSettings(uuid(doc.get("storageProfileId")), doc.getString("kmsKeySet").getValue(),
                        doc.getString("legalEntity").getValue())));
    }

    WriteResult putStorageProfile(StorageProfile profile, Change change) {
        return transactions.change(change, "storage profile put", tx -> {
            tx.find(STORAGE_PROFILE, byId(uuid(profile.id()))).ifPresent(tx::see);
            tx.collection(STORAGE_PROFILE).updateOne(tx.session, byId(uuid(profile.id())), new BsonDocument("$set",
                    new BsonDocument("name", new BsonString(profile.name()))
                            .append("kind", new BsonString(profile.kind().name()))
                            .append("endpoint", text(profile.endpoint().orElse(null)))
                            .append("stsRole", new BsonString(profile.stsRole()))
                            .append("allowedLocations", strings(profile.allowedLocations()))
                            .append("clock", tx.clock())), new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        });
    }

    Optional<StorageProfile> readStorageProfile(UUID profileId) {
        return transactions.read("storage profile read", () -> Optional.ofNullable(collection(STORAGE_PROFILE)
                .find(byId(uuid(profileId))).first()).map(doc -> new StorageProfile(uuid(doc.get("_id")),
                doc.getString("name").getValue(), StorageProfile.Kind.valueOf(doc.getString("kind").getValue()),
                Optional.ofNullable(string(doc, "endpoint")), doc.getString("stsRole").getValue(),
                doc.getArray("allowedLocations").stream().map(value -> value.asString().getValue()).toList())));
    }

    WriteResult putAttribute(GovernanceAttribute attribute, Change change) {
        return transactions.change(change, "attribute put", tx -> {
            Optional<BsonDocument> target = Guards.live(tx, attribute.target());
            if (target.isEmpty()) {
                return Guards.missing(attribute.target());
            }
            BsonDocument key = new BsonDocument("targetId", uuid(attribute.target().id()))
                    .append("kind", new BsonString(attribute.kind().name()));
            tx.find(ATTRIBUTE, key).ifPresent(tx::see);
            tx.see(target.get());
            tx.mark(Guards.collection(attribute.target().kind()), target.get());
            tx.collection(ATTRIBUTE).updateOne(tx.session, key, new BsonDocument("$set",
                    new BsonDocument("targetKind", new BsonString(attribute.target().kind().name()))
                            .append("value", new BsonString(attribute.value()))
                            .append("provenance", new BsonString(attribute.provenance()))
                            .append("setBy", new BsonString(attribute.setBy()))
                            .append("setAt", time(attribute.setAt()))
                            .append("clock", tx.clock())), new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        });
    }

    WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, Change change) {
        return transactions.change(change, "attribute remove", tx -> {
            Optional<BsonDocument> found = tx.find(ATTRIBUTE, new BsonDocument("targetId", uuid(target.id()))
                    .append("targetKind", new BsonString(target.kind().name())).append("kind", new BsonString(kind.name())));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.ATTRIBUTE);
            }
            remove(tx, ATTRIBUTE, found.get(), target);
            return WriteResult.APPLIED;
        });
    }

    List<GovernanceAttribute> attributesOf(Set<Securable> targets) {
        return transactions.read("attribute read", () -> collection(ATTRIBUTE).find(new BsonDocument("targetId", in(ids(targets))))
                        .map(doc -> new GovernanceAttribute(target(doc), GovernanceAttribute.Kind.valueOf(doc.getString("kind").getValue()),
                                doc.getString("value").getValue(), doc.getString("provenance").getValue(),
                                doc.getString("setBy").getValue(), instant(doc.get("setAt"))))
                        .into(new ArrayList<>()))
                .stream().filter(attribute -> targets.contains(attribute.target())).toList();
    }

    WriteResult placeHold(LegalHold hold, Change change) {
        return transactions.change(change, "hold place", tx -> {
            Optional<BsonDocument> target = Guards.live(tx, hold.target());
            if (target.isEmpty()) {
                return Guards.missing(hold.target());
            }
            tx.see(target.get());
            tx.mark(Guards.collection(hold.target().kind()), target.get());
            tx.collection(HOLD).insertOne(tx.session, byId(uuid(hold.id()))
                    .append("targetKind", new BsonString(hold.target().kind().name()))
                    .append("targetId", uuid(hold.target().id()))
                    .append("reason", new BsonString(hold.reason()))
                    .append("placedBy", new BsonString(hold.placedBy()))
                    .append("placedAt", time(hold.placedAt()))
                    .append("clock", tx.clock()));
            return WriteResult.APPLIED;
        });
    }

    WriteResult releaseHold(UUID holdId, Change change) {
        return transactions.change(change, "hold release", tx -> {
            Optional<BsonDocument> found = tx.find(HOLD, byId(uuid(holdId)));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.HOLD);
            }
            remove(tx, HOLD, found.get(), target(found.get()));
            return WriteResult.APPLIED;
        });
    }

    List<LegalHold> holdsOn(Set<Securable> targets) {
        return transactions.read("hold read", () -> collection(HOLD).find(new BsonDocument("targetId", in(ids(targets))))
                        .map(doc -> new LegalHold(uuid(doc.get("_id")), target(doc), doc.getString("reason").getValue(),
                                doc.getString("placedBy").getValue(), instant(doc.get("placedAt"))))
                        .into(new ArrayList<>()))
                .stream().filter(hold -> targets.contains(hold.target())).toList();
    }

    WriteResult putTrustedEngine(TrustedEngine engine, Change change) {
        return transactions.change(change, "trusted engine put", tx -> {
            BsonDocument key = engineKey(engine.issuer(), engine.clientId());
            tx.find(ENGINE, key).ifPresent(tx::see);
            tx.collection(ENGINE).updateOne(tx.session, key, new BsonDocument("$set",
                    new BsonDocument("endUserClaim", new BsonString(engine.endUserClaim()))
                            .append("flags", strings(engine.flags().stream().sorted().toList()))
                            .append("removed", BsonBoolean.FALSE)
                            .append("clock", tx.clock())), new UpdateOptions().upsert(true));
            return WriteResult.APPLIED;
        });
    }

    WriteResult removeTrustedEngine(String issuer, String clientId, Change change) {
        return transactions.change(change, "trusted engine remove", tx -> {
            Optional<BsonDocument> found = tx.find(ENGINE, engineKey(issuer, clientId).append("removed", NOT_REMOVED.get("removed")));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.ENGINE);
            }
            tx.see(found.get());
            tx.collection(ENGINE).updateOne(tx.session, byId(found.get().get("_id")), new BsonDocument("$set",
                    new BsonDocument("removed", BsonBoolean.TRUE).append("clock", tx.clock())));
            return WriteResult.APPLIED;
        });
    }

    Optional<TrustedEngine> findTrustedEngine(String issuer, String clientId) {
        return transactions.read("trusted engine read", () -> Optional.ofNullable(collection(ENGINE)
                .find(engineKey(issuer, clientId).append("removed", NOT_REMOVED.get("removed"))).first())
                .map(MongoGovernance::engine));
    }

    List<TrustedEngine> listTrustedEngines() {
        return transactions.read("trusted engine listing", () -> collection(ENGINE).find(NOT_REMOVED.clone())
                .sort(ascending("issuer", "clientId")).map(MongoGovernance::engine).into(new ArrayList<>()));
    }

    /** Deletes {@code doc}, marking the record it was on so a later put on that record stamps higher. */
    private static void remove(Tx tx, String collection, BsonDocument doc, Securable target) {
        Optional<BsonDocument> record = Guards.record(tx, target);
        record.ifPresent(tx::see);
        tx.see(doc);
        record.ifPresent(found -> tx.mark(Guards.collection(target.kind()), found));
        tx.collection(collection).deleteOne(tx.session, byId(doc.get("_id")));
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return transactions.database().getCollection(name, BsonDocument.class);
    }

    private static BsonDocument engineKey(String issuer, String clientId) {
        return new BsonDocument("issuer", new BsonString(issuer)).append("clientId", new BsonString(clientId));
    }

    private static TrustedEngine engine(BsonDocument doc) {
        return new TrustedEngine(doc.getString("issuer").getValue(), doc.getString("clientId").getValue(),
                doc.getString("endUserClaim").getValue(),
                Set.copyOf(doc.getArray("flags").stream().map(value -> value.asString().getValue()).toList()));
    }

    private static Securable target(BsonDocument doc) {
        return new Securable(Securable.Kind.valueOf(doc.getString("targetKind").getValue()), uuid(doc.get("targetId")));
    }

    private static List<BsonValue> ids(Set<Securable> targets) {
        return targets.stream().<BsonValue>map(target -> uuid(target.id())).toList();
    }

    private static BsonArray strings(List<String> values) {
        return new BsonArray(values.stream().<BsonValue>map(BsonString::new).toList());
    }
}
