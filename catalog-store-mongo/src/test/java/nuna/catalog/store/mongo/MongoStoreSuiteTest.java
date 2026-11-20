package nuna.catalog.store.mongo;

import static nuna.catalog.store.suite.Changes.creation;
import static nuna.catalog.store.suite.Changes.now;
import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.fullBatch;
import static nuna.catalog.store.suite.Commits.newKey;
import static nuna.catalog.store.suite.Commits.next;
import static nuna.catalog.store.suite.Commits.pointer;
import static nuna.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoDatabase;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEntry;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.StoreUnavailableException;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import nuna.catalog.store.suite.CatalogObjectSuite;
import nuna.catalog.store.suite.BranchCommitSuite;
import nuna.catalog.store.suite.CatalogStoreUnderTest;
import nuna.catalog.store.suite.Changes;
import nuna.catalog.store.suite.CommitSuite;
import nuna.catalog.store.suite.GovernanceSuite;
import nuna.catalog.store.suite.DrSuite;
import nuna.catalog.store.suite.HealthSuite;
import nuna.catalog.store.suite.GrantSuite;
import nuna.catalog.store.suite.IdempotencySuite;
import nuna.catalog.store.suite.JournalSuite;
import nuna.catalog.store.suite.MutationSuite;
import nuna.catalog.store.suite.NamespaceSuite;
import nuna.catalog.store.suite.OutboxSuite;
import nuna.catalog.store.suite.RefSuite;
import nuna.catalog.store.suite.SizeSuite;
import nuna.catalog.store.suite.StoreUnderTest;
import nuna.catalog.store.suite.UpgradeSuite;
import nuna.catalog.store.suite.WrittenRows;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared suite against a three-member MongoDB 8.0 replica set: the commit suite, every group
 * suite as a nested class on the same backend, and MongoDB-only hazards.
 */
@Tag("heavy")
class MongoStoreSuiteTest extends CommitSuite {

    private MongoReplicaSet mongo;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @Override
    protected StoreUnderTest storeUnderTest() {
        mongo = new MongoReplicaSet();
        return mongo;
    }

    @AfterAll
    void stopThreads() {
        threads.shutdownNow();
    }

