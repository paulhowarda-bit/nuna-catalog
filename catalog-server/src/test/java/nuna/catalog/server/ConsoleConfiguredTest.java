package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/** The console with OIDC and the development token box switched on: what reaches the browser, and the policy. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nuna.ui.dev-token-login=true",
        "nuna.ui.oidc.issuer=https://login.example.com/tenant-1/v2.0",
        "nuna.ui.oidc.client-id=nuna-console",
        "nuna.ui.oidc.scopes=openid,api://nuna-catalog/catalog.access",
        "nuna.ui.oidc.name= the example sign-in ",
        "nuna.ui.docs-url=https://docs.nuna.example/catalog",
        "nuna.ui.grafana-url=https://grafana.nuna.example",
})
@Tag("heavy")
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
class ConsoleConfiguredTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void configCarriesTheSignInSettingsAndNothingElse() throws Exception {
        HttpResponse<String> response = get("/ui/config.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$")).containsOnlyKeys("dev-token-login", "oidc",
                "docs-url", "grafana-url", "brand");
        assertThat(JsonPath.<Object>read(response.body(), "$.brand")).as("no brand pack set").isNull();
        assertThat(JsonPath.<String>read(response.body(), "$['grafana-url']")).isEqualTo("https://grafana.nuna.example");
        assertThat(JsonPath.<Boolean>read(response.body(), "$['dev-token-login']")).isTrue();
        assertThat(JsonPath.<String>read(response.body(), "$['docs-url']")).isEqualTo("https://docs.nuna.example/catalog/");
        Map<String, Object> oidc = JsonPath.read(response.body(), "$.oidc");
        assertThat(oidc).containsOnlyKeys("issuer", "client-id", "scopes", "redirect-uri", "name");
        // The IdP's name in words, for the sign-in screen, trimmed (backlog 332).
        assertThat(oidc.get("name")).isEqualTo("the example sign-in");
        assertThat(oidc.get("issuer")).isEqualTo("https://login.example.com/tenant-1/v2.0");
        assertThat(oidc.get("client-id")).isEqualTo("nuna-console");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.oidc.scopes"))
                .containsExactly("openid", "api://nuna-catalog/catalog.access");
    }

    @Test
    void thePolicyLetsTheConsoleReachTheIdpAndNothingElse() throws Exception {
        assertThat(get("/ui/").headers().firstValue("Content-Security-Policy")).hasValueSatisfying(policy ->
                assertThat(policy).contains("connect-src 'self' https://login.example.com;")
                        .contains("script-src 'self';").doesNotContain("unsafe-inline"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
