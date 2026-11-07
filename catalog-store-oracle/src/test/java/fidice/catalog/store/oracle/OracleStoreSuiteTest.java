package fidice.catalog.store.oracle;

import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.batch;
import static fidice.catalog.store.suite.Commits.before;
import static fidice.catalog.store.suite.Commits.fullBatch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.SafetyFinding;
import fidice.catalog.store.suite.StoreSuite;
import fidice.catalog.store.suite.StoreUnderTest;
import fidice.catalog.store.suite.WrittenRows;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared suite against Oracle AI Database Free with Transaction Guard, plus Oracle-only hazards. */
class OracleStoreSuiteTest extends StoreSuite {

    /** Shorter than the link's read timeout: the client still gets its answer. */
    private static final Duration COMMIT_DELAY = Duration.ofSeconds(3);
    /** Longer than the link's read timeout: the client gives up while the COMMIT is still on its way. */
    private static final Duration LATE_COMMIT_DELAY = Duration.ofSeconds(OracleFree.READ_TIMEOUT_SECONDS + 3);

    private OracleFree oracle;

    @Override
    protected StoreUnderTest storeUnderTest() {
        oracle = new OracleFree();
        return oracle;
    }

    @Test
    @DisplayName("Oracle: the catalog service has COMMIT_OUTCOME, so the only finding on Free is the missing Data Guard")
    void transactionGuardIsNotReportedWhereItIsOn() {
        assertThat(store().selfCheck()).extracting(SafetyFinding::code).containsExactly("ora.no-data-guard");
    }

    @Test
    @DisplayName("Oracle: Transaction Guard answers NOT_COMMITTED for a COMMIT still on its way, and that COMMIT"
            + " then cannot land")
    void transactionGuardBlocksACommitStillOnItsWay() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = batch(swap(key, Optional.of(base), pointer("too-late", 2)));
        long[] sentAt = new long[1];

        // The link holds the COMMIT longer than the client waits for its answer.
        assertOutcomeUnknown(oracle.store(before(batch, () -> {
            oracle.delayRequests(LATE_COMMIT_DELAY);
            sentAt[0] = System.nanoTime();
        })), batch);
        CatalogStore sameProcess = oracle.storeAroundTheLink();
        assertThat(sameProcess.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.NOT_COMMITTED);

        Sql.pause(Duration.ofNanos(sentAt[0] + LATE_COMMIT_DELAY.plusSeconds(3).toNanos() - System.nanoTime()));
        assertThat(sameProcess.readPointer(key)).as("the held COMMIT reached the database and was refused")
                .contains(base);
        assertThat(oracle.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Oracle: without the commit's transaction id, findCommit waits for an update whose COMMIT is on its way")
    void anotherProcessWaitsForAnUpdateOnItsWay() throws Exception {
        PointerKey key = newKey();
        assertAnotherProcessWaitsForCommitOnItsWay(key, Optional.of(commitNew(key, "base")));
    }

    @Test
    @DisplayName("Oracle: without the commit's transaction id, findCommit waits for a create whose COMMIT is on its way")
    void anotherProcessWaitsForACreateOnItsWay() throws Exception {
        assertAnotherProcessWaitsForCommitOnItsWay(newKey(), Optional.empty());
    }

    private void assertAnotherProcessWaitsForCommitOnItsWay(PointerKey key, Optional<Pointer> base) throws Exception {
        CommitBatch batch = batch(advance(key, base, "on-its-way"));
        CountDownLatch held = new CountDownLatch(1);
        CatalogStore delaying = oracle.store(before(batch, () -> {
            oracle.delayRequests(COMMIT_DELAY);
            held.countDown();
        }));
        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> delaying.commit(batch));
        assertThat(held.await(30, SECONDS)).isTrue();

        CompletableFuture<CommitLookup> lookup = CompletableFuture.supplyAsync(
                () -> oracle.storeOfAnotherProcess().findCommit(batch.commitId(), key));
        Thread.sleep(1_000);
        assertThat(lookup).as("findCommit must wait while the COMMIT is on its way").isNotDone();

        assertThat(commit.get(60, SECONDS)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(lookup.get(60, SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("Oracle: a commit shows before its redo is written, and findCommit without its transaction id"
            + " answers COMMITTED only once the redo is written")
    void anotherProcessAnswersCommittedOnlyOnceTheRedoIsWritten() throws Exception {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = batch(swap(key, Optional.of(base), pointer("redo-unwritten", 2)));
        oracle.stallReplication();

        assertOutcomeUnknown(store(), batch);
        CatalogStore anotherProcess = oracle.storeOfAnotherProcess();
        // The hazard is real: other sessions see the commit though its redo is not written.
        assertThat(anotherProcess.readPointer(key)).contains(next(batch));

        CompletableFuture<CommitLookup> lookup =
                CompletableFuture.supplyAsync(() -> anotherProcess.findCommit(batch.commitId(), key));
        Thread.sleep(1_500);
        assertThat(lookup).as("COMMITTED must wait until the redo is written").isNotDone();
        oracle.resumeReplication();

        assertThat(lookup.get(60, SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("Oracle: an empty idempotency body (a 204 answer) commits, though Oracle stores it as NULL")
    void emptyIdempotencyBodyCommits() {
        PointerKey key = newKey();
        CommitBatch full = fullBatch(advance(key, Optional.empty(), "no-content"));
        IdempotencyResult result = full.idempotency().orElseThrow();
        CommitBatch batch = new CommitBatch(full.commitId(), full.committedAt(), full.swaps(), full.outbox(),
                Optional.of(new IdempotencyResult(result.key(), result.principal(), result.requestHash(), 204, "",
                        result.expiresAt())));

        assertThat(store().commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(oracle.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
    }
}
