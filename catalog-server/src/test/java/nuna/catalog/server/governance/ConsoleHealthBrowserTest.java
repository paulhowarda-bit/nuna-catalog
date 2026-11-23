package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import nuna.catalog.server.ConsoleBrowser;
import nuna.catalog.server.IssuerForTests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Health's DR replication and Store panels in a real browser (D45; backlog 138 and 139), for platform operators
 * only.
 *
 * <ul>
 *   <li>DR replication, from the DR readout: with every table at its watermark, none is behind; with only a table
 *       the verifier never reported on, the panel says no verifier has reported on it rather than count it; then the
 *       tables behind their watermark, the one never verified counted apart, the most versions behind linking to
 *       its table, and the longest lag.</li>
 *   <li>Store: the backend and version this server finds on its database, and its self-check at start with each
 *       finding {@code nuna.database.allow-unsafe} let through, as {@code /health/server?store=true} answers.</li>
 *   <li>Anyone else sees neither panel, and both routes refuse them.</li>
 * </ul>
 *
 * <p>The server is shared with every M7 server test, whose tables the readout lists too. The maintenance worker's
 * stand-in first records each table's pointer as its watermark, as a verifier finding the DR store whole would, so the
 * tables this test makes are the only ones behind. JUnit runs one class at a time, so none is made meanwhile.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleHealthBrowserTest {

    private static final String AUTH = "Authorization";
    private static final String[] WORKER = {GovernanceServer.PRINCIPAL, "maintenance"};

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

    @Value("${local.server.port}")
    private int port;

    private Api api;
    private ConsoleBrowser browser;
    private RemoteWebDriver driver;
    private String namespace;

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        namespace = "dr_health_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}"), 200);
        browser = new ConsoleBrowser(port, port, null);
    }

    @AfterAll
    void close() {
        if (browser != null) {
            browser.close();
        }
        api.close();
    }

    @BeforeEach
    void freshSession() {
        driver = browser.session();
    }

    @Test
    void anOperatorSeesDrReplicationAndTheStore() {
        String operator = token("olga", GovernanceServer.PLATFORM_OPS);
        browser.signIn(operator);

        // Every table and view at its watermark: none is behind.
        verifyAll();
        assertThat(readout(operator)).isEmpty();
        browser.open("/ui/#p=health");
        assertThat(browser.await(By.id("health-dr-none")).getText()).startsWith("No table or view is behind its DR "
                + "watermark");
        assertThat(fact("health-dr", "behind")).isEqualTo("0");

        // One table the verifier has not reported on: listed by every version it has, so not counted as behind.
        api.table(namespace, "fresh");
        browser.open("/ui/#p=overview");
        browser.open("/ui/#p=health");
        assertThat(browser.await(By.id("health-dr-no-verifier")).getText()).isEqualTo("No DR verifier has reported "
                + "on them. 1 table or view has no DR watermark, so none is counted as behind one. The maintenance "
                + "worker's verifier records one when it checks a DR store.");
        assertThat(driver.findElements(By.cssSelector("#health-dr [data-fact]"))).as("no count of them").isEmpty();

        // Verified, then committed on: fresh three versions behind, lagging one; unseen never verified.
        api.table(namespace, "lagging");
        verifyAll();
        for (int i = 0; i < 3; i++) {
            expect(api.rest("POST", "/namespaces/" + namespace + "/tables/fresh", Api.SET_PROPERTY.formatted("v" + i)),
                    200);
        }
        expect(api.rest("POST", "/namespaces/" + namespace + "/tables/lagging", Api.SET_PROPERTY.formatted("v")), 200);
        api.table(namespace, "unseen");
        List<Map<String, Object>> behind = readout(operator);
        assertThat(behind).extracting(row -> row.get("name")).containsExactlyInAnyOrder("fresh", "lagging", "unseen");

        browser.open("/ui/#p=overview");
        browser.open("/ui/#p=health");
        browser.await(By.cssSelector("#health-dr [data-fact='most']"));
        assertThat(fact("health-dr", "behind")).isEqualTo("2");
        assertThat(fact("health-dr", "never")).isEqualTo("1");
        assertThat(fact("health-dr", "most")).isEqualTo("3 · fresh");
        long lag = behind.stream().filter(row -> row.get("watermark-version") != null)
                .mapToLong(row -> ((Number) row.get("lag-seconds")).longValue()).max().orElseThrow();
        assertThat(fact("health-dr", "lag")).as("the longest of %s s, read a moment later", lag).matches("\\d+ s");
        assertThat(driver.findElements(By.id("health-dr-more"))).as("all of them were read").isEmpty();
        driver.findElement(By.cssSelector("#health-dr [data-fact='most'] a")).click();
        browser.await("the table's page has not opened", d -> d.getCurrentUrl().contains("n=fresh") ? true : null);
        assertThat(driver.getCurrentUrl()).contains("c=" + Api.CATALOG, "k=table", "ns=" + namespace);

        // The store, as this server finds it.
        String own = expect(api.send("GET", "/management/v1/health/server?store=true", null, AUTH, operator), 200)
                .body();
        String version = jdbc.queryForObject("SELECT version()", String.class);
        browser.open("/ui/#p=health");
        assertThat(browser.await(By.cssSelector("#health-store [data-fact='backend'] b")).getText())
                .isEqualTo("PostgreSQL " + version.split(" ")[1]);
        List<String> allowed = JsonPath.read(own, "$.store['self-check'].allowed[*].code");
        String selfCheck = fact("health-store", "self-check");
        if (allowed.isEmpty()) {
            assertThat(selfCheck).endsWith(" · passed");
            assertThat(driver.findElements(By.id("health-store-findings"))).isEmpty();
        } else {
            assertThat(selfCheck).endsWith(" · started despite " + allowed.size() + " finding"
                    + (allowed.size() == 1 ? "" : "s"));
            assertThat(driver.findElements(By.cssSelector("#health-store-findings li"))).extracting(li -> li
                    .getDomAttribute("data-code")).containsExactlyElementsOf(allowed);
        }
        assertThat(browser.await(By.id("health-store")).getText()).contains("Point-in-time recovery is not shown");
        assertThat(driver.findElements(By.id("health-store-unreadable"))).isEmpty();
    }

    @Test
    void someoneWhoIsNotAPlatformOperatorSeesNeitherPanel() {
        String reader = token("rita", "dr-health-readers");
        browser.signIn(reader);
        browser.open("/ui/#p=health");
        assertThat(browser.await(By.cssSelector("#catalog-health .health-refused")).getText())
                .startsWith("Catalog health is shown to platform operators only.");
        assertThat(driver.findElements(By.id("health-dr"))).isEmpty();
        assertThat(driver.findElements(By.id("health-store"))).isEmpty();

        expectError(api.send("GET", "/management/v1/dr/watermarks", null, AUTH, reader), 403, "ForbiddenException");
        expectError(api.send("GET", "/management/v1/health/server?store=true", null, AUTH, reader), 403,
                "ForbiddenException");
    }

    // ---- helpers ----

    /** The maintenance worker's stand-in records each table's and view's pointer as its DR watermark. */
    private void verifyAll() {
        String pageToken = null;
        do {
            String page = expect(api.send("GET", "/management/v1/maintenance/dr-work?pageSize=1000"
                    + (pageToken == null ? "" : "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8)),
                    null, WORKER), 200).body();
            List<Map<String, Object>> objects = JsonPath.read(page, "$.objects");
            for (Map<String, Object> object : objects) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pointer = (Map<String, Object>) object.get("pointer");
                expect(api.manage("PUT", "/maintenance/tables/" + object.get("id") + "/dr-watermark",
                        "{\"version\":" + pointer.get("version") + ",\"metadata-location\":\""
                                + pointer.get("metadata-location") + "\"}", WORKER), 204);
            }
            pageToken = JsonPath.<Map<String, Object>>read(page, "$").containsKey("next-page-token")
                    ? JsonPath.read(page, "$.next-page-token") : null;
        } while (pageToken != null);
    }

    /** Every table and view behind its DR watermark, as the readout lists them to a platform operator. */
    private List<Map<String, Object>> readout(String operator) {
        String page = expect(api.send("GET", "/management/v1/dr/watermarks?pageSize=200", null, AUTH, operator), 200)
                .body();
        assertThat(JsonPath.<Map<String, Object>>read(page, "$")).doesNotContainKey("next-page-token");
        return JsonPath.read(page, "$.tables");
    }

    /** The figure beside {@code key} in a panel's list. */
    private String fact(String panel, String key) {
        return browser.await(By.cssSelector("#" + panel + " [data-fact='" + key + "'] b")).getText();
    }

    /** A token for {@code user} in {@code groups}, valid for an hour by the server's clock. */
    private String token(String user, String... groups) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(user, "nuna-console", groups)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }
}
