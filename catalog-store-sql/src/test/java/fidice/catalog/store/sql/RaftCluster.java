package fidice.catalog.store.sql;

import static fidice.catalog.store.sql.Sql.await;
import static fidice.catalog.store.sql.Sql.column;
import static fidice.catalog.store.sql.Sql.execute;
import static fidice.catalog.store.sql.Sql.value;

import com.github.dockerjava.api.DockerClient;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.suite.CatalogStoreUnderTest;
import fidice.catalog.store.suite.Faults;
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
import java.util.Optional;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Three nodes of a Raft-replicated database (CockroachDB, YugabyteDB) with every table replicated
 * three ways, and Toxiproxy in front: the link leads to one node, the gateway, and one proxy per
 * node is never faulted, for the harness's direct reads.
 *
 * <p>The faults of the shared suite map onto the cluster as follows. Replication stalls when the
 * two nodes other than the gateway are paused, which takes the quorum away. The stall is armed by
 * {@link #stallReplication} and struck just before the next COMMIT a store of this harness sends,
 * because without a quorum the reads and writes before COMMIT would block too. The primary is the
 * gateway: killing it and promoting a replica unpauses the others and points the link at one of
 * them. A replica lags by being read with a follower read at least the lag in the past.
 */
abstract class RaftCluster implements CatalogStoreUnderTest, Faults {

    static final String DATABASE = "catalog";
    /**
     * The stores' socket timeout: long enough for a cluster whose quorum was stalled for the
     * in-flight tests to serve the lookups again, which has taken over 5 s here (as has a 5 MiB
     * projection read while other suites load this Docker host).
     */
    static final int SOCKET_TIMEOUT_SECONDS = 15;
    /**
     * How long the one connection about to send COMMIT waits once a fault struck from its commit
     * hook has dropped the answer, dropped the COMMIT or stalled the quorum: no answer can come, so
     * waiting out the full socket timeout proves nothing more. Every other connection, the lookups
     * after a stall among them, keeps {@link #SOCKET_TIMEOUT_SECONDS}. The same 5 s as PostgreSQL's
     * and MongoDB's harnesses.
     */
    static final Duration LOST_ANSWER_WAIT = Duration.ofSeconds(5);
    static final int ADMIN_SOCKET_TIMEOUT_SECONDS = 120;
    static final int NODES = 3;
    static final Duration SETTLE = Duration.ofSeconds(180);

    private static final int LINK_PORT = 8666;

    protected final Network network = Network.newNetwork();
    protected final List<GenericContainer<?>> nodes = new ArrayList<>();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient toxiproxyClient;
    private Proxy link;
    private LinkDataSource linkDataSource;

    private int gateway;
    private boolean stallArmed;
    private final TreeSet<Integer> paused = new TreeSet<>();
    private Integer killed;
    private Duration lag;
    private int freshDatabases;

    RaftCluster() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image")))
                .withNetwork(network);
        toxiproxy.start();
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    }

    /** Starts the nodes, the proxies and the catalog's database. Subclasses call it last in their constructor. */
    protected final void start() {
        startNodes();
        for (int node = 0; node < NODES; node++) {
            String name = "node-" + node;
            String listen = "0.0.0.0:" + (LINK_PORT + 1 + node);
            String upstream = alias(node) + ":" + sqlPort();
            toxi(() -> toxiproxyClient.createProxy(name, listen, upstream));
        }
        link = toxi(() -> toxiproxyClient.createProxy("link", "0.0.0.0:" + LINK_PORT, alias(0) + ":" + sqlPort()));
        await(SETTLE, () -> {
            try {
                execute(direct(0, defaultDatabase()), "CREATE DATABASE " + DATABASE);
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }, "the cluster to create the catalog's database");
        // A first connection to the new database through the link has taken over the stores' 15 s
        // socket timeout while other modules load this Docker host (-T 2), so the cluster counts as
        // started once that connection is answered.
        await(SETTLE, () -> {
            try {
                value(throughLink(DATABASE), "SELECT 1");
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }, "the catalog's database to answer through the link");
        linkDataSource = new LinkDataSource(throughLink(DATABASE));
    }

    /** The network alias of node {@code node}, 0 to 2. */
    abstract String alias(int node);

    abstract int sqlPort();

    /** The database a fresh connection lands in before the catalog's exists. */
    abstract String defaultDatabase();

    /** Creates, starts and joins the three nodes into one cluster on {@link #network}. */
    abstract void startNodes();

    abstract DataSource dataSource(String host, int port, String database, int socketTimeoutSeconds);

    /** Reads {@code key}'s pointer on node {@code node} with a follower read at least {@code lag} old. */
    abstract Optional<Pointer> followerRead(int node, PointerKey key, Duration lag);

    /** Undoes whatever {@link #followerRead} needed set on the cluster. */
    void resetLag() {
    }

    /** Waits until node {@code node}, restarted after a kill, serves again. */
    abstract void awaitRejoined(int node);

    /** Where this backend keeps its schema steps. */
    abstract Backend backend();

    // ---- data sources ----

    DataSource throughLink(String database) {
        return dataSource(toxiproxy.getHost(), toxiproxy.getMappedPort(LINK_PORT), database, SOCKET_TIMEOUT_SECONDS);
    }

    /** Node {@code node}, around the link. */
    DataSource direct(int node, String database) {
        return dataSource(toxiproxy.getHost(), toxiproxy.getMappedPort(LINK_PORT + 1 + node), database,
                ADMIN_SOCKET_TIMEOUT_SECONDS);
    }

    /** The gateway, around the link. */
    DataSource primaryDirect() {
        return direct(gateway, DATABASE);
    }

    int gateway() {
        return gateway;
    }

    /**
     * Connecting may take as long as an answer: a previous-release node's connection opened while
     * another node ran a CockroachDB schema step has taken over 5 s here.
     */
    static DataSource withConnectTimeout(PGSimpleDataSource dataSource, int seconds) {
        dataSource.setConnectTimeout(seconds);
        return dataSource;
    }

    /** A fresh, empty database on the cluster, reached through the link. */
    DataSource freshDatabase() {
        return throughLink(createFreshDatabase());
    }

    /** A fresh, empty database on the cluster, reached around the link, with the admin timeout. */
    DataSource freshDatabaseDirect() {
        return direct(gateway, createFreshDatabase());
    }

    /**
     * A fresh, empty database on the gateway, around the link, whose connections wait at most
     * {@code socketTimeoutSeconds} for an answer.
     */
    DataSource freshDatabaseDirect(int socketTimeoutSeconds) {
        PGSimpleDataSource dataSource = (PGSimpleDataSource) freshDatabaseDirect();
        dataSource.setSocketTimeout(socketTimeoutSeconds);
        return dataSource;
    }

    private String createFreshDatabase() {
        String database = "fresh_" + ++freshDatabases;
        execute(primaryDirect(), "CREATE DATABASE " + database);
        return database;
    }

    // ---- StoreUnderTest ----

    @Override
    public CatalogStore store(CommitHook hook) {
        return new SqlCatalogStore(linkDataSource, id -> {
            hook.beforeCommit(id);
            strikeArmedStall();
        });
    }

    @Override
    public CatalogStore storeOfAnotherProcess() {
        return store(CommitHook.NONE);
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
        try (Connection connection = primaryDirect().getConnection();
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
        int outbox = Integer.parseInt(value(primaryDirect(), "SELECT count(*) FROM outbox WHERE commit_id = ?", change.id()));
        int idempotency = Integer.parseInt(value(primaryDirect(), "SELECT count(*) FROM idempotency WHERE key = ?"
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
        return 1 + Integer.parseInt(value(primaryDirect(), "SELECT count(*) FROM projection_piece WHERE object_id = ?"
                + " AND branch = ?", key.objectId(), key.branch()));
    }

    /**
     * A fresh database at V2, the M1 commit core, reached through the link. M1 shipped for
     * PostgreSQL only; the previous release's store stands for M1's code on this backend.
     */
    @Override
    public UpgradeTarget upgradeTarget() {
        DataSource throughLink = freshDatabase();
        PreviousReleaseStore previous = new PreviousReleaseStore(throughLink, backend());
        previous.migrate();
        return new UpgradeTarget(PreviousReleaseStore.SCHEMA_VERSION, previous, new SqlCatalogStore(throughLink));
    }

    @Override
    public List<Long> journalVersions(PointerKey key) {
        return column(primaryDirect(), "SELECT new_version FROM journal WHERE object_id = ? AND branch = ? ORDER BY seq",
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

    /** The lookup aborts a stalled transaction its client abandoned (sections 5.4 and 5.5). */
    @Override
    public boolean stalledCommitCanBeLost() {
        return true;
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
        linkDataSource.shortenWaitOfThisThreadsConnection(LOST_ANSWER_WAIT);
    }

    @Override
    public void dropResponses() {
        toxi(() -> link.toxics().timeout("drop-responses", ToxicDirection.DOWNSTREAM, 0));
        linkDataSource.shortenWaitOfThisThreadsConnection(LOST_ANSWER_WAIT);
    }

    @Override
    public void healLink() {
        toxi(() -> {
            toxiproxyClient.reset();
            return null;
        });
    }

    /** Armed here, struck by the next COMMIT a store of this harness sends. */
    @Override
    public synchronized void stallReplication() {
        stallArmed = true;
    }

    private synchronized void strikeArmedStall() {
        if (!stallArmed) {
            return;
        }
        stallArmed = false;
        for (int node = 0; node < NODES; node++) {
            if (node != gateway && (killed == null || node != killed)) {
                docker().pauseContainerCmd(nodes.get(node).getContainerId()).exec();
                paused.add(node);
            }
        }
        linkDataSource.shortenWaitOfThisThreadsConnection(LOST_ANSWER_WAIT);
    }

    @Override
    public synchronized void resumeReplication() {
        stallArmed = false;
        unpause();
        awaitServing(gateway);
    }

    private void unpause() {
        for (int node : paused) {
            docker().unpauseContainerCmd(nodes.get(node).getContainerId()).exec();
        }
        paused.clear();
    }

    @Override
    public void lagReplicaApply(Duration lag) {
        this.lag = lag;
    }

    @Override
    public Optional<Pointer> readPointerOnReplica(PointerKey key) {
        int replica = (gateway + 1) % NODES;
        return followerRead(replica, key, lag == null ? Duration.ZERO : lag);
    }

    @Override
    public synchronized void killPrimary() {
        killed = gateway;
        docker().killContainerCmd(nodes.get(gateway).getContainerId()).exec();
    }

    /** The two nodes left form a quorum once the paused one runs again; the link leads to one of them. */
    @Override
    public synchronized void promoteReplica() {
        unpause();
        int promoted = (gateway + 1) % NODES;
        toxi(() -> {
            link.setUpstream(alias(promoted) + ":" + sqlPort());
            return null;
        });
        gateway = promoted;
        awaitServing(gateway);
    }

    @Override
    public synchronized void restore() {
        healLink();
        stallArmed = false;
        unpause();
        if (killed != null) {
            int restarted = killed;
            docker().startContainerCmd(nodes.get(restarted).getContainerId()).exec();
            awaitRejoined(restarted);
            killed = null;
        }
        if (lag != null) {
            resetLag();
            lag = null;
        }
        awaitServing(gateway);
    }

    /** Waits until a write through node {@code node} commits: the ranges it needs have a quorum and a leader. */
    void awaitServing(int node) {
        DataSource direct = direct(node, DATABASE);
        await(SETTLE, () -> {
            try {
                execute(direct, "UPDATE durability_barrier SET counter = counter + 1");
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }, "node " + node + " to commit a write");
    }

    @Override
    public void close() {
        unpause();
        for (GenericContainer<?> node : nodes) {
            node.stop();
        }
        toxiproxy.stop();
        network.close();
    }

    protected DockerClient docker() {
        return toxiproxy.getDockerClient();
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
