package fidice.catalog.store.oracle;

import static fidice.catalog.store.oracle.Sql.await;
import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.fullBatch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.oracle.Sql.column;
import static fidice.catalog.store.oracle.Sql.execute;
import static fidice.catalog.store.oracle.Sql.value;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.suite.Faults;
import fidice.catalog.store.suite.StoreUnderTest;
import fidice.catalog.store.suite.UnsafeSetting;
import fidice.catalog.store.suite.WrittenRows;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import oracle.jdbc.LogicalTransactionId;
import oracle.ucp.UniversalConnectionPoolException;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.Network;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Oracle AI Database Free: one instance, with Toxiproxy between the stores and its listener. The
 * catalog connects through a service with COMMIT_OUTCOME = TRUE, so Transaction Guard is on.
 *
 * <p>Free has no Data Guard, so there is no replica to stall, lag or promote. A single instance's
 * COMMIT becomes durable when the log writer writes its redo, so stalling replication freezes the
 * log writer (SIGSTOP): a COMMIT then waits at the primary, as it waits for a SYNC standby.
 */
final class OracleFree implements StoreUnderTest, Faults {

    static final String USER = "catalog";
    /** The catalog service: COMMIT_OUTCOME = TRUE, no FAILOVER_TYPE (the driver never replays). */
    static final String SERVICE = "catalog_tg";
    /**
     * The link's read timeout. Short, so a lost answer fails the client quickly; it does not
     * cancel the server's COMMIT.
     */
    static final int READ_TIMEOUT_SECONDS = 5;

    private static final String PASSWORD = "catalog";
    private static final String PDB_SERVICE = "freepdb1";
    private static final String PLAIN_SERVICE = "catalog_plain";
    private static final String ORACLE_ALIAS = "oracle";
    private static final int ORACLE_PORT = 1521;
    private static final int LINK_PORT = 8666;
    private static final int ADMIN_READ_TIMEOUT_SECONDS = 120;
    /** Free caps itself at 2 GB of memory; the container gets a little headroom and no more. */
    private static final long MEMORY_BYTES = 2560L * 1024 * 1024;

    private final Network network = Network.newNetwork();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient toxiproxyClient;
    private final OracleContainer oracle;
    private final Proxy link;
    private final PoolDataSource linkPool;
    private final PoolDataSource directPool;
    private final DataSource sysdba;
    /** The undecided commits of the stores this harness hands out: they act as one process. */
    private final Map<UUID, LogicalTransactionId> undecided = new ConcurrentHashMap<>();
    private String logWriterPid;
    private boolean logWriterFrozen;

