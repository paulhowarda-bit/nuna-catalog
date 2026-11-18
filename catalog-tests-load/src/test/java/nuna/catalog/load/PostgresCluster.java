package nuna.catalog.load;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 18 as D37 deploys it: a primary and a synchronous standby (zero loss in-region), and optionally
 * an asynchronous standby standing in for the DR region. Servers reach it by a multi-host URL that prefers
 * the primary, so a promoted standby takes over without reconfiguring them.
 */
final class PostgresCluster implements AutoCloseable {

    static final String DATABASE = "catalog";
    static final String USER = "catalog";
    private static final String SYNC = "standby1";
    private static final Duration SETTLE = Duration.ofSeconds(90);

    private final DockerImageName image = DockerImageName.parse(System.getProperty("postgres.image"));
    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer primary;
    private final GenericContainer<?> standby;
    private final GenericContainer<?> dr;
    private String current;

    /** @param withDr also start an asynchronous standby, not named in synchronous_standby_names */
    PostgresCluster(boolean withDr) {
        primary = new PostgreSQLContainer(image)
                .withNetwork(network)
                .withNetworkAliases("primary")
                .withDatabaseName(DATABASE)
                .withUsername(USER)
                .withPassword(USER)
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                .withCopyToContainer(Transferable.of("echo 'host replication all all trust' >> \"$PGDATA/pg_hba.conf\"\n"),
                        "/docker-entrypoint-initdb.d/replication.sh")
                // PostgreSQLContainer would add fsync=off; the catalog's durability needs it on.
                .withCommand("postgres", "-c", "max_connections=300");
        primary.start();
        standby = standby(SYNC, "standby");
        dr = withDr ? standby("dr", "dr") : null;
        execute(primaryUrl(), "ALTER SYSTEM SET synchronous_standby_names = '" + SYNC + "'");
        execute(primaryUrl(), "SELECT pg_reload_conf()");
        await(() -> query(primaryUrl(), "SELECT sync_state FROM pg_stat_replication WHERE application_name = '"
                + SYNC + "'").contains("sync"), "the standby to stream in sync");
        current = primaryUrl();
    }

    private GenericContainer<?> standby(String applicationName, String alias) {
        String conninfo = "host=primary port=5432 user=" + USER + " application_name=" + applicationName;
        String script = """
                set -e
                until pg_basebackup -d '%s' -D "$PGDATA" -X stream; do sleep 1; done
                echo "primary_conninfo = '%s'" >> "$PGDATA/postgresql.auto.conf"
                touch "$PGDATA/standby.signal"
                exec postgres -c max_connections=300 -c wal_retrieve_retry_interval=200ms
                """.formatted(conninfo, conninfo);
        GenericContainer<?> container = new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withExposedPorts(5432)
                .withCreateContainerCmdModifier(c -> c.withUser("postgres").withEntrypoint("bash", "-c", script))
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept read-only connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        return container;
    }

    /** What the servers connect to: primary and synchronous standby, whichever is primary. */
    String serverUrl() {
        return "jdbc:postgresql://localhost:" + primary.getMappedPort(5432) + ",localhost:" + standby.getMappedPort(5432)
                + "/" + DATABASE + "?targetServerType=primary&connectTimeout=5&socketTimeout=30";
    }

    /** The DR region's database, for servers started there after the failover. */
    String drServerUrl() {
        return "jdbc:postgresql://localhost:" + dr.getMappedPort(5432) + "/" + DATABASE
                + "?targetServerType=primary&connectTimeout=5&socketTimeout=30";
    }

    String primaryUrl() {
        return "jdbc:postgresql://localhost:" + primary.getMappedPort(5432) + "/" + DATABASE;
    }

    private String standbyUrl() {
        return "jdbc:postgresql://localhost:" + standby.getMappedPort(5432) + "/" + DATABASE;
    }

    private String drUrl() {
        return "jdbc:postgresql://localhost:" + dr.getMappedPort(5432) + "/" + DATABASE;
    }

    /** Runs SQL on whichever node is primary now. */
    List<String> query(String sql) {
        return query(current, sql);
    }

    String value(String sql) {
        return query(sql).getFirst();
    }

    void execute(String sql) {
        execute(current, sql);
    }

    /** Deadlocks PostgreSQL has detected in the catalog database since it started. */
    long deadlocks() {
        return Long.parseLong(value("SELECT deadlocks FROM pg_stat_database WHERE datname = '" + DATABASE + "'"));
    }

    /** Kills the primary's container, as a crashed host loses it: no shutdown, no goodbye to clients. */
    void killPrimary() {
        primary.getDockerClient().killContainerCmd(primary.getContainerId()).exec();
    }

    /** Promotes the synchronous standby, as the cluster manager would once the primary is gone. */
    void promoteStandby() {
        if (!query(standbyUrl(), "SELECT pg_promote(true, 60)").getFirst().equals("t")) {
            throw new IllegalStateException("the standby did not promote");
        }
        current = standbyUrl();
    }

    /** Waits until the DR standby has replayed everything the primary has written so far. */
    void awaitDrCaughtUp() {
        String written = query(primaryUrl(), "SELECT pg_current_wal_lsn()::text").getFirst();
        await(() -> query(drUrl(), "SELECT pg_last_wal_replay_lsn() >= '" + written + "'::pg_lsn").getFirst().equals("t"),
                "the DR standby to catch up");
    }

    /** Stops the DR standby receiving WAL: from now on the DR region falls behind. */
    void cutDrReplication() {
        execute(drUrl(), "ALTER SYSTEM SET primary_conninfo = ''");
        execute(drUrl(), "SELECT pg_reload_conf()");
        await(() -> query(primaryUrl(), "SELECT count(*) FROM pg_stat_replication WHERE application_name = 'dr'")
                .getFirst().equals("0"), "the DR standby to disconnect");
    }

    /** The primary region is lost: both of its nodes die and the DR standby is promoted, behind as it is. */
    void loseRegionAndPromoteDr() {
        standby.getDockerClient().killContainerCmd(standby.getContainerId()).exec();
        killPrimary();
        if (!query(drUrl(), "SELECT pg_promote(true, 60)").getFirst().equals("t")) {
            throw new IllegalStateException("the DR standby did not promote");
        }
        current = drUrl();
    }

    @Override
    public void close() {
        if (dr != null) {
            dr.stop();
        }
        standby.stop();
        primary.stop();
        network.close();
    }

    static List<String> query(String url, String sql) {
        try (Connection connection = DriverManager.getConnection(url, USER, USER);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + " failed on " + url, e);
        }
    }

    static void execute(String url, String sql) {
        try (Connection connection = DriverManager.getConnection(url, USER, USER);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql + " failed on " + url, e);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
