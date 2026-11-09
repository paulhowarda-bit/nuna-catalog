package fidice.catalog.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import fidice.catalog.security.TestIdp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One trusted token issuer for server tests that are not about authentication: a local JWKS endpoint, its
 * {@code fidice.security.issuers} settings, and tokens signed with its key at the real current time. Used as
 * {@code @ContextConfiguration(initializers = IssuerForTests.class)}: an initializer, not a property registrar
 * bean, because the authentication filter binds these settings while the web server starts, before such beans run.
 */
public class IssuerForTests implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ISSUER = "https://idp.test/fidice";
    public static final String AUDIENCE = "fidice-catalog";
    /** The group server tests act as; {@link #grant} gives it privileges. */
    public static final String GROUP = "catalog-testers";

    private static final TestIdp IDP;
    private static final RSAKey KEY;

    static {
        try {
            IDP = new TestIdp(ISSUER, AUDIENCE);
            KEY = TestIdp.rsaKey("server-tests");
            IDP.publish(KEY);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trusted-test-issuer", Map.of(
                "fidice.security.issuers[0].issuer", ISSUER,
                "fidice.security.issuers[0].jwks-uri", IDP.jwksUri().toString(),
                "fidice.security.issuers[0].audiences", AUDIENCE,
                "fidice.security.issuers[0].algorithms", "RS256",
                "fidice.security.issuers[0].user-id-claim", "sub",
                "fidice.security.issuers[0].groups-claim", "groups",
                "fidice.security.issuers[0].client-id-claim", "azp")));
    }

    /** An {@code Authorization} header value for {@code user} in {@link #GROUP}, valid for an hour. */
    public static String bearer() {
        return bearer("tester", GROUP);
    }

    public static String bearer(String user, String... groups) {
        return sign(claims(user, "server-tests", groups).build());
    }

    /** Claims of a token this issuer would sign now for {@code user} using {@code client}, valid for an hour. */
    public static JWTClaimsSet.Builder claims(String user, String client, String... groups) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(user)
                .claim("groups", List.of(groups))
                .claim("azp", client)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(3600)));
    }

    /** An {@code Authorization} header value with these claims, signed with this issuer's key. */
    public static String sign(JWTClaimsSet claims) {
        try {
            return "Bearer " + TestIdp.sign(KEY, JWSAlgorithm.RS256, claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Grants {@code privileges} on the whole catalog to {@code group}, straight into the store's table. */
    public static void grant(JdbcTemplate jdbc, String catalogNameNorm, String group, String... privileges) {
        UUID catalog = jdbc.queryForObject("SELECT id FROM catalog WHERE name_norm = ?", UUID.class, catalogNameNorm);
        for (String privilege : privileges) {
            jdbc.update("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) VALUES (?, 'CATALOG', ?, ?, ?, 'server-tests', now())"
                    + " ON CONFLICT DO NOTHING", catalog, catalog, group, privilege);
        }
    }
}
