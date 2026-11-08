package fidice.catalog.store.sql;

import fidice.catalog.store.Change;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;

/**
 * Runs the store's statements. Writes are READ COMMITTED transactions that lock what they check;
 * a failure before COMMIT is sent is a clean {@link StoreException}, and any failure of a COMMIT
 * that carries a commit or change id is an unknown outcome (docs/storage-design.md section 4).
 */
final class Transactions {

    @FunctionalInterface
    interface Body<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;
    private final CommitHook commitHook;

    Transactions(DataSource dataSource, CommitHook commitHook) {
        this.dataSource = dataSource;
        this.commitHook = commitHook;
    }

    /** One statement, or several whose results need not agree, in autocommit mode. */
    <T> T autocommit(String what, Body<T> body) {
        try (Connection connection = dataSource.getConnection()) {
            return body.run(connection);
        } catch (SQLException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /** Several reads from one snapshot. */
    <T> T snapshot(String what, Body<T> body) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            T result = body.run(connection);
            connection.rollback();
            return result;
        } catch (SQLException e) {
            throw new StoreException(what + " failed", e);
        }
    }

    /**
     * Runs {@code body} and commits if {@code keep} accepts its result, else rolls back. The hook
     * runs just before COMMIT with {@code id}; a COMMIT that fails or warns is unknown.
     */
    <T> T write(UUID id, String what, Body<T> body, Predicate<T> keep) {
        Connection connection = connect(what);
        try {
            T result = runOrRollBack(connection, what, body);
            if (!keep.test(result)) {
                rollBack(connection, what);
                return result;
            }
            commitHook.beforeCommit(id);
            try {
                commitChecked(connection);
            } catch (SQLException e) {
                throw new CommitOutcomeUnknownException(id, e);
            }
            return result;
        } finally {
            close(connection);
        }
    }

    /** A non-commit mutation: when {@code body} applies, its change records join its transaction. */
    WriteResult change(Change change, String what, Body<WriteResult> body) {
        return write(change.id(), what, connection -> {
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
                commitChecked(connection);
            } catch (SQLException e) {
                throw new StoreException(what + " may not have committed; it is safe to repeat", e);
            }
            return result;
        } finally {
            close(connection);
        }
    }

    /**
     * Commits an increment of the durability barrier: with synchronous commit it returns only once
     * the standby has flushed WAL past every commit this session could see (section 5.1 step 3).
     */
    void awaitDurable(String what) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            barrier(connection);
            commitChecked(connection);
        } catch (SQLException e) {
            throw new StoreException(what + ": cannot confirm the answer is durable", e);
        }
    }

    static void barrier(Connection connection) throws SQLException {
        try (Statement barrier = connection.createStatement()) {
            barrier.executeUpdate("UPDATE durability_barrier SET counter = counter + 1");
        }
    }

    /**
     * Commits, and treats a warning as a failure: PostgreSQL answers a COMMIT whose wait for the
     * synchronous standby was cancelled with a warning, not an error, and that commit may not be
     * replicated.
     */
    static void commitChecked(Connection connection) throws SQLException {
        connection.clearWarnings();
        connection.commit();
        SQLWarning warning = connection.getWarnings();
        if (warning != null) {
            throw warning;
        }
    }

    Connection connect(String what) {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new StoreException(what + " could not connect", e);
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
