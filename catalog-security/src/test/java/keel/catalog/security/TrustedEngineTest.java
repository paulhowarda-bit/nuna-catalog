package keel.catalog.security;

import static keel.catalog.security.TestIdp.CLOCK;
import static keel.catalog.security.TestIdp.ENTRA_CLAIMS;
import static keel.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import keel.catalog.core.principal.Caller;
import keel.catalog.core.principal.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/** A shared engine authenticates as itself and asserts the end user it acts for (D17). */
class TrustedEngineTest {

    private static final Principal ALICE = new Principal("alice@corp.example", Set.of("trading-readers"));

    private TestIdp entra;
    private RSAKey key;
    private TokenAuthenticator authenticator;
    private String trinoToken;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://keel-catalog");
        key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticator = new TokenAuthenticator(List.of(entra.policy(ENTRA_CLAIMS, SignatureAlgorithm.RS256)), SKEW, CLOCK);
        trinoToken = TestIdp.sign(key, JWSAlgorithm.RS256, entra.claims()
                .claim("upn", "svc-trino@corp.example")
                .claim("groups", List.of("platform-engines"))
                .claim("azp", "trino-client")
                .build());
    }

    @AfterEach
    void stop() {
        entra.close();
    }

    @Test
    void aRegisteredEngineActsForTheEndUserAndBothAreKept() {
        Caller caller = authenticator.authenticate(trinoToken, Optional.of(ALICE),
                Set.of(new TrustedEngine(entra.issuer, "trino-client"))::contains);

        assertThat(caller.principal().id()).isEqualTo("svc-trino@corp.example");
        assertThat(caller.engine()).isEqualTo("trino-client");
        assertThat(caller.trustedEngine()).isTrue();
        assertThat(caller.endUser()).contains(ALICE);
        assertThat(caller.subject()).isEqualTo(ALICE);
    }

    @Test
    void aRegisteredEngineThatAssertsNoOneActsAsItself() {
        Caller caller = authenticator.authenticate(trinoToken, Optional.empty(),
                Set.of(new TrustedEngine(entra.issuer, "trino-client"))::contains);

        assertThat(caller.trustedEngine()).isTrue();
        assertThat(caller.endUser()).isEmpty();
        assertThat(caller.subject().id()).isEqualTo("svc-trino@corp.example");
    }

    @Test
    void anUnregisteredClientMayNotActForAnotherUser() {
        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticator.authenticate(trinoToken, Optional.of(ALICE),
                        Set.of(new TrustedEngine(entra.issuer, "spark-platform-client"))::contains))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(403))
                .withMessageContaining("client trino-client of " + entra.issuer + " is not a registered trusted engine");
    }

    @Test
    void registrationBelongsToOneIssuer() {
        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticator.authenticate(trinoToken, Optional.of(ALICE),
                        Set.of(new TrustedEngine("https://sso.example.test/ping", "trino-client"))::contains))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(403));
    }

    @Test
    void anUnregisteredClientThatAssertsNoOneIsItself() {
        Caller caller = authenticator.authenticate(trinoToken, Optional.empty(), Set.of()::contains);

        assertThat(caller.trustedEngine()).isFalse();
        assertThat(caller.subject().id()).isEqualTo("svc-trino@corp.example");
    }
}
