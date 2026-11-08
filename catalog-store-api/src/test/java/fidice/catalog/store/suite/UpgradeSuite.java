package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.CommitStore;
import fidice.catalog.store.JournalRecord;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suite test 10: the schema migrates from the previous release's version to the current one while
 * nodes of the previous release keep committing (D8.7).
 */
public abstract class UpgradeSuite extends GroupSuite {

    private static final int WRITERS = 4;

    /** A node of one release committing successive versions of its own pointer until stopped. */
    private final class Writer {
        final PointerKey key = newKey();
        final AtomicInteger commits = new AtomicInteger();
        Optional<Pointer> current = Optional.empty();

        void commitOnce(CommitStore node) {
            CommitBatch batch = Commits.batch(advance(key, current, "upgrade-" + key.objectId() + "-" + commits.get()));
            assertThat(node.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            current = Optional.of(Commits.next(batch));
            commits.incrementAndGet();
        }
    }

    @Test
    @DisplayName("10 Upgrade: nodes of the previous release keep committing while two nodes migrate, and both"
            + " releases read and resolve each other's commits")
    void migrateWhileThePreviousReleaseServes() throws Exception {
        CatalogStoreUnderTest.UpgradeTarget target = underTest().upgradeTarget();
        CommitStore previous = target.previousRelease();
        CatalogStore current = target.currentRelease();
        assertThat(current.schemaVersion().version()).isEqualTo(target.previousVersion());
        List<Writer> writers = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> running = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            Writer writer = new Writer();
            writers.add(writer);
            running.add(CompletableFuture.runAsync(() -> {
                try {
                    while (!stop.get()) {
                        writer.commitOnce(previous);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, background));
        }
        awaitCommits(writers, 3, failures);

        CompletableFuture<Void> firstNode = CompletableFuture.runAsync(current::migrate, background);
        CompletableFuture<Void> secondNode = CompletableFuture.runAsync(current::migrate, background);
        firstNode.get(300, TimeUnit.SECONDS);
        secondNode.get(300, TimeUnit.SECONDS);
        List<Integer> atMigration = writers.stream().map(writer -> writer.commits.get()).toList();
        awaitCommits(writers, atMigration.stream().mapToInt(Integer::intValue).max().orElseThrow() + 3, failures);
        stop.set(true);
        for (CompletableFuture<Void> writer : running) {
            writer.get(60, TimeUnit.SECONDS);
        }

        assertThat(failures).as("previous-release commits during and after the migration").isEmpty();
        assertThat(current.schemaVersion().version()).isGreaterThan(target.previousVersion());
        for (Writer writer : writers) {
            assertThat(current.readPointer(writer.key)).isEqualTo(writer.current);
            List<JournalRecord> journal = allPages(50, page -> current.readJournal(writer.key, Instant.EPOCH,
                    Instant.now().plus(Duration.ofDays(1)), page));
            assertThat(journal).extracting(JournalRecord::newVersion).as("one journal entry per previous-release commit")
                    .containsExactlyElementsOf(versions(writer.commits.get()));
        }
        Writer first = writers.getFirst();
        first.commitOnce(current);
        assertThat(previous.readPointer(first.key)).isEqualTo(first.current);
        CommitBatch byPrevious = Commits.batch(advance(first.key, first.current, "previous-after"));
        assertThat(previous.commit(byPrevious)).isEqualTo(new CommitResult.Committed(byPrevious.commitId()));
        assertThat(current.readPointer(first.key)).contains(Commits.next(byPrevious));
        assertThat(current.findCommit(byPrevious.commitId(), first.key)).isEqualTo(CommitLookup.COMMITTED);
        CommitBatch byCurrent = Commits.batch(advance(first.key, Optional.of(Commits.next(byPrevious)), "current-after"));
        assertThat(current.commit(byCurrent)).isEqualTo(new CommitResult.Committed(byCurrent.commitId()));
        assertThat(previous.findCommit(byCurrent.commitId(), first.key)).isEqualTo(CommitLookup.COMMITTED);
        int migrated = current.schemaVersion().version();
        current.migrate();
        assertThat(current.schemaVersion().version()).as("migrating again changes nothing").isEqualTo(migrated);
    }

    private static List<Long> versions(int count) {
        List<Long> versions = new ArrayList<>();
        for (long version = 1; version <= count; version++) {
            versions.add(version);
        }
        return versions;
    }

    /** Waits until every writer has made {@code each} commits, or one has failed. */
    private static void awaitCommits(List<Writer> writers, int each, ConcurrentLinkedQueue<Throwable> failures)
            throws InterruptedException {
        CommitSuite.awaitTrue(Duration.ofSeconds(120), () -> !failures.isEmpty()
                        || writers.stream().allMatch(writer -> writer.commits.get() >= each),
                "every previous-release writer to reach " + each + " commits");
    }
}
