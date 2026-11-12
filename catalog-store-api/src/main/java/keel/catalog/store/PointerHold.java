package keel.catalog.store;

import java.util.Objects;

/**
 * A pointer a commit depends on but does not change: a table a transaction only checks (D15). The store
 * locks it in key order with the batch's swaps and compares it with {@code expected} on location and
 * version, as it compares a swap, but writes no new version and no journal entry. No commit to it can
 * land between that comparison and the batch's COMMIT.
 */
public record PointerHold(PointerKey key, Pointer expected) implements PointerLock {

    public PointerHold {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
    }
}
