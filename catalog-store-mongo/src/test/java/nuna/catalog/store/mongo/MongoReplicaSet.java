package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.MongoCatalogStore.IDEMPOTENCY;
import static nuna.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static nuna.catalog.store.mongo.MongoCatalogStore.OUTBOX;
import static nuna.catalog.store.mongo.MongoCatalogStore.POINTER;
import static nuna.catalog.store.mongo.Docs.keyFilter;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;

import com.github.dockerjava.api.model.ExposedPort;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandListener;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitStore;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.suite.CatalogStoreUnderTest;
import nuna.catalog.store.suite.Faults;

import nuna.catalog.store.suite.UnsafeSetting;
import nuna.catalog.store.suite.WrittenRows;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MongoDB 8.0: a three-member replica set on a private network, with one Toxiproxy proxy in
 * front of each member. The faults of the shared suite act on this topology; scratch servers
 * are started on demand for the self-check findings a three-member set cannot stage.
 *
 * <p>The stores use an ordinary replica-set client. Replica-set discovery hands the client the
 * member addresses from the set's configuration, and a member's address must also be the
 * address the other members replicate from. So each member listens, inside the network, on the
 * same port number Docker publishes its proxy on, and the client resolves every member name to
 * the Docker host: {@code mongo-b:P} reaches member b through proxy b, while member a reaches
 * {@code mongo-b:P} directly. Every byte between the stores and the set crosses Toxiproxy, the
 * driver finds a new primary by itself after failover, and replication never crosses the link.
 */
final class MongoReplicaSet implements CatalogStoreUnderTest, Faults {

    static final String DATABASE = "catalog";
    /** The stores' socket timeout: a lost answer fails the client quickly; it does not stop the server's commit. */
    static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(5);
    /** Shorter than the server's default of 60 s, so an abandoned transaction is reaped within the test. */
    static final int TRANSACTION_LIFETIME_SECONDS = 15;

    private static final String SET_NAME = "rs0";
    private static final List<String> NAMES = List.of("mongo-a", "mongo-b", "mongo-c");
    private static final int FIRST_PROXY_PORT = 8666;
    private static final Duration SETTLE = Duration.ofSeconds(90);

    private final DockerImageName mongoImage = DockerImageName.parse(System.getProperty("mongo.image"));
    private final Network network = Network.newNetwork();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient toxiproxyClient;
    private final List<Member> members = new ArrayList<>();
    private final MongoClientSettings linkSettings;
    private final MongoClient linkClient;

    private final Set<Member> stalled = new LinkedHashSet<>();
    private int upgrades;
    private int freshDatabases;
    private Member applyStopped;
    private Member killed;

    /** One replica-set member: its container, the port it listens on, and its proxy. */
    private final class Member {
        final String name;
        final int port;
        final GenericContainer<?> container;
        final Proxy proxy;
        MongoClient direct;

        Member(String name, int port, Proxy proxy) {
            this.name = name;
            this.port = port;
            this.proxy = proxy;
            this.container = new GenericContainer<>(mongoImage)
                    .withNetwork(network)
                    .withNetworkAliases(name)
                    .withExposedPorts(port)
                    .withCommand(mongod("--replSet", SET_NAME, "--port", String.valueOf(port),
                            "--setParameter", "transactionLifetimeLimitSeconds=" + TRANSACTION_LIFETIME_SECONDS))
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
        }

        /** Connects around the link, on whatever host port Docker publishes now (it changes on restart). */
        void connect() {
            if (direct != null) {
                direct.close();
            }
            direct = directClient(container.getHost(), publishedPort());
        }

