package keel.catalog.store.suite;

import static keel.catalog.store.suite.Changes.now;
import static keel.catalog.store.suite.Commits.advance;
import static keel.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitResult;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.PointerSwap;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The journal range read (D5). */
public abstract class JournalSuite extends GroupSuite {

    /** Commits one swap of {@code key} at each of {@code times}, in order; returns the batches. */
    protected List<CommitBatch> commitAt(PointerKey key, List<Instant> times) {
        List<CommitBatch> batches = new ArrayList<>();
        Optional<Pointer> current = store.readPointer(key);
        for (Instant at : times) {
            PointerSwap swap = advance(key, current, "journal-" + UUID.randomUUID());
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, List.of(swap), List.of(), Optional.empty());
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            batches.add(batch);
            current = Optional.of(swap.next());
        }
        return batches;
    }

    private static JournalRecord recordOf(CommitBatch batch) {
        PointerSwap swap = batch.swaps().getFirst();
        return new JournalRecord(batch.commitId(), swap.key(), batch.committedAt(), swap.journal(),
                swap.expected().map(Pointer::metadataLocation), swap.next().metadataLocation(), swap.next().version());
    }

    @Test
    @DisplayName("Journal: each swap's record holds its commit, time, entry and locations, in version order")
    void recordsHoldEverySwap() {
        PointerKey key = newKey();
        Instant start = now();
        List<CommitBatch> batches = commitAt(key, List.of(start, start.plusMillis(1), start.plusMillis(2)));

        List<JournalRecord> records = store.readJournal(key, start, start.plusSeconds(1),
                PageRequest.first(10)).items();

        assertThat(records).containsExactly(recordOf(batches.get(0)), recordOf(batches.get(1)), recordOf(batches.get(2)));
        assertThat(records.getFirst().oldLocation()).isEmpty();
    }

    @Test
    @DisplayName("Journal: a range is from inclusive to exclusive, and pages through every record once")
    void rangeIsHalfOpenAndPaged() {
        PointerKey key = newKey();
        Instant start = now();
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            times.add(start.plus(Duration.ofMinutes(i)));
        }
        List<CommitBatch> batches = commitAt(key, times);

        List<JournalRecord> middle = allPages(4, page -> store.readJournal(key, times.get(2), times.get(9), page));
        List<JournalRecord> all = allPages(5, page -> store.readJournal(key, start, start.plus(Duration.ofDays(1)), page));

        assertThat(middle).extracting(JournalRecord::commitId)
                .containsExactlyElementsOf(batches.subList(2, 9).stream().map(CommitBatch::commitId).toList());
        assertThat(all).extracting(JournalRecord::newVersion).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        assertThat(store.readJournal(key, start.minusSeconds(60), start, PageRequest.first(5)).items())
                .isEmpty();
    }

    @Test
    @DisplayName("Journal: a read names one branch of one object; other branches and objects stay out")
    void readIsPerPointer() {
        UUID object = UUID.randomUUID();
        PointerKey main = new PointerKey(object, PointerKey.MAIN);
        PointerKey audit = new PointerKey(object, "audit");
        Instant start = now();
        List<CommitBatch> onMain = commitAt(main, List.of(start, start.plusMillis(5)));
        commitAt(audit, List.of(start.plusMillis(1)));
        commitAt(newKey(), List.of(start.plusMillis(2)));

        assertThat(store.readJournal(main, start, start.plusSeconds(1), PageRequest.first(10)).items())
                .extracting(JournalRecord::commitId).containsExactly(onMain.get(0).commitId(), onMain.get(1).commitId());
        assertThat(store.readJournal(audit, start, start.plusSeconds(1), PageRequest.first(10)).items())
                .extracting(JournalRecord::key).containsExactly(audit);
    }
}
