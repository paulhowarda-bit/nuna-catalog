package keel.catalog.store.sql;

import static keel.catalog.store.sql.Sql.await;
import static keel.catalog.store.sql.Sql.column;
import static keel.catalog.store.sql.Sql.execute;
import static keel.catalog.store.sql.Sql.pause;
import static keel.catalog.store.sql.Sql.value;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitHook;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.SafetyFinding;
import keel.catalog.store.suite.Faults;
import keel.catalog.store.suite.CatalogStoreUnderTest;
import keel.catalog.store.suite.UnsafeSetting;
import keel.catalog.store.suite.WrittenRows;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 18: a primary and one synchronous standby streaming over a private network, with
 * Toxiproxy between the stores and the primary. The faults of the shared suite act on this
 * topology; an unsafe single node is started on demand for the self-check findings, and a fresh
 * database on the primary for each upgrade test.
 */
final class ReplicatedPostgres implements CatalogStoreUnderTest, Faults {

    static final String DATABASE = "catalog";
    static final String USER = "catalog";
    /**
     * The stores' socket timeout. Short, so a lost answer fails the client quickly; it does not
     * cancel the server's COMMIT (docs/storage-design.md 5.1 step 4).
     */
    static final int SOCKET_TIMEOUT_SECONDS = 5;

    private static final String STANDBY_NAME = "standby1";
    private static final int LINK_PORT = 8666;
    private static final int ADMIN_SOCKET_TIMEOUT_SECONDS = 120;
    private static final Duration SETTLE = Duration.ofSeconds(60);

    private final DockerImageName postgresImage = DockerImageName.parse(System.getProperty("postgres.image"));
    private final Network network = Network.newNetwork();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient toxiproxyClient;
    private final Proxy link;
    private final DataSource linkDataSource;

    private int generation;
    private PostgreSQLContainer primary;
    private GenericContainer<?> standby;
    private String standbyConninfo;
    private DataSource primaryDirect;
    private DataSource standbyDirect;
    private boolean replicationStalled;
    private boolean applyLagged;
    private boolean topologyLost;
    private PostgreSQLContainer singleNode;
    private int upgrades;

