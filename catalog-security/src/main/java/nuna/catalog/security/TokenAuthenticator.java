package nuna.catalog.security;

import com.nimbusds.jose.jwk.source.JWKSetSourceWithHealthStatusReporting;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.health.HealthReport;
import com.nimbusds.jose.util.health.HealthReportListener;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
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
 * so tokens with made-up key ids cannot hammer the IdP. The one exception is how long a fetch waits
 * for the IdP's answer: {@link #JWKS_READ_TIMEOUT}, not Nimbus's 500 ms.
 *
 * <p>When a token needs keys the cache does not hold and they cannot be fetched (the IdP down, or slower
 * than the timeout), the token is not checked and not accepted: the failure is 503, not 401, because the
 * token is not known to be bad (D16, amended 2026-11-20). A token bad on its own is still 401.
 */
public final class TokenAuthenticator {

    /** OIDC Core 5.6.2: a claim moved out of the token (Entra's group overage) is named here. */
    private static final String MOVED_CLAIMS = "_claim_names";

    /**
     * How long a JWKS fetch waits for the IdP to answer. A fetch that times out leaves the token unchecked
     * (503 since backlog 90; before, 401, which told the caller its good token was bad). Nimbus's 500 ms timed
     * out in the overnight run of 2026-11-18, where the test JVM, slowed by the build beside it, served its in-JVM
     * IdP's keys late (backlog 60). Fetches are rare (first use, rotation, refresh ahead of the cache's expiry) and
     * rate-limited, so a longer wait costs a request little; connecting keeps Nimbus's 500 ms.
     */
    private static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(5);

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
        issuers = policies.stream().collect(Collectors.toUnmodifiableMap(IssuerPolicy::issuer, policy -> {
            KeyFetches fetches = new KeyFetches();
            return new Issuer(policy, decoder(policy, fetches, clockSkew, clock), fetches);
        }));
    }

    /**
     * @param bearerToken     the token from the {@code Authorization} header, without its scheme
     * @param assertedEndUser the end user the caller says it acts for, when it says so
     * @param registered      whether an engine is in the trusted-engine registry
     * @throws AuthenticationFailure 401 if the token is not accepted; 403 if its client asserts an
     *                               end user without being a registered trusted engine; 503 if it could
     *                               not be checked because its issuer's keys could not be fetched
     */
    public Caller authenticate(String bearerToken, Optional<Principal> assertedEndUser,
                               Predicate<TrustedEngine> registered) {
        Issuer issuer = issuerNamedBy(bearerToken);
        String iss = issuer.policy().issuer();
        Jwt jwt;
        try {
            jwt = issuer.decoder().decode(bearerToken);
        } catch (JwtException e) {
            Optional<String> outage = issuer.fetches().outage(e);
            if (outage.isPresent()) {
                throw AuthenticationFailure.keysUnavailable("token from " + iss + " not checked, as the issuer's keys"
                        + " could not be fetched: " + outage.get(), e);
            }
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

    private static JwtDecoder decoder(IssuerPolicy policy, KeyFetches fetches, Duration clockSkew, Clock clock) {
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(policy.jwksUri().toURL(), new DefaultResourceRetriever(
                    JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT, (int) JWKS_READ_TIMEOUT.toMillis(),
                    JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT)).healthReporting(fetches).build();
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

    private record Issuer(IssuerPolicy policy, JwtDecoder decoder, KeyFetches fetches) {
    }

    /**
     * Tells an issuer's keys being out of reach from a bad token (backlog 90). Nimbus refuses a token whose
     * keys it could not get with a {@link JWKSetUnavailableException}: the fetch failed or timed out, the IdP
     * sent no usable key set, or the wait for another request's fetch ran out. Its rate limit refuses a
     * refetch with a {@link RateLimitReachedException} instead, which is the IdP's doing only when the last
     * fetch failed: an IdP that stays down would otherwise get every token past the limit's two fetches in 30
     * seconds refused as bad. After a good fetch it means the token names a key the issuer does not publish.
     * Nimbus reports each fetch it makes, the background ones ahead of the cache's expiry included.
     */
    private static final class KeyFetches
            implements HealthReportListener<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext> {

        /** Why the last fetch failed; null once one succeeds. */
        private volatile Exception lastFailure;

        @Override
        public void notify(HealthReport<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext> report) {
            lastFailure = report.getException();
        }

        /** Why the token's keys could not be had, when that and not the token is why it was not accepted. */
        Optional<String> outage(JwtException refusal) {
            Exception failure = lastFailure;
            if (refusal.getCause() instanceof JWKSetUnavailableException unavailable) {
                return Optional.of(unavailable.getMessage());
            }
            if (refusal.getCause() instanceof RateLimitReachedException && failure != null) {
                return Optional.of("no refetch allowed yet after the last one failed: " + failure.getMessage());
            }
            return Optional.empty();
        }
    }
}
