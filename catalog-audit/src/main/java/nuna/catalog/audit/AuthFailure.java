package nuna.catalog.audit;

import java.util.Objects;

/**
 * A request whose token was rejected (D16). No principal was established, so the record holds
 * what the token claimed; it never holds the token.
 *
 * @param issuer         the token's issuer, or null when the token could not be read
 * @param claimedSubject the token's subject claim, or null
 * @param clientId       the token's client or application id, or null
 * @param reason         why it was rejected
 * @param requestId      the request's id
 * @param remoteAddress  the client's address, or null when unknown
 */
public record AuthFailure(String issuer, String claimedSubject, String clientId, String reason, String requestId,
                          String remoteAddress) implements EventData {

    public AuthFailure {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    public EventType type() {
        return EventType.AUTH_FAILURE;
    }

    @Override
    public String subject() {
        return null;
    }
}
