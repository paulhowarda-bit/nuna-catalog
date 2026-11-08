package fidice.catalog.security;

import java.util.Objects;

/**
 * A shared engine registered to act for its end users (D17): the client id it authenticates with,
 * at the issuer that client belongs to. Registry records are stored by the catalog and passed in.
 *
 * @param issuer   the exact {@code iss} value of the engine's tokens
 * @param clientId the engine's client id, as the issuer's claim mapping reads it
 */
public record TrustedEngine(String issuer, String clientId) {

    public TrustedEngine {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
    }
}
