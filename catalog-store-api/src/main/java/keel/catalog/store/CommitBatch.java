package keel.catalog.store;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one commit writes, as one atomic unit (D8.1). The caller generates {@code commitId}
 * before the transaction begins, so an ambiguous outcome can be looked up afterwards.
 *
 * <p>{@code swaps} is held in ascending key order whatever order it was given in, so every
 * backend locks pointers in the same order and multi-table commits cannot deadlock (D15).
 */
public record CommitBatch(UUID commitId, Instant committedAt, List<PointerSwap> swaps,
                          List<OutboxEvent> outbox, Optional<IdempotencyResult> idempotency) {

    public CommitBatch {
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(committedAt, "committedAt");
        Objects.requireNonNull(idempotency, "idempotency");
        swaps = swaps.stream().sorted(Comparator.comparing(PointerSwap::key)).toList();
        outbox = List.copyOf(outbox);
        if (swaps.isEmpty()) {
            throw new IllegalArgumentException("a commit swaps at least one pointer");
        }
        for (int i = 1; i < swaps.size(); i++) {
            if (swaps.get(i).key().equals(swaps.get(i - 1).key())) {
                throw new IllegalArgumentException("pointer " + swaps.get(i).key() + " is swapped twice");
            }
        }
    }
}
