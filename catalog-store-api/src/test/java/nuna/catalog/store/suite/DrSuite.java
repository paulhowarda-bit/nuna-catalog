package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.WriteResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DR replication watermark (D37, amended 2026-11-20): kept per active table and view, it only rises and never
 * passes the main pointer, a DR rewind's commit records it at the version it writes (item 100), and the objects behind
 * it are listed by id. The listing is shared by every test on the backend, so each test looks only at its own objects.
 */
public abstract class DrSuite extends GroupSuite {

    private static final WriteResult.Missing MISSING = new WriteResult.Missing(WriteResult.Subject.OBJECT);
    private static final WriteResult BEHIND = new WriteResult.Refused(WriteResult.Reason.WATERMARK_BEHIND);
    private static final WriteResult AHEAD = new WriteResult.Refused(WriteResult.Reason.WATERMARK_AHEAD);

    /**
     * Commits {@code versions} versions of the object's main pointer; returns the last. Creating an object commits
     * its version 1 ({@link Changes#creation}), so the first of these is version 2.
     */
    private Pointer commit(CatalogObject object, int versions) {
        PointerKey key = new PointerKey(object.id(), PointerKey.MAIN);
        Optional<Pointer> current = store.readPointer(key);
        for (int i = 0; i < versions; i++) {
            var batch = Commits.batch(Commits.advance(key, current, object.id() + "-" + UUID.randomUUID()));
            assertThat(store.commit(batch)).isInstanceOf(CommitResult.Committed.class);
            current = Optional.of(Commits.next(batch));
        }
        return current.orElseThrow();
    }

    /** One time for every watermark a test records, so a watermark read back equals the one recorded. */
    private final java.time.Instant recorded = now();

    private DrWatermark at(Pointer pointer) {
        return new DrWatermark(pointer.version(), pointer.metadataLocation(), recorded);
    }

    private List<DrState> behind(CatalogObject... objects) {
        List<UUID> ids = java.util.Arrays.stream(objects).map(CatalogObject::id).toList();
        return allPages(3, store::listDrBehind).stream().filter(state -> ids.contains(state.object().id())).toList();
    }

    @Test
    @DisplayName("DR: a watermark is kept, rises, never moves back, and never passes the pointer")
    void aWatermarkOnlyRises() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer v2 = commit(table, 1);
        Pointer v3 = commit(table, 1);
        DrWatermark low = at(v2);
        DrWatermark high = at(v3);
        assertThat(store.readDrWatermark(table.id())).as("none yet").isEmpty();

        assertApplied(store.recordDrWatermark(table.id(), low));

