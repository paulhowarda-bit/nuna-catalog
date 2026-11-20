package nuna.catalog.server.governance;

import static nuna.catalog.server.ConsoleBrowser.textContent;
import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.server.ConsoleBrowser;
import nuna.catalog.server.IssuerForTests;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The web console in a real browser, on the server the M7 tests share: the screens UI v1 checked by hand against
 * the demo on 2026-11-10 (build-plan.md). The tree down to tables and views; a table's overview, schemas, partition
 * specs and sort orders, snapshots with their refs, and metadata history; a view's versions with their SQL;
 * governance with the level each value comes from; grants made and revoked; every refusal shown as the catalog
 * answered it; and an object's audit trail.
 *
 * <p>The fixtures are made through the API before the browser looks: a namespace three levels deep with a v2 table
 * whose schema, partitioning and sort order evolved and which has three snapshots, a tag and a branch, a v3 table and
 * a view with two versions, and a regulated namespace holding an unclassified table (D27). The browser signs in by
 * putting a token in the tab's storage, where the console's token box or its PKCE sign-in would put it
 * ({@code ConsoleSignInTest} drives the real sign-in).
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleBrowserTest {

    private static final String AUTH = "Authorization";
    private static final String HOLD_REASON = "console test: litigation hold";
    private static final String FREEZE_REASON = "console test: DR reconcile in progress";
    private static final long S1 = 7_001;
    private static final long S2 = 7_002;
    private static final long S3 = 7_003;
    /** A table's own rows, not those of a table nested in one of its cells (a snapshot's full summary). */
    private static final By OUTER_ROWS = By.xpath(".//table[not(ancestor::table)]/tbody/tr");

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
    private String top;
    private List<String> equities;
    private List<String> eu;
    private List<String> ledger;

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        top = "console_" + Api.suffix();
        equities = List.of(top, "equities");
        eu = List.of(top, "equities", "eu");
        ledger = List.of(top, "ledger");
        for (List<String> levels : List.of(List.of(top), equities, eu, ledger)) {
            expect(api.rest("POST", "/namespaces", "{\"namespace\":" + json(levels) + "}"), 200);
        }
        trades();
        create(eu, "positions", 3);
        daily();
        create(ledger, "accounts", 2);

        // Governance: an owner pushed on the top namespace, a classification on the table itself, a regulated
        // namespace, a hold on the namespace above the table, and last, since it stops every change below it, a
        // DR freeze on the table's own namespace.
        String steward = api.steward();
        expect(api.manage("PUT", "/namespaces/" + Api.ns(top) + "/attributes/owner",
                "{\"value\":\"desk-a\",\"provenance\":\"console test\"}", AUTH, steward), 200);
        expect(api.manage("PUT", tablePath(eu, "trades") + "/attributes/classification",
                "{\"value\":\"internal\",\"provenance\":\"console test\"}", AUTH, steward), 200);
        expect(api.manage("PUT", "/namespaces/" + ns(ledger) + "/attributes/regulated",
                "{\"value\":\"true\",\"provenance\":\"console test\"}", AUTH, steward), 200);
        expect(api.manage("POST", "/namespaces/" + ns(equities) + "/holds",
                "{\"reason\":\"" + HOLD_REASON + "\"}", AUTH, steward), 201);
        expect(api.manage("PUT", "/namespaces/" + ns(eu) + "/dr-freeze",
                "{\"reason\":\"" + FREEZE_REASON + "\"}", AUTH, api.operator()), 200);

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
    void theTreeOpensDownToTablesAndViews() {
        signIn(tester());
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("nav.tree button[aria-label='catalog trading']"));
        assertThat(new Select(driver.findElement(By.id("catalog-select"))).getFirstSelectedOption().getText())
                .isEqualTo("trading");
        assertThat(driver.findElement(By.id("who")).getText()).contains("tester", IssuerForTests.GROUP);

        expand(top);
        expand("equities");
        browser.await(leaf("view", "daily"));
        expand("ledger");
        browser.await(leaf("table", "accounts"));
        expand("eu");
        browser.await(leaf("table", "positions"));
        browser.await(leaf("table", "trades")).click();

        WebElement head = browser.awaitText(By.cssSelector("section.pane .pane-head"), "trades");
        assertThat(head.findElement(By.cssSelector(".badge")).getText()).isEqualTo("table");
        assertThat(head.findElements(By.cssSelector(".crumb"))).extracting(WebElement::getText)
                .containsExactly("trading", top, "equities", "eu", "trades");
        assertThat(driver.getCurrentUrl()).contains("k=table", "n=trades", "ns=" + top + "%1Fequities%1Feu");
        assertThat(browser.await(leaf("table", "trades")).getAttribute("class")).contains("selected");

        // The filter keeps what matches and the namespaces above it.
        driver.findElement(By.cssSelector("nav.tree input[type='search']")).sendKeys("positi");
        browser.await("trades is still listed", d -> d.findElements(leaf("table", "trades")).stream()
                .noneMatch(WebElement::isDisplayed));
        assertThat(browser.displayed(leaf("table", "positions"))).hasSize(1);
        assertThat(browser.displayed(By.cssSelector("nav.tree button.node[title='" + top + "']"))).hasSize(1);
        assertThat(browser.displayed(leaf("view", "daily"))).isEmpty();
    }

    @Test
    void theTableTabsShowSchemasSpecsSnapshotsAndHistory() {
        signIn(tester());
        browser.open("/ui/#" + at("table", eu, "trades", "overview"));
        assertThat(fact("Format version")).isEqualTo("v2");
        assertThat(fact("Current schema")).isEqualTo("id 2 (3 in all)");
        assertThat(fact("Default partition spec")).isEqualTo("id 1");
        assertThat(fact("Default sort order")).isEqualTo("id 1");
        assertThat(fact("Snapshots")).isEqualTo("3");
        assertThat(fact("Current snapshot")).startsWith(S3 + " · ").endsWith("append");
        // The console asks for no storage credentials, and the catalog honours that from any caller (D21, amended
        // 2026-11-19): there is nothing to discard, and nothing of them reaches the page or the tab's storage.
        assertThat(driver.findElements(By.cssSelector("section.pane .notice"))).isEmpty();
        assertThat(driver.getPageSource()).doesNotContain("storage-credentials", "secret-access-key",
                "session-token");
        assertThat(storageKeys()).containsExactly("nuna.token");

        tab("Schema");
        Select schemas = new Select(browser.await(By.cssSelector("section.pane select[aria-label='Schema']")));
        assertThat(schemas.getOptions()).extracting(WebElement::getText)
                .containsExactly("schema 2 (current)", "schema 1", "schema 0");
        assertThat(addedFields()).containsExactly("venue");
        assertThat(schemaFields()).containsExactly("id", "symbol", "price", "venue");
        schemas.selectByVisibleText("schema 1");
        browser.await("schema 1 is not shown", d -> schemaFields().equals(List.of("id", "symbol", "price")));
        assertThat(addedFields()).containsExactly("price");
        schemas.selectByVisibleText("schema 0");
        browser.await("schema 0 is not shown", d -> schemaFields().equals(List.of("id", "symbol")));
        assertThat(addedFields()).isEmpty();

        tab("Partitioning & sort");
        WebElement specs = section("Partition specs");
        assertThat(specs.findElements(By.tagName("h4"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("Spec 1default", "Spec 0");
        assertThat(rows(specs)).containsExactly(List.of("1000", "symbol", "symbol", "identity"));
        assertThat(specs.getText()).contains("Unpartitioned.");
        WebElement orders = section("Sort orders");
        assertThat(orders.findElements(By.tagName("h4"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("Order 1default", "Order 0");
        assertThat(rows(orders)).containsExactly(List.of("id", "identity", "asc", "nulls-first"));

        tab("Snapshots");
        assertThat(rows(section("Refs: branches and tags"))).containsExactly(
                List.of("main", "branch", String.valueOf(S3), ""),
                List.of("close-day-1", "tag", String.valueOf(S1), ""),
                List.of("review", "branch", String.valueOf(S2), ""));
        List<WebElement> snapshots = section("Snapshots, newest first").findElements(OUTER_ROWS);
        assertThat(snapshots).hasSize(3);
        assertThat(cell(snapshots.get(0), 1)).isEqualTo("current");
        assertThat(cell(snapshots.get(0), 2)).isEqualTo(S3 + "main");
        assertThat(cell(snapshots.get(1), 1)).isEmpty();
        assertThat(cell(snapshots.get(1), 2)).isEqualTo(S2 + "review");
        assertThat(cell(snapshots.get(1), 3)).isEqualTo(String.valueOf(S1));
        assertThat(cell(snapshots.get(2), 2)).isEqualTo(S1 + "close-day-1");
        assertThat(cell(snapshots.get(2), 3)).isEqualTo("–");

        tab("Metadata history");
        HttpResponse<String> loaded = expect(api.rest("GET", "/namespaces/" + ns(eu) + "/tables/trades", null), 200);
        List<String> earlier = JsonPath.read(loaded.body(), "$.metadata.metadata-log[*].metadata-file");
        List<WebElement> files = section("Metadata files, newest first").findElements(OUTER_ROWS);
        assertThat(files).hasSize(earlier.size() + 1);
        assertThat(cell(files.get(0), 2)).isEqualTo(JsonPath.<String>read(loaded.body(), "$.metadata-location"));
        assertThat(cell(files.get(0), 1)).endsWith("current");
        assertThat(rows(section("Snapshot log (main), newest first"))).extracting(row -> row.get(1))
                .containsExactly(String.valueOf(S3), String.valueOf(S2), String.valueOf(S1));

        browser.open("/ui/#" + at("table", eu, "positions", "overview"));
        assertThat(fact("Format version")).isEqualTo("v3");
        assertThat(fact("Next row id")).isNotBlank();
    }

    @Test
    void theViewTabsShowEveryVersionWithItsSql() {
        signIn(tester());
        browser.open("/ui/#" + at("view", equities, "daily", "overview"));
        assertThat(fact("Current version")).isEqualTo("id 2 (2 in all)");

        tab("Versions & SQL");
        List<WebElement> versions = browser.await("no view versions", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("section.pane article.version"));
            return found.size() == 2 ? found : null;
        });
        assertThat(textContent(versions.get(0).findElement(By.tagName("h4")))).startsWith("Version 2current ·");
        assertThat(versions.get(0).getAttribute("class")).contains("current-version");
        assertThat(sql(versions.get(0))).containsExactly("spark select 2 as x, venue", "trino SELECT 2 AS x, venue");
        assertThat(textContent(versions.get(1).findElement(By.tagName("h4")))).startsWith("Version 1 ·");
        assertThat(sql(versions.get(1))).containsExactly("spark select 1 as x, venue");

        tab("Schema");
        assertThat(new Select(browser.await(By.cssSelector("section.pane select[aria-label='Schema']")))
                .getFirstSelectedOption().getText()).isEqualTo("schema 0 (current)");
        assertThat(schemaFields()).containsExactly("x", "venue");
    }

    @Test
    void governanceShowsTheFreezeTheHoldAndWhereEachValueComesFrom() {
        signIn(tester());
        browser.open("/ui/#" + at("table", eu, "trades", "governance"));
        WebElement frozen = browser.await(By.cssSelector("section.pane .status-frozen"));
        assertThat(frozen.getText()).contains("DR freeze in force", FREEZE_REASON);
        assertThat(browser.await(By.cssSelector("section.pane .status-held")).getText()).startsWith("1 legal hold");

        assertThat(fact("owner")).isEqualTo("desk-a");
        assertThat(fact("classification")).isEqualTo("internal");
        assertThat(fact("retention-class")).isEqualTo("–");

        List<List<String>> holds = rows(section("Legal holds"));
        assertThat(holds).hasSize(1);
        assertThat(holds.get(0).subList(0, 3)).containsExactly(HOLD_REASON,
                "inherited from namespace " + top + ".equities", "stella");
        List<List<String>> attributes = rows(section("Attributes, nearest level first"));
        assertThat(attributes).extracting(row -> row.get(0) + " = " + row.get(1) + " @ " + row.get(2))
                .contains("classification = internal @ this table",
                        "owner = desk-a @ inherited from namespace " + top);

        // The level above sees the same hold as its own.
        browser.open("/ui/#" + at("namespace", equities, null, "governance"));
        browser.await(By.cssSelector("section.pane .status-held"));
        assertThat(rows(section("Legal holds")).get(0).get(1)).isEqualTo("this namespace");
        assertThat(browser.await(By.cssSelector("section.pane .status-row")).getText()).contains("No DR freeze");
    }

    @Test
    void refusalsAreShownExactlyAsTheCatalogAnswered() {
        // D27: an unclassified table in a regulated namespace is refused everything but describe.
        signIn(tester());
        browser.open("/ui/#" + at("table", ledger, "accounts", "overview"));
        assertShownVerbatim(refusal(), api.send("GET", "/v1/trading/namespaces/" + ns(ledger)
                + "/tables/accounts?snapshots=all", null, AUTH, tester(), "X-Iceberg-Access-Delegation", "none"));

        // Reading an object's audit trail needs manage-grants.
        browser.open("/ui/#" + at("table", eu, "trades", "audit"));
        assertShownVerbatim(refusal(), api.manage("GET", tablePath(eu, "trades") + "/audit?limit=50", null,
                AUTH, tester()));

        // Grant administrators are kept away from the data itself (D18).
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("table", eu, "trades", "overview"));
        assertShownVerbatim(refusal(), api.send("GET", "/v1/trading/namespaces/" + ns(eu)
                + "/tables/trades?snapshots=all", null, AUTH, api.grantAdmin(), "X-Iceberg-Access-Delegation", "none"));
    }

    @Test
    void grantsAreMadeAndRevokedAndASeparationOfDutiesRefusalIsShownVerbatim() {
        String group = "console-readers-" + Api.suffix();
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("namespace", equities, null, "grants"));
        grant("select", group);
        browser.awaitText(By.cssSelector("section.pane .status-line .ok"), "Granted select to " + group + ".");
        WebElement row = browser.awaitText(By.cssSelector("section.pane tr"), group);
        assertThat(row.findElements(By.tagName("td"))).extracting(WebElement::getText).startsWith(
                "namespace " + top + ".equities", group, "select", "gina");

        row.findElement(By.xpath(".//button[normalize-space()='Revoke']")).click();
        assertThat(driver.switchTo().alert().getText()).isEqualTo("Revoke select from " + group + "?");
        driver.switchTo().alert().accept();
        browser.awaitText(By.cssSelector("section.pane .status-line .ok"), "Revoked select from " + group + ".");
        browser.await(group + " is still listed", d -> d.findElements(By.cssSelector("section.pane tr")).stream()
                .noneMatch(r -> r.getText().contains(group)));
        assertThat(section("Grants on this namespace").getText()).contains("No grants on this namespace itself.");

        // Stewards hold manage-tags, a duty that write must stay apart from (D18).
        grant("write", GovernanceServer.STEWARDS);
        WebElement refusal = browser.await(By.cssSelector("section.pane .status-line .alert-refused"));
        assertShownVerbatim(refusal, api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\","
                + "\"namespace\":" + json(equities) + "},\"group\":\"" + GovernanceServer.STEWARDS
                + "\",\"privilege\":\"write\"}", AUTH, api.grantAdmin()));
        assertThat(textContent(refusal)).contains("separate");

        // Grants by group, on the catalog's own page.
        browser.open("/ui/#" + at("catalog", List.of(), null, "grants"));
        browser.await(By.cssSelector("section.pane input[aria-label='Groups']")).sendKeys(GovernanceServer.STEWARDS);
        section("Grants by group").findElement(By.xpath(".//button[normalize-space()='List']")).click();
        browser.await("no grants listed for stewards", d -> rows(section("Grants by group")).stream()
                .anyMatch(r -> r.subList(0, 3).equals(List.of("catalog whole catalog", GovernanceServer.STEWARDS,
                        "manage-tags"))));
    }

    @Test
    void theAuditTrailShowsCommitsReadsCredentialsAndDenials() {
        // A read with its vended credentials, and a refused read, beside the commits that made the table.
        String path = "/v1/trading/namespaces/" + ns(eu) + "/tables/trades";
        expect(api.send("GET", path, null, AUTH, tester()), 200);
        expect(api.send("GET", path, null, AUTH, api.grantAdmin()), 403);

        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("table", eu, "trades", "audit"));
        Set<String> wanted = Set.of("commit", "read", "credential-issue", "denial");
        Set<String> seen = new TreeSet<>();
        long deadline = System.nanoTime() + ConsoleBrowser.WAIT.toNanos();
        while (true) {
            browser.await(By.cssSelector("section.pane p.window"));
            seen.clear();
            for (List<String> record : rows(section())) {
                seen.add(record.get(1));
            }
            if (seen.containsAll(wanted) || System.nanoTime() > deadline) {
                break;
            }
            // The relay publishes commits a moment after they are made: ask again.
            driver.findElement(By.xpath("//section[contains(@class,'pane')]//button[normalize-space()='Refresh']"))
                    .click();
            browser.await("the trail did not reload", d -> d.findElements(By.cssSelector("section.pane p.window"))
                    .isEmpty() || d.findElements(By.cssSelector("section.pane p.loading")).isEmpty());
        }
        assertThat(seen).containsAll(wanted);

        WebElement denied = browser.await(By.cssSelector("section.pane tr.denied-row"));
        assertThat(cell(denied, 2)).isEqualTo("denial");
        assertThat(cell(denied, 3)).startsWith("gina");
        assertThat(cell(denied, 4)).startsWith("refused loadTable");
        String table = api.id(String.join("\u001f", eu), "tables", "trades");
        denied.findElement(By.tagName("summary")).click();
        WebElement raw = browser.await(By.cssSelector("section.pane tr.denied-row pre.raw"));
        assertThat(JsonPath.<String>read(raw.getText(), "$.subject")).isEqualTo(table);
        assertThat(JsonPath.<String>read(raw.getText(), "$.type")).isEqualTo("nuna.catalog.denial");
        assertThat(browser.await(By.cssSelector("section.pane p.window")).getText())
                .startsWith("Read from nuna.catalog.audit, partition ");
    }

    // ---- fixtures ----

    /** The v2 table: three schemas, a second partition spec and sort order, three snapshots, a tag and a branch. */
    private void trades() {
        String location = create(eu, "trades", 2);
        String root = location.substring(0, location.indexOf("/metadata/"));
        commit("{\"action\":\"add-schema\",\"last-column-id\":3,\"schema\":" + schema(1, true, false) + "},"
                + "{\"action\":\"set-current-schema\",\"schema-id\":-1}");
        commit("{\"action\":\"add-schema\",\"last-column-id\":4,\"schema\":" + schema(2, true, true) + "},"
                + "{\"action\":\"set-current-schema\",\"schema-id\":-1}");
        commit("""
                {"action":"add-spec","spec":{"spec-id":1,"fields":[
                  {"source-id":2,"field-id":1000,"name":"symbol","transform":"identity"}]}},
                {"action":"set-default-spec","spec-id":-1},
                {"action":"add-sort-order","sort-order":{"order-id":1,"fields":[
                  {"source-id":1,"transform":"identity","direction":"asc","null-order":"nulls-first"}]}},
                {"action":"set-default-sort-order","sort-order-id":-1}""");
        // Real time, a second apart: a snapshot on main may not be stamped after the commit that adds it.
        long now = System.currentTimeMillis();
        long parent = 0;
        int sequence = 1;
        for (long id : List.of(S1, S2, S3)) {
            commit("""
                    {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,"timestamp-ms":%d,
                      "manifest-list":"%s/metadata/snap-%d.avro","schema-id":2,
                      "summary":{"operation":"append","added-records":"%d","total-records":"%d"}}},
                    {"action":"set-snapshot-ref","ref-name":"main","snapshot-id":%d,"type":"branch"}"""
                    .formatted(id, parent == 0 ? "" : "\"parent-snapshot-id\":" + parent + ",", sequence,
                            now - 3_000 + sequence * 1_000L, root, id, 10, 10L * sequence, id));
            parent = id;
            sequence++;
        }
        commit("""
                {"action":"set-snapshot-ref","ref-name":"close-day-1","snapshot-id":%d,"type":"tag"},
                {"action":"set-snapshot-ref","ref-name":"review","snapshot-id":%d,"type":"branch"}"""
                .formatted(S1, S2));
    }

    /** A view in {@code equities} with two versions, the second with SQL for two engines. */
    private void daily() {
        String schema = """
                {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"x","required":false,"type":"int"},
                  {"id":2,"name":"venue","required":false,"type":"string"}]}""";
        expect(api.rest("POST", "/namespaces/" + ns(equities) + "/views", """
                {"name":"daily","schema":%s,"view-version":{"version-id":1,"timestamp-ms":%d,"schema-id":0,
                  "summary":{"engine-name":"spark"},"default-namespace":%s,
                  "representations":[{"type":"sql","sql":"select 1 as x, venue","dialect":"spark"}]},
                 "properties":{}}""".formatted(schema, System.currentTimeMillis(), json(equities))), 200);
        expect(api.rest("POST", "/namespaces/" + ns(equities) + "/views/daily", """
                {"requirements":[],"updates":[{"action":"add-view-version","view-version":{"version-id":2,
                  "timestamp-ms":%d,"schema-id":0,"summary":{"engine-name":"spark"},"default-namespace":%s,
                  "representations":[{"type":"sql","sql":"select 2 as x, venue","dialect":"spark"},
                                     {"type":"sql","sql":"SELECT 2 AS x, venue","dialect":"trino"}]}},
                 {"action":"set-current-view-version","view-version-id":-1}]}"""
                .formatted(System.currentTimeMillis(), json(equities))), 200);
    }

    /** Creates a table with schema 0 and returns its metadata location. */
    private String create(List<String> levels, String name, int formatVersion) {
        HttpResponse<String> created = expect(api.rest("POST", "/namespaces/" + ns(levels) + "/tables",
                "{\"name\":\"" + name + "\",\"schema\":" + schema(0, false, false)
                        + ",\"properties\":{\"format-version\":\"" + formatVersion + "\"}}"), 200);
        return JsonPath.read(created.body(), "$.metadata-location");
    }

    private void commit(String updates) {
        expect(api.rest("POST", "/namespaces/" + ns(eu) + "/tables/trades",
                "{\"requirements\":[],\"updates\":[" + updates + "]}"), 200);
    }

    /** Schema {@code id}: id and symbol, then price and venue as they were added. */
    private static String schema(int id, boolean price, boolean venue) {
        return "{\"type\":\"struct\",\"schema-id\":" + id + ",\"fields\":["
                + "{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"},"
                + "{\"id\":2,\"name\":\"symbol\",\"required\":true,\"type\":\"string\"}"
                + (price ? ",{\"id\":3,\"name\":\"price\",\"required\":false,\"type\":\"double\"}" : "")
                + (venue ? ",{\"id\":4,\"name\":\"venue\",\"required\":false,\"type\":\"string\","
                + "\"doc\":\"where it traded\"}" : "")
                + "]}";
    }

    // ---- signing in ----

    /** The tester: create and modify on the catalog, so may read everything but the audit trail. */
    private String tester() {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims("tester", "nuna-console", IssuerForTests.GROUP)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    private void signIn(String bearer) {
        browser.signIn(bearer);
    }

    // ---- reading the page ----

    /** A deep link's fragment, as the console writes it: levels joined by 0x1F. */
    private static String at(String kind, List<String> levels, String name, String tab) {
        StringBuilder fragment = new StringBuilder("c=trading");
        if (!kind.equals("catalog")) {
            fragment.append("&k=").append(kind);
        }
        if (!levels.isEmpty()) {
            fragment.append("&ns=").append(String.join("%1F", levels));
        }
        if (name != null) {
            fragment.append("&n=").append(name);
        }
        return fragment.append("&tab=").append(tab).toString();
    }

    private void expand(String namespace) {
        browser.await(By.cssSelector("nav.tree button.twisty[aria-label='Expand " + namespace + "']")).click();
        browser.await(By.cssSelector("nav.tree button.twisty[aria-label='Collapse " + namespace + "']"));
    }

    private static By leaf(String kind, String name) {
        return By.cssSelector("nav.tree button.node[aria-label='" + kind + " " + name + "']");
    }

    private void tab(String label) {
        browser.await(By.xpath("//section[contains(@class,'pane')]//button[@role='tab'][normalize-space()='"
                + label + "']")).click();
        browser.await("tab " + label + " is not selected", d -> d.findElements(By.xpath(
                "//button[@role='tab'][@aria-selected='true'][normalize-space()='" + label + "']")).size() == 1
                && d.findElements(By.cssSelector("section.pane .tab-body > p.loading")).isEmpty());
    }

    /** The value beside {@code label} in the pane's first list of facts that has it. */
    private String fact(String label) {
        return browser.await(By.xpath("//section[contains(@class,'pane')]//dl[contains(@class,'facts')]"
                + "/dt[normalize-space()='" + label + "']/following-sibling::dd[1]")).getText();
    }

    /** A titled section of the pane. */
    private WebElement section(String title) {
        return browser.await(By.xpath("//section[contains(@class,'pane')]//section[h3[normalize-space()='"
                + title + "']]"));
    }

    /** The pane's tab body. */
    private WebElement section() {
        return browser.await(By.cssSelector("section.pane .tab-body"));
    }

    /** The cells' text of each row of the tables under {@code within}, nested tables left out. */
    private static List<List<String>> rows(WebElement within) {
        List<List<String>> rows = new ArrayList<>();
        for (WebElement row : within.findElements(OUTER_ROWS)) {
            rows.add(row.findElements(By.xpath("./td")).stream().map(WebElement::getText).toList());
        }
        return rows;
    }

    /** A row's {@code n}th cell's text, counting from 1. */
    private static String cell(WebElement row, int n) {
        return row.findElement(By.xpath("./td[" + n + "]")).getText();
    }

    private List<String> schemaFields() {
        return driver.findElements(By.cssSelector("section.pane table.schema .field-name")).stream()
                .map(WebElement::getText).toList();
    }

    private List<String> addedFields() {
        return driver.findElements(By.cssSelector("section.pane table.schema tbody tr")).stream()
                .filter(row -> row.findElements(By.cssSelector(".badge-new")).stream()
                        .anyMatch(b -> b.getText().equals("added")))
                .map(row -> row.findElement(By.cssSelector(".field-name")).getText()).toList();
    }

    /** Each SQL representation of a view version: its dialect, a space, its SQL. */
    private static List<String> sql(WebElement version) {
        return version.findElements(By.cssSelector(".sql")).stream()
                .map(sql -> sql.findElement(By.cssSelector(".sql-head")).getText().replace("sql ", "").trim()
                        + " " + textContent(sql.findElement(By.tagName("pre"))))
                .toList();
    }

    private void grant(String privilege, String group) {
        WebElement input = browser.await(By.cssSelector("section.pane input[aria-label='Group']"));
        input.clear();
        input.sendKeys(group);
        new Select(driver.findElement(By.cssSelector("section.pane select[aria-label='Privilege']")))
                .selectByValue(privilege);
        driver.findElement(By.xpath("//section[contains(@class,'pane')]//form//button[@type='submit']"
                + "[normalize-space()='Grant']")).click();
    }

    private WebElement refusal() {
        return browser.await(By.cssSelector("section.pane .alert-refused"));
    }

    /** The refusal on the page says exactly what the catalog answers to the same request: status, type, message. */
    private static void assertShownVerbatim(WebElement alert, HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(403);
        String type = JsonPath.read(answer.body(), "$.error.type");
        String message = JsonPath.read(answer.body(), "$.error.message");
        assertThat(message).isNotBlank();
        assertThat(alert.getDomAttribute("role")).isEqualTo("alert");
        assertThat(alert.findElement(By.tagName("strong")).getText()).isEqualTo("Refused by the catalog");
        List<WebElement> lines = alert.findElements(By.cssSelector(".alert-line"));
        assertThat(lines).hasSize(2);
        assertThat(textContent(lines.get(0))).isEqualTo("403 " + type);
        assertThat(textContent(lines.get(1))).isEqualTo(message);
    }

    @SuppressWarnings("unchecked")
    private List<String> storageKeys() {
        return (List<String>) driver.executeScript("return Object.keys(sessionStorage)");
    }

    // ---- paths ----

    private static String ns(List<String> levels) {
        return Api.ns(levels.toArray(String[]::new));
    }

    private static String tablePath(List<String> levels, String table) {
        return "/namespaces/" + ns(levels) + "/tables/" + table;
    }

    private static String json(List<String> levels) {
        return levels.stream().map(level -> "\"" + level + "\"").collect(
                java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
