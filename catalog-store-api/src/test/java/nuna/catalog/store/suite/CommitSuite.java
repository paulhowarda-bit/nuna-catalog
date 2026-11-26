package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.before;
import static nuna.catalog.store.suite.Commits.fullBatch;
import static nuna.catalog.store.suite.Commits.holding;
import static nuna.catalog.store.suite.Commits.newKey;
import static nuna.catalog.store.suite.Commits.next;
import static nuna.catalog.store.suite.Commits.pointer;
import static nuna.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.CommitStore;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
 * {@link nuna.catalog.store.CatalogStore} also runs every group suite (see {@link GroupSuite})
 * as {@code @Nested} classes, which share the backend and its after-each restore. Test numbers
 * follow the design's table; display names start with them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class CommitSuite {

    /**
     * Rounds of the CAS linearizability test. It is tagged {@code cas} and no build runs it, fast or
     * heavy: it runs only overnight, on its own, at 1,000 rounds ({@code -Pnightly}; Paul, 2026-11-11).
     */
    static final int CAS_ROUNDS = Integer.getInteger("nuna.suite.cas-rounds", 200);
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
        AtomicInteger repeated = new AtomicInteger();
        AtomicInteger reread = new AtomicInteger();
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
                        return repeating(StoreUnavailableException.class, () -> store.commit(batch), repeated);
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
                assertThat(repeating(StoreUnavailableException.class, () -> store.readPointer(key), reread))
                        .contains(winning);
                assertThat(repeating(StoreUnavailableException.class,
                        () -> store.findCommit(winners.getFirst().commitId(), key), reread))
                        .isEqualTo(CommitLookup.COMMITTED);
                base = Optional.of(winning);
            }
            assertThat(repeating(RuntimeException.class, () -> underTest.journalVersions(key), reread))
                    .as("one journal entry per committed version")
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, CAS_ROUNDS).boxed().toList());
        } finally {
            writers.shutdownNow();
            if (repeated.get() > 0) {
                System.out.println("CAS: " + repeated + " commits repeated after the store could not reach the database");
            }
            if (reread.get() > 0) {
                System.out.println("CAS: " + reread + " reads repeated after they could not reach the database");
            }
        }
    }

    /**
     * Asks {@code request} again, after 1 s, while it fails with {@code transientFailure}; a third such failure in a
     * row still fails the test. The overnight run of 2026-11-16 lost this test to one CockroachDB connection that
     * waited out its 15 s while the Docker host stalled (backlog 55), and every request of a round meets that stall
     * alike: each writer's commit, and the round's reads of the pointer and the commit's outcome after it. Those are
     * asked again on {@link StoreUnavailableException} alone: it means the store could not reach the database and
     * nothing was applied, and its callers repeat it (the commit engine does). The journal read at the end is the
     * suite's own instrument, not the store under test, so any failure of it is asked again: the backends' drivers
     * fail in their own types, and reading again cannot change what was committed.
     */
    private static <T> T repeating(Class<? extends RuntimeException> transientFailure, Supplier<T> request,
                                   AtomicInteger repeated) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException e) {
                if (!transientFailure.isInstance(e) || attempt == 3) {
                    throw e;
                }
                repeated.incrementAndGet();
                Thread.sleep(1_000);
            }
        }
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

    @Test
    @DisplayName("2 Atomicity: a held pointer is compared but not written: no new version and no journal entry")
    void heldPointerIsComparedNotWritten() {
        List<PointerKey> keys = sortedKeys(3);
        Pointer first = commitNew(keys.get(0), "first");
        Pointer held = commitNew(keys.get(1), "held");
        Pointer third = commitNew(keys.get(2), "third");
        // The held pointer sorts between the two swaps.
        CommitBatch batch = holding(fullBatch(
                        swap(keys.get(0), Optional.of(first), pointer("first-new", 2)),
                        swap(keys.get(2), Optional.of(third), pointer("third-new", 2))),
                new PointerHold(keys.get(1), held));

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        assertThat(underTest.writtenRows(batch)).isEqualTo(new WrittenRows(2, 2, 2, 1));
        assertThat(store.readPointer(keys.get(0))).contains(batch.swaps().get(0).next());
        assertThat(store.readPointer(keys.get(1))).contains(held);
        assertThat(store.readPointer(keys.get(2))).contains(batch.swaps().get(1).next());
        assertThat(underTest.journalVersions(keys.get(1))).containsExactly(1L);
        assertThat(commitNext(keys.get(1), held, "held-moved").version()).isEqualTo(2);
    }

    @Test
    @DisplayName("2 Atomicity: a batch holding a pointer that has moved is a conflict and writes nothing")
    void staleHoldInABatchWritesNothing() {
        List<PointerKey> keys = sortedKeys(2);
        Pointer first = commitNew(keys.get(0), "first");
        Pointer heldV1 = commitNew(keys.get(1), "held");
        Pointer heldV2 = commitNext(keys.get(1), heldV1, "held-moved");
        // The stale hold sorts last, so the swap is already applied when it is compared.
        CommitBatch batch = holding(fullBatch(swap(keys.get(0), Optional.of(first), pointer("first-new", 2))),
                new PointerHold(keys.get(1), heldV1));

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Conflict(keys.get(1), Optional.of(heldV2)));

        assertThat(store.readPointer(keys.get(0))).contains(first);
        assertThat(store.readPointer(keys.get(1))).contains(heldV2);
        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
        assertThat(store.findCommit(batch.commitId(), keys.get(0))).isEqualTo(CommitLookup.NOT_COMMITTED);
    }

    @Test
    @DisplayName("2 Atomicity: a hold of no pointer is compared and leaves none; once a pointer is there, the same hold is a conflict and writes nothing (backlog 278)")
    void heldAbsenceIsComparedNotWritten() {
        List<PointerKey> keys = sortedKeys(3);
        Pointer first = commitNew(keys.get(0), "first");
        Pointer third = commitNew(keys.get(2), "third");
        // The held key sorts between the two swaps.
        CommitBatch batch = holding(fullBatch(
                        swap(keys.get(0), Optional.of(first), pointer("first-new", 2)),
                        swap(keys.get(2), Optional.of(third), pointer("third-new", 2))),
                new PointerHold(keys.get(1), Optional.empty()));

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        assertThat(underTest.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
        assertThat(store.readPointer(keys.get(1))).as("the hold leaves no pointer").isEmpty();
        assertThat(underTest.journalVersions(keys.get(1))).isEmpty();
        Pointer created = commitNew(keys.get(1), "created");
        assertThat(created.version()).as("a create after the hold finds the key free").isEqualTo(1);

        CommitBatch stale = holding(fullBatch(advance(keys.get(0), Optional.of(batch.swaps().get(0).next()), "first-stale")),
                new PointerHold(keys.get(1), Optional.empty()));
        assertThat(store.commit(stale)).isEqualTo(new CommitResult.Conflict(keys.get(1), Optional.of(created)));
        assertThat(underTest.writtenRows(stale)).isEqualTo(WrittenRows.NONE);
        assertThat(store.readPointer(keys.get(0))).contains(batch.swaps().get(0).next());
        assertThat(store.readPointer(keys.get(1))).contains(created);
    }

    /**
     * A hold of no pointer takes the key as a create does, so a create of the key and the hold take turns: a create
     * held open before COMMIT keeps the hold waiting, which then finds the pointer and loses; a hold held open keeps
     * the create waiting, which then finds the key free (backlog 278).
     */
    @Tag("heavy")
    @Test
    @DisplayName("2 Atomicity: a hold of no pointer and a create of that key take turns, so a pointer created before the hold's COMMIT fails the hold")
    void heldAbsenceAndACreateTakeTurns() throws Exception {
        List<PointerKey> keys = sortedKeys(2);
        Pointer a = commitNew(keys.get(0), "a");
        PointerKey absent = keys.get(1);

        CommitBatch create = batch(advance(absent, Optional.empty(), "created-first"));
        CommitBatch hold = holding(batch(advance(keys.get(0), Optional.of(a), "held-after")),
                new PointerHold(absent, Optional.empty()));
        assertThat(heldOpenThenRaced(create, hold)).as("the create, then the hold")
                .containsExactly(new CommitResult.Committed(create.commitId()),
                        new CommitResult.Conflict(absent, Optional.of(next(create))));
        assertThat(store.readPointer(keys.get(0))).contains(a);

        PointerKey stillAbsent = sortedKeys(1).getFirst();
        CommitBatch holdFirst = holding(batch(advance(keys.get(0), Optional.of(a), "held-first")),
                new PointerHold(stillAbsent, Optional.empty()));
        CommitBatch createAfter = batch(advance(stillAbsent, Optional.empty(), "created-after"));
        assertThat(heldOpenThenRaced(holdFirst, createAfter)).as("the hold, then the create")
                .containsExactly(new CommitResult.Committed(holdFirst.commitId()),
                        new CommitResult.Committed(createAfter.commitId()));
        assertThat(store.readPointer(keys.get(0))).contains(next(holdFirst));
        assertThat(store.readPointer(stillAbsent)).contains(next(createAfter));
        assertThat(underTest.journalVersions(stillAbsent)).containsExactly(1L);
    }

    /**
     * Commits {@code open} and holds it just before its COMMIT, then commits {@code racing}, which must wait for it;
     * releases {@code open} and gives both answers, in that order.
     */
    private List<CommitResult> heldOpenThenRaced(CommitBatch open, CommitBatch racing) throws Exception {
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommitStore held = underTest.store(before(open, () -> {
            atCommit.countDown();
            awaitLatch(release);
        }));
        CompletableFuture<CommitResult> openResult = CompletableFuture.supplyAsync(() -> held.commit(open), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).as("the first batch reached COMMIT").isTrue();

        CompletableFuture<CommitResult> racingResult = CompletableFuture.supplyAsync(() -> store.commit(racing),
                background);
        Thread.sleep(1_500);
        assertThat(racingResult).as("the second batch must wait while the first holds the key").isNotDone();
        release.countDown();
        return List.of(openResult.get(60, TimeUnit.SECONDS), racingResult.get(60, TimeUnit.SECONDS));
    }

    /**
     * Write skew: each batch holds the pointer the other swaps, both from the same reads. Held only at the
     * comparison, both would commit. Held to COMMIT, the second waits for the first, then finds the pointer
     * it holds moved.
     */
    @Tag("heavy")
    @Test
    @DisplayName("2 Atomicity: a held pointer stays locked until COMMIT, so of two batches that each hold what the other swaps, one commits")
    void holdLastsUntilCommit() throws Exception {
        List<PointerKey> keys = sortedKeys(2);
        Pointer a = commitNew(keys.get(0), "a");
        Pointer b = commitNew(keys.get(1), "b");
        CommitBatch first = holding(batch(advance(keys.get(0), Optional.of(a), "first")), new PointerHold(keys.get(1), b));
        CommitBatch second = holding(batch(advance(keys.get(1), Optional.of(b), "second")), new PointerHold(keys.get(0), a));
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommitStore held = underTest.store(before(first, () -> {
            atCommit.countDown();
            awaitLatch(release);
        }));
        CompletableFuture<CommitResult> firstResult = CompletableFuture.supplyAsync(() -> held.commit(first), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).as("the first batch reached COMMIT").isTrue();

        CompletableFuture<CommitResult> secondResult = CompletableFuture.supplyAsync(() -> store.commit(second), background);
        Thread.sleep(1_500);
        assertThat(secondResult).as("the second batch must wait while the first holds what it swaps").isNotDone();
        release.countDown();

        assertThat(firstResult.get(60, TimeUnit.SECONDS)).isEqualTo(new CommitResult.Committed(first.commitId()));
        assertThat(secondResult.get(60, TimeUnit.SECONDS))
                .isEqualTo(new CommitResult.Conflict(keys.get(0), Optional.of(next(first))));
        assertThat(store.readPointer(keys.get(0))).contains(next(first));
        assertThat(store.readPointer(keys.get(1))).contains(b);
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

    /**
     * The database cannot be reached, so the store answers that nothing was applied
     * ({@link StoreUnavailableException}), which the routes answer 503 with Retry-After (backlog 7).
     */
    @Tag("heavy")
    @Test
    @DisplayName("2 Atomicity: a link that is down before the commit starts is a clean failure the caller may repeat"
            + " (unavailable), not an unknown outcome")
    void linkDownBeforeCommitIsACleanFailure() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("never", 2)));
        faults.cutLink();

        assertThatThrownBy(() -> store.commit(batch)).isInstanceOf(StoreUnavailableException.class);
        assertThatThrownBy(() -> store.readPointer(key)).isInstanceOf(StoreUnavailableException.class);
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
