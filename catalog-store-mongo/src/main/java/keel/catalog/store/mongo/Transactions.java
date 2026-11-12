package keel.catalog.store.mongo;

import static com.mongodb.MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import keel.catalog.store.Change;
import keel.catalog.store.CommitHook;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.StoreException;
import keel.catalog.store.WriteResult;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bson.types.ObjectId;

/**
 * Runs the store's write transactions: snapshot reads and majority writes on the primary
 * (docs/storage-design.md section 4). A failure before commitTransaction is sent is a clean
 * {@link StoreException}; a failed commitTransaction of a write that carries a commit or change
 * id is an unknown outcome. The transaction body is never replayed once commitTransaction is sent.
 */
final class Transactions {

    /**
     * Attempts of commitTransaction on the same session while the answer is
     * UnknownTransactionCommitResult. The driver resends commitTransaction once by itself within
     * each attempt, whatever retryWrites says.
     */
    static final int COMMIT_ATTEMPTS = 3;
    static final int WRITE_CONFLICT = 112;
    /** How long a write keeps retrying while a concurrent transaction holds a document it writes. */
    private static final Duration WRITE_CONFLICT_BUDGET = Duration.ofSeconds(10);
    static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary())
            .build();
    private static final ClientSessionOptions SNAPSHOT = ClientSessionOptions.builder().snapshot(true).build();

    @FunctionalInterface
    interface Body<T> {
        T run(Tx tx);
    }

    private final MongoClient client;
    private final MongoDatabase database;
    private final CommitHook commitHook;
    private final Supplier<ObjectId> entryIds;
    private final int pieceBytes;

    Transactions(MongoClient client, MongoDatabase database, CommitHook commitHook, Supplier<ObjectId> entryIds, int pieceBytes) {
        this.client = client;
        this.database = database;
        this.commitHook = commitHook;
        this.entryIds = entryIds;
        this.pieceBytes = pieceBytes;
    }

    /** Ids of outbox entries: ObjectIds, ascending within one process. */
    Supplier<ObjectId> entryIds() {
        return entryIds;
    }

    /** Projections longer than this are stored in pieces. */
    int pieceBytes() {
        return pieceBytes;
    }

    MongoDatabase database() {
        return database;
    }

    /** A read outside any transaction, with the database's majority read concern. */
    <T> T read(String what, Supplier<T> body) {
        try {
            return body.get();
        } catch (MongoException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /**
     * Reads that must see one point in time: every read in a snapshot session reads the same
     * majority-committed snapshot.
     */
    <T> T snapshot(String what, Function<ClientSession, T> body) {
        try (ClientSession session = client.startSession(SNAPSHOT)) {
            return body.apply(session);
        } catch (MongoException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /**
     * Runs {@code body} and commits if {@code keep} accepts its result, else aborts. The hook runs
     * just before commitTransaction with {@code id}; a commitTransaction that fails is unknown.
     */
    <T> T write(UUID id, String what, Body<T> body, Predicate<T> keep) {
        try (ClientSession session = client.startSession()) {
            T result = run(session, what, body, keep);
            if (keep.test(result)) {
                commitHook.beforeCommit(id);
                commitTransaction(session, id);
            }
            return result;
        }
    }

    /** A non-commit mutation: when {@code body} applies, its change records join its transaction. */
    WriteResult change(Change change, String what, Body<WriteResult> body) {
        return write(change.id(), what, tx -> {
            WriteResult result = body.run(tx);
            if (result.equals(WriteResult.APPLIED)) {
                ChangeRecords.write(tx, entryIds, change.id(), change.at(), change.outbox(), change.idempotency());
            }
            return result;
        }, WriteResult.APPLIED::equals);
    }

    /** A write that carries no id (the relay's mark): an unknown outcome is safe to repeat, so any failure is clean. */
    <T> T writeWithoutId(String what, Body<T> body, Predicate<T> keep) {
        try (ClientSession session = client.startSession()) {
            T result = run(session, what, body, keep);
            if (keep.test(result)) {
                try {
                    session.commitTransaction();
                } catch (MongoException e) {
                    throw new StoreException(what + " may not have committed; it is safe to repeat", e);
                }
            }
            return result;
        }
    }

    /**
     * Runs {@code body} in a new transaction, leaving it open when {@code keep} accepts the result
     * and aborting it otherwise. A write conflict with a concurrent transaction, including an insert
     * of a unique key that one holds open or committed after this snapshot, aborts this one before
     * anything is sent to commit, and the body runs again on a fresh snapshot; a body that took a
     * name or key then finds its holder.
     */
    private <T> T run(ClientSession session, String what, Body<T> body, Predicate<T> keep) {
        long deadline = System.nanoTime() + WRITE_CONFLICT_BUDGET.toNanos();
        for (int attempt = 0; ; attempt++) {
            session.startTransaction(TRANSACTION);
            try {
                T result = body.run(new Tx(session, database));
                if (!keep.test(result)) {
                    session.abortTransaction();
                }
                return result;
            } catch (MongoException e) {
                abort(session, e);
                if (e.getCode() != WRITE_CONFLICT || System.nanoTime() > deadline) {
                    throw new StoreException(what + " failed before COMMIT was sent", e);
                }
            } catch (RuntimeException e) {
                abort(session, e);
                throw e;
            }
            backOff(attempt);
        }
    }

    private static void abort(ClientSession session, RuntimeException failure) {
        try {
            if (session.hasActiveTransaction()) {
                session.abortTransaction();
            }
        } catch (MongoException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    /**
     * Sends commitTransaction, and sends it again on the same session while the answer is
     * UnknownTransactionCommitResult: the server applies a transaction's commit at most once
     * (5.3 step 1). The body is never replayed, so a commit that landed can never come back as a
     * conflict.
     */
    static void commitTransaction(ClientSession session, UUID id) {
        for (int attempt = 1; ; attempt++) {
            try {
                session.commitTransaction();
                return;
            } catch (MongoException e) {
                if (attempt == COMMIT_ATTEMPTS || !e.hasErrorLabel(UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    throw new CommitOutcomeUnknownException(id, e);
                }
            }
        }
    }

    static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L << Math.min(attempt, 6)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted while waiting out a write conflict", e);
        }
    }
}