        assertThat(store.readDrWatermark(table.id())).contains(low);
        assertThat(store.recordDrWatermark(table.id(), low)).as("the same again").isEqualTo(WriteResult.UNCHANGED);
        assertApplied(store.recordDrWatermark(table.id(), high));
        assertThat(store.readDrWatermark(table.id())).contains(high);
        assertThat(store.recordDrWatermark(table.id(), low)).as("a lower one, reported late").isEqualTo(BEHIND);
        assertThat(store.recordDrWatermark(table.id(), new DrWatermark(4, "s3://suite-warehouse/ahead.json", recorded)))
                .as("one above the pointer").isEqualTo(AHEAD);
        assertThat(store.readDrWatermark(table.id())).contains(high);
    }

    @Test
    @DisplayName("DR: a swap that records the watermark, a DR rewind's, records it at the version it writes in its own"
            + " commit, however many versions it gives up, so the object is no longer behind; one that conflicts"
            + " records nothing, and a later commit leaves it")
    void aRewindsCommitRecordsTheWatermark() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        PointerKey key = new PointerKey(table.id(), PointerKey.MAIN);
        Pointer v1 = store.readPointer(key).orElseThrow();
        Pointer v102 = commit(table, 101);
        assertApplied(store.recordDrWatermark(table.id(), at(v1)));
        PointerSwap stale = recording(Commits.advance(key, Optional.of(v1), table.id() + "-stale"));
        PointerSwap rewind = recording(Commits.advance(key, Optional.of(v102), table.id() + "-rewind"));

        assertThat(store.commit(new CommitBatch(UUID.randomUUID(), recorded, List.of(stale), List.of(),
                Optional.empty()))).isInstanceOf(CommitResult.Conflict.class);
        assertThat(store.readDrWatermark(table.id())).as("after a conflict").contains(at(v1));
        assertThat(store.commit(new CommitBatch(UUID.randomUUID(), recorded, List.of(rewind), List.of(),
                Optional.empty()))).isInstanceOf(CommitResult.Committed.class);

        DrWatermark rewound = at(rewind.next());
        assertThat(rewound.version()).isEqualTo(103);
        assertThat(store.readDrWatermark(table.id())).contains(rewound);
        assertThat(behind(table)).as("not behind, so the verifier's work list leaves it out").isEmpty();
        Pointer v104 = commit(table, 1);
        assertThat(store.readDrWatermark(table.id())).as("after a plain commit").contains(rewound);
        assertThat(behind(table)).containsExactly(new DrState(table, 104, v104.metadataLocation(),
                Optional.of(rewound)));
    }

    /** {@code swap}, recording its next pointer as the object's DR watermark, as a DR rewind's does. */
    private static PointerSwap recording(PointerSwap swap) {
        return new PointerSwap(swap.key(), swap.expected(), swap.next(), swap.journal(), true);
    }

    @Test
    @DisplayName("DR: a view keeps a watermark; a dropped table or an unknown id is Missing")
    void onlyActiveTablesAndViewsKeepOne() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        CatalogObject dropped = newTable(namespace, "dropped");
        Pointer viewAt = commit(view, 1);
        Pointer droppedAt = commit(dropped, 1);
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));

        assertApplied(store.recordDrWatermark(view.id(), at(viewAt)));

        assertThat(store.readDrWatermark(view.id())).contains(at(viewAt));
        for (UUID id : List.of(dropped.id(), UUID.randomUUID())) {
            assertThat(store.recordDrWatermark(id, at(droppedAt))).as("watermark of %s", id).isEqualTo(MISSING);
            assertThat(store.readDrWatermark(id)).as("watermark of %s", id).isEmpty();
        }
    }

    @Test
    @DisplayName("DR: the tables and views behind are listed by id, with pointer and watermark; those at their"
            + " watermark or dropped are left out, and pages resume after the last")
    void theObjectsBehindAreListed() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject never = newTable(namespace, "never");
        CatalogObject lagging = newTable(namespace, "lagging");
        CatalogObject level = newTable(namespace, "level");
        CatalogObject view = newObject(namespace, "view", CatalogObject.Kind.VIEW);
        CatalogObject dropped = newTable(namespace, "dropped");
        Pointer neverAt = commit(never, 1);
        Pointer laggingLow = store.readPointer(lagging.mainKey()).orElseThrow();
        Pointer laggingAt = commit(lagging, 2);
        Pointer levelAt = commit(level, 3);
        Pointer viewAt = store.readPointer(view.mainKey()).orElseThrow();
        commit(dropped, 1);
        assertApplied(store.recordDrWatermark(lagging.id(), at(laggingLow)));
        assertApplied(store.recordDrWatermark(level.id(), at(levelAt)));
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));

        List<DrState> listed = behind(never, lagging, level, view, dropped);

        assertThat(listed).extracting(state -> state.object().id()).containsExactlyElementsOf(
                java.util.stream.Stream.of(never, lagging, view).map(CatalogObject::id)
                        .sorted(HealthSuiteOrder.UNSIGNED).toList());
        assertThat(listed).contains(new DrState(never, 2, neverAt.metadataLocation(), Optional.empty()),
                new DrState(lagging, 3, laggingAt.metadataLocation(), Optional.of(at(laggingLow))),
                new DrState(view, 1, viewAt.metadataLocation(), Optional.empty()));
        assertThat(listed).extracting(DrState::behind).containsExactlyInAnyOrder(2L, 2L, 1L);
    }

    @Test
    @DisplayName("DR: completing a purge deletes the watermark")
    void aPurgeDeletesTheWatermark() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        assertApplied(store.recordDrWatermark(table.id(), at(commit(table, 1))));
        assertApplied(store.dropObject(table.id(), now(), change()));
        assertApplied(store.claimPurge(table.id(), "worker", change()));
        assertThat(store.readDrWatermark(table.id())).as("kept while the table is purged").isPresent();

        assertApplied(store.completePurge(table.id(), "worker", change()));

        assertThat(store.readDrWatermark(table.id())).isEmpty();
    }

    @Test
    @DisplayName("DR: a journal page can start after any version, as the verifier's work list reads it")
    void theJournalReadsAfterAVersion() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        commit(table, 4);
        PointerKey key = new PointerKey(table.id(), PointerKey.MAIN);

        List<JournalRecord> after2 = store.readJournal(key, JournalStore.EARLIEST, JournalStore.LATEST,
                JournalStore.afterVersion(2, 2)).items();

        assertThat(after2).extracting(JournalRecord::newVersion).containsExactly(3L, 4L);
        assertThat(store.readJournal(key, JournalStore.EARLIEST, JournalStore.LATEST, JournalStore.afterVersion(0, 1))
                .items()).extracting(JournalRecord::newVersion).containsExactly(1L);
        assertThat(store.readJournal(key, JournalStore.EARLIEST, JournalStore.LATEST, JournalStore.afterVersion(5, 1))
                .items()).isEmpty();
    }

    /** Ids as the stores order them: an unsigned 128-bit number. */
    private static final class HealthSuiteOrder {
        static final java.util.Comparator<UUID> UNSIGNED = java.util.Comparator
                .<UUID>comparingLong(id -> id.getMostSignificantBits() ^ Long.MIN_VALUE)
                .thenComparingLong(id -> id.getLeastSignificantBits() ^ Long.MIN_VALUE);
    }
}
