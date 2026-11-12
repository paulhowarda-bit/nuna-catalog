package keel.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request's claim on an idempotency key (D10). {@code changeId} is the commit or change id the
 * request writes with; only a write with that id completes the claim. The claim lapses at
 * {@code leaseUntil}, after which a request with the same key may take it over. {@code now} is the
 * caller's clock, against which leases and expiry are judged.
 */
public record IdempotencyClaim(String key, String principal, String requestHash, UUID changeId,
                               Instant now, Instant leaseUntil, Instant expiresAt) {

    public IdempotencyClaim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
