package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;
import org.junit.jupiter.api.Test;

/** The SQL demo.sh runs for the feeds' SingleStore side is what this module makes (regenerate it with Main sql). */
class SingleStoreTablesTest {

    private static final Kit KIT = Kit.load();
    private static final Path DIR = Path.of("..", "demo", "singlestore");

    @Test
    void theCommittedSqlIsTheGeneratedSql() throws Exception {
        assertThat(Files.readString(DIR.resolve("brokerage-feeds.sql")))
            .as("demo/singlestore/brokerage-feeds.sql: ./gradlew :demo-datagen:imageLib, then java -cp 'demo-datagen/build/image/lib/*' nuna.datagen.Main sql demo/singlestore")
            .isEqualTo(SingleStoreTables.tables(KIT));
        assertThat(Files.readString(DIR.resolve("brokerage-feeds-pipelines.sql")))
            .as("demo/singlestore/brokerage-feeds-pipelines.sql: ./gradlew :demo-datagen:imageLib, then java -cp 'demo-datagen/build/image/lib/*' nuna.datagen.Main sql demo/singlestore")
            .isEqualTo(SingleStoreTables.pipelines());
    }

    @Test
    void everyFeedButTheOrdersHasATableKeyedOnSeqAndEachKafkaFeedAnAppendOnlyChangeFeedInOnePipeline() {
        String tables = SingleStoreTables.tables(KIT);
        String pipelines = SingleStoreTables.pipelines();
        java.util.List<String> fed = new java.util.ArrayList<>();
        for (Feed f : Feed.values()) {
            if (f == Feed.ORDERS) {
                assertThat(tables).doesNotContain("CREATE TABLE IF NOT EXISTS orders ");
                continue;
            }
            assertThat(tables).contains("CREATE TABLE IF NOT EXISTS " + f.table + " (\n  seq BIGINT NOT NULL,");
            if (f.path == Feed.Path.KAFKA) {
                fed.add(f.table);
            }
        }
        assertThat(fed).hasSize(31);
        assertThat(pipelines.split("CREATE OR REPLACE PIPELINE ", -1)).hasSize(2);
        assertThat(pipelines).contains("CREATE OR REPLACE PIPELINE brokerage_world_to_lake AS LOAD DATA CONNECTOR 'ChangeFeedConnector'")
            .contains("\"singlestore.append-only\": \"true\"")
            .contains("\"singlestore.tables\": \"" + String.join(",", fed) + "\"")
            .doesNotContain("\"singlestore.table\":").doesNotContain("\"iceberg.table\":");
        assertThat(tables).contains("  lots JSON,").contains("  event_time DATETIME(6),")
            .contains("  locate_id TEXT,\n  PRIMARY KEY (seq)");
        assertThat(SingleStoreTables.columns(KIT).get(Feed.FINANCIAL_TRANSACTIONS)).last().isEqualTo("locate_id");
    }

    /**
     * The compliance feeds' copies go under brokerage.compliance.singlestore, which only compliance's readers read (item
     * 152); every other copy under brokerage.singlestore.
     */
    @Test
    void theComplianceCopiesGoUnderTheComplianceNamespace() {
        String pipelines = SingleStoreTables.pipelines();
        java.util.List<String> own = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"iceberg\\.namespace\\.(\\w+)\": \"([\\w.]+)\"")
            .matcher(pipelines);
        while (m.find()) {
            assertThat(m.group(2)).isEqualTo(Feed.COMPLIANCE_COPIES);
            own.add(m.group(1));
        }
        assertThat(own).containsExactlyElementsOf(java.util.Arrays.stream(Feed.values())
            .filter(f -> f.namespace.equals(Feed.COMPLIANCE)).map(f -> f.table).toList());
        assertThat(own).hasSize(6);
        assertThat(pipelines).contains("\"iceberg.namespace\": \"" + Feed.SINGLESTORE_COPIES + "\"");
    }

    /**
     * The order management system's tables are SingleStore's own: feeds.sql's pipeline brokerage_oms_to_lake copies
     * exactly them, append-only, into brokerage.trading, and the toy ops.orders and its change feed are gone (item 151).
     */
    @Test
    void feedsSqlCopiesTheOrderManagementSystemsTablesInOneAppendOnlyPipeline() throws Exception {
        String feeds = Files.readString(DIR.resolve("feeds.sql"));
        int at = feeds.indexOf("CREATE OR REPLACE PIPELINE " + SingleStoreTables.OMS_PIPELINE
            + " AS LOAD DATA CONNECTOR");
        assertThat(at).as("feeds.sql makes " + SingleStoreTables.OMS_PIPELINE).isPositive();
        String oms = feeds.substring(at, feeds.indexOf("SET committed_at", at));
        assertThat(oms).contains("\"singlestore.tables\": \"order_events,routes,executions\"")
            .contains("\"singlestore.tables\": \"" + SingleStoreTables.tableList(Feed.Path.SINGLESTORE) + "\"")
            .contains("\"singlestore.append-only\": \"true\"")
            .contains("\"iceberg.namespace\": \"brokerage.trading\"")
            .doesNotContain("iceberg.partition-by").doesNotContain("\"iceberg.table\"");
        assertThat(feeds).doesNotContain("CREATE TABLE ops.orders").doesNotContain("PIPELINE orders_to_lake AS");
    }
}
