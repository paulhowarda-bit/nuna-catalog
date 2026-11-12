package keel.catalog.audit;

import java.util.Objects;

/**
 * Who made a request. A trusted engine authenticates as itself and asserts the end user; both
 * are recorded (D17).
 *
 * @param principal the normalized principal that authenticated
 * @param endUser   the end user a trusted engine asserted, or null
 * @param engine    the calling engine, or null when unknown
 * @param requestId the request's id; vended credentials carry it too (D21)
 */
public record Actor(String principal, String endUser, String engine, String requestId) {

    public Actor {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(requestId, "requestId");
    }
}
