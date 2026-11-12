package keel.catalog.store.mongo;

import static keel.catalog.store.mongo.Docs.keyFilter;
import static keel.catalog.store.mongo.Docs.uuid;

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
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogSettings;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitHook;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitResult;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.Grant;
import keel.catalog.store.GrantRecord;
import keel.catalog.store.IdempotencyClaim;
import keel.catalog.store.IdempotencyResult;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.KeyState;
import keel.catalog.store.LegalHold;
import keel.catalog.store.Name;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.ObjectCreation;
import keel.catalog.store.OutboxBatch;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.SafetyFinding;
import keel.catalog.store.SchemaVersion;
import keel.catalog.store.Securable;
import keel.catalog.store.StorageProfile;
import keel.catalog.store.StoreException;
import keel.catalog.store.TrustedEngine;
import keel.catalog.store.WriteResult;
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
    public Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        return objects.listPurgeEligible(now, page);
    }

    @Override
    public WriteResult purgeObject(UUID objectId, Change change) {
        return objects.purge(objectId, change);
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
