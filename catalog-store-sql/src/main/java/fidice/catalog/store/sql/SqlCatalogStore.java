package fidice.catalog.store.sql;

import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogSettings;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.GovernanceAttribute;
import fidice.catalog.store.Grant;
import fidice.catalog.store.GrantRecord;
import fidice.catalog.store.IdempotencyClaim;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.JournalRecord;
import fidice.catalog.store.KeyState;
import fidice.catalog.store.LegalHold;
import fidice.catalog.store.Name;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.ObjectCreation;
import fidice.catalog.store.OutboxBatch;
import fidice.catalog.store.Page;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.SafetyFinding;
import fidice.catalog.store.SchemaVersion;
import fidice.catalog.store.Securable;
import fidice.catalog.store.StorageProfile;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.TrustedEngine;
import fidice.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * PostgreSQL-wire implementation: PostgreSQL, CockroachDB and YugabyteDB (D7), told apart by
 * {@code version()}. Implements the whole storage interface on each (docs/storage-design.md
 * sections 4, 5.1, 5.4, 5.5, 7 and 9); each operation group lives in its own class.
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

    public SqlCatalogStore(DataSource dataSource) {
        this(dataSource, CommitHook.NONE);
    }

    /** {@code commitHook} is a test seam called just before each COMMIT that carries a commit or change id. */
    public SqlCatalogStore(DataSource dataSource, CommitHook commitHook) {
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
     * same holds under READ COMMITTED on CockroachDB and YugabyteDB (sections 5.4 and 5.5).
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
        try {
            if (backend == Backend.YUGABYTEDB) {
                return SelfChecks.yugabyte(dataSource);
            }
            try (Connection connection = dataSource.getConnection()) {
                return backend == Backend.COCKROACHDB ? SelfChecks.cockroach(connection) : SelfChecks.postgres(connection);
            }
        } catch (SQLException e) {
            throw new StoreException("self-check failed", e);
        }
    }

    /**
     * Migrates the schema; on PostgreSQL it then extends the journal's partitions
     * {@link #JOURNAL_HORIZON_MONTHS} ahead.
     */
    @Override
    public void migrate() {
        try {
            Backend backend = transactions.backend();
            // Flyway's PostgreSQL lock otherwise holds a transaction open for the whole migration,
            // and a YugabyteDB online index build waits for every open transaction to end.
            Flyway.configure()
                    .configuration(Map.of("flyway.postgresql.transactional.lock",
                            String.valueOf(backend.transactionalSchemaSteps)))
                    .dataSource(maintenanceDataSource).locations(backend.migrations)
                    .executeInTransaction(backend.transactionalSchemaSteps)
                    .lockRetryCount(MIGRATION_LOCK_RETRIES).load().migrate();
        } catch (FlywayException e) {
            throw new StoreException("schema migration failed", e);
        }
        extendJournal(Instant.now().atOffset(ZoneOffset.UTC).plusMonths(JOURNAL_HORIZON_MONTHS).toInstant());
    }

    /**
     * How many times, a second apart, a node retries Flyway's migration lock while another node
     * migrates: 5 minutes. Flyway's default, 50, failed a second node's start while the first ran
     * V3 on a loaded YugabyteDB cluster.
     */
    static final int MIGRATION_LOCK_RETRIES = 300;

    /** How far ahead the journal's monthly partitions reach; a maintenance job keeps it so. */
    public static final int JOURNAL_HORIZON_MONTHS = 12;

    /**
     * Creates the journal's missing monthly partitions through the month holding {@code through},
     * moving any commits the default partition holds for those months into them. Returns how many
     * it created. Safe to call from several nodes at once. Only PostgreSQL partitions its journal;
     * CockroachDB and YugabyteDB split it into ranges and tablets themselves, so there it creates none.
     */
    public int extendJournal(Instant through) {
        if (!transactions.backend().partitionsJournal) {
            return 0;
        }
        return maintenance.writeAndRetryOnFailure("journal partition extension", connection ->
                Jdbc.first(connection, "SELECT journal_extend(?)", row -> row.getInt(1), through).orElseThrow(),
                created -> true);
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
    public Page<CatalogObject> listPurgeEligible(Instant now, PageRequest page) {
        return objects.listPurgeEligible(now, page);
    }

    @Override
    public WriteResult purgeObject(UUID objectId, Change change) {
        return objects.purge(objectId, change);
    }

    // ---- grants ----

    @Override
    public WriteResult putGrant(Grant grant, String grantedBy, Change change) {
        return grants.put(grant, grantedBy, change);
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
}
