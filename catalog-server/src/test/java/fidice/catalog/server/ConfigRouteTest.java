package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, SiloForTests.class})
class ConfigRouteTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedCatalog() {
        jdbc.update("INSERT INTO catalog (id, name_norm, name) VALUES (?, 'trading', 'Trading') "
                + "ON CONFLICT (name_norm) DO NOTHING", UUID.randomUUID());
    }

    @Test
    void warehouseIsMatchedCaseInsensitivelyAndBecomesThePrefix() throws Exception {
        HttpResponse<String> response = get("/v1/config?warehouse=TRADING");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.overrides.prefix")).isEqualTo("trading");
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$.defaults")).isEmpty();
        assertThat(JsonPath.<List<String>>read(response.body(), "$.endpoints"))
                .containsExactlyElementsOf(AdvertisedEndpoints.ALL)
                .contains("HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}");
        assertThat(JsonPath.<String>read(response.body(), "$.idempotency-key-lifetime")).isEqualTo("PT24H");
    }

    @Test
    void missingWarehouseIsBadRequest() throws Exception {
        HttpResponse<String> response = get("/v1/config");

        assertError(response, 400, "BadRequestException");
    }

    @Test
    void unknownWarehouseIsNoSuchWarehouse() throws Exception {
        HttpResponse<String> response = get("/v1/config?warehouse=nowhere");

        assertError(response, 404, "NoSuchWarehouseException");
    }

    @Test
    void unknownRouteIsAnIcebergError() throws Exception {
        HttpResponse<String> response = get("/v1/trading/nowhere");

        assertError(response, 404, "NotFoundException");
    }

    private void assertError(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.error.code")).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo(type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", IssuerForTests.bearer()).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
