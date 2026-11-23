package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The web console as the server hosts it under {@code /ui}, with no {@code nuna.ui.*} settings: its files
 * need no token and carry a strict Content-Security-Policy, {@code config.json} offers no token box and holds
 * nothing secret, and everything else still needs a token.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
class ConsoleTest {

    static final List<String> FILES = List.of("index.html", "app.js", "api.js", "audit.js", "auth.js", "browse.js",
            "dom.js", "governance.js", "health.js", "maintenance.js", "nights.js", "overview.js", "panels.js", "queue.js",
            "requests.js", "route.js", "table.js", "tree.js", "style.css", "favicon.svg");

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void thePageAndEveryFileAreServedWithoutATokenUnderTheContentSecurityPolicy() throws Exception {
        HttpResponse<String> page = get("/ui/", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
        assertThat(page.body()).contains("<script type=\"module\" src=\"app.js\">");
        assertStrictPolicy(page, "connect-src 'self';");
        for (String file : FILES) {
            HttpResponse<String> response = get("/ui/" + file, null);
            assertThat(response.statusCode()).as(file).isEqualTo(200);
            assertStrictPolicy(response, "connect-src 'self';");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        }
        assertThat(get("/ui/app.js", null).headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).contains("javascript"));
    }

    @Test
    void theBarePathRedirectsToThePage() throws Exception {
        HttpResponse<String> response = get("/ui", null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(v -> assertThat(v).endsWith("/ui/"));
    }

    @Test
    void devTokenLoginIsOffByDefaultAndConfigHoldsNoSecret() throws Exception {
        HttpResponse<String> response = get("/ui/config.json", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertStrictPolicy(response, "connect-src 'self';");
        Map<String, Object> config = JsonPath.read(response.body(), "$");
        assertThat(config).containsOnlyKeys("dev-token-login", "oidc");
        assertThat(config.get("dev-token-login")).isEqualTo(false);
        assertThat(config.get("oidc")).isNull();
        assertThat(response.body().toLowerCase()).doesNotContain("secret");
    }

    @Test
    void onlyTheConsoleFilesSkipAuthentication() throws Exception {
        // Paths under /ui that are not one of its flat files, and every API route, still need a token.
        for (String path : List.of("/ui/nested/app.js", "/management/v1/catalogs", "/v1/config?warehouse=trading")) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
        }
        // Odd spellings are refused, by the web server (400) or for want of a token (401), never served.
        for (String path : List.of("/ui/..%2Fv1%2Fconfig", "/ui/app.js;x=1", "/ui/%2e%2e/v1/config")) {
            assertThat(get(path, null).statusCode()).as(path).isIn(400, 401);
        }
        assertThat(get("/ui/missing.js", null).statusCode()).isEqualTo(404);
    }

    @Test
    void signedInCallersListOnlyTheCatalogsTheyHoldSomethingOn() throws Exception {
        // D18, amended 2026-11-19 (item 20): a caller who holds nothing in a catalog is not told its name.
        HttpResponse<String> nothing = get("/management/v1/catalogs", IssuerForTests.bearer("nobody", "no-grants"));
        assertThat(nothing.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(nothing.body(), "$.catalogs[*].name")).isEmpty();

        IssuerForTests.grant(jdbc, "trading", "console-describers", "describe");
        HttpResponse<String> held = get("/management/v1/catalogs",
                IssuerForTests.bearer("dora", "no-grants", "console-describers"));
        assertThat(held.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(held.body(), "$.catalogs[*].name")).containsExactly("trading");
    }

    @Test
    void theFilesHoldNoInlineCodeAndNeverWriteMarkup() throws IOException {
        Pattern inlineScript = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern inlineStyle = Pattern.compile("<style|\\sstyle=|\\son[a-z]+=", Pattern.CASE_INSENSITIVE);
        String page = resource("index.html");
        assertThat(inlineScript.matcher(page).find()).as("inline <script> in index.html").isFalse();
        assertThat(inlineStyle.matcher(page).find()).as("inline style or handler in index.html").isFalse();
        for (String file : FILES.stream().filter(f -> f.endsWith(".js")).toList()) {
            String code = resource(file);
            assertThat(code).as(file).doesNotContain(".innerHTML =", ".outerHTML =", "insertAdjacentHTML",
                    "document.write", "eval(", "new Function", "localStorage.setItem('nuna.token", ".style.", "cssText");
        }
        assertThat(resource("auth.js")).contains("sessionStorage.setItem(TOKEN").doesNotContain("localStorage.")
                .doesNotContain("document.cookie");
    }

    private static void assertStrictPolicy(HttpResponse<String> response, String connect) {
        assertThat(response.headers().firstValue("Content-Security-Policy")).as(response.uri().toString())
                .hasValueSatisfying(policy -> assertThat(policy)
                        .contains("default-src 'none'", "script-src 'self';", "style-src 'self';", "font-src 'self';",
                                connect, "frame-ancestors 'none'", "base-uri 'none'")
                        .doesNotContain("unsafe-inline", "unsafe-eval", "*"));
    }

    private static String resource(String file) throws IOException {
        try (InputStream in = ConsoleTest.class.getResourceAsStream("/nuna/ui/" + file)) {
            assertThat(in).as(file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
