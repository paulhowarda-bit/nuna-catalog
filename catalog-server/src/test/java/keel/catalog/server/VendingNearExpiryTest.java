package keel.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The 304 rule of D14 with real STS credentials: when every vended credential expires within the refresh
 * margin (here a 20-minute margin over 15-minute credentials), no load is cacheable, so none carries an
 * ETag and none answers 304, and each load carries fresh credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keel.rest.credential-refresh-margin=20m", "keel.storage.credential-lifetime=15m"})
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, AuditSinkForTests.class})
class VendingNearExpiryTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void noNotModifiedWhenCredentialsExpireWithinTheMargin() throws Exception {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        String namespace = "near_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}", null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> created = send("POST", "/namespaces/" + namespace + "/tables", """
                {"name":"t","schema":{"type":"struct","schema-id":0,"fields":[
                  {"id":1,"name":"id","required":true,"type":"long"}]}}""", null);
        assertThat(created.statusCode()).isEqualTo(200);
        String etag = created.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> first = send("GET", "/namespaces/" + namespace + "/tables/t", null, etag);
        HttpResponse<String> second = send("GET", "/namespaces/" + namespace + "/tables/t", null, etag);

        for (HttpResponse<String> load : java.util.List.of(first, second)) {
            assertThat(load.statusCode()).isEqualTo(200);
            assertThat(load.headers().firstValue("ETag")).as("no ETag a client could reuse").isEmpty();
        }
        Map<String, String> a = JsonPath.read(first.body(), "$.config");
        Map<String, String> b = JsonPath.read(second.body(), "$.config");
        assertThat(a.get("s3.access-key-id")).as("never reused this close to expiry")
                .isNotEqualTo(b.get("s3.access-key-id"));
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