        /** The host port Docker publishes for the member, or null while it has none. */
        Integer publishedPort() {
            var bindings = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec()
                    .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port));
            return bindings == null || bindings.length == 0 ? null : Integer.valueOf(bindings[0].getHostPortSpec());
        }

        String address() {
            return name + ":" + port;
        }

        BsonDocument admin(BsonDocument command) {
            return MongoReplicaSet.admin(direct, command);
        }

        boolean isPrimary() {
            try {
                return admin(new BsonDocument("hello", new BsonInt32(1))).getBoolean("isWritablePrimary").getValue();
            } catch (MongoException e) {
                return false;
            }
        }

        boolean isSecondary() {
            try {
                return admin(new BsonDocument("hello", new BsonInt32(1))).getBoolean("secondary", BsonBoolean.FALSE).getValue();
            } catch (MongoException e) {
                return false;
            }
        }

        void failPoint(String name, boolean on) {
            BsonDocument answer = admin(new BsonDocument("configureFailPoint", new BsonString(name))
                    .append("mode", new BsonString(on ? "alwaysOn" : "off")));
            if (on) {
                // Returns once the replication thread has stopped at the fail point, not merely armed it.
                admin(new BsonDocument("waitForFailPoint", new BsonString(name))
                        .append("timesEntered", new BsonInt64(answer.getNumber("count").longValue() + 1))
                        .append("maxTimeMS", new BsonInt32(60_000)));
            }
        }
    }

    MongoReplicaSet() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image"))).withNetwork(network);
        toxiproxy.start();
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        for (int i = 0; i < NAMES.size(); i++) {
            String name = NAMES.get(i);
            int port = toxiproxy.getMappedPort(FIRST_PROXY_PORT + i);
            String listen = "0.0.0.0:" + (FIRST_PROXY_PORT + i);
            Proxy proxy = toxi(() -> toxiproxyClient.createProxy(name, listen, name + ":" + port));
            members.add(new Member(name, port, proxy));
        }
        Startables.deepStart(members.stream().map(member -> member.container)).join();
        members.forEach(Member::connect);
        BsonArray config = new BsonArray();
        for (int i = 0; i < members.size(); i++) {
            config.add(new BsonDocument("_id", new BsonInt32(i)).append("host", new BsonString(members.get(i).address())));
        }
        members.getFirst().admin(new BsonDocument("replSetInitiate",
                new BsonDocument("_id", new BsonString(SET_NAME)).append("members", config)));
        primary();
        awaitReplicated(new WriteConcern(members.size()));

        String seeds = String.join(",", members.stream().map(Member::address).toList());
        InetAddress dockerHost = resolve(toxiproxy.getHost());
        linkSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + seeds + "/?replicaSet=" + SET_NAME))
                .inetAddressResolver(host -> NAMES.contains(host) ? List.of(dockerHost) : List.of(InetAddress.getAllByName(host)))
                .retryWrites(false)
                .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(SOCKET_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToServerSettings(server -> server.heartbeatFrequency(1, TimeUnit.SECONDS))
                .build();
        linkClient = MongoClients.create(linkSettings);
        awaitLinkToPrimary();
    }

    /**
     * Until the driver's monitor has seen the primary again after a link fault, the driver keeps
     * its connection pool paused and fails operations at once.
     */
    private void awaitLinkToPrimary() {
        await(SETTLE, () -> {
            try {
                return admin(linkClient, new BsonDocument("hello", new BsonInt32(1))).getBoolean("isWritablePrimary").getValue();
            } catch (MongoException e) {
                return false;
            }
        }, "the stores' client to reach the primary through the proxies");
    }

    /**
     * A 1 GB cache: the size suite commits a projection five piece budgets long (40 MiB) in one
     * transaction, and the server refuses a transaction that dirties more than about 15% of its
     * cache (TransactionTooLargeForCache), which a 0.25 GB cache would.
     */
    private String[] mongod(String... extra) {
        List<String> command = new ArrayList<>(List.of("mongod", "--bind_ip_all", "--wiredTigerCacheSizeGB", "1",
                "--setParameter", "enableTestCommands=1"));
        command.addAll(List.of(extra));
        return command.toArray(String[]::new);
    }

    /** The current primary, around the link; waits for an election to settle. */
    private Member primary() {
        Member[] found = new Member[1];
        await(SETTLE, () -> {
            found[0] = members.stream().filter(member -> member != killed && member.isPrimary()).findFirst().orElse(null);
            return found[0] != null;
        }, "a primary");
        return found[0];
    }

    private List<Member> secondaries() {
        Member primary = primary();
        return members.stream().filter(member -> member != primary && member != killed).toList();
    }

    /** Waits until a write with {@code writeConcern} is acknowledged by the current primary. */
    private void awaitReplicated(WriteConcern writeConcern) {
        await(SETTLE, () -> {
            try {
                probes(primary(), writeConcern.withWTimeout(10, TimeUnit.SECONDS)).insertOne(new BsonDocument("at", new BsonInt64(System.nanoTime())));
                return true;
            } catch (MongoException e) {
                return false;
            }
        }, "a write acknowledged with " + writeConcern);
    }

    private static MongoCollection<BsonDocument> probes(Member member, WriteConcern writeConcern) {
        return member.direct.getDatabase("harness").getCollection("probe", BsonDocument.class).withWriteConcern(writeConcern);
    }

    // ---- StoreUnderTest ----

    @Override
    public CatalogStore store(CommitHook hook) {
        return new MongoCatalogStore(linkClient, DATABASE, hook);
    }

    /**
     * A client of its own, reaching the set as the suite's does, that also hands each command it sends to
     * {@code listener}, on the thread that sends it, before it goes out. The caller closes it.
     */
    MongoClient linkClientWatchedBy(CommandListener listener) {
        return MongoClients.create(MongoClientSettings.builder(linkSettings).addCommandListener(listener).build());
    }

    /** A store on {@code database} whose outbox entry ids come from {@code entryIds}, as from a process with another clock. */
    CatalogStore store(String database, Supplier<ObjectId> entryIds) {
        return new MongoCatalogStore(linkClient, database, CommitHook.NONE, entryIds, MongoCatalogStore.PIECE_BYTES);
    }

    /** A node of the previous release on the suite's database. */
    PreviousReleaseStore previousRelease() {
        return new PreviousReleaseStore(linkClient, DATABASE);
    }

    /** A store whose projections are split into pieces of {@code pieceBytes}. */
    CatalogStore store(int pieceBytes) {
        return new MongoCatalogStore(linkClient, DATABASE, CommitHook.NONE, ObjectId::new, pieceBytes);
    }

    /** A new database at the current schema, for a test that must see only its own outbox. */
    String freshDatabase() {
        String database = "fresh_" + ++freshDatabases;
        new MongoCatalogStore(linkClient, database).migrate();
        return database;
    }

    /** Whether the key's record holds an answer on the primary, read locally: written there, not necessarily on a majority. */
    boolean doneOnPrimary(String key) {
        BsonDocument record = primary().direct.getDatabase(DATABASE).getCollection(IDEMPOTENCY, BsonDocument.class)
                .withReadConcern(ReadConcern.LOCAL).find(new BsonDocument("key", new BsonString(key))).first();
        return record != null && record.containsKey("status");
    }

    /** The store keeps nothing in memory about its commits, so another process is another store on the link. */
    @Override
    public CatalogStore storeOfAnotherProcess() {
        return new MongoCatalogStore(linkClient, DATABASE);
    }

    /** Outbox entries of the change, and its idempotency result if the key's record holds it. */
    @Override
    public WrittenRows changeRows(Change change) {
        var database = primary().direct.getDatabase(DATABASE);
        int outbox = (int) database.getCollection(OUTBOX).countDocuments(new BsonDocument("commitId", uuid(change.id())));
        int idempotency = (int) database.getCollection(IDEMPOTENCY).countDocuments(new BsonDocument("key",
                        new BsonString(change.idempotency().map(result -> result.key()).orElse("")))
                .append("changeId", uuid(change.id())).append("status", new BsonDocument("$exists", BsonBoolean.TRUE)));
        return new WrittenRows(0, 0, outbox, idempotency);
    }

    @Override
    public int projectionPieceBytes() {
        return MongoCatalogStore.PIECE_BYTES;
    }

    @Override
    public int projectionPieces(PointerKey key) {
        var database = primary().direct.getDatabase(DATABASE);
        return (int) (database.getCollection(POINTER).countDocuments(keyFilter(key))
                + database.getCollection(PROJECTION_PIECE).countDocuments(keyFilter(key)));
    }

    /** A fresh database at the previous release's schema version, reached through the link. */
    @Override
    public UpgradeTarget upgradeTarget() {
        String database = "upgrade_" + ++upgrades;
        PreviousReleaseStore previous = new PreviousReleaseStore(linkClient, database);
        previous.migrate();
        return new UpgradeTarget(PreviousReleaseStore.SCHEMA_VERSION, previous, new MongoCatalogStore(linkClient, database));
    }

    @Override
    public Faults faults() {
        return this;
    }

    @Override
    public WrittenRows writtenRows(CommitBatch batch) {
        var database = primary().direct.getDatabase(DATABASE);
        BsonDocument byCommit = new BsonDocument("commitId", uuid(batch.commitId()));
        return new WrittenRows(
                (int) database.getCollection(POINTER).countDocuments(new BsonDocument("lastCommitId", uuid(batch.commitId()))),
                (int) database.getCollection(JOURNAL).countDocuments(byCommit),
                (int) database.getCollection(OUTBOX).countDocuments(byCommit),
                (int) database.getCollection(IDEMPOTENCY).countDocuments(new BsonDocument("key",
                        new BsonString(batch.idempotency().map(result -> result.key()).orElse("")))));
    }

    /** MongoDB assigns no journal sequence (no global counter document); insertion order is the driver's ObjectId order. */
    @Override
    public List<Long> journalVersions(PointerKey key) {
        List<Long> versions = new ArrayList<>();
        primary().direct.getDatabase(DATABASE).getCollection(JOURNAL, BsonDocument.class).find(keyFilter(key))
                .sort(new BsonDocument("_id", new BsonInt32(1)))
                .forEach(entry -> versions.add(entry.getInt64("newVersion").getValue()));
        return versions;
    }

    /** A database through the link with the concerns the store gives its own. */
    MongoDatabase database(String name) {
        return linkClient.getDatabase(name).withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY);
    }

    /** The schema runner's record in {@code database}. */
    BsonDocument schemaVersion(MongoDatabase database) {
        return database.getCollection(MongoSchema.SCHEMA_VERSION, BsonDocument.class)
                .find(new BsonDocument("_id", new BsonString("schema"))).first();
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
        return Set.of("mongo.not-replica-set", "mongo.too-few-voters", "mongo.weak-write-concern");
    }

    @Override
    public List<UnsafeSetting> unsafeSettings() {
        return List.of(
                new UnsafeSetting("a standalone server, not a replica set", "mongo.not-replica-set",
                        () -> onScratchServer(false, client -> new MongoCatalogStore(client, DATABASE).selfCheck())),
                new UnsafeSetting("a replica set of one member", "mongo.too-few-voters",
                        () -> onScratchServer(true, client -> new MongoCatalogStore(client, DATABASE).selfCheck())),
                new UnsafeSetting("three members, one of them with votes 0", "mongo.too-few-voters",
                        () -> selfCheckWithConfig(config -> {
                            BsonDocument member = memberConfig(config, secondaries().getFirst());
                            member.put("votes", new BsonInt32(0));
                            member.put("priority", new BsonInt32(0));
                        })),
                new UnsafeSetting("writeConcernMajorityJournalDefault false", "mongo.weak-write-concern",
                        () -> selfCheckWithConfig(config -> config.put("writeConcernMajorityJournalDefault", BsonBoolean.FALSE))),
                new UnsafeSetting("default write concern {w: 1}", "mongo.weak-write-concern",
                        () -> selfCheckWithDefaultWriteConcern(new BsonInt32(1))));
    }

    /** Self-check of the main set with its configuration changed by {@code change}, then changed back. */
    private List<SafetyFinding> selfCheckWithConfig(Consumer<BsonDocument> change) {
        BsonDocument original = primary().admin(new BsonDocument("replSetGetConfig", new BsonInt32(1))).getDocument("config");
        BsonDocument changed = original.clone();
        change.accept(changed);
        reconfigure(changed, original.getNumber("version").intValue() + 1);
        try {
            return store(CommitHook.NONE).selfCheck();
        } finally {
            BsonDocument current = primary().admin(new BsonDocument("replSetGetConfig", new BsonInt32(1))).getDocument("config");
            reconfigure(original, current.getNumber("version").intValue() + 1);
        }
    }

    private void reconfigure(BsonDocument config, int version) {
        BsonDocument next = config.clone();
        next.put("version", new BsonInt32(version));
        next.remove("term");
        primary().admin(new BsonDocument("replSetReconfig", next).append("maxTimeMS", new BsonInt32(60_000)));
    }

    private static BsonDocument memberConfig(BsonDocument config, Member member) {
        return config.getArray("members").stream().map(BsonValue::asDocument)
                .filter(entry -> entry.getString("host").getValue().equals(member.address())).findFirst().orElseThrow();
    }

    private List<SafetyFinding> selfCheckWithDefaultWriteConcern(BsonValue w) {
        setDefaultWriteConcern(w);
        try {
            return store(CommitHook.NONE).selfCheck();
        } finally {
            setDefaultWriteConcern(new BsonString("majority"));
        }
    }

    private void setDefaultWriteConcern(BsonValue w) {
        primary().admin(new BsonDocument("setDefaultRWConcern", new BsonInt32(1))
                .append("defaultWriteConcern", new BsonDocument("w", w))
                .append("writeConcern", new BsonDocument("w", new BsonString("majority"))));
    }

    /** Runs {@code check} against a fresh single server: standalone, or an initiated replica set of one. */
    private <T> T onScratchServer(boolean replicaSet, Function<MongoClient, T> check) {
        List<String> extra = new ArrayList<>(List.of("--port", "27017"));
        if (replicaSet) {
            extra.addAll(List.of("--replSet", "scratch"));
        }
        try (GenericContainer<?> server = new GenericContainer<>(mongoImage)
                .withExposedPorts(27017)
                .withCommand(mongod(extra.toArray(String[]::new)))
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            server.start();
            try (MongoClient client = directClient(server.getHost(), server.getMappedPort(27017))) {
                if (replicaSet) {
                    admin(client, new BsonDocument("replSetInitiate", new BsonDocument("_id", new BsonString("scratch"))
                            .append("members", new BsonArray(List.of(new BsonDocument("_id", new BsonInt32(0))
                                    .append("host", new BsonString("localhost:27017")))))));
                    await(SETTLE, () -> admin(client, new BsonDocument("hello", new BsonInt32(1)))
                            .getBoolean("isWritablePrimary").getValue(), "the scratch set to elect itself");
                }
                return check.apply(client);
            }
        }
    }

    // ---- Faults ----

    @Override
    public void cutLink() {
        members.forEach(member -> toxi(() -> {
            member.proxy.disable();
            return null;
        }));
    }

    @Override
    public void dropRequests() {
        members.forEach(member -> toxi(() -> member.proxy.toxics().timeout("drop-requests", ToxicDirection.UPSTREAM, 0)));
    }

    @Override
    public void dropResponses() {
        members.forEach(member -> toxi(() -> member.proxy.toxics().timeout("drop-responses", ToxicDirection.DOWNSTREAM, 0)));
    }

    /**
     * The primary's next {@code sends} commitTransaction commands commit and then answer with a
     * retryable write-concern error, so the client cannot tell that the commit landed.
     */
    void commitAnswersUnknown(int sends) {
        primary().admin(new BsonDocument("configureFailPoint", new BsonString("failCommand"))
                .append("mode", new BsonDocument("times", new BsonInt32(sends)))
                .append("data", new BsonDocument("failCommands", new BsonArray(List.of(new BsonString("commitTransaction"))))
                        .append("writeConcernError", new BsonDocument("code", new BsonInt32(91))
                                .append("errmsg", new BsonString("injected: commit outcome unknown")))));
    }

    @Override
    public void healLink() {
        toxi(() -> {
            toxiproxyClient.reset();
            return null;
        });
        awaitLinkToPrimary();
    }

    /** Stops every secondary fetching the primary's oplog; heartbeats go on, so the primary stays primary. */
    @Override
    public void stallReplication() {
        for (Member secondary : secondaries()) {
            secondary.failPoint("stopReplProducer", true);
            stalled.add(secondary);
        }
        Member primary = primary();
        await(SETTLE, () -> {
            try {
                probes(primary, WriteConcern.MAJORITY.withWTimeout(500, TimeUnit.MILLISECONDS))
                        .insertOne(new BsonDocument("at", new BsonInt64(System.nanoTime())));
                return false;
            } catch (MongoException e) {
                return true;
            }
        }, "a majority write to wait at the primary");
    }

    @Override
    public void resumeReplication() {
        stalled.forEach(member -> member.failPoint("stopReplProducer", false));
        stalled.clear();
        awaitReplicated(WriteConcern.MAJORITY);
    }

    /**
     * Stops one secondary applying the oplog; the other secondary still makes the majority, so
     * commits are acknowledged at once. That secondary applies nothing until {@link #restore},
     * which is later than any lag the suite asks for.
     */
    @Override
    public void lagReplicaApply(Duration lag) {
        Member replica = secondaries().getFirst();
        replica.failPoint("rsSyncApplyStop", true);
        applyStopped = replica;
        Member primary = primary();
        BsonDocument probe = new BsonDocument("_id", new BsonString(UUID.randomUUID().toString()));
        probes(primary, WriteConcern.MAJORITY.withWTimeout(10, TimeUnit.SECONDS)).insertOne(probe);
        if (replica.direct.getDatabase("harness").getCollection("probe", BsonDocument.class)
                .withReadPreference(ReadPreference.secondary()).find(probe).first() != null) {
            throw new IllegalStateException(replica.name + " applied a write while its applier was stopped");
        }
    }

    @Override
    public Optional<Pointer> readPointerOnReplica(PointerKey key) {
        Member replica = applyStopped != null ? applyStopped : secondaries().getFirst();
        return Optional.ofNullable(replica.direct.getDatabase(DATABASE).getCollection(POINTER, BsonDocument.class)
                .withReadPreference(ReadPreference.secondary()).find(keyFilter(key)).first()).map(doc -> new Pointer(doc.getString("metadataLocation").getValue(),
                doc.getInt64("version").getValue(), doc.getString("sha256").getValue(), doc.getBinary("projection").getData()));
    }

    @Override
    public void killPrimary() {
        Member primary = primary();
        primary.container.getDockerClient().killContainerCmd(primary.container.getContainerId()).exec();
        killed = primary;
    }

    /**
     * Lets the surviving members replicate again and has one of them elected at once rather than
     * after the election timeout. The stores' client finds the new primary itself.
     */
    @Override
    public void promoteReplica() {
        List<Member> survivors = members.stream().filter(member -> member != killed).toList();
        for (Member survivor : survivors) {
            if (stalled.remove(survivor)) {
                survivor.failPoint("stopReplProducer", false);
            }
        }
        await(SETTLE, () -> {
            if (survivors.stream().anyMatch(Member::isPrimary)) {
                return true;
            }
            try {
                survivors.getFirst().admin(new BsonDocument("replSetStepUp", new BsonInt32(1)));
            } catch (MongoException e) {
                // Not electable yet; the next round asks again.
            }
            return false;
        }, "a surviving member to become primary");
        awaitReplicated(WriteConcern.MAJORITY);
    }

    @Override
    public void restore() {
        healLink();
        for (Member member : members) {
            if (member != killed) {
                member.failPoint("failCommand", false);
            }
        }
        for (Member member : stalled) {
            member.failPoint("stopReplProducer", false);
        }
        stalled.clear();
        if (applyStopped != null) {
            applyStopped.failPoint("rsSyncApplyStop", false);
            applyStopped = null;
        }
        if (killed != null) {
            // It rejoins as a secondary and rolls back whatever it never replicated.
            Member restarted = killed;
            killed = null;
            restarted.container.getDockerClient().startContainerCmd(restarted.container.getContainerId()).exec();
            await(SETTLE, () -> restarted.publishedPort() != null, restarted.name + " to publish its port again");
            restarted.connect();
            await(SETTLE, restarted::isSecondary, restarted.name + " to rejoin as a secondary");
        }
        awaitReplicated(new WriteConcern(members.size()));
    }

    @Override
    public void close() {
        linkClient.close();
        for (Member member : members) {
            if (member.direct != null) {
                member.direct.close();
            }
            member.container.stop();
        }
        toxiproxy.stop();
        network.close();
    }

    // ---- helpers ----

    private static MongoClient directClient(String host, int port) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + host + ":" + port + "/?directConnection=true"))
                .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build());
    }

    static BsonDocument admin(MongoClient client, BsonDocument command) {
        return client.getDatabase("admin").runCommand(command, BsonDocument.class);
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static void await(Duration limit, BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
            pause(Duration.ofMillis(100));
        }
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
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
