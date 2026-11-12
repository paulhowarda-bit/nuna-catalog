package keel.catalog.security;

import java.util.Objects;

/**
 * Which claims of one issuer's tokens name the principal (D17a). Each issuer gets its own mapping,
 * so a person known to Entra and to Ping becomes one principal as long as both mappings point at
 * claims that carry the same directory value.
 *
 * @param userIdClaim   the claim holding the stable user or service id; required in every token
 * @param groupsClaim   the claim holding the IdP groups; a token without it has no groups
 * @param clientIdClaim the claim holding the client (application) id; required in every token
 */
public record ClaimMapping(String userIdClaim, String groupsClaim, String clientIdClaim) {

    public ClaimMapping {
        Objects.requireNonNull(userIdClaim, "userIdClaim");
        Objects.requireNonNull(groupsClaim, "groupsClaim");
        Objects.requireNonNull(clientIdClaim, "clientIdClaim");
    }
}
