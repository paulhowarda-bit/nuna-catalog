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
    void everyFeedButTheOrdersHasATableKeyedOnSeqAndAnAppendOnlyChangeFeed() {
        String tables = SingleStoreTables.tables(KIT);
        String pipelines = SingleStoreTables.pipelines();
        for (Feed f : Feed.values()) {
            if (f == Feed.ORDERS) {
                assertThat(tables).doesNotContain("CREATE TABLE IF NOT EXISTS orders ");
                continue;
            }
            assertThat(tables).contains("CREATE TABLE IF NOT EXISTS " + f.table + " (\n  seq BIGINT NOT NULL,");
            assertThat(pipelines).contains("CREATE OR REPLACE PIPELINE brokerage_" + f.table + "_to_lake ")
                .contains("\"singlestore.table\": \"" + f.table + "\", \"singlestore.append-only\": \"true\"");
        }
        assertThat(tables).contains("  lots JSON,").contains("  event_time DATETIME(6),")
            .contains("  locate_id TEXT,\n  PRIMARY KEY (seq)");
        assertThat(SingleStoreTables.columns(KIT).get(Feed.FINANCIAL_TRANSACTIONS)).last().isEqualTo("locate_id");
    }
}
