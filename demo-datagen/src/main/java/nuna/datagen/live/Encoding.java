package nuna.datagen.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Kafka record's value: the row as schemaless JSON, in the forms the Iceberg sink converts without loss, and the table
 * it lands in under {@link Feed#ROUTE_FIELD}. Decimals go as plain strings (a JSON number would pass through a double),
 * dates as ISO strings, instants as epoch milliseconds.
 */
public final class Encoding {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Encoding() {
    }

    public static String json(Event event) {
        Map<String, Object> out = new LinkedHashMap<>();
        event.row().forEach((column, value) -> out.put(column, value(value)));
        out.put(Feed.ROUTE_FIELD, event.feed().lakeTable(Feed.Path.KAFKA));
        return JSON.writeValueAsString(out);
    }

    /** A SingleStore column's value: a list (financial_transactions' lots) as its JSON, everything else as it is. */
    static Object column(Object value) {
        return value instanceof List<?> ? JSON.writeValueAsString(value(value)) : value;
    }

    static Object value(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal d -> d.toPlainString();
            case LocalDate d -> d.toString();
            case Instant i -> i.toEpochMilli();
            case List<?> list -> list.stream().map(Encoding::value).toList();
            case Map<?, ?> map -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(k, value(v)));
                yield out;
            }
            default -> value;
        };
    }
}
