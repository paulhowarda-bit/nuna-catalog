package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;

/**
 * A pointer a commit depends on but does not change: a table a transaction only checks (D15). The store
 * locks it in key order with the batch's swaps and compares it with {@code expected} on location and
 * version, as it compares a swap, but writes no new version and no journal entry. No commit to it can
 * land between that comparison and the batch's COMMIT.
 *
 * <p>{@code expected} empty holds the key's absence: a table a transaction on a branch only checks and the branch has
 * not committed to yet (backlog 278). The store locks the key as a swap that creates it does, so a pointer created
 * meanwhile fails the batch as a changed one does, and leaves no pointer there.
 */
public record PointerHold(PointerKey key, Optional<Pointer> expected) implements PointerLock {

    public PointerHold {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
    }

    /** A hold of the pointer {@code expected}, which exists. */
    public PointerHold(PointerKey key, Pointer expected) {
        this(key, Optional.of(expected));
    }
}
