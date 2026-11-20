package nuna.catalog.store;

import static nuna.catalog.store.suite.Commits.pointer;
import static nuna.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.suite.Refs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A batch's ref (D33, backlog 103): a commit on a branch keeps to the branch's own pointers, and a merge swaps main's
 * pointers and holds the branch's. The stores lock and check the ref; these are the rules the batch itself keeps.
 */
class RefLockTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Ref DEV = Refs.branch(UUID.randomUUID(), "dev", NOW);

    private static CommitBatch batch(List<PointerSwap> swaps, List<PointerHold> holds, RefLock ref) {
        return new CommitBatch(UUID.randomUUID(), NOW, swaps, holds, Optional.of(ref), List.of(), Optional.empty());
    }

    private static PointerSwap create(PointerKey key) {
        return swap(key, Optional.empty(), pointer("s3://a", 1));
    }

    private static PointerSwap update(PointerKey key) {
        return swap(key, Optional.of(pointer("s3://a", 1)), pointer("s3://b", 2));
    }

    @Test
    void aBranchOwnsOnlyItsOwnPointers() {
        UUID object = UUID.randomUUID();
        RefLock hold = new RefLock.Hold(DEV.id());

        assertThat(hold.owns(DEV.key(object))).isTrue();
        assertThat(hold.owns(new PointerKey(object, PointerKey.MAIN))).isFalse();
        assertThat(hold.owns(Refs.branch(DEV.catalogId(), "other", NOW).key(object))).isFalse();
        assertThat(new RefLock.End(DEV.id()).owns(DEV.key(object))).isTrue();
    }

    @Test
    void aCommitOnABranchSwapsAndHoldsOnlyTheBranchsPointers() {
        RefLock hold = new RefLock.Hold(DEV.id());
        PointerKey own = DEV.key(UUID.randomUUID());
        PointerKey ownHeld = DEV.key(UUID.randomUUID());

        CommitBatch batch = batch(List.of(create(own)), List.of(new PointerHold(ownHeld, pointer("s3://h", 3))), hold);

        assertThat(batch.ref()).contains(hold);
        assertThat(batch.refKey()).isEqualTo(own);
        PointerKey onMain = new PointerKey(UUID.randomUUID(), PointerKey.MAIN);
        assertThatThrownBy(() -> batch(List.of(create(own), create(onMain)), List.of(), hold)).as("a swap on main")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only that branch's pointers");
        assertThatThrownBy(() -> batch(List.of(create(own)), List.of(new PointerHold(onMain, pointer("s3://h", 3))),
                hold)).as("a hold on main")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only that branch's pointers");
        PointerKey otherBranch = Refs.branch(DEV.catalogId(), "other", NOW).key(UUID.randomUUID());
        assertThatThrownBy(() -> batch(List.of(create(otherBranch)), List.of(), hold)).as("another branch's pointer")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMergeSwapsMainAndHoldsOnlyTheBranchsPointers() {
        RefLock end = new RefLock.End(DEV.id());
        UUID object = UUID.randomUUID();
        PointerKey main = new PointerKey(object, PointerKey.MAIN);
        PointerHold own = new PointerHold(DEV.key(object), pointer("s3://dev", 2));

        CommitBatch merge = batch(List.of(update(main)), List.of(own), end);

        assertThat(merge.ref()).contains(end);
        assertThat(merge.refKey()).as("the ref's key of the first object swapped").isEqualTo(DEV.key(object));
        assertThat(batch(List.of(update(main)), List.of(), end).holds()).as("a branch that wrote nothing").isEmpty();
        assertThatThrownBy(() -> batch(List.of(update(DEV.key(object))), List.of(), end)).as("a swap on the branch")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("swaps main's pointers");
        PointerKey otherMain = new PointerKey(UUID.randomUUID(), PointerKey.MAIN);
        assertThatThrownBy(() -> batch(List.of(update(main)), List.of(new PointerHold(otherMain, pointer("s3://m", 1))),
                end)).as("a hold on main").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds only the branch's");
    }

    @Test
    void aBatchWithNoRefKeepsTheOldRules() {
        PointerKey main = new PointerKey(UUID.randomUUID(), PointerKey.MAIN);
        PointerKey own = DEV.key(UUID.randomUUID());

        CommitBatch mixed = new CommitBatch(UUID.randomUUID(), NOW, List.of(create(main), create(own)), List.of(),
                List.of(), Optional.empty());

        assertThat(mixed.ref()).isEmpty();
        assertThat(new CommitBatch(UUID.randomUUID(), NOW, List.of(create(main)), List.of(), Optional.empty()).ref())
                .isEmpty();
        assertThatThrownBy(mixed::refKey).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CommitBatch(UUID.randomUUID(), NOW, List.of(create(main)), List.of(), null,
                List.of(), Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefLock.Hold(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefLock.End(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void inactiveNamesItsSubjectAndAnObjectByDefault() {
        PointerKey key = DEV.key(UUID.randomUUID());

        assertThat(new CommitResult.Inactive(key).subject()).isEqualTo(CommitResult.Subject.OBJECT);
        assertThat(new CommitResult.Inactive(key)).isEqualTo(new CommitResult.Inactive(key, CommitResult.Subject.OBJECT))
                .isNotEqualTo(new CommitResult.Inactive(key, CommitResult.Subject.REF));
        assertThatThrownBy(() -> new CommitResult.Inactive(key, null)).isInstanceOf(NullPointerException.class);
    }
}
