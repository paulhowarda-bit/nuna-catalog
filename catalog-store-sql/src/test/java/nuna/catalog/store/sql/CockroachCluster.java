package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Sql.await;
import static nuna.catalog.store.sql.Sql.execute;
import static nuna.catalog.store.sql.Sql.value;

import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.suite.UnsafeSetting;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * CockroachDB: three nodes, every range replicated three ways, insecure (the store's statements do
 * not depend on authentication). The nodes let a zone shrink ranges to 64 KiB, so the journal's
 * roll-over test can watch ranges split; nothing else changes a range size.
 */
final class CockroachCluster extends RaftCluster {

    static final int SQL_PORT = 26257;
    private static final Pattern INTERLOCK_KEY = Pattern.compile("unsafe_setting_interlock_key = '([^']+)'|key: (\\S+)");

    private final DockerImageName image = DockerImageName.parse(System.getProperty("cockroachdb.image"));
    private DataSource staging;

    CockroachCluster() {
        start();
    }

    @Override
    String alias(int node) {
        return "crdb-" + (node + 1);
    }

    @Override
    int sqlPort() {
        return SQL_PORT;
    }

    @Override
    String defaultDatabase() {
        return "defaultdb";
    }

    @Override
    void startNodes() {
        String join = "crdb-1:26257,crdb-2:26257,crdb-3:26257";
        for (int node = 0; node < NODES; node++) {
            GenericContainer<?> container = new GenericContainer<>(image)
                    .withNetwork(network)
                    .withNetworkAliases(alias(node))
                    .withEnv("COCKROACH_MIN_RANGE_MAX_BYTES", "65536")
                    .withCommand("start", "--insecure", "--join=" + join, "--advertise-addr=" + alias(node) + ":" + SQL_PORT,
                            "--listen-addr=:" + SQL_PORT, "--http-addr=:8080", "--cache=.08", "--max-sql-memory=.08")
                    .withExposedPorts(SQL_PORT)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
            container.start();
            nodes.add(container);
        }
        exec(nodes.getFirst(), "./cockroach", "init", "--insecure", "--host=" + alias(0) + ":" + SQL_PORT);
    }

    @Override
    DataSource dataSource(String host, int port, String database, int socketTimeoutSeconds) {
        return withConnectTimeout(Sql.dataSource(host, port, database, "root", socketTimeoutSeconds), socketTimeoutSeconds);
    }

