package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.now;
import static nuna.catalog.store.suite.Refs.branch;
import static nuna.catalog.store.suite.Refs.changeAt;
import static nuna.catalog.store.suite.Refs.tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Commits on a branch and the merge transaction (D33, backlog 103): a commit on a branch locks its ref before any
 * pointer, writes only the branch's own pointers of objects in its cut, and lands before a deletion or a merge of the
 * branch or not at all; a merge moves main and ends the branch in one transaction, and never loses a commit on the
 * branch; a ref keeps the files of a dropped table its cut names.
 */
public abstract class BranchCommitSuite extends GroupSuite {

    private static final WriteResult REFERENCED = new WriteResult.Refused(WriteResult.Reason.REFERENCED);

    private final Random random = new Random(103);

    private Ref created(Ref ref) {
        assertApplied(store.createRef(ref, RefLimits.P11, change()));
        return ref;
    }

    /** {@code full}'s swaps and records, at {@code at}, holding {@code holds}, on {@code lock}. */
    private static CommitBatch withRef(CommitBatch full, Instant at, List<PointerHold> holds, RefLock lock) {
        return new CommitBatch(full.commitId(), at, full.swaps(), holds, Optional.of(lock), full.outbox(),
                full.idempotency());
    }

    /** A commit on {@code ref}'s branch at {@code at}, with every record a commit writes. */
    private static CommitBatch onBranch(Ref ref, Instant at, PointerSwap... swaps) {
        return withRef(Commits.fullBatch(swaps), at, List.of(), new RefLock.Hold(ref.id()));
    }

    private static CommitBatch onBranch(Ref ref, PointerSwap... swaps) {
        return onBranch(ref, now(), swaps);
    }

    /** The merge of {@code ref} at {@code at}: it swaps main's pointers and holds the branch's own. */
    private static CommitBatch merge(Ref ref, Instant at, List<PointerSwap> swaps, PointerHold... holds) {
        return withRef(Commits.fullBatch(swaps.toArray(PointerSwap[]::new)), at, List.of(holds),
                new RefLock.End(ref.id()));
    }

    private static CommitBatch merge(Ref ref, List<PointerSwap> swaps, PointerHold... holds) {
        return merge(ref, now(), swaps, holds);
    }

    /** Swaps main's pointer from {@code main} to the branch's {@code head}, at main's next version. */
    private static PointerSwap toMain(CatalogObject object, Pointer main, Pointer head) {
        return Commits.swap(object.mainKey(), Optional.of(main), new Pointer(head.metadataLocation(), main.version() + 1,
                head.sha256(), head.projection()));
    }

    private static CommitResult.Inactive refGone(CommitBatch batch) {
        return new CommitResult.Inactive(batch.refKey(), CommitResult.Subject.REF);
    }

    /** Commits {@code key} on {@code ref}'s branch from {@code expected} to a pointer named {@code name}; returns it. */
    private Pointer commitOn(Ref ref, PointerKey key, Optional<Pointer> expected, String name) {
        PointerSwap swap = Commits.advance(key, expected, name);
        assertThat(store.commit(onBranch(ref, swap))).isInstanceOf(CommitResult.Committed.class);
        return swap.next();
    }

    /** Commits {@code key} on main from {@code expected} to a pointer named {@code name}; returns it. */
    private Pointer commitOnMain(PointerKey key, Pointer expected, String name) {
        PointerSwap swap = Commits.advance(key, Optional.of(expected), name);
        assertThat(store.commit(Commits.batch(swap))).isInstanceOf(CommitResult.Committed.class);
        return swap.next();
    }

    private Pointer mainOf(CatalogObject object) {
        return store.readPointer(object.mainKey()).orElseThrow();
    }

    private List<UUID> purgeEligible(Instant now) {
        return allPages(100, page -> store.listPurgeEligible(now, page)).stream().map(CatalogObject::id).toList();
    }

