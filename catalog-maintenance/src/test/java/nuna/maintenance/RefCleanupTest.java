package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.RefLock;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cleanup under the catalog's refs (D33, P11, item 108): a branch or tag keeps every file its metadata reaches for a
 * table, through expiry and the orphan scan on main, and once it is gone the next orphan scan queues them; a tag made
 * after the worker read the work list still keeps its files (the catalog refuses the record and the worker reads
 * again), and so does one made after a file was recorded (the recheck before deletion); a merged branch's files are
 * main's; maintenance stays on main; the worker warns of a branch once before its expiry and ends it after; and
 * deleting a tag of a held or frozen table deletes nothing while the hold or freeze stands (security finding 10).
 *
 * <p>A commit on a catalog branch and a merge are made through the server's store ({@link TestCatalog#store}), as items
 * 105 and 107 will through routes: a branch's own head is a metadata file main once had, and main is put back.
 */
@Tag("heavy")
class RefCleanupTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> EXPIRY = Map.of("snapshot-expiry.enabled", "true",
            "snapshot-expiry.max-snapshot-age", "P5D", "snapshot-expiry.min-snapshots-to-keep", "1");
    private static final Map<String, String> CLEANUP = Map.of("snapshot-expiry.enabled", "true",
            "snapshot-expiry.max-snapshot-age", "P5D", "snapshot-expiry.min-snapshots-to-keep", "1",
            "orphan-removal.enabled", "true", "orphan-removal.older-than", "P8D",
            // A job a hold does not stop, so a held table stays on the work list; it never has enough to do.
            "manifest-rewrite.enabled", "true", "manifest-rewrite.min-manifests", "1000");

    private final TestCatalog catalog = TestCatalog.get();
    private final FakeClock clock = new FakeClock();
    /** The refs this test made, by name, deleted after it unless gone. */
    private final Map<String, UUID> refs = new LinkedHashMap<>();
    private RESTCatalog writer;
    private Worker worker;

    @BeforeEach
    void start() {
        writer = catalog.writer();
        worker = catalog.worker(clock, Compactor.NONE);
    }

    @AfterEach
    void stop() throws Exception {
        // A ref's cut names every table of the catalog: one left behind would keep other tests' files.
        refs.keySet().forEach(name -> catalog.manage(catalog.writerTokens(), "DELETE", "/refs/" + name, null));
        catalog.quiet();
        worker.close();
        writer.close();
    }

    /**
     * A branch's own files, which main no longer reaches, survive expiry and the orphan scan past the orphan age while
     * the branch lives, and its head is left as it was; once it is deleted, the next orphan scan queues them.
     */
    @Test
    void aBranchsFilesSurviveCleanupUntilItIsDeleted() {
        TableIdentifier id = TestCatalog.table(writer, "branched");
        Table table = writer.loadTable(id);
        UUID object = UUID.fromString(table.uuid().toString());
        table.newAppend().appendFile(TestCatalog.dataFile(table, "a")).commit();
        String branch = ref("dev", "branch");
        DataFile b = TestCatalog.dataFile(table, "b");
        table.newAppend().appendFile(b).commit();
        Snapshot second = table.currentSnapshot();
        Pointer head = commitOnBranch(object, branch, mainOf(object));
        table.newOverwrite().deleteFile(b).addFile(TestCatalog.dataFile(table, "c")).commit();
        catalog.policy(id, CLEANUP);

        catalog.advance(Duration.ofDays(9), clock);
        ScanReport report = worker.runOnce();

        assertThat(writer.loadTable(id).snapshots()).as("expiry ran on main").hasSize(1);
        assertThat(report.orphanScans).isPositive();
        assertThat(report.kept).containsKey(Management.RefKind.BRANCH);
        assertThat(catalog.pending(object.toString())).as("the branch reaches them")
                .doesNotContain(b.location(), second.manifestListLocation());
        assertThat(catalog.exists(b.location())).isTrue();
        assertThat(catalog.store.readPointer(new PointerKey(object, refs.get(branch).toString())))
                .as("maintenance stays on main").get().extracting(Pointer::metadataLocation)
                .isEqualTo(head.metadataLocation());

        deleteRef(branch);
        catalog.advance(Duration.ofDays(1), clock);
        worker.runOnce();

        assertThat(catalog.pending(object.toString())).contains(b.location(), second.manifestListLocation());
    }

    /** A tag's files survive expiry on main, which still runs. */
    @Test
    void aTagsFilesSurviveExpiryOnMain() {
        TableIdentifier id = TestCatalog.table(writer, "tagged");
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        Snapshot first = table.currentSnapshot();
        ref("v1", "tag");
        table.newOverwrite().deleteFile(a).addFile(TestCatalog.dataFile(table, "b")).commit();
        catalog.policy(id, EXPIRY);

        catalog.advance(Duration.ofDays(6), clock);
        ScanReport report = worker.runOnce();

        assertThat(writer.loadTable(id).snapshots()).as("expiry ran on main").hasSize(1);
        assertThat(report.kept).containsKey(Management.RefKind.TAG);
        assertThat(catalog.pending(table.uuid().toString())).doesNotContain(a.location(),
                first.manifestListLocation());
        assertThat(catalog.exists(a.location())).isTrue();
    }

    /**
     * A tag made after the worker read the work list, before expiry commits: the catalog refuses the record, which
     * names no ref, hands back the tag, and the worker records only what the tag does not reach.
     */
    @Test
    void aTagMadeAfterTheWorkListWasReadKeepsItsFiles() {
        TableIdentifier id = TestCatalog.table(writer, "late-tag");
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        table.newOverwrite().deleteFile(a).addFile(TestCatalog.dataFile(table, "b")).commit();
        catalog.policy(id, EXPIRY);
        catalog.advance(Duration.ofDays(6), clock);
        Management.TableWork stale = work(UUID.fromString(table.uuid().toString()));
        assertThat(stale.refs()).isEmpty();
        ref("late", "tag");

        ScanReport report = new ScanReport();
        worker.maintain(stale, clock.instant(), report);

        assertThat(report.failuresByKind).doesNotContainKey(ScanReport.Failure.SNAPSHOT_EXPIRY);
        assertThat(report.snapshotsExpired).isEqualTo(1);
        assertThat(report.kept).containsKey(Management.RefKind.TAG);
        assertThat(catalog.pending(table.uuid().toString())).doesNotContain(a.location());
        assertThat(catalog.exists(a.location())).isTrue();
    }

    /**
     * A file recorded for deletion before a tag that reaches it was made, as when the tag's create was under way while
     * the catalog checked the record: once due, the worker checks it against the tag and completes it, not deleting it.
     */
    @Test
    void aDeletionRecordedBeforeATagIsCheckedAgainstIt() {
        TableIdentifier id = TestCatalog.table(writer, "recorded");
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        table.newOverwrite().deleteFile(a).addFile(TestCatalog.dataFile(table, "b")).commit();
        UUID object = UUID.fromString(table.uuid().toString());
        catalog.management().recordDeletions(TestCatalog.CATALOG, object, List.of(a.location()), List.of());
        ref("after", "tag");

        catalog.advance(Duration.ofDays(8), clock);
        ScanReport report = worker.runOnce();

        assertThat(report.kept).containsKey(Management.RefKind.TAG);
        assertThat(catalog.exists(a.location())).as("the tag reaches it").isTrue();
        assertThat(catalog.pending(object.toString())).as("completed, not deleted").doesNotContain(a.location());
    }

    /**
     * A branch merged into main ends, and its files are main's: main reaches them, so cleanup keeps them with no ref
     * left to.
     */
    @Test
    void aMergedBranchsFilesAreMains() {
        TableIdentifier id = TestCatalog.table(writer, "merged");
        Table table = writer.loadTable(id);
        UUID object = UUID.fromString(table.uuid().toString());
        table.newAppend().appendFile(TestCatalog.dataFile(table, "a")).commit();
        Pointer before = mainOf(object);
        String branch = ref("feature", "branch");
        DataFile b = TestCatalog.dataFile(table, "b");
        table.newAppend().appendFile(b).commit();
        Pointer withB = mainOf(object);
        Pointer head = commitOnBranch(object, branch, withB);
        // Main as it was when the branch was made: B is the branch's alone.
        Pointer back = commitOnMain(object, withB, before);
        assertThat(commit(new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(new PointerSwap(
                        new PointerKey(object, PointerKey.MAIN), Optional.of(back), new Pointer(head.metadataLocation(),
                        back.version() + 1, head.sha256(), head.projection()), journal())),
                List.of(new PointerHold(new PointerKey(object, refs.get(branch).toString()), head)),
                Optional.of(new RefLock.End(refs.get(branch))), List.of(), Optional.empty())))
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(catalog.store.readRef(refs.get(branch))).as("the merge ended the branch").isEmpty();
        catalog.policy(id, CLEANUP);

        catalog.advance(Duration.ofDays(9), clock);
        worker.runOnce();

        assertThat(writer.loadTable(id).currentSnapshot().snapshotId()).isEqualTo(table.currentSnapshot().snapshotId());
        assertThat(catalog.pending(object.toString())).doesNotContain(b.location());
        assertThat(catalog.exists(b.location())).isTrue();
    }

    /** A branch is warned of once as its expiry nears, and ended once past it (choice 11). */
    @Test
    void anExpiringBranchIsWarnedOfOnceThenEnded() {
        String branch = ref("ageing", "branch");
        UUID id = refs.get(branch);

        catalog.advance(Duration.ofDays(24), clock);
        assertThat(worker.runOnce().refsWarned).as("within a week of its expiry").isEqualTo(1);
        catalog.advance(Duration.ofHours(1), clock);
        assertThat(worker.runOnce().refsWarned).as("once").isZero();
        assertThat(catalog.store.readRef(id)).isPresent();

        catalog.advance(Duration.ofDays(7), clock);
        assertThat(worker.runOnce().refsEnded).isEqualTo(1);
        assertThat(catalog.store.readRef(id)).as("ended").isEmpty();
    }

    /**
     * Deleting a tag that names a held table, and one in a frozen namespace, deletes nothing while the hold and the
     * freeze stand: they stop expiry and the orphan scan, and the deletions of such tables are not listed. Once lifted,
     * the files the tag kept are queued (security finding 10).
     */
    @Test
    void deletingATagOfAHeldOrFrozenTableDeletesNothingWhileTheHoldOrFreezeStands() {
        TableIdentifier held = TestCatalog.table(writer, "held");
        TableIdentifier frozen = TestCatalog.table(writer, "frozen");
        Map<TableIdentifier, DataFile> tagged = new LinkedHashMap<>();
        for (TableIdentifier id : List.of(held, frozen)) {
            Table table = writer.loadTable(id);
            DataFile a = TestCatalog.dataFile(table, "a");
            table.newAppend().appendFile(a).commit();
            tagged.put(id, a);
        }
        String tag = ref("kept", "tag");
        for (TableIdentifier id : List.of(held, frozen)) {
            Table table = writer.loadTable(id);
            table.newOverwrite().deleteFile(tagged.get(id)).addFile(TestCatalog.dataFile(table, "b")).commit();
            catalog.policy(id, CLEANUP);
        }
        String hold = catalog.hold(held);
        catalog.freeze(frozen.namespace());

        catalog.advance(Duration.ofDays(9), clock);
        worker.runOnce();
        deleteRef(tag);
        catalog.advance(Duration.ofDays(1), clock);
        worker.runOnce();

        for (TableIdentifier id : List.of(held, frozen)) {
            Table table = writer.loadTable(id);
            assertThat(table.snapshots()).as("%s keeps every snapshot", id).hasSize(2);
            assertThat(catalog.pending(table.uuid().toString())).as("%s has nothing pending", id).isEmpty();
            assertThat(catalog.exists(tagged.get(id).location())).as("%s keeps its file", id).isTrue();
        }

        catalog.release(held, hold);
        catalog.thaw(frozen.namespace());
        catalog.advance(Duration.ofDays(1), clock);
        worker.runOnce();

        for (TableIdentifier id : List.of(held, frozen)) {
            assertThat(catalog.pending(writer.loadTable(id).uuid().toString())).as("%s, lifted", id)
                    .contains(tagged.get(id).location());
        }
    }

    // ---- refs, through the catalog's routes ----

    /** Makes a ref from main, as a writer (who holds create on the catalog); returns its name, unique to the run. */
    private String ref(String name, String kind) {
        String unique = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        String answer = catalog.expect(catalog.manage(catalog.writerTokens(), "POST", "/refs", "{\"name\":\"" + unique
                + "\",\"kind\":\"" + kind + "\"}"), 201).body();
        try {
            refs.put(unique, UUID.fromString(JSON.readTree(answer).path("id").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return unique;
    }

    private void deleteRef(String name) {
        catalog.expect(catalog.manage(catalog.writerTokens(), "DELETE", "/refs/" + name, null), 204);
        refs.remove(name);
    }

    /** The table as the work list names it now. */
    private Management.TableWork work(UUID table) {
        Management management = catalog.management();
        Optional<String> token = Optional.empty();
        do {
            Management.Page<Management.TableWork> page = management.tables(token);
            for (Management.TableWork work : page.items()) {
                if (work.id().equals(table)) {
                    return work;
                }
            }
            token = page.nextToken();
        } while (token.isPresent());
        throw new AssertionError("the work list does not name table " + table);
    }

    // ---- commits on a branch and a merge, through the store (items 105 and 107) ----

    private Pointer mainOf(UUID object) {
        return catalog.store.readPointer(new PointerKey(object, PointerKey.MAIN)).orElseThrow();
    }

    /** The branch's first own pointer of the table: {@code at}'s metadata, as a commit on the branch writes it. */
    private Pointer commitOnBranch(UUID object, String branch, Pointer at) {
        UUID ref = refs.get(branch);
        Pointer head = new Pointer(at.metadataLocation(), 1, at.sha256(), at.projection());
        assertThat(commit(new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(new PointerSwap(
                new PointerKey(object, ref.toString()), Optional.empty(), head, journal())), List.of(),
                Optional.of(new RefLock.Hold(ref)), List.of(), Optional.empty()))).isInstanceOf(
                CommitResult.Committed.class);
        return head;
    }

    /** Main's pointer moved from {@code expected} to {@code to}'s metadata, at its next version. */
    private Pointer commitOnMain(UUID object, Pointer expected, Pointer to) {
        Pointer next = new Pointer(to.metadataLocation(), expected.version() + 1, to.sha256(), to.projection());
        assertThat(commit(new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(new PointerSwap(
                new PointerKey(object, PointerKey.MAIN), Optional.of(expected), next, journal())), List.of(),
                Optional.empty()))).isInstanceOf(CommitResult.Committed.class);
        return next;
    }

    private CommitResult commit(CommitBatch batch) {
        return catalog.store.commit(batch);
    }

    private static JournalEntry journal() {
        return new JournalEntry("ref-cleanup-test", null, "test", "[]", "[]");
    }
}
