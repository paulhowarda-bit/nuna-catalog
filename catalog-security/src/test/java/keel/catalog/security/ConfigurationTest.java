package keel.catalog.security;

import static keel.catalog.security.TestIdp.CLOCK;
import static keel.catalog.security.TestIdp.ENTRA_CLAIMS;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/** Issuer configuration that would weaken validation is refused when the authenticator is built. */
class ConfigurationTest {

    private static final String ISSUER = "https://login.example.test/entra/v2.0";
    private static final URI JWKS = URI.create("http://127.0.0.1:1/keys");

    @Test
    void anIssuerMustAcceptSomeAudience() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IssuerPolicy(ISSUER, JWKS, Set.of(), Set.of(SignatureAlgorithm.RS256), ENTRA_CLAIMS))
                .withMessageContaining("accepts no audience");
    }

    @Test
    void anEmptyAlgorithmListIsRefusedRatherThanDefaulted() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IssuerPolicy(ISSUER, JWKS, Set.of("api://keel-catalog"), Set.of(), ENTRA_CLAIMS))
                .withMessageContaining("allows no signature algorithm");
    }

    @Test
    void theClockSkewCannotBeNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenAuthenticator(List.of(policy()), Duration.ofSeconds(-1), CLOCK))
                .withMessageContaining("must not be negative");
    }

    @Test
    void oneIssuerCannotHaveTwoPolicies() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new TokenAuthenticator(List.of(policy(), policy()), Duration.ofSeconds(30), CLOCK))
                .withMessageContaining("Duplicate key " + ISSUER);
    }

    private static IssuerPolicy policy() {
        return new IssuerPolicy(ISSUER, JWKS, Set.of("api://keel-catalog"), Set.of(SignatureAlgorithm.RS256), ENTRA_CLAIMS);
    }
}
