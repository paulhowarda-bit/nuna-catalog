package keel.catalog.store.oracle;

import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitHook;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.CommitResult;
import keel.catalog.store.CommitStore;
import keel.catalog.store.IdempotencyResult;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.PointerSwap;
import keel.catalog.store.SafetyFinding;
import keel.catalog.store.StoreException;

import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import oracle.jdbc.LogicalTransactionId;
import oracle.jdbc.OracleConnection;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * The previous release's store, frozen for suite test 10: the M1 OracleCatalogStore as committed in
 * dee045f, unchanged except for this comment, its name, its constructors, and its migrate(), which
 * stops at the previous release's schema version.
 */
final class PreviousReleaseStore implements CommitStore {

    static final int SCHEMA_VERSION = 2;

    private static final String MIGRATIONS = "classpath:keel/store/oracle/migration";
    private static final Set<String> SYNCHRONIZED_PROTECTION = Set.of("MAXIMUM AVAILABILITY", "MAXIMUM PROTECTION");

    private final DataSource dataSource;
    private final CommitHook commitHook;
    /** Logical transaction ids of this process's commits whose outcome is unknown, until findCommit decides them. */
    private final Map<UUID, LogicalTransactionId> undecided;

    PreviousReleaseStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.commitHook = CommitHook.NONE;
        this.undecided = new ConcurrentHashMap<>();
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
                return Optional.of(new CatalogRecord(uuid(row.getBytes(1)), row.getString(2), row.getString(3)));
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
        LogicalTransactionId transaction;
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Optional<CommitResult.Conflict> conflict = lockAndCompare(connection, batch);
            if (conflict.isPresent()) {
                connection.rollback();
                return conflict.get();
            }
            write(connection, batch);
            // The id this transaction commits under; null when the service lacks COMMIT_OUTCOME.
            transaction = connection.unwrap(OracleConnection.class).getLogicalTransactionId();
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
            connection.commit();
        } catch (SQLException e) {
            if (transaction != null) {
                undecided.put(batch.commitId(), transaction);
            }
            throw new CommitOutcomeUnknownException(batch.commitId(), e);
        }
        return new CommitResult.Committed(batch.commitId());
    }

    /**
     * Locks every pointer in key order and compares it with the swap's expectation. A create
     * inserts its row here: the primary key is its lock, and a concurrent creator waits on it.
     */
    private static Optional<CommitResult.Conflict> lockAndCompare(Connection connection, CommitBatch batch)
            throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            Optional<Pointer> actual;
            if (swap.expected().isEmpty()) {
                try {
                    insertPointer(connection, swap, batch);
                    continue;
                } catch (SQLIntegrityConstraintViolationException exists) {
                    actual = selectPointer(connection, swap.key(), "");
                }
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

    private static void insertPointer(Connection connection, PointerSwap swap, CommitBatch batch) throws SQLException {
        String sql = "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setBytes(1, raw(swap.key().objectId()));
            insert.setString(2, swap.key().branch());
            setPointer(insert, 3, swap.next());
            insert.setBytes(7, raw(batch.commitId()));
            insert.setObject(8, utc(batch.committedAt()));
            insert.executeUpdate();
        }
    }

    private static void write(Connection connection, CommitBatch batch) throws SQLException {
        String pointerSql = "UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = ?,"
                + " last_commit_id = ?, updated_at = ? WHERE object_id = ? AND branch = ?";
        try (PreparedStatement update = connection.prepareStatement(pointerSql)) {
            for (PointerSwap swap : batch.swaps()) {
                if (swap.expected().isPresent()) {
                    setPointer(update, 1, swap.next());
                    update.setBytes(5, raw(batch.commitId()));
                    update.setObject(6, utc(batch.committedAt()));
                    update.setBytes(7, raw(swap.key().objectId()));
                    update.setString(8, swap.key().branch());
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        String journalSql = "INSERT INTO journal (commit_id, object_id, branch, committed_at, principal, end_user,"
                + " engine, requirements, updates, old_location, new_location, old_version, new_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(journalSql)) {
            for (PointerSwap swap : batch.swaps()) {
                insert.setBytes(1, raw(batch.commitId()));
                insert.setBytes(2, raw(swap.key().objectId()));
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
        String outboxSql = "INSERT INTO outbox (commit_id, created_at, type, payload) VALUES (?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(outboxSql)) {
            for (OutboxEvent event : batch.outbox()) {
                insert.setBytes(1, raw(batch.commitId()));
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

    /**
     * With the commit's logical transaction id (this process ran it), Transaction Guard decides
     * (5.2). Without it (another process ran the commit, or the service lacks COMMIT_OUTCOME), the
     * pointer lock and the journal decide (5.1 steps 1-2). Either way COMMITTED is answered only
     * behind the durability barrier: Oracle shows a commit, to queries and to Transaction Guard
     * alike, before its redo is written.
     */
    @Override
    public CommitLookup findCommit(UUID commitId, PointerKey anyKeyInBatch) {
        LogicalTransactionId transaction = undecided.get(commitId);
        try (Connection connection = dataSource.getConnection()) {
            CommitLookup outcome = transaction != null
                    ? forceOutcome(connection, transaction)
                    : lookUpAfterInFlightCommit(connection, commitId, anyKeyInBatch);
            if (outcome == CommitLookup.COMMITTED) {
                awaitDurable(connection);
            }
            undecided.remove(commitId);
            return outcome;
        } catch (SQLException e) {
            throw new StoreException("cannot decide whether commit " + commitId + " committed", e);
        }
    }

    /**
     * GET_LTXID_OUTCOME answers from the transaction history and, when the transaction has not
     * committed, blocks its id so a COMMIT still on its way fails. It reports a commit as soon as
     * the commit is visible, which can be before its redo is written. PL/SQL BOOLEAN is converted
     * in the block: binding it as BIT fails with PLS-00306.
     */
    private static CommitLookup forceOutcome(Connection connection, LogicalTransactionId transaction)
            throws SQLException {
        String block = "DECLARE committed BOOLEAN; completed BOOLEAN; BEGIN"
                + " DBMS_APP_CONT.GET_LTXID_OUTCOME(?, committed, completed);"
                + " ? := CASE WHEN committed THEN 1 ELSE 0 END; END;";
        try (CallableStatement call = connection.prepareCall(block)) {
            call.setObject(1, transaction);
            call.registerOutParameter(2, Types.INTEGER);
            call.execute();
            return call.getInt(2) == 1 ? CommitLookup.COMMITTED : CommitLookup.NOT_COMMITTED;
        }
    }

    private static CommitLookup lookUpAfterInFlightCommit(Connection connection, UUID commitId, PointerKey key)
            throws SQLException {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        awaitInFlightCommit(connection, key, commitId);
        boolean journaled;
        String sql = "SELECT 1 FROM journal WHERE commit_id = ? AND object_id = ? AND branch = ?";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setBytes(1, raw(commitId));
            select.setBytes(2, raw(key.objectId()));
            select.setString(3, key.branch());
            try (ResultSet row = select.executeQuery()) {
                journaled = row.next();
            }
        }
        connection.rollback();
        return journaled ? CommitLookup.COMMITTED : CommitLookup.NOT_COMMITTED;
    }

    /**
     * Commits a write of its own, which returns only once the redo (and, with Data Guard SYNC, the
     * standby) holds everything before it, including a commit just seen before its redo was written.
     */
    private static void awaitDurable(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement barrier = connection.createStatement()) {
            barrier.executeUpdate("UPDATE durability_barrier SET counter = counter + 1");
        }
        connection.commit();
    }

    /**
     * Blocks until no transaction that could still commit a swap of {@code key} is in flight. One
     * that updated the pointer holds its row lock, which SELECT FOR UPDATE waits for. One that
     * created it holds only the key in the primary key index, and its row is invisible, so SELECT
     * FOR UPDATE finds nothing; then a placeholder insert (rolled back by the caller) waits for it.
     */
    private static void awaitInFlightCommit(Connection connection, PointerKey key, UUID commitId)
            throws SQLException {
        String lockSql = "SELECT 1 FROM pointer WHERE object_id = ? AND branch = ? FOR UPDATE";
        try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
            lock.setBytes(1, raw(key.objectId()));
            lock.setString(2, key.branch());
            try (ResultSet row = lock.executeQuery()) {
                if (row.next()) {
                    return;
                }
            }
        }
        String insertSql = "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, 'placeholder', 1, 'placeholder', EMPTY_BLOB(), ?, SYSTIMESTAMP)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setBytes(1, raw(key.objectId()));
            insert.setString(2, key.branch());
            insert.setBytes(3, raw(commitId));
            insert.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException created) {
            // The creator committed while the insert waited for it.
        }
    }

    @Override
    public List<SafetyFinding> selfCheck() {
        String sql = "SELECT d.protection_mode, d.protection_level, SYS_CONTEXT('USERENV', 'SERVICE_NAME'),"
                + " (SELECT s.commit_outcome FROM v$services s WHERE s.name = SYS_CONTEXT('USERENV', 'SERVICE_NAME'))"
                + " FROM v$database d";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            String protectionMode = row.getString(1);
            String protectionLevel = row.getString(2);
            String service = row.getString(3);
            String commitOutcome = row.getString(4);
            List<SafetyFinding> findings = new ArrayList<>();
            // The level, not the mode: a MAXIMUM AVAILABILITY primary whose standby is not
            // synchronized keeps its mode and drops its level (to RESYNCHRONIZATION).
            if (!SYNCHRONIZED_PROTECTION.contains(protectionLevel)) {
                findings.add(new SafetyFinding("ora.no-data-guard",
                        "protection level is " + protectionLevel + " (mode " + protectionMode + "); a Data Guard"
                                + " standby must be synchronized in MAXIMUM AVAILABILITY or MAXIMUM PROTECTION"));
            }
            if (!"YES".equals(commitOutcome)) {
                findings.add(new SafetyFinding("ora.no-transaction-guard",
                        "service " + service + " has COMMIT_OUTCOME " + commitOutcome
                                + "; the catalog service needs COMMIT_OUTCOME = TRUE for Transaction Guard"));
            }
            return findings;
        } catch (SQLException e) {
            throw new StoreException("self-check failed (the catalog role needs SELECT on V_$DATABASE and V_$SERVICES)", e);
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
            select.setBytes(1, raw(key.objectId()));
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

    /** A UUID as RAW(16), most significant byte first, so RAW order is the unsigned order of D15. */
    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer bytes = ByteBuffer.wrap(raw);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