    @Test
    @DisplayName("Branch commits: a commit on a branch writes the branch's own pointers and every record, and leaves main")
    void aBranchCommitWritesOnlyItsOwnPointers() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject other = newTable(namespace, "u");
        Pointer main = mainOf(table);
        List<Long> mainJournal = underTest().journalVersions(table.mainKey());
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        CommitBatch first = onBranch(dev, Commits.advance(own, Optional.empty(), "t-on-dev-1"));

        assertThat(store.commit(first)).isEqualTo(new CommitResult.Committed(first.commitId()));

        assertThat(underTest().writtenRows(first)).isEqualTo(WrittenRows.allOf(first));
        assertThat(store.readPointer(own)).contains(Commits.next(first));
        assertThat(store.readPointer(table.mainKey())).contains(main);
        assertThat(store.readPointer(dev.key(other.id()))).as("an object the branch has not written").isEmpty();
        assertThat(store.findCommit(first.commitId(), own)).isEqualTo(CommitLookup.COMMITTED);
        PointerSwap again = Commits.advance(own, Optional.of(Commits.next(first)), "t-on-dev-2");
        PointerSwap otherOwn = Commits.advance(dev.key(other.id()), Optional.empty(), "u-on-dev-1");
        CommitBatch second = onBranch(dev, again, otherOwn);
        assertThat(store.commit(second)).isEqualTo(new CommitResult.Committed(second.commitId()));
        assertThat(store.readPointer(dev.key(other.id()))).contains(otherOwn.next());
        assertThat(underTest().journalVersions(own)).containsExactly(1L, 2L);
        assertThat(underTest().journalVersions(table.mainKey())).isEqualTo(mainJournal);
        CommitBatch stale = onBranch(dev, Commits.advance(own, Optional.of(Commits.next(first)), "t-stale"));
        assertThat(store.commit(stale)).isEqualTo(new CommitResult.Conflict(own, Optional.of(again.next())));
        assertThat(underTest().writtenRows(stale)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Branch commits: a commit on a deleted, expired or unknown branch, or on a tag, writes nothing")
    void aCommitOnAGoneBranchWritesNothing() {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        Pointer main = mainOf(table);
        Instant made = now();
        Ref dev = created(branch(catalog, "dev", made));
        Ref deleted = created(branch(catalog, "deleted", made));
        Ref v1 = created(tag(catalog, "v1", made));
        assertApplied(store.deleteRef(deleted.id(), change()));
        Ref unknown = branch(catalog, "unknown", made);
        Instant expiry = dev.expiresAt().orElseThrow();

        for (CommitBatch refused : List.of(
                onBranch(deleted, Commits.advance(deleted.key(table.id()), Optional.empty(), "on-deleted")),
                onBranch(dev, expiry, Commits.advance(dev.key(table.id()), Optional.empty(), "at-expiry")),
                onBranch(v1, Commits.advance(v1.key(table.id()), Optional.empty(), "on-tag")),
                onBranch(unknown, Commits.advance(unknown.key(table.id()), Optional.empty(), "on-unknown")))) {
            assertThat(store.commit(refused)).isEqualTo(refGone(refused));
            assertThat(underTest().writtenRows(refused)).isEqualTo(WrittenRows.NONE);
            assertThat(store.readPointer(refused.refKey())).isEmpty();
        }

        CommitBatch beforeExpiry = onBranch(dev, expiry.minusMillis(1),
                Commits.advance(dev.key(table.id()), Optional.empty(), "before-expiry"));
        assertThat(store.commit(beforeExpiry)).isEqualTo(new CommitResult.Committed(beforeExpiry.commitId()));
        assertThat(store.readPointer(table.mainKey())).contains(main);
    }

    @Test
    @DisplayName("Branch commits: a branch swaps only the tables and views of its cut, and none dropped since")
    void aBranchSwapsOnlyItsCut() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject dropped = newTable(namespace, "dropped");
        Ref dev = created(branch(catalog, "dev", now()));
        CatalogObject later = newTable(namespace, "later");
        CatalogObject elsewhere = newTable(newNamespace(newCatalog(), "ns"), "t");
        assertApplied(store.dropObject(dropped.id(), now().plus(Duration.ofDays(30)), change()));
        PointerKey own = dev.key(table.id());

        for (UUID outside : List.of(later.id(), elsewhere.id(), namespace.id(), UUID.randomUUID())) {
            PointerKey key = dev.key(outside);
            CommitBatch refused = onBranch(dev, Commits.advance(own, Optional.empty(), "t-" + outside),
                    Commits.advance(key, Optional.empty(), "outside-" + outside));
            assertThat(store.commit(refused)).as("object %s", outside).isEqualTo(new CommitResult.Inactive(key));
            assertThat(underTest().writtenRows(refused)).isEqualTo(WrittenRows.NONE);
        }
        PointerKey droppedKey = dev.key(dropped.id());
        CommitBatch onDropped = onBranch(dev, Commits.advance(droppedKey, Optional.empty(), "dropped-on-dev"));
        assertThat(store.commit(onDropped)).as("dropped on main after the ref was made")
                .isEqualTo(new CommitResult.Inactive(droppedKey));
        assertThat(underTest().writtenRows(onDropped)).isEqualTo(WrittenRows.NONE);
        assertThat(store.readPointer(own)).isEmpty();
    }

