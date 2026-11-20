package nuna.catalog.core.commit;

import java.time.Duration;
import java.util.Objects;

/**
 * The commit engine's bounds (build-plan P10).
 *
 * @param maxAttempts  attempts of one request, the first included, before a lost swap is a 409 (D9); also
 *                     how often findCommit is asked to decide an unknown outcome while the store is down
 * @param retryBudget  wall-clock bound on those attempts and asks, from the request's start; no retry starts
 *                     after it
 * @param firstBackoff the wait before the second attempt; it doubles per attempt, as iceberg-core's
 *                     CatalogHandlers does, with up to 10% jitter
 * @param keyLifetime  how long an idempotency key's answer is replayed (D10)
 * @param keyLease     how long a request holds its key while it works; a holder that dies is taken
 *                     over after this
 */
public record CommitSettings(int maxAttempts, Duration retryBudget, Duration firstBackoff, Duration keyLifetime,
                             Duration keyLease) {

    public static final CommitSettings DEFAULTS = new CommitSettings(4, Duration.ofSeconds(2), Duration.ofMillis(100),
            Duration.ofHours(24), Duration.ofSeconds(30));

    public CommitSettings {
        Objects.requireNonNull(retryBudget, "retryBudget");
        Objects.requireNonNull(firstBackoff, "firstBackoff");
        Objects.requireNonNull(keyLifetime, "keyLifetime");
        Objects.requireNonNull(keyLease, "keyLease");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
    }
}
