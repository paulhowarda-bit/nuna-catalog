package fidice.catalog.security;

import static fidice.catalog.security.TestIdp.CLOCK;
import static fidice.catalog.security.TestIdp.ENTRA_CLAIMS;
import static fidice.catalog.security.TestIdp.NOW;
import static fidice.catalog.security.TestIdp.SKEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import fidice.catalog.core.principal.Caller;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/** One issuer that allows RS256 only, with a 30-second clock skew. */
class TokenValidationTest {

    private static final String NO_MATCHING_KEY = "Another algorithm expected, or no matching key(s) found";

    private TestIdp entra;
    private RSAKey key;
    private TokenAuthenticator authenticator;

    @BeforeEach
    void start() throws Exception {
        entra = new TestIdp("https://login.example.test/entra/v2.0", "api://fidice-catalog");
        key = TestIdp.rsaKey("entra-1");
        entra.publish(key);
        authenticator = new TokenAuthenticator(List.of(entra.policy(ENTRA_CLAIMS, SignatureAlgorithm.RS256)), SKEW, CLOCK);
    }

    @AfterEach
    void stop() {
        entra.close();
    }

    @Test
    void acceptsATokenFromTheTrustedIssuer() throws Exception {
        Caller caller = authenticate(signed(person()));

        assertThat(caller.principal().id()).isEqualTo("alice@corp.example");
        assertThat(caller.principal().groups()).containsExactlyInAnyOrder("trading-readers", "trading-writers");
        assertThat(caller.engine()).isEqualTo("pyiceberg-client");
        assertThat(caller.trustedEngine()).isFalse();
        assertThat(caller.endUser()).isEmpty();
    }

    @Test
    void refusesAnIssuerOffTheAllowListWithoutFetchingAnyKeys() throws Exception {
        String token = signed(person().issuer("https://login.example.test/rogue/v2.0"));

        rejected(token).withMessageContaining("issuer https://login.example.test/rogue/v2.0 is not trusted");
        assertThat(entra.fetches()).isZero();
    }

    @Test
    void refusesATokenThatNamesNoIssuer() throws Exception {
        rejected(signed(person().issuer(null))).withMessageContaining("names no issuer");
    }

    @Test
    void refusesAMalformedToken() {
        rejected("not-a-token").withMessageContaining("malformed token");
        rejected("a.b.c").withMessageContaining("malformed token");
    }

    @Test
    void refusesAnEncryptedToken() throws Exception {
        EncryptedJWT token = new EncryptedJWT(new JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM),
                person().build());
        token.encrypt(new RSAEncrypter(key));

        rejected(token.serialize()).withMessageContaining("names no issuer");
    }

    @Test
    void refusesATokenForAnotherAudience() throws Exception {
        rejected(signed(person().audience("api://some-other-service"))).withMessageContaining("aud claim is not valid");
    }

    @Test
    void refusesATokenWithNoAudience() throws Exception {
        rejected(signed(person().audience((String) null))).withMessageContaining("aud claim is not valid");
    }

    @Test
    void acceptsATokenThatNamesSeveralAudiencesIncludingOurs() throws Exception {
        String token = signed(person().audience(List.of("api://some-other-service", entra.audience)));

        assertThat(authenticate(token).principal().id()).isEqualTo("alice@corp.example");
    }

    @Test
    void acceptsATokenThatExpiredWithinTheSkew() throws Exception {
        String token = signed(person().expirationTime(Date.from(NOW.minus(SKEW).plusSeconds(1))));

        assertThat(authenticate(token).principal().id()).isEqualTo("alice@corp.example");
    }

    @Test
    void refusesATokenThatExpiredBeyondTheSkew() throws Exception {
        String token = signed(person().expirationTime(Date.from(NOW.minus(SKEW).minusSeconds(1))));

        rejected(token).withMessageContaining("Jwt expired at");
    }

    @Test
    void acceptsATokenNotYetValidWithinTheSkew() throws Exception {
        String token = signed(person().notBeforeTime(Date.from(NOW.plus(SKEW).minusSeconds(1))));

        assertThat(authenticate(token).principal().id()).isEqualTo("alice@corp.example");
    }

    @Test
    void refusesATokenNotYetValidBeyondTheSkew() throws Exception {
        String token = signed(person().notBeforeTime(Date.from(NOW.plus(SKEW).plusSeconds(1))));

        rejected(token).withMessageContaining("Jwt used before");
    }

    @Test
    void refusesATokenThatNeverExpires() throws Exception {
        rejected(signed(person().expirationTime(null))).withMessageContaining("exp is required");
    }

    @Test
    void refusesAnUnsignedToken() {
        String token = new PlainJWT(person().build()).serialize();

        rejected(token).withMessageContaining("Unsupported algorithm of none");
    }

    @Test
    void refusesAnHmacTokenKeyedWithThePublishedPublicKey() throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(),
                person().build());
        token.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));

        rejected(token.serialize()).withMessageContaining(NO_MATCHING_KEY);
    }

    @Test
    void refusesAnAlgorithmTheIssuerDoesNotAllow() throws Exception {
        String token = TestIdp.sign(key, JWSAlgorithm.RS384, person().build());

        rejected(token).withMessageContaining(NO_MATCHING_KEY);
    }

    @Test
    void refusesATokenSignedWithAKeyTheIssuerNeverPublished() throws Exception {
        RSAKey forged = TestIdp.rsaKey(key.getKeyID());

        rejected(TestIdp.sign(forged, JWSAlgorithm.RS256, person().build())).withMessageContaining("Invalid signature");
    }

    private JWTClaimsSet.Builder person() {
        return entra.claims()
                .claim("upn", "alice@corp.example")
                .claim("groups", List.of("trading-readers", "trading-writers"))
                .claim("azp", "pyiceberg-client");
    }

    private String signed(JWTClaimsSet.Builder claims) throws Exception {
        return TestIdp.sign(key, JWSAlgorithm.RS256, claims.build());
    }

    private Caller authenticate(String token) {
        return authenticator.authenticate(token, Optional.empty(), Set.of()::contains);
    }

    private org.assertj.core.api.ThrowableAssertAlternative<AuthenticationFailure> rejected(String token) {
        ThrowingCallable call = () -> authenticate(token);
        return assertThatExceptionOfType(AuthenticationFailure.class).isThrownBy(call)
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(401));
    }
}
