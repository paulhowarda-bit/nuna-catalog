package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A legal hold released (D24) or a DR freeze lifted (D37), as the store keeps it (backlog 133): what it was on, the
 * reason, who placed it and when, as the hold or freeze recorded them, and who lifted it and when. The store writes
 * one in the transaction of each release and lift, so a catalog's history of them is complete
 * ({@link GovernanceStore#liftedIn}).
 *
 * @param holdId the hold's id; empty for a freeze
 * @param target the level it was on
 * @param reason the hold's reason, or the freeze's value
 * @param liftedAt the change's time
 */
public record GovernanceLift(Kind kind, Optional<UUID> holdId, Securable target, String reason, String placedBy,
                             Instant placedAt, String liftedBy, Instant liftedAt) {

    public enum Kind { HOLD, DR_FREEZE }

    public GovernanceLift {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(placedBy, "placedBy");
        Objects.requireNonNull(placedAt, "placedAt");
        Objects.requireNonNull(liftedBy, "liftedBy");
        Objects.requireNonNull(liftedAt, "liftedAt");
        if (holdId.isPresent() != (kind == Kind.HOLD)) {
            throw new IllegalArgumentException("a hold's lift names the hold, and only a hold's: " + kind);
        }
    }

    /** The release of {@code hold} by {@code by} at {@code at}. */
    public static GovernanceLift released(LegalHold hold, String by, Instant at) {
        return new GovernanceLift(Kind.HOLD, Optional.of(hold.id()), hold.target(), hold.reason(), hold.placedBy(),
                hold.placedAt(), by, at);
    }

    /** The lift of {@code freeze}, a DR freeze attribute, by {@code by} at {@code at}. */
    public static GovernanceLift lifted(GovernanceAttribute freeze, String by, Instant at) {
        if (freeze.kind() != GovernanceAttribute.Kind.DR_FREEZE) {
            throw new IllegalArgumentException("not a DR freeze: " + freeze.kind());
        }
        return new GovernanceLift(Kind.DR_FREEZE, Optional.empty(), freeze.target(), freeze.value(), freeze.setBy(),
                freeze.setAt(), by, at);
    }
}
