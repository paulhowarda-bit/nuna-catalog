package keel.catalog.store;

import java.time.Instant;
import java.util.Objects;

/** The response to replay for an idempotency key, written in the commit's transaction (D10). */
public record IdempotencyResult(String key, String principal, String requestHash,
                                int status, String body, Instant expiresAt) {

    public IdempotencyResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
