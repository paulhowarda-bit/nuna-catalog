package fidice.changefeed.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The change feed against a real SingleStore (the dev image, 10.9.0), writing to an in-memory Iceberg catalog: the
 * snapshot, changes, a restart that resumes from the offsets in Iceberg, and a restart after SingleStore has dropped
 * the log the copy needed. Each step checks the copy holds exactly SingleStore's rows. Last, a restart from an
 * earlier stored receipt sends the later receipts again, as they were.
 */
@Tag("heavy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChangeFeedTest {

    static final TableIdentifier COPY = TableIdentifier.of(Namespace.of("it", "ops"), "orders");
    static final Duration WAIT = Duration.ofSeconds(90);

    static GenericContainer<?> singlestore;
    static String url;
    static ChangeFeed feed;
    /** Every receipt the feed has returned, as SingleStore would have stored them. */
    static final List<Map<String, Object>> stored = new ArrayList<>();

    @BeforeAll
    static void start() throws SQLException {
        singlestore = new GenericContainer<>(DockerImageName.parse("ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88"))
                .withEnv("ROOT_PASSWORD", "it-root")
                .withEnv("SINGLESTORE_SET_GLOBAL_ENABLE_OBSERVE_QUERIES", "1")
                .withExposedPorts(3306)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(3L << 30))
                .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(5)));
        singlestore.start();
        url = "jdbc:mariadb://" + singlestore.getHost() + ":" + singlestore.getMappedPort(3306) + "/";
        sql("CREATE DATABASE ops",
                "CREATE TABLE ops.orders (order_id BIGINT NOT NULL, symbol VARCHAR(12) NOT NULL, qty INT NOT NULL, "
                        + "price DECIMAL(18,6), status VARCHAR(16) NOT NULL, PRIMARY KEY (order_id), SHARD KEY (order_id))",
                "INSERT INTO ops.orders VALUES (1,'SAP',100,182.1,'NEW'),(2,'SIE',50,171,'NEW'),(3,'ALV',10,NULL,'NEW')");
    }

    @AfterAll
    static void stop() {
        if (feed != null) {
            feed.close();
        }
        if (singlestore != null) {
            singlestore.stop();
        }
    }

    @Test
    @Order(1)
    void copiesTheTableThenItsChanges() throws SQLException {
        feed = new ChangeFeed(props(), lastStored());
        List<Map<String, Object>> receipts = pollUntilCopied();
        assertThat(receipts).first().satisfies(r -> assertThat(r).containsEntry("kind", "snapshot"));

        sql("UPDATE ops.orders SET status = 'FILLED' WHERE order_id = 1",
                "DELETE FROM ops.orders WHERE order_id = 2",
                "INSERT INTO ops.orders VALUES (4,'DTE',2000,23.1,'NEW')",
                "UPDATE ops.orders SET qty = 20 WHERE order_id = 4");
        receipts = pollUntilCopied();

        assertThat(receipts).isNotEmpty().allSatisfy(r -> assertThat(r).containsEntry("kind", "changes"));
    }

    @Test
    @Order(2)
    void aRestartResumesFromTheOffsetsInIceberg() throws SQLException {
        feed.close();
        sql("UPDATE ops.orders SET price = 11.5 WHERE order_id = 3",
                "INSERT INTO ops.orders VALUES (5,'BAS',300,47.2,'NEW')");
        String[] before = offsets();

        feed = new ChangeFeed(props(), lastStored());
        List<Map<String, Object>> receipts = pollUntilCopied();

        assertThat(receipts).isNotEmpty().allSatisfy(r -> assertThat(r).containsEntry("kind", "changes"));
        assertThat(table().history()).hasSizeGreaterThan(2);
        assertThat(offsets()).isNotEqualTo(before);
    }

    @Test
    @Order(3)
    void aRestartAfterSingleStoreDroppedTheLogTakesANewSnapshotAndRecordsTheGap() throws SQLException {
        feed.close();
        String[] before = offsets();
        // snapshots_to_keep is 2: four snapshots move the oldest one SingleStore keeps past the copy's offsets.
        sql("DELETE FROM ops.orders WHERE order_id = 1", "SNAPSHOT DATABASE ops",
                "INSERT INTO ops.orders VALUES (6,'VOW3',15,101.5,'NEW')", "SNAPSHOT DATABASE ops",
                "UPDATE ops.orders SET status = 'CANCELLED' WHERE order_id = 5", "SNAPSHOT DATABASE ops",
                "SNAPSHOT DATABASE ops");

        feed = new ChangeFeed(props(), lastStored());
        List<Map<String, Object>> receipts = pollUntilCopied();

        assertThat(receipts).first().satisfies(r -> assertThat(r)
                .containsEntry("kind", "resnapshot")
                .containsEntry("gap_after", String.join(",", before)));
        assertThat(table().currentSnapshot().summary().get(IcebergCopy.GAP_AFTER)).isNotNull();
    }

    @Test
    @Order(4)
    void aRestartFromAnEarlierStoredReceiptSendsTheLaterOnesAgain() {
        feed.close();
        // SingleStore stored the first receipt and failed every batch after it.
        feed = new ChangeFeed(props(), (Long) stored.getFirst().get("snapshot_id"));

        assertThat(feed.apply(0L)).isEqualTo(stored.subList(1, stored.size()));
        assertThat(feed.apply(0L)).isEmpty();
    }

    private static Map<String, String> props() {
        Map<String, String> props = new HashMap<>();
        props.put("singlestore.url", url);
        props.put("singlestore.user", "root");
        props.put("singlestore.password", "it-root");
        props.put("singlestore.database", "ops");
        props.put("singlestore.table", "orders");
        props.put("iceberg.catalog.catalog-impl", SharedCatalog.class.getName());
        props.put("iceberg.namespace", "it.ops");
        props.put("iceberg.table", "orders");
        props.put("iceberg.partition-by", "bucket(order_id, 4)");
        props.put("commit.interval.ms", "0");
        return props;
    }

    /** Polls the feed until the copy holds SingleStore's rows; returns the receipts of the commits on the way. */
    private static List<Map<String, Object>> pollUntilCopied() throws SQLException {
        Map<Long, List<Object>> expected = singleStoreRows();
        List<Map<String, Object>> receipts = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            receipts.addAll(feed.apply(500L));
            if (!receipts.isEmpty() && Fixtures.rows(table()).equals(expected)) {
                stored.addAll(receipts);
                return receipts;
            }
            if (System.nanoTime() > deadline) {
                assertThat(Fixtures.rows(table())).as("the Iceberg copy after %s", receipts).isEqualTo(expected);
            }
        }
    }

    private static Long lastStored() {
        return stored.isEmpty() ? null : (Long) stored.getLast().get("snapshot_id");
    }

    private static Table table() {
        return SharedCatalog.CATALOG.loadTable(COPY);
    }

    private static String[] offsets() {
        return table().currentSnapshot().summary().get(IcebergCopy.OFFSETS).split(",");
    }

    private static Map<Long, List<Object>> singleStoreRows() throws SQLException {
        Map<Long, List<Object>> rows = new TreeMap<>();
        try (Connection c = connect(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT order_id, symbol, qty, price, status FROM ops.orders")) {
            while (rs.next()) {
                BigDecimal price = rs.getBigDecimal(4);
                rows.put(rs.getLong(1), Fixtures.values(rs.getString(2), rs.getInt(3), price == null ? null : price.toPlainString(), rs.getString(5)));
            }
        }
        return rows;
    }

    private static void sql(String... statements) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "root", "it-root");
    }
}
