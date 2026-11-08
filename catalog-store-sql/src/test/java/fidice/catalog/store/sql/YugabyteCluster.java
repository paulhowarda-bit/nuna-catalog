package fidice.catalog.store.sql;

import static fidice.catalog.store.sql.Sql.execute;

import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.SafetyFinding;
import fidice.catalog.store.suite.UnsafeSetting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * YugabyteDB: three yugabyted nodes (a master and a tablet server each), every tablet replicated
 * three ways. The tablet servers run with the settings section 5.5 requires: READ COMMITTED,
 * sequence cache 1, and the WAL synced on every write. One tablet per table keeps the many schema
 * steps of the suite quick. Before the suite starts, every tablet server knows all three masters
 * and every tablet has three replicas; yugabyted reaches neither by itself in time. A single node
 * with the defaults stages the unsafe settings; it also splits tablets above 64 KiB, for the
 * journal's roll-over test.
 */
final class YugabyteCluster extends RaftCluster {

    static final int SQL_PORT = 5433;
    static final String SAFE_FLAGS = "yb_enable_read_committed_isolation=true,ysql_sequence_cache_minval=1,"
            + "durable_wal_write=true,ysql_num_tablets=1";
    /** Every phase of automatic tablet splitting splits a tablet above 64 KiB. */
    static final String SMALL_SPLIT_FLAGS = "tablet_split_low_phase_size_threshold_bytes=65536,"
            + "tablet_split_high_phase_size_threshold_bytes=65536,tablet_split_low_phase_shard_count_per_node=16,"
            + "tablet_split_high_phase_shard_count_per_node=64";

    private final DockerImageName image = DockerImageName.parse(System.getProperty("yugabytedb.image"));
    private GenericContainer<?> singleNode;
    private DataSource singleNodeDatabase;
    private int singleNodeDatabases;

    YugabyteCluster() {
        start();
    }

    @Override
    String alias(int node) {
        return "yb-" + (node + 1);
    }

    @Override
    int sqlPort() {
        return SQL_PORT;
    }

    @Override
    String defaultDatabase() {
        return "yugabyte";
    }

    @Override
    void startNodes() {
        for (int node = 0; node < NODES; node++) {
            GenericContainer<?> container = yugabyted(network, alias(node), node == 0 ? null : alias(0), SAFE_FLAGS, null);
            container.start();
            nodes.add(container);
        }
        for (int node = 0; node < NODES; node++) {
            restartWithEveryMaster(node);
        }
        awaitFullyReplicated();
    }

    /**
     * yugabyted raises the replication factor to 3 when the third node joins. The tablets made
     * before that, among them the transaction status tablets, have one replica on the first node,
     * and the load balancer copies them over minutes; a fault before it finishes can take a
     * transaction status tablet's only replica, and the survivors commit nothing.
     */
    private void awaitFullyReplicated() {
        Sql.await(Duration.ofMinutes(10), () -> shell(nodes.get(0), "curl -sL --max-time 10 http://" + alias(0)
                        + ":7000/api/v1/tablet-under-replication").contains("\"underreplicated_tablets\":[]"),
                "every tablet to have three replicas");
    }

    /**
     * yugabyted starts a tablet server knowing two masters, its own and the one it joined. Within a
     * minute it learns the third and sets the flag, but the running server's client keeps the two:
     * when the node holding the master leader dies and the third master takes over, that server can
     * reach no leader and serves nothing (section 5.5). yugabyted starts a tablet server it finds
     * dead with every master it knows, so the harness waits until it knows all three, then kills
     * the tablet server and waits until yugabyted has restarted it with them.
     */
    private void restartWithEveryMaster(int node) {
        GenericContainer<?> container = nodes.get(node);
        Sql.await(Duration.ofSeconds(150), () -> knowsEveryMaster(shell(container, "cat /root/var/conf/yugabyted.conf"),
                CURRENT_MASTERS), "yugabyted on " + alias(node) + " to learn every master");
        shell(container, "pkill -f bin/[y]b-tserver");
        Sql.await(Duration.ofSeconds(120), () -> knowsEveryMaster(shell(container, "ps -eo args"), TSERVER_MASTERS),
                "the tablet server on " + alias(node) + " to restart with every master");
    }

    private static final Pattern CURRENT_MASTERS = Pattern.compile("\"current_masters\":\\s*\"([^\"]*)\"");
    private static final Pattern TSERVER_MASTERS = Pattern.compile("bin/yb-tserver .*--tserver_master_addrs=(\\S+)");

