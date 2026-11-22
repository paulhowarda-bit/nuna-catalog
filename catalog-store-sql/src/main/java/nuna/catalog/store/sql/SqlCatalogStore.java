package nuna.catalog.store.sql;

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
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.SchemaVersion;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * PostgreSQL-wire implementation: PostgreSQL and CockroachDB (D7), told apart by
 * {@code version()}. Implements the whole storage interface on each (docs/storage-design.md
 * sections 4, 5.1, 5.4, 7 and 9); each operation group lives in its own class. YugabyteDB is not
 * supported: {@link #migrate()}, {@link #selfCheck()} and every write refuse it.
 *
 * <p>The caller's DataSource must reach the primary, must never replay a statement, and sets the
 * socket timeout that bounds every wait. This class never retries a COMMIT (D8.4).
 */
public final class SqlCatalogStore implements CatalogStore {

    private final DataSource dataSource;
    private final Transactions transactions;
    private final DataSource maintenanceDataSource;
    private final Transactions maintenance;
    private final SqlNamespaces namespaces;
    private final SqlObjects objects;
    private final SqlGrants grants;
    private final SqlIdempotency idempotency;
    private final SqlOutbox outbox;
    private final SqlGovernance governance;
    private final SqlDeletions deletions;
    private final SqlHealth health;
    private final SqlNights nights;
    private final SqlRequests requests;
    private final SqlDr dr;
    private final SqlRefs refs;
    private final JournalHorizon horizon;

    public SqlCatalogStore(DataSource dataSource) {
        this(dataSource, CommitHook.NONE);
    }

    /** {@code commitHook} is a test seam called just before each COMMIT that carries a commit or change id. */
    public SqlCatalogStore(DataSource dataSource, CommitHook commitHook) {
        this(dataSource, commitHook, Clock.systemUTC());
    }

    /** {@code clock} is a test seam: the clock the journal's horizon is kept by. */
    SqlCatalogStore(DataSource dataSource, CommitHook commitHook, Clock clock) {
        this.horizon = new JournalHorizon(clock);
        this.dataSource = dataSource;
        this.transactions = new Transactions(dataSource, commitHook);
        this.maintenanceDataSource = new MaintenanceDataSource(dataSource);
        this.maintenance = new Transactions(maintenanceDataSource, CommitHook.NONE);
        this.namespaces = new SqlNamespaces(transactions);
        this.objects = new SqlObjects(transactions);
        this.grants = new SqlGrants(transactions);
        this.idempotency = new SqlIdempotency(transactions);
        this.outbox = new SqlOutbox(transactions);
        this.governance = new SqlGovernance(transactions);
        this.deletions = new SqlDeletions(transactions);
        this.health = new SqlHealth(transactions);
        this.nights = new SqlNights(transactions);
        this.requests = new SqlRequests(transactions);
        this.dr = new SqlDr(transactions);
        this.refs = new SqlRefs(transactions);
    }

    // ---- commit core (sections 4 and 5.1) ----

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        return transactions.autocommit("catalog lookup", connection -> Jdbc.first(connection,
                "SELECT id, name_norm, name FROM catalog WHERE name_norm = ?",
                row -> new CatalogRecord(row.getObject(1, UUID.class), row.getString(2), row.getString(3)), normalizedName));
    }

    @Override
    public Optional<Pointer> readPointer(PointerKey key) {
        return transactions.autocommit("pointer read for " + key, connection -> Pointers.read(connection, key));
    }

    @Override
    public CommitResult commit(CommitBatch batch) {
        keepJournalHorizon();
        return transactions.write(batch.commitId(), "commit " + batch.commitId(),
                connection -> Pointers.apply(connection, batch), result -> result instanceof CommitResult.Committed);
    }

    @Override
    public CommitLookup findCommit(UUID commitId, PointerKey anyKeyInBatch) {
        boolean journaled = transactions.rolledBack("lookup of commit " + commitId, connection -> {
            awaitInFlightCommit(connection, anyKeyInBatch, commitId);
            return Jdbc.exists(connection, "SELECT 1 FROM journal WHERE commit_id = ? AND object_id = ? AND branch = ?",
                    commitId, anyKeyInBatch.objectId(), anyKeyInBatch.branch());
        });
        if (!journaled) {
            return CommitLookup.NOT_COMMITTED;
        }
        // PostgreSQL: a commit whose wait for the synchronous standby was cancelled is visible here
        // but may not be replicated; the barrier returns only once the standby has flushed WAL past it.
        transactions.awaitDurable("lookup of commit " + commitId);
        return CommitLookup.COMMITTED;
    }

    /**
     * Blocks until no transaction that could still commit a swap of {@code key} is in flight. One
     * that updated the pointer holds its row lock, which SELECT FOR UPDATE waits for. One that
     * created it holds only the key in the unique index, and its row is invisible, so SELECT FOR
     * UPDATE finds nothing; then a placeholder insert (rolled back by the caller) waits for it. The
     * same holds under READ COMMITTED on CockroachDB (section 5.4).
     */
    private static void awaitInFlightCommit(Connection connection, PointerKey key, UUID commitId) throws SQLException {
        if (Jdbc.exists(connection, "SELECT 1 FROM pointer WHERE object_id = ? AND branch = ? FOR UPDATE",
                key.objectId(), key.branch())) {
            return;
        }
        Jdbc.update(connection, "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, '', 1, '', '', ?, now())"
                + " ON CONFLICT (object_id, branch) DO NOTHING", key.objectId(), key.branch(), commitId);
    }

    @Override
    public List<SafetyFinding> selfCheck() {
        Backend backend = transactions.backend();
        try (Connection connection = dataSource.getConnection()) {
            return backend == Backend.COCKROACHDB ? SelfChecks.cockroach(connection) : SelfChecks.postgres(connection);
        } catch (SQLException e) {
            throw new StoreException("self-check failed", e);
        }
    }

    /**
     * Migrates the schema; on PostgreSQL it then extends the journal's partitions
     * {@link JournalHorizon#MONTHS} ahead. A write that journals keeps them so afterwards
     * ({@link #keepJournalHorizon()}).
     */
    @Override
    public void migrate() {
        try {
            Backend backend = transactions.backend();
            // Where steps run outside a transaction, Flyway's PostgreSQL lock must not hold one
            // open for the whole migration either.
            Flyway.configure()
                    .configuration(Map.of("flyway.postgresql.transactional.lock",
                            String.valueOf(backend.transactionalSchemaSteps)))
                    .dataSource(maintenanceDataSource).locations(backend.migrations)
                    .executeInTransaction(backend.transactionalSchemaSteps)
                    .lockRetryCount(MIGRATION_LOCK_RETRIES).load().migrate();
        } catch (FlywayException e) {
            throw new StoreException("schema migration failed", e);
        }
        extendJournal(horizon.target());
    }

    /**
     * How many times, a second apart, a node retries Flyway's migration lock while another node
     * migrates: 5 minutes. Flyway's default, 50, failed a second node's start while the first ran
     * V3 on a loaded YugabyteDB cluster (a backend until 2026-11-11, D7).
     */
    static final int MIGRATION_LOCK_RETRIES = 300;

    /**
     * The longest a write waits for each lock when it extends the journal's partitions. Attaching a
     * partition locks the default partition exclusively; behind a long reader of the journal, that
     * wait would hold up the write and every query that queues behind the attach.
     */
    static final Duration HORIZON_LOCK_WAIT = Duration.ofSeconds(1);

    private static final System.Logger LOG = System.getLogger(SqlCatalogStore.class.getName());

    /**
     * Creates the journal's missing monthly partitions through the month holding {@code through},
     * moving any commits the default partition holds for those months into them. Returns how many
     * it created. Safe to call from several nodes at once. Only PostgreSQL partitions its journal;
     * CockroachDB splits it into ranges itself, so there it creates none.
     */
    public int extendJournal(Instant through) {
        return extendJournal(through, Duration.ZERO);
    }

    /**
     * As {@link #extendJournal(Instant)}, waiting at most {@code lockWait} for each lock; zero keeps
     * the session's own lock_timeout.
     */
    private int extendJournal(Instant through, Duration lockWait) {
        if (!transactions.backend().partitionsJournal) {
            return 0;
        }
        return maintenance.writeAndRetryOnFailure("journal partition extension", connection -> {
            if (!lockWait.isZero()) {
                Jdbc.first(connection, "SELECT set_config('lock_timeout', ?, true)", row -> row.getString(1),
                        lockWait.toMillis() + "ms");
            }
            return Jdbc.first(connection, "SELECT journal_extend(?)", row -> row.getInt(1), through).orElseThrow();
        }, created -> true);
    }

    /**
     * Before a write that journals, at most once a day: extends the journal's partitions
     * {@link JournalHorizon#MONTHS} ahead of the clock, so a server that runs on without a migration
     * keeps its horizon (backlog 9). It waits at most {@link #HORIZON_LOCK_WAIT} for each lock. A
     * failure is logged and the write goes ahead: the default partition takes a commit beyond the
     * horizon, and the next day's first write tries again.
     */
    private void keepJournalHorizon() {
        horizon.claim().ifPresent(through -> {
            try {
                extendJournal(through, HORIZON_LOCK_WAIT);
            } catch (StoreException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not extend the journal's partitions through " + through
                        + "; the first write after " + JournalHorizon.EVERY + " tries again", e);
            }
        });
    }

    /** The newest Flyway step applied; version 0 before the first migration. */
    @Override
    public SchemaVersion schemaVersion() {
        return transactions.autocommit("schema version read", connection -> {
            if (!Jdbc.exists(connection, "SELECT 1 FROM pg_tables WHERE tablename = 'flyway_schema_history'"
                    + " AND schemaname = current_schema()")) {
                return new SchemaVersion(0, "empty");
            }
            return Jdbc.first(connection, "SELECT version, description FROM flyway_schema_history"
                            + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
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
        keepJournalHorizon();
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
        keepJournalHorizon();
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
}
