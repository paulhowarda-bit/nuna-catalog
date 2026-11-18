package nuna.catalog.core.principal;

import java.util.Objects;
import java.util.Optional;

/**
 * Who a request comes from (plan 2.2 step 1). {@code principal} is whoever the bearer token names.
 * When a registered trusted engine acts for someone, {@code endUser} is that person: the journal
 * records both, and authorization is for the end user (D17).
 *
 * @param principal     the principal the token names
 * @param engine        the client (application) id the token was issued to
 * @param trustedEngine whether that client is in the trusted-engine registry (D17, D20)
 * @param endUser       the end user a trusted engine asserted it acts for
 */
public record Caller(Principal principal, String engine, boolean trustedEngine, Optional<Principal> endUser) {

    public Caller {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(endUser, "endUser");
    }

    /** The principal authorization decides for: the end user when an engine acts for one (D17). */
    public Principal subject() {
        return endUser.orElse(principal);
    }
}
