package nuna.catalog.store.mongo;

import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.UuidRepresentation;

/** Document fields, filters and keyset pages shared by the store's operation groups. */
final class Docs {

    private Docs() {
    }

    static BsonBinary uuid(UUID id) {
        return new BsonBinary(id, UuidRepresentation.STANDARD);
    }

    static UUID uuid(BsonValue value) {
        return value.asBinary().asUuid(UuidRepresentation.STANDARD);
    }

    static BsonArray uuids(Collection<UUID> ids) {
        return new BsonArray(ids.stream().<BsonValue>map(Docs::uuid).toList());
    }

    static List<UUID> uuidList(BsonValue array) {
        return array.asArray().stream().map(Docs::uuid).toList();
    }

    /** Times are kept to the millisecond, BSON's precision. */
    static BsonDateTime time(Instant instant) {
        return new BsonDateTime(instant.toEpochMilli());
    }

    static Instant instant(BsonValue value) {
        return Instant.ofEpochMilli(value.asDateTime().getValue());
    }

    static Optional<Instant> optionalInstant(BsonDocument doc, String field) {
        BsonValue value = doc.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(instant(value));
    }

    static BsonValue text(String value) {
        return value == null ? BsonNull.VALUE : new BsonString(value);
    }

    /** The string at {@code field}, or null when it is absent or null. */
    static String string(BsonDocument doc, String field) {
        BsonValue value = doc.get(field);
        return value == null || value.isNull() ? null : value.asString().getValue();
    }

    static BsonDocument eq(String field, BsonValue value) {
        return new BsonDocument(field, value);
    }

    static BsonDocument in(List<? extends BsonValue> values) {
        return new BsonDocument("$in", new BsonArray(List.copyOf(values)));
    }

    static BsonDocument byId(BsonValue id) {
        return new BsonDocument("_id", id);
    }

    static BsonDocument keyFilter(PointerKey key) {
        return new BsonDocument("objectId", uuid(key.objectId())).append("branch", new BsonString(key.branch()));
    }

    /** A document's Lamport clock (storage-design 9 b); documents written before M2 have none, which is 0. */
    static long clock(BsonDocument doc) {
        BsonValue clock = doc.get("clock");
        return clock == null ? 0 : clock.asNumber().longValue();
    }

    static BsonInt64 int64(long value) {
        return new BsonInt64(value);
    }

    /** Ascending sort on {@code fields}, in order. */
    static BsonDocument ascending(String... fields) {
        BsonDocument sort = new BsonDocument();
        for (String field : fields) {
            sort.append(field, new BsonInt32(1));
        }
        return sort;
    }

    /**
     * The keyset condition "strictly after {@code values}" on the sort key {@code fields}: the
     * first field greater, or equal and the next greater, and so on.
     */
    static BsonDocument after(List<String> fields, List<BsonValue> values) {
        BsonArray branches = new BsonArray();
        for (int i = 0; i < fields.size(); i++) {
            BsonDocument branch = new BsonDocument();
            for (int j = 0; j < i; j++) {
                branch.append(fields.get(j), values.get(j));
            }
            branch.append(fields.get(i), new BsonDocument("$gt", values.get(i)));
            branches.add(branch);
        }
        return new BsonDocument("$or", branches);
    }

    /** Up to {@code request.size()} of {@code fetched} (which holds one more when a next page exists). */
    static <T> Page<T> page(List<T> fetched, PageRequest request, Function<T, String> token) {
        if (fetched.size() <= request.size()) {
            return new Page<>(fetched, Optional.empty());
        }
        List<T> items = fetched.subList(0, request.size());
        return new Page<>(items, Optional.of(token.apply(items.getLast())));
    }

    /** The token's parts, parsed; a token the store did not issue is an IllegalArgumentException. */
    static <T> Optional<T> after(PageRequest request, int parts, Function<List<String>, T> parse) {
        if (request.token().isEmpty()) {
            return Optional.empty();
        }
        List<String> decoded = PageToken.decode(request.token().get(), parts);
        try {
            return Optional.of(parse.apply(decoded));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a page token: " + request.token().get(), e);
        }
    }
}