    @Nested
    class Namespaces extends NamespaceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class CatalogObjects extends CatalogObjectSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Grants extends GrantSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Idempotency extends IdempotencySuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Outbox extends OutboxSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Governance extends GovernanceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Health extends HealthSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Dr extends DrSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Refs extends RefSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class BranchCommits extends BranchCommitSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Journal extends JournalSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Size extends SizeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Upgrade extends UpgradeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Nested
    class Mutations extends MutationSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return mongo;
        }
    }

    @Test
    @DisplayName("MongoDB: a commitTransaction whose outcome comes back unknown is sent again on the same session"
            + " and answers Committed, without replaying the transaction")
    void unknownCommitOutcomeIsResolvedOnTheSameSession() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("outcome-unknown", 2)));
        // The driver resends commitTransaction once within each call, so two unknown answers outlast
        // the store's first call whether or not the driver resends. A server fail point rather than
        // a link fault: how long one call lasts under a link fault depends on the driver's monitor.
        mongo.commitAnswersUnknown(2);

        assertThat(store().commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        assertThat(store().readPointer(key)).contains(next(batch));
        assertThat(mongo.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
    }

    /**
     * The members elect a new primary only after electionTimeoutMillis (10 s), so for longer than the stores'
     * server selection timeout (5 s) there is none: the commit's first read in its transaction finds no primary
     * (backlog 7).
     */
    @Test
    @DisplayName("MongoDB: a commit while the set is failing over, its primary killed and no new one elected yet, is"
            + " a clean failure the caller may repeat (unavailable), and writes nothing")
    void commitWhileFailingOverIsUnavailable() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("no-primary", 2)));
        mongo.killPrimary();

        assertThatThrownBy(() -> store().commit(batch)).isInstanceOf(StoreUnavailableException.class);
        mongo.promoteReplica();

        assertThat(mongo.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
        assertThat(store().readPointer(key)).contains(base);
    }

    @Test
    @DisplayName("MongoDB: migrate is repeatable and records every step once")
    void migrateIsRepeatable() {
        store().migrate();
        store().migrate();

        BsonDocument schema = mongo.schemaVersion(mongo.database(MongoReplicaSet.DATABASE));
        assertThat(schema.getInt32("version").getValue()).isEqualTo(MongoSchema.STEPS.getLast().version());
        assertThat(schema.getArray("applied")).extracting(step -> step.asDocument().getString("description"))
                .containsExactlyElementsOf(MongoSchema.STEPS.stream().map(step -> new BsonString(step.description())).toList());
    }

    @Test
    @DisplayName("MongoDB: the schema runner skips applied steps, and runners racing each other record each step once")
    void schemaRunnerAppliesEachStepOnce() throws Exception {
        Map<Integer, AtomicInteger> runs = new ConcurrentHashMap<>();
        List<MongoSchema.Step> steps = List.of(1, 2, 3).stream().map(version -> new MongoSchema.Step(version,
                "step " + version, database -> runs.computeIfAbsent(version, v -> new AtomicInteger()).incrementAndGet())).toList();

        MongoDatabase alone = mongo.database("schema_" + UUID.randomUUID().toString().replace("-", ""));
        MongoSchema.migrate(alone, steps);
        MongoSchema.migrate(alone, steps);
        assertThat(runs).hasSize(steps.size()).allSatisfy((version, count) -> assertThat(count).as("runs of step %d", version).hasValue(1));
        assertRecordedOnce(mongo.schemaVersion(alone), steps);

        MongoDatabase raced = mongo.database("schema_" + UUID.randomUUID().toString().replace("-", ""));
        CyclicBarrier start = new CyclicBarrier(8);
        List<Future<?>> runners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            runners.add(threads.submit(() -> {
                start.await();
                MongoSchema.migrate(raced, steps);
                return null;
            }));
        }
        for (Future<?> runner : runners) {
            runner.get(60, TimeUnit.SECONDS);
        }
        assertRecordedOnce(mongo.schemaVersion(raced), steps);
    }

    private static void assertRecordedOnce(BsonDocument schema, List<MongoSchema.Step> steps) {
        assertThat(schema.getInt32("version").getValue()).isEqualTo(steps.getLast().version());
        assertThat(schema.getArray("applied")).extracting(step -> step.asDocument().getInt32("version").getValue())
                .containsExactlyElementsOf(steps.stream().map(MongoSchema.Step::version).toList());
    }

    /**
     * Outbox entry ids are ObjectIds, which begin with their process's clock. Two processes'
     * clocks disagree, so the ids alone would let a later change's events overtake an earlier
     * one's. The Lamport clock orders them: each change stamps above every record it writes.
     */
    @Test
    @DisplayName("MongoDB: the relay claims the events of successive changes to one record in change order, even when"
            + " the later change comes from a process whose clock is an hour behind")
    void relayOrderFollowsChangesNotProcessClocks() {
        String database = mongo.freshDatabase();
        CatalogStore ahead = mongo.store(database, ObjectId::new);
        CatalogStore behind = mongo.store(database, () -> new ObjectId(Date.from(Instant.now().minus(Duration.ofHours(1)))));

        PointerKey key = newKey();
        CommitBatch first = commitWithEvent(ahead, key, Optional.empty(), "pointer-1");
        commitWithEvent(behind, key, Optional.of(next(first)), "pointer-2");

        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "lamport", StorageProfile.Kind.S3, Optional.empty(),
                "arn:aws:iam::000000000000:role/lamport", List.of(), Optional.empty());
        assertApplied(ahead.putStorageProfile(profile, event("profile")));
        UUID catalogId = UUID.randomUUID();
        assertApplied(ahead.createCatalog(new CatalogRecord(catalogId, "lamport", "Lamport"),
                new CatalogSettings(profile.id(), "kms-lamport", "lamport-entity"), event("catalog")));
        NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("ns", "ns"), Map.of(), 1);
        assertApplied(ahead.createNamespace(namespace, event("namespace-created")));
        assertApplied(ahead.updateNamespaceProperties(namespace.id(), 1, Map.of("k", "1"), event("namespace-1")));
        assertApplied(behind.updateNamespaceProperties(namespace.id(), 2, Map.of("k", "2"), event("namespace-2")));
        Grant grant = new Grant(catalogId, new Securable(Securable.Kind.NAMESPACE, namespace.id()), "analysts", "READ");
        assertApplied(ahead.putGrant(grant, "admin", event("grant-1")));
        assertApplied(ahead.revokeGrant(grant, event("grant-revoked")));
        assertApplied(behind.putGrant(grant, "admin", event("grant-2")));

        // A removal deletes its record, so it marks the parent or target, which the later change sees.
        NamespaceRecord gone = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("gone", "gone"), Map.of(), 1);
        assertApplied(ahead.createNamespace(gone, event("gone-created")));
        assertApplied(ahead.dropNamespace(gone.id(), event("gone-dropped")));
        assertApplied(behind.createNamespace(new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("gone", "gone"),
                Map.of(), 1), event("gone-recreated")));
        Securable target = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        GovernanceAttribute owner = new GovernanceAttribute(target, GovernanceAttribute.Kind.OWNER, "team", "manual", "admin", now());
        assertApplied(ahead.putAttribute(owner, event("attribute-1")));
        assertApplied(ahead.removeAttribute(target, GovernanceAttribute.Kind.OWNER, event("attribute-removed")));
        assertApplied(behind.putAttribute(owner, event("attribute-2")));
        LegalHold hold = new LegalHold(UUID.randomUUID(), target, "case", "legal", now());
        assertApplied(ahead.placeHold(hold, event("hold-1")));
        assertApplied(ahead.releaseHold(hold.id(), event("hold-released")));
        assertApplied(behind.placeHold(new LegalHold(UUID.randomUUID(), target, "case", "legal", now()), event("hold-2")));

        TrustedEngine engine = new TrustedEngine("https://issuer.lamport", "spark", "sub", Set.of("delegated"));
        assertApplied(ahead.putTrustedEngine(engine, event("engine-1")));
        assertApplied(ahead.removeTrustedEngine(engine.issuer(), engine.clientId(), event("engine-removed")));
        assertApplied(behind.putTrustedEngine(engine, event("engine-2")));

        List<OutboxEntry> relayed = ahead.claimOutbox(UUID.randomUUID(), now(), Duration.ofMinutes(1), 1_000)
                .orElseThrow().entries();
        List<String> types = relayed.stream().map(OutboxEntry::type).toList();
        assertThat(entryOf(relayed, "pointer-2").id()).as("the later process's ids sort first")
                .isLessThan(entryOf(relayed, "pointer-1").id());
        assertThat(types).containsSubsequence("pointer-1", "pointer-2");
        assertThat(types).containsSubsequence("profile", "catalog", "namespace-created", "namespace-1", "namespace-2",
                "grant-1", "grant-revoked", "grant-2");
        assertThat(types).containsSubsequence("gone-created", "gone-dropped", "gone-recreated");
        assertThat(types).containsSubsequence("attribute-1", "attribute-removed", "attribute-2");
        assertThat(types).containsSubsequence("hold-1", "hold-released", "hold-2");
        assertThat(types).containsSubsequence("engine-1", "engine-removed", "engine-2");
    }

    private static CommitBatch commitWithEvent(CatalogStore store, PointerKey key, Optional<Pointer> base, String type) {
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), now(), List.of(advance(key, base, type)),
                List.of(new OutboxEvent(type, "{}")), Optional.empty());
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        return batch;
    }

    private static Change event(String type) {
        return new Change(UUID.randomUUID(), now(), List.of(new OutboxEvent(type, "{}")), Optional.empty());
    }

    private static OutboxEntry entryOf(List<OutboxEntry> entries, String type) {
        return entries.stream().filter(entry -> entry.type().equals(type)).findFirst().orElseThrow();
    }

    private static void assertApplied(WriteResult result) {
        assertThat(result).isEqualTo(WriteResult.APPLIED);
    }

    /**
     * MongoDB has no shared locks. A write that depends on a record it does not change marks that
     * record, so it conflicts with an open drop of the record, waits it out, and then finds the
     * record gone. Without the mark it would read the record from its snapshot and orphan itself.
     */
    @Test
    @DisplayName("MongoDB: a write that depends on a namespace or table waits for an open drop or purge of it, then"
            + " finds it gone")
    void dependentWritesWaitForAnOpenDrop() throws Exception {
        CatalogStore store = mongo.store(CommitHook.NONE);
        UUID catalogId = newCatalog(store, "dependents");
        Map<String, Function<NamespaceRecord, WriteResult>> dependents = new LinkedHashMap<>();
        dependents.put("child namespace", parent -> store.createNamespace(new NamespaceRecord(catalogId, UUID.randomUUID(),
                parent.id(), new Name("child", "child"), Map.of(), 1), Changes.change()));
        dependents.put("table", parent -> store.createObject(creation(CatalogObject.active(catalogId, UUID.randomUUID(),
                parent.id(), new Name("t", "t"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID()))));
        dependents.put("grant", parent -> store.putGrant(new Grant(catalogId,
                new Securable(Securable.Kind.NAMESPACE, parent.id()), "analysts", "READ"), "admin", Changes.change()));
        dependents.put("attribute", parent -> store.putAttribute(new GovernanceAttribute(
                new Securable(Securable.Kind.NAMESPACE, parent.id()), GovernanceAttribute.Kind.OWNER, "team", "manual",
                "admin", now()), Changes.change()));
        dependents.put("hold", parent -> store.placeHold(new LegalHold(UUID.randomUUID(),
                new Securable(Securable.Kind.NAMESPACE, parent.id()), "case", "legal", now()), Changes.change()));
        NamespaceRecord elsewhere = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("elsewhere", "elsewhere"),
                Map.of(), 1);
        assertApplied(store.createNamespace(elsewhere, Changes.change()));
        CatalogObject movable = CatalogObject.active(catalogId, UUID.randomUUID(), elsewhere.id(), new Name("movable", "movable"),
                CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID());
        assertApplied(store.createObject(creation(movable)));
        dependents.put("rename into it", parent -> store.renameObject(movable.id(), parent.id(), new Name("moved", "moved"),
                Changes.change()));
        for (Map.Entry<String, Function<NamespaceRecord, WriteResult>> dependent : dependents.entrySet()) {
            NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId,
                    new Name("ns-" + dependent.getKey(), "ns-" + dependent.getKey()), Map.of(), 1);
            assertApplied(store.createNamespace(namespace, Changes.change()));
            WriteResult result = whileHeldOpen((held, drop) -> held.dropNamespace(namespace.id(), drop),
                    () -> dependent.getValue().apply(namespace), dependent.getKey());
            assertThat(result).as(dependent.getKey()).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        }

        NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("purged", "purged"),
                Map.of(), 1);
        assertApplied(store.createNamespace(namespace, Changes.change()));
        CatalogObject table = CatalogObject.active(catalogId, UUID.randomUUID(), namespace.id(), new Name("t", "t"),
                CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID());
        assertApplied(store.createObject(creation(table)));
        assertApplied(store.dropObject(table.id(), now().minus(Duration.ofDays(1)), Changes.change()));
        assertApplied(store.claimPurge(table.id(), "worker", Changes.change()));
        WriteResult granted = whileHeldOpen((held, purge) -> held.completePurge(table.id(), "worker", purge),
                () -> store.putGrant(new Grant(catalogId, table.securable(), "analysts", "READ"), "admin", Changes.change()),
                "grant on a table being purged");
        assertThat(granted).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.listGrantsOn(table.securable(), PageRequest.first(10)).items()).isEmpty();
    }

    /**
     * Runs {@code removal} with its transaction held open just before commitTransaction, starts
     * {@code dependent}, checks that it waits, releases the removal, and returns the dependent's result.
     */
    private WriteResult whileHeldOpen(BiFunction<CatalogStore, Change, WriteResult> removal,
                                      Callable<WriteResult> dependent, String what) throws Exception {
        Change change = Changes.change();
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatalogStore held = mongo.store(changeId -> {
            if (changeId.equals(change.id())) {
                atCommit.countDown();
                awaitLatch(release);
            }
        });
        Future<WriteResult> removing = threads.submit(() -> removal.apply(held, change));
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();
        Future<WriteResult> waiting = threads.submit(dependent);
        MongoReplicaSet.pause(Duration.ofMillis(1_500));
        assertThat(waiting).as("%s waits for the open removal", what).isNotDone();
        release.countDown();
        assertThat(removing.get(60, TimeUnit.SECONDS)).as(what).isEqualTo(WriteResult.APPLIED);
        return waiting.get(60, TimeUnit.SECONDS);
    }

    /** The group suites leave these two contract guards untested. */
    @Test
    @DisplayName("MongoDB: a hold on a namespace, an ancestor or the catalog refuses the namespace's drop")
    void heldNamespaceRefusesTheDrop() {
        CatalogStore store = mongo.store(CommitHook.NONE);
        UUID catalogId = newCatalog(store, "held-namespaces");
        NamespaceRecord parent = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("parent", "parent"),
                Map.of(), 1);
        assertApplied(store.createNamespace(parent, Changes.change()));
        for (UUID target : List.of(catalogId, parent.id())) {
            NamespaceRecord child = new NamespaceRecord(catalogId, UUID.randomUUID(), parent.id(),
                    new Name("child-" + target, "child-" + target), Map.of(), 1);
            assertApplied(store.createNamespace(child, Changes.change()));
            for (Securable securable : List.of(new Securable(target.equals(catalogId) ? Securable.Kind.CATALOG
                    : Securable.Kind.NAMESPACE, target), new Securable(Securable.Kind.NAMESPACE, child.id()))) {
                LegalHold hold = new LegalHold(UUID.randomUUID(), securable, "case", "legal", now());
                assertApplied(store.placeHold(hold, Changes.change()));
                assertThat(store.dropNamespace(child.id(), Changes.change())).as("hold on %s", securable)
                        .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
                assertApplied(store.releaseHold(hold.id(), Changes.change()));
            }
            assertApplied(store.dropNamespace(child.id(), Changes.change()));
        }
    }

    @Test
    @DisplayName("MongoDB: a grant naming a catalog other than its securable's is Missing and writes nothing")
    void grantNamesTheSecurablesCatalog() {
        CatalogStore store = mongo.store(CommitHook.NONE);
        UUID catalogId = newCatalog(store, "grant-home");
        UUID otherCatalogId = newCatalog(store, "grant-other");
        NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("ns", "ns"),
                Map.of(), 1);
        assertApplied(store.createNamespace(namespace, Changes.change()));
        Securable securable = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        assertThat(store.putGrant(new Grant(otherCatalogId, securable, "analysts", "READ"), "admin", Changes.change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.listGrantsOn(securable, PageRequest.first(10)).items()).isEmpty();
    }

    /** A trusted engine has no parent to mark, so only its unique index keeps concurrent first puts to one record. */
    @Test
    @DisplayName("MongoDB: 8 concurrent puts of one new trusted engine leave one record")
    void concurrentPutsOfOneEngineLeaveOneRecord() throws Exception {
        CatalogStore store = mongo.store(CommitHook.NONE);
        TrustedEngine engine = new TrustedEngine("https://issuer.concurrent/" + UUID.randomUUID(), "spark", "sub",
                Set.of("delegated"));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WriteResult>> puts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            puts.add(threads.submit(() -> {
                awaitLatch(start);
                return store.putTrustedEngine(engine, Changes.change());
            }));
        }
        start.countDown();
        for (Future<WriteResult> put : puts) {
            assertThat(put.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
        }
        assertThat(store.listTrustedEngines()).filteredOn(listed -> listed.issuer().equals(engine.issuer())).hasSize(1);
    }

    private static UUID newCatalog(CatalogStore store, String name) {
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), name, StorageProfile.Kind.S3, Optional.empty(),
                "arn:aws:iam::000000000000:role/" + name, List.of(), Optional.empty());
        assertApplied(store.putStorageProfile(profile, Changes.change()));
        UUID catalogId = UUID.randomUUID();
        assertApplied(store.createCatalog(new CatalogRecord(catalogId, name + "-" + catalogId, name),
                new CatalogSettings(profile.id(), "kms-" + name, name + "-entity"), Changes.change()));
        return catalogId;
    }

    /**
     * MongoDB compares strings by code point unless a collation says otherwise, and code point
     * order is UTF-8 byte order. Java's String order is UTF-16 order, which puts characters above
     * U+FFFF before those from U+E000 to U+FFFF.
     */
    @Test
    @DisplayName("MongoDB: namespaces and objects list in UTF-8 byte order of their names, not by a locale or UTF-16")
    void namesListInByteOrder() {
        CatalogStore store = mongo.store(CommitHook.NONE);
        UUID catalogId = newCatalog(store, "bytes");
        NamespaceRecord parent = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId, new Name("parent", "parent"),
                Map.of(), 1);
        assertApplied(store.createNamespace(parent, Changes.change()));
        List<String> names = List.of("z-🧊", "a", "z-ｚ", "B", "日", "z", "é");
        for (String name : names) {
            assertApplied(store.createNamespace(new NamespaceRecord(catalogId, UUID.randomUUID(), parent.id(),
                    new Name(name, name), Map.of(), 1), Changes.change()));
            assertApplied(store.createObject(creation(CatalogObject.active(catalogId, UUID.randomUUID(), parent.id(),
                    new Name(name, name), CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID()))));
        }
        Comparator<String> utf8 = (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
        List<String> byBytes = names.stream().sorted(utf8).toList();
        assertThat(byBytes).as("the names tell byte order from UTF-16 order").isNotEqualTo(names.stream().sorted().toList());

        assertThat(store.listNamespaces(parent.id(), PageRequest.first(100)).items())
                .extracting(namespace -> namespace.name().normalized()).containsExactlyElementsOf(byBytes);
        assertThat(store.listObjects(parent.id(), CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE,
                PageRequest.first(100)).items()).extracting(object -> object.name().normalized()).containsExactlyElementsOf(byBytes);
    }

    /**
     * A snapshot transaction reads the newest data on the primary, which may not be on a majority
     * yet. A claim that finds the key done there must not hand back that answer until it is
     * durable, and a read of the key must not report it done before then.
     */
    @Test
    @DisplayName("MongoDB: a key done on the primary but not yet on a majority is neither read nor replayed as done until it is")
    void doneKeyIsAnsweredOnlyWhenDurable() throws Exception {
        CatalogStore store = mongo.store(CommitHook.NONE);
        String key = "durable-" + UUID.randomUUID();
        Instant at = now();
        UUID holder = UUID.randomUUID();
        assertThat(store.claimKey(claim(key, holder, at))).isEqualTo(KeyState.CLAIMED);
        IdempotencyResult result = Changes.result(key, 201, at);
        mongo.stallReplication();
        try {
            Future<WriteResult> completion = threads.submit(() -> store.completeKey(result, holder));
            MongoReplicaSet.await(Duration.ofSeconds(30), () -> mongo.doneOnPrimary(key), "the completion to apply on the primary");

            assertThat(store.readKey(key, now())).as("a read while the answer is on the primary only")
                    .isInstanceOf(KeyState.InFlight.class);
            Future<KeyState> replay = threads.submit(() -> store.claimKey(claim(key, UUID.randomUUID(), now())));
            MongoReplicaSet.pause(Duration.ofSeconds(2));
            assertThat(replay).as("a claim that finds the answer on the primary only waits").isNotDone();

            mongo.resumeReplication();
            assertThat(replay.get(60, TimeUnit.SECONDS)).isEqualTo(new KeyState.Done(result));
            assertThat(completion.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
            assertThat(store.readKey(key, now())).isEqualTo(new KeyState.Done(result));
        } finally {
            mongo.restore();
        }
    }

    private static IdempotencyClaim claim(String key, UUID changeId, Instant at) {
        return new IdempotencyClaim(key, "svc-suite", "hash-of-" + key, changeId, at, at.plus(Duration.ofMinutes(5)),
                at.plus(Duration.ofDays(1)));
    }

    /**
     * A projection longer than the piece budget is read as the pointer document and then its
     * pieces. A commit between the two reads replaces the pieces; the reads share one snapshot,
     * so they still see one version. Small pieces let the race run hundreds of commits cheaply;
     * the size suite covers the real budget.
     */
    @Test
    @DisplayName("MongoDB: reads of a projection in pieces, racing commits that replace it, always reassemble one version")
    void piecesAreReadFromOneVersion() throws Exception {
        CatalogStore store = mongo.store(RACE_PIECE_BYTES);
        PointerKey key = newKey();
        Random random = new Random(11);
        Map<Long, Pointer> committed = new ConcurrentHashMap<>();
        Pointer current = piecedPointer(random, 1);
        committed.put(1L, current);
        CommitBatch create = batch(swap(key, Optional.empty(), current));
        assertThat(store.commit(create)).isEqualTo(new CommitResult.Committed(create.commitId()));
        AtomicBoolean stop = new AtomicBoolean();
        Future<Integer> reader = threads.submit(() -> {
            int reads = 0;
            while (!stop.get()) {
                Pointer read = store.readPointer(key).orElseThrow();
                assertThat(read).isEqualTo(committed.get(read.version()));
                reads++;
            }
            return reads;
        });
        for (long version = 2; version <= 300; version++) {
            Pointer next = piecedPointer(random, version);
            committed.put(version, next);
            CommitBatch batch = batch(swap(key, Optional.of(current), next));
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            current = next;
        }
        stop.set(true);
        assertThat(reader.get(120, TimeUnit.SECONDS)).isPositive();
        assertThat(mongo.projectionPieces(key)).isEqualTo(3);
    }

    /**
     * A node of the previous release writes the projection whole and leaves the piece count and
     * the pieces of the version it replaced. Pieces are read by version, so none of them match.
     */
    @Test
    @DisplayName("MongoDB: a projection in pieces that a node of the previous release overwrites reads back as that node wrote it")
    void previousReleaseOverwriteOfPiecesReadsAsWritten() {
        CatalogStore store = mongo.store(RACE_PIECE_BYTES);
        PointerKey key = newKey();
        Pointer pieced = piecedPointer(new Random(5), 1);
        CommitBatch create = batch(swap(key, Optional.empty(), pieced));
        assertThat(store.commit(create)).isEqualTo(new CommitResult.Committed(create.commitId()));
        Pointer whole = pointer("whole", 2);
        CommitBatch overwrite = batch(swap(key, Optional.of(pieced), whole));
        assertThat(mongo.previousRelease().commit(overwrite)).isEqualTo(new CommitResult.Committed(overwrite.commitId()));
        assertThat(mongo.projectionPieces(key)).as("the pieces the previous release left").isEqualTo(3);
        assertThat(store.readPointer(key)).contains(whole);
    }

    private static final int RACE_PIECE_BYTES = 1024;

    private static Pointer piecedPointer(Random random, long version) {
        byte[] projection = new byte[2 * RACE_PIECE_BYTES + 64];
        random.nextBytes(projection);
        Pointer named = pointer("pieced", version);
        return new Pointer(named.metadataLocation(), version, named.sha256(), projection);
    }
}
