package keel.catalog.store.mongo;

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
import com.mongodb.client.result.UpdateResult;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitHook;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.CommitResult;
import keel.catalog.store.CommitStore;
import keel.catalog.store.IdempotencyResult;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.PointerSwap;
import keel.catalog.store.SafetyFinding;
import keel.catalog.store.StoreException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.UuidRepresentation;

import static com.mongodb.MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL;

/**
 * The commit store of the previous release (M1), frozen: a copy of {@code MongoCatalogStore} at
 * dee045f, at schema version 2. The upgrade test runs it against a database while the current
 * release migrates that database. Only its class name, visibility and schema steps differ.
 */
final class PreviousReleaseStore implements CommitStore {

    /** The schema version of the previous release: steps 1 and 2. */
    static final int SCHEMA_VERSION = 2;

    private static final String CATALOG = "catalog";
    private static final String POINTER = "pointer";
    private static final String JOURNAL = "journal";
    private static final String OUTBOX = "outbox";
    private static final String IDEMPOTENCY = "idempotency";
    private static final String DURABILITY_BARRIER = "durability_barrier";

    /**
     * Attempts of commitTransaction on the same session while the answer is
     * UnknownTransactionCommitResult. The driver resends commitTransaction once by itself within
     * each attempt, whatever retryWrites says.
     */
    private static final int COMMIT_ATTEMPTS = 3;
    /** How long a commit keeps retrying while a concurrent transaction holds one of its pointers. */
    private static final Duration WRITE_CONFLICT_BUDGET = Duration.ofSeconds(10);
    private static final int WRITE_CONFLICT = 112;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary())
            .build();
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
    private final CommitHook commitHook;

    PreviousReleaseStore(MongoClient client, String databaseName) {
        this(client, databaseName, CommitHook.NONE);
    }

    /** {@code commitHook} is a test seam called just before each commitTransaction is sent. */
    private PreviousReleaseStore(MongoClient client, String databaseName, CommitHook commitHook) {
        this.client = client;
        this.database = client.getDatabase(databaseName)
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY);
        this.commitHook = commitHook;
    }

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        try {
            BsonDocument found = collection(CATALOG).find(new BsonDocument("nameNorm", new BsonString(normalizedName))).first();
            return Optional.ofNullable(found).map(doc -> new CatalogRecord(uuid(doc.get("_id")),
                    doc.getString("nameNorm").getValue(), doc.getString("name").getValue()));
        } catch (MongoException e) {
            throw new StoreException("catalog lookup failed", e);
        }
    }

    @Override
    public Optional<Pointer> readPointer(PointerKey key) {
        try {
            return Optional.ofNullable(collection(POINTER).find(keyFilter(key)).first()).map(PreviousReleaseStore::pointer);
        } catch (MongoException e) {
            throw new StoreException("pointer read failed for " + key, e);
        }
    }

    @Override
    public CommitResult commit(CommitBatch batch) {
        try (ClientSession session = client.startSession()) {
            Optional<CommitResult.Conflict> conflict = writeInTransaction(session, batch);
            if (conflict.isPresent()) {
                return conflict.get();
            }
            commitHook.beforeCommit(batch.commitId());
            commitTransaction(session, batch.commitId());
            return new CommitResult.Committed(batch.commitId());
        }
    }

    /**
     * Opens a transaction and writes the whole batch, leaving it open for commitTransaction; or
     * aborts it and returns the conflict. A write conflict with a concurrent transaction aborts
     * this one before anything is sent to commit, so the batch is retried on a fresh snapshot.
     */
    private Optional<CommitResult.Conflict> writeInTransaction(ClientSession session, CommitBatch batch) {
        long deadline = System.nanoTime() + WRITE_CONFLICT_BUDGET.toNanos();
        for (int attempt = 0; ; attempt++) {
            session.startTransaction(TRANSACTION);
            try {
                Optional<CommitResult.Conflict> conflict = swapPointers(session, batch);
                if (conflict.isPresent()) {
                    session.abortTransaction();
                    return conflict;
                }
                writeRecords(session, batch);
                return Optional.empty();
            } catch (MongoException e) {
                session.abortTransaction();
                if (e.getCode() != WRITE_CONFLICT || System.nanoTime() > deadline) {
                    throw new StoreException("commit " + batch.commitId() + " failed before COMMIT was sent", e);
                }
                backOff(attempt);
            }
        }
    }

    /**
     * Compares and writes every pointer in key order. Each write is conditional on the expected
     * pointer, so it is the compare and the lock at once; a create inserts only if no pointer
     * exists. When nothing matched, the transaction's snapshot shows what is there instead.
     */
    private Optional<CommitResult.Conflict> swapPointers(ClientSession session, CommitBatch batch) {
        MongoCollection<BsonDocument> pointers = collection(POINTER);
        for (PointerSwap swap : batch.swaps()) {
            BsonDocument next = pointerFields(swap.next(), batch.commitId(), batch.committedAt());
            if (swap.expected().isEmpty()) {
                UpdateResult created = pointers.updateOne(session, keyFilter(swap.key()),
                        new BsonDocument("$setOnInsert", next), new UpdateOptions().upsert(true));
                if (created.getUpsertedId() != null) {
                    continue;
                }
            } else {
                Pointer expected = swap.expected().get();
                BsonDocument filter = keyFilter(swap.key())
                        .append("version", new BsonInt64(expected.version()))
                        .append("metadataLocation", new BsonString(expected.metadataLocation()));
                if (pointers.updateOne(session, filter, new BsonDocument("$set", next)).getMatchedCount() == 1) {
                    continue;
                }
            }
            Optional<Pointer> actual = Optional.ofNullable(pointers.find(session, keyFilter(swap.key())).first())
                    .map(PreviousReleaseStore::pointer);
            return Optional.of(new CommitResult.Conflict(swap.key(), actual));
        }
        return Optional.empty();
    }

    private void writeRecords(ClientSession session, CommitBatch batch) {
        BsonBinary commitId = uuid(batch.commitId());
        BsonDateTime committedAt = time(batch.committedAt());
        List<BsonDocument> journal = new ArrayList<>();
        for (PointerSwap swap : batch.swaps()) {
            journal.add(new BsonDocument("commitId", commitId)
                    .append("objectId", uuid(swap.key().objectId()))
                    .append("branch", new BsonString(swap.key().branch()))
                    .append("committedAt", committedAt)
                    .append("principal", new BsonString(swap.journal().principal()))
                    .append("endUser", text(swap.journal().endUser()))
                    .append("engine", text(swap.journal().engine()))
                    .append("requirements", new BsonString(swap.journal().requirementsJson()))
                    .append("updates", new BsonString(swap.journal().updatesJson()))
                    .append("oldLocation", text(swap.expected().map(Pointer::metadataLocation).orElse(null)))
                    .append("newLocation", new BsonString(swap.next().metadataLocation()))
                    .append("oldVersion", swap.expected().<BsonValue>map(p -> new BsonInt64(p.version())).orElse(BsonNull.VALUE))
                    .append("newVersion", new BsonInt64(swap.next().version())));
        }
        collection(JOURNAL).insertMany(session, journal);
        List<BsonDocument> outbox = new ArrayList<>();
        for (int position = 0; position < batch.outbox().size(); position++) {
            outbox.add(new BsonDocument("commitId", commitId)
                    .append("position", new BsonInt32(position))
                    .append("createdAt", committedAt)
                    .append("type", new BsonString(batch.outbox().get(position).type()))
                    .append("payload", new BsonString(batch.outbox().get(position).payloadJson())));
        }
        if (!outbox.isEmpty()) {
            collection(OUTBOX).insertMany(session, outbox);
        }
        if (batch.idempotency().isPresent()) {
            IdempotencyResult result = batch.idempotency().get();
            collection(IDEMPOTENCY).insertOne(session, new BsonDocument("key", new BsonString(result.key()))
                    .append("principal", new BsonString(result.principal()))
                    .append("requestHash", new BsonString(result.requestHash()))
                    .append("status", new BsonInt32(result.status()))
                    .append("body", new BsonString(result.body()))
                    .append("expiresAt", time(result.expiresAt())));
        }
    }

    /**
     * Sends commitTransaction, and sends it again on the same session while the answer is
     * UnknownTransactionCommitResult: the server applies a transaction's commit at most once
     * (5.3 step 1). The body is never replayed, so a commit that landed can never come back as a
     * conflict.
     */
    private static void commitTransaction(ClientSession session, UUID commitId) {
        for (int attempt = 1; ; attempt++) {
            try {
                session.commitTransaction();
                return;
            } catch (MongoException e) {
                if (attempt == COMMIT_ATTEMPTS || !e.hasErrorLabel(UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    throw new CommitOutcomeUnknownException(commitId, e);
                }
            }
        }
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
                if (e.getCode() != WRITE_CONFLICT || System.nanoTime() > deadline) {
                    throw e;
                }
            } finally {
                session.abortTransaction();
            }
            backOff(attempt);
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
            MongoSchema.migrate(database, MongoSchema.STEPS.subList(0, SCHEMA_VERSION));
        } catch (MongoException e) {
            throw new StoreException("schema migration failed", e);
        }
    }

    private MongoCollection<BsonDocument> collection(String name) {
        return database.getCollection(name, BsonDocument.class);
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L << Math.min(attempt, 6)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted while waiting out a write conflict", e);
        }
    }

    // ---- documents ----

    private static BsonDocument keyFilter(PointerKey key) {
        return new BsonDocument("objectId", uuid(key.objectId())).append("branch", new BsonString(key.branch()));
    }

    private static Pointer pointer(BsonDocument doc) {
        return new Pointer(doc.getString("metadataLocation").getValue(), doc.getInt64("version").getValue(),
                doc.getString("sha256").getValue(), doc.getBinary("projection").getData());
    }

    private static BsonDocument pointerFields(Pointer pointer, UUID commitId, Instant committedAt) {
        return new BsonDocument("metadataLocation", new BsonString(pointer.metadataLocation()))
                .append("version", new BsonInt64(pointer.version()))
                .append("sha256", new BsonString(pointer.sha256()))
                .append("projection", new BsonBinary(pointer.projection()))
                .append("lastCommitId", uuid(commitId))
                .append("updatedAt", time(committedAt));
    }

    private static BsonBinary uuid(UUID id) {
        return new BsonBinary(id, UuidRepresentation.STANDARD);
    }

    private static UUID uuid(BsonValue value) {
        return value.asBinary().asUuid(UuidRepresentation.STANDARD);
    }

    private static BsonDateTime time(Instant instant) {
        return new BsonDateTime(instant.toEpochMilli());
    }

    private static BsonValue text(String value) {
        return value == null ? BsonNull.VALUE : new BsonString(value);
    }
}
