package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.server.rest.CredentialVending;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Primary;

/**
 * A table load never answers 304 when the vended credentials expire within the refresh margin, and a
 * new credential epoch is a new ETag (D14). A stand-in vends credentials whose expiry the test moves;
 * {@code storage.VendingRouteTest} covers the same rule with real STS credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, SiloForTests.class, CredentialExpiryTest.Vending.class})
class CredentialExpiryTest {

    private static final AtomicReference<Instant> EXPIRES = new AtomicReference<>();
    private static final AtomicReference<String> EPOCH = new AtomicReference<>("1");

    @TestConfiguration
    static class Vending {

        @Bean
        @Primary
        CredentialVending expiringCredentials() {
            return request -> new CredentialVending.Vended(
                    Map.of(), List.of(), EPOCH.get(), Optional.of(EXPIRES.get()));
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void noNotModifiedNearExpiryAndANewEpochIsANewETag() throws Exception {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        EXPIRES.set(Instant.now().plus(Duration.ofHours(1)));
        String namespace = "expiry_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(send("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}", null).statusCode())
                .isEqualTo(200);
        String table = "/namespaces/" + namespace + "/tables/t";
        HttpResponse<String> created = send("POST", "/namespaces/" + namespace + "/tables", """
                {"name":"t","schema":{"type":"struct","schema-id":0,"fields":[
                  {"id":1,"name":"id","required":true,"type":"long"}]}}""", null);
        assertThat(created.statusCode()).isEqualTo(200);
        String etag = created.headers().firstValue("ETag").orElseThrow();

        assertThat(send("GET", table, null, etag).statusCode()).as("an hour left").isEqualTo(304);

        EXPIRES.set(Instant.now().plus(Duration.ofMinutes(1)));
        HttpResponse<String> nearExpiry = send("GET", table, null, etag);
        assertThat(nearExpiry.statusCode()).as("a minute left, inside the refresh margin").isEqualTo(200);
        assertThat(nearExpiry.headers().firstValue("ETag")).as("no ETag a client could reuse").isEmpty();

        EXPIRES.set(Instant.now().plus(Duration.ofHours(1)));
        EPOCH.set("2");
        HttpResponse<String> newEpoch = send("GET", table, null, etag);
        assertThat(newEpoch.statusCode()).as("new credentials").isEqualTo(200);
        assertThat(newEpoch.headers().firstValue("ETag")).isPresent().get().isNotEqualTo(etag);
    }

    private HttpResponse<String> send(String method, String path, String body, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/trading" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", IssuerForTests.bearer());
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
