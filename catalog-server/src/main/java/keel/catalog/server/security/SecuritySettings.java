package keel.catalog.server.security;

import keel.catalog.core.principal.Caller;
import keel.catalog.security.ClaimMapping;
import keel.catalog.security.IssuerPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * Settings of authentication and authorization ({@code keel.security.*}). There is no switch that turns
 * security off: a server with no trusted issuer refuses to start.
 *
 * @param issuers                   the trusted token issuers (D16, D17a), each with its JWKS and claim mapping
 * @param clockSkew                 how far either side of a token's {@code exp}/{@code nbf} it is still accepted (P10)
 * @param grantAdminGroups          groups that hold {@code manage-grants} on every catalog and nothing else (D18)
 * @param platformOperatorGroups    groups that run the trusted-engine registry and the DR freeze, and hold no data
 *                                  access (D18, D37)
 * @param maintenancePrincipals     the maintenance tier (D25, D30), by principal id: acting as themselves, they alone
 *                                  claim and complete purges, record pending deletions and read the work list, and
 *                                  pass D20's row/column rule (audited); their vended credentials delete nothing,
 *                                  as nobody's do
 * @param refuseIdentityLessCommits D17's switch (CMP-7): refuse writes to regulated areas by a trusted engine
 *                                  that names no end user
 */
@ConfigurationProperties("keel.security")
public record SecuritySettings(
        List<Issuer> issuers,
        @DefaultValue("60s") Duration clockSkew,
        @DefaultValue Set<String> grantAdminGroups,
        @DefaultValue Set<String> platformOperatorGroups,
        @DefaultValue Set<String> maintenancePrincipals,
        @DefaultValue("false") boolean refuseIdentityLessCommits) {

    public SecuritySettings {
        if (issuers == null || issuers.isEmpty()) {
            throw new IllegalArgumentException("keel.security.issuers names no trusted token issuer;"
                    + " the catalog accepts no request without one");
        }
        issuers = List.copyOf(issuers);
        grantAdminGroups = Set.copyOf(grantAdminGroups);
        platformOperatorGroups = Set.copyOf(platformOperatorGroups);
        maintenancePrincipals = Set.copyOf(maintenancePrincipals);
    }

    /** Whether the caller is a maintenance principal acting as itself; one acting for an end user is not. */
    public boolean isMaintenance(Caller caller) {
        return caller.endUser().isEmpty() && maintenancePrincipals.contains(caller.principal().id());
    }

    /**
     * One trusted issuer. Keys come only from {@code jwksUri}; there is no OIDC discovery at startup.
     *
     * @param issuer        the exact {@code iss} value
     * @param jwksUri       where the issuer publishes its signing keys
     * @param audiences     a token must name one of these
     * @param algorithms    accepted signature algorithms, e.g. RS256; never empty
     * @param userIdClaim   the claim with the stable user id that is the same person at every issuer
     * @param groupsClaim   the claim with the user's groups
     * @param clientIdClaim the claim with the client (application) id
     */
    public record Issuer(String issuer, URI jwksUri, Set<String> audiences, Set<SignatureAlgorithm> algorithms,
                         String userIdClaim, String groupsClaim, String clientIdClaim) {

        public Issuer {
            Objects.requireNonNull(issuer, "keel.security.issuers[].issuer");
            Objects.requireNonNull(jwksUri, "keel.security.issuers[].jwks-uri of " + issuer);
            Objects.requireNonNull(audiences, "keel.security.issuers[].audiences of " + issuer);
            Objects.requireNonNull(algorithms, "keel.security.issuers[].algorithms of " + issuer);
            Objects.requireNonNull(userIdClaim, "keel.security.issuers[].user-id-claim of " + issuer);
            Objects.requireNonNull(groupsClaim, "keel.security.issuers[].groups-claim of " + issuer);
            Objects.requireNonNull(clientIdClaim, "keel.security.issuers[].client-id-claim of " + issuer);
        }

        IssuerPolicy policy() {
            return new IssuerPolicy(issuer, jwksUri, audiences, algorithms,
                    new ClaimMapping(userIdClaim, groupsClaim, clientIdClaim));
        }
    }
}
