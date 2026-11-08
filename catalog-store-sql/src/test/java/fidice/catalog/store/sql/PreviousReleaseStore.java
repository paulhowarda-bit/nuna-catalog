package fidice.catalog.store.sql;

import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.CommitStore;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.PointerSwap;
import fidice.catalog.store.SafetyFinding;
import fidice.catalog.store.StoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * The previous release's store, frozen for suite test 10: the M1 SqlCatalogStore as committed in
 * bdde131, unchanged except for this comment, its name, and its migrate(), which stops at the
 * previous release's schema version.
 */
final class PreviousReleaseStore implements CommitStore {

    static final int SCHEMA_VERSION = 2;
    private static final String MIGRATIONS = "classpath:fidice/store/sql/migration";

    private final DataSource dataSource;
    private final CommitHook commitHook;

    PreviousReleaseStore(DataSource dataSource) {
        this(dataSource, CommitHook.NONE);
    }

    private PreviousReleaseStore(DataSource dataSource, CommitHook commitHook) {
        this.dataSource = dataSource;
        this.commitHook = commitHook;
    }

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        String sql = "SELECT id, name_norm, name FROM catalog WHERE name_norm = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedName);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CatalogRecord(
                        row.getObject(1, UUID.class), row.getString(2), row.getString(3)));
            }
        } catch (SQLException e) {
            throw new StoreException("catalog lookup failed", e);
        }
    }

    @Override
    public Optional<Pointer> readPointer(PointerKey key) {
        try (Connection connection = dataSource.getConnection()) {
            return selectPointer(connection, key, "");
        } catch (SQLException e) {
            throw new StoreException("pointer read failed for " + key, e);
        }
    }

    @Override
    public CommitResult commit(CommitBatch batch) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new StoreException("commit " + batch.commitId() + " could not connect", e);
        }
        try {
            return commitOn(connection, batch);
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The outcome is decided; failing to close cannot change it.
            }
        }
    }

    private CommitResult commitOn(Connection connection, CommitBatch batch) {
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Optional<CommitResult.Conflict> conflict = lockAndCompare(connection, batch);
            if (conflict.isPresent()) {
                connection.rollback();
                return conflict.get();
            }
            write(connection, batch);
        } catch (SQLException e) {
            StoreException failure = new StoreException(
                    "commit " + batch.commitId() + " failed before COMMIT was sent", e);
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        commitHook.beforeCommit(batch.commitId());
        try {
            commitChecked(connection);
        } catch (SQLException e) {
            throw new CommitOutcomeUnknownException(batch.commitId(), e);
        }
        return new CommitResult.Committed(batch.commitId());
    }

    /**
     * Locks every pointer in key order and compares it with the swap's expectation. A create
     * inserts its row here: the unique key is its lock, and a concurrent creator waits on it.
     */
    private static Optional<CommitResult.Conflict> lockAndCompare(Connection connection, CommitBatch batch)
            throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            Optional<Pointer> actual;
            if (swap.expected().isEmpty()) {
                if (insertPointer(connection, swap, batch)) {
                    continue;
                }
                actual = selectPointer(connection, swap.key(), "");
            } else {
                actual = selectPointer(connection, swap.key(), " FOR UPDATE");
                Pointer expected = swap.expected().get();
                if (actual.isPresent()
                        && actual.get().version() == expected.version()
                        && actual.get().metadataLocation().equals(expected.metadataLocation())) {
                    continue;
                }
            }
            return Optional.of(new CommitResult.Conflict(swap.key(), actual));
        }
        return Optional.empty();
    }

    private static boolean insertPointer(Connection connection, PointerSwap swap, CommitBatch batch)
            throws SQLException {
        String sql = "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (object_id, branch) DO NOTHING";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, swap.key().objectId());
            insert.setString(2, swap.key().branch());
            setPointer(insert, 3, swap.next());
            insert.setObject(7, batch.commitId());
            insert.setObject(8, utc(batch.committedAt()));
            return insert.executeUpdate() == 1;
        }
    }

    private static void write(Connection connection, CommitBatch batch) throws SQLException {
        String pointerSql = "UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = ?,"
                + " last_commit_id = ?, updated_at = ? WHERE object_id = ? AND branch = ?";
        try (PreparedStatement update = connection.prepareStatement(pointerSql)) {
            for (PointerSwap swap : batch.swaps()) {
                if (swap.expected().isPresent()) {
                    setPointer(update, 1, swap.next());
                    update.setObject(5, batch.commitId());
                    update.setObject(6, utc(batch.committedAt()));
                    update.setObject(7, swap.key().objectId());
                    update.setString(8, swap.key().branch());
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        String journalSql = "INSERT INTO journal (commit_id, object_id, branch, committed_at, principal, end_user,"
                + " engine, requirements, updates, old_location, new_location, old_version, new_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(journalSql)) {
            for (PointerSwap swap : batch.swaps()) {
                insert.setObject(1, batch.commitId());
                insert.setObject(2, swap.key().objectId());
                insert.setString(3, swap.key().branch());
                insert.setObject(4, utc(batch.committedAt()));
                insert.setString(5, swap.journal().principal());
                insert.setString(6, swap.journal().endUser());
                insert.setString(7, swap.journal().engine());
                insert.setString(8, swap.journal().requirementsJson());
                insert.setString(9, swap.journal().updatesJson());
                insert.setString(10, swap.expected().map(Pointer::metadataLocation).orElse(null));
                insert.setString(11, swap.next().metadataLocation());
                insert.setObject(12, swap.expected().map(Pointer::version).orElse(null));
                insert.setLong(13, swap.next().version());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        String outboxSql = "INSERT INTO outbox (commit_id, created_at, type, payload) VALUES (?, ?, ?, CAST(? AS JSON))";
        try (PreparedStatement insert = connection.prepareStatement(outboxSql)) {
            for (OutboxEvent event : batch.outbox()) {
                insert.setObject(1, batch.commitId());
                insert.setObject(2, utc(batch.committedAt()));
                insert.setString(3, event.type());
                insert.setString(4, event.payloadJson());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        if (batch.idempotency().isPresent()) {
            IdempotencyResult result = batch.idempotency().get();
            String sql = "INSERT INTO idempotency (key, principal, request_hash, status, body, expires_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                insert.setString(1, result.key());
                insert.setString(2, result.principal());
                insert.setString(3, result.requestHash());
                insert.setInt(4, result.status());
                insert.setString(5, result.body());
                insert.setObject(6, utc(result.expiresAt()));
                insert.executeUpdate();
            }
        }
    }

    @Override
    public CommitLookup findCommit(UUID commitId, PointerKey anyKeyInBatch) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            awaitInFlightCommit(connection, anyKeyInBatch, commitId);
            boolean journaled;
            String sql = "SELECT 1 FROM journal WHERE commit_id = ? AND object_id = ? AND branch = ?";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setObject(1, commitId);
                select.setObject(2, anyKeyInBatch.objectId());
                select.setString(3, anyKeyInBatch.branch());
                try (ResultSet row = select.executeQuery()) {
                    journaled = row.next();
                }
            }
            connection.rollback();
            if (!journaled) {
                return CommitLookup.NOT_COMMITTED;
            }
            // A commit whose wait for the synchronous standby was cancelled is visible here but may
            // not be replicated. This commit returns only once the standby has flushed WAL past it.
            try (Statement barrier = connection.createStatement()) {
                barrier.executeUpdate("UPDATE durability_barrier SET counter = counter + 1");
            }
            commitChecked(connection);
            return CommitLookup.COMMITTED;
        } catch (SQLException e) {
            throw new StoreException("cannot decide whether commit " + commitId + " committed", e);
        }
    }

    /**
     * Blocks until no transaction that could still commit a swap of {@code key} is in flight. One
     * that updated the pointer holds its row lock, which SELECT FOR UPDATE waits for. One that
     * created it holds only the key in the unique index, and its row is invisible, so SELECT FOR
     * UPDATE finds nothing; then a placeholder insert (rolled back by the caller) waits for it.
     */
    private static void awaitInFlightCommit(Connection connection, PointerKey key, UUID commitId)
            throws SQLException {
        String lockSql = "SELECT 1 FROM pointer WHERE object_id = ? AND branch = ? FOR UPDATE";
        try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
            lock.setObject(1, key.objectId());
            lock.setString(2, key.branch());
            try (ResultSet row = lock.executeQuery()) {
                if (row.next()) {
                    return;
                }
            }
        }
        String insertSql = "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, '', 1, '', '', ?, now())"
                + " ON CONFLICT (object_id, branch) DO NOTHING";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setObject(1, key.objectId());
            insert.setString(2, key.branch());
            insert.setObject(3, commitId);
            insert.executeUpdate();
        }
    }

    /**
     * Commits, and treats a warning as a failure: PostgreSQL answers a COMMIT whose wait for the
     * synchronous standby was cancelled with a warning, not an error, and that commit may not be
     * replicated.
     */
    private static void commitChecked(Connection connection) throws SQLException {
        connection.clearWarnings();
        connection.commit();
        SQLWarning warning = connection.getWarnings();
        if (warning != null) {
            throw warning;
        }
    }

    @Override
    public List<SafetyFinding> selfCheck() {
        String sql = "SELECT current_setting('synchronous_commit'), current_setting('synchronous_standby_names'),"
                + " (SELECT count(*) FROM pg_stat_replication WHERE sync_state IN ('sync', 'quorum')),"
                + " current_setting('fsync'), current_setting('full_page_writes'),"
                + " current_setting('statement_timeout'), current_setting('idle_in_transaction_session_timeout')";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            String synchronousCommit = row.getString(1);
            String standbyNames = row.getString(2);
            long syncStandbys = row.getLong(3);
            String fsync = row.getString(4);
            String fullPageWrites = row.getString(5);
            String statementTimeout = row.getString(6);
            String idleTimeout = row.getString(7);
            List<SafetyFinding> findings = new ArrayList<>();
            if (!synchronousCommit.equals("on") && !synchronousCommit.equals("remote_apply")) {
                findings.add(new SafetyFinding("pg.sync-commit-off",
                        "synchronous_commit is " + synchronousCommit + "; it must be on or remote_apply"));
            }
            if (standbyNames.isBlank() || syncStandbys == 0) {
                findings.add(new SafetyFinding("pg.no-sync-standby",
                        "synchronous_standby_names is '" + standbyNames + "' and " + syncStandbys
                                + " standbys stream in sync or quorum state (the catalog role needs pg_monitor to see them)"));
            }
            if (fsync.equals("off") || fullPageWrites.equals("off")) {
                findings.add(new SafetyFinding("pg.fsync-off",
                        "fsync is " + fsync + " and full_page_writes is " + fullPageWrites + "; both must be on"));
            }
            if (!statementTimeout.equals("0") || !idleTimeout.equals("0")) {
                findings.add(new SafetyFinding("pg.commit-cancel-timeout",
                        "statement_timeout is " + statementTimeout + " and idle_in_transaction_session_timeout is "
                                + idleTimeout + "; a timeout can cancel a COMMIT's wait for the standby, so both must be 0"));
            }
            return findings;
        } catch (SQLException e) {
            throw new StoreException("self-check failed", e);
        }
    }

    @Override
    public void migrate() {
        try {
            Flyway.configure().dataSource(dataSource).locations(MIGRATIONS).target(Integer.toString(SCHEMA_VERSION))
                    .load().migrate();
        } catch (FlywayException e) {
            throw new StoreException("schema migration failed", e);
        }
    }

    private static Optional<Pointer> selectPointer(Connection connection, PointerKey key, String lockClause)
            throws SQLException {
        String sql = "SELECT metadata_location, version, sha256, projection FROM pointer"
                + " WHERE object_id = ? AND branch = ?" + lockClause;
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setObject(1, key.objectId());
            select.setString(2, key.branch());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Pointer(row.getString(1), row.getLong(2), row.getString(3), row.getBytes(4)));
            }
        }
    }

    private static void setPointer(PreparedStatement statement, int firstIndex, Pointer pointer) throws SQLException {
        statement.setString(firstIndex, pointer.metadataLocation());
        statement.setLong(firstIndex + 1, pointer.version());
        statement.setString(firstIndex + 2, pointer.sha256());
        statement.setBytes(firstIndex + 3, pointer.projection());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
