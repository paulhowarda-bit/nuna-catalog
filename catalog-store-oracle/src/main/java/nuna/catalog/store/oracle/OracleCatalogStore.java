package nuna.catalog.store.oracle;

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
import nuna.catalog.store.GovernanceLift;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import nuna.catalog.store.MaintenanceRequest;
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
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.SchemaVersion;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.sql.DataSource;
import oracle.jdbc.LogicalTransactionId;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * Oracle implementation: single instance, RAC and Exadata, protected by Data Guard (D7). Implements
 * the whole storage interface (docs/storage-design.md sections 4, 5.2, 7 and 9); each operation
 * group lives in its own class.
 *
 * <p>The caller's DataSource must reach the primary through the catalog service, which has
 * COMMIT_OUTCOME = TRUE (Transaction Guard) and no FAILOVER_TYPE, so the driver never replays a
 * transaction; it must not retry statements, and it sets the read timeout that bounds every wait.
 * Sessions must sort and compare with NLS_SORT = BINARY (the driver sets NLS_LANGUAGE from the JVM
 * locale, and NLS_SORT follows the language). The catalog role needs EXECUTE on DBMS_APP_CONT, SELECT on V_$DATABASE, V_$SERVICES
 * and V_$PARAMETER, and CREATE JOB for the job that creates the journal's partitions ahead
 * (migration V5). This class never retries a COMMIT (D8.4).
 */
public final class OracleCatalogStore implements CatalogStore {

    private static final String MIGRATIONS = "classpath:nuna/store/oracle/migration";
    private static final Set<String> SYNCHRONIZED_PROTECTION = Set.of("MAXIMUM AVAILABILITY", "MAXIMUM PROTECTION");
    private static final Set<String> REPLAYING_FAILOVER = Set.of("TRANSACTION", "AUTO");

    private final DataSource dataSource;
    /** Logical transaction ids of this process's commits whose outcome is unknown, until findCommit decides them. */
    private final Map<UUID, LogicalTransactionId> undecided;
    private final Transactions transactions;
    private final OracleNamespaces namespaces;
    private final OracleObjects objects;
    private final OracleGrants grants;
    private final OracleRules rules;
    private final OracleIdempotency idempotency;
    private final OracleOutbox outbox;
    private final OracleGovernance governance;
    private final OracleDeletions deletions;
    private final OracleHealth health;
    private final OracleNights nights;
    private final OracleRequests requests;
    private final OracleDr dr;
    private final OracleRefs refs;

    public OracleCatalogStore(DataSource dataSource) {
        this(dataSource, CommitHook.NONE);
    }

    /** {@code commitHook} is a test seam called just before each COMMIT that carries a commit or change id. */
    public OracleCatalogStore(DataSource dataSource, CommitHook commitHook) {
        this(dataSource, commitHook, new ConcurrentHashMap<>());
    }

    /** Stores that share {@code undecided} act as one process: each can decide the others' unknown commits. */
    OracleCatalogStore(DataSource dataSource, CommitHook commitHook, Map<UUID, LogicalTransactionId> undecided) {
        this.dataSource = dataSource;
        this.undecided = undecided;
        this.transactions = new Transactions(dataSource, commitHook, undecided);
        this.namespaces = new OracleNamespaces(transactions);
        this.objects = new OracleObjects(transactions);
        this.grants = new OracleGrants(transactions);
        this.rules = new OracleRules(transactions);
        this.idempotency = new OracleIdempotency(transactions);
        this.outbox = new OracleOutbox(transactions);
        this.governance = new OracleGovernance(transactions);
        this.deletions = new OracleDeletions(transactions);
        this.health = new OracleHealth(transactions);
        this.nights = new OracleNights(transactions);
        this.requests = new OracleRequests(transactions);
        this.dr = new OracleDr(transactions);
        this.refs = new OracleRefs(transactions);
    }

