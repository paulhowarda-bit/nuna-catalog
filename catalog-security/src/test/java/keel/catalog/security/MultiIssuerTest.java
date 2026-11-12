package keel.catalog.security;

import static keel.catalog.security.TestIdp.CLOCK;
import static keel.catalog.security.TestIdp.ENTRA_CLAIMS;
import static keel.catalog.security.TestIdp.PING_CLAIMS;
import static keel.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import keel.catalog.core.principal.Caller;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * Two issuers standing in for Entra (RSA keys, {@code upn}/{@code groups}/{@code azp}) and Ping
 * (EC keys, {@code sub}/{@code memberOf}/{@code client_id}), each with its own JWKS (D17a).
 */
class MultiIssuerTest {

    private TestIdp entra;
    private TestIdp ping;
    private RSAKey entraKey;
    private ECKey pingKey;
    private TokenAuthenticator authenticator;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://keel-catalog");
        ping = new TestIdp("https://sso.example.test/ping", "keel-catalog");
        entraKey = TestIdp.rsaKey("entra-1");
        pingKey = TestIdp.ecKey("ping-1");
        entra.publish(entraKey);
        ping.publish(pingKey);
        authenticator = new TokenAuthenticator(List.of(
                entra.policy(ENTRA_CLAIMS, SignatureAlgorithm.RS256),
                ping.policy(PING_CLAIMS, SignatureAlgorithm.ES256, SignatureAlgorithm.RS256)), SKEW, CLOCK);
    }

    @AfterEach
    void stop() {
        entra.close();
        ping.close();
    }

    @Test
    void onePersonIsOnePrincipalWhicheverIssuerNamedThem() throws Exception {
        String fromEntra = TestIdp.sign(entraKey, JWSAlgorithm.RS256, entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("groups", List.of("trading-readers"))
                .claim("azp", "notebook-client")
                .build());
        String fromPing = TestIdp.sign(pingKey, JWSAlgorithm.ES256, ping.claims()
                .subject("alice@corp.example")
                .claim("memberOf", List.of("trading-readers"))
                .claim("client_id", "legacy-bi-client")
                .build());

        Caller viaEntra = authenticate(fromEntra);
        Caller viaPing = authenticate(fromPing);

        assertThat(viaPing.principal()).isEqualTo(viaEntra.principal());
        assertThat(viaEntra.engine()).isEqualTo("notebook-client");
        assertThat(viaPing.engine()).isEqualTo("legacy-bi-client");
    }

    @Test
    void anIssuerAcceptsOnlyKeysFromItsOwnJwks() throws Exception {
        String pingTokenSignedByEntraKey = TestIdp.sign(entraKey, JWSAlgorithm.RS256, ping.claims()
                .subject("alice@corp.example")
                .claim("client_id", "legacy-bi-client")
                .build());

        assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticate(pingTokenSignedByEntraKey))
                .withMessageContaining("no matching key(s) found");
        assertThat(entra.fetches()).isZero();
    }

    private Caller authenticate(String token) {
        return authenticator.authenticate(token, Optional.empty(), Set.of()::contains);
    }
}
