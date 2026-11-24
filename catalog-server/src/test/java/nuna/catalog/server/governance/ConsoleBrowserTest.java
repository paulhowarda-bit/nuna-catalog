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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The web console in a real browser, on the server the M7 tests share: the screens UI v1 checked by hand against
 * the demo on 2026-11-10 (build-plan.md), in the redesigned console (D45, backlog 124). The tree down to tables and
 * views; a namespace's Contents with each table's format, snapshots and last commit (backlog 137); a table's
 * overview, schemas compared, partition specs and sort orders, snapshots with their Iceberg refs, and metadata log; a view's versions with their SQL; governance with the level each value comes from; grants made and
 * revoked; every refusal shown as the catalog answered it, with its request id; an object's audit trail; the theme
 * picked and kept; the phone layout, its menu a drawer and no page wider than the screen; and the catalog's branches
 * and tags (backlog 109): a branch made, committed on, previewed and merged, and a table read as of a tag.
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
        browser.await(node("catalog"));
        assertThat(textContent(browser.await(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading");
        assertThat(textContent(driver.findElement(By.id("who")))).contains("tester", IssuerForTests.GROUP);
        browser.await(By.cssSelector("#catalog-page table.contents tr[data-kind='namespace'][data-name='" + top + "']"));

        // Opening a namespace in the tree opens its page and lists what is in it.
        browser.await(node("namespace:" + top)).click();
        browser.await(node("namespace:" + top + ".equities")).click();
        browser.await(node("view:" + top + ".equities.daily"));
        browser.await(node("namespace:" + top + ".ledger")).click();
        browser.await(node("table:" + top + ".ledger.accounts"));

        // The tree marks what is held, frozen and regulated.
        browser.await(mark("namespace:" + top + ".equities", "m-held"));
        browser.await(mark("namespace:" + top + ".ledger", "m-reg"));
        browser.await(node("namespace:" + top + ".equities.eu")).click();
        browser.await(node("table:" + top + ".equities.eu.positions"));
        browser.await(mark("namespace:" + top + ".equities.eu", "m-frozen"));
        browser.await(node("table:" + top + ".equities.eu.trades")).click();

        browser.awaitText(By.cssSelector("#table-page h1"), "trades");
        assertThat(driver.findElements(By.cssSelector("#table-page nav.crumbs > a, #table-page nav.crumbs > span.cur")))
                .extracting(ConsoleBrowser::textContent).containsExactly("trading", top, "equities", "eu", "trades");
        assertThat(driver.getCurrentUrl()).contains("k=table", "n=trades", "ns=" + top + "%1Fequities%1Feu");
        browser.await("trades is not marked as the page shown", d -> "true".equals(
                d.findElement(node("table:" + top + ".equities.eu.trades")).getDomAttribute("aria-current")));

        // The filter keeps what matches and the namespaces above it.
        driver.findElement(By.cssSelector(".tree input[aria-label='Filter names in the tree']")).sendKeys("positi");
        browser.await("trades is still listed", d -> d.findElements(node("table:" + top + ".equities.eu.trades"))
                .stream().noneMatch(WebElement::isDisplayed));
        assertThat(browser.displayed(node("table:" + top + ".equities.eu.positions"))).hasSize(1);
        assertThat(browser.displayed(node("namespace:" + top))).hasSize(1);
        assertThat(browser.displayed(node("view:" + top + ".equities.daily"))).isEmpty();

        // A namespace's Contents marks the same.
        browser.open("/ui/#" + at("namespace", List.of(top), null, null));
        browser.awaitText(contentsGovernance("equities"), "Held");
        browser.awaitText(contentsGovernance("ledger"), "regulated");
    }

    /**
     * Sign-in opens the catalog this browser used last, else the one with the most tables the caller may read
     * (Atelier's review, F2; backlog 183); a catalog with nothing the caller may read says so in plain words. This
     * server has one catalog, so the landing is the catalog with tables and the empty headline names no other.
     */
    @Test
    void signInOpensTheCatalogWithTablesAndAnEmptyOneSaysSo() {
        signIn(tester());
        browser.open("/ui/");
        browser.await("sign-in did not open the overview of trading",
                d -> d.getCurrentUrl().endsWith("#p=overview&c=trading"));
        browser.await(By.cssSelector("#overview-kpis"));
        assertThat(driver.executeScript("return localStorage.getItem('nuna.catalog')")).isEqualTo("trading");

        // Grant administrators read no table (D18).
        signIn(api.grantAdmin());
        browser.open("/ui/#p=overview&c=trading");
        browser.awaitText(By.cssSelector("#overview h1"), "Nothing in trading you may read.");
        assertThat(driver.findElements(By.cssSelector("#overview h1 a[data-other-catalog]"))).isEmpty();
    }

    /**
     * Each role sees only the pages, tabs and buttons its groups can use, and lands where its work is (Atelier's
     * review, F4 and F12; backlog 184): someone who reads data on the overview, with no Audit or Service; a grant admin
     * on Audit, with no Overview or Maintenance; a platform operator on Health.
     */
    @Test
    void eachRoleSeesWhatItsGroupsCanUseAndLandsWhereItsWorkIs() {
        signIn(tester());
        browser.open("/ui/");
        browser.await("the tester did not land on the overview", d -> d.getCurrentUrl().endsWith("#p=overview&c=trading"));
        assertThat(rail()).containsExactly("overview", "catalog", "maintenance", "governance");
        browser.open("/ui/#" + at("table", eu, "trades", "governance"));
        browser.await(By.cssSelector("#governance .status-row"));
        assertThat(driver.findElements(By.cssSelector("#table-page nav.tabs a[data-tab]")))
                .extracting(a -> a.getDomAttribute("data-tab")).doesNotContain("audit").contains("grants");
        assertThat(driver.findElements(By.cssSelector("#governance [data-acts='labels']"))).isEmpty();
        tab("grants");
        browser.await(By.cssSelector("[data-grants='own']"));
        assertThat(driver.findElements(By.cssSelector(".nc-main form.grant-form"))).isEmpty();
        browser.open("/ui/#p=audit&c=trading");
        assertThat(textContent(browser.await(By.cssSelector("#closed-page [data-closed] h3"))))
                .isEqualTo("Audit is open to auditors and to those who manage trading's grants.");

        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        assertThat(rail()).containsExactly("catalog", "governance", "audit");

        signIn(api.operator());
        browser.open("/ui/");
        browser.await("the operator did not land on Service", d -> d.getCurrentUrl().endsWith("#p=service"));
        assertThat(rail()).containsExactly("catalog", "governance", "service");
    }

    /**
     * The rail keeps its labels on the Catalog page, the page people browse most (Atelier's review, F5; backlog 192);
     * only its fold button folds it to icons.
     */
    @Test
    void theRailKeepsItsLabelsOnTheCatalogPage() {
        signIn(tester());
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#nav a[data-k='catalog'][aria-current='page']"));
        assertThat(driver.findElement(By.cssSelector("#nav a[data-k='catalog'] .nl")).isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector(".nc-app")).getDomAttribute("class")).doesNotContain("rail");
    }

    /** The rail's items drawn for the caller, by key. */
    private List<String> rail() {
        browser.await(By.cssSelector("#nav a[data-k][aria-current='page'], #nav a[data-k]:not([hidden])"));
        return driver.findElements(By.cssSelector("#nav a[data-k]")).stream()
                .filter(a -> a.getDomAttribute("hidden") == null).map(a -> a.getDomAttribute("data-k")).toList();
    }

    /**
     * Go to a path opens a full dotted path the console has not listed yet, looking up each level as a deep link
     * does, from a page with no tree; a path that is not there says so, and a word with no match says what was
     * searched (Atelier's review, F1; backlog 185).
     */
    @Test
    void goToAPathOpensAFullDottedPathNotListedYet() {
        String path = top + ".equities.eu.trades";
        signIn(tester());
        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#overview-kpis"));
        browser.await(By.cssSelector(".nc-go")).click();
        WebElement box = browser.await(By.id("goto-in"));
        box.sendKeys(path);
        browser.await(By.cssSelector("#goto-list [data-goto-path='" + path + "']"));
        box.sendKeys(Keys.ENTER);
        browser.awaitText(By.cssSelector("#table-page h1"), "trades");
        assertThat(driver.getCurrentUrl()).contains("k=table", "n=trades", "ns=" + top + "%1Fequities%1Feu");

        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#overview-kpis"));
        browser.await(By.cssSelector(".nc-go")).click();
        box = browser.await(By.id("goto-in"));
        box.sendKeys("zzqq");
        assertThat(textContent(browser.await(By.cssSelector("#goto-list li.none"))))
                .startsWith("Not found among the ").contains("listed so far in trading").doesNotContain("fewer words");
        box.clear();
        box.sendKeys(top + ".equities.nothing_here");
        box.sendKeys(Keys.ENTER);
        browser.await(By.cssSelector("#goto-list [data-goto-none='" + top + ".equities.nothing_here']"));
        assertThat(driver.getCurrentUrl()).endsWith("#p=overview&c=trading");
    }

    /**
     * A namespace's Contents gives each table its format, snapshot count and last commit, as the catalog's listing of
     * them answers (backlog 137): the last commit is the newest entry of the table's metadata log, "12:49 by lake".
     * Each child namespace gets its totals over the tables beneath it (backlog 147), its newest commit "12:49 in
     * rates" (backlog 186).
     */
    @Test
    void contentsShowEachTablesFormatSnapshotsAndLastCommit() {
        HttpResponse<String> listed = expect(api.manage("GET", "/namespaces/" + ns(eu) + "/tables", null), 200);
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.tables[*].name")).containsExactly("positions", "trades");
        String id = api.id(String.join("\u001f", eu), "tables", "trades");
        HttpResponse<String> log = expect(api.manage("GET", "/objects/" + id + "/metadata-log", null), 200);
        String committer = JsonPath.read(log.body(), "$.journal[0].principal");
        assertThat(JsonPath.<Integer>read(listed.body(), "$.tables[1].last-commit.version"))
                .isEqualTo(JsonPath.<Integer>read(log.body(), "$.journal[0].new-version"));

        signIn(tester());
        browser.open("/ui/#" + at("namespace", eu, null, null));
        browser.await(By.cssSelector("#namespace-page table.contents tr[data-name='trades']"));
        // A column with nothing on any row is dropped (backlog 193): the queue and governance columns come and go with
        // what other tests leave, so only the columns these tables always fill are named.
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).containsSubsequence("Name", "Kind", "Format", "Snapshots",
                        "Last commit");
        WebElement trades = contentsRow("trades");
        assertThat(contentsCell(trades, "fmt")).isEqualTo("v2");
        assertThat(contentsCell(trades, "snaps")).isEqualTo("3");
        assertThat(textContent(trades.findElement(By.cssSelector("td.last .tnum")))).isNotBlank();
        assertThat(textContent(trades.findElement(By.cssSelector("td.last")))).endsWith(" by " + committer);
        WebElement positions = contentsRow("positions");
        assertThat(contentsCell(positions, "fmt")).isEqualTo("v3");
        assertThat(contentsCell(positions, "snaps")).as("created, never appended to").isEqualTo("0");

        // A namespace's row counts the tables beneath it, sums their snapshots and names the newest commit by its
        // table's path below it, as the catalog's totals answer (backlog 147); it has no format of its own. A view
        // has none of the three, so with only a namespace and a view here, the format column is dropped; and a
        // namespace's header names its dotted path only where it differs from its name (backlog 193).
        HttpResponse<String> totals = expect(api.manage("GET", "/namespaces/" + ns(equities) + "/namespaces", null),
                200);
        assertThat(JsonPath.<List<String>>read(totals.body(), "$.namespaces[*].name")).containsExactly("eu");
        assertThat(JsonPath.<Integer>read(totals.body(), "$.namespaces[0].tables")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(totals.body(), "$.namespaces[0].snapshots")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(totals.body(), "$.namespaces[0].complete")).isTrue();
        List<String> newest = JsonPath.read(totals.body(), "$.namespaces[0].newest-commit.table");
        assertThat(newest).hasSize(1).isSubsetOf("trades", "positions");

        browser.open("/ui/#" + at("namespace", equities, null, null));
        WebElement euRow = contentsRow("eu");
        assertThat(contentsCell(euRow, 1)).isEqualTo("eu · 2 tables");
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).doesNotContain("Format");
        assertThat(euRow.findElements(By.cssSelector("td.fmt"))).isEmpty();
        assertThat(textContent(driver.findElement(By.cssSelector("#namespace-page .title-row code"))))
                .isEqualTo(top + ".equities");
        assertThat(contentsCell(euRow, "snaps")).isEqualTo("3");
        assertThat(textContent(euRow.findElement(By.cssSelector("td.last .tnum")))).isNotBlank();
        assertThat(textContent(euRow.findElement(By.cssSelector("td.last")))).endsWith(" in " + newest.getFirst());
        WebElement daily = contentsRow("daily");
        assertThat(List.of(contentsCell(daily, "snaps"), contentsCell(daily, "last"))).containsOnly("–");

        // A name is a link in the link colour, as every link is, and its row opens it: a chevron, and a click
        // anywhere on the row follows the name (Atelier's review, F11; backlog 189).
        WebElement euName = euRow.findElement(By.cssSelector("td.name a"));
        assertThat(euName.getCssValue("color")).as("a name in the link colour, not the text's")
                .isNotEqualTo(euRow.findElement(By.cssSelector("td.name")).getCssValue("color"));
        assertThat(driver.executeScript("return getComputedStyle(arguments[0], '::after').content", euName))
                .isEqualTo("\"›\"");
        euRow.findElement(By.xpath("./td[2]")).click();
        browser.await("the eu row did not open eu", d -> d.getCurrentUrl().endsWith("ns=" + top + "%1Fequities%1Feu"));

        // The catalog's Contents counts every level beneath each top namespace: trades and positions two levels
        // down, accounts one.
        browser.open("/ui/#" + at("namespace", List.of(top), null, null));
        browser.await(By.cssSelector("#namespace-page .title-row h1"));
        assertThat(driver.findElements(By.cssSelector("#namespace-page .title-row code")))
                .as("a top namespace's path is its name").isEmpty();
        browser.open("/ui/#c=trading");
        WebElement topRow = contentsRow(top);
        assertThat(contentsCell(topRow, 1)).isEqualTo(top + " · 3 tables");
        assertThat(textContent(topRow.findElement(By.cssSelector("td.last"))))
                .matches(".+ in (equities\\.eu\\.trades|equities\\.eu\\.positions|ledger\\.accounts)");

        // Who made the current snapshot is on the table's Overview, and each snapshot's author on Snapshots, paired
        // with its commit in the metadata log (Atelier's review, F6; backlog 186).
        browser.open("/ui/#" + at("table", eu, "trades", null));
        assertThat(textContent(browser.await(By.cssSelector("#overview-tab #current-by b")))).isEqualTo(committer);
        assertThat(textContent(driver.findElement(By.id("current-by")))).startsWith("by " + committer);
        browser.open("/ui/#" + at("table", eu, "trades", "snapshots"));
        browser.await(By.cssSelector("table.snaps td.by b"));
        assertThat(driver.findElements(By.cssSelector("table.snaps thead th"))).extracting(ConsoleBrowser::textContent)
                .contains("By");
        assertThat(driver.findElements(By.cssSelector("table.snaps tbody td.by b"))).hasSize(3)
                .extracting(ConsoleBrowser::textContent).containsOnly(committer);
    }

    @Test
    void theTableTabsShowSchemasSpecsSnapshotsAndHistory() {
        signIn(tester());
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.awaitText(By.cssSelector("#table-page .title-row .badge"), "iceberg v2");
        assertThat(fact("Schema, spec, order")).isEqualTo("schema 2 of 3 · partition spec 1 of 2 · sort order 1");
        assertThat(fact("Current snapshot")).startsWith(S3 + " · append · ");
        assertThat(kpi("Snapshots")).isEqualTo("3");
        assertThat(kpi("Schemas")).isEqualTo("3");
        // The table's own Iceberg refs are named apart from the catalog's branches and tags, with one icon of their
        // own (Atelier's review, F8; backlog 191); read through a catalog tag, they are folded away.
        WebElement engineRefs = driver.findElement(By.id("engine-refs"));
        assertThat(engineRefs.getTagName()).isEqualTo("div");
        assertThat(textContent(engineRefs.findElement(By.tagName("h2"))))
                .isEqualTo("Engine-level refs in this table's metadata");
        assertThat(engineRefs.findElements(By.cssSelector(".badge use"))).isNotEmpty()
                .allSatisfy(use -> assertThat(use.getDomAttribute("href")).isEqualTo("#i-iref"));
        String tag = "refs_" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}"), 201);
        try {
            browser.open("/ui/#" + at("table", eu, "trades", null) + "&r=" + tag);
            browser.awaitText(By.cssSelector("[data-ref-band='" + tag + "']"), "Reading the tag");
            WebElement folded = browser.await(By.cssSelector("details#engine-refs"));
            assertThat(folded.getDomAttribute("open")).isNull();
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
        }
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.awaitText(By.cssSelector("#table-page .title-row .badge"), "iceberg v2");
        // The console asks for no storage credentials, and the catalog honours that from any caller (D21, amended
        // 2026-11-19): nothing of them reaches the page or the tab's storage.
        assertThat(driver.getPageSource()).doesNotContain("storage-credentials", "secret-access-key",
                "session-token");
        assertThat(storageKeys()).containsExactly("nuna.token");

        // Schemas: the current one beside the one before it, then any two picked on the timeline.
        tab("schema");
        assertThat(driver.findElements(By.cssSelector(".schema-tl button.node"))).extracting(WebElement::getText)
                .containsExactly("0", "1", "2");
        browser.awaitText(By.cssSelector("#schema-diff h2"), "Schema 1 → schema 2");
        assertThat(schemaFields()).containsExactly("id", "symbol", "price", "venue");
        assertThat(addedFields()).containsExactly("venue");
        schemaNode("0").click();
        browser.awaitText(By.cssSelector("#schema-diff h2"), "Schema 0 → schema 2");
        assertThat(addedFields()).containsExactly("price", "venue");
        schemaNode("1").click();
        browser.awaitText(By.cssSelector("#schema-diff h2"), "Schema 0 → schema 1");
        assertThat(schemaFields()).containsExactly("id", "symbol", "price");
        assertThat(addedFields()).containsExactly("price");

        tab("partitioning");
        List<WebElement> specs = driver.findElements(By.cssSelector("#table-page [data-spec]"));
        assertThat(specs).extracting(spec -> spec.getDomAttribute("data-spec")).containsExactly("1", "0");
        assertThat(specs.get(0).findElement(By.tagName("h2")).getText()).contains("Spec 1", "Default");
        assertThat(specs.get(0).findElements(By.cssSelector(".xf")))
                .extracting(xf -> xf.findElement(By.cssSelector(".src")).getText() + " " + xf.findElement(
                        By.cssSelector(".fn")).getText() + " " + xf.findElement(By.cssSelector(".out")).getText())
                .containsExactly("symbol · 2 identity symbol · 1000");
        assertThat(specs.get(1).getText()).contains("Every data file sits in one partition.");
        assertThat(panel("Sort order 1").getText()).contains("default");
        assertThat(fact("1st")).isEqualTo("id · identity · ascending · nulls first");
        assertThat(panel("Sort order 0").getText()).contains("Unsorted");

        tab("snapshots");
        List<WebElement> snapshots = driver.findElements(By.cssSelector("table.snaps tbody tr"));
        assertThat(snapshots).extracting(row -> row.getDomAttribute("data-id"))
                .containsExactly(String.valueOf(S3), String.valueOf(S2), String.valueOf(S1));
        assertThat(snapshots.get(0).getDomAttribute("class")).contains("is-cur");
        assertThat(cell(snapshots.get(0), 3)).startsWith("append");
        assertThat(cell(snapshots.get(0), 7)).isEqualTo("main");
        assertThat(cell(snapshots.get(1), 7)).isEqualTo("review");
        assertThat(cell(snapshots.get(2), 7)).isEqualTo("close-day-1");
        assertThat(driver.findElements(By.cssSelector(".lanes circle[data-id]"))).hasSize(3);
        snapshots.get(2).findElement(By.cssSelector("button.row-pick")).click();
        browser.await("the oldest snapshot is not selected", d -> d.findElement(
                By.cssSelector("table.snaps tr[data-id='" + S1 + "']")).getDomAttribute("class").contains("is-sel"));
        String summary = textContent(browser.await(By.cssSelector("#selected-snapshot pre.raw")));
        assertThat(JsonPath.<String>read(summary, "$.operation")).isEqualTo("append");
        assertThat(JsonPath.<String>read(summary, "$.total-records")).isEqualTo("10");

        tab("metadata-log");
        String id = api.id(String.join("\u001f", eu), "tables", "trades");
        HttpResponse<String> log = expect(api.manage("GET", "/objects/" + id + "/metadata-log", null), 200);
        List<Integer> versions = JsonPath.read(log.body(), "$.journal[*].new-version");
        List<String> principals = JsonPath.read(log.body(), "$.journal[*].principal");
        List<WebElement> commits = browser.await("the metadata log is not shown", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("#metadata-log tbody tr[data-version]"));
            return found.size() == versions.size() ? found : null;
        });
        assertThat(commits).extracting(row -> row.getDomAttribute("data-version"))
                .containsExactlyElementsOf(versions.stream().map(String::valueOf).toList());
        assertThat(commits.get(0).getDomAttribute("class")).contains("is-cur");
        assertThat(cell(commits.get(0), 4)).startsWith(principals.get(0));
        commits.get(0).findElement(By.tagName("button")).click();
        String location = JsonPath.read(log.body(), "$.pointer.metadata-location");
        browser.awaitText(By.cssSelector("#metadata-log tr.mlog-det"), location);
        assertThat(driver.findElements(By.cssSelector("#snapshot-log tbody tr")))
                .extracting(row -> row.getDomAttribute("data-id"))
                .containsExactly(String.valueOf(S3), String.valueOf(S2), String.valueOf(S1));

        browser.open("/ui/#" + at("table", eu, "positions", null));
        browser.awaitText(By.cssSelector("#table-page .title-row .badge"), "iceberg v3");
        assertThat(fact("Next row id")).isNotBlank();
    }

    @Test
    void theViewTabsShowEveryVersionWithItsSql() {
        signIn(tester());
        browser.open("/ui/#" + at("view", equities, "daily", null));
        assertThat(fact("Current version")).startsWith("2 · ");
        assertThat(sql(panel("Definition"))).containsExactly("spark select 2 as x, venue",
                "trino SELECT 2 AS x, venue");
        assertThat(rows(panel("Schema"))).extracting(row -> row.get(1)).containsExactly("x", "venue");

        tab("versions");
        List<WebElement> versions = browser.await("no view versions", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("#view-page article.version"));
            return found.size() == 2 ? found : null;
        });
        assertThat(versions).extracting(v -> v.getDomAttribute("data-version")).containsExactly("2", "1");
        assertThat(versions.get(0).findElement(By.tagName("h2")).getText()).startsWith("Version 2").contains("Current");
        assertThat(sql(versions.get(0))).containsExactly("spark select 2 as x, venue", "trino SELECT 2 AS x, venue");
        assertThat(versions.get(1).findElement(By.tagName("h2")).getText()).startsWith("Version 1")
                .doesNotContain("Current");
        assertThat(sql(versions.get(1))).containsExactly("spark select 1 as x, venue");
    }

    @Test
    void governanceShowsTheFreezeTheHoldAndWhereEachValueComesFrom() {
        signIn(tester());
        browser.open("/ui/#" + at("table", eu, "trades", "governance"));
        WebElement frozen = browser.await(By.cssSelector("#governance .status-frozen"));
        assertThat(frozen.getText()).contains("DR freeze in force", FREEZE_REASON);
        assertThat(browser.await(By.cssSelector("#governance .status-held")).getText()).startsWith("1 legal hold");

        assertThat(fact("owner")).startsWith("desk-a ").contains("inherited from namespace " + top);
        assertThat(fact("classification")).startsWith("internal ").contains("this table");
        assertThat(fact("retention-class")).isEqualTo("–");

        List<WebElement> holds = driver.findElements(By.cssSelector("#table-page tr[data-hold]"));
        assertThat(holds).hasSize(1);
        assertThat(cell(holds.get(0), 1)).isEqualTo(HOLD_REASON);
        assertThat(cell(holds.get(0), 2)).isEqualTo("inherited from namespace " + top + ".equities");
        assertThat(cell(holds.get(0), 3)).endsWith("stella");
        assertThat(rows(panel("Attributes"))).extracting(row -> row.get(0) + " = " + row.get(1) + " @ " + row.get(2))
                .contains("classification = internal @ this table",
                        "owner = desk-a @ inherited from namespace " + top);

        // The level above sees the same hold as its own, and no freeze.
        browser.open("/ui/#" + at("namespace", equities, null, "governance"));
        WebElement hold = browser.await(By.cssSelector("#namespace-page tr[data-hold]"));
        assertThat(cell(hold, 2)).isEqualTo("this namespace");
        assertThat(browser.await(By.cssSelector("#governance .status-row")).getText()).contains("No DR freeze");
        assertThat(driver.findElements(By.cssSelector("#governance .status-frozen"))).isEmpty();
    }

    @Test
    void refusalsAreShownExactlyAsTheCatalogAnswered() {
        // D27: an unclassified table in a regulated namespace is refused everything but describe.
        signIn(tester());
        browser.open("/ui/#" + at("table", ledger, "accounts", null));
        assertShownVerbatim(refusal("#table-page .tab-body"), api.send("GET", "/v1/trading/namespaces/" + ns(ledger)
                + "/tables/accounts?snapshots=all", null, AUTH, tester(), "X-Iceberg-Access-Delegation", "none"));

        // Reading an object's audit trail needs manage-grants: the tab is not offered, and a link to it says so
        // without asking the catalog (backlog 184), which would refuse it.
        browser.open("/ui/#" + at("table", eu, "trades", "audit"));
        assertThat(textContent(browser.await(By.cssSelector("#table-page [data-closed] h3"))))
                .isEqualTo("An object's audit trail is open to those who manage trading's grants.");
        assertThat(driver.findElements(By.cssSelector("#table-page nav.tabs a[data-tab='audit']"))).isEmpty();
        expect(api.manage("GET", tablePath(eu, "trades") + "/audit?limit=50", null, AUTH, tester()), 403);

        // Grant administrators are kept away from the data itself (D18).
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("table", eu, "trades", null));
        assertShownVerbatim(refusal("#table-page .tab-body"), api.send("GET", "/v1/trading/namespaces/" + ns(eu)
                + "/tables/trades?snapshots=all", null, AUTH, api.grantAdmin(), "X-Iceberg-Access-Delegation", "none"));
    }

    @Test
    void grantsAreMadeAndRevokedAndASeparationOfDutiesRefusalIsShownVerbatim() {
        String group = "console-readers-" + Api.suffix();
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("namespace", equities, null, "grants"));
        grant("select", group);
        browser.awaitText(By.cssSelector("#grant-result .granted"), "Granted select to " + group + ".");
        WebElement row = browser.await(grantRow(group, "select"));
        assertThat(row.getDomAttribute("data-on")).isEqualTo("here");
        assertThat(cell(row, 1)).isEqualTo(group);
        assertThat(cell(row, 2)).isEqualTo("select");
        assertThat(cell(row, 3)).isEqualTo("here");
        assertThat(cell(row, 4)).endsWith("gina");
        browser.awaitText(whoMay("Read"), group);

        // The grants made above reach it too, each with where it was made, and "Who may" adds them up.
        WebElement fromAbove = browser.await(By.cssSelector(".grants-reach tr[data-group='" + GovernanceServer.STEWARDS
                + "'][data-privilege='manage-tags'][data-on='catalog']"));
        assertThat(cell(fromAbove, 3)).isEqualTo("catalog trading");
        assertThat(browser.await(whoMay("Manage tags")).getText()).contains(GovernanceServer.STEWARDS);
        assertThat(browser.await(whoMay("Manage grants")).getText())
                .contains(GovernanceServer.GRANT_ADMINS + ", on every catalog");

        row.findElement(By.xpath(".//button[normalize-space()='Revoke']")).click();
        WebElement dialog = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(dialog.findElement(By.tagName("h2")).getText()).isEqualTo("Revoke select from " + group);
        dialog.findElement(By.cssSelector("button[type='submit']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), "Revoked select from " + group + ".");
        dialog.findElement(By.cssSelector("button.dlg-x")).click();
        browser.await(group + " is still listed", d -> d.findElements(grantRow(group, "select")).isEmpty()
                && d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
        browser.await(group + " may still read", d -> !d.findElement(whoMay("Read")).getText().contains(group));

        // Stewards hold manage-tags, a duty that write must stay apart from (D18).
        grant("write", GovernanceServer.STEWARDS);
        WebElement refusal = browser.await(By.cssSelector("#grant-result .refusal"));
        assertShownVerbatim(refusal, api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\","
                + "\"namespace\":" + json(equities) + "},\"group\":\"" + GovernanceServer.STEWARDS
                + "\",\"privilege\":\"write\"}", AUTH, api.grantAdmin()));
        assertThat(textContent(refusal)).contains("separate");

        // Grants by group, on the governance page, from a link that names the group.
        browser.open("/ui/#p=governance&c=trading&tab=grants&g=" + GovernanceServer.STEWARDS);
        WebElement stewards = browser.await(grantRow(GovernanceServer.STEWARDS, "manage-tags"));
        assertThat(cell(stewards, 3)).isEqualTo("the whole catalog");
        assertThat(browser.await(By.cssSelector("#governance-grants input[aria-label='Group']")).getDomProperty("value"))
                .isEqualTo(GovernanceServer.STEWARDS);
        assertThat(browser.await(By.cssSelector(".duty[data-duty='tags']")).getText()).contains("granted");
    }

    @Test
    void theGovernanceSummaryCountsAndListsTheHoldTheFreezeAndTheRegulatedNamespace() {
        signIn(tester());
        browser.open("/ui/#p=governance&c=trading");
        WebElement held = browser.await(By.cssSelector("#governance-summary tr[data-kind='hold'][data-on='" + top
                + ".equities']"));
        assertThat(cell(held, 3)).isEqualTo(HOLD_REASON);
        WebElement frozen = browser.await(By.cssSelector("#governance-summary tr[data-kind='freeze'][data-on='" + top
                + ".equities.eu']"));
        assertThat(cell(frozen, 3)).isEqualTo(FREEZE_REASON);
        assertThat(Integer.parseInt(governanceKpi("holds"))).isPositive();
        assertThat(Integer.parseInt(governanceKpi("freezes"))).isPositive();
        assertThat(Integer.parseInt(governanceKpi("regulated"))).isPositive();
        WebElement regulated = browser.await(By.cssSelector("#regulated tr[data-on='" + top + ".ledger']"));
        assertThat(cell(regulated, 2)).isEqualTo("1");
        assertThat(cell(regulated, 3)).isEqualTo("1");
        browser.await(By.cssSelector("#regulated .closed-list li[data-on='" + top + ".ledger.accounts']"));

        // A hold released is listed as lifted, from what the store keeps of the release (item 133).
        List<String> archive = List.of(top, "archive_" + Api.suffix());
        expect(api.rest("POST", "/namespaces", "{\"namespace\":" + json(archive) + "}"), 200);
        String reason = "console test: matter closed " + Api.suffix();
        String holdId = JsonPath.read(expect(api.manage("POST", "/namespaces/" + ns(archive) + "/holds",
                "{\"reason\":\"" + reason + "\"}", AUTH, api.steward()), 201).body(), "$.id");
        expect(api.manage("DELETE", "/namespaces/" + ns(archive) + "/holds/" + holdId, null, AUTH, api.steward()), 204);
        browser.open("/ui/#p=governance&c=trading&tab=holds");
        browser.await(By.cssSelector("#holds-standing tr[data-kind='freeze'][data-on='" + top + ".equities.eu']"));
        WebElement lifted = browser.await("the release is not listed as lifted", d -> {
            if (d.findElements(By.id("lifted-window")).isEmpty()) {
                return null;
            }
            List<WebElement> found = d.findElements(By.cssSelector("#holds-lifted tr[data-kind='hold'][data-on='"
                    + String.join(".", archive) + "']"));
            if (found.isEmpty()) {
                d.navigate().refresh();
                return null;
            }
            return found.getFirst();
        });
        assertThat(cell(lifted, 3)).isEqualTo(reason);
        assertThat(cell(lifted, 5)).endsWith("stella");

        // The overview carries the same figures, for whoever may describe the catalog.
        browser.open("/ui/#p=overview&c=trading");
        assertThat(browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='governance']")).getText())
                .contains("Legal holds", "DR freezes", "Closed until classified");
    }

    @Test
    void theNewestNightIsDrawnOnItsSlotsWithThirtyNightsOfFigures() {
        // A night no other test reports: tomorrow's, the newest the route answers.
        LocalDate night = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1);
        Instant opens = night.atTime(1, 0).toInstant(ZoneOffset.UTC);
        String trades = api.id(String.join("\u001f", eu), "tables", "trades");
        UUID compaction = UUID.randomUUID();
        UUID scan = UUID.randomUUID();
        expect(reportJob(compaction, night, opens, "compaction", trades, 2, opens.plusSeconds(60),
                opens.plusSeconds(1_800), "succeeded", 40, 3), 204);
        expect(reportJob(scan, night, opens, "orphan-scan", trades, null, opens.plusSeconds(120),
                opens.plusSeconds(4 * 3600), "cut-off", 0, 0), 204);

        signIn(tester());
        browser.open("/ui/#p=maintenance&c=trading");
        WebElement lastNight = browser.await(By.cssSelector("#last-night[data-night='" + night + "']"));
        WebElement job = browser.await(By.cssSelector("#last-night a.gj[data-job='" + compaction + "']"));
        assertThat(job.getDomAttribute("href")).contains("k=table", "n=trades", "tab=maintenance");
        assertThat(job.getDomAttribute("aria-label")).startsWith("Compaction of " + top + ".equities.eu.trades: ")
                .endsWith("succeeded, 40 files into 3");
        browser.await(By.cssSelector("#last-night a.gj[data-job='" + scan + "'] rect.scan"));
        assertThat(lastNight.findElements(By.cssSelector("svg text.axis"))).extracting(WebElement::getText)
                .contains("Slot 1", "Slot 2", "Orphans");
        assertThat(Integer.parseInt(browser.await(By.cssSelector(
                "#night-kpis .kpi[data-kpi='tables-compacted'] .kpi-v")).getText())).isPositive();
        assertThat(driver.findElements(By.cssSelector("#night-kpis svg.spark"))).hasSize(4);

        browser.open("/ui/#p=overview&c=trading");
        assertThat(browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='maintenance']")).getText())
                .contains("Maintenance, last night", "tables compacted", "Snapshots expired");
        browser.await(By.cssSelector("#overview #last-night a.gj[data-job='" + compaction + "']"));
    }

    /**
     * The audit feed in the page's own answers: this server has no audit tables, so its feed and counts are answered in
     * the page, and the case checks what the console asks for and draws (Atelier's review, F7; backlog 187).
     */
    private static final String AUDIT_TABLES_IN_THE_PAGE = """
            const real = window.fetch;
            window.asked = [];
            const now = Date.now();
            const rec = (i, type, who, path) => ({time: new Date(now - i * 60000).toISOString(), type: 'nuna.catalog.' + type,
              principal: who, 'event-id': 'e' + i, partition: 0, offset: i, 'request-id': 'r' + i,
              object: path ? {kind: 'TABLE', path} : null, event: {data: {}}, chain: {status: 'no-archive'}});
            const records = [rec(1, 'audit-read', 'gina', null), rec(2, 'grant', 'gina', ['equities', 'eu', 'trades']),
              rec(3, 'commit', 'tester', ['equities', 'eu', 'trades']), rec(4, 'audit-read', 'gina', null)];
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              if (u.pathname.endsWith('/audit/summary')) {
                return json({'by-type': {'nuna.catalog.audit-read': 2, 'nuna.catalog.grant': 1, 'nuna.catalog.commit': 1},
                  from: new Date(now - 86400000).toISOString(), to: new Date(now).toISOString(), busiest: []});
              }
              if (u.pathname.endsWith('/audit/archive')) return json({chains: [], configured: false});
              if (u.pathname.endsWith('/audit/feed')) {
                window.asked.push(u.searchParams.get('type'));
                const types = (u.searchParams.get('type') || '').split(',').filter(Boolean);
                return json({records: records.filter(r => !types.length || types.includes(r.type))});
              }
              return real(url, init);
            };
            location.hash = '#p=audit&c=trading&kind=all';
            """;

    /**
     * Audit filters by kind, and each By kind row applies its kind (Atelier's review, F7; backlog 187): a grant admin
     * reaches a grant's record in three steps, Audit (where they land), the grant row, its record. Every filter is in
     * the link, so Back keeps it; the audit's own reads are hidden unless asked for; the range names both days.
     */
    @Test
    void auditFiltersByKindKeepsItsFiltersInTheLinkAndHidesItsOwnReads() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(AUDIT_TABLES_IN_THE_PAGE);
        browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant']"));
        assertThat(feedTypes()).containsExactly("grant", "commit");
        assertThat(lastAsked()).contains("nuna.catalog.grant", "nuna.catalog.read").doesNotContain("audit-read");
        assertThat(textContent(browser.await(By.cssSelector("#audit-kinds p.fine"))))
                .matches("\\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d to \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d, .*");

        browser.await(By.cssSelector("#audit-kinds tr[data-type='nuna.catalog.grant'] button")).click();
        browser.await("the feed did not show only grants", d -> feedTypes().equals(List.of("grant")));
        assertThat(lastAsked()).isEqualTo("nuna.catalog.grant");
        assertThat(driver.getCurrentUrl()).contains("p=audit", "kind=grant");
        assertThat(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='grant']")).getDomAttribute("aria-pressed"))
                .isEqualTo("true");
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] button")).click();
        assertThat(textContent(browser.await(By.cssSelector("#audit-feed .feed-row .rec")))).contains("r2");

        // Back from the record's table keeps the kind.
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] .what a")).click();
        browser.await("the table did not open", d -> d.getCurrentUrl().contains("n=trades"));
        driver.navigate().back();
        browser.await(By.cssSelector("#audit-feed .chip[data-f='grant'][aria-pressed='true']"));
        browser.await("the feed did not keep its kind", d -> feedTypes().equals(List.of("grant")));

        // The audit's own reads, when asked for.
        driver.findElement(By.cssSelector("#audit-feed .chip[data-f='all']")).click();
        browser.await("the feed did not show every kind", d -> feedTypes().equals(List.of("grant", "commit")));
        driver.findElement(By.cssSelector("#audit-feed input[name='audit-reads']")).click();
        browser.await("the audit's reads were not shown", d -> feedTypes().contains("audit-read"));
        assertThat(lastAsked()).isNull();
        assertThat(driver.getCurrentUrl()).contains("reads=all").doesNotContain("kind=");
    }

    /** The types of the feed's rows, as drawn. */
    private List<String> feedTypes() {
        return driver.findElements(By.cssSelector("#audit-feed .feed-row")).stream()
                .map(row -> row.getDomAttribute("data-type")).toList();
    }

    /** The type filter of the feed's last request, null for none. */
    private String lastAsked() {
        return (String) driver.executeScript("return window.asked[window.asked.length - 1];");
    }

    @Test
    void theAuditPageShowsTheFeedsAnswerVerbatimAndTheCatalogsOwnTrail() {
        // This server has no audit tables, so the feed answers 503, and the page says so as it was answered.
        signIn(api.grantAdmin());
        browser.open("/ui/#p=audit&c=trading");
        WebElement refusal = browser.await(By.cssSelector("#audit-feed .refusal"));
        HttpResponse<String> answer = api.manage("GET", "/audit/feed", null, AUTH, api.grantAdmin());
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(503);
        assertThat(textContent(refusal.findElement(By.cssSelector(".refusal-code"))))
                .isEqualTo("503 " + JsonPath.read(answer.body(), "$.error.type"));
        assertThat(textContent(refusal.findElement(By.cssSelector(".refusal-msg"))))
                .isEqualTo(JsonPath.<String>read(answer.body(), "$.error.message"));
        assertThat(refusal.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid")).isNotBlank();
        // The catalog's own records, from the audit topic, for whoever manages its grants.
        browser.await(By.cssSelector("#audit-page #audit p.window"));
        browser.await("the audit figures are shown without the audit tables",
                d -> d.findElements(By.id("audit-kpis")).isEmpty());

        // Nor does the overview show an audit figure.
        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#overview-kpis .kpi[data-kpi='maintenance']"));
        assertThat(driver.findElements(By.cssSelector("#overview-kpis .kpi[data-kpi='audit']"))).isEmpty();
    }

    @Test
    void theCatalogsPropertiesAreShownAsDeclared() {
        signIn(tester());
        browser.open("/ui/#c=trading&tab=properties");
        browser.await(By.id("catalog-properties"));
        HttpResponse<String> declared = expect(api.manage("GET", "/properties", null), 200);
        assertThat(fact("Name")).isEqualTo(JsonPath.<String>read(declared.body(), "$.name"));
        assertThat(fact("Legal entity")).isEqualTo(JsonPath.<String>read(declared.body(), "$.legal-entity"));
        assertThat(fact("Table root")).isEqualTo(JsonPath.<String>read(declared.body(), "$.storage-profile.table-root"));
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
            auditLoaded();
            seen.clear();
            for (WebElement record : driver.findElements(By.cssSelector("#audit .feed-row"))) {
                seen.add(record.getDomAttribute("data-type"));
            }
            if (seen.containsAll(wanted) || System.nanoTime() > deadline) {
                break;
            }
            // The relay publishes commits a moment after they are made: ask again.
            driver.findElement(By.xpath("//div[@id='audit']//button[normalize-space()='Refresh']")).click();
        }
        assertThat(seen).containsAll(wanted);

        WebElement denied = driver.findElements(By.cssSelector("#audit .feed-row.is-refused")).stream()
                .filter(r -> textContent(r.findElement(By.cssSelector(".w"))).startsWith("gina")
                        && textContent(r.findElement(By.cssSelector(".what"))).startsWith("refused loadTable"))
                .findFirst().orElseThrow();
        assertThat(denied.getDomAttribute("data-type")).isEqualTo("denial");
        denied.findElement(By.xpath(".//button[normalize-space()='Show record']")).click();
        String raw = textContent(browser.await(By.cssSelector("#audit .feed-row.is-refused .rec pre.raw")));
        String table = api.id(String.join("\u001f", eu), "tables", "trades");
        assertThat(JsonPath.<String>read(raw, "$.subject")).isEqualTo(table);
        assertThat(JsonPath.<String>read(raw, "$.type")).isEqualTo("nuna.catalog.denial");
        assertThat(browser.await(By.cssSelector("#audit p.window")).getText())
                .startsWith("Read from nuna.catalog.audit, partition ");

        // The kind chips keep only the refusals.
        browser.await(By.cssSelector("#audit .chip[data-f='refusal']")).click();
        browser.await("rows other than refusals are shown", d -> d.findElements(By.cssSelector("#audit .feed-row"))
                .stream().filter(WebElement::isDisplayed)
                .allMatch(r -> "denial".equals(r.getDomAttribute("data-type"))));
    }

    @Test
    void theThemeIsPickedAndKeptInThisBrowser() {
        signIn(tester());
        browser.open("/ui/#c=trading");
        browser.await(node("catalog"));
        assertThat(theme()).isNull();
        String light = background();

        browser.await(By.cssSelector("[data-theme-pick='dark']")).click();
        assertThat(theme()).isEqualTo("dark");
        assertThat(driver.executeScript("return localStorage.getItem('nuna.theme')")).isEqualTo("dark");
        assertThat(background()).isNotEqualTo(light);
        assertThat(storageKeys()).containsExactly("nuna.token");

        browser.open("/ui/#c=trading");
        browser.await(node("catalog"));
        assertThat(theme()).isEqualTo("dark");
        assertThat(browser.await(By.cssSelector("[data-theme-pick='dark']")).getDomAttribute("aria-pressed"))
                .isEqualTo("true");

        browser.await(By.cssSelector("[data-theme-pick='light']")).click();
        assertThat(theme()).isEqualTo("light");
        assertThat(background()).isEqualTo(light);
        browser.await(By.cssSelector("[data-theme-pick='auto']")).click();
        assertThat(theme()).isNull();
        assertThat(driver.executeScript("return localStorage.getItem('nuna.theme')")).isNull();
    }

    @Test
    void onAPhoneTheMenuIsADrawerAndNoPageScrollsSideways() {
        signIn(tester());
        driver.manage().window().setSize(new Dimension(390, 844));
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.awaitText(By.cssSelector("#table-page h1"), "trades");
        browser.await("the console is wider than a phone", d -> ((Number) driver.executeScript(
                "return document.getElementById('console').clientWidth")).intValue() <= 760);
        assertThat(browser.displayed(By.cssSelector(".tree"))).isEmpty();
        assertThat(sideways()).isFalse();

        WebElement menu = browser.await(By.cssSelector(".nc-menu"));
        menu.click();
        browser.await("the drawer did not open", d -> d.findElement(By.id("console")).getDomAttribute("class")
                .contains("menu-open"));
        assertThat(browser.displayed(By.cssSelector("#nav a[data-k]"))).isNotEmpty();
        driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        browser.await("the drawer did not close", d -> !d.findElement(By.id("console")).getDomAttribute("class")
                .contains("menu-open"));

        for (String page : List.of(at("table", eu, "trades", "snapshots"), at("table", eu, "trades", "schema"),
                at("namespace", equities, null, "governance"), at("namespace", equities, null, "grants"),
                "p=governance&c=trading&tab=grants", "c=trading")) {
            browser.open("/ui/#" + page);
            browser.await("the page did not load: " + page, d -> d.findElements(
                    By.cssSelector(".nc-main [data-loading]")).isEmpty() && !d.findElements(
                    By.cssSelector(".nc-main h1")).isEmpty());
            assertThat(sideways()).as(page).isFalse();
        }
    }

    /**
     * A catalog branch made in the console, committed on through the REST API as any engine commits to a warehouse,
     * then previewed and merged in the console (D33, backlog 109). Read through the branch, a table's page names it in
     * the band, the top chip, the first crumb and the selector; the merge tab and its dialog show the dry run's plan;
     * the merge moves main to the file the branch wrote and ends the branch.
     */
    @Test
    void aBranchIsMadeCommittedOnPreviewedAndMerged() {
        String ns = "refs_" + Api.suffix();
        String branch = "dev_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + ns + "\"]}"), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);

        signIn(tester());
        browser.open("/ui/#c=trading&tab=refs");
        browser.await(By.cssSelector("button[data-act='ref-new']")).click();
        WebElement make = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(textContent(make.findElement(By.id("dlg-h")))).isEqualTo("Make a branch or tag");
        assertThat(make.findElement(By.cssSelector("button[data-k='branch']")).getDomAttribute("aria-pressed"))
                .isEqualTo("true");
        make.findElement(By.cssSelector("input[name='name']")).sendKeys(branch);
        make.findElement(By.cssSelector("button[type='submit']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), "Made the branch @" + branch);
        HttpResponse<String> made = expect(api.manage("GET", "/refs/" + branch, null), 200);
        assertThat(JsonPath.<String>read(made.body(), "$.kind")).isEqualTo("branch");
        assertThat(JsonPath.<String>read(made.body(), "$.from")).isEqualTo("main");

        // An engine commits on the branch as on any warehouse, trading@<branch>; main keeps its own version.
        HttpResponse<String> committed = expect(api.send("POST", "/v1/" + Api.CATALOG + "@" + branch + "/namespaces/"
                + ns + "/tables/orders", Api.SET_PROPERTY.formatted("on-branch")), 200);
        assertThat(JsonPath.<String>read(committed.body(), "$.metadata-location")).contains(".branch-");
        assertThat(properties(ns)).doesNotContainKey("k");

        // Read through the branch, the table's page says so wherever the catalog is named.
        browser.open("/ui/#" + at("table", List.of(ns), "orders", null) + "&r=" + branch);
        browser.awaitText(By.cssSelector("#table-page h1"), "orders");
        assertThat(browser.awaitText(By.cssSelector("[data-ref-band='" + branch + "']"), "Reading the branch").getText())
                .contains("1 table changed on it");
        assertThat(textContent(browser.await(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading@" + branch);
        assertThat(textContent(driver.findElement(By.cssSelector("#table-page nav.crumbs > a"))))
                .isEqualTo("trading@" + branch);
        assertThat(browser.await(By.cssSelector("[data-ref-pick]")).getDomAttribute("aria-label"))
                .isEqualTo("Reading branch " + branch + ". Choose a branch or tag");

        // The merge tab shows the dry run: the table moves from main's version to the next one.
        HttpResponse<String> plan = expect(api.manage("POST", "/refs/" + branch + "/merge?dry-run=true", null), 200);
        int cut = JsonPath.read(plan.body(), "$.tables[0].cut-version");
        int mainNow = JsonPath.read(plan.body(), "$.tables[0].main-version");
        int onBranch = JsonPath.read(plan.body(), "$.tables[0].branch-version");
        int mainAfter = JsonPath.read(plan.body(), "$.tables[0].version");
        browser.open("/ui/#c=trading&k=ref&n=" + branch + "&tab=merge");
        browser.awaitText(By.cssSelector(".merge-verdict[data-verdict='ready']"),
                "Ready: 1 table would move to main in one transaction");
        WebElement moves = browser.await(By.cssSelector(".mm-row.moves[data-path='" + ns + ".orders']"));
        assertThat(moves.findElements(By.cssSelector("dl.mm-v dd"))).extracting(ConsoleBrowser::textContent)
                .containsExactly(String.valueOf(cut), String.valueOf(mainNow), String.valueOf(onBranch));

        // The page's Delete warns that the branch's commit was never merged: one ref's answer lists its changed
        // tables, where the list gives a count.
        browser.await(By.cssSelector("button[data-act='ref-delete']")).click();
        WebElement delete = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(textContent(delete.findElement(By.id("dlg-h")))).isEqualTo("Delete the branch @" + branch);
        assertThat(textContent(delete)).contains("Its changes to 1 table were never merged and are lost.");
        delete.findElement(By.cssSelector("button.dlg-x")).click();

        browser.await(By.cssSelector("button[data-act='ref-merge']")).click();
        WebElement confirm = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(textContent(confirm.findElement(By.id("dlg-h")))).isEqualTo("Merge @" + branch + " into main");
        assertThat(rows(confirm)).containsExactly(
                List.of(ns + ".orders", String.valueOf(mainNow), String.valueOf(mainAfter)));
        confirm.findElement(By.cssSelector("button[type='submit']")).click();
        browser.awaitText(By.cssSelector("[data-merged='" + branch + "'] .granted"), "Merged @" + branch + " into main");

        assertThat(properties(ns)).containsEntry("k", "on-branch");
        expect(api.manage("GET", "/refs/" + branch, null), 404);
    }

    /**
     * A table read as of a tag (D33, backlog 109): the page loads through trading@<tag> and shows what the tag keeps,
     * not what main committed since; the band, the top chip and the first crumb name the tag; the read-only note
     * quotes what the catalog answers a commit through it; the tree lists the tag's tables, not one made on main
     * since, and a click in it keeps the tag (backlog 173); and "Read main instead" goes back to main.
     */
    @Test
    void aTableIsReadAsOfATag() {
        String ns = "tagged_" + Api.suffix();
        String tag = "eod_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + ns + "\"]}"), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables/orders", Api.SET_PROPERTY.formatted("before")), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("fills")), 200);
        expect(api.manage("POST", "/refs", "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}"), 201);
        try {
            expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("late")), 200);
            expect(api.rest("POST", "/namespaces/" + ns + "/tables/orders", Api.SET_PROPERTY.formatted("after")), 200);
            HttpResponse<String> refused = api.send("POST", "/v1/" + Api.CATALOG + "@" + tag + "/namespaces/" + ns
                    + "/tables/orders", Api.SET_PROPERTY.formatted("through-the-tag"));
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);

            signIn(tester());
            browser.open("/ui/#" + at("table", List.of(ns), "orders", "properties") + "&r=" + tag);
            browser.awaitText(By.cssSelector("[data-ref-band='" + tag + "']"), "Reading the tag");
            assertThat(textContent(browser.await(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading@" + tag);
            assertThat(textContent(driver.findElement(By.cssSelector("#table-page nav.crumbs > a"))))
                    .isEqualTo("trading@" + tag);
            browser.await(By.cssSelector("[data-ref-note='props']"));
            assertThat(fact("k")).isEqualTo("before");
            WebElement note = browser.await(By.cssSelector(".ro-note"));
            assertThat(textContent(note.findElement(By.cssSelector(".refusal-code"))))
                    .isEqualTo("400 " + JsonPath.read(refused.body(), "$.error.type"));
            assertThat(textContent(note.findElement(By.cssSelector(".refusal-msg"))))
                    .isEqualTo(JsonPath.<String>read(refused.body(), "$.error.message"));
            assertThat(properties(ns)).containsEntry("k", "after");

            // The tree reads the tag too: it names it beside the catalog, lists what the tag keeps and not the table
            // made on main since, and a click in it stays on the tag.
            browser.await(By.cssSelector(".tree[data-ref='" + tag + "'] [data-tree-ref='" + tag + "']"));
            browser.await(node("table:" + ns + ".fills"));
            assertThat(driver.findElements(node("table:" + ns + ".late"))).isEmpty();
            driver.findElement(node("table:" + ns + ".fills")).click();
            browser.awaitText(By.cssSelector("#table-page h1"), "fills");
            assertThat(driver.getCurrentUrl()).contains("n=fills", "r=" + tag);
            browser.awaitText(By.cssSelector("[data-ref-band='" + tag + "']"), "Reading the tag");
            assertThat(driver.findElement(By.cssSelector("#nav a[data-k='catalog']")).getDomAttribute("href"))
                    .contains("r=" + tag);

            driver.findElement(By.xpath("//div[@data-ref-band='" + tag + "']//a[normalize-space()='Read main instead']"))
                    .click();
            browser.await("the page still reads the tag", d -> !d.getCurrentUrl().contains("r=" + tag)
                    && d.findElements(By.cssSelector("[data-ref-band]")).isEmpty());
            browser.awaitText(By.cssSelector("#catalog-pick [data-cat]"), "trading");
            assertThat(textContent(driver.findElement(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading");
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
        }
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

    /** The properties of table {@code orders} in namespace {@code ns}, as main has them. */
    private Map<String, String> properties(String ns) {
        return JsonPath.read(expect(api.rest("GET", "/namespaces/" + ns + "/tables/orders", null), 200).body(),
                "$.metadata.properties");
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

    /** A deep link's fragment, as the console writes it: levels joined by 0x1F, no tab for the overview. */
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
        if (tab != null) {
            fragment.append("&tab=").append(tab);
        }
        return fragment.toString();
    }

    /** A node of the tree: {@code catalog}, or a kind and a dotted path, such as {@code table:a.b.t}. */
    private static By node(String id) {
        return By.cssSelector(".tree a.node[data-node='" + id + "']");
    }

    /** The worker's report of one job of a night whose window opens at {@code opens} and lasts four hours. */
    private HttpResponse<String> reportJob(UUID id, LocalDate night, Instant opens, String kind, String object,
                                           Integer slot, Instant started, Instant ended, String outcome, long in,
                                           long out) {
        String body = "{\"night\":\"" + night + "\",\"opens\":\"" + opens + "\",\"closes\":\""
                + opens.plusSeconds(4 * 3600) + "\",\"kind\":\"" + kind + "\",\"object-id\":\"" + object + "\""
                + (slot == null ? "" : ",\"slot\":" + slot) + ",\"started-at\":\"" + started + "\",\"ended-at\":\""
                + ended + "\",\"outcome\":\"" + outcome + "\",\"files-in\":" + in + ",\"files-out\":" + out
                + ",\"snapshots-expired\":0,\"files-deleted\":0,\"bytes-deleted\":0}";
        return api.manage("PUT", "/maintenance/jobs/" + id, body, GovernanceServer.PRINCIPAL, "maintenance");
    }

    /** A governance mark on a node of the tree: {@code m-held}, {@code m-frozen} or {@code m-reg}. */
    private static By mark(String id, String cls) {
        return By.cssSelector(".tree a.node[data-node='" + id + "'] .mk i." + cls);
    }

    /** The governance cell of a namespace's row in the Contents shown. */
    private static By contentsGovernance(String name) {
        return By.cssSelector(".nc-main table.contents tr[data-name='" + name + "'] td.gov");
    }

    /** A row of the Contents shown, by name, once it is there. */
    private WebElement contentsRow(String name) {
        return browser.await(By.cssSelector(".nc-main table.contents tr[data-name='" + name + "']"));
    }

    /** A Contents row's {@code n}th cell's text, counting from 1, whether or not this width shows that column. */
    private static String contentsCell(WebElement row, int n) {
        return textContent(row.findElement(By.xpath("./td[" + n + "]"))).trim();
    }

    /** A Contents row's cell of one column, by its class (fmt, queue, gov, snaps, last), as columns come and go. */
    private static String contentsCell(WebElement row, String column) {
        return textContent(row.findElement(By.cssSelector("td." + column))).trim();
    }

    /** The groups beside {@code label} in the grants tab's "Who may". */
    private static By whoMay(String label) {
        return By.xpath("//dl[contains(@class,'who-may')]/dt[normalize-space()='" + label
                + "']/following-sibling::dd[1]");
    }

    /** A figure of the governance summary, by its key. */
    private String governanceKpi(String key) {
        return browser.await(By.cssSelector("#governance-kpis .kpi[data-kpi='" + key + "'] .kpi-v")).getText();
    }

    /** Opens a tab of the page shown and waits for its body to load. */
    private void tab(String key) {
        By link = By.cssSelector(".nc-main nav.tabs a[data-tab='" + key + "']");
        browser.await(link).click();
        browser.await("tab " + key + " is not shown", d -> d.findElements(link).stream()
                .anyMatch(a -> "page".equals(a.getDomAttribute("aria-current")))
                && d.findElements(By.cssSelector(".nc-main .tab-body [data-loading]")).isEmpty());
    }

    /** The value beside {@code label} in the page's first list of facts that has it. */
    private String fact(String label) {
        return browser.await(By.xpath("//div[contains(@class,'nc-main')]//dl[contains(@class,'facts')]"
                + "/dt[normalize-space()='" + label + "']/following-sibling::dd[1]")).getText();
    }

    /** A key figure's value. */
    private String kpi(String label) {
        return browser.await(By.xpath("//div[contains(@class,'nc-main')]//div[@class='kpi'][p[contains(@class,"
                + "'kpi-l')][normalize-space()='" + label + "']]/p[contains(@class,'kpi-v')]")).getText();
    }

    /** A panel of the page, by the start of its title. */
    private WebElement panel(String title) {
        return browser.await(By.xpath("//div[contains(@class,'nc-main')]//*[contains(concat(' ',@class,' '),"
                + "' panel ')][h2[starts-with(normalize-space(),'" + title + "')]]"));
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

    private WebElement schemaNode(String id) {
        return browser.await(By.xpath("//div[contains(@class,'schema-tl')]//button[contains(@class,'node')]"
                + "[normalize-space()='" + id + "']"));
    }

    /** The fields of the schema compared, by name, without the word on what changed. */
    @SuppressWarnings("unchecked")
    private List<String> schemaFields() {
        return (List<String>) driver.executeScript("return [...document.querySelectorAll("
                + "'#schema-diff tr[data-field]:not(.removed) .field-name')].map(td => td.firstChild.textContent)");
    }

    @SuppressWarnings("unchecked")
    private List<String> addedFields() {
        return (List<String>) driver.executeScript("return [...document.querySelectorAll("
                + "'#schema-diff tr.added .field-name')].map(td => td.firstChild.textContent)");
    }

    /** Each SQL representation under {@code within}: its dialect, a space, its SQL. */
    private static List<String> sql(WebElement within) {
        return within.findElements(By.cssSelector(".sql")).stream()
                .map(sql -> sql.getDomAttribute("data-dialect") + " " + textContent(sql.findElement(By.tagName("pre"))))
                .toList();
    }

    private static By grantRow(String group, String privilege) {
        return By.cssSelector(".nc-main tr[data-group='" + group + "'][data-privilege='" + privilege + "']");
    }

    private void grant(String privilege, String group) {
        WebElement input = browser.await(By.cssSelector(".nc-main form input[aria-label='Group']"));
        input.clear();
        input.sendKeys(group);
        new Select(driver.findElement(By.cssSelector(".nc-main form select[aria-label='Privilege']")))
                .selectByValue(privilege);
        driver.findElement(By.xpath("//div[contains(@class,'nc-main')]//form//button[@type='submit']"
                + "[normalize-space()='Grant']")).click();
    }

    /** The audit tab once its records and the window it read are shown. */
    private void auditLoaded() {
        browser.await("the trail did not load", d -> !d.findElements(By.cssSelector("#audit p.window")).isEmpty()
                && d.findElements(By.cssSelector("#audit .feed [data-loading]")).isEmpty());
    }

    private WebElement refusal(String within) {
        return browser.await(By.cssSelector(within + " .refusal"));
    }

    /**
     * The refusal on the page says exactly what the catalog answers to the same request: status, type and message,
     * with the request id the server stamped on the console's own answer.
     */
    private static void assertShownVerbatim(WebElement alert, HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(403);
        String type = JsonPath.read(answer.body(), "$.error.type");
        String message = JsonPath.read(answer.body(), "$.error.message");
        assertThat(message).isNotBlank();
        assertThat(alert.getDomAttribute("role")).isEqualTo("alert");
        assertThat(textContent(alert.findElement(By.cssSelector(".refusal-h"))).trim())
                .isEqualTo("Refused by the catalog");
        assertThat(textContent(alert.findElement(By.cssSelector(".refusal-code")))).isEqualTo("403 " + type);
        assertThat(textContent(alert.findElement(By.cssSelector(".refusal-msg")))).isEqualTo(message);
        WebElement id = alert.findElement(By.cssSelector("[data-rid]"));
        assertThat(id.getDomAttribute("data-rid")).isNotBlank();
        assertThat(textContent(id)).isEqualTo("X-Request-ID " + id.getDomAttribute("data-rid"));
    }

    @SuppressWarnings("unchecked")
    private List<String> storageKeys() {
        return (List<String>) driver.executeScript("return Object.keys(sessionStorage)");
    }

    private String theme() {
        return (String) driver.executeScript("return document.documentElement.getAttribute('data-theme')");
    }

    private String background() {
        return (String) driver.executeScript("return getComputedStyle(document.body).backgroundColor");
    }

    /** Whether the page is wider than the window, so it scrolls sideways. */
    private boolean sideways() {
        return (Boolean) driver.executeScript(
                "return document.documentElement.scrollWidth > document.documentElement.clientWidth");
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