    @Test
    @DisplayName("Branch commits: a deletion waits for a commit on the branch held open before COMMIT, and deletes what it wrote")
    void aDeletionWaitsForABranchCommitInFlight() throws Exception {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        CommitBatch batch = onBranch(dev, Commits.advance(own, Optional.empty(), "held"));
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatalogStore held = underTest().store(Commits.before(batch, () -> {
            atCommit.countDown();
            CommitSuite.awaitLatch(release);
        }));
        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> held.commit(batch), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).as("the commit reached COMMIT").isTrue();

        CompletableFuture<WriteResult> deletion = CompletableFuture.supplyAsync(
                () -> store.deleteRef(dev.id(), change()), background);
        Thread.sleep(1_500);
        assertThat(deletion).as("the deletion must wait while the commit holds the ref").isNotDone();
        release.countDown();

        assertThat(commit.get(60, TimeUnit.SECONDS)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.readPointer(own)).as("the deletion deleted what the commit wrote").isEmpty();
        assertThat(underTest().journalVersions(own)).as("the branch's journal stays").containsExactly(1L);
    }

    @Test
    @DisplayName("Branch commits: of a commit on a branch and the branch's deletion at once, no pointer of the deleted ref is left")
    void aBranchCommitAndItsDeletionTakeTurns() throws Exception {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        for (int round = 0; round < 20; round++) {
            Ref ref = created(branch(catalog, "r" + round, now()));
            PointerKey own = ref.key(table.id());
            CommitBatch batch = onBranch(ref, Commits.advance(own, Optional.empty(), "r" + round));
            CountDownLatch start = new CountDownLatch(1);
            Future<CommitResult> commit = background.submit(() -> {
                start.await();
                return store.commit(batch);
            });
            Future<WriteResult> deletion = background.submit(() -> {
                start.await();
                return store.deleteRef(ref.id(), change());
            });
            start.countDown();

            assertThat(commit.get(2, TimeUnit.MINUTES)).as("round %d", round)
                    .isIn(new CommitResult.Committed(batch.commitId()), refGone(batch));
            assertThat(deletion.get(2, TimeUnit.MINUTES)).isEqualTo(WriteResult.APPLIED);
            assertThat(store.readPointer(own)).as("round %d", round).isEmpty();
            assertThat(store.readRef(ref.id())).isEmpty();
        }
    }

    @Test
    @DisplayName("Merge: a merge moves main and ends the branch with its cut, own pointers and pieces; its journal stays and its name is free")
    void aMergeMovesMainAndEndsTheBranch() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject untouched = newTable(namespace, "u");
        Pointer main = mainOf(table);
        Pointer untouchedMain = mainOf(untouched);
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        byte[] projection = new byte[3 * underTest().projectionPieceBytes()];
        random.nextBytes(projection);
        Pointer large = new Pointer("s3://suite-warehouse/large-on-dev.metadata.json", 1, "0".repeat(64), projection);
        assertThat(store.commit(onBranch(dev, Commits.swap(own, Optional.empty(), large))))
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(underTest().projectionPieces(own)).isEqualTo(3);
        CommitBatch merge = merge(dev, List.of(toMain(table, main, large)), new PointerHold(own, large));

        assertThat(store.commit(merge)).isEqualTo(new CommitResult.Committed(merge.commitId()));

        assertThat(underTest().writtenRows(merge)).isEqualTo(WrittenRows.allOf(merge));
        assertThat(store.readPointer(table.mainKey())).contains(Commits.next(merge));
        assertThat(store.readPointer(untouched.mainKey())).contains(untouchedMain);
        assertThat(store.findCommit(merge.commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readRef(dev.id())).isEmpty();
        assertThat(store.findRef(catalog, "dev")).isEmpty();
        assertThat(store.readPointer(own)).isEmpty();
        assertThat(underTest().projectionPieces(own)).isZero();
        assertThat(underTest().journalVersions(own)).as("the branch's journal stays").containsExactly(1L);
        assertThat(store.listRefNamespaces(dev.id(), catalog, PageRequest.first(10)).items()).isEmpty();
        assertThat(store.listRefObjects(dev.id(), namespace.id(), CatalogObject.Kind.TABLE, PageRequest.first(10))
                .items()).isEmpty();
        assertThat(store.commit(merge)).as("the merge again, once it landed").isEqualTo(refGone(merge));
        assertApplied(store.createRef(branch(catalog, "dev", now()), RefLimits.P11, change()));
    }

    @Test
    @DisplayName("Merge: a merge whose main or branch pointer moved, or that leaves an own pointer unheld, writes nothing and keeps the branch")
    void aMergeThatDoesNotMatchWritesNothing() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject other = newTable(namespace, "u");
        Pointer main = mainOf(table);
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        PointerKey otherOwn = dev.key(other.id());
        Pointer onDev = commitOn(dev, own, Optional.empty(), "t-on-dev");

        Pointer movedMain = commitOnMain(table.mainKey(), main, "t-on-main");
        CommitBatch mainMoved = merge(dev, List.of(toMain(table, main, onDev)), new PointerHold(own, onDev));
        assertThat(store.commit(mainMoved)).isEqualTo(new CommitResult.Conflict(table.mainKey(), Optional.of(movedMain)));

        CommitBatch branchMoved = merge(dev, List.of(toMain(table, movedMain, onDev)), new PointerHold(own, onDev));
        Pointer onDevAgain = commitOn(dev, own, Optional.of(onDev), "t-on-dev-2");
        assertThat(store.commit(branchMoved)).isEqualTo(new CommitResult.Conflict(own, Optional.of(onDevAgain)));

        CommitBatch unheld = merge(dev, List.of(toMain(table, movedMain, onDevAgain)), new PointerHold(own, onDevAgain));
        Pointer otherOnDev = commitOn(dev, otherOwn, Optional.empty(), "u-on-dev");
        assertThat(store.commit(unheld)).as("a commit on the branch after the merge read it")
                .isEqualTo(new CommitResult.Conflict(otherOwn, Optional.of(otherOnDev)));

        for (CommitBatch refused : List.of(mainMoved, branchMoved, unheld)) {
            assertThat(underTest().writtenRows(refused)).isEqualTo(WrittenRows.NONE);
        }
        assertThat(store.readRef(dev.id())).contains(dev);
        assertThat(store.readPointer(own)).contains(onDevAgain);
        assertThat(store.readPointer(table.mainKey())).contains(movedMain);
        Pointer otherMain = mainOf(other);
        CommitBatch whole = merge(dev, List.of(toMain(table, movedMain, onDevAgain), toMain(other, otherMain, otherOnDev)),
                new PointerHold(own, onDevAgain), new PointerHold(otherOwn, otherOnDev));
        assertThat(store.commit(whole)).isEqualTo(new CommitResult.Committed(whole.commitId()));
        assertThat(store.readPointer(other.mainKey()).map(Pointer::metadataLocation))
                .contains(otherOnDev.metadataLocation());
        assertThat(store.readPointer(otherOwn)).isEmpty();
    }

    @Test
    @DisplayName("Merge: a merge of a deleted, expired or unknown branch, or of a tag, writes nothing and leaves main")
    void aMergeOfAGoneBranchWritesNothing() {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        Pointer main = mainOf(table);
        Instant made = now();
        Ref dev = created(branch(catalog, "dev", made));
        Pointer onDev = commitOn(dev, dev.key(table.id()), Optional.empty(), "t-on-dev");
        Ref deleted = created(branch(catalog, "deleted", made));
        Pointer onDeleted = commitOn(deleted, deleted.key(table.id()), Optional.empty(), "t-on-deleted");
        assertApplied(store.deleteRef(deleted.id(), change()));
        Ref v1 = created(tag(catalog, "v1", made));
        Ref unknown = branch(catalog, "unknown", made);
        PointerSwap toMain = toMain(table, main, onDev);

        for (CommitBatch refused : List.of(
                merge(deleted, List.of(toMain(table, main, onDeleted)), new PointerHold(deleted.key(table.id()), onDeleted)),
                merge(dev, dev.expiresAt().orElseThrow(), List.of(toMain), new PointerHold(dev.key(table.id()), onDev)),
                merge(v1, List.of(toMain)),
                merge(unknown, List.of(toMain)))) {
            assertThat(store.commit(refused)).isEqualTo(refGone(refused));
            assertThat(underTest().writtenRows(refused)).isEqualTo(WrittenRows.NONE);
        }
        assertThat(store.readPointer(table.mainKey())).contains(main);
        assertThat(store.readRef(dev.id())).contains(dev);
        assertThat(store.readPointer(dev.key(table.id()))).contains(onDev);
    }

    @Test
    @DisplayName("Merge: of a merge and a commit on its branch at once, never both land, so no commit on the branch is lost")
    void aMergeAndABranchCommitNeverBothLand() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject other = newTable(namespace, "u");
        for (int round = 0; round < 20; round++) {
            Ref ref = created(branch(catalog, "r" + round, now()));
            PointerKey own = ref.key(table.id());
            Pointer onRef = commitOn(ref, own, Optional.empty(), "t-r" + round);
            Pointer main = mainOf(table);
            CommitBatch merge = merge(ref, List.of(toMain(table, main, onRef)), new PointerHold(own, onRef));
            CommitBatch commit = round % 2 == 0
                    ? onBranch(ref, Commits.advance(own, Optional.of(onRef), "t-r" + round + "-again"))
                    : onBranch(ref, Commits.advance(ref.key(other.id()), Optional.empty(), "u-r" + round));
            CountDownLatch start = new CountDownLatch(1);
            Future<CommitResult> merging = background.submit(() -> {
                start.await();
                return store.commit(merge);
            });
            Future<CommitResult> committing = background.submit(() -> {
                start.await();
                return store.commit(commit);
            });
            start.countDown();
            CommitResult merged = merging.get(2, TimeUnit.MINUTES);
            CommitResult committed = committing.get(2, TimeUnit.MINUTES);

            if (merged instanceof CommitResult.Committed) {
                assertThat(committed).as("round %d: the commit after the merge", round).isEqualTo(refGone(commit));
                assertThat(store.readPointer(table.mainKey())).contains(Commits.next(merge));
                assertThat(store.readRef(ref.id())).isEmpty();
            } else {
                PointerSwap landed = commit.swaps().getFirst();
                assertThat(committed).as("round %d", round).isEqualTo(new CommitResult.Committed(commit.commitId()));
                assertThat(merged).as("round %d: the merge after the commit", round)
                        .isEqualTo(new CommitResult.Conflict(landed.key(), Optional.of(landed.next())));
                assertThat(store.readPointer(table.mainKey())).contains(main);
                assertThat(underTest().writtenRows(merge)).isEqualTo(WrittenRows.NONE);
                assertApplied(store.deleteRef(ref.id(), change()));
            }
        }
    }

    @Tag("heavy")
    @Test
    @DisplayName("Merge: a merge whose COMMIT is lost wrote nothing and keeps the branch; one whose answer is lost landed whole")
    void aLostMergeIsDecidedByFindCommit() {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        Pointer main = mainOf(table);
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        Pointer onDev = commitOn(dev, own, Optional.empty(), "t-on-dev");
        CommitBatch lost = merge(dev, List.of(toMain(table, main, onDev)), new PointerHold(own, onDev));
        CatalogStore losing = underTest().store(Commits.before(lost, faults::dropRequests));

        assertThatThrownBy(() -> losing.commit(lost)).isInstanceOf(CommitOutcomeUnknownException.class);
        faults.healLink();

        assertThat(store.findCommit(lost.commitId(), table.mainKey())).isEqualTo(CommitLookup.NOT_COMMITTED);
        assertThat(underTest().writtenRows(lost)).isEqualTo(WrittenRows.NONE);
        assertThat(store.readRef(dev.id())).contains(dev);
        assertThat(store.readPointer(own)).contains(onDev);
        assertThat(store.readPointer(table.mainKey())).contains(main);
        CommitBatch answered = merge(dev, List.of(toMain(table, main, onDev)), new PointerHold(own, onDev));
        CatalogStore dropping = underTest().store(Commits.before(answered, faults::dropResponses));

        assertThatThrownBy(() -> dropping.commit(answered))
                .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                        unknown -> assertThat(unknown.commitId()).isEqualTo(answered.commitId()));
        faults.healLink();

        assertThat(store.findCommit(answered.commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
        assertThat(underTest().writtenRows(answered)).isEqualTo(WrittenRows.allOf(answered));
        assertThat(store.readRef(dev.id())).isEmpty();
        assertThat(store.readPointer(own)).isEmpty();
    }

    @Test
    @DisplayName("Purge: a ref whose cut names a dropped table keeps its files until it is deleted: a tag, an expired branch and a cut copied from a branch alike")
    void aRefKeepsADroppedTablesFiles() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Ref v1 = created(tag(catalog, "v1", now()));
        Ref dev = created(branch(catalog, "dev", now()));
        CatalogObject later = newTable(namespace, "later");
        assertApplied(store.dropObject(table.id(), now(), change()));
        assertApplied(store.dropObject(later.id(), now(), change()));

        assertThat(purgeEligible(now())).as("a table made after the refs").contains(later.id())
                .doesNotContain(table.id());
        assertThat(store.claimPurge(table.id(), "worker", change())).isEqualTo(REFERENCED);
        assertApplied(store.deleteRef(v1.id(), change()));
        assertThat(store.claimPurge(table.id(), "worker", changeAt(dev.expiresAt().orElseThrow())))
                .as("a branch past its expiry, until it is deleted").isEqualTo(REFERENCED);
        Ref fix = created(Refs.from(dev, "fix", Ref.Kind.TAG, now()));
        assertApplied(store.deleteRef(dev.id(), change()));
        assertThat(store.claimPurge(table.id(), "worker", change())).as("a ref made from the branch after the drop")
                .isEqualTo(REFERENCED);
        assertThat(purgeEligible(now())).doesNotContain(table.id());
        assertApplied(store.deleteRef(fix.id(), change()));

        assertThat(purgeEligible(now())).contains(table.id(), later.id());
        assertApplied(store.claimPurge(table.id(), "worker", change()));
        assertApplied(store.claimPurge(later.id(), "worker", change()));
    }
}
