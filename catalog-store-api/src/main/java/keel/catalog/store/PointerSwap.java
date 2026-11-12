package keel.catalog.store;

import java.util.Objects;
import java.util.Optional;

/**
 * Compare-and-swap of one pointer. {@code expected} empty means create. The swap succeeds only
 * if the stored pointer equals {@code expected} on both location and version.
 */
public record PointerSwap(PointerKey key, Optional<Pointer> expected, Pointer next, JournalEntry journal) {

    public PointerSwap {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(journal, "journal");
        long nextVersion = expected.map(pointer -> pointer.version() + 1).orElse(1L);
        if (next.version() != nextVersion) {
            throw new IllegalArgumentException("next.version must be " + nextVersion + ", got " + next.version());
        }
    }
}
