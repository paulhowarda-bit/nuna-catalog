package keel.catalog.store;

import static keel.catalog.store.suite.Commits.newKey;
import static keel.catalog.store.suite.Commits.pointer;
import static keel.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    void createMustStartAtVersionOne() {
        assertThat(swap(newKey(), Optional.empty(), pointer("a", 1)).next().version()).isEqualTo(1);
        assertThatThrownBy(() -> swap(newKey(), Optional.empty(), pointer("a", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be 1");
    }

    @Test
    void updateMustAdvanceTheVersionByExactlyOne() {
        Optional<Pointer> v4 = Optional.of(pointer("a", 4));
        assertThat(swap(newKey(), v4, pointer("b", 5)).next().version()).isEqualTo(5);
        for (long wrong : new long[] {1, 4, 6}) {
            assertThatThrownBy(() -> swap(newKey(), v4, pointer("b", wrong)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be 5");
        }
    }

    @Test
    void pointerVersionsStartAtOne() {
        assertThatThrownBy(() -> new Pointer("s3://x", 0, "00", new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pointerIsImmutableAndComparedByContent() {
        byte[] projection = {1, 2, 3};
        Pointer pointer = new Pointer("s3://x", 1, "ab", projection);
        projection[0] = 9;
        pointer.projection()[1] = 9;

        assertThat(pointer.projection()).containsExactly(1, 2, 3);
        assertThat(pointer).isEqualTo(new Pointer("s3://x", 1, "ab", new byte[] {1, 2, 3}))
                .hasSameHashCodeAs(new Pointer("s3://x", 1, "ab", new byte[] {1, 2, 3}))
                .isNotEqualTo(new Pointer("s3://x", 1, "ab", new byte[] {1, 2, 4}));
    }

    @Test
    void keysOrderLikeTheirCanonicalTextAndThenByBranch() {
        List<PointerKey> keys = Stream.generate(() -> new PointerKey(UUID.randomUUID(), "main")).limit(2_000).toList();

        assertThat(keys.stream().sorted().toList())
                .isEqualTo(keys.stream().sorted(Comparator.comparing(key -> key.objectId().toString())).toList());
        UUID id = UUID.randomUUID();
        assertThat(new PointerKey(id, "dev")).isLessThan(new PointerKey(id, "main"));
        assertThatThrownBy(() -> new PointerKey(id, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchHoldsSwapsInKeyOrder() {
        List<PointerSwap> swaps = Stream.generate(() -> swap(newKey(), Optional.empty(), pointer("a", 1))).limit(50).toList();

        CommitBatch batch = new CommitBatch(UUID.randomUUID(), Instant.now(), swaps, List.of(), Optional.empty());

        assertThat(batch.swaps()).extracting(PointerSwap::key).isSorted().hasSize(50);
        assertThatThrownBy(() -> batch.swaps().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void batchRejectsNoSwapsAndTwoSwapsOfOnePointer() {
        PointerKey key = newKey();
        assertThatThrownBy(() -> new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(), List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        List<PointerSwap> twice = List.of(swap(key, Optional.empty(), pointer("a", 1)), swap(key, Optional.empty(), pointer("b", 1)));
        assertThatThrownBy(() -> new CommitBatch(UUID.randomUUID(), Instant.now(), twice, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("swapped twice");
    }

    @Test
    void unknownOutcomeIsNotAStoreException() {
        UUID commitId = UUID.randomUUID();
        RuntimeException unknown = new CommitOutcomeUnknownException(commitId, new RuntimeException("link lost"));

        assertThat(unknown).isNotInstanceOf(StoreException.class);
        assertThat(((CommitOutcomeUnknownException) unknown).commitId()).isEqualTo(commitId);
    }
}
