package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.keyedChange;
import static nuna.catalog.store.suite.Changes.now;
import static nuna.catalog.store.suite.Changes.result;
import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Suite test 9: idempotency keys (D10). */
public abstract class IdempotencySuite extends GroupSuite {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static String newKeyName() {
        return UUID.randomUUID().toString();
    }

    private static IdempotencyClaim claim(String key, UUID changeId, Instant now) {
        return new IdempotencyClaim(key, "svc-suite", "hash-of-" + key, changeId, now, now.plus(LEASE),
                now.plus(Duration.ofHours(24)));
    }

    /** A commit that creates a new pointer and completes {@code key} with a 200. */
    private static CommitBatch keyedCommit(String key, UUID commitId, PointerKey pointer) {
        Instant now = now();
        return new CommitBatch(commitId, now, List.of(advance(pointer, Optional.empty(), "keyed-" + commitId)), List.of(),
                Optional.of(result(key, 200, now)));
    }

    @Test
    @DisplayName("9 Idempotency: a claim holds the key; the same request sees it in flight, another request is a mismatch")
    void claimHoldsTheKey() {
        String key = newKeyName();
        UUID owner = UUID.randomUUID();
        Instant now = now();

        assertThat(store.readKey(key, now)).isEqualTo(KeyState.ABSENT);
        assertThat(store.claimKey(claim(key, owner, now))).isEqualTo(KeyState.CLAIMED);

        KeyState inFlight = new KeyState.InFlight(owner, now.plus(LEASE));
        assertThat(store.claimKey(claim(key, UUID.randomUUID(), now))).isEqualTo(inFlight);
        assertThat(store.readKey(key, now)).isEqualTo(inFlight);
        assertThat(store.claimKey(new IdempotencyClaim(key, "svc-suite", "another-request", UUID.randomUUID(), now,
                now.plus(LEASE), now.plusSeconds(60)))).isEqualTo(KeyState.MISMATCH);
        assertThat(store.claimKey(new IdempotencyClaim(key, "someone-else", "hash-of-" + key, UUID.randomUUID(), now,
                now.plus(LEASE), now.plusSeconds(60)))).isEqualTo(KeyState.MISMATCH);
    }

    @Test
    @DisplayName("9 Idempotency: of 8 concurrent claims of one key exactly one holds it")
    void exactlyOneConcurrentClaimHolds() throws Exception {
        for (int round = 0; round < 10; round++) {
            String key = newKeyName();
            Instant now = now();
            CyclicBarrier start = new CyclicBarrier(8);
            List<Future<KeyState>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(background.submit(() -> {
                    start.await();
                    return store.claimKey(claim(key, UUID.randomUUID(), now));
                }));
            }
            List<KeyState> states = new ArrayList<>();
            for (Future<KeyState> result : results) {
                states.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(states).as("round %d", round).filteredOn(KeyState.CLAIMED::equals).hasSize(1);
            assertThat(states).filteredOn(state -> !state.equals(KeyState.CLAIMED)).hasOnlyElementsOfType(KeyState.InFlight.class);
        }
    }

    @Test
    @DisplayName("9 Idempotency: a commit completes the claim it was made under, and its success is replayed")
    void commitCompletesItsClaimAndIsReplayed() {
        String key = newKeyName();
        UUID commitId = UUID.randomUUID();
        assertThat(store.claimKey(claim(key, commitId, now()))).isEqualTo(KeyState.CLAIMED);
        CommitBatch batch = keyedCommit(key, commitId, newKey());

        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(commitId));

