package nuna.catalog.security;

import static nuna.catalog.security.TestIdp.CLOCK;
import static nuna.catalog.security.TestIdp.ENTRA_CLAIMS;
import static nuna.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * How the issuer's JWKS is fetched: once, again on rotation, and never on demand from a stranger; and that keys
 * which cannot be fetched are an outage (503), never a bad token (401), while a bad token is still 401.
 */
class JwksRefreshTest {

    private TestIdp entra;
    private TokenAuthenticator authenticator;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://nuna-catalog");
        authenticator = new TokenAuthenticator(List.of(entra.policy(ENTRA_CLAIMS, SignatureAlgorithm.RS256)), SKEW, CLOCK);
    }

    @AfterEach
    void stop() {
        entra.close();
    }

    @Test
    void keysAreFetchedOnFirstUseAndThenCached() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        assertThat(entra.fetches()).isZero();

        for (int i = 0; i < 5; i++) {
            authenticate(signedBy(key));
        }

        assertThat(entra.fetches()).isEqualTo(1);
    }

    @Test
    void aRotatedKeyIsPickedUpAndTheRetiredOneIsNot() throws Exception {
        RSAKey retired = TestIdp.rsaKey("entra-2026-10");
        RSAKey current = TestIdp.rsaKey("entra-2026-11");
        entra.publish(retired);
        authenticate(signedBy(retired));

        entra.publish(current);

        assertThat(authenticate(signedBy(current))).isEqualTo("alice@corp.example");
        assertThat(entra.fetches()).isEqualTo(2);
        assertThatExceptionOfType(AuthenticationFailure.class).isThrownBy(() -> authenticate(signedBy(retired)));
    }

    @Test
    void unknownKeyIdsCannotForceAFetchEachTime() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticate(signedBy(key));

        for (int i = 0; i < 10; i++) {
            String madeUp = signedBy(TestIdp.rsaKey("made-up-" + i));
            // Bad tokens, the refetches the rate limit refused included: the IdP answered the last fetch.
            assertThat(status(madeUp)).as("made-up key id %d", i).isEqualTo(401);
        }

        // The first fetch, then the one refetch the 30-second window allows.
        assertThat(entra.fetches()).isEqualTo(2);
        assertThat(authenticate(signedBy(key))).isEqualTo("alice@corp.example");
    }

    /** A token bad on its own is refused as bad while the issuer's keys are reachable (backlog 90). */
    @Test
    void aTokenBadOnItsOwnIs401WhileTheKeysAreReachable() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);

        assertBadTokensAre401(key);

        assertThat(authenticate(signedBy(key))).isEqualTo("alice@corp.example");
        assertThat(entra.fetches()).as("bad tokens forced no fetch but the first").isEqualTo(1);
    }

    /** Keys already held still check tokens while the IdP is down: a bad token among them is 401, a good one passes. */
    @Test
    void heldKeysStillCheckTokensWhileTheIdpIsDown() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticate(signedBy(key));
        entra.failFetches();

        assertBadTokensAre401(key);

        assertThat(authenticate(signedBy(key))).isEqualTo("alice@corp.example");
        assertThat(entra.fetches()).isEqualTo(1);
    }

    /**
     * A key the catalog does not hold and cannot fetch might be a rotated one: the token is not known to be bad,
     * so 503 (backlog 90), also once the rate limit refuses the refetch.
     */
    @Test
    void anUnheldKeyWhileTheIdpIsDownIsAnOutage() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticate(signedBy(key));
        entra.failFetches();

        for (int i = 0; i < 3; i++) {
            assertThat(status(signedBy(TestIdp.rsaKey("rotated-" + i)))).as("unheld key %d", i).isEqualTo(503);
        }

        assertThat(entra.fetches()).as("the first fetch and the one refetch allowed").isEqualTo(2);
    }

    /**
     * Nimbus gives up on a JWKS fetch after 500 ms by default. The overnight run of 2026-11-18 refused a valid
     * token with "Couldn't retrieve JWK set from URL: Read timed out" when its in-JVM IdP was that slow under load
     * (backlog 60); a slow IdP at work would do the same to every caller of a fresh node.
     */
    @Test
    void aSlowJwksIsWaitedForNotTakenForAnInvalidToken() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        entra.slowFetches(Duration.ofSeconds(1));

        assertThat(authenticate(signedBy(key))).isEqualTo("alice@corp.example");
        assertThat(entra.fetches()).isEqualTo(1);
    }

    /**
     * Keys that cannot be fetched mean no token is accepted, and none is refused as bad either: 503, so the client
     * sends its good token again later instead of throwing it away (D16, amended 2026-11-20; backlog 90).
     */
    @Test
    void anUnreachableJwksMeansNoTokenIsAccepted() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        entra.failFetches();

        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticate(signedBy(key)))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(503))
                .withMessageContaining("could not be fetched")
                .withMessageContaining("HTTP 503");
        assertThat(entra.fetches()).isEqualTo(1);
    }

    /** An IdP slower than the read timeout is an outage too, not a bad token (backlog 90). */
    @Test
    void aJwksSlowerThanTheReadTimeoutIsAnOutage() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        // Past the 5 s read timeout, with a second's margin for a loaded JVM.
        entra.slowFetches(Duration.ofSeconds(6));

        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticate(signedBy(key)))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(503))
                .withMessageContaining("Read timed out");
        assertThat(entra.fetches()).isEqualTo(1);
    }

    /**
     * Past the rate limit's two fetches in 30 seconds, Nimbus refuses a refetch without asking the IdP. While the
     * IdP stays down that is still an outage: every request is 503, none 401, and none accepted (backlog 90).
     */
    @Test
    void anIdpThatStaysDownIsAnOutagePastTheRefetchLimit() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        entra.failFetches();

        for (int i = 0; i < 5; i++) {
            assertThat(status(signedBy(key))).as("request %d", i).isEqualTo(503);
        }

        assertThat(entra.fetches()).as("the rate limit held").isEqualTo(2);
    }

    /** Wrong signature against a held key, expired, another audience, an untrusted issuer: each is 401. */
    private void assertBadTokensAre401(RSAKey key) throws Exception {
        Map<String, String> bad = Map.of(
                "wrong signature", TestIdp.sign(TestIdp.rsaKey(key.getKeyID()), JWSAlgorithm.RS256, alice().build()),
                "expired", TestIdp.sign(key, JWSAlgorithm.RS256, alice()
                        .expirationTime(Date.from(TestIdp.NOW.minus(SKEW).minusSeconds(1))).build()),
                "another audience", TestIdp.sign(key, JWSAlgorithm.RS256, alice().audience("api://other").build()),
                "untrusted issuer", TestIdp.sign(key, JWSAlgorithm.RS256, alice()
                        .issuer("https://login.example.test/stranger").build()));
        bad.forEach((what, token) -> assertThat(status(token)).as(what).isEqualTo(401));
    }

    /** The status of the refusal; fails if the token is accepted. */
    private int status(String token) {
        try {
            authenticate(token);
        } catch (AuthenticationFailure failure) {
            return failure.status();
        }
        throw new AssertionError("token accepted");
    }

    private JWTClaimsSet.Builder alice() {
        return entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("azp", "pyiceberg-client");
    }

    private String signedBy(RSAKey key) throws Exception {
        return TestIdp.sign(key, JWSAlgorithm.RS256, alice().build());
    }

    private String authenticate(String token) {
        return authenticator.authenticate(token, Optional.empty(), Set.of()::contains).principal().id();
    }
}