    OracleFree() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image")))
                .withNetwork(network);
        toxiproxy.start();
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());

        oracle = new OracleContainer(DockerImageName.parse(System.getProperty("oracle.image")))
                .withNetwork(network)
                .withNetworkAliases(ORACLE_ALIAS)
                .withUsername(USER)
                .withPassword(PASSWORD)
                .withStartupTimeout(Duration.ofMinutes(5))
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(MEMORY_BYTES));
        oracle.start();
        sysdba = Sql.direct(oracle.getHost(), oracle.getMappedPort(ORACLE_PORT), PDB_SERVICE, "sys", PASSWORD, true);
        createServicesAndGrants();

        link = toxi(() -> toxiproxyClient.createProxy("catalog", "0.0.0.0:" + LINK_PORT, ORACLE_ALIAS + ":" + ORACLE_PORT));
        linkPool = Sql.pool("fidice-link", toxiproxy.getHost(), toxiproxy.getMappedPort(LINK_PORT), SERVICE, USER,
                PASSWORD, READ_TIMEOUT_SECONDS, 8);
        directPool = Sql.pool("fidice-direct", oracle.getHost(), oracle.getMappedPort(ORACLE_PORT), SERVICE, USER,
                PASSWORD, ADMIN_READ_TIMEOUT_SECONDS, 4);
    }

    private void createServicesAndGrants() {
        execute(sysdba, """
                DECLARE
                  parameters DBMS_SERVICE.SVC_PARAMETER_ARRAY;
                BEGIN
                  parameters('COMMIT_OUTCOME') := 'true';
                  parameters('RETENTION_TIMEOUT') := 86400;
                  DBMS_SERVICE.CREATE_SERVICE('%s', '%s', parameters);
                  DBMS_SERVICE.START_SERVICE('%s');
                END;""".formatted(SERVICE, SERVICE, SERVICE));
        execute(sysdba, """
                DECLARE
                  parameters DBMS_SERVICE.SVC_PARAMETER_ARRAY;
                BEGIN
                  DBMS_SERVICE.CREATE_SERVICE('%s', '%s', parameters);
                  DBMS_SERVICE.START_SERVICE('%s');
                END;""".formatted(PLAIN_SERVICE, PLAIN_SERVICE, PLAIN_SERVICE));
        execute(sysdba, "GRANT EXECUTE ON SYS.DBMS_APP_CONT TO " + USER);
        execute(sysdba, "GRANT SELECT ON SYS.V_$DATABASE TO " + USER);
        execute(sysdba, "GRANT SELECT ON SYS.V_$SERVICES TO " + USER);
        for (String service : List.of(SERVICE, PLAIN_SERVICE)) {
            await(Duration.ofSeconds(60), () -> {
                try (Connection ignored = directTo(service).getConnection()) {
                    return true;
                } catch (SQLException notRegisteredYet) {
                    return false;
                }
            }, "the listener to offer service " + service);
        }
    }

    private DataSource directTo(String service) {
        return Sql.direct(oracle.getHost(), oracle.getMappedPort(ORACLE_PORT), service, USER, PASSWORD, false);
    }

    // ---- Oracle-only seams for OracleStoreSuiteTest ----

    /** A store of another process: it reaches the database around the link and never saw this process's commits. */
    CatalogStore storeOfAnotherProcess() {
        return new OracleCatalogStore(directPool);
    }

    /** A store of this process (it shares the undecided commits) that reaches the database around the link. */
    CatalogStore storeAroundTheLink() {
        return new OracleCatalogStore(directPool, CommitHook.NONE, undecided);
    }

    /** Holds every byte sent towards the database for {@code delay}; connections stay open. */
    void delayRequests(Duration delay) {
        toxi(() -> link.toxics().latency("delay-requests", ToxicDirection.UPSTREAM, delay.toMillis()));
    }

    // ---- StoreUnderTest ----

    @Override
    public CatalogStore store(CommitHook hook) {
        return new OracleCatalogStore(linkPool, hook, undecided);
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
                + " (SELECT count(*) FROM idempotency WHERE key = ?) FROM dual";
        byte[] commitId = OracleCatalogStore.raw(batch.commitId());
        try (Connection connection = directPool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, commitId);
            statement.setBytes(2, commitId);
            statement.setBytes(3, commitId);
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
    public List<Long> journalVersions(PointerKey key) {
        return column(directPool, "SELECT new_version FROM journal WHERE object_id = ? AND branch = ? ORDER BY seq",
                key.objectId(), key.branch()).stream().map(Long::valueOf).toList();
    }

    /** Free has no Data Guard: failover is a work-hardware test. */
    @Override
    public boolean supportsFailover() {
        return false;
    }

    /** Free has no Data Guard, so ora.no-data-guard always stands: the safe environment is a work-hardware test. */
    @Override
    public boolean hasSafeEnvironment() {
        return false;
    }

    @Override
    public Set<String> findingCodes() {
        return Set.of("ora.no-data-guard", "ora.no-transaction-guard");
    }

    @Override
    public List<UnsafeSetting> unsafeSettings() {
        return List.of(
                new UnsafeSetting("no Data Guard standby (Oracle Free): protection level UNPROTECTED",
                        "ora.no-data-guard", () -> new OracleCatalogStore(directPool).selfCheck()),
                new UnsafeSetting("connected through the PDB's default service, which has no COMMIT_OUTCOME",
                        "ora.no-transaction-guard", () -> new OracleCatalogStore(directTo(PDB_SERVICE)).selfCheck()),
                new UnsafeSetting("connected through a service created without COMMIT_OUTCOME",
                        "ora.no-transaction-guard", () -> new OracleCatalogStore(directTo(PLAIN_SERVICE)).selfCheck()));
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

    /**
     * Freezes the log writer, so every COMMIT waits for its redo write. Other work waits for it too
     * and is done first, so that only the COMMIT waits: a session's first LOB write to a table (it
     * can need a block-range-reuse checkpoint) and the first run of a plain pointer read (seen
     * once) are done by running every store operation through every pooled session, and a
     * sequence-cache refresh (it commits recursively) is avoided by reloading the identity caches.
     */
    @Override
    public void stallReplication() {
        runEveryOperationThroughEverySession(linkPool);
        runEveryOperationThroughEverySession(directPool);
        execute(sysdba, "ALTER SYSTEM FLUSH SHARED_POOL");
        for (String sequence : column(directPool, "SELECT sequence_name FROM user_tab_identity_cols")) {
            value(directPool, "SELECT " + sequence + ".NEXTVAL FROM dual");
        }
        signalLogWriter("STOP");
        logWriterFrozen = true;
    }

    /**
     * Runs every store operation once through each of the pool's sessions: a create and an update
     * with every record, a read, and findCommit of a commit that exists and of one that does not.
     * All other connections are held, so the store borrows the one just released.
     */
    private static void runEveryOperationThroughEverySession(PoolDataSource pool) {
        CatalogStore store = new OracleCatalogStore(pool);
        List<Connection> held = new ArrayList<>();
        try {
            int open = pool.getAvailableConnectionsCount() + pool.getBorrowedConnectionsCount();
            for (int i = 0; i < open; i++) {
                held.add(pool.getConnection());
            }
            for (int i = 0; i < held.size(); i++) {
                held.get(i).close();
                PointerKey key = newKey();
                CommitBatch create = fullBatch(advance(key, Optional.empty(), "warm-up"));
                store.commit(create);
                store.commit(fullBatch(advance(key, Optional.of(next(create)), "warm-up")));
                store.readPointer(key);
                store.findCommit(create.commitId(), key);
                store.findCommit(UUID.randomUUID(), newKey());
                held.set(i, pool.getConnection());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Closing a pooled connection returns it; the next borrow validates it.
                }
            }
        }
    }

    @Override
    public void resumeReplication() {
        signalLogWriter("CONT");
        logWriterFrozen = false;
    }

    @Override
    public void lagReplicaApply(Duration lag) {
        Assumptions.abort("Oracle Free has no standby (no Data Guard) to lag; read-after-commit with replica lag"
                + " is a work-hardware test");
    }

    @Override
    public Optional<Pointer> readPointerOnReplica(PointerKey key) {
        return Assumptions.abort("Oracle Free has no standby (no Data Guard) to read");
    }

    @Override
    public void killPrimary() {
        throw new UnsupportedOperationException("Oracle Free has no standby to fail over to; supportsFailover() is false");
    }

    @Override
    public void promoteReplica() {
        throw new UnsupportedOperationException("Oracle Free has no standby to promote; supportsFailover() is false");
    }

    @Override
    public void restore() {
        healLink();
        if (logWriterFrozen) {
            resumeReplication();
        }
    }

    @Override
    public void close() {
        try {
            UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager().destroyConnectionPool("fidice-link");
            UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager().destroyConnectionPool("fidice-direct");
        } catch (UniversalConnectionPoolException e) {
            throw new IllegalStateException(e);
        } finally {
            oracle.stop();
            toxiproxy.stop();
            network.close();
        }
    }

    private void signalLogWriter(String signal) {
        if (logWriterPid == null) {
            logWriterPid = inContainer("for p in /proc/[0-9]*; do"
                    + " [ \"$(cat $p/comm 2>/dev/null)\" = ora_lgwr_free ] && echo ${p#/proc/}; done; true").strip();
        }
        inContainer("kill -" + signal + " " + logWriterPid);
    }

    private String inContainer(String script) {
        try {
            Container.ExecResult result = oracle.execInContainer("bash", "-c", script);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(script + " failed: " + result.getStderr());
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
