package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import fidice.catalog.audit.AuthFailure;
import fidice.catalog.audit.Denial;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Two real OIDC issuers, Keycloak realms standing in for Entra ID and Ping (D16, D17a): each has its own claim
 * mapping, and both name the same directory user, who must come out as one principal. A third realm the catalog
 * does not trust issues otherwise acceptable tokens. Real Entra and Ping tenants are out of scope (work hardware).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TwoIssuersTest.Realms.class)
@Import({PostgresForTests.class, S3ForTests.class, AuditSinkForTests.class})
class TwoIssuersTest {

    /** The directory id both realms carry for the same person, in different claims. */
    private static final String DIRECTORY_ID = "d1001";

    /** Starts Keycloak once, imports the realms, and trusts two of them. */
    static final class Realms implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                DockerImageName.parse(System.getProperty("keycloak.image")))
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m")
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/entra-realm.json"),
                        "/opt/keycloak/data/import/entra-realm.json")
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/ping-realm.json"),
                        "/opt/keycloak/data/import/ping-realm.json")
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/rogue-realm.json"),
                        "/opt/keycloak/data/import/rogue-realm.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/rogue").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

        static String base() {
            return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        }

        static String issuer(String realm) {
            return base() + "/realms/" + realm;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (!KEYCLOAK.isRunning()) {
                KEYCLOAK.start();
            }
            Map<String, Object> properties = new HashMap<>();
            issuer(properties, 0, "entra", "oid", "groups", "azp");
            issuer(properties, 1, "ping", "uid", "memberOf", "client_id");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("keycloak-issuers",
                    properties));
        }

        private static void issuer(Map<String, Object> properties, int index, String realm, String userId,
                                   String groups, String clientId) {
            String prefix = "fidice.security.issuers[" + index + "].";
            properties.put(prefix + "issuer", issuer(realm));
            properties.put(prefix + "jwks-uri", issuer(realm) + "/protocol/openid-connect/certs");
            properties.put(prefix + "audiences", "fidice-catalog");
            properties.put(prefix + "algorithms", "RS256");
            properties.put(prefix + "user-id-claim", userId);
            properties.put(prefix + "groups-claim", groups);
            properties.put(prefix + "client-id-claim", clientId);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditSinkForTests.Sink audit;

    @BeforeEach
    void seed() {
        for (String catalog : List.of("markets", "locked")) {
            jdbc.update("INSERT INTO catalog (id, name_norm, name) VALUES (?, ?, ?) ON CONFLICT (name_norm) DO NOTHING",
                    UUID.randomUUID(), catalog, catalog);
        }
        IssuerForTests.grant(jdbc, "markets", "traders", "create");
        audit.clear();
    }

    @Test
    void bothIssuersAuthenticateTheSameDirectoryUserAsOnePrincipal() throws Exception {
        String entra = token("entra", "trino-entra");
        String ping = token("ping", "trino-ping");
        String namespace = "two_issuers_" + UUID.randomUUID().toString().replace("-", "");

        assertThat(send(entra, "POST", "/v1/markets/namespaces", "{\"namespace\":[\"" + namespace + "\"]}")
                .statusCode()).as("Entra-like token, groups claim").isEqualTo(200);
        assertThat(send(ping, "GET", "/v1/markets/namespaces/" + namespace, null).statusCode())
                .as("Ping-like token, memberOf claim").isEqualTo(200);

        assertThat(send(entra, "GET", "/v1/locked/namespaces", null).statusCode()).isEqualTo(403);
        assertThat(send(ping, "GET", "/v1/locked/namespaces", null).statusCode()).isEqualTo(403);
        List<Denial> denials = audit.denials();
        assertThat(denials).hasSize(2);
        assertThat(denials).extracting(d -> d.actor().principal()).containsOnly(DIRECTORY_ID);
        assertThat(denials).extracting(d -> d.actor().engine()).containsExactly("trino-entra", "trino-ping");
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() throws Exception {
        String portal = token("entra", "portal");
        assertThat(send(portal, "GET", "/v1/markets/namespaces", null).statusCode()).isEqualTo(401);
        AuthFailure failure = audit.authFailures().getLast();
        assertThat(failure.issuer()).isEqualTo(Realms.issuer("entra"));
        assertThat(failure.claimedSubject()).isEqualTo(DIRECTORY_ID);
        assertThat(failure.reason()).containsIgnoringCase("aud");
    }

    @Test
    void aTokenFromAnUntrustedIssuerIsRefused() throws Exception {
        String rogue = token("rogue", "trino-rogue");
        assertThat(send(rogue, "GET", "/v1/markets/namespaces", null).statusCode()).isEqualTo(401);
        AuthFailure failure = audit.authFailures().getLast();
        assertThat(failure.issuer()).isEqualTo(Realms.issuer("rogue"));
        assertThat(failure.reason()).contains("not trusted");
    }

    /** A token from the realm's token endpoint, by the password grant for the shared directory user. */
    private String token(String realm, String client) throws Exception {
        String form = "grant_type=password&scope=openid&client_id=" + client + "&username=" + DIRECTORY_ID
                + "&password=" + URLEncoder.encode("pw-" + DIRECTORY_ID, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(Realms.issuer(realm) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return "Bearer " + JsonPath.<String>read(response.body(), "$.access_token");
    }

    private HttpResponse<String> send(String bearer, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
