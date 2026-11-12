package keel.catalog.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import keel.catalog.core.principal.Caller;
import keel.catalog.core.principal.Principal;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Turns a bearer token into a {@link Caller} (plan 2.2 step 1; D16, D17, D17a).
 *
 * <p>A token is accepted only when its {@code iss} is on the allow-list, its signature verifies
 * against that issuer's JWKS with an algorithm the issuer allows, its {@code aud} names an accepted
 * audience, and the clock is inside its {@code exp}/{@code nbf} window give or take the clock skew.
 * {@code exp} is required. The issuer's own claim mapping then names the principal.
 *
 * <p>Each issuer's JWKS is fetched on first use and cached. A token signed with a key the cache
 * does not hold forces one refetch, which is how key rotation is picked up. Fetches use Nimbus's
 * defaults: cache for 5 minutes, refresh ahead of expiry, and at most two fetches per 30 seconds,
 * so tokens with made-up key ids cannot hammer the IdP.
 */
public final class TokenAuthenticator {

    /** OIDC Core 5.6.2: a claim moved out of the token (Entra's group overage) is named here. */
    private static final String MOVED_CLAIMS = "_claim_names";

    private final Map<String, Issuer> issuers;

    /**
     * @param policies  the trusted issuers, one per {@code iss} value
     * @param clockSkew how far either side of {@code exp} and {@code nbf} a token is still accepted
     * @param clock     the clock token lifetimes are checked against
     */
    public TokenAuthenticator(Collection<IssuerPolicy> policies, Duration clockSkew, Clock clock) {
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("clock skew must not be negative: " + clockSkew);
        }
        issuers = policies.stream().collect(Collectors.toUnmodifiableMap(
                IssuerPolicy::issuer, policy -> new Issuer(policy, decoder(policy, clockSkew, clock))));
    }

    /**
     * @param bearerToken     the token from the {@code Authorization} header, without its scheme
     * @param assertedEndUser the end user the caller says it acts for, when it says so
     * @param registered      whether an engine is in the trusted-engine registry
     * @throws AuthenticationFailure 401 if the token is not accepted; 403 if its client asserts an
     *                               end user without being a registered trusted engine
     */
    public Caller authenticate(String bearerToken, Optional<Principal> assertedEndUser,
                               Predicate<TrustedEngine> registered) {
        Issuer issuer = issuerNamedBy(bearerToken);
        String iss = issuer.policy().issuer();
        Jwt jwt;
        try {
            jwt = issuer.decoder().decode(bearerToken);
        } catch (JwtException e) {
            throw AuthenticationFailure.invalidToken("token from " + iss + " rejected: " + e.getMessage(), e);
        }

        ClaimMapping claims = issuer.policy().claims();
        Principal principal = new Principal(required(jwt, claims.userIdClaim(), iss), groups(jwt, claims.groupsClaim(), iss));
        String clientId = required(jwt, claims.clientIdClaim(), iss);
        boolean trusted = registered.test(new TrustedEngine(iss, clientId));
        if (assertedEndUser.isPresent() && !trusted) {
            throw AuthenticationFailure.assertionRefused("client " + clientId + " of " + iss
                    + " is not a registered trusted engine, so it may not act for another user");
        }
        return new Caller(principal, clientId, trusted, assertedEndUser);
    }

    /** Picks the issuer before anything is verified, so a token never chooses where keys come from. */
    private Issuer issuerNamedBy(String token) {
        JWTClaimsSet unverified;
        try {
            unverified = JWTParser.parse(token).getJWTClaimsSet();
        } catch (ParseException e) {
            throw AuthenticationFailure.invalidToken("malformed token", e);
        }
        // An encrypted token's claims cannot be read without a key the catalog never holds.
        String iss = unverified == null ? null : unverified.getIssuer();
        if (iss == null) {
            throw AuthenticationFailure.invalidToken("token names no issuer");
        }
        Issuer issuer = issuers.get(iss);
        if (issuer == null) {
            throw AuthenticationFailure.invalidToken("issuer " + iss + " is not trusted");
        }
        return issuer;
    }

    private static String required(Jwt jwt, String claim, String iss) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            throw AuthenticationFailure.invalidToken("token from " + iss + " has no " + claim + " claim");
        }
        return value;
    }

    private static Set<String> groups(Jwt jwt, String claim, String iss) {
        Map<String, Object> moved = jwt.getClaimAsMap(MOVED_CLAIMS);
        if (moved != null && moved.containsKey(claim)) {
            throw AuthenticationFailure.invalidToken("token from " + iss + " does not carry its " + claim
                    + " claim (group overage); the catalog's app must emit only the groups assigned to it (D19)");
        }
        List<String> groups = jwt.getClaimAsStringList(claim);
        return groups == null ? Set.of() : Set.copyOf(groups);
    }

    private static JwtDecoder decoder(IssuerPolicy policy, Duration clockSkew, Clock clock) {
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(policy.jwksUri().toURL()).build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("issuer " + policy.issuer() + " has a malformed JWKS URI", e);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys)
                .jwsAlgorithms(allowed -> allowed.addAll(policy.algorithms()))
                .build();
        JwtTimestampValidator lifetime = new JwtTimestampValidator(clockSkew);
        lifetime.setAllowEmptyExpiryClaim(false);
        lifetime.setClock(clock);
        // JwtClaimValidator refuses a token without the claim before it calls the predicate.
        JwtClaimValidator<Collection<String>> audience = new JwtClaimValidator<>(JwtClaimNames.AUD,
                aud -> !Collections.disjoint(aud, policy.audiences()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(lifetime, audience)));
        return decoder;
    }

    private record Issuer(IssuerPolicy policy, JwtDecoder decoder) {
    }
}
