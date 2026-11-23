package nuna.catalog.server.governance;

import nuna.catalog.store.Ref;
import nuna.catalog.store.RefLimits;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * P11's limits and expiry for a catalog's branches and tags ({@code nuna.refs.*}, D33), changeable without code as
 * P10's bounds are. A branch's 180 days is its whole life (item 26's choice 11): its creation, each commit on it and
 * each renewal set its expiry {@code branchIdleExpiry} ahead, never past {@code branchMaxLife} from its creation. Tags
 * never expire.
 *
 * @param maxBranches      the open branches a catalog may hold; a branch past its expiry no longer counts
 * @param maxTags          the tags a catalog may hold
 * @param branchIdleExpiry how long a branch lives after it is made, last committed to or renewed
 * @param branchMaxLife    how long a branch can live in all, from its creation
 * @param expiryWarning    how long before a branch's expiry the catalog warns of it once, on the change stream, and
 *                         the console marks it
 */
@ConfigurationProperties("nuna.refs")
public record RefSettings(
        @DefaultValue("50") int maxBranches,
        @DefaultValue("1000") int maxTags,
        @DefaultValue("30d") Duration branchIdleExpiry,
        @DefaultValue("180d") Duration branchMaxLife,
        @DefaultValue("7d") Duration expiryWarning) {

    public RefSettings {
        if (maxBranches < 0 || maxTags < 0) {
            throw new IllegalArgumentException("nuna.refs.max-branches and nuna.refs.max-tags must be 0 or more");
        }
        for (Duration duration : new Duration[] {branchIdleExpiry, branchMaxLife}) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("nuna.refs.branch-idle-expiry and nuna.refs.branch-max-life must be"
                        + " longer than zero");
            }
        }
        if (expiryWarning.isNegative()) {
            throw new IllegalArgumentException("nuna.refs.expiry-warning must not be negative");
        }
    }

    /** The limits the store checks a create against (P11). */
    public RefLimits limits() {
        return new RefLimits(maxBranches, maxTags);
    }

    /**
     * A branch's expiry once it is made, committed to or renewed at {@code at}: {@code branchIdleExpiry} ahead, never
     * past {@code branchMaxLife} from its creation (choice 11).
     */
    public Instant expiryAfter(Instant createdAt, Instant at) {
        Instant idle = at.plus(branchIdleExpiry);
        Instant end = createdAt.plus(branchMaxLife);
        return idle.isBefore(end) ? idle : end;
    }

    /** The latest a branch made at {@code createdAt} can expire, however often it is renewed. */
    public Instant lastExpiry(Instant createdAt) {
        return createdAt.plus(branchMaxLife);
    }

    /** Whether {@code ref} is a branch, not yet expired, within {@code expiryWarning} of its expiry at {@code now}. */
    public boolean expiring(Ref ref, Instant now) {
        return ref.expiresAt().filter(expiry -> expiry.isAfter(now) && !expiry.minus(expiryWarning).isAfter(now))
                .isPresent();
    }
}
