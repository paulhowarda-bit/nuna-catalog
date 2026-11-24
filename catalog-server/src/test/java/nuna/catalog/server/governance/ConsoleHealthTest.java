package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import nuna.catalog.server.ConsoleBrowser;
import nuna.catalog.server.IssuerForTests;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The console's Health and Maintenance pages in a real browser (D38, backlog 48; redesigned in D45, backlog 124),
 * for the three people they treat differently.
 *
 * <ul>
 *   <li>A platform operator sees catalog health for the whole deployment on Health: this server's figures added to
 *       another server's, a third server named as not answering, and the worker's scans. Holding no grant, they see
 *       no table in tonight's queue on Maintenance.</li>
 *   <li>A reader sees no catalog health and no worker scans, and in tonight's queue only the tables they may select,
 *       neediest first, each linking to its table's Health tab: the latest assessment, why it is needy and its last
 *       compaction. A table they may not read is refused there as the catalog answers.</li>
 *   <li>A table the worker has not assessed yet is counted apart on the overview and Maintenance, never as in shape
 *       or queued, whether some of the reader's tables are assessed or none is (backlog 143).</li>
 *   <li>Someone with no grant sees no catalog health and no catalog, and their token never reaches another
 *       server.</li>
 *   <li>An owner, whose group holds {@code modify}, asks for a table's compaction now from its Health tab and sees
 *       the request waiting (backlog 114); a request the catalog refuses shows its answer as it came. A reader sees no
 *       such button.</li>
 * </ul>
 *
 * <p>The other servers are stand-ins on this host: one answers {@code GET /management/v1/health/server} as a server
 * of the deployment would, with figures of its own, and one port has nothing listening. {@code nuna.health.peers}
 * names both, as the Helm chart names its headless Service.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleHealthTest {

    private static final String AUTH = "Authorization";
    private static final String[] WORKER = {GovernanceServer.PRINCIPAL, "maintenance"};
    private static final String PEER_NAME = "nuna-catalog-zone-b";
    private static final Peer PEER = new Peer();
    private static final int SILENT = freePort();

    @DynamicPropertySource
    static void peers(DynamicPropertyRegistry registry) {
        registry.add("nuna.health.peers", () -> PEER.address() + ",127.0.0.1:" + SILENT);
    }

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
    private String readable;
    private String hidden;
    private String readers;
    private String owners;
    private String fresh;
    private String newcomers;
    private Instant lastSuccess;

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        readable = "health_r_" + Api.suffix();
        hidden = "health_h_" + Api.suffix();
        for (String namespace : List.of(readable, hidden)) {
            expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}"), 200);
        }
        for (String table : List.of("orders", "events", "fine")) {
            api.table(readable, table);
        }
        api.table(hidden, "secret");
        // Tables the worker has not assessed yet: one beside the assessed ones, and one alone in a namespace of its own.
        api.table(readable, "unseen");
        fresh = "health_f_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + fresh + "\"]}"), 200);
        api.table(fresh, "first");

        // The worker's reports: orders has small files and was compacted; events has equality deletes, so comes
        // first; fine needs nothing; secret needs it most, but the reader may not see it.
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assess(readable, "orders", at, 40, 12, 0, List.of("small-files"), 3.0);
        assess(readable, "events", at, 10, 0, 1, List.of("equality-deletes"), 0.5);
        assess(readable, "fine", at, 10, 0, 0, List.of(), 0.2);
        assess(hidden, "secret", at, 900, 800, 0, List.of("small-files"), 50.0);
        expect(api.manage("PUT", "/maintenance/tables/" + api.id(readable, "tables", "orders") + "/compaction",
                named(readable, "orders", "{\"started-at\":\"" + at.minusSeconds(3600) + "\",\"ended-at\":\""
                        + at.minusSeconds(1800) + "\",\"outcome\":\"succeeded\"}"), WORKER), 204);

        // The worker's scans, later than any other test's: one that succeeded with a failed job, then one that failed.
        Instant scans = at.plus(Duration.ofHours(3));
        lastSuccess = scans.plusSeconds(40);
        expect(api.manage("PUT", "/maintenance/scans/" + UUID.randomUUID(), "{\"started-at\":\"" + scans
                + "\",\"ended-at\":\"" + lastSuccess + "\",\"outcome\":\"succeeded\",\"failures\":{\"orphan-scan\":2}}",
                WORKER), 204);
        expect(api.manage("PUT", "/maintenance/scans/" + UUID.randomUUID(), "{\"started-at\":\""
                + scans.plusSeconds(600) + "\",\"ended-at\":\"" + scans.plusSeconds(601) + "\",\"outcome\":\"failed\","
                + "\"failures\":{\"table-load\":4},\"error\":\"console health test: the catalog was down\"}", WORKER),
                204);

        readers = "health-readers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + readable
                + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        owners = "health-owners-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + readable
                + "\"]},\"group\":\"" + owners + "\",\"privilege\":\"modify\"}", AUTH, api.grantAdmin()), 204);
        newcomers = "health-newcomers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + fresh
                + "\"]},\"group\":\"" + newcomers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        // Compaction runs on orders; nothing turns on fine's orphan scan, so a request for it is refused.
        expect(api.manage("PUT", "/namespaces/" + readable + "/tables/orders/maintenance-policy",
                "{\"compaction.enabled\":\"true\"}"), 200);

        // Loads and a commit of this server's own, for the SLO rows.
        for (int i = 0; i < 3; i++) {
            expect(api.rest("GET", "/namespaces/" + readable + "/tables/orders", null), 200);
        }
        expect(api.rest("POST", "/namespaces/" + readable + "/tables/fine", Api.SET_PROPERTY.formatted("v")), 200);

        browser = new ConsoleBrowser(port, port, null);
    }

    @AfterAll
    void close() {
        if (browser != null) {
            browser.close();
        }
        api.close();
        PEER.stop();
    }

    @BeforeEach
    void freshSession() {
        driver = browser.session();
    }

    @Test
    void anOperatorSeesTheWholeDeploymentsHealth() {
        String operator = token("ops", GovernanceServer.PLATFORM_OPS);
        String own = expect(api.send("GET", "/management/v1/health/server", null, AUTH, operator), 200).body();
        browser.signIn(operator);
        browser.open("/ui/#p=health");

        WebElement gathered = browser.await(By.id("health-gathered"));
        assertThat(gathered.getText()).isEqualTo("Gathered from 2 servers; 1 did not answer, so their figures are "
                + "missing.");
        List<WebElement> servers = driver.findElements(By.cssSelector("#health-servers > div[data-server]"));
        assertThat(servers).hasSize(3);
        assertThat(servers.get(0).getText()).contains("this server", "answered");
        assertThat(servers).extracting(WebElement::getText).anySatisfy(row -> assertThat(row)
                .contains(PEER_NAME, PEER.address(), "answered"));
        assertThat(servers).extracting(WebElement::getText).anySatisfy(row -> assertThat(row)
                .contains("127.0.0.1:" + SILENT, "no answer", "could not connect"));
        assertThat(PEER.tokens).as("the peer was asked with the operator's own token").contains(operator);

        // Each figure is this server's and the other's added up.
        assertThat(alert("metadata-integrity")).isEqualTo(figure(own, "alerts.metadata-integrity") + 1);
        assertThat(alert("large-metadata")).isEqualTo(figure(own, "alerts.large-metadata") + 2);
        assertThat(alert("regulated-schema-change")).isEqualTo(figure(own, "alerts.regulated-schema-change"));
        WebElement loads = browser.await(By.cssSelector("#health-slos tr[data-slo='load']"));
        assertThat(cell(loads, 3)).isEqualTo(String.valueOf(figure(own, "load") + 1000));
        assertThat(figure(own, "load")).as("the fixture's loads").isGreaterThanOrEqualTo(3);
        assertThat(cell(loads, 2)).isEqualTo("p99 < 50 ms");
        WebElement commits = driver.findElement(By.cssSelector("#health-slos tr[data-slo='commit']"));
        assertThat(cell(commits, 3)).isEqualTo(String.valueOf(figure(own, "commit") + 20));
        assertThat(cell(commits, 7)).as("the other server's 20 commits, 2 of them slow").isEqualTo("missed");
        // A share below the 97 to 100% scale points off its left edge with its value, the share's tenths cut, not
        // rounded up onto the scale (Atelier's review, F15; backlog 194); one on the scale is a dot.
        long within = Long.parseLong(cell(commits, 4));
        long count = Long.parseLong(cell(commits, 3));
        if (within * 100 < 97 * count) {
            double tenths = Math.floor(1000.0 * within / count) / 10;
            String shown = (tenths == Math.rint(tenths) ? String.valueOf((long) tenths) : String.valueOf(tenths)) + "%";
            assertThat(commits.findElements(By.cssSelector("td.slo svg circle.dot"))).isEmpty();
            assertThat(commits.findElements(By.cssSelector("td.slo svg path.off"))).hasSize(1);
            assertThat(ConsoleBrowser.textContent(commits.findElement(By.cssSelector("td.slo svg text.offv"))))
                    .isEqualTo(shown);
        } else {
            assertThat(commits.findElements(By.cssSelector("td.slo svg path.off"))).isEmpty();
        }
        WebElement notModified = driver.findElement(By.cssSelector("#health-slos tr[data-slo='not-modified']"));
        assertThat(cell(notModified, 3)).isEqualTo(String.valueOf(figure(own, "not-modified")));
        assertThat(rows("#health-vend-failures")).contains(List.of("ServiceUnavailableException",
                String.valueOf(figure(own, "vends.failed.ServiceUnavailableException") + 3)));
        assertThat(fact("Publishes failed")).startsWith(String.valueOf(figure(own, "audit.failed") + 1));

        // The worker, from the catalog's record of its scans.
        assertThat(browser.await(By.id("health-last-success")).getText())
                .isEqualTo(local(lastSuccess) + " by maintenance");
        assertThat(driver.findElement(By.id("health-job-failures")).getText())
                .contains("orphan-scan 2", "table-load 4");
        assertThat(fact("Latest failed scan")).endsWith(": console health test: the catalog was down");

        // Platform operators hold no grant, so tonight's queue lists no table for them; the worker is there too.
        browser.open("/ui/#p=maintenance&c=trading");
        assertThat(browser.await(By.id("neediest-none")).getText()).contains("no table you may read");
        assertThat(driver.findElements(By.cssSelector("#neediest tbody tr"))).isEmpty();
        assertThat(browser.await(By.cssSelector("#maintenance #health-last-success")).getText())
                .isEqualTo(local(lastSuccess) + " by maintenance");
    }

    @Test
    void aReaderSeesOnlyTheTablesTheyMayReadAndEachOnesHealth() {
        browser.signIn(token("rita", readers));
        browser.open("/ui/#p=health");
        assertNoCatalogHealth();

        browser.open("/ui/#p=maintenance&c=trading");
        browser.await(By.cssSelector("#neediest tbody tr"));
        assertThat(driver.findElements(By.cssSelector("#neediest tbody tr"))).extracting(row -> row.getDomAttribute(
                "data-table")).containsExactly("trading." + readable + ".events", "trading." + readable + ".orders");
        WebElement orders = driver.findElement(By.cssSelector("#neediest tr[data-table='trading." + readable
                + ".orders']"));
        assertThat(orders.getDomAttribute("data-reasons")).isEqualTo("small-files");
        assertThat(orders.getText()).contains("Small files", "succeeded");
        // The worker's scans are for platform operators: the catalog's refusal stands in their place.
        assertThat(browser.await(By.cssSelector("#maintenance .health-refused")).getText())
                .contains("platform operators only");
        assertThat(driver.findElements(By.id("health-worker"))).isEmpty();
        browser.await("the page has not loaded", d -> d.findElements(By.cssSelector("#maintenance [data-loading]"))
                .isEmpty());
        assertThat(driver.getPageSource()).doesNotContain(hidden, "secret");

        orders.findElement(By.cssSelector("td.name a")).click();
        assertThat(browser.awaitText(By.id("table-health-verdict"), "Needs compaction").getText())
                .contains("Score 3");
        assertThat(driver.getCurrentUrl()).contains("k=table", "n=orders", "tab=maintenance");
        assertThat(driver.findElements(By.cssSelector("#table-health-reasons li"))).extracting(li -> li.getDomAttribute(
                "data-reason")).containsExactly("small-files");
        assertThat(fact("Small files")).isEqualTo("12 (30.0% of data files)");
        assertThat(fact("Equality-delete files")).isEqualTo("0");
        WebElement compaction = browser.await(By.id("table-health-compaction"));
        assertThat(compaction.getText()).contains("succeeded");
        browser.await("the policy has not loaded", d -> d.findElements(By.cssSelector("#table-page [data-loading]"))
                .isEmpty());
        assertThat(driver.findElements(By.id("table-health-acts"))).as("a reader may not ask for maintenance").isEmpty();
        assertThat(driver.findElements(By.cssSelector("#table-page [data-act='compact']"))).isEmpty();

        // A link from before the tab was named Maintenance (backlog 192) still opens it.
        browser.open("/ui/#c=trading&k=table&ns=" + readable + "&n=fine&tab=health");
        assertThat(browser.awaitText(By.id("table-health-verdict"), "No compaction needed")).isNotNull();
        assertThat(driver.findElement(By.cssSelector("#table-page nav.tabs a[aria-current='page']")).getText())
                .isEqualTo("Maintenance");
        assertThat(browser.await(By.id("table-health-compaction")).getText())
                .contains("No compaction has run for this table.");

        browser.open("/ui/#c=trading&k=table&ns=" + hidden + "&n=secret&tab=health");
        WebElement shown = loadedTab();
        assertThat(shown.findElements(By.id("table-health-verdict"))).as("the health of a table they may not read")
                .isEmpty();
        // Rita holds nothing on the hidden namespace, so the catalog answers as if the table did not exist (D18,
        // backlog 73), and the page shows that answer as it came.
        assertThat(ConsoleBrowser.textContent(shown)).contains("The catalog answered with an error",
                "404 NoSuchTableException",
                "Table does not exist: " + hidden + ".secret");
    }

    @Test
    void aTableNotAssessedYetIsNeitherInShapeNorQueued() {
        // Some assessed: the reader's four tables, one of them not assessed yet (backlog 143).
        browser.signIn(token("rita", readers));
        browser.open("/ui/#p=overview&c=trading");
        assertThat(browser.await(By.cssSelector("#overview .ph h1")).getText())
                .isEqualTo("1 of 3 assessed tables in shape; 1 not assessed yet. 2 queued for tonight.");
        WebElement shape = browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='shape']"));
        assertThat(shape.findElement(By.cssSelector(".kpi-v")).getText()).startsWith("1 of 3");
        assertThat(shape.findElement(By.cssSelector(".kpi-d")).getText())
                .isEqualTo("2 queued for tonight, 1 not assessed yet");
        // Each legend item is a flex row, so its count and words come back on lines of their own.
        assertThat(browser.await(By.id("estate-legend")).getText().replaceAll("\\s+", " "))
                .contains("1 not assessed yet");
        browser.open("/ui/#p=maintenance&c=trading");
        assertThat(browser.await(By.cssSelector("#maintenance .ph h1")).getText())
                .isEqualTo("2 tables queued for tonight. 1 not assessed yet.");

        // None assessed: before the worker reports, nothing is called in shape (backlog 143). The catalog's policy is
        // the built-in one, every job off, and nothing nearer first turns one on, so that is said first (backlog 190);
        // with a job on, as for rita's orders above, the pages lead as before.
        driver = browser.session();
        browser.signIn(token("nell", newcomers));
        browser.open("/ui/#p=overview&c=trading");
        String off = "Maintenance is off in trading's policy: nothing will be compacted or expired.";
        assertThat(browser.await(By.cssSelector("#overview .ph h1")).getText()).isEqualTo(off);
        shape = browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='shape']"));
        assertThat(shape.findElement(By.cssSelector(".kpi-d")).getText())
                .isEqualTo("1 not assessed yet: maintenance is off in the catalog's policy");
        assertThat(shape.getText()).doesNotContain(" of ");
        assertThat(browser.awaitText(By.cssSelector("#overview .grid-21 p.muted"), "Nothing queued").getText())
                .isEqualTo("Nothing queued: maintenance is off in trading's policy.");
        browser.open("/ui/#p=maintenance&c=trading");
        // One headline, the policy's; the empty queue blames the policy, not the worker (Atelier's re-walk).
        assertThat(browser.await(By.cssSelector("#maintenance .ph h1")).getText()).isEqualTo(off);
        assertThat(browser.await(By.id("neediest-none")).getText()).contains("Maintenance is off in trading's policy, "
                + "so the worker assesses no table and queues none.").doesNotContain("in shape");
        browser.open("/ui/#c=trading&k=table&ns=" + fresh + "&n=first&tab=maintenance");
        assertThat(browser.await(By.id("table-health-off")).getText()).isEqualTo(off);

        // Nell may not modify the catalog, so neither empty state offers its policy (backlog 206).
        browser.open("/ui/#p=maintenance&c=trading");
        browser.await(By.id("neediest-none"));
        assertThat(driver.findElements(By.cssSelector("[data-act='edit-policy']"))).isEmpty();
    }

    /**
     * The maintenance policy's keys read as words wherever the console shows them, each with its key as its title,
     * which people type (Atelier's words table, "Not words" 3; backlog 207).
     */
    @Test
    void policyKeysShowTheirLabelsWithTheKeyInTheirTitle() {
        browser.signIn(token("rita", readers));
        browser.open("/ui/#c=trading&k=table&ns=" + readable + "&n=orders&tab=maintenance");
        assertLabel(By.cssSelector("[data-policy] dt [data-key='compaction.enabled']"), "Compaction");
        assertLabel(By.cssSelector("[data-policy] dt [data-key='orphan-removal.older-than']"), "Remove orphans older than");
        browser.open("/ui/#c=trading&k=table&ns=" + readable + "&n=orders&tab=history");
        assertLabel(By.cssSelector("dt [data-key='snapshot-expiry.max-snapshot-age']"), "Keep snapshots for");
        assertLabel(By.cssSelector("dt [data-key='snapshot-expiry.min-snapshots-to-keep']"), "Keep at least");

        browser.open("/ui/#p=maintenance&c=trading");
        assertLabel(By.cssSelector("#policy tr[data-key='compaction.target-file-size-bytes'] [data-key]"),
                "Target file size");
        assertLabel(By.cssSelector("#policy tr[data-key='manifest-rewrite.min-manifests'] [data-key]"), "Fewest manifests");
        assertThat(driver.findElements(By.cssSelector("#policy td code"))).as("no key shown as a name").isEmpty();
        driver.findElement(By.xpath("//div[@id='policy']//button[normalize-space()=\"Edit the catalog's policy\"]")).click();
        browser.awaitText(By.cssSelector("dialog.dlg h2"), "Policy for trading");
        WebElement field = driver.findElement(By.xpath("//dialog//label[.//input[@name='compaction.delete-file-ratio']]"));
        assertThat(field.findElement(By.cssSelector("[data-key]")).getText()).isEqualTo("Delete-file share");
        assertThat(field.findElement(By.cssSelector("[data-key]")).getDomAttribute("title"))
                .isEqualTo("compaction.delete-file-ratio");
    }

    private void assertLabel(By label, String words) {
        WebElement shown = browser.await(label);
        assertThat(shown.getText()).isEqualTo(words);
        assertThat(shown.getDomAttribute("title")).isEqualTo(shown.getDomAttribute("data-key"));
    }

    /**
     * With maintenance off in the catalog's policy, both empty states end with Edit the catalog's policy for someone
     * who may modify the catalog (backlog 206). Such a grant shows them every table in the catalog, the assessed ones
     * here among them, so nell's own grants are answered in the page with modify on the catalog; the server still
     * decides the save.
     */
    @Test
    void maintenanceOffOffersTheCatalogsPolicyToThoseWhoMayModifyIt() {
        browser.signIn(token("nell", newcomers));
        browser.open("/ui/#c=trading&k=namespace&ns=" + fresh);
        browser.await(By.cssSelector("#namespace-page .title-row h1"));
        driver.executeScript("""
                const real = window.fetch;
                window.fetch = (url, init) => new URL(url, location.origin).pathname === '/management/v1/catalogs/trading/grants'
                  ? Promise.resolve(new Response(JSON.stringify({grants: [{securable: {type: 'catalog'}, group: arguments[0],
                      privilege: 'modify'}]}), {headers: {'Content-Type': 'application/json'}}))
                  : real(url, init);
                location.hash = '#p=maintenance&c=trading';
                """, newcomers);
        WebElement edit = browser.await(By.cssSelector("#neediest-none [data-act='edit-policy']"));
        assertThat(browser.await(By.id("neediest-none")).getText()).contains("Maintenance is off in trading's policy")
                .endsWith("Edit the catalog's policy");
        edit.click();
        assertThat(browser.awaitText(By.cssSelector("dialog.dlg h2"), "Policy for trading")).isNotNull();
        assertThat(driver.findElements(By.cssSelector("dialog.dlg input[name='compaction.enabled']"))).hasSize(1);
        driver.findElement(By.cssSelector("dialog.dlg .dlg-x")).click();

        driver.executeScript("location.hash = '#p=overview&c=trading';");
        assertThat(browser.awaitText(By.cssSelector("#overview .grid-21 p.muted"), "Nothing queued").getText())
                .isEqualTo("Nothing queued: maintenance is off in trading's policy. Edit the catalog's policy");
        browser.await(By.cssSelector("#overview .grid-21 p.muted [data-act='edit-policy']")).click();
        assertThat(browser.awaitText(By.cssSelector("dialog.dlg h2"), "Policy for trading")).isNotNull();
    }

    @Test
    void someoneWithNoGrantSeesNoCatalogHealthAndNoTable() {
        String outsider = token("otto", "outsiders");
        browser.signIn(outsider);
        browser.open("/ui/#p=health");
        assertNoCatalogHealth();

        // Holding nothing, they are shown no catalog: the console has none to name.
        browser.open("/ui/#p=maintenance&c=trading");
        assertThat(browser.awaitText(By.cssSelector(".nc-main .empty h3"), "Your groups hold nothing in any catalog"))
                .isNotNull();
        assertThat(driver.findElements(By.cssSelector("#neediest tbody tr"))).isEmpty();

        // The server refuses before it gathers anything: their token never reaches another server.
        expectError(api.send("GET", "/management/v1/health/catalog", null, AUTH, outsider), 403, "ForbiddenException");
        expectError(api.send("GET", "/management/v1/health/server", null, AUTH, outsider), 403, "ForbiddenException");
        assertThat(PEER.tokens).doesNotContain(outsider);
    }

    @Test
    void anOwnerAsksForACompactionNowAndARefusalShowsTheCatalogsAnswer() {
        browser.signIn(token("olive", owners));
        browser.open("/ui/#c=trading&k=table&ns=" + readable + "&n=orders&tab=health");
        browser.await(By.cssSelector("#table-health-acts [data-act='request-compaction']")).click();
        WebElement dialog = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(dialog.findElement(By.tagName("h2")).getText()).isEqualTo("Compact orders now");
        dialog.findElement(By.cssSelector("button[type='submit']")).click();
        WebElement asked = browser.awaitText(By.cssSelector("dialog.dlg .granted"), "Compaction of orders requested");
        assertThat(asked.getText()).contains("202", "asked by olive");
        assertThat(asked.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid")).isNotBlank();
        dialog.findElement(By.cssSelector("button.dlg-x")).click();

        WebElement waiting = browser.await(By.cssSelector("#table-health-requests [data-job='compaction']"));
        assertThat(waiting.getDomAttribute("data-state")).isEqualTo("waiting");
        assertThat(waiting.getText()).contains("Compaction requested at", "by olive", "waiting");
        assertThat(browser.await(By.cssSelector("#table-health-acts [data-act='request-compaction']")).isEnabled())
                .as("one waits already").isFalse();

        browser.open("/ui/#c=trading&k=table&ns=" + readable + "&n=fine&tab=health");
        browser.await(By.cssSelector("#table-health-acts [data-act='request-orphan-scan']")).click();
        browser.await(By.cssSelector("dialog.dlg button[type='submit']")).click();
        WebElement refused = browser.await(By.cssSelector("dialog.dlg .refusal"));
        assertThat(ConsoleBrowser.textContent(refused.findElement(By.cssSelector(".refusal-code"))))
                .isEqualTo("409 MaintenanceNotAllowedException");
        assertThat(refused.getText()).contains("does not run orphan-scan");
        assertThat(refused.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid")).isNotBlank();
    }

    // ---- helpers ----

    private void assertNoCatalogHealth() {
        WebElement refused = browser.await(By.cssSelector("#catalog-health .health-refused"));
        assertThat(refused.getText()).startsWith("Service is shown to platform operators only, and your groups include none.");
        WebElement refusal = browser.await(By.cssSelector("#catalog-health .refusal"));
        assertThat(refusal.getDomAttribute("role")).isEqualTo("alert");
        assertThat(ConsoleBrowser.textContent(refusal.findElement(By.cssSelector(".refusal-code"))))
                .isEqualTo("403 ForbiddenException");
        assertThat(refusal.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid")).isNotBlank();
        assertThat(driver.findElements(By.cssSelector("#catalog-health .health-figures"))).isEmpty();
        assertThat(driver.findElements(By.id("health-alerts"))).isEmpty();
        assertThat(driver.findElements(By.id("health-slos"))).isEmpty();
        assertThat(driver.findElements(By.id("health-worker"))).isEmpty();
    }

    /** A token for {@code user} in {@code groups}, valid for an hour by the server's clock. */
    private String token(String user, String... groups) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(user, "nuna-console", groups)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    private void assess(String namespace, String table, Instant at, long data, long small, long equality,
                        List<String> reasons, double score) {
        String body = "{\"assessed-at\":\"" + at + "\",\"data-files\":" + data + ",\"small-files\":" + small
                + ",\"position-delete-files\":0,\"deletion-vectors\":0,\"equality-delete-files\":" + equality
                + ",\"reasons\":[" + String.join(",", reasons.stream().map(r -> "\"" + r + "\"").toList())
                + "],\"score\":" + score + "}";
        expect(api.manage("PUT", "/maintenance/tables/" + api.id(namespace, "tables", table) + "/assessment",
                named(namespace, table, body), WORKER), 204);
    }

    private static String named(String namespace, String table, String body) {
        return "{\"catalog\":\"" + Api.CATALOG + "\",\"namespace\":[\"" + namespace + "\"],\"name\":\"" + table + "\","
                + body.substring(1);
    }

    /** One of this server's own figures, from its answer to the route the other servers are asked. */
    private static long figure(String own, String name) {
        Map<String, Number> figures = JsonPath.read(own, "$.figures");
        return Math.round(figures.getOrDefault(name, 0).doubleValue());
    }

    private long alert(String kind) {
        return Long.parseLong(browser.await(By.cssSelector("#health-alerts [data-alert='" + kind + "'] [data-count]"))
                .getText());
    }

    /** The value beside {@code label} in the first list of facts on the page that has it. */
    private String fact(String label) {
        return browser.await(By.xpath("//dl[contains(@class,'facts')]/dt[normalize-space()='" + label
                + "']/following-sibling::dd[1]")).getText();
    }

    private List<List<String>> rows(String table) {
        return browser.await(By.cssSelector(table)).findElements(By.cssSelector("tbody tr")).stream()
                .map(row -> row.findElements(By.xpath("./td")).stream().map(WebElement::getText).toList()).toList();
    }

    /** The table page's tab body once its tab has loaded, whatever it shows. */
    private WebElement loadedTab() {
        return browser.await("the tab has not loaded", d -> d.findElements(By.cssSelector("#table-page .tab-body"))
                .stream().filter(body -> body.isDisplayed() && !body.getText().isBlank()
                        && body.findElements(By.cssSelector("[data-loading]")).isEmpty()).findFirst().orElse(null));
    }

    private static String cell(WebElement row, int n) {
        return row.findElement(By.xpath("./td[" + n + "]")).getText();
    }

    /** {@code instant} as the console shows a time: the browser's local date and time. */
    private String local(Instant instant) {
        long offsetMinutes = ((Number) driver.executeScript("return new Date(arguments[0]).getTimezoneOffset();",
                instant.toString())).longValue();
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(instant.atOffset(ZoneOffset.ofTotalSeconds((int) (-60 * offsetMinutes))));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Another server of the deployment, as far as the health page can tell: it answers the server route with figures
     * of its own (alerts, a thousand fast loads, twenty commits of which two were slow, failed vends and a failed
     * audit publish), and keeps every token it was asked with.
     */
    private static final class Peer {

        final List<String> tokens = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        Peer() {
            Instant now = Instant.now();
            String answer = """
                    {"server":{"id":"%s","name":"%s","started-at":"%s"},"from":"%s","to":"%s","figures":{
                     "alerts.metadata-integrity":1,"alerts.large-metadata":2,
                     "load":1000,"load.le.10":990,"load.le.50":1000,"load.le.300":1000,
                     "commit":20,"commit.le.10":0,"commit.le.50":5,"commit.le.300":18,
                     "vends.issued":40,"vends.failed":3,"vends.failed.ServiceUnavailableException":3,
                     "audit.acknowledged":50,"audit.acknowledged.ms":250,"audit.acknowledged.le.5":30,
                     "audit.acknowledged.le.10":50,"audit.failed":1}}"""
                    .formatted(UUID.randomUUID(), PEER_NAME, now.minus(Duration.ofDays(3)),
                            now.minus(Duration.ofDays(1)),
                            now);
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/management/v1/health/server", exchange -> {
                tokens.add(exchange.getRequestHeaders().getFirst(AUTH));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        String address() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }
}
