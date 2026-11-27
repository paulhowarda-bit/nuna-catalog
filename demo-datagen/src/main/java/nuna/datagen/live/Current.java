package nuna.datagen.live;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The current-state views over the back office's histories (item 152): each versioned feed ({@link LakeSchemas#VERSIONED}:
 * a party, a suitability profile, a trusted contact, a payment, an AML alert or case, a surveillance alert, a complaint)
 * sends a new row for each change, with its version, so its table is the history and {@code <table>_current}, in the
 * same namespace, holds each key's latest version. Like the reconciliation views ({@link Recon}), each reads every lake
 * table its feed has, through Kafka and through SingleStore, each column cast to its type in the Kafka table, and its SQL
 * is portable, so the fast build runs it in H2.
 */
final class Current {

    /** One view: its namespace, name, comment, columns and Trino SQL. */
    record View(Namespace namespace, String name, String comment, Schema schema, String sql) {}

    private Current() {
    }

    /**
     * The views of the versioned feeds that have a lake table.
     *
     * @param catalog the engine's name for the warehouse ({@code lakehouse} in Trino)
     * @param exists  whether a lake table, by its dotted name, exists
     */
    static List<View> views(String catalog, Predicate<String> exists) {
        List<View> views = new ArrayList<>();
        for (Feed f : LakeSchemas.VERSIONED) {
            Schema schema = LakeSchemas.schema(f);
            String key = quote(LakeSchemas.key(f));
            String casts = schema.columns().stream()
                .map(c -> "CAST(" + quote(c.name()) + " AS " + trino(c.type()) + ") AS " + quote(c.name()))
                .collect(Collectors.joining(", "));
            List<String> selects = new ArrayList<>();
            for (Feed.Path p : Feed.Path.values()) {
                String table = f.lakeTable(p);
                if (exists.test(table)) {
                    int dot = table.lastIndexOf('.');
                    selects.add("SELECT " + casts + " FROM " + catalog + ".\"" + table.substring(0, dot) + "\"."
                        + table.substring(dot + 1));
                }
            }
            if (selects.isEmpty()) {
                continue;
            }
            String columns = schema.columns().stream().map(c -> "h." + quote(c.name()))
                .collect(Collectors.joining(", "));
            String sql = "WITH h AS (" + String.join(" UNION ALL ", selects) + ")\nSELECT " + columns
                + "\nFROM h JOIN (SELECT " + key + ", MAX(\"version\") AS latest FROM h GROUP BY " + key + ") l"
                + " ON h." + key + " = l." + key + " AND h.\"version\" = l.latest";
            views.add(new View(Namespace.of(f.namespace.split("\\.")), f.table + "_current",
                "Each " + LakeSchemas.key(f) + "'s latest version in " + f.table + ", which keeps every version"
                    + " (item 152).", schema, sql));
        }
        return views;
    }

    private static String quote(String name) {
        return "\"" + name + "\"";
    }

    /** A column's type in Trino's SQL, which H2 also reads. */
    static String trino(Type type) {
        return switch (type.typeId()) {
            case LONG -> "bigint";
            case INTEGER -> "integer";
            case STRING -> "varchar";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case TIMESTAMP -> ((Types.TimestampType) type).shouldAdjustToUTC() ? "timestamp(6) with time zone"
                : "timestamp(6)";
            case DECIMAL -> "decimal(" + ((Types.DecimalType) type).precision() + ", "
                + ((Types.DecimalType) type).scale() + ")";
            default -> throw new IllegalArgumentException("no Trino type for " + type);
        };
    }
}
