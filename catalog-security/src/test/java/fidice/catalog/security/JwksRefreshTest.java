package fidice.catalog.security;

import static fidice.catalog.security.TestIdp.CLOCK;
import static fidice.catalog.security.TestIdp.ENTRA_CLAIMS;
import static fidice.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/** How the issuer's JWKS is fetched: once, again on rotation, and never on demand from a stranger. */
class JwksRefreshTest {

    private TestIdp entra;
    private TokenAuthenticator authenticator;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://fidice-catalog");
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
            assertThatExceptionOfType(AuthenticationFailure.class).isThrownBy(() -> authenticate(madeUp));
        }

        // The first fetch, then the one refetch the 30-second window allows.
        assertThat(entra.fetches()).isEqualTo(2);
        assertThat(authenticate(signedBy(key))).isEqualTo("alice@corp.example");
    }

    @Test
    void anUnreachableJwksMeansNoTokenIsAccepted() throws Exception {
        RSAKey key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        entra.failFetches();

        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticate(signedBy(key)))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(401));
        assertThat(entra.fetches()).isPositive();
    }

    private String signedBy(RSAKey key) throws Exception {
        return TestIdp.sign(key, JWSAlgorithm.RS256, entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("azp", "pyiceberg-client")
                .build());
    }

    private String authenticate(String token) {
        return authenticator.authenticate(token, Optional.empty(), Set.of()::contains).principal().id();
    }
}
