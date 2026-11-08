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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import javax.sql.DataSource;

/**
 * Runs the store's statements. Writes are READ COMMITTED transactions that lock what they check;
 * a failure before COMMIT is sent is a clean {@link StoreException}, and any failure of a COMMIT
 * that carries a commit or change id is an unknown outcome (docs/storage-design.md section 4).
 *
 * <p>A serialization failure or deadlock (SQLSTATE 40001, 40P01) before COMMIT is sent rolls the
 * transaction back and runs it again, a bounded number of times: CockroachDB and YugabyteDB raise
 * them under READ COMMITTED too (sections 5.4 and 5.5). A COMMIT is never run again (D8.4).
 */
final class Transactions {

    @FunctionalInterface
    interface Body<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Attempts of a transaction that keeps failing with a serialization failure before COMMIT. */
    static final int ATTEMPTS = 10;

    private final DataSource dataSource;
    private final CommitHook commitHook;
    private volatile Backend backend;

    Transactions(DataSource dataSource, CommitHook commitHook) {
        this.dataSource = dataSource;
        this.commitHook = commitHook;
    }

    /** The database behind the DataSource, asked once. */
    Backend backend() {
        Backend known = backend;
        if (known == null) {
            try (Connection connection = dataSource.getConnection()) {
                known = Backend.of(connection);
            } catch (SQLException e) {
                throw new StoreException("cannot tell which database the store is on", e);
            }
            backend = known;
        }
        return known;
    }

    /** One statement, or several whose results need not agree, in autocommit mode. */
    <T> T autocommit(String what, Body<T> body) {
        return attempts(what, () -> {
            try (Connection connection = dataSource.getConnection()) {
                return body.run(connection);
            } catch (SQLException e) {
                throw retryOr(e, new StoreException(what + " failed", e));
            }
        });
    }

    /** Several reads from one snapshot. */
    <T> T snapshot(String what, Body<T> body) {
        return attempts(what, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                T result = body.run(connection);
                connection.rollback();
                return result;
            } catch (SQLException e) {
                throw retryOr(e, new StoreException(what + " failed", e));
            }
        });
    }

    /**
     * Runs {@code body} in a READ COMMITTED transaction that is always rolled back: reads that
     * lock or wait, such as findCommit's wait for a commit in flight.
     */
    <T> T rolledBack(String what, Body<T> body) {
        return attempts(what, () -> {
            Connection connection = connect(what);
            try {
                T result = runOrRollBack(connection, what, body);
                rollBack(connection, what);
                return result;
            } finally {
                close(connection);
            }
        });
    }

    /**
     * Runs {@code body} and commits if {@code keep} accepts its result, else rolls back. The hook
     * runs just before COMMIT with {@code id}; a COMMIT that fails, or warns on PostgreSQL, is
     * unknown. The backend is known before the transaction starts, so nothing after COMMIT needs
     * another connection.
     */
    <T> T write(UUID id, String what, Body<T> body, Predicate<T> keep) {
        Backend backend = backend();
        return attempts(what, () -> {
            Connection connection = connect(what);
            try {
                T result = runOrRollBack(connection, what, body);
                if (!keep.test(result)) {
                    rollBack(connection, what);
                    return result;
                }
                commitHook.beforeCommit(id);
                try {
                    commitChecked(connection, backend);
                } catch (SQLException e) {
                    throw new CommitOutcomeUnknownException(id, e);
                }
                return result;
            } finally {
                close(connection);
            }
        });
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
        Backend backend = backend();
        return attempts(what, () -> {
            Connection connection = connect(what);
            try {
                T result = runOrRollBack(connection, what, body);
                if (!keep.test(result)) {
                    rollBack(connection, what);
                    return result;
                }
                try {
                    commitChecked(connection, backend);
                } catch (SQLException e) {
                    throw new StoreException(what + " may not have committed; it is safe to repeat", e);
                }
                return result;
            } finally {
                close(connection);
            }
        });
    }

    /**
     * Returns once every commit this session could see is durable. On PostgreSQL it commits an
     * increment of the durability barrier: with synchronous commit it returns only once the standby
     * has flushed WAL past those commits (section 5.1 step 3). On CockroachDB and YugabyteDB a
     * visible commit is already on a quorum, so there is nothing to wait for.
     */
    void awaitDurable(String what) {
        if (!backend().needsDurabilityBarrier) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            barrier(connection);
            commitChecked(connection, backend());
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
     * Commits, and on PostgreSQL treats a warning as a failure: it answers a COMMIT whose wait for
     * the synchronous standby was cancelled with a warning, not an error, and that commit may not
     * be replicated. A COMMIT on CockroachDB or YugabyteDB that answers is on a quorum; CockroachDB
     * warns on it of a missing license (section 5.4), which says nothing about the commit.
     */
    static void commitChecked(Connection connection, Backend backend) throws SQLException {
        connection.clearWarnings();
        connection.commit();
        SQLWarning warning = connection.getWarnings();
        if (warning != null && backend.needsDurabilityBarrier) {
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

    /** A serialization failure or deadlock: the transaction was rolled back and may run again. */
    static boolean retryable(SQLException e) {
        return "40001".equals(e.getSQLState()) || "40P01".equals(e.getSQLState());
    }

    /** Thrown inside an attempt to have it run again. */
    private static final class Retry extends RuntimeException {
        Retry(SQLException cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run();
    }

    private static RuntimeException retryOr(SQLException e, RuntimeException otherwise) {
        return retryable(e) ? new Retry(e) : otherwise;
    }

    private static <T> T attempts(String what, Attempt<T> attempt) {
        for (int n = 1; ; n++) {
            try {
                return attempt.run();
            } catch (Retry retry) {
                if (n == ATTEMPTS) {
                    throw new StoreException(what + " failed before COMMIT was sent: still a serialization failure after "
                            + ATTEMPTS + " attempts", retry.getCause());
                }
                backOff(n);
            }
        }
    }

    /** Jittered exponential backoff: up to 10 ms after the first failure, up to 1 s from the eighth. */
    private static void backOff(int failures) {
        long ceiling = Math.min(1_000, 10L << Math.min(failures - 1, 7));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted while backing off a serialization failure", e);
        }
    }

    private static <T> T runOrRollBack(Connection connection, String what, Body<T> body) {
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return body.run(connection);
        } catch (SQLException | RuntimeException e) {
            RuntimeException failure = e instanceof RuntimeException runtime ? runtime
                    : retryOr((SQLException) e, new StoreException(what + " failed before COMMIT was sent", e));
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