        KeyState done = new KeyState.Done(batch.idempotency().orElseThrow());
        assertThat(store.claimKey(claim(key, UUID.randomUUID(), now()))).isEqualTo(done);
        assertThat(store.readKey(key, now())).isEqualTo(done);
        assertThat(store.awaitKey(key, Duration.ofSeconds(1))).isEqualTo(done);
    }

    @Test
    @DisplayName("9 Idempotency: a change completes the claim it was made under, and its answer is replayed")
    void changeCompletesItsClaimAndIsReplayed() {
        String key = newKeyName();
        UUID changeId = UUID.randomUUID();
        Instant now = now();
        assertThat(store.claimKey(claim(key, changeId, now))).isEqualTo(KeyState.CLAIMED);
        UUID catalog = newCatalog();

        var change = keyedChange(key, changeId, now);
        assertThat(store.createNamespace(new NamespaceRecord(catalog, UUID.randomUUID(), catalog,
                Changes.name("keyed"), Map.of(), 1), change)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.claimKey(claim(key, UUID.randomUUID(), now()))).isEqualTo(new KeyState.Done(change.idempotency().orElseThrow()));
    }

    @Test
    @DisplayName("9 Idempotency: a definite 4xx is completed and replayed; a 5xx releases the key and is never replayed")
    void fourHundredsReplayAndFiveHundredsDoNot() {
        String rejected = newKeyName();
        UUID first = UUID.randomUUID();
        assertThat(store.claimKey(claim(rejected, first, now()))).isEqualTo(KeyState.CLAIMED);
        IdempotencyResult conflict = result(rejected, 409, now());
        assertThat(store.completeKey(conflict, first)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.claimKey(claim(rejected, UUID.randomUUID(), now()))).isEqualTo(new KeyState.Done(conflict));

        String failed = newKeyName();
        UUID second = UUID.randomUUID();
        assertThat(store.claimKey(claim(failed, second, now()))).isEqualTo(KeyState.CLAIMED);
        assertThat(store.releaseKey(failed, second)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.readKey(failed, now())).isEqualTo(KeyState.ABSENT);
        assertThat(store.claimKey(claim(failed, UUID.randomUUID(), now()))).as("the retry runs again").isEqualTo(KeyState.CLAIMED);
    }

    @Test
    @DisplayName("9 Idempotency: only the claimant completes or releases a key; a commit under another id fails and writes nothing")
    void onlyTheClaimantCompletes() {
        String key = newKeyName();
        UUID owner = UUID.randomUUID();
        assertThat(store.claimKey(claim(key, owner, now()))).isEqualTo(KeyState.CLAIMED);
        UUID intruder = UUID.randomUUID();
        PointerKey pointer = newKey();
        CommitBatch foreign = keyedCommit(key, intruder, pointer);

        assertThatThrownBy(() -> store.commit(foreign)).isInstanceOf(StoreException.class);
        assertThat(underTest().writtenRows(foreign)).as("the claim row only").isEqualTo(new WrittenRows(0, 0, 0, 1));
        assertThat(store.readPointer(pointer)).isEmpty();
        assertThat(store.completeKey(result(key, 400, now()), intruder)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        assertThat(store.releaseKey(key, intruder)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        assertThat(store.completeKey(result(newKeyName(), 400, now()), owner)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.KEY));
        assertThat(store.readKey(key, now())).isInstanceOfSatisfying(KeyState.InFlight.class,
                held -> assertThat(held.changeId()).isEqualTo(owner));
    }

    @Test
    @DisplayName("9 Idempotency: a lapsed claim is taken over, and the first claimant can no longer complete it")
    void lapsedClaimIsTakenOver() {
        String key = newKeyName();
        UUID first = UUID.randomUUID();
        Instant start = now();
        assertThat(store.claimKey(claim(key, first, start))).isEqualTo(KeyState.CLAIMED);
        UUID second = UUID.randomUUID();
        Instant later = start.plus(LEASE).plusSeconds(1);

        assertThat(store.claimKey(claim(key, second, later))).isEqualTo(KeyState.CLAIMED);

        assertThat(store.readKey(key, later)).isEqualTo(new KeyState.InFlight(second, later.plus(LEASE)));
        CommitBatch late = keyedCommit(key, first, newKey());
        assertThatThrownBy(() -> store.commit(late)).isInstanceOf(StoreException.class);
        assertThat(store.completeKey(result(key, 409, later), first)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        CommitBatch winner = keyedCommit(key, second, newKey());
        assertThat(store.commit(winner)).isEqualTo(new CommitResult.Committed(second));
        assertThat(store.readKey(key, later)).isEqualTo(new KeyState.Done(winner.idempotency().orElseThrow()));
    }

    @Test
    @DisplayName("9 Idempotency: a waiter sees the holder's answer as soon as it is done, and gives up at its limit")
    void awaitSeesTheAnswer() throws Exception {
        String key = newKeyName();
        UUID owner = UUID.randomUUID();
        assertThat(store.claimKey(claim(key, owner, now()))).isEqualTo(KeyState.CLAIMED);

        long started = System.nanoTime();
        assertThat(store.awaitKey(key, Duration.ofMillis(300))).isInstanceOf(KeyState.InFlight.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("gives up at its limit, not at the lease")
                .isLessThan(Duration.ofSeconds(10));
        CompletableFuture<KeyState> waiter = CompletableFuture.supplyAsync(() -> store.awaitKey(key, Duration.ofSeconds(30)),
                background);
        Thread.sleep(1_000);
        assertThat(waiter).isNotDone();
        CommitBatch batch = keyedCommit(key, owner, newKey());
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(owner));

        assertThat(waiter.get(30, TimeUnit.SECONDS)).isEqualTo(new KeyState.Done(batch.idempotency().orElseThrow()));
    }

    @Test
    @DisplayName("9 Idempotency: an expired answer is never replayed, and expiry deletes only expired records")
    void expiredAnswersAreNotReplayed() {
        String old = newKeyName();
        Instant past = now().minus(Duration.ofDays(2));
        UUID first = UUID.randomUUID();
        assertThat(store.claimKey(new IdempotencyClaim(old, "svc-suite", "hash-of-" + old, first, past,
                past.plus(LEASE), past.plus(Duration.ofHours(24))))).isEqualTo(KeyState.CLAIMED);
        assertThat(store.completeKey(result(old, 200, past), first)).isEqualTo(WriteResult.APPLIED);
        String fresh = newKeyName();
        assertThat(store.claimKey(claim(fresh, UUID.randomUUID(), now()))).isEqualTo(KeyState.CLAIMED);

        assertThat(store.readKey(old, now())).isEqualTo(KeyState.ABSENT);
        assertThat(store.expireKeys(now(), 100_000)).isGreaterThanOrEqualTo(1);
        assertThat(store.readKey(old, past.plusSeconds(1))).as("deleted, not just hidden").isEqualTo(KeyState.ABSENT);
        assertThat(store.readKey(fresh, now())).isInstanceOf(KeyState.InFlight.class);
        assertThat(store.claimKey(claim(old, UUID.randomUUID(), now()))).isEqualTo(KeyState.CLAIMED);
    }

    @Test
    @DisplayName("9 Idempotency: an expired record left undeleted is claimed afresh, not replayed")
    void expiredUndeletedRecordIsClaimedAfresh() {
        String key = newKeyName();
        Instant past = now().minus(Duration.ofDays(2));
        UUID first = UUID.randomUUID();
        assertThat(store.claimKey(new IdempotencyClaim(key, "svc-suite", "hash-of-" + key, first, past,
                past.plus(LEASE), past.plus(Duration.ofHours(24))))).isEqualTo(KeyState.CLAIMED);
        assertThat(store.completeKey(result(key, 200, past), first)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.claimKey(new IdempotencyClaim(key, "other-principal", "other-hash", UUID.randomUUID(), now(),
                now().plus(LEASE), now().plusSeconds(60)))).isEqualTo(KeyState.CLAIMED);
    }

    @Test
    @DisplayName("9 Idempotency: a commit over another request's expired claim or answer records its own answer")
    void commitOverAnExpiredRecordRecordsItsOwnAnswer() {
        Instant past = now().minus(Duration.ofDays(2));
        String abandoned = newKeyName();
        assertThat(store.claimKey(new IdempotencyClaim(abandoned, "svc-suite", "hash-of-" + abandoned, UUID.randomUUID(),
                past, past.plus(LEASE), past.plus(Duration.ofHours(24))))).isEqualTo(KeyState.CLAIMED);
        String answered = newKeyName();
        UUID first = UUID.randomUUID();
        assertThat(store.claimKey(new IdempotencyClaim(answered, "svc-suite", "hash-of-" + answered, first,
                past, past.plus(LEASE), past.plus(Duration.ofHours(24))))).isEqualTo(KeyState.CLAIMED);
        assertThat(store.completeKey(result(answered, 200, past), first)).isEqualTo(WriteResult.APPLIED);

        for (String key : List.of(abandoned, answered)) {
            CommitBatch batch = keyedCommit(key, UUID.randomUUID(), newKey());

            assertThat(store.commit(batch)).as(key.equals(abandoned) ? "over a claim" : "over an answer")
                    .isEqualTo(new CommitResult.Committed(batch.commitId()));

            assertThat(store.readKey(key, now())).isEqualTo(new KeyState.Done(batch.idempotency().orElseThrow()));
        }
    }

    @Test
    @DisplayName("9 Idempotency: 8 concurrent requests with one key make exactly one commit and all get its answer")
    void concurrentSameKeyRequestsCommitOnce() throws Exception {
        String key = newKeyName();
        PointerKey pointer = newKey();
        CyclicBarrier start = new CyclicBarrier(8);
        List<Future<KeyState>> answers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            answers.add(background.submit(() -> {
                UUID commitId = UUID.randomUUID();
                start.await();
                KeyState state = store.claimKey(claim(key, commitId, now()));
                if (state.equals(KeyState.CLAIMED)) {
                    CommitBatch batch = keyedCommit(key, commitId, pointer);
                    assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(commitId));
                    return new KeyState.Done(batch.idempotency().orElseThrow());
                }
                return store.awaitKey(key, Duration.ofSeconds(30));
            }));
        }
        List<KeyState> states = new ArrayList<>();
        for (Future<KeyState> answer : answers) {
            states.add(answer.get(60, TimeUnit.SECONDS));
        }

        assertThat(states).hasSize(8).allSatisfy(state -> assertThat(state).isEqualTo(states.getFirst()));
        assertThat(underTest().journalVersions(pointer)).containsExactly(1L);
        Pointer stored = store.readPointer(pointer).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("9 Idempotency: a claim that finds a commit still writing the key waits for it, then replays it")
    void claimWaitsForAnOpenCommitOfTheKey() throws Exception {
        String key = newKeyName();
        UUID owner = UUID.randomUUID();
        assertThat(store.claimKey(claim(key, owner, now()))).isEqualTo(KeyState.CLAIMED);
        CommitBatch batch = keyedCommit(key, owner, newKey());
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var held = underTest().store(Commits.before(batch, () -> {
            atCommit.countDown();
            CommitSuite.awaitLatch(release);
        }));
        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> held.commit(batch), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<KeyState> retry = CompletableFuture.supplyAsync(
                () -> store.claimKey(claim(key, UUID.randomUUID(), now().plus(LEASE).plusSeconds(1))), background);
        Thread.sleep(1_500);
        assertThat(retry).as("a lapsed-looking claim must not be taken over while its commit is open").isNotDone();
        release.countDown();

        assertThat(commit.get(60, TimeUnit.SECONDS)).isEqualTo(new CommitResult.Committed(owner));
        assertThat(retry.get(60, TimeUnit.SECONDS)).isEqualTo(new KeyState.Done(batch.idempotency().orElseThrow()));
    }
}
