package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import nuna.catalog.server.ConsoleBrowser;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.S3ForTests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Overview's head, In shape line and queue line while no table the caller may read is assessed (backlog 266), on the
 * catalog {@link ConsoleReadinessBrowserTest} uses: the worker's group holds no grant there, it reports no storage that
 * covers the catalog's, and the catalog's policy turns every job off. One table, alone in a namespace whose policy turns
 * compaction on, so the catalog's policy is not said to have maintenance off.
 *
 * <ul>
 *   <li>A reader who may also read the catalog's readiness (describe on it) is told the steps left, as Maintenance
 *       tells them (item 252), with a link there in place of its panel; before any worker reports, the words that wait
 *       for the worker stay.</li>
 *   <li>A reader of the namespace alone, whom readiness would refuse, keeps the words that wait for the worker.</li>
 * </ul>
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleOverviewReadinessBrowserTest {

    private static final String AUTH = "Authorization";
    private static final String STEPS_LEFT = "The worker can't maintain trading yet: 4 of 4 steps left.";
    private static final String NOT_REPORTED = "1 table not assessed yet: the maintenance worker has not reported.";

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
    /** Select on the namespace and describe on the catalog, which reading its readiness needs. */
    private String describers;
    /** Select on the namespace alone. */
    private String readers;
    /** The worker's group, which holds nothing in the catalog. */
    private String workers;
    private Map<String, Object> policyBefore;

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        browser = new ConsoleBrowser(port, port, null);
        String ns = api.namespace();
        api.table(ns, "first");
        expect(api.manage("PUT", "/namespaces/" + ns + "/maintenance-policy", "{\"compaction.enabled\":\"true\"}"), 200);
        describers = "overview-describers-" + Api.suffix();
        readers = "overview-readers-" + Api.suffix();
        for (String group : new String[] {describers, readers}) {
            expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + ns
                    + "\"]},\"group\":\"" + group + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        }
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"catalog\"},\"group\":\"" + describers
                + "\",\"privilege\":\"describe\"}", AUTH, api.grantAdmin()), 204);
    }

    @AfterAll
    void close() {
        if (browser != null) {
            browser.close();
        }
        api.close();
    }

    @BeforeEach
    void aCatalogWithNoGrantAndNoStorage() {
        browser.session();
        jdbc.update("DELETE FROM maintenance_scan");
        workers = "workers-" + Api.suffix();
        policyBefore = JsonPath.read(expect(api.manage("GET", "/maintenance-policy", null), 200).body(), "$.policy");
        expect(api.manage("PUT", "/maintenance-policy", "{\"snapshot-expiry.enabled\":\"false\",\"orphan-removal.enabled\":"
                + "\"false\",\"compaction.enabled\":\"false\",\"manifest-rewrite.enabled\":\"false\"}"), 200);
    }

    @AfterEach
    void restorePolicy() {
        if (policyBefore.isEmpty()) {
            api.manage("DELETE", "/maintenance-policy", null);
        } else {
            expect(api.manage("PUT", "/maintenance-policy", Management.JSON.writeValueAsString(policyBefore)), 200);
        }
    }

    @Test
    void aReaderOfReadinessIsToldTheStepsLeftOnceAWorkerReports() {
        browser.signIn(token("dora", describers));

        // No worker has reported: the words that wait for it stay.
        browser.open("/ui/#p=overview&c=trading");
        assertThat(head()).isEqualTo(NOT_REPORTED);
        assertThat(shapeLine()).isEqualTo("1 not assessed yet: the maintenance worker has not reported");

        reportNoStorage();
        browser.open("/ui/#p=overview&c=trading");
        browser.await("the head does not say the steps left", d -> STEPS_LEFT.equals(head()) ? true : null);
        assertThat(shapeLine()).isEqualTo(STEPS_LEFT);
        WebElement queue = browser.await(By.cssSelector("#overview .grid-21 p.muted"));
        assertThat(queue.getText()).isEqualTo(STEPS_LEFT + " What the worker needs to maintain trading →");
        assertThat(queue.findElement(By.cssSelector("a[data-readiness]")).getDomAttribute("href"))
                .isEqualTo("#p=maintenance&c=trading");
        assertThat(browser.await(By.id("overview")).getText()).doesNotContain("has not reported", "has not assessed");
    }

    @Test
    void aReaderReadinessWouldRefuseKeepsTheOldWords() {
        reportNoStorage();
        browser.signIn(token("rhea", readers));
        browser.open("/ui/#p=overview&c=trading");
        assertThat(head()).isEqualTo(NOT_REPORTED);
        assertThat(shapeLine()).isEqualTo("1 not assessed yet: the maintenance worker has not reported");
        assertThat(browser.awaitText(By.cssSelector("#overview .grid-21 p.muted"), "Nothing queued").getText())
                .isEqualTo("Nothing queued: the maintenance worker has not assessed any table you may read yet.");
        assertThat(browser.await(By.id("overview")).findElements(By.cssSelector("a[data-readiness]"))).isEmpty();
    }

    // ---- helpers ----

    /**
     * The worker's scan in {@link #workers}, which holds nothing in the catalog, reaching a bucket that is not the
     * catalog's: no grant and no storage.
     */
    private void reportNoStorage() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String storage = "{\"kind\":\"s3\",\"endpoint\":\"https://silo.example:9000\",\"prefixes\":[{\"prefix\":"
                + "\"s3://elsewhere-" + S3ForTests.bucket() + "/tables\",\"listed\":\"ok\",\"deleted\":0,"
                + "\"delete-refused\":0,\"delete-failed\":0}]}";
        String body = "{\"started-at\":\"" + at.minusSeconds(20) + "\",\"ended-at\":\"" + at + "\",\"outcome\":"
                + "\"succeeded\",\"failures\":{},\"storage\":[" + storage + "]}";
        String id = UUID.nameUUIDFromBytes(at.toString().getBytes(StandardCharsets.UTF_8)).toString();
        expect(api.manage("PUT", "/maintenance/scans/" + id, body, GovernanceServer.PRINCIPAL, "maintenance",
                GovernanceServer.GROUPS, workers), 204);
    }

    private String head() {
        return browser.await(By.cssSelector("#overview .ph h1")).getText();
    }

    private String shapeLine() {
        return browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='shape'] .kpi-d")).getText();
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
