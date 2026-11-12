package keel.catalog.store;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency keys (D10). A request claims its key; the commit or change that carries the
 * key's {@link IdempotencyResult} completes the claim in its own transaction when written with the
 * claim's change id, or records the result directly when no live record exists. If another
 * request holds the key, or it is already done, that commit or change fails cleanly with
 * {@link StoreException} and writes nothing. A definite 4xx is recorded with {@link #completeKey};
 * a 5xx releases the claim and is never replayed.
 *
 * <p>A record expires at its {@code expiresAt} and is then treated as absent, whether or not
 * {@link #expireKeys} has deleted it yet. A {@code Done} answer is durable: the store waits
 * until the result it read can no longer be lost (storage-design 9).
 */
public interface IdempotencyStore {

    /**
     * Claims the key, or reports who has it.
     *
     * @return {@code Claimed} (new, expired, or a lapsed claim taken over), {@code Done},
     *         {@code InFlight} (another request holds a live claim), or {@code Mismatch}
     * @throws CommitOutcomeUnknownException carrying the claim's change id
     */
    KeyState claimKey(IdempotencyClaim claim);

    /** @return {@code Absent}, {@code InFlight} or {@code Done}, judged at {@code now} */
    KeyState readKey(String key, Instant now);

    /**
     * Waits, by the store's clock, until the key is no longer held by a live claim, or {@code limit}
     * passes.
     *
     * @return as {@link #readKey}; {@code InFlight} only when {@code limit} passed or the claim lapsed
     */
    KeyState awaitKey(String key, Duration limit);

    /**
     * Records a final answer that no commit or change wrote (a definite 4xx), if the claim held
     * with {@code changeId} is still live.
     *
     * @return {@code Applied}, {@code Missing(KEY)}, or {@code Refused(NOT_HOLDER)}
     */
    WriteResult completeKey(IdempotencyResult result, UUID changeId);

    /** Drops the claim held with {@code changeId} (a 5xx), so a retry runs again. Results as {@link #completeKey}. */
    WriteResult releaseKey(String key, UUID changeId);

    /** Deletes up to {@code limit} records that expired at or before {@code now}; returns how many. */
    int expireKeys(Instant now, int limit);
}
