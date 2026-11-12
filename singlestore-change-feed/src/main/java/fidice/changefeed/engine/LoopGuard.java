package fidice.changefeed.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Refuses to feed an Iceberg table that a SingleStore pipeline loads from. The pipeline would load the feed's own
 * commits back into SingleStore, and if it loads into the table the feed observes, every change would go round
 * forever.
 *
 * <p>Checked when the feed starts and every minute after, since a pipeline can be created at any time. A pipeline
 * sources the table if any string in its configuration names it ({@code namespace.table}): an Iceberg pipeline
 * names it in {@code table_id} (or as its source, {@code LOAD DATA S3 'ns.table'}). Matching any string errs on
 * the side of refusing.
 */
final class LoopGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoopGuard() {}

    static void check(Connection c, FeedConfig config) throws SQLException {
        String name = name(config.icebergTable());
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT DATABASE_NAME, PIPELINE_NAME, CONFIG_JSON FROM information_schema.PIPELINES")) {
            while (rs.next()) {
                if (names(rs.getString(3), name)) {
                    throw new IllegalStateException("pipeline " + rs.getString(1) + "." + rs.getString(2) + " loads "
                            + name + ", which this change feed writes: its rows would come back into SingleStore. "
                            + "Point the feed at another Iceberg table, or drop the pipeline.");
                }
            }
        }
    }

    static String name(TableIdentifier table) {
        return table.namespace().isEmpty() ? table.name() : String.join(".", table.namespace().levels()) + "." + table.name();
    }

    /** Whether any string value in the pipeline's configuration is the table's name, with or without quotes. */
    static boolean names(String configJson, String table) {
        if (configJson == null) {
            return false;
        }
        try {
            return names(JSON.readTree(configJson), table);
        } catch (IOException e) {
            return configJson.contains(table);
        }
    }

    private static boolean names(JsonNode node, String table) {
        if (node.isTextual()) {
            String value = node.asText().strip();
            // SingleStore keeps a pipeline's CONFIG as a JSON string inside CONFIG_JSON ("config": "{...}").
            if (value.startsWith("{")) {
                try {
                    return names(JSON.readTree(value), table);
                } catch (IOException e) {
                    return value.contains(table);
                }
            }
            return value.replace("`", "").replace("\"", "").equalsIgnoreCase(table);
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            if (names(it.next(), table)) {
                return true;
            }
        }
        return false;
    }
}
