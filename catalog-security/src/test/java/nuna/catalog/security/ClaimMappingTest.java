package nuna.catalog.security;

import static nuna.catalog.security.TestIdp.CLOCK;
import static nuna.catalog.security.TestIdp.ENTRA_CLAIMS;
import static nuna.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.core.principal.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/** The Entra-style mapping: {@code upn} names the principal, {@code groups} its groups, {@code azp} the client. */
class ClaimMappingTest {

    private TestIdp entra;
    private RSAKey key;
    private TokenAuthenticator authenticator;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://nuna-catalog");
        key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticator = new TokenAuthenticator(List.of(entra.policy(ENTRA_CLAIMS, SignatureAlgorithm.RS256)), SKEW, CLOCK);
    }

    @AfterEach
    void stop() {
        entra.close();
    }

    @Test
    void aTokenWithoutTheUserIdClaimNamesNoOne() throws Exception {
        String token = signed(entra.claims().subject("8f1c0d2e").claim("azp", "pyiceberg-client"));

        rejected(token).withMessageContaining("has no upn claim");
    }

    @Test
    void aBlankUserIdNamesNoOne() throws Exception {
        String token = signed(entra.claims().claim("upn", " ").claim("azp", "pyiceberg-client"));

        rejected(token).withMessageContaining("has no upn claim");
    }

    @Test
    void aTokenWithoutTheClientIdClaimIsRefused() throws Exception {
        String token = signed(entra.claims().claim("upn", "alice@corp.example"));

        rejected(token).withMessageContaining("has no azp claim");
    }

    @Test
    void aTokenWithoutGroupsIsAPrincipalWithNoGroups() throws Exception {
        Principal principal = principal(entra.claims().claim("upn", "alice@corp.example").claim("azp", "pyiceberg-client"));

        assertThat(principal.groups()).isEmpty();
    }

    @Test
    void aSingleGroupGivenAsAStringIsOneGroup() throws Exception {
        Principal principal = principal(entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("groups", "trading-readers")
                .claim("azp", "pyiceberg-client"));

        assertThat(principal.groups()).containsExactly("trading-readers");
    }

    @Test
    void groupsMovedOutOfTheTokenAreRefusedRatherThanReadAsNone() throws Exception {
        String token = signed(entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("azp", "pyiceberg-client")
                .claim("_claim_names", Map.of("groups", "src1"))
                .claim("_claim_sources", Map.of("src1", Map.of("endpoint", "https://graph.example.test/getMemberObjects"))));

        rejected(token).withMessageContaining("group overage");
    }

    @Test
    void anotherMovedClaimDoesNotHideTheGroups() throws Exception {
        Principal principal = principal(entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("groups", List.of("trading-readers"))
                .claim("azp", "pyiceberg-client")
                .claim("_claim_names", Map.of("address", "src1"))
                .claim("_claim_sources", Map.of("src1", Map.of("endpoint", "https://userinfo.example.test/"))));

        assertThat(principal.groups()).containsExactly("trading-readers");
    }

    private Principal principal(JWTClaimsSet.Builder claims) throws Exception {
        return authenticator.authenticate(signed(claims), Optional.empty(), Set.of()::contains).principal();
    }

    private String signed(JWTClaimsSet.Builder claims) throws Exception {
        return TestIdp.sign(key, JWSAlgorithm.RS256, claims.build());
    }

    private org.assertj.core.api.ThrowableAssertAlternative<AuthenticationFailure> rejected(String token) {
        return assertThatExceptionOfType(AuthenticationFailure.class)
                .isThrownBy(() -> authenticator.authenticate(token, Optional.empty(), Set.of()::contains))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(401));
    }
}
