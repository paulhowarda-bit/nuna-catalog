package keel.catalog.store;

import java.time.Instant;
import java.util.UUID;

/** What an idempotency key holds, as {@link IdempotencyStore} reports it. */
public sealed interface KeyState {

    KeyState ABSENT = new Absent();
    KeyState CLAIMED = new Claimed();
    KeyState MISMATCH = new Mismatch();

    /** Nothing live: never used, released, or expired. */
    record Absent() implements KeyState {
    }

    /** The caller's claim now holds the key. */
    record Claimed() implements KeyState {
    }

    /** Another request holds the key until {@code leaseUntil}. */
    record InFlight(UUID changeId, Instant leaseUntil) implements KeyState {
    }

    /** The final answer, durable: replay it. */
    record Done(IdempotencyResult result) implements KeyState {
    }

    /** The key was used by another principal or for another request. */
    record Mismatch() implements KeyState {
    }
}
