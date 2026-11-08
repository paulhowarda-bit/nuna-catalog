package fidice.catalog.store.oracle;

import fidice.catalog.store.Change;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import oracle.jdbc.LogicalTransactionId;
import oracle.jdbc.OracleConnection;

/**
 * Runs the store's statements. Writes are READ COMMITTED transactions that lock what they check
 * (SELECT FOR UPDATE: Oracle has no shared row lock); a failure before COMMIT is sent is a clean
 * {@link StoreException}, and any failure of a COMMIT that carries a commit or change id is an
 * unknown outcome (docs/storage-design.md section 4).
 */
final class Transactions {

    @FunctionalInterface
    interface Body<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Whether findCommit may decide an unknown COMMIT through its logical transaction id. */
    enum Outcome { REMEMBER, FORGET }

    private final DataSource dataSource;
    private final CommitHook commitHook;
    /** Logical transaction ids of this process's commits whose outcome is unknown, until findCommit decides them. */
    private final Map<UUID, LogicalTransactionId> undecided;

    Transactions(DataSource dataSource, CommitHook commitHook, Map<UUID, LogicalTransactionId> undecided) {
        this.dataSource = dataSource;
        this.commitHook = commitHook;
        this.undecided = undecided;
    }

    /** One statement, or several whose results need not agree, in autocommit mode. */
    <T> T autocommit(String what, Body<T> body) {
        try (Connection connection = dataSource.getConnection()) {
            return body.run(connection);
        } catch (SQLException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /** Several reads from one snapshot: a read-only transaction reads as of its start. */
    <T> T snapshot(String what, Body<T> body) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement readOnly = connection.createStatement()) {
                readOnly.execute("SET TRANSACTION READ ONLY");
            }
            try {
                return body.run(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /**
     * Runs {@code body} and commits if {@code keep} accepts its result, else rolls back. The hook
     * runs just before COMMIT with {@code id}; a COMMIT that fails is unknown. With
     * {@link Outcome#REMEMBER} an unknown COMMIT's logical transaction id is kept under {@code id}
     * for findCommit (section 5.2).
     */
    <T> T write(UUID id, String what, Outcome outcome, Body<T> body, Predicate<T> keep) {
        Connection connection = connect(what);
        try {
            T result = runOrRollBack(connection, what, body);
            if (!keep.test(result)) {
                rollBack(connection, what);
                return result;
            }
            // The id this transaction commits under; null when the service lacks COMMIT_OUTCOME.
            LogicalTransactionId transaction = outcome == Outcome.REMEMBER ? logicalTransactionId(connection, what) : null;
            commitHook.beforeCommit(id);
            try {
                connection.commit();
            } catch (SQLException e) {
                if (transaction != null) {
                    undecided.put(id, transaction);
                }
                throw new CommitOutcomeUnknownException(id, e);
            }
            return result;
        } finally {
            close(connection);
        }
    }

    /** A non-commit mutation: when {@code body} applies, its change records join its transaction. */
    WriteResult change(Change change, String what, Body<WriteResult> body) {
        return write(change.id(), what, Outcome.FORGET, connection -> {
            WriteResult result = body.run(connection);
            if (result.equals(WriteResult.APPLIED)) {
                ChangeRecords.write(connection, change.id(), change.at(), change.outbox(), change.idempotency());
            }
            return result;
        }, WriteResult.APPLIED::equals);
    }

    /** A write that carries no id (a relay lease, expiry): an unknown outcome is safe to retry, so any failure is clean. */
    <T> T writeAndRetryOnFailure(String what, Body<T> body, Predicate<T> keep) {
        Connection connection = connect(what);
        try {
            T result = runOrRollBack(connection, what, body);
            if (!keep.test(result)) {
                rollBack(connection, what);
                return result;
            }
            try {
                connection.commit();
            } catch (SQLException e) {
                throw new StoreException(what + " may not have committed; it is safe to repeat", e);
            }
            return result;
        } finally {
            close(connection);
        }
    }

    /**
     * Commits an increment of the durability barrier. Oracle shows a commit to other sessions
     * before its redo is written; this COMMIT returns only once the redo (and, with Data Guard SYNC,
     * the standby) holds everything before it, including any commit this session could see.
     */
    void awaitDurable(String what) {
        try (Connection connection = dataSource.getConnection()) {
            barrier(connection);
        } catch (SQLException e) {
            throw new StoreException(what + ": cannot confirm the answer is durable", e);
        }
    }

    static void barrier(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement barrier = connection.createStatement()) {
            barrier.executeUpdate("UPDATE durability_barrier SET counter = counter + 1");
        }
        connection.commit();
    }

    Connection connect(String what) {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new StoreException(what + " could not connect", e);
        }
    }

    private static LogicalTransactionId logicalTransactionId(Connection connection, String what) {
        try {
            return connection.unwrap(OracleConnection.class).getLogicalTransactionId();
        } catch (SQLException e) {
            StoreException failure = new StoreException(what + " failed before COMMIT was sent", e);
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static <T> T runOrRollBack(Connection connection, String what, Body<T> body) {
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return body.run(connection);
        } catch (SQLException | RuntimeException e) {
            RuntimeException failure = e instanceof RuntimeException runtime ? runtime
                    : new StoreException(what + " failed before COMMIT was sent", e);
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static void rollBack(Connection connection, String what) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new StoreException(what + " could not roll back", e);
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The outcome is decided; failing to close cannot change it.
        }
    }
}
