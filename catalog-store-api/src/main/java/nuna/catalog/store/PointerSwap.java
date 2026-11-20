package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;

/**
 * Compare-and-swap of one pointer. {@code expected} empty means create. The swap succeeds only
 * if the stored pointer equals {@code expected} on both location and version.
 *
 * <p>{@code drWatermark}: the commit also records {@code next} as the object's DR watermark ({@link DrWatermark}), in
 * its own transaction, as the DR rewind does (D37, amended again 2026-11-20: "Rewind sets watermark"; item 100). The
 * watermark keeps its rules: it only rises, so a higher one kept stays, and it is at the pointer, never above it.
 */
public record PointerSwap(PointerKey key, Optional<Pointer> expected, Pointer next, JournalEntry journal,
                          boolean drWatermark) implements PointerLock {

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

    /** A swap that records no DR watermark, as every commit but a DR rewind. */
    public PointerSwap(PointerKey key, Optional<Pointer> expected, Pointer next, JournalEntry journal) {
        this(key, expected, next, journal, false);
    }
}