    ReplicatedPostgres() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image")))
                .withNetwork(network);
        toxiproxy.start();
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        startPair();
        link = toxi(() -> toxiproxyClient.createProxy("catalog", "0.0.0.0:" + LINK_PORT, primaryAlias() + ":5432"));
        linkDataSource = Sql.dataSource(toxiproxy.getHost(), toxiproxy.getMappedPort(LINK_PORT), USER,
                SOCKET_TIMEOUT_SECONDS);
    }

    private void startPair() {
        generation++;
        primary = new PostgreSQLContainer(postgresImage)
                .withNetwork(network)
                .withNetworkAliases(primaryAlias())
                .withDatabaseName(DATABASE)
                .withUsername(USER)
                .withPassword(USER)
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                .withCopyToContainer(Transferable.of("echo 'host replication all all trust' >> \"$PGDATA/pg_hba.conf\"\n"),
                        "/docker-entrypoint-initdb.d/replication.sh")
                // PostgreSQLContainer would add fsync=off, which the self-check rightly rejects.
                .withCommand("postgres");
        primary.start();
        primaryDirect = Sql.dataSource(primary.getHost(), primary.getMappedPort(5432), USER, ADMIN_SOCKET_TIMEOUT_SECONDS);

        standbyConninfo = "host=" + primaryAlias() + " port=5432 user=" + USER + " application_name=" + STANDBY_NAME;
        String script = """
                set -e
                until pg_basebackup -d '%s' -D "$PGDATA" -X stream; do sleep 1; done
                echo "primary_conninfo = '%s'" >> "$PGDATA/postgresql.auto.conf"
                touch "$PGDATA/standby.signal"
                exec postgres -c wal_retrieve_retry_interval=200ms
                """.formatted(standbyConninfo, standbyConninfo);
        standby = new GenericContainer<>(postgresImage)
                .withNetwork(network)
                .withNetworkAliases(standbyAlias())
                .withExposedPorts(5432)
                .withCreateContainerCmdModifier(command -> command.withUser("postgres").withEntrypoint("bash", "-c", script))
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept read-only connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        standby.start();
        standbyDirect = Sql.dataSource(standby.getHost(), standby.getMappedPort(5432), USER, ADMIN_SOCKET_TIMEOUT_SECONDS);

        // Set only now: with it on the command line, initdb's own commits would wait for a standby.
        execute(primaryDirect, "ALTER SYSTEM SET synchronous_standby_names = '" + STANDBY_NAME + "'");
        execute(primaryDirect, "SELECT pg_reload_conf()");
        awaitSyncStandby();
        new SqlCatalogStore(primaryDirect).migrate();
    }

    private String primaryAlias() {
        return "primary-" + generation;
    }

    private String standbyAlias() {
        return "standby-" + generation;
    }

    private void awaitSyncStandby() {
        await(SETTLE, () -> column(primaryDirect, "SELECT sync_state FROM pg_stat_replication WHERE application_name = ?",
                STANDBY_NAME).contains("sync"), "the standby to stream in sync state");
    }

    /** The current primary, around the link. */
    DataSource primaryDirect() {
        return primaryDirect;
    }

    // ---- StoreUnderTest ----

    @Override
    public CatalogStore store(CommitHook hook) {
        return new SqlCatalogStore(linkDataSource, hook);
    }

    /** The store keeps nothing in memory about its commits, so another process is another store on the link. */
    @Override
    public CatalogStore storeOfAnotherProcess() {
        return new SqlCatalogStore(linkDataSource);
    }

    @Override
    public Faults faults() {
        return this;
    }

    @Override
    public WrittenRows writtenRows(CommitBatch batch) {
        String sql = "SELECT (SELECT count(*) FROM pointer WHERE last_commit_id = ?),"
                + " (SELECT count(*) FROM journal WHERE commit_id = ?),"
                + " (SELECT count(*) FROM outbox WHERE commit_id = ?),"
                + " (SELECT count(*) FROM idempotency WHERE key = ?)";
        try (Connection connection = primaryDirect.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, batch.commitId());
            statement.setObject(2, batch.commitId());
            statement.setObject(3, batch.commitId());
            statement.setString(4, batch.idempotency().map(result -> result.key()).orElse(null));
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new WrittenRows(row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public WrittenRows changeRows(Change change) {
        int outbox = Integer.parseInt(value(primaryDirect, "SELECT count(*) FROM outbox WHERE commit_id = ?", change.id()));
        int idempotency = Integer.parseInt(value(primaryDirect, "SELECT count(*) FROM idempotency WHERE key = ?"
                + " AND change_id = ? AND status IS NOT NULL", change.idempotency().map(result -> result.key()).orElse(null),
                change.id()));
        return new WrittenRows(0, 0, outbox, idempotency);
    }

    @Override
    public int projectionPieceBytes() {
        return Pointers.PIECE_BYTES;
    }

    @Override
    public int projectionPieces(PointerKey key) {
        return 1 + Integer.parseInt(value(primaryDirect, "SELECT count(*) FROM projection_piece WHERE object_id = ?"
                + " AND branch = ?", key.objectId(), key.branch()));
    }

    /** A fresh, empty database on the primary, reached through the link. */
    DataSource freshDatabase() {
        String database = "fresh_" + ++upgrades;
        execute(primaryDirect, "CREATE DATABASE " + database);
        return Sql.dataSource(toxiproxy.getHost(), toxiproxy.getMappedPort(LINK_PORT), database, USER,
                SOCKET_TIMEOUT_SECONDS);
    }

    /** A fresh database on the primary, at the previous release's schema version, reached through the link. */
    @Override
    public UpgradeTarget upgradeTarget() {
        DataSource throughLink = freshDatabase();
        PreviousReleaseStore previous = new PreviousReleaseStore(throughLink);
        previous.migrate();
        return new UpgradeTarget(PreviousReleaseStore.SCHEMA_VERSION, previous, new SqlCatalogStore(throughLink));
    }

    @Override
    public List<Long> journalVersions(PointerKey key) {
        return column(primaryDirect, "SELECT new_version FROM journal WHERE object_id = ? AND branch = ? ORDER BY seq",
                key.objectId(), key.branch()).stream().map(Long::valueOf).toList();
    }

    @Override
    public boolean supportsFailover() {
        return true;
    }

    @Override
    public boolean hasReplica() {
        return true;
    }

    @Override
    public boolean hasSafeEnvironment() {
        return true;
    }

    @Override
    public Set<String> findingCodes() {
        return Set.of("pg.sync-commit-off", "pg.no-sync-standby", "pg.fsync-off", "pg.commit-cancel-timeout");
    }

    @Override
    public List<UnsafeSetting> unsafeSettings() {
        return List.of(
                new UnsafeSetting("synchronous_commit = off for the catalog role", "pg.sync-commit-off",
                        () -> selfCheckAsRoleWith("synchronous_commit", "off")),
                new UnsafeSetting("synchronous_commit = local for the catalog role", "pg.sync-commit-off",
                        () -> selfCheckAsRoleWith("synchronous_commit", "local")),
                new UnsafeSetting("synchronous_commit = remote_write for the catalog role", "pg.sync-commit-off",
                        () -> selfCheckAsRoleWith("synchronous_commit", "remote_write")),
                new UnsafeSetting("statement_timeout = 30s for the catalog role", "pg.commit-cancel-timeout",
                        () -> selfCheckAsRoleWith("statement_timeout", "30s")),
                new UnsafeSetting("idle_in_transaction_session_timeout = 60s for the catalog role",
                        "pg.commit-cancel-timeout", () -> selfCheckAsRoleWith("idle_in_transaction_session_timeout", "60s")),
                new UnsafeSetting("a single node: synchronous_standby_names is empty", "pg.no-sync-standby",
                        () -> new SqlCatalogStore(singleNode()).selfCheck()),
                new UnsafeSetting("synchronous_standby_names names no connected standby (standby1 streams async)",
                        "pg.no-sync-standby", () -> selfCheckWithSystemSetting(primaryDirect,
                                "synchronous_standby_names", "elsewhere")),
                new UnsafeSetting("fsync = off", "pg.fsync-off",
                        () -> selfCheckWithSystemSetting(singleNode(), "fsync", "off")),
                new UnsafeSetting("full_page_writes = off", "pg.fsync-off",
                        () -> selfCheckWithSystemSetting(singleNode(), "full_page_writes", "off")));
    }

    /** Self-check as a fresh login role that has {@code setting} set for it, on the safe primary. */
    private List<SafetyFinding> selfCheckAsRoleWith(String setting, String value) {
        String role = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        execute(primaryDirect, "CREATE ROLE " + role + " LOGIN IN ROLE pg_monitor");
        execute(primaryDirect, "ALTER ROLE " + role + " SET " + setting + " = '" + value + "'");
        try {
            return new SqlCatalogStore(Sql.dataSource(primary.getHost(), primary.getMappedPort(5432), role,
                    ADMIN_SOCKET_TIMEOUT_SECONDS)).selfCheck();
        } finally {
            execute(primaryDirect, "DROP ROLE " + role);
        }
    }

    /** Self-check with a server-wide setting changed by ALTER SYSTEM, then set back. */
    private List<SafetyFinding> selfCheckWithSystemSetting(DataSource server, String setting, String value) {
        String original = value(server, "SELECT current_setting(?)", setting);
        execute(server, "ALTER SYSTEM SET " + setting + " = '" + value + "'");
        reloadUntil(server, setting, value);
        try {
            return new SqlCatalogStore(server).selfCheck();
        } finally {
            execute(server, "ALTER SYSTEM SET " + setting + " = '" + original + "'");
            reloadUntil(server, setting, original);
            if (server == primaryDirect) {
                awaitSyncStandby();
            }
        }
    }

    private static void reloadUntil(DataSource server, String setting, String value) {
        execute(server, "SELECT pg_reload_conf()");
        await(SETTLE, () -> value(server, "SELECT current_setting(?)", setting).equals(value),
                setting + " to read " + value);
    }

    private DataSource singleNode() {
        if (singleNode == null) {
            singleNode = new PostgreSQLContainer(postgresImage)
                    .withDatabaseName(DATABASE)
                    .withUsername(USER)
                    .withPassword(USER)
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                    .withCommand("postgres");
            singleNode.start();
        }
        return Sql.dataSource(singleNode.getHost(), singleNode.getMappedPort(5432), USER, ADMIN_SOCKET_TIMEOUT_SECONDS);
    }

    // ---- Faults ----

    @Override
    public void cutLink() {
        toxi(() -> {
            link.disable();
            return null;
        });
    }

    @Override
    public void dropRequests() {
        toxi(() -> link.toxics().timeout("drop-requests", ToxicDirection.UPSTREAM, 0));
    }

    @Override
    public void dropResponses() {
        toxi(() -> link.toxics().timeout("drop-responses", ToxicDirection.DOWNSTREAM, 0));
    }

    @Override
    public void healLink() {
        toxi(() -> {
            toxiproxyClient.reset();
            return null;
        });
    }

    @Override
    public void stallReplication() {
        // Without a primary_conninfo the standby's WAL receiver stops; its socket and buffers go with it.
        execute(standbyDirect, "ALTER SYSTEM SET primary_conninfo = ''");
        execute(standbyDirect, "SELECT pg_reload_conf()");
        await(SETTLE, () -> value(primaryDirect, "SELECT count(*) FROM pg_stat_replication").equals("0"),
                "the standby to disconnect");
        replicationStalled = true;
    }

    @Override
    public void resumeReplication() {
        execute(standbyDirect, "ALTER SYSTEM SET primary_conninfo = '" + standbyConninfo + "'");
        execute(standbyDirect, "SELECT pg_reload_conf()");
        awaitSyncStandby();
        replicationStalled = false;
    }

    @Override
    public void lagReplicaApply(Duration lag) {
        execute(standbyDirect, "ALTER SYSTEM SET recovery_min_apply_delay = '" + lag.toMillis() + "ms'");
        execute(standbyDirect, "SELECT pg_reload_conf()");
        applyLagged = true;
        // In effect once a fresh commit on the primary stays invisible on the standby.
        await(SETTLE, () -> {
            long counter = Long.parseLong(value(primaryDirect,
                    "UPDATE durability_barrier SET counter = counter + 1 RETURNING counter"));
            pause(Duration.ofMillis(500));
            return Long.parseLong(value(standbyDirect, "SELECT counter FROM durability_barrier")) < counter;
        }, "the standby to apply late");
    }

    @Override
    public Optional<Pointer> readPointerOnReplica(PointerKey key) {
        return new SqlCatalogStore(standbyDirect).readPointer(key);
    }

    @Override
    public void killPrimary() {
        topologyLost = true;
        primary.getDockerClient().killContainerCmd(primary.getContainerId()).exec();
    }

    @Override
    public void promoteReplica() {
        if (!value(standbyDirect, "SELECT pg_promote(true, 60)").equals("t")) {
            throw new IllegalStateException("the standby did not promote");
        }
        toxi(() -> {
            link.setUpstream(standbyAlias() + ":5432");
            return null;
        });
        primaryDirect = standbyDirect;
    }

    @Override
    public void restore() {
        healLink();
        if (topologyLost) {
            standby.stop();
            primary.stop();
            startPair();
            toxi(() -> {
                link.setUpstream(primaryAlias() + ":5432");
                return null;
            });
            topologyLost = false;
            replicationStalled = false;
            applyLagged = false;
            return;
        }
        if (replicationStalled) {
            resumeReplication();
        }
        if (applyLagged) {
            execute(standbyDirect, "ALTER SYSTEM RESET recovery_min_apply_delay");
            execute(standbyDirect, "SELECT pg_reload_conf()");
            applyLagged = false;
        }
    }

    @Override
    public void close() {
        if (singleNode != null) {
            singleNode.stop();
        }
        standby.stop();
        primary.stop();
        toxiproxy.stop();
        network.close();
    }

    private interface ToxiproxyCall<T> {
        T call() throws IOException;
    }

    private static <T> T toxi(ToxiproxyCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