    /** A historical read at least {@code lag} old, which a follower serves once its closed timestamp passes it. */
    @Override
    Optional<Pointer> followerRead(int node, PointerKey key, Duration lag) {
        try (Connection connection = direct(node, DATABASE).getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION AS OF SYSTEM TIME '-" + Math.max(lag.toMillis(), 1) + "ms'");
            }
            Optional<Pointer> pointer = Pointers.read(connection, key);
            connection.rollback();
            return pointer;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    Backend backend() {
        return Backend.COCKROACHDB;
    }

    @Override
    void awaitRejoined(int node) {
        awaitServing(node);
    }

    @Override
    public Set<String> findingCodes() {
        return Set.of("crdb.replicas", "crdb.fsync-off", "crdb.read-committed-off");
    }

    @Override
    public List<UnsafeSetting> unsafeSettings() {
        return List.of(
                new UnsafeSetting("the catalog's database zone keeps one replica", "crdb.replicas",
                        () -> selfCheckWithZone("DATABASE " + stagingName(), "num_replicas = 1")),
                new UnsafeSetting("the pointer table's zone keeps one replica", "crdb.replicas",
                        () -> selfCheckWithZone("TABLE pointer", "num_replicas = 1")),
                new UnsafeSetting("the journal table's zone keeps three replicas, one of them voting", "crdb.replicas",
                        () -> selfCheckWithZone("TABLE journal", "num_replicas = 3, num_voters = 1")),
                new UnsafeSetting("kv.raft_log.synchronization.unsafe.disabled = true", "crdb.fsync-off",
                        () -> selfCheckWithClusterSetting("kv.raft_log.synchronization.unsafe.disabled", "true")),
                new UnsafeSetting("sql.txn.read_committed_isolation.enabled = false: READ COMMITTED runs as SERIALIZABLE",
                        "crdb.read-committed-off",
                        () -> selfCheckWithClusterSetting("sql.txn.read_committed_isolation.enabled", "false")));
    }

    private String stagingName() {
        staging();
        return "staging";
    }

    /** A migrated database for zone changes, so the catalog's own keeps three voters throughout. */
    private DataSource staging() {
        if (staging == null) {
            execute(primaryDirect(), "CREATE DATABASE staging");
            staging = direct(gateway(), "staging");
            new SqlCatalogStore(staging).migrate();
        }
        return staging;
    }

    private List<SafetyFinding> selfCheckWithZone(String target, String zone) {
        DataSource database = staging();
        execute(database, "ALTER " + target + " CONFIGURE ZONE USING " + zone);
        try {
            return new SqlCatalogStore(database).selfCheck();
        } finally {
            execute(database, "ALTER " + target + " CONFIGURE ZONE DISCARD");
        }
    }

    /** Changes a cluster setting, waits until the gateway sees it, self-checks, and sets it back. */
    private List<SafetyFinding> selfCheckWithClusterSetting(String setting, String unsafe) {
        String original = value(primaryDirect(), "SHOW CLUSTER SETTING " + setting);
        setClusterSetting(setting, unsafe);
        try {
            awaitSetting(setting, unsafe);
            if (setting.equals("sql.txn.read_committed_isolation.enabled")) {
                // The setting is read as a transaction begins; wait until a new one runs as SERIALIZABLE.
                await(SETTLE, () -> isolationOfReadCommitted().equals("serializable"), "READ COMMITTED to run as SERIALIZABLE");
            }
            return new SqlCatalogStore(primaryDirect()).selfCheck();
        } finally {
            setClusterSetting(setting, original);
            awaitSetting(setting, original);
            if (setting.equals("sql.txn.read_committed_isolation.enabled")) {
                await(SETTLE, () -> isolationOfReadCommitted().equals("read committed"), "READ COMMITTED to return");
            }
        }
    }

    private String isolationOfReadCommitted() {
        try (Connection connection = primaryDirect().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            String isolation = Jdbc.first(connection, "SHOW transaction_isolation", row -> row.getString(1)).orElseThrow();
            connection.rollback();
            return isolation;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitSetting(String setting, String expected) {
        await(SETTLE, () -> normalized(value(primaryDirect(), "SHOW CLUSTER SETTING " + setting))
                .equals(normalized(expected)), setting + " to read " + expected);
    }

    private static String normalized(String value) {
        return switch (value) {
            case "t" -> "true";
            case "f" -> "false";
            default -> value;
        };
    }

    /**
     * SET CLUSTER SETTING; an unsafe setting first answers with an interlock key, which the same
     * session sets before trying again.
     */
    private void setClusterSetting(String setting, String value) {
        try (Connection connection = primaryDirect().getConnection(); Statement statement = connection.createStatement()) {
            String set = "SET CLUSTER SETTING " + setting + " = " + value;
            try {
                statement.execute(set);
            } catch (SQLException refused) {
                String text = refused.getMessage() + " " + (refused instanceof org.postgresql.util.PSQLException psql
                        && psql.getServerErrorMessage() != null ? psql.getServerErrorMessage().getDetail() : "");
                Matcher key = INTERLOCK_KEY.matcher(text);
                if (!key.find()) {
                    throw refused;
                }
                statement.execute("SET unsafe_setting_interlock_key = '" + (key.group(1) != null ? key.group(1)
                        : key.group(2)) + "'");
                statement.execute(set);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code command} in {@code container} and fails unless it exits 0. */
    static String exec(GenericContainer<?> container, String... command) {
        try {
            Container.ExecResult result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " exited " + result.getExitCode() + ": "
                        + result.getStdout() + result.getStderr());
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
