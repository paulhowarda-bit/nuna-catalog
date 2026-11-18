package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.keyFilter;
import static nuna.catalog.store.mongo.Docs.uuid;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.OutboxBatch;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.SchemaVersion;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;

/**
 * MongoDB implementation (D7): multi-document transactions with snapshot reads and majority
 * writes on the primary, for the whole storage contract (docs/storage-design.md sections 4, 5.3,
 * 7 and 9). Reads outside a transaction use majority read concern; reads that must see one
 * point in time use a snapshot session.
 *
 * <p>The caller's client must reach a replica set and sets the socket timeout that bounds every
 * wait. Its retryWrites setting does not matter: the driver never retries a write inside a
 * transaction, and it resends commitTransaction once per call whatever the setting says.
 * The catalog role needs {@code clusterMonitor} for the self-check and findCommit.
 */
public final class MongoCatalogStore implements CatalogStore {

    static final String CATALOG = "catalog";
    static final String POINTER = "pointer";
    static final String PROJECTION_PIECE = "projection_piece";
    static final String JOURNAL = "journal";
    static final String OUTBOX = "outbox";
    static final String OUTBOX_RELAY = "outbox_relay";
    static final String IDEMPOTENCY = "idempotency";
    static final String DURABILITY_BARRIER = "durability_barrier";
    static final String NAMESPACE = "namespace";
    static final String OBJECT = "catalog_object";
    static final String GRANT = "grant";
    static final String STORAGE_PROFILE = "storage_profile";
    static final String ATTRIBUTE = "governance_attribute";
    static final String HOLD = "legal_hold";
    static final String ENGINE = "trusted_engine";
    static final String PENDING_DELETION = "pending_deletion";
    static final String TABLE_HEALTH = "table_health";
    static final String MAINTENANCE_SCAN = "maintenance_scan";

    /** Projections longer than this are stored in pieces (section 7). */
    public static final int PIECE_BYTES = Pointers.PIECE_BYTES;

