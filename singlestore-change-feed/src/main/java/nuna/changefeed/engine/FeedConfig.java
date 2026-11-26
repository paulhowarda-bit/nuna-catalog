package nuna.changefeed.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * The pipeline's CONFIG and CREDENTIALS, merged by SingleStore into one map.
 *
 * <ul>
 *   <li>{@code singlestore.url}, {@code singlestore.user}, {@code singlestore.password}: where the change feed is
 *       read. The connector runs on a leaf, so the URL names an aggregator.
 *   <li>{@code singlestore.database}, {@code singlestore.table}: the table whose changes are sent. A pipeline may
 *       give {@code singlestore.tables} instead, a list of tables: its task then runs a feed for each, with
 *       {@code singlestore.table} and {@code iceberg.table} set to the table's name (backlog 238).
 *   <li>{@code iceberg.catalog.*}: the Iceberg catalog's properties, without the prefix. A REST catalog unless
 *       {@code type} or {@code catalog-impl} says otherwise: {@code uri}, {@code warehouse}, {@code token},
 *       {@code header.X-Iceberg-Access-Delegation}, ...
 *   <li>{@code iceberg.namespace} (levels separated by dots) and {@code iceberg.table}: the table written.
 *   <li>{@code iceberg.format-version} (default 2) and {@code iceberg.partition-by} (default none): used only
 *       when the connector creates the table.
 *   <li>{@code commit.interval.ms} (default 5000): how long changes collect before a commit.
 *   <li>{@code singlestore.append-only} ({@code true} or {@code false}, default false): for a table that is only
 *       ever inserted into. Its primary key need only contain its shard key, not equal it, and the first UPDATE or
 *       DELETE stops the feed before that transaction is applied (D35, amended 2026-11-20).
 * </ul>
 */
public record FeedConfig(
        String jdbcUrl,
        String user,
        String password,
        String database,
        String table,
        Map<String, String> catalogProperties,
        TableIdentifier icebergTable,
        int formatVersion,
        String partitionBy,
        long commitIntervalMs,
        boolean appendOnly) {

    static final String CATALOG_PREFIX = "iceberg.catalog.";

    public static FeedConfig from(Map<String, String> props) {
        Map<String, String> catalog = new HashMap<>();
        props.forEach((key, value) -> {
            if (key.startsWith(CATALOG_PREFIX)) {
                catalog.put(key.substring(CATALOG_PREFIX.length()), value);
            }
        });
        if (!catalog.containsKey("type") && !catalog.containsKey("catalog-impl")) {
            catalog.put("type", "rest");
            if (!catalog.containsKey("uri")) {
                throw new IllegalArgumentException("missing iceberg.catalog.uri");
            }
        }
        String[] levels = required(props, "iceberg.namespace").split("\\.");
        return new FeedConfig(
                required(props, "singlestore.url"),
                required(props, "singlestore.user"),
                props.getOrDefault("singlestore.password", ""),
                required(props, "singlestore.database"),
                required(props, "singlestore.table"),
                Map.copyOf(catalog),
                TableIdentifier.of(Namespace.of(Arrays.copyOf(levels, levels.length)), required(props, "iceberg.table")),
                Integer.parseInt(props.getOrDefault("iceberg.format-version", "2")),
                props.getOrDefault("iceberg.partition-by", "").strip(),
                Long.parseLong(props.getOrDefault("commit.interval.ms", "5000")),
                flag(props, "singlestore.append-only"));
    }

    /** A connection to SingleStore. The driver is called directly: SingleStore's extractor has its own JDBC drivers. */
    Connection connect() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection c = new org.mariadb.jdbc.Driver().connect(jdbcUrl, props);
        if (c == null) {
            throw new SQLException("singlestore.url is not a MariaDB JDBC URL: " + jdbcUrl);
        }
        return c;
    }

    /** {@code database.table} in SingleStore. */
    public String source() {
        return database + "." + table;
    }

    private static boolean flag(Map<String, String> props, String key) {
        String value = props.getOrDefault(key, "false").strip();
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " is " + value + "; it takes true or false");
        };
    }

    private static String required(Map<String, String> props, String key) {
        String value = props.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value.strip();
    }
}