    private boolean knowsEveryMaster(String text, Pattern masters) {
        Matcher found = masters.matcher(text);
        if (!found.find()) {
            return false;
        }
        Set<String> known = Set.of(found.group(1).split(","));
        return IntStream.range(0, NODES).allMatch(node -> known.contains(alias(node) + ":7100"));
    }

    private static String shell(GenericContainer<?> container, String command) {
        try {
            return container.execInContainer("sh", "-c", command).getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private GenericContainer<?> yugabyted(Network network, String alias, String join, String tserverFlags,
            String masterFlags) {
        StringBuilder command = new StringBuilder("bin/yugabyted start --background=false --ui=false --advertise_address=")
                .append(alias);
        if (join != null) {
            command.append(" --join=").append(join);
        }
        command.append(" --tserver_flags=").append(tserverFlags);
        if (masterFlags != null) {
            command.append(" --master_flags=").append(masterFlags);
        }
        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withCommand(command.toString().split(" "))
                .withExposedPorts(SQL_PORT)
                .waitingFor(Wait.forLogMessage("(?s).*YugabyteDB Started.*", 1).withStartupTimeout(Duration.ofMinutes(4)));
    }

    @Override
    DataSource dataSource(String host, int port, String database, int socketTimeoutSeconds) {
        return withConnectTimeout(Sql.dataSource(host, port, database, "yugabyte", socketTimeoutSeconds),
                socketTimeoutSeconds);
    }

    /** A read-only transaction that reads from the nearest replica, {@code lag} in the past. */
    @Override
    Optional<Pointer> followerRead(int node, PointerKey key, Duration lag) {
        try (Connection connection = direct(node, DATABASE).getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET yb_read_from_followers = true");
                statement.execute("SET yb_follower_read_staleness_ms = " + Math.max(lag.toMillis(), 1_000));
            }
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            Optional<Pointer> pointer = Pointers.read(connection, key);
            connection.rollback();
            return pointer;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    Backend backend() {
        return Backend.YUGABYTEDB;
    }

    @Override
    void awaitRejoined(int node) {
        awaitServing(node);
    }

    @Override
    public Set<String> findingCodes() {
        return Set.of("yb.replicas", "yb.read-committed-off", "yb.sequence-cache");
    }

    @Override
    public List<UnsafeSetting> unsafeSettings() {
        return List.of(
                new UnsafeSetting("a single node: every tablet has one replica", "yb.replicas", this::selfCheckOnSingleNode),
                new UnsafeSetting("yb_enable_read_committed_isolation=false: READ COMMITTED runs as snapshot isolation",
                        "yb.read-committed-off", this::selfCheckOnSingleNode),
                new UnsafeSetting("ysql_sequence_cache_minval at its default, 100 per connection", "yb.sequence-cache",
                        this::selfCheckOnSingleNode));
    }

    /** A single node with the defaults, but READ COMMITTED turned off, and the catalog migrated on it. */
    private List<SafetyFinding> selfCheckOnSingleNode() {
        if (singleNodeDatabase == null) {
            singleNodeDatabase = Sql.dataSource(startSingleNode(), singleNode.getMappedPort(SQL_PORT), "yugabyte",
                    "yugabyte", ADMIN_SOCKET_TIMEOUT_SECONDS);
            Sql.await(SETTLE, () -> {
                try {
                    new SqlCatalogStore(singleNodeDatabase).migrate();
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }, "the single node to take the catalog's schema");
        }
        return new SqlCatalogStore(singleNodeDatabase).selfCheck();
    }

    /** A fresh, empty database on the single node, whose tablets split above 64 KiB. */
    DataSource singleNodeFreshDatabase() {
        String host = startSingleNode();
        String database = "single_" + ++singleNodeDatabases;
        execute(Sql.dataSource(host, singleNode.getMappedPort(SQL_PORT), "yugabyte", "yugabyte",
                ADMIN_SOCKET_TIMEOUT_SECONDS), "CREATE DATABASE " + database);
        return Sql.dataSource(host, singleNode.getMappedPort(SQL_PORT), database, "yugabyte", ADMIN_SOCKET_TIMEOUT_SECONDS);
    }

    /** Starts the single node once; returns its host. */
    private String startSingleNode() {
        if (singleNode == null) {
            singleNode = yugabyted(Network.newNetwork(), "yb-single", null, "yb_enable_read_committed_isolation=false,"
                    + "ysql_num_tablets=1,memstore_size_mb=1", SMALL_SPLIT_FLAGS);
            singleNode.start();
        }
        return singleNode.getHost();
    }

    @Override
    public void close() {
        if (singleNode != null) {
            singleNode.stop();
        }
        super.close();
    }
}