    // ---- commit core (sections 4 and 5.2) ----

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        return transactions.autocommit("catalog lookup", connection -> Jdbc.first(connection,
                "SELECT id, name_norm, name FROM catalog WHERE name_norm = ?",
                row -> new CatalogRecord(Jdbc.uuid(row, 1), Jdbc.text(row, 2), Jdbc.text(row, 3)), normalizedName));
    }

    @Override
    public Optional<Pointer> readPointer(PointerKey key) {
        return transactions.autocommit("pointer read for " + key, connection -> Pointers.read(connection, key));
    }

    /** An unknown COMMIT keeps its logical transaction id, so findCommit can force its outcome. */
    @Override
    public CommitResult commit(CommitBatch batch) {
        return transactions.write(batch.commitId(), "commit " + batch.commitId(), Transactions.Outcome.REMEMBER,
                connection -> Pointers.apply(connection, batch), result -> result instanceof CommitResult.Committed);
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
            throw Transactions.failed("cannot decide whether commit " + commitId + " committed", e);
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

    /**
     * Reads the settings that decide whether a COMMIT can be trusted: Data Guard protection, the
     * service's Transaction Guard and replay settings, whether COMMIT waits for its redo, and the
     * session's sort, which must be binary for names to compare as bytes. Then whether the job that
     * creates the journal's partitions ahead can run ({@link JournalJob}).
     */
    @Override
    public List<SafetyFinding> selfCheck() {
        int schemaVersion = schemaVersion().version();
        String sql = "SELECT d.protection_mode, d.protection_level, SYS_CONTEXT('USERENV', 'SERVICE_NAME'),"
                + " s.commit_outcome, s.failover_type,"
                + " (SELECT LISTAGG(p.name || ' = ' || p.value, ', ') WITHIN GROUP (ORDER BY p.name) FROM v$parameter p"
                + " WHERE p.name IN ('commit_wait', 'commit_write') AND UPPER(p.value) LIKE '%NOWAIT%'),"
                + " (SELECT n.value FROM nls_session_parameters n WHERE n.parameter = 'NLS_SORT'),"
                + " (SELECT p.value FROM v$parameter p WHERE p.name = 'job_queue_processes')"
                + " FROM v$database d LEFT JOIN v$services s ON s.name = SYS_CONTEXT('USERENV', 'SERVICE_NAME')";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            String protectionMode = row.getString(1);
            String protectionLevel = row.getString(2);
            String service = row.getString(3);
            String commitOutcome = row.getString(4);
            String failoverType = row.getString(5);
            String noWait = row.getString(6);
            String sort = row.getString(7);
            String jobQueueProcesses = row.getString(8);
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
            if (failoverType != null && REPLAYING_FAILOVER.contains(failoverType.toUpperCase(Locale.ROOT))) {
                findings.add(new SafetyFinding("ora.commit-replay",
                        "service " + service + " has FAILOVER_TYPE " + failoverType + "; Application Continuity can"
                                + " replay a transaction whose outcome the store must decide itself"));
            }
            if (noWait != null) {
                findings.add(new SafetyFinding("ora.commit-nowait",
                        noWait + "; COMMIT must wait until its redo is written (COMMIT_WAIT unset or WAIT)"));
            }
            if (!"BINARY".equals(sort)) {
                findings.add(new SafetyFinding("ora.linguistic-sort",
                        "NLS_SORT is " + sort + "; names must sort and compare as bytes, so sessions need"
                                + " NLS_SORT = BINARY (the driver sets NLS_LANGUAGE from the JVM locale, and NLS_SORT follows it)"));
            }
            JournalJob.check(connection, jobQueueProcesses, schemaVersion).ifPresent(findings::add);
            return findings;
        } catch (SQLException e) {
            throw new StoreException("self-check failed (the catalog role needs SELECT on V_$DATABASE, V_$SERVICES"
                    + " and V_$PARAMETER)", e);
        }
    }

    @Override
    public void migrate() {
        try {
            Flyway.configure().dataSource(dataSource).locations(MIGRATIONS).load().migrate();
        } catch (FlywayException e) {
            throw new StoreException("schema migration failed", e);
        }
    }

    /** The newest Flyway step applied; version 0 before the first migration. */
    @Override
    public SchemaVersion schemaVersion() {
        return transactions.autocommit("schema version read", connection -> {
            if (!Jdbc.exists(connection, "SELECT 1 FROM user_tables WHERE table_name = 'flyway_schema_history'")) {
                return new SchemaVersion(0, "empty");
            }
            return Jdbc.first(connection, "SELECT \"version\", \"description\" FROM \"flyway_schema_history\""
                            + " WHERE \"success\" = 1 AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC"
                            + " FETCH FIRST 1 ROW ONLY",
                    row -> new SchemaVersion(Integer.parseInt(row.getString(1)), row.getString(2)))
                    .orElse(new SchemaVersion(0, "empty"));
        });
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

    // ---- the worker's nights (D45, backlog 126) ----

    @Override
    public void recordMaintenanceJob(MaintenanceJob job, LocalDate keepFrom) {
        nights.record(job, keepFrom);
    }

    @Override
    public List<MaintenanceNight> listMaintenanceNights(LocalDate through, int limit) {
        return nights.nights(through, limit);
    }

    @Override
    public List<MaintenanceJob> listMaintenanceJobs(LocalDate night, Set<MaintenanceJob.Kind> kinds) {
        return nights.jobs(night, kinds);
    }

    // ---- maintenance on request (D30, amended 2026-11-20; backlog 114) ----

    @Override
    public WriteResult requestMaintenance(MaintenanceRequest request, Change change) {
        return requests.request(request, change);
    }

    @Override
    public List<MaintenanceRequest> listMaintenanceRequests(UUID objectId) {
        return requests.list(objectId);
    }

    @Override
    public Page<MaintenanceRequest> listWaitingRequests(PageRequest page) {
        return requests.listWaiting(page);
    }

    @Override
    public WriteResult startMaintenanceRequest(UUID objectId, MaintenanceRequest.Job job, Instant requestedAt,
                                               Instant startedAt) {
        return requests.start(objectId, job, requestedAt, startedAt);
    }

    // ---- the DR watermark (D37) ----

    @Override
    public WriteResult recordDrWatermark(UUID objectId, DrWatermark watermark) {
        return dr.record(objectId, watermark);
    }

    @Override
    public Optional<DrWatermark> readDrWatermark(UUID objectId) {
        return dr.read(objectId);
    }

    @Override
    public Page<DrState> listDrBehind(PageRequest page) {
        return dr.listBehind(page);
    }

    // ---- grants ----

    @Override
    public WriteResult putGrant(Grant grant, String grantedBy, Change change) {
        return grants.put(grant, grantedBy, change);
    }

    @Override
    public WriteResult putGrantIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits) {
        return grants.putIf(grant, grantedBy, change, admits);
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

    // ---- row filters and column masks ----

    @Override
    public WriteResult putRule(RowColumnRule rule, OptionalLong expected, String setBy, Change change) {
        return rules.put(rule, expected, setBy, change);
    }

    @Override
    public WriteResult removeRule(UUID catalogId, UUID tableId, String group, OptionalLong expected, Change change) {
        return rules.remove(catalogId, tableId, group, expected, change);
    }

    @Override
    public List<RowColumnRule.Stored> rulesOn(UUID tableId) {
        return rules.on(tableId);
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
    public WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, String removedBy,
                                       Change change) {
        return governance.removeAttribute(target, kind, removedBy, change);
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
    public WriteResult releaseHold(UUID holdId, String releasedBy, Change change) {
        return governance.releaseHold(holdId, releasedBy, change);
    }

    @Override
    public List<LegalHold> holdsOn(Set<Securable> targets) {
        return governance.holdsOn(targets);
    }

    @Override
    public List<GovernanceLift> liftedIn(UUID catalogId, Instant since) {
        return governance.liftedIn(catalogId, since);
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

    // ---- refs (D33) ----

    @Override
    public WriteResult createRef(Ref ref, RefLimits limits, Change change) {
        return refs.create(ref, limits, change);
    }

    @Override
    public Optional<Ref> readRef(UUID refId) {
        return refs.read(refId);
    }

    @Override
    public Optional<Ref> findRef(UUID catalogId, String normalizedName) {
        return refs.find(catalogId, normalizedName);
    }

    @Override
    public Page<Ref> listRefs(UUID catalogId, PageRequest page) {
        return refs.list(catalogId, page);
    }

    @Override
    public WriteResult renewRef(UUID refId, Instant expiresAt, Change change) {
        return refs.renew(refId, expiresAt, change);
    }

    @Override
    public WriteResult deleteRef(UUID refId, Change change) {
        return refs.delete(refId, change);
    }

    @Override
    public WriteResult removeFromRefs(UUID objectId, Set<UUID> refIds, boolean every, Change change) {
        return refs.remove(objectId, refIds, every, change);
    }

    @Override
    public long countOwnPointers(UUID refId) {
        return refs.countOwn(refId);
    }

    @Override
    public List<RefPointers> listRefPointers(UUID objectId) {
        return refs.listPointers(objectId);
    }

    @Override
    public Optional<RefEntry> findRefNamespace(UUID refId, List<String> normalizedPath) {
        return refs.findNamespace(refId, normalizedPath);
    }

    @Override
    public Optional<RefEntry> resolveOnRef(UUID refId, List<String> normalizedNamespacePath, String normalizedName) {
        return refs.resolve(refId, normalizedNamespacePath, normalizedName);
    }

    @Override
    public Page<RefEntry> listRefNamespaces(UUID refId, UUID parentId, PageRequest page) {
        return refs.listNamespaces(refId, parentId, page);
    }

    @Override
    public Page<RefEntry> listRefObjects(UUID refId, UUID namespaceId, CatalogObject.Kind kind, PageRequest page) {
        return refs.listObjects(refId, namespaceId, kind, page);
    }

    /** A UUID as RAW(16), most significant byte first, so RAW order is the unsigned order of D15. */
    static byte[] raw(UUID id) {
        return Jdbc.raw(id);
    }
}
