package keel.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/** {@code warehouse} is optional in the spec: with a default set, a client that names none gets that catalog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
@TestPropertySource(properties = "keel.rest.default-warehouse=Trading")
class ConfigRouteDefaultWarehouseTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

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

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", IssuerForTests.bearer()).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
