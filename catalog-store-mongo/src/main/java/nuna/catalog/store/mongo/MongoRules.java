package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.byId;
import static nuna.catalog.store.mongo.Docs.instant;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.ATTRIBUTE;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.RULE;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Row filters and column masks (D20, backlog 115), one document per table and group. MongoDB has no locks: a put and
 * a write that takes the table's row/column-policy flag away both mark the table's document, so two that run at once conflict
 * and one runs again, reading what the other committed. Masks are kept as a list of {column, expression}, since a
 * column's name may hold a dot, which a field name may not.
 */
final class MongoRules {

    private final Transactions transactions;

    MongoRules(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(RowColumnRule rule, OptionalLong expected, String setBy, Change change) {
        return transactions.change(change, "rule put", tx -> {
            Optional<BsonDocument> table = activeTable(tx, rule.tableId());
            if (table.isEmpty() || !uuid(table.get().get("catalogId")).equals(rule.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            tx.see(table.get());
            if (!tx.exists(ATTRIBUTE, new BsonDocument("targetId", uuid(rule.tableId()))
                    .append("kind", new BsonString(GovernanceAttribute.Kind.ROW_COLUMN_POLICY.name()))
                    .append("value", new BsonString("true")))) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_FLAGGED);
            }
            BsonDocument key = key(rule.tableId(), rule.group());
            Optional<BsonDocument> was = tx.find(RULE, key);
            was.ifPresent(tx::see);
            long version = was.map(MongoRules::version).orElse(0L);
            if (expected.isPresent() && expected.getAsLong() != version) {
                return new WriteResult.VersionMismatch(version);
            }
            if (was.isPresent() && rule(was.get()).rule().equals(rule)) {
                return WriteResult.UNCHANGED;
            }
            tx.mark(OBJECT, table.get());
            was.ifPresent(doc -> tx.collection(RULE).deleteOne(tx.session, byId(doc.get("_id"))));
            BsonArray masks = new BsonArray();
            rule.columnMasks().forEach((column, expression) -> masks.add(new BsonDocument("column",
                    new BsonString(column)).append("expression", new BsonString(expression))));
            tx.collection(RULE).insertOne(tx.session, key.clone()
                    .append("catalogId", uuid(rule.catalogId()))
                    .append("rowFilter", rule.rowFilter().<BsonValue>map(BsonString::new).orElse(BsonNull.VALUE))
                    .append("columnMasks", masks)
                    .append("setBy", new BsonString(setBy))
                    .append("setAt", time(change.at()))
                    .append("version", new BsonInt64(RowColumnRule.nextVersion(
                            was.isPresent() ? OptionalLong.of(version) : OptionalLong.empty(), change.at())))
                    .append("clock", tx.clock()));
            return WriteResult.APPLIED;
        });
    }

    WriteResult remove(UUID catalogId, UUID tableId, String group, OptionalLong expected, Change change) {
        return transactions.change(change, "rule remove", tx -> {
            Optional<BsonDocument> found = tx.find(RULE, key(tableId, group).append("catalogId", uuid(catalogId)));
            if (found.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.RULE);
            }
            tx.see(found.get());
            if (expected.isPresent() && expected.getAsLong() != version(found.get())) {
                return new WriteResult.VersionMismatch(version(found.get()));
            }
            tx.collection(RULE).deleteOne(tx.session, byId(found.get().get("_id")));
            return WriteResult.APPLIED;
        });
    }

    List<RowColumnRule.Stored> on(UUID tableId) {
        return transactions.read("rule read", () -> transactions.database().getCollection(RULE, BsonDocument.class).find(new BsonDocument("tableId", uuid(tableId)))
                .sort(new BsonDocument("group", new BsonInt32(1)))
                .map(MongoRules::rule).into(new ArrayList<>()));
    }

    /**
     * Inside a write that would take the row/column-policy flag from a table, which marks the table's document as a
     * put does: {@code Refused(HAS_RULES)} if the table has rules, else empty.
     */
    static Optional<WriteResult> flagRemovalRefused(Tx tx, UUID tableId) {
        return tx.exists(RULE, new BsonDocument("tableId", uuid(tableId)))
                ? Optional.of(new WriteResult.Refused(WriteResult.Reason.HAS_RULES)) : Optional.empty();
    }

    private static Optional<BsonDocument> activeTable(Tx tx, UUID tableId) {
        return tx.find(OBJECT, byId(uuid(tableId)).append("kind", new BsonString("TABLE"))
                .append("state", new BsonString(CatalogObject.State.ACTIVE.name())));
    }

    private static BsonDocument key(UUID tableId, String group) {
        return new BsonDocument("tableId", uuid(tableId)).append("group", new BsonString(group));
    }

    private static RowColumnRule.Stored rule(BsonDocument doc) {
        Map<String, String> masks = new TreeMap<>();
        for (BsonValue mask : doc.getArray("columnMasks")) {
            masks.put(mask.asDocument().getString("column").getValue(),
                    mask.asDocument().getString("expression").getValue());
        }
        BsonValue filter = doc.get("rowFilter");
        RowColumnRule rule = RowColumnRule.of(uuid(doc.get("catalogId")), uuid(doc.get("tableId")),
                doc.getString("group").getValue(),
                filter == null || filter.isNull() ? Optional.empty() : Optional.of(filter.asString().getValue()), masks);
        return new RowColumnRule.Stored(rule, doc.getString("setBy").getValue(), instant(doc.get("setAt")),
                version(doc));
    }

    /** The rule's version; 1 for a rule a server of the release before versions wrote (backlog 244). */
    private static long version(BsonDocument doc) {
        BsonValue version = doc.get("version");
        return version == null || version.isNull() ? 1 : version.asNumber().longValue();
    }
}
