package nuna.catalog.store.oracle;

import nuna.catalog.store.Change;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import oracle.jdbc.LogicalTransactionId;
import oracle.jdbc.OracleConnection;

/**
 * Runs the store's statements. Writes are READ COMMITTED transactions that lock what they check
 * (SELECT FOR UPDATE: Oracle has no shared row lock); a failure before COMMIT is sent is a clean
 * {@link StoreException}, or a {@link StoreUnavailableException} when the database could not be reached,
 * and any failure of a COMMIT that carries a commit or change id is an unknown outcome
 * (docs/storage-design.md section 4).
 */
final class Transactions {

    /** The SQLState ojdbc gives every Oracle Net error (ORA-12xxx), the listener's refusals among them. */
    private static final String ORACLE_NET = "66000";
    /**
     * ORA-03135 (connection lost contact), ORA-16000 (the database is open read-only: a write reached a standby
     * during failover) and ORA-17800 (got minus one from a read call: the database or a proxy closed the connection,
     * as it does when it drops one during logon; backlog 7), which ojdbc raises as plain SQLExceptions of other
     * classes.
     */
    private static final Set<Integer> UNAVAILABLE_ERRORS = Set.of(3135, 16000, 17800);

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
            throw failed(what + " failed", e);
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
            throw failed(what + " failed", e);
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
                throw failed(what + " may not have committed; it is safe to repeat", e);
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
            throw failed(what + ": cannot confirm the answer is durable", e);
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
            throw failed(what + " could not connect", e);
        }
    }

    /**
     * A failure in which nothing was committed: {@link StoreUnavailableException} when the database could
     * not be reached or dropped the connection, so the caller may repeat once it is back.
     */
    static StoreException failed(String message, SQLException e) {
        return unavailable(e) ? new StoreUnavailableException(message, e) : new StoreException(message, e);
    }

    /**
     * Whether the database was unreachable rather than refusing the work: what ojdbc raises as
     * SQLRecoverableException (a lost or dropped connection, a killed session, an instance starting, stopping or
     * gone, sessions or processes exhausted, a service the listener does not offer during failover, a
     * switchover), an Oracle Net error (a refused connection, a listener out of handlers or blocking new
     * connections), a connection exception (class 08, as a connect timeout), {@link #UNAVAILABLE_ERRORS}, or a
     * pool timing out waiting for a connection (SQLTransientConnectionException, as HikariCP raises it). A pool
     * that wraps the driver's error, as UCP does, is seen through its causes.
     */
    static boolean unavailable(SQLException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLRecoverableException || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals(ORACLE_NET))
                        || UNAVAILABLE_ERRORS.contains(sql.getErrorCode())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LogicalTransactionId logicalTransactionId(Connection connection, String what) {
        try {
            return connection.unwrap(OracleConnection.class).getLogicalTransactionId();
        } catch (SQLException e) {
            StoreException failure = failed(what + " failed before COMMIT was sent", e);
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
                    : failed(what + " failed before COMMIT was sent", (SQLException) e);
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
            throw failed(what + " could not roll back", e);
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
