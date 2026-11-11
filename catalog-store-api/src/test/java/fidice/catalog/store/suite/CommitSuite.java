package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.batch;
import static fidice.catalog.store.suite.Commits.before;
import static fidice.catalog.store.suite.Commits.fullBatch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.CommitStore;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.SafetyFinding;
import fidice.catalog.store.StoreException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

/**
 * The commit suite: tests 1 to 6 of the shared invariant and fault-injection suite
 * (docs/storage-design.md sections 6 and 9). A backend extends this class and supplies a
 * {@link StoreUnderTest}; this class starts and stops it. A backend of the whole
 * {@link fidice.catalog.store.CatalogStore} also runs every group suite (see {@link GroupSuite})
 * as {@code @Nested} classes, which share the backend and its after-each restore. Test numbers
 * follow the design's table; display names start with them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class CommitSuite {

    /**
     * Rounds of the CAS linearizability test. It is tagged {@code cas} and no build runs it, fast or
     * heavy: it runs only overnight, on its own, at 10,000 rounds ({@code -Pnightly}).
     */
    static final int CAS_ROUNDS = Integer.getInteger("fidice.suite.cas-rounds", 200);
    static final int CAS_WRITERS = 8;

    private StoreUnderTest underTest;
    private CommitStore store;
    private Faults faults;
    private final ExecutorService background = Executors.newCachedThreadPool();

    /** Starts the backend's environment. Called once, before any test. */
    protected abstract StoreUnderTest storeUnderTest();

    @BeforeAll
    void startBackend() {
        underTest = storeUnderTest();
        faults = underTest.faults();
        store = underTest.store(CommitHook.NONE);
        store.migrate();
    }

    @AfterEach
    void restoreBackend() {
        faults.restore();
    }

    @AfterAll
    void stopBackend() {
        background.shutdownNow();
        if (underTest != null) {
            underTest.close();
        }
    }

    // ---- 1. CAS linearizability ----

    @Test
    @Tag("cas")
    @DisplayName("1 CAS: of N writers from one base exactly one commits and the version advances by 1, every round")
    void exactlyOneWriterWinsEachRound() throws Exception {
        PointerKey key = newKey();
        ExecutorService writers = Executors.newFixedThreadPool(CAS_WRITERS);
        try {
            Optional<Pointer> base = Optional.empty();
            for (int round = 0; round < CAS_ROUNDS; round++) {
                List<CommitBatch> batches = new ArrayList<>();
                for (int writer = 0; writer < CAS_WRITERS; writer++) {
                    batches.add(batch(advance(key, base, "round-" + round + "-writer-" + writer)));
                }
                CyclicBarrier start = new CyclicBarrier(CAS_WRITERS);
                List<Future<CommitResult>> results = new ArrayList<>();
                for (CommitBatch batch : batches) {
                    results.add(writers.submit(() -> {
                        start.await();
                        return store.commit(batch);
                    }));
                }
                List<CommitBatch> winners = new ArrayList<>();
                List<CommitResult.Conflict> conflicts = new ArrayList<>();
                for (int i = 0; i < CAS_WRITERS; i++) {
                    CommitResult result = results.get(i).get(60, TimeUnit.SECONDS);
                    if (result instanceof CommitResult.Conflict conflict) {
                        conflicts.add(conflict);
                    } else {
                        assertThat(result).isEqualTo(new CommitResult.Committed(batches.get(i).commitId()));
                        winners.add(batches.get(i));
                    }
                }
                assertThat(winners).as("winners of round %d", round).hasSize(1);
                Pointer winning = next(winners.getFirst());
                assertThat(winning.version()).isEqualTo(round + 1);
                assertThat(conflicts).as("losers of round %d", round).hasSize(CAS_WRITERS - 1)
                        .allSatisfy(conflict -> assertThat(conflict).isEqualTo(
                                new CommitResult.Conflict(key, Optional.of(winning))));
                assertThat(store.readPointer(key)).contains(winning);
                assertThat(store.findCommit(winners.getFirst().commitId(), key)).isEqualTo(CommitLookup.COMMITTED);
                base = Optional.of(winning);
            }
        } finally {
            writers.shutdownNow();
        }
        assertThat(underTest.journalVersions(key)).as("one journal entry per committed version")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, CAS_ROUNDS).boxed().toList());
    }

    @Test
    @DisplayName("1 CAS: a swap must match the stored version, not just the location")
    void staleVersionAtTheSameLocationConflicts() {
        PointerKey key = newKey();
        Pointer v1 = commitNew(key, "a");
        Pointer v2 = commitNext(key, v1, "b");
        Pointer v3 = commitNext(key, v2, "a");
        assertThat(v3.metadataLocation()).isEqualTo(v1.metadataLocation());

        CommitResult result = store.commit(batch(swap(key, Optional.of(v1), pointer("c", 2))));

        assertThat(result).isEqualTo(new CommitResult.Conflict(key, Optional.of(v3)));
        assertThat(store.readPointer(key)).contains(v3);
    }

    @Test
    @DisplayName("1 CAS: a swap must match the stored location, not just the version")
    void sameVersionAtAnotherLocationConflicts() {
        PointerKey key = newKey();
        Pointer v1 = commitNew(key, "a");

        CommitResult result = store.commit(batch(swap(key, Optional.of(pointer("x", 1)), pointer("y", 2))));

        assertThat(result).isEqualTo(new CommitResult.Conflict(key, Optional.of(v1)));
        assertThat(store.readPointer(key)).contains(v1);
    }

    @Test
    @DisplayName("1 CAS: creating a pointer that exists conflicts")
    void createOverAnExistingPointerConflicts() {
        PointerKey key = newKey();
        Pointer v1 = commitNew(key, "a");

        CommitResult result = store.commit(batch(swap(key, Optional.empty(), pointer("b", 1))));

        assertThat(result).isEqualTo(new CommitResult.Conflict(key, Optional.of(v1)));
        assertThat(store.readPointer(key)).contains(v1);
    }

    @Test
    @DisplayName("1 CAS: updating a pointer that does not exist conflicts")
    void updateOfAMissingPointerConflicts() {
        PointerKey key = newKey();

        CommitResult result = store.commit(batch(swap(key, Optional.of(pointer("a", 1)), pointer("b", 2))));

        assertThat(result).isEqualTo(new CommitResult.Conflict(key, Optional.empty()));
        assertThat(store.readPointer(key)).isEmpty();
    }

    // ---- 2. Atomicity ----

    @Test
    @DisplayName("2 Atomicity: a committed batch writes every pointer, journal, outbox and idempotency row")
    void committedBatchWritesEveryRecord() {
        List<PointerKey> keys = sortedKeys(3);
        Pointer second = commitNew(keys.get(1), "second");
        Pointer third = commitNew(keys.get(2), "third");
        CommitBatch batch = fullBatch(
                swap(keys.get(0), Optional.empty(), pointer("first-new", 1)),
                swap(keys.get(1), Optional.of(second), pointer("second-new", 2)),
                swap(keys.get(2), Optional.of(third), pointer("third-new", 2)));

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        assertThat(underTest.writtenRows(batch)).isEqualTo(new WrittenRows(3, 3, 2, 1));
        for (int i = 0; i < 3; i++) {
            assertThat(store.readPointer(keys.get(i))).contains(batch.swaps().get(i).next());
        }
        assertThat(store.findCommit(batch.commitId(), keys.get(0))).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("2 Atomicity: a multi-swap batch with one stale expectation is a conflict and writes nothing")
    void staleSwapInABatchWritesNothing() {
        List<PointerKey> keys = sortedKeys(3);
        Pointer second = commitNew(keys.get(1), "second");
        Pointer thirdV1 = commitNew(keys.get(2), "third");
        Pointer thirdV2 = commitNext(keys.get(2), thirdV1, "third-moved");
        // The stale swap sorts last, so the create and the good update are already applied when it is compared.
        CommitBatch batch = fullBatch(
                swap(keys.get(0), Optional.empty(), pointer("first-new", 1)),
                swap(keys.get(1), Optional.of(second), pointer("second-new", 2)),
                swap(keys.get(2), Optional.of(thirdV1), pointer("third-new", 2)));

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Conflict(keys.get(2), Optional.of(thirdV2)));

        assertThat(store.readPointer(keys.get(0))).isEmpty();
        assertThat(store.readPointer(keys.get(1))).contains(second);
        assertThat(store.readPointer(keys.get(2))).contains(thirdV2);
        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
        assertThat(store.findCommit(batch.commitId(), keys.get(0))).isEqualTo(CommitLookup.NOT_COMMITTED);
    }

    @Tag("heavy")
    @Test
    @DisplayName("2 Atomicity: cutting the link just before COMMIT leaves no pointer, journal, outbox or idempotency row")
    void linkCutBeforeCommitLeavesNothing() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("cut", 2)));

        assertOutcomeUnknown(underTest.store(before(batch, faults::cutLink)), batch);
        faults.healLink();

        assertThat(store.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.NOT_COMMITTED);
        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
        assertThat(store.readPointer(key)).contains(base);
    }

    @Tag("heavy")
    @Test
    @DisplayName("2 Atomicity: a link that is down before the commit starts is a clean failure, not an unknown outcome")
    void linkDownBeforeCommitIsACleanFailure() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("never", 2)));
        faults.cutLink();

        assertThatThrownBy(() -> store.commit(batch)).isInstanceOf(StoreException.class);
        faults.healLink();

        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
        assertThat(store.readPointer(key)).contains(base);
    }

    // ---- 3. Ambiguity ----

    @Tag("heavy")
    @Test
    @DisplayName("3 Ambiguity: a response lost after COMMIT is unknown, never a conflict, and resolves to COMMITTED")
    void lostResponseResolvesToCommitted() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("answered-into-the-void", 2)));

        assertOutcomeUnknown(underTest.store(before(batch, faults::dropResponses)), batch);
        faults.healLink();

        assertThat(store.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readPointer(key)).contains(next(batch));
        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
    }

    @Tag("heavy")
    @Test
    @DisplayName("3 Ambiguity: a COMMIT lost on its way is unknown, never a conflict, and resolves to NOT_COMMITTED")
    void lostCommitResolvesToNotCommitted() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("never-arrived", 2)));

        assertOutcomeUnknown(underTest.store(before(batch, faults::dropRequests)), batch);
        faults.healLink();

        assertThat(store.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.NOT_COMMITTED);
        assertThat(store.readPointer(key)).contains(base);
        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
    }

    @Tag("heavy")
    @Test
    @DisplayName("3 Ambiguity: findCommit waits for an update whose COMMIT is still in flight")
    void inFlightUpdateIsWaitedFor() throws Exception {
        PointerKey key = newKey();
        assertFindCommitWaitsForInFlightCommit(key, Optional.of(commitNew(key, "base")));
    }

    @Tag("heavy")
    @Test
    @DisplayName("3 Ambiguity: findCommit waits for a create whose COMMIT is still in flight")
    void inFlightCreateIsWaitedFor() throws Exception {
        assertFindCommitWaitsForInFlightCommit(newKey(), Optional.empty());
    }

    private void assertFindCommitWaitsForInFlightCommit(PointerKey key, Optional<Pointer> base) throws Exception {
        CommitBatch batch = batch(advance(key, base, "in-flight"));
        faults.stallReplication();

        // The commit's COMMIT waits at the primary for the replicas; the client gives up first.
        assertOutcomeUnknown(store, batch);
        CompletableFuture<CommitLookup> lookup =
                CompletableFuture.supplyAsync(() -> store.findCommit(batch.commitId(), key), background);
        Thread.sleep(1_500);
        assertThat(lookup).as("findCommit must wait while the commit can still complete").isNotDone();
        faults.resumeReplication();

        CommitLookup answer = lookup.get(60, TimeUnit.SECONDS);
        if (answer == CommitLookup.NOT_COMMITTED && underTest.stalledCommitCanBeLost()) {
            assertThat(store.readPointer(key)).as("a lost commit stays lost").isEqualTo(base);
            assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
            assertThat(store.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.NOT_COMMITTED);
            return;
        }
        assertThat(answer).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readPointer(key)).contains(next(batch));
    }

    @Test
    @DisplayName("3 Ambiguity: findCommit in another process waits for a create whose transaction is open before COMMIT")
    void openCreateIsWaitedFor() throws Exception {
        assertFindCommitWaitsForOpenTransaction(newKey(), Optional.empty());
    }

    @Test
    @DisplayName("3 Ambiguity: findCommit in another process waits for an update whose transaction is open before COMMIT")
    void openUpdateIsWaitedFor() throws Exception {
        PointerKey key = newKey();
        assertFindCommitWaitsForOpenTransaction(key, Optional.of(commitNew(key, "base")));
    }

    /**
     * The transaction has written everything and is held just before COMMIT, as when that request
     * is still on its way. Its writes are invisible to others until it commits, so a lookup that
     * does not wait for it answers NOT_COMMITTED and is then wrong. Replication-stall variants miss
     * this on MongoDB, whose snapshot reads see locally committed writes.
     */
    private void assertFindCommitWaitsForOpenTransaction(PointerKey key, Optional<Pointer> base) throws Exception {
        CommitBatch batch = batch(advance(key, base, "held-open"));
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommitStore held = underTest.store(before(batch, () -> {
            atCommit.countDown();
            awaitLatch(release);
        }));
        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> held.commit(batch), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).as("the commit reached COMMIT").isTrue();

        CommitStore anotherProcess = underTest.storeOfAnotherProcess();
        CompletableFuture<CommitLookup> lookup = CompletableFuture.supplyAsync(
                () -> anotherProcess.findCommit(batch.commitId(), key), background);
        Thread.sleep(1_500);
        assertThat(lookup).as("findCommit must wait while the open transaction can still commit").isNotDone();
        release.countDown();

        assertThat(commit.get(60, TimeUnit.SECONDS)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(lookup.get(60, TimeUnit.SECONDS)).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readPointer(key)).contains(next(batch));
    }

    // ---- 4. Failover ----

    @Tag("heavy")
    @Test
    @DisplayName("4 Failover: every commit acknowledged before the primary is lost is present after promotion")
    void acknowledgedCommitsSurviveFailover() throws Exception {
        assumeTrue(underTest.supportsFailover(), "the backend declares it cannot fail over in this environment");
        PointerKey key = newKey();
        List<CommitBatch> acknowledged = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CommitBatch> unanswered = new AtomicReference<>();
        Future<?> writer = background.submit(() -> {
            Optional<Pointer> current = Optional.empty();
            while (true) {
                CommitBatch batch = batch(advance(key, current, "stream-" + acknowledged.size()));
                try {
                    assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
                } catch (StoreException | CommitOutcomeUnknownException e) {
                    unanswered.set(batch);
                    return;
                }
                acknowledged.add(batch);
                current = Optional.of(next(batch));
            }
        });
        awaitTrue(Duration.ofSeconds(30), () -> acknowledged.size() >= 20, "20 acknowledged commits");
        // With replication stalled no further commit may be acknowledged; any that is would be lost below.
        faults.stallReplication();
        Thread.sleep(1_000);
        faults.killPrimary();
        writer.get(60, TimeUnit.SECONDS);
        faults.promoteReplica();

        List<CommitBatch> acked = List.copyOf(acknowledged);
        assertThat(unanswered.get()).as("the writer saw the primary go").isNotNull();
        for (CommitBatch batch : acked) {
            assertThat(store.findCommit(batch.commitId(), key)).as("acknowledged %s", next(batch))
                    .isEqualTo(CommitLookup.COMMITTED);
        }
        CommitLookup lastOutcome = store.findCommit(unanswered.get().commitId(), key);
        Pointer expected = lastOutcome == CommitLookup.COMMITTED ? next(unanswered.get()) : next(acked.getLast());
        assertThat(store.readPointer(key)).contains(expected);
        assertThat(underTest.journalVersions(key))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, expected.version()).boxed().toList());
    }

    // ---- 5. Self-check ----

    @Test
    @DisplayName("5 Self-check: the safe environment reports no findings")
    void safeEnvironmentHasNoFindings() {
        assumeTrue(underTest.hasSafeEnvironment(), "the backend declares no safe environment here");
        assertThat(store.selfCheck()).isEmpty();
    }

    @TestFactory
    @DisplayName("5 Self-check: each unsafe setting reports its finding code")
    Stream<DynamicTest> eachUnsafeSettingReportsItsFinding() {
        return underTest.unsafeSettings().stream().map(setting -> dynamicTest(
                setting.description() + " -> " + setting.findingCode(), () -> {
                    List<SafetyFinding> findings = setting.selfCheck().get();
                    assertThat(findings).extracting(SafetyFinding::code).contains(setting.findingCode());
                    assertThat(findings).allSatisfy(finding -> assertThat(finding.detail()).isNotBlank());
                }));
    }

    @Test
    @DisplayName("5 Self-check: every finding code has an unsafe setting that stages it")
    void everyFindingCodeIsStaged() {
        assertThat(underTest.unsafeSettings()).extracting(UnsafeSetting::findingCode)
                .containsAll(underTest.findingCodes());
    }

    // ---- 6. Read-after-commit ----

    @Tag("heavy")
    @Test
    @DisplayName("6 Read-after-commit: a read after an acknowledged commit sees it while replicas lag")
    void readAfterCommitSeesItDespiteReplicaLag() {
        assumeTrue(underTest.hasReplica(), "the backend declares no replica in this environment");
        PointerKey key = newKey();
        Pointer current = commitNew(key, "base");
        faults.lagReplicaApply(Duration.ofSeconds(10));
        for (int i = 0; i < 5; i++) {
            current = commitNext(key, current, "read-after-" + i);
            assertThat(store.readPointer(key)).contains(current);
            assertThat(faults.readPointerOnReplica(key)).as("the injected lag must show on the replica")
                    .isNotEqualTo(Optional.of(current));
        }
    }

    // ---- helpers ----

    /** The store under test with no commit hook. */
    protected CommitStore store() {
        return store;
    }

    protected Pointer commitNew(PointerKey key, String name) {
        CommitBatch batch = batch(advance(key, Optional.empty(), name));
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        return next(batch);
    }

    protected Pointer commitNext(PointerKey key, Pointer current, String name) {
        CommitBatch batch = batch(advance(key, Optional.of(current), name));
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        return next(batch);
    }

    protected static void assertOutcomeUnknown(CommitStore store, CommitBatch batch) {
        assertThatThrownBy(() -> store.commit(batch))
                .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                        unknown -> assertThat(unknown.commitId()).isEqualTo(batch.commitId()));
    }

    protected static void awaitTrue(Duration limit, java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    /** Blocks a held commit until the test releases it. */
    protected static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the commit");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<PointerKey> sortedKeys(int count) {
        return Stream.generate(Commits::newKey).limit(count).sorted().toList();
    }
}
