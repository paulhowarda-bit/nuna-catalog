package nuna.catalog.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * A stand-in identity provider: signing keys made in the test and a JWKS served over HTTP from
 * 127.0.0.1, counting every fetch. The issuer string is unrelated to the server's address, as with
 * a real IdP whose JWKS URI is configured rather than derived from the token.
 */
public final class TestIdp implements AutoCloseable {

    public static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Not Spring's 60-second default, so a check that ignores the configured skew shows up. */
    public static final Duration SKEW = Duration.ofSeconds(30);

    public static final ClaimMapping ENTRA_CLAIMS = new ClaimMapping("upn", "groups", "azp");
    public static final ClaimMapping PING_CLAIMS = new ClaimMapping("sub", "memberOf", "client_id");

    public final String issuer;
    public final String audience;
    private final HttpServer server;
    private final AtomicInteger fetches = new AtomicInteger();
    private volatile String jwks = new JWKSet().toString();
    private volatile int status = 200;

    public TestIdp(String issuer, String audience) throws IOException {
        this.issuer = issuer;
        this.audience = audience;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/keys", exchange -> {
            fetches.incrementAndGet();
            byte[] body = jwks.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public URI jwksUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
    }

    public IssuerPolicy policy(ClaimMapping claims, SignatureAlgorithm... algorithms) {
        return new IssuerPolicy(issuer, jwksUri(), Set.of(audience), Set.of(algorithms), claims);
    }

    /** Publishes the public halves of these keys, replacing whatever was published before. */
    public void publish(JWK... keys) {
        jwks = new JWKSet(List.of(keys)).toPublicJWKSet().toString();
    }

    public void failFetches() {
        status = 503;
    }

    public int fetches() {
        return fetches.get();
    }

    /** Claims for a token this IdP would issue now: its issuer and audience, valid for ten minutes. */
    public JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(600)));
    }

    public static RSAKey rsaKey(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyID(keyId).generate();
    }

    public static ECKey ecKey(String keyId) throws Exception {
        return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
    }

    public static String sign(JWK key, JWSAlgorithm algorithm, JWTClaimsSet claims) throws Exception {
        JWSSigner signer = key instanceof RSAKey rsa ? new RSASSASigner(rsa) : new ECDSASigner((ECKey) key);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