    /**
     * The findCommit marker is always aborted. Its abort uses w:1 because an abort with w:majority
     * waits for the newest write on the primary to replicate, which would hide whether the
     * durability barrier does that job.
     */
    private static final TransactionOptions MARKER = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.W1)
            .readPreference(ReadPreference.primary())
            .build();

    private final MongoClient client;
    private final MongoDatabase database;
    private final Transactions transactions;
    private final MongoNamespaces namespaces;
    private final MongoObjects objects;
    private final MongoGrants grants;
    private final MongoIdempotency idempotency;
    private final MongoOutbox outbox;
    private final MongoGovernance governance;
    private final MongoDeletions deletions;
    private final MongoHealth health;

    public MongoCatalogStore(MongoClient client, String databaseName) {
        this(client, databaseName, CommitHook.NONE);
    }

    /** {@code commitHook} is a test seam called just before each commitTransaction is sent. */
    public MongoCatalogStore(MongoClient client, String databaseName, CommitHook commitHook) {
        this(client, databaseName, commitHook, ObjectId::new, PIECE_BYTES);
    }

    /**
     * Test seams: {@code entryIds} are the ids of outbox entries, which break ties of the Lamport
     * clock; {@code pieceBytes} is the piece budget, so a race over pieces can use small ones.
     */
    MongoCatalogStore(MongoClient client, String databaseName, CommitHook commitHook, Supplier<ObjectId> entryIds,
            int pieceBytes) {
        this.client = client;
        this.database = client.getDatabase(databaseName)
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY);
        this.transactions = new Transactions(client, database, commitHook, entryIds, pieceBytes);
        this.namespaces = new MongoNamespaces(transactions);
        this.objects = new MongoObjects(transactions);
        this.grants = new MongoGrants(transactions);
        this.idempotency = new MongoIdempotency(transactions);
        this.outbox = new MongoOutbox(transactions);
        this.governance = new MongoGovernance(transactions);
        this.deletions = new MongoDeletions(transactions);
        this.health = new MongoHealth(transactions);
    }

    // ---- commit core (sections 4, 5.3 and 7) ----

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        return transactions.read("catalog lookup", () -> Optional.ofNullable(collection(CATALOG)
                        .find(new BsonDocument("nameNorm", new BsonString(normalizedName))).first())
                .map(doc -> new CatalogRecord(uuid(doc.get("_id")), doc.getString("nameNorm").getValue(),
                        doc.getString("name").getValue())));
    }

    /** Read in a snapshot session, so a projection in pieces is reassembled from one version. */
    @Override
    public Optional<Pointer> readPointer(PointerKey key) {
        return transactions.snapshot("pointer read for " + key, session -> Pointers.read(database, session, key));
    }

    @Override
    public CommitResult commit(CommitBatch batch) {
        return transactions.write(batch.commitId(), "commit " + batch.commitId(), tx -> {
            Pointers.see(tx, batch);
            Optional<CommitResult.Inactive> inactive = Pointers.markObjects(tx, batch);
            if (inactive.isPresent()) {
                return inactive.get();
            }
            Optional<CommitResult.Conflict> conflict = Pointers.swap(tx, batch, transactions.pieceBytes());
            if (conflict.isPresent()) {
                return conflict.get();
            }
            Pointers.records(tx, batch, transactions.entryIds());
            return new CommitResult.Committed(batch.commitId());
        }, result -> result instanceof CommitResult.Committed);
    }

    @Override
    public CommitLookup findCommit(UUID commitId, PointerKey anyKeyInBatch) {
        try (ClientSession markerSession = client.startSession();
             ClientSession ordered = client.startSession(ClientSessionOptions.builder().causallyConsistent(true).build())) {
            awaitInFlightCommit(markerSession, anyKeyInBatch);
            // Majority-acknowledged, so the primary is still the primary and every commit it
            // applied before this write is on a majority. In a fresh causal session the barrier is
            // the only operation the read must follow: it reads a majority snapshot that holds it,
            // on whichever member is primary by then.
            collection(DURABILITY_BARRIER).updateOne(ordered, new BsonDocument("_id", new BsonString("barrier")),
                    new BsonDocument("$inc", new BsonDocument("counter", new BsonInt64(1))), new UpdateOptions().upsert(true));
            BsonDocument journaled = collection(JOURNAL).find(ordered, keyFilter(anyKeyInBatch)
                    .append("commitId", uuid(commitId))).first();
            return journaled == null ? CommitLookup.NOT_COMMITTED : CommitLookup.COMMITTED;
        } catch (MongoException e) {
            throw new StoreException("cannot decide whether commit " + commitId + " committed", e);
        }
    }

    /**
     * Blocks until no transaction that wrote the pointer at {@code key} is still open. A marker
     * write on that pointer conflicts with any open transaction that wrote it, including one that
     * is creating it: the upsert collides with the in-flight insert of the same key, where a
     * plain update would match nothing and pass. The marker is never committed. The server
     * aborts an open transaction after transactionLifetimeLimitSeconds, so the wait is bounded.
     */
    private void awaitInFlightCommit(ClientSession session, PointerKey key) {
        long lifetimeSeconds = client.getDatabase("admin").runCommand(
                        new BsonDocument("getParameter", new BsonInt32(1)).append("transactionLifetimeLimitSeconds", new BsonInt32(1)),
                        BsonDocument.class)
                .getNumber("transactionLifetimeLimitSeconds").longValue();
        long deadline = System.nanoTime() + Duration.ofSeconds(2 * lifetimeSeconds + 10).toNanos();
        // A fresh value every time: an update that changes nothing writes nothing and conflicts with nothing.
        BsonDocument marker = new BsonDocument("$set", new BsonDocument("marker", uuid(UUID.randomUUID())));
        for (int attempt = 0; ; attempt++) {
            session.startTransaction(MARKER);
            try {
                collection(POINTER).updateOne(session, keyFilter(key), marker, new UpdateOptions().upsert(true));
                return;
            } catch (MongoException e) {
                if (e.getCode() != Transactions.WRITE_CONFLICT || System.nanoTime() > deadline) {
                    throw e;
                }
            } finally {
                session.abortTransaction();
            }
            Transactions.backOff(attempt);
        }
    }

    @Override
    public List<SafetyFinding> selfCheck() {
        try {
            MongoDatabase admin = client.getDatabase("admin").withReadPreference(ReadPreference.primary());
            BsonDocument hello = admin.runCommand(new BsonDocument("hello", new BsonInt32(1)), BsonDocument.class);
            if (!hello.containsKey("setName")) {
                return List.of(new SafetyFinding("mongo.not-replica-set",
                        "the server is not a replica set member; transactions and majority writes need a replica set"));
            }
            List<SafetyFinding> findings = new ArrayList<>();
            BsonDocument config = admin.runCommand(new BsonDocument("replSetGetConfig", new BsonInt32(1)), BsonDocument.class)
                    .getDocument("config");
            long voters = config.getArray("members").stream().map(BsonValue::asDocument)
                    .filter(member -> member.getNumber("votes", new BsonInt32(1)).intValue() > 0
                            && !member.getBoolean("arbiterOnly", BsonBoolean.FALSE).getValue())
                    .count();
            if (voters < 3) {
                findings.add(new SafetyFinding("mongo.too-few-voters", "the replica set has " + voters
                        + " voting data-bearing members; at least 3 are needed for a majority to survive losing one"));
            }
            BsonDocument defaults = admin.runCommand(new BsonDocument("getDefaultRWConcern", new BsonInt32(1)), BsonDocument.class);
            BsonDocument writeConcern = defaults.getDocument("defaultWriteConcern");
            BsonValue w = writeConcern.get("w");
            boolean majority = w.isString() ? w.asString().getValue().equals("majority")
                    : w.asNumber().intValue() > voters / 2;
            boolean journalDefault = config.getBoolean("writeConcernMajorityJournalDefault", BsonBoolean.TRUE).getValue();
            if (!majority || !journalDefault) {
                findings.add(new SafetyFinding("mongo.weak-write-concern", "the default write concern is "
                        + writeConcern.toJson() + " (" + defaults.getString("defaultWriteConcernSource", new BsonString("unknown")).getValue()
                        + ") and writeConcernMajorityJournalDefault is " + journalDefault
                        + "; the default must be majority and majority writes must be journaled"));
            }
            return findings;
        } catch (MongoException e) {
            throw new StoreException("self-check failed", e);
        }
    }

    @Override
    public void migrate() {
        try {
            MongoSchema.migrate(database, MongoSchema.STEPS);
        } catch (MongoException e) {
            throw new StoreException("schema migration failed", e);
        }
    }

    @Override
    public SchemaVersion schemaVersion() {
        return transactions.read("schema version read", () -> {
            BsonDocument schema = collection(MongoSchema.SCHEMA_VERSION).find(new BsonDocument("_id", new BsonString("schema"))).first();
            if (schema == null) {
                return new SchemaVersion(0, "empty");
            }
            int version = schema.getInt32("version").getValue();
            String description = schema.getArray("applied").stream().map(BsonValue::asDocument)
                    .filter(applied -> applied.getInt32("version").getValue() == version)
                    .map(applied -> applied.getString("description").getValue())
                    .findFirst().orElse("");
            return new SchemaVersion(version, description);
        });
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return database.getCollection(name, BsonDocument.class);
    }

    // ---- namespaces ----

    @Override
    public WriteResult createNamespace(NamespaceRecord namespace, Change change) {
        return namespaces.create(namespace, change);
    }

    @Override
    public Optional<NamespaceRecord> findNamespace(UUID catalogId, List<String> normalizedPath) {
        return namespaces.find(catalogId, normalizedPath);
    }

    @Override
    public Optional<NamespaceRecord> readNamespace(UUID namespaceId) {
        return namespaces.read(namespaceId);
    }

    @Override
    public Page<NamespaceRecord> listNamespaces(UUID parentId, PageRequest page) {
        return namespaces.list(parentId, page);
    }

    @Override
    public WriteResult updateNamespaceProperties(UUID namespaceId, long expectedVersion, Map<String, String> properties,
                                                 Change change) {
        return namespaces.updateProperties(namespaceId, expectedVersion, properties, change);
    }

    @Override
    public WriteResult dropNamespace(UUID namespaceId, Change change) {
        return namespaces.drop(namespaceId, change);
    }

    // ---- objects ----

    @Override
    public WriteResult createObject(ObjectCreation creation) {
        return objects.create(creation);
    }

    @Override
    public Optional<CatalogObject> findObject(UUID namespaceId, String normalizedName) {
        return objects.find(namespaceId, normalizedName);
    }

    @Override
    public Optional<CatalogObject> readObject(UUID objectId) {
        return objects.read(objectId);
    }

    @Override
    public Page<CatalogObject> listObjects(UUID namespaceId, CatalogObject.Kind kind, CatalogObject.State state,
                                           PageRequest page) {
        return objects.list(namespaceId, kind, state, page);
    }

    @Override
    public WriteResult renameObject(UUID objectId, UUID namespaceId, Name name, Change change) {
        return objects.rename(objectId, namespaceId, name, change);
    }

    @Override
    public WriteResult dropObject(UUID objectId, Instant purgeAfter, Change change) {
        return objects.drop(objectId, purgeAfter, change);
    }

    @Override
    public WriteResult undropObject(UUID objectId, UUID namespaceId, Name name, Change change) {
        return objects.undrop(objectId, namespaceId, name, change);
    }

    @Override
    public WriteResult unregisterObject(UUID objectId, Pointer expected, Change change) {
        return objects.unregister(objectId, expected, change);
    }

    @Override
    public WriteResult reviveObject(UUID objectId, CatalogObject.State from, UUID namespaceId, Name name,
                                    CommitBatch batch) {
        return objects.revive(objectId, from, namespaceId, name, batch);
    }

    @Override
    public Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        return objects.listPurgeEligible(now, page);
    }

    @Override
    public WriteResult claimPurge(UUID objectId, String claimant, Change change) {
        return objects.claimPurge(objectId, claimant, change);
    }

    @Override
    public WriteResult completePurge(UUID objectId, String claimant, Change change) {
        return objects.completePurge(objectId, claimant, change);
    }

    // ---- maintenance (D30) ----

    @Override
    public Page<CatalogObject> listActiveObjects(CatalogObject.Kind kind, PageRequest page) {
        return objects.listActive(kind, page);
    }

    @Override
    public WriteResult recordDeletions(UUID objectId, List<String> locations, Instant notBefore, Change change) {
        return deletions.record(objectId, locations, notBefore, change);
    }

    @Override
    public Page<PendingDeletion> listDueDeletions(Instant now, PageRequest page) {
        return deletions.listDue(now, page);
    }

    @Override
    public WriteResult completeDeletions(UUID objectId, List<UUID> ids, Change change) {
        return deletions.complete(objectId, ids, change);
    }

    // ---- the worker's state (D38) ----

    @Override
    public WriteResult recordAssessment(UUID objectId, TableAssessment assessment) {
        return health.recordAssessment(objectId, assessment);
    }

    @Override
    public WriteResult recordCompaction(UUID objectId, CompactionRun compaction) {
        return health.recordCompaction(objectId, compaction);
    }

    @Override
    public Optional<TableHealth> readTableHealth(UUID objectId) {
        return health.read(objectId);
    }

    @Override
    public Page<TableHealth> listNeediestTables(PageRequest page) {
        return health.listNeediest(page);
    }

    @Override
    public void recordScan(MaintenanceScan scan, int keep) {
        health.recordScan(scan, keep);
    }

    @Override
    public List<MaintenanceScan> listScans() {
        return health.listScans();
    }

    // ---- grants ----

    @Override
    public WriteResult putGrant(Grant grant, String grantedBy, Change change) {
        return grants.put(grant, grantedBy, change);
    }

    @Override
    public WriteResult revokeGrant(Grant grant, Change change) {
        return grants.revoke(grant, change);
    }

    @Override
    public List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
        return grants.grantsFor(securables, groups);
    }

    @Override
    public Page<GrantRecord> listGrantsOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        return grants.listOfGroups(catalogId, groups, page);
    }

    @Override
    public Page<GrantRecord> listGrantsOn(Securable securable, PageRequest page) {
        return grants.listOn(securable, page);
    }

    // ---- idempotency ----

    @Override
    public KeyState claimKey(IdempotencyClaim claim) {
        return idempotency.claim(claim);
    }

    @Override
    public KeyState readKey(String key, Instant now) {
        return idempotency.read(key, now);
    }

    @Override
    public KeyState awaitKey(String key, Duration limit) {
        return idempotency.await(key, limit);
    }

    @Override
    public WriteResult completeKey(IdempotencyResult result, UUID changeId) {
        return idempotency.complete(result, changeId);
    }

    @Override
    public WriteResult releaseKey(String key, UUID changeId) {
        return idempotency.release(key, changeId);
    }

    @Override
    public int expireKeys(Instant now, int limit) {
        return idempotency.expire(now, limit);
    }

    // ---- outbox and journal ----

    @Override
    public Optional<OutboxBatch> claimOutbox(UUID relayId, Instant now, Duration lease, int limit) {
        return outbox.claim(relayId, now, lease, limit);
    }

    @Override
    public WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now) {
        return outbox.markRelayed(relayId, entryIds, now);
    }

    @Override
    public Page<JournalRecord> readJournal(PointerKey key, Instant from, Instant to, PageRequest page) {
        return outbox.readJournal(key, from, to, page);
    }

    // ---- governance ----

    @Override
    public WriteResult createCatalog(CatalogRecord catalog, CatalogSettings settings, Change change) {
        return governance.createCatalog(catalog, settings, change);
    }

    @Override
    public Optional<CatalogSettings> catalogSettings(UUID catalogId) {
        return governance.catalogSettings(catalogId);
    }

    @Override
    public WriteResult putStorageProfile(StorageProfile profile, Change change) {
        return governance.putStorageProfile(profile, change);
    }

    @Override
    public Optional<StorageProfile> readStorageProfile(UUID profileId) {
        return governance.readStorageProfile(profileId);
    }

    @Override
    public WriteResult putAttribute(GovernanceAttribute attribute, Change change) {
        return governance.putAttribute(attribute, change);
    }

    @Override
    public WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, Change change) {
        return governance.removeAttribute(target, kind, change);
    }

    @Override
    public List<GovernanceAttribute> attributesOf(Set<Securable> targets) {
        return governance.attributesOf(targets);
    }

    @Override
    public WriteResult placeHold(LegalHold hold, Change change) {
        return governance.placeHold(hold, change);
    }

    @Override
    public WriteResult releaseHold(UUID holdId, Change change) {
        return governance.releaseHold(holdId, change);
    }

    @Override
    public List<LegalHold> holdsOn(Set<Securable> targets) {
        return governance.holdsOn(targets);
    }

    @Override
    public WriteResult putTrustedEngine(TrustedEngine engine, Change change) {
        return governance.putTrustedEngine(engine, change);
    }

    @Override
    public WriteResult removeTrustedEngine(String issuer, String clientId, Change change) {
        return governance.removeTrustedEngine(issuer, clientId, change);
    }

    @Override
    public Optional<TrustedEngine> findTrustedEngine(String issuer, String clientId) {
        return governance.findTrustedEngine(issuer, clientId);
    }

    @Override
    public List<TrustedEngine> listTrustedEngines() {
        return governance.listTrustedEngines();
    }
}
