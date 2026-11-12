package keel.catalog.store;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Everything one commit writes, as one atomic unit (D8.1). The caller generates {@code commitId}
 * before the transaction begins, so an ambiguous outcome can be looked up afterwards.
 *
 * <p>{@code holds} are pointers the commit depends on but does not change. Every backend locks the
 * swapped and the held pointers together in ascending key order ({@link #locks}), so multi-table
 * commits cannot deadlock (D15). {@code swaps} and {@code holds} are each held in that order whatever
 * order they were given in, and no pointer is in the batch twice.
 */
public record CommitBatch(UUID commitId, Instant committedAt, List<PointerSwap> swaps, List<PointerHold> holds,
                          List<OutboxEvent> outbox, Optional<IdempotencyResult> idempotency) {

    public CommitBatch {
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(committedAt, "committedAt");
        Objects.requireNonNull(idempotency, "idempotency");
        swaps = swaps.stream().sorted(Comparator.comparing(PointerSwap::key)).toList();
        holds = holds.stream().sorted(Comparator.comparing(PointerHold::key)).toList();
        outbox = List.copyOf(outbox);
        if (swaps.isEmpty()) {
            throw new IllegalArgumentException("a commit swaps at least one pointer");
        }
        List<PointerKey> keys = Stream.<PointerLock>concat(swaps.stream(), holds.stream())
                .map(PointerLock::key).sorted().toList();
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).equals(keys.get(i - 1))) {
                throw new IllegalArgumentException("pointer " + keys.get(i) + " is in the batch twice");
            }
        }
    }

    /** A batch that holds nothing: every pointer it locks, it swaps. */
    public CommitBatch(UUID commitId, Instant committedAt, List<PointerSwap> swaps, List<OutboxEvent> outbox,
                       Optional<IdempotencyResult> idempotency) {
        this(commitId, committedAt, swaps, List.of(), outbox, idempotency);
    }

    /** Every swapped and held pointer, in the ascending key order every backend locks them in. */
    public List<PointerLock> locks() {
        return Stream.<PointerLock>concat(swaps.stream(), holds.stream())
                .sorted(Comparator.comparing(PointerLock::key)).toList();
    }
}
