package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/** {@code warehouse} is optional in the spec: with a default set, a client that names none gets that catalog. */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
@TestPropertySource(properties = "nuna.rest.default-warehouse=Trading")
class ConfigRouteDefaultWarehouseTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /** The tester's group holds something in trading: a default it held nothing in would be hidden (item 82). */
    @BeforeEach
    void holdSomethingInTrading() {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "describe");
    }

    @Test
    void aClientThatNamesNoWarehouseGetsTheDefault() throws Exception {
        HttpResponse<String> response = get("/v1/config");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.overrides.prefix")).isEqualTo("trading");
    }

    @Test
    void aWarehouseTheClientNamesStillWins() throws Exception {
        HttpResponse<String> response = get("/v1/config?warehouse=nowhere");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo("NoSuchWarehouseException");
    }

    @Test
    void aDefaultTheCallerHoldsNothingInIsAnsweredAsOneThatDoesNotExist() throws Exception {
        // D18, amended 2026-11-20 (item 82): the 404 a missing warehouse gets, but for the name.
        String outsider = IssuerForTests.bearer("otto", "outsiders");
        HttpResponse<String> hidden = get("/v1/config", outsider);
        HttpResponse<String> missing = get("/v1/config?warehouse=nowhere", outsider);

        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(hidden.body(), "$.error.type")).isEqualTo("NoSuchWarehouseException");
        assertThat(hidden.body().replace("Trading", "nowhere")).isEqualTo(missing.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, IssuerForTests.bearer());
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
