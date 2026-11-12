package keel.catalog.store.suite;

import static keel.catalog.store.suite.Changes.creation;
import static keel.catalog.store.suite.Changes.name;
import static keel.catalog.store.suite.Changes.now;
import static keel.catalog.store.suite.Commits.newKey;
import static keel.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CatalogObject;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitResult;
import keel.catalog.store.JournalEntry;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.ObjectCreation;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.PointerSwap;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suite test 7: projections above the size budget are stored in pieces and reassembled (D8.5);
 * large journal entries and journal entries far apart in time are written and read back. How a
 * backend partitions its journal by time is its own; this suite checks what a caller sees.
 */
public abstract class SizeSuite extends GroupSuite {

    private final Random random = new Random(7);

    private Pointer pointerOf(String name, long version, int projectionBytes) {
        byte[] projection = new byte[projectionBytes];
        random.nextBytes(projection);
        Pointer named = Commits.pointer(name, version);
        return new Pointer(named.metadataLocation(), version, named.sha256(), projection);
    }

    private CommitBatch commit(PointerKey key, Optional<Pointer> expected, Pointer next) {
        CommitBatch batch = Commits.batch(swap(key, expected, next));
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        return batch;
    }

    private int piecesFor(int bytes) {
        int budget = underTest().projectionPieceBytes();
        return Math.max(1, (bytes + budget - 1) / budget);
    }

    @Test
    @DisplayName("7 Size: a projection five budgets long is stored in pieces, reassembled by every read, and shrinks back")
    void largeProjectionIsChunkedAndReassembled() {
        int large = 5 * underTest().projectionPieceBytes() - 3;
        PointerKey key = newKey();
        Pointer first = pointerOf("large-1", 1, large);
        CommitBatch create = commit(key, Optional.empty(), first);
        Pointer stale = pointerOf("stale", 1, 10);

        assertThat(store.readPointer(key)).contains(first);
        assertThat(underTest().projectionPieces(key)).isEqualTo(5);
        assertThat(store.commit(Commits.batch(swap(key, Optional.of(stale), pointerOf("x", 2, 10)))))
                .isEqualTo(new CommitResult.Conflict(key, Optional.of(first)));
        assertThat(store.findCommit(create.commitId(), key)).isEqualTo(CommitLookup.COMMITTED);

        Pointer second = pointerOf("large-2", 2, large + 7);
        commit(key, Optional.of(first), second);
        assertThat(store.readPointer(key)).contains(second);
        Pointer small = pointerOf("small", 3, 100);
        commit(key, Optional.of(second), small);

        assertThat(store.readPointer(key)).contains(small);
        assertThat(underTest().projectionPieces(key)).as("pieces of replaced projections are gone").isEqualTo(1);
    }

    @Test
    @DisplayName("7 Size: a projection of exactly the budget is one piece; one byte more is two; empty is one")
    void pieceBoundaries() {
        int budget = underTest().projectionPieceBytes();
        for (int bytes : List.of(budget, budget + 1, 0, 1)) {
            PointerKey key = newKey();
            Pointer pointer = pointerOf("boundary-" + bytes, 1, bytes);
            commit(key, Optional.empty(), pointer);

            assertThat(store.readPointer(key)).as("%d bytes", bytes).contains(pointer);
            assertThat(underTest().projectionPieces(key)).as("%d bytes", bytes).isEqualTo(piecesFor(bytes));
        }
    }

    @Test
    @DisplayName("7 Size: an object created with a projection three budgets long reads back whole")
    void largeFirstProjectionOfACreatedObject() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(), name("wide"),
                CatalogObject.Kind.TABLE, "s3://suite-warehouse/wide");
        ObjectCreation plain = creation(table);
        Pointer big = pointerOf("wide-1", 1, 3 * underTest().projectionPieceBytes());
        PointerSwap swap = plain.firstCommit().swaps().getFirst();
        CommitBatch first = new CommitBatch(plain.firstCommit().commitId(), plain.firstCommit().committedAt(),
                List.of(new PointerSwap(swap.key(), Optional.empty(), big, swap.journal())),
                plain.firstCommit().outbox(), plain.firstCommit().idempotency());

        assertApplied(store.createObject(new ObjectCreation(table, first)));

        assertThat(store.readPointer(table.mainKey())).contains(big);
        assertThat(underTest().projectionPieces(table.mainKey())).isEqualTo(3);
    }

    @Test
    @DisplayName("7 Size: a journal entry carrying 2 MiB of updates is written and read back whole")
    void largeJournalEntry() {
        PointerKey key = newKey();
        StringBuilder updates = new StringBuilder("[");
        while (updates.length() < 2 * 1024 * 1024) {
            updates.append("{\"action\":\"add-schema\",\"field\":\"").append(UUID.randomUUID()).append("\"},");
        }
        updates.setCharAt(updates.length() - 1, ']');
        JournalEntry entry = new JournalEntry("svc-suite", null, null, "[]", updates.toString());
        Instant at = now();
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), at,
                List.of(new PointerSwap(key, Optional.empty(), Commits.pointer("wide-journal", 1), entry)), List.of(),
                Optional.empty());
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        List<JournalRecord> records = store.readJournal(key, at, at.plusMillis(1), PageRequest.first(1)).items();

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.entry()).isEqualTo(entry);
            assertThat(record.entry().updatesJson()).hasSize(updates.length());
        });
    }

    @Test
    @DisplayName("7 Size: journal entries years apart, past and future, are written and read back in one range")
    void journalEntriesFarApartInTime() {
        PointerKey key = newKey();
        Instant now = now();
        List<Instant> times = List.of(now.minus(Duration.ofDays(800)), now.minus(Duration.ofDays(40)), now,
                now.plus(Duration.ofDays(40)), now.plus(Duration.ofDays(400)));
        Optional<Pointer> current = Optional.empty();
        for (Instant at : times) {
            Pointer next = Commits.pointer("far-" + at.toEpochMilli(), current.map(Pointer::version).orElse(0L) + 1);
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, List.of(swap(key, current, next)), List.of(),
                    Optional.empty());
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            current = Optional.of(next);
        }

        List<JournalRecord> all = allPages(2, page -> store.readJournal(key, times.getFirst(), times.getLast().plusMillis(1), page));
        List<JournalRecord> recent = store.readJournal(key, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(41)),
                PageRequest.first(10)).items();

        assertThat(all).extracting(JournalRecord::committedAt).containsExactlyElementsOf(times);
        assertThat(recent).extracting(JournalRecord::newVersion).containsExactly(3L, 4L);
    }
}
