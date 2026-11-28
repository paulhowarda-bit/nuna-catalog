package nuna.catalog.server.governance;

import static nuna.catalog.server.ConsoleBrowser.textContent;
import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
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
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.Augmenter;
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
    /** A table's data tabs to a caller whose groups do not read it (backlog 325). */
    private static final String UNREAD_TABLE = "Reading this table's data needs select on it, which your groups do not"
            + " hold. Its grants and governance are on Access.";
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
        // The header's copy button names what it copies, the warehouse name (backlog 220).
        assertThat(textContent(driver.findElement(By.cssSelector(
                "#catalog-page .ph .side button[aria-label='Copy the warehouse name trading']")))).isEqualTo("Copy trading");

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
        assertThat(driver.executeScript("return Object.keys(localStorage).filter(k => k.startsWith('nuna.catalog.'))"
                + ".map(k => localStorage.getItem(k))")).as("kept per person (backlog 200)").isEqualTo(List.of("trading"));

        // Grant administrators read no table (D18).
        signIn(api.grantAdmin());
        browser.open("/ui/#p=overview&c=trading");
        browser.awaitText(By.cssSelector("#overview h1"), "Nothing in trading you may read.");
        assertThat(driver.findElements(By.cssSelector("#overview h1 a[data-other-catalog]"))).isEmpty();
    }

    /**
     * The empty headline never sends anyone to the audit tables' catalog, a dead end for data and maintenance, as a
     * sign-in's landing leaves it out too (the 27 Nov review: gary was told "nuna_audit has 1 table"; backlog 331).
     * This server has no audit tables, so the page is answered one, nuna_audit, holding a table the caller may select.
     */
    @Test
    void theEmptyHeadlineNamesNoAuditCatalog() {
        HasCdp cdp = (HasCdp) new Augmenter().augment(driver);
        cdp.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", AN_AUDIT_CATALOG));
        signIn(gary());
        browser.open("/ui/#p=overview&c=trading");
        browser.awaitText(By.cssSelector("#overview h1"), "Nothing in trading you may read.");
        assertThat((Boolean) driver.executeScript("return window.auditAnswered === 2"))
                .as("the catalog list and the estate were both answered with nuna_audit").isTrue();
        assertThat(driver.findElements(By.cssSelector("#overview h1 a[data-other-catalog]"))).isEmpty();
    }

    /** Answers the catalog list and the estate each with the audit tables' catalog, nuna_audit, and one table in it. */
    private static final String AN_AUDIT_CATALOG = """
            (() => {
              const real = window.fetch;
              window.auditAnswered = 0;
              const add = (url, init, edit) => real(url, init).then(async answer => {
                if (!answer.ok) return answer;
                const body = await answer.json();
                edit(body);
                window.auditAnswered++;
                return new Response(JSON.stringify(body), {status: 200, headers: {'Content-Type': 'application/json'}});
              });
              window.fetch = (url, init) => {
                const path = new URL(url, location.origin).pathname;
                if (path.endsWith('/management/v1/catalogs')) {
                  return add(url, init, b => b.catalogs.push({name: 'nuna_audit', audit: true, may: {select: true}}));
                }
                if (path.endsWith('/management/v1/maintenance/estate')) {
                  return add(url, init, b => { b.tables = (b.tables || []).concat([{catalog: 'nuna_audit',
                    namespace: ['audit'], name: 'events'}]); });
                }
                return real(url, init);
              };
            })();
            """;

    /**
     * Signing in draws no rail item until the catalog list has said what the caller's groups may use, and the loading
     * line sits inside the page's padding, not in its corner (the 27 Nov review: gary's landing sat on an unstyled
     * "Loading…" with all eight rail items drawn; backlog 331). The list is held here until both are seen.
     */
    @Test
    void signInDrawsNoRailItemBeforeTheCatalogListAnswers() {
        HasCdp cdp = (HasCdp) new Augmenter().augment(driver);
        cdp.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", HOLD_THE_CATALOG_LIST));
        signIn(gary());
        browser.open("/ui/");
        browser.await("the console did not ask for the catalog list",
                d -> (Boolean) driver.executeScript("return window.heldList.asked"));
        WebElement line = browser.await(By.cssSelector("#slot > .loading-line"));
        assertThat(driver.findElements(By.cssSelector("#nav a[data-k]"))).hasSize(8).noneMatch(WebElement::isDisplayed);
        assertThat(List.of(line.getCssValue("padding-top"), line.getCssValue("padding-left")))
                .as("the page's padding").containsExactly("24px", "28px");
        driver.executeScript("window.heldList.release()");
        browser.await("gary did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        assertThat(rail()).containsExactly("audit", "catalog", "refs", "governance", "grants");
    }

    /** Holds the console's catalog list until {@code window.heldList.release()}. */
    private static final String HOLD_THE_CATALOG_LIST = """
            (() => {
              const real = window.fetch;
              let release;
              const gate = new Promise(done => release = done);
              window.heldList = {asked: false, release};
              window.fetch = (url, init) => {
                if (!new URL(url, location.origin).pathname.endsWith('/management/v1/catalogs')) return real(url, init);
                window.heldList.asked = true;
                return gate.then(() => real(url, init));
              };
            })();
            """;

    /**
     * The overview's tiles fill their rows: a presenter's six sit as three columns of two rows at 1,440 px and two
     * columns of three at 1,280, each row's first tile with no left border and every row after the first ruled above
     * (the 27 Nov review; backlog 331). And the estate's namespace labels never run together, as "markets 5reference 1"
     * did: one too long for the room beside its marks is left out, its marks naming it on hover.
     */
    @Test
    void overviewTilesFillTheirRowsAndTheEstatesLabelsNeverRunTogether() {
        String crowded = "a_namespace_named_far_too_long_for_one_mark_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + crowded + "\"]}"), 200);
        api.table(crowded, "lonely");
        signIn(api.everyDuty());
        // The console's frame is hidden until a page signed in is drawn, so one is drawn before it is measured.
        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#who strong"));
        for (int[] at : new int[][] {{1440, 2}, {1280, 3}, {1920, 2}}) {
            frameOf(at[0]);
            browser.open("/ui/#p=overview&c=trading");
            browser.await("the six tiles did not come",
                    d -> d.findElements(By.cssSelector("#overview-kpis > .kpi")).size() == 6);
            @SuppressWarnings("unchecked")
            Map<String, Object> tiles = (Map<String, Object>) driver.executeScript(TILE_ROWS);
            assertThat(tiles.get("rows")).as("rows of tiles at %d px", at[0]).isEqualTo((long) at[1]);
            assertThat(tiles.get("wrong")).as("the tiles' rows and rules at %d px", at[0]).isEqualTo("");

            browser.await(By.cssSelector("#overview [data-iceline='estate'] svg .labs"));
            assertThat((String) driver.executeScript(LABELS_RUN_TOGETHER)).as("the estate's labels at %d px", at[0])
                    .isEmpty();
            assertThat(driver.findElements(By.cssSelector("#overview [data-iceline='estate'] .labs text.lab-strong")))
                    .extracting(ConsoleBrowser::textContent).as("the long name has no room beside its one mark")
                    .doesNotContain(crowded);
            WebElement group = driver.findElement(By.cssSelector("#overview [data-iceline='estate'] a.ga[aria-label^='"
                    + crowded + ",']"));
            assertThat(textContent(group.findElement(By.tagName("title")))).startsWith(crowded + ", 1 table");
        }
    }

    /** The overview's tiles by row: how many rows, and each tile whose rules are wrong for its place. */
    private static final String TILE_ROWS = """
            const tiles = [...document.querySelectorAll('#overview-kpis > .kpi')];
            const tops = [...new Set(tiles.map(t => Math.round(t.getBoundingClientRect().top)))];
            const rows = tops.map(y => tiles.filter(t => Math.round(t.getBoundingClientRect().top) === y));
            const wrong = [];
            if (new Set(rows.map(r => r.length)).size !== 1) wrong.push('rows of ' + rows.map(r => r.length).join(', '));
            rows.forEach((r, i) => r.forEach((t, j) => {
              const cs = getComputedStyle(t);
              if ((cs.borderLeftWidth !== '0px') !== (j > 0)) wrong.push(`row ${i + 1}, tile ${j + 1}: left ${cs.borderLeftWidth}`);
              if ((cs.borderTopWidth !== '0px') !== (i > 0)) wrong.push(`row ${i + 1}, tile ${j + 1}: top ${cs.borderTopWidth}`);
            }));
            return {rows: rows.length, wrong: wrong.join('; ')};
            """;

    /** The estate's labels that touch another, or run past the next namespace's first mark, as "a and b". */
    private static final String LABELS_RUN_TOGETHER = """
            const svg = document.querySelector('#overview [data-iceline="estate"] svg');
            const texts = [...svg.querySelectorAll('.labs text.lab-strong, .labs text.lab:not([text-anchor])')];
            const boxes = texts.map(t => ({t: t.textContent, b: t.getBoundingClientRect()}));
            const hits = [];
            for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
              const a = boxes[i].b, b = boxes[j].b;
              if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) hits.push(boxes[i].t + ' and ' + boxes[j].t);
            }
            return hits.join('; ');
            """;

    /**
     * Every row action is one ghost button, alike on Branches and tags and on Governance: Read, Merge… and Renew as
     * Lift and Release are, and Delete last, set apart and in the bad ink (the 27 Nov review; backlog 331).
     */
    @Test
    void rowActionsLookAlikeWithDeleteLastAndSetApart() {
        String branch = "acts_" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + branch + "\",\"kind\":\"branch\"}"), 201);
        try {
            signIn(api.everyDuty());
            browser.open("/ui/#c=trading&tab=refs");
            WebElement acts = browser.await(By.cssSelector("tr[data-ref='" + branch + "'] .row-acts"));
            List<WebElement> buttons = acts.findElements(By.cssSelector(":scope > .btn"));
            assertThat(buttons).extracting(ConsoleBrowser::textContent).containsExactly("Read", "Merge…", "Renew", "Delete");
            WebElement delete = buttons.getLast();
            assertThat(delete.getAccessibleName()).isEqualTo("Delete @" + branch);
            List<String> look = look(buttons.getFirst());
            assertThat(buttons).allSatisfy(b -> assertThat(look(b)).as(textContent(b)).isEqualTo(look));
            assertThat(delete.getCssValue("color")).isNotEqualTo(buttons.getFirst().getCssValue("color"));
            int gap = buttons.get(2).getRect().getX() - (buttons.get(1).getRect().getX() + buttons.get(1).getRect().getWidth());
            int apart = delete.getRect().getX() - (buttons.get(2).getRect().getX() + buttons.get(2).getRect().getWidth());
            assertThat(apart).as("delete set apart").isGreaterThan(gap);

            browser.open("/ui/#p=governance&c=trading&tab=holds");
            WebElement lift = browser.await(By.cssSelector("#holds-standing tr[data-kind='freeze'][data-on='" + top
                    + ".equities.eu'] .row-acts > .btn"));
            assertThat(textContent(lift)).isEqualTo("Lift");
            assertThat(look(lift)).isEqualTo(look);
        } finally {
            api.manage("DELETE", "/refs/" + branch, null);
        }
    }

    /** What makes a row action look as it does, but its ink: its outline, ground, size, type and corners. */
    private static List<String> look(WebElement button) {
        return List.of("border-top-style", "border-top-width", "border-top-color", "background-color", "height",
                "font-size", "font-weight", "padding-left", "border-top-left-radius").stream()
                .map(button::getCssValue).toList();
    }

    /**
     * Each role sees only the pages, tabs and buttons its groups can use, and lands where its work is (Atelier's
     * review, F4 and F12; backlog 184): someone who reads data on the overview, with no Audit or Service; a grant admin
     * on Audit, with no Overview or Maintenance; a platform operator on Service, with no catalog to pick.
     */
    @Test
    void eachRoleSeesWhatItsGroupsCanUseAndLandsWhereItsWorkIs() {
        signIn(tester());
        browser.open("/ui/");
        browser.await("the tester did not land on the overview", d -> d.getCurrentUrl().endsWith("#p=overview&c=trading"));
        assertThat(rail()).containsExactly("overview", "catalog", "refs", "maintenance", "governance");
        browser.open("/ui/#" + at("table", eu, "trades", "governance"));
        browser.await(By.cssSelector("#governance .status-row"));
        // A table's six tabs, Audit left out for one who may not read its trail (backlog 199); Access leads with
        // what the tester's groups may do there.
        assertThat(driver.findElements(By.cssSelector("#table-page nav.tabs a[data-tab]")))
                .extracting(a -> a.getDomAttribute("data-tab"))
                .containsExactly("overview", "schema", "history", "access", "maintenance");
        assertThat(driver.findElements(By.cssSelector("#governance [data-acts='labels']"))).isEmpty();
        browser.await(By.cssSelector("#access-tab #you-may li"));
        // It leads: the slots before it that stay empty (the closed notice, on an open table) take no place.
        assertThat((Boolean) driver.executeScript("const first = [...document.getElementById('access-tab').children]"
                + ".find(e => e.getClientRects().length > 0); return !!first && first.contains(document.getElementById('you-may'))"))
                .as("What you may do leads the Access tab").isTrue();
        assertThat(driver.findElements(By.cssSelector("#you-may li"))).extracting(ConsoleBrowser::textContent)
                .as("the testers' create and modify on the catalog, in words")
                .containsExactly("Read and write its data, and change it: its schema, properties and maintenance.");
        browser.await(By.cssSelector("#access-tab [data-grants='own']"));
        assertThat(driver.findElements(By.cssSelector(".nc-main [data-act='grant-access']"))).isEmpty();
        browser.open("/ui/#p=audit&c=trading");
        assertThat(textContent(browser.await(By.cssSelector("#closed-page [data-closed] h3"))))
                .isEqualTo("Audit is open to auditors and to those who manage trading's grants.");

        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        // The home comes first in the rail (Atelier's re-walk after phase 2, backlog 217).
        assertThat(rail()).containsExactly("audit", "catalog", "refs", "governance", "grants");

        signIn(api.operator());
        browser.open("/ui/");
        browser.await("the operator did not land on Service", d -> d.getCurrentUrl().endsWith("#p=service"));
        // He holds nothing in the catalog: Catalog and Branches and tags are not drawn, and his Governance is the DR
        // freeze alone (backlog 325).
        assertThat(rail()).containsExactly("service", "governance");
        // Service is the whole deployment's: it offers no catalog to pick (backlog 200).
        assertThat(driver.findElement(By.cssSelector(".pick-wrap")).isDisplayed()).isFalse();
        // An operator freezes a namespace from Governance, not only from its Access tab (backlog 221), where neither
        // the summary nor the holds list, which need describe, is asked for (backlog 325).
        browser.open("/ui/#p=governance&c=trading&tab=holds");
        browser.await(By.cssSelector("#governance-freeze [data-act='freeze-path']")).click();
        assertThat(driver.findElements(By.cssSelector("#governance-holds, #governance-summary, .nc-main .refusal"))).isEmpty();
        WebElement named = browser.await(By.cssSelector("dialog.dlg"));
        named.findElement(By.cssSelector("input[name='path']")).sendKeys(top + ".equities.eu");
        named.findElement(By.cssSelector("button[type='submit']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg #dlg-h"), "Freeze eu for DR");
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();

        // One who holds every duty, as the demo's presenter does, opens on the overview and walks the rail in its
        // usual order (Atelier, 2026-11-24; backlog 236).
        signIn(api.everyDuty());
        browser.open("/ui/");
        browser.await("the caller with every duty did not land on the overview",
                d -> d.getCurrentUrl().endsWith("#p=overview&c=trading"));
        assertThat(rail())
                .containsExactly("overview", "catalog", "refs", "maintenance", "governance", "grants", "audit", "service");
    }

    /**
     * Grants have a place in the rail, after Governance, for those who manage them, and a table or view opens on
     * Access for one who may manage its grants but not read it (Paul, 2026-11-24: how to add grants from the console;
     * backlog 245). As the demo's people: gary, a grant admin who reads no data, and ana, who reads and changes it and
     * manages no grants.
     */
    @Test
    void grantsHaveARailItemAndAGrantAdminOpensATableOrViewOnAccess() {
        signIn(gary());
        browser.open("/ui/#c=trading");
        assertThat(rail()).containsExactly("audit", "catalog", "refs", "governance", "grants");
        browser.await(By.cssSelector("#nav a[data-k='grants']")).click();
        browser.await("the rail did not open Grants", d -> d.getCurrentUrl().endsWith("#p=grants&c=trading"));
        browser.await(By.cssSelector("#nav a[data-k='grants'][aria-current='page']"));
        assertThat(textContent(browser.await(By.cssSelector("#governance-grants h1")))).isEqualTo("Grants in trading");
        assertThat(textContent(browser.await(By.cssSelector("#governance-grants .ph button[data-act='grant-access']"))))
                .isEqualTo("Grant access");
        assertThat(driver.findElements(By.cssSelector("#governance-grants nav.tabs"))).isEmpty();
        // Governance keeps Summary and Holds and freezes.
        browser.await(By.cssSelector("#nav a[data-k='governance']")).click();
        browser.await(By.cssSelector("#governance-summary nav.tabs a[data-tab]"));
        assertThat(driver.findElements(By.cssSelector("#governance-summary nav.tabs a[data-tab]")))
                .extracting(ConsoleBrowser::textContent).containsExactly("Summary", "Holds and freezes");
        // A link to Governance's old Grants tab opens the rail's Grants.
        browser.open("/ui/#p=governance&c=trading&tab=grants");
        browser.await(By.cssSelector("#nav a[data-k='grants'][aria-current='page']"));
        browser.awaitText(By.cssSelector("#governance-grants h1"), "Grants in trading");

        // A table opens on Access, where he grants on it; Overview, which refuses him, is a tab away by its own link.
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.await(By.cssSelector("#table-page nav.tabs a[data-tab='access'][aria-current='page']"));
        browser.await(By.cssSelector("#access-tab > .access-acts button[data-act='grant-access']"));
        WebElement overview = driver.findElement(By.cssSelector("#table-page nav.tabs a[data-tab='overview']"));
        assertThat(overview.getDomAttribute("href")).isEqualTo("#" + at("table", eu, "trades", "overview"));
        overview.click();
        browser.await(By.cssSelector("#table-page nav.tabs a[data-tab='overview'][aria-current='page']"));
        // It stays on offer and says, without asking the catalog, that his groups do not read it (backlog 325).
        assertThat(textContent(browser.await(By.cssSelector("#table-page .tab-body [data-closed] h3"))))
                .isEqualTo(UNREAD_TABLE);
        // So does a view.
        browser.open("/ui/#" + at("view", equities, "daily", null));
        browser.await(By.cssSelector("#view-page nav.tabs a[data-tab='access'][aria-current='page']"));
        browser.await(By.cssSelector("#access-tab > .access-acts button[data-act='grant-access']"));

        // ana manages no grants: no Grants in her rail, a link to it says why, and a table and a view open on Overview.
        signIn(ana());
        browser.open("/ui/#c=trading");
        assertThat(rail()).containsExactly("overview", "catalog", "refs", "maintenance", "governance");
        browser.open("/ui/#p=grants&c=trading");
        assertThat(textContent(browser.await(By.cssSelector("#closed-page [data-closed] h3"))))
                .isEqualTo("Grants in trading are open to those who manage its grants.");
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.await(By.cssSelector("#table-page nav.tabs a[data-tab='overview'][aria-current='page']"));
        assertThat(driver.findElement(By.cssSelector("#table-page nav.tabs a[data-tab='overview']"))
                .getDomAttribute("href")).isEqualTo("#" + at("table", eu, "trades", null));
        browser.await(By.cssSelector("#overview-tab"));
        browser.open("/ui/#" + at("view", equities, "daily", null));
        browser.await(By.cssSelector("#view-page nav.tabs a[data-tab='overview'][aria-current='page']"));
    }

    /**
     * Browsing writes no refusal the console could have known from the caller's own grants (the 27 Nov review, item 3;
     * backlog 325), as the demo's gary, cora and oscar: a table's or view's data tabs stay on offer and say in a closed
     * note that the caller's groups do not read it, without asking for its load or its health; a platform operator
     * who holds nothing in the catalog is not offered its namespaces or branches and tags, and his Governance is the
     * DR freeze alone. One refusal asked for on purpose at the end shows the audit log is being read.
     */
    @Test
    void browsingWritesNoRefusalTheCallersOwnGrantsForetell() {
        String compliance = "compliance-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, compliance, "describe");
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":" + json(eu)
                + "},\"group\":\"" + compliance + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        try (AuditLogCapture log = new AuditLogCapture()) {
            // gary manages grants and reads nothing.
            signIn(gary());
            for (String tab : new String[] {"overview", "schema", "history", "maintenance"}) {
                browser.open("/ui/#" + at("table", eu, "trades", tab));
                assertThat(textContent(browser.await(By.cssSelector("#table-page .tab-body [data-closed] h3"))))
                        .as(tab).isEqualTo(UNREAD_TABLE);
            }
            assertThat(driver.findElements(By.cssSelector("#table-page nav.tabs a[data-tab]")))
                    .as("every tab stays on offer (Atelier, 2026-11-27)").extracting(a -> a.getDomAttribute("data-tab"))
                    .containsExactly("overview", "schema", "history", "access", "maintenance", "audit");

            // cora reads eu only: elsewhere she is told per object, not from what she may do somewhere in the catalog.
            signIn(person("cora", compliance));
            browser.open("/ui/#" + at("table", eu, "trades", "overview"));
            browser.await(By.cssSelector("#overview-tab"));
            for (String tab : new String[] {"overview", "maintenance"}) {
                browser.open("/ui/#" + at("table", ledger, "accounts", tab));
                assertThat(textContent(browser.await(By.cssSelector("#table-page .tab-body [data-closed] h3"))))
                        .as(tab).isEqualTo(UNREAD_TABLE);
            }
            browser.open("/ui/#" + at("view", equities, "daily", "overview"));
            assertThat(textContent(browser.await(By.cssSelector("#view-page .tab-body [data-closed] h3"))))
                    .isEqualTo("Reading this view's data needs select on it, which your groups do not hold. Its grants and"
                            + " governance are on Access.");

            // oscar operates the deployment and holds nothing in trading.
            signIn(person("oscar", GovernanceServer.PLATFORM_OPS));
            browser.open("/ui/#p=governance&c=trading");
            browser.await(By.cssSelector("#governance-freeze [data-act='freeze-path']"));
            assertThat(driver.findElements(By.cssSelector("#governance-summary, #governance-holds"))).isEmpty();
            // The standing freezes he may lift, from the operators' own list, which writes no denial (D55, item 323).
            browser.await(By.cssSelector("#operator-freezes tr[data-on='" + String.join(".", eu) + "']"));
            assertThat(rail()).containsExactly("service", "governance");
            browser.open("/ui/#c=trading");
            assertThat(textContent(browser.await(By.cssSelector("#closed-page [data-closed] h3"))))
                    .isEqualTo("trading's namespaces, tables, branches and tags are open to those whose groups hold a"
                            + " grant in it.");
            browser.open("/ui/#c=trading&tab=refs");
            browser.awaitText(By.cssSelector("#closed-page h1"), "Branches and tags");

            // The one refusal asked for: once it is in the log, nothing before it was refused to any of them.
            expect(api.send("GET", "/v1/trading/namespaces/" + ns(eu) + "/tables/trades", null, AUTH, gary()), 403);
            long deadline = System.nanoTime() + ConsoleBrowser.WAIT.toNanos();
            while (denials(log, "gary").isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(denials(log, "gary")).extracting(Denial::operation).containsExactly("loadTable");
            assertThat(denials(log, "cora")).isEmpty();
            assertThat(denials(log, "oscar")).isEmpty();
        }
    }

    /** The refusals the audit log holds for {@code principal}, in order. */
    private static List<Denial> denials(AuditLogCapture log, String principal) {
        return log.records().stream().map(CatalogEvent::data)
                .filter(d -> d instanceof Denial denial && denial.actor().principal().equals(principal))
                .map(Denial.class::cast).toList();
    }

    /**
     * Branches and tags have a place in the rail for anyone who may list them (Paul, 2026-11-23: "how do i create a
     * branch"; backlog 221): it opens the catalog's page of them, current there and not Catalog, with the Reading
     * control; one whose groups don't hold create reads why there, with no button to make one.
     */
    @Test
    void theRailOpensBranchesAndTagsAndSaysWhyOneMayNotMakeOne() {
        signIn(api.grantAdmin());
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#nav a[data-k='refs']")).click();
        browser.await("the rail did not open Branches and tags", d -> d.getCurrentUrl().endsWith("#c=trading&tab=refs"));
        browser.await(By.cssSelector("#nav a[data-k='refs'][aria-current='page']"));
        assertThat(driver.findElements(By.cssSelector("#nav a[data-k='catalog'][aria-current]"))).isEmpty();
        assertThat(textContent(browser.await(By.cssSelector("#refs-page h1")))).isEqualTo("Branches and tags in trading");
        assertThat(textContent(browser.await(By.cssSelector("#refs-page [data-no-create]"))))
                .startsWith("You can read every branch and tag here. Making, renewing, merging or deleting one needs "
                        + "create on trading");
        assertThat(driver.findElements(By.cssSelector("[data-act='ref-new']"))).isEmpty();
        browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]")).click();
        browser.await(By.cssSelector("#ref-menu a.mfoot"));
        assertThat(driver.findElements(By.cssSelector("#ref-menu [data-act='ref-new-menu']"))).isEmpty();
        // The catalog's own page keeps Contents and Properties.
        browser.open("/ui/#c=trading");
        assertThat(browser.await(By.cssSelector("#catalog-page nav.tabs a[data-tab]")).isDisplayed()).isTrue();
        assertThat(driver.findElements(By.cssSelector("#catalog-page nav.tabs a[data-tab]")))
                .extracting(a -> a.getDomAttribute("data-tab")).containsExactly("overview", "properties");
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
                .isEqualTo("No namespace, table or view you may see has a name like that, in any catalog. Check the "
                        + "spelling, or type fewer letters.");
        box.clear();
        // The catalog's search for the words is held until the look-up has answered, so it comes back after it every
        // time, and the answer must stand (backlog 305; the overnight run of 2026-11-27 had the search first).
        driver.executeScript(HOLD_THE_SEARCH, "nothing_here");
        box.sendKeys(top + ".equities.nothing_here");
        box.sendKeys(Keys.ENTER);
        By notThere = By.cssSelector("#goto-list [data-goto-none='" + top + ".equities.nothing_here']");
        browser.await(notThere);
        browser.await("the search was not asked", d -> (Boolean) driver.executeScript("return window.held.asked"));
        driver.executeScript("window.held.release()");
        browser.await("the search did not answer", d -> (Boolean) driver.executeScript("return window.held.answered"));
        driver.executeAsyncScript("setTimeout(arguments[arguments.length - 1], 500)");
        assertThat(driver.findElements(notThere)).as("the look-up's answer, after the search came back").hasSize(1);
        assertThat(driver.getCurrentUrl()).endsWith("#p=overview&c=trading");

        // A name typed alone is found though no page has listed its namespace: the catalog's search knows every name
        // the tester may describe (Atelier's review, S1; backlog 198). Then it comes first, as opened lately.
        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#overview-kpis"));
        browser.await(By.cssSelector(".nc-go")).click();
        box = browser.await(By.id("goto-in"));
        box.sendKeys(top + " positions");
        WebElement match = browser.await(By.cssSelector("#goto-list [data-goto-target='trading:" + top + ".equities.eu.positions']"));
        // A match in the catalog being read names it too (Atelier's re-walk after phase 2, backlog 217).
        assertThat(textContent(match.findElement(By.cssSelector(".gp [data-goto-catalog='trading']")))).isEqualTo("in trading");
        match.click();
        browser.awaitText(By.cssSelector("#table-page h1"), "positions");
        browser.open("/ui/#p=overview&c=trading");
        browser.await(By.cssSelector("#overview-kpis"));
        browser.await(By.cssSelector(".nc-go")).click();
        browser.awaitText(By.id("goto-h"), "Opened lately");
        assertThat(browser.await(By.cssSelector("#goto-list li[role='option']")).getDomAttribute("data-goto-target"))
                .isEqualTo("trading:" + top + ".equities.eu.positions");

        // The tree's filter lists only what is open; when nothing open matches, it hands its words to Go to a path,
        // which searches every name (Atelier's words table, "Not words" 4; backlog 208).
        browser.open("/ui/#c=trading");
        browser.await(node("namespace:" + top));
        driver.findElement(By.cssSelector(".tree input[aria-label='Filter names in the tree']")).sendKeys("accounts");
        WebElement none = browser.await(By.cssSelector(".tree li.tree-none"));
        assertThat(textContent(none)).startsWith("No open namespace in trading holds a matching name.");
        none.findElement(By.xpath(".//button[normalize-space()='Go to a path']")).click();
        assertThat(browser.await(By.id("goto-in")).getDomProperty("value")).isEqualTo("accounts");
        browser.await(By.cssSelector("#goto-list [data-goto-target='trading:" + top + ".ledger.accounts']")).click();
        browser.awaitText(By.cssSelector("#table-page h1"), "accounts");
    }

    /**
     * A namespace's Contents gives each table its format, snapshot count and last data change, as the catalog's
     * listing of them answers (backlogs 137 and 330): here the entry of the table's metadata log before the newest,
     * which appended rows, the newest having set a tag and a branch, "12:49 by lake"; a table created and never written
     * to has none. Each child namespace gets its
     * totals over the tables beneath it (backlog 147), its newest data change "12:49 in rates" (backlog 186).
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
        // trades' newest commit set its tag and branch, which changes no rows (backlog 330); the one before appended.
        assertThat(JsonPath.<Integer>read(listed.body(), "$.tables[1].last-data-change.version"))
                .as("trades' last append, not the refs set after it").isEqualTo(JsonPath.<Integer>read(log.body(),
                        "$.journal[1].new-version"));
        assertThat(JsonPath.<List<Object>>read(listed.body(), "$.tables[?(@.name == 'positions')].last-data-change"))
                .as("positions was created and never written to").isEmpty();

        signIn(tester());
        browser.open("/ui/#" + at("namespace", eu, null, null));
        browser.await(By.cssSelector("#namespace-page table.contents tr[data-name='trades']"));
        // A column with nothing on any row is dropped (backlog 193): the queue and governance columns come and go with
        // what other tests leave, so only the columns these tables always fill are named. Kind says "table" on both
        // rows, so it goes too (the 27 Nov review; backlog 331); Format stays, v2 beside v3.
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).containsSubsequence("Name", "Format", "Snapshots",
                        "Last data change")
                .doesNotContain("Kind");
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents td.kind"))).isEmpty();
        WebElement trades = contentsRow("trades");
        assertThat(contentsCell(trades, "fmt")).isEqualTo("v2");
        assertThat(contentsCell(trades, "snaps")).isEqualTo("3");
        assertThat(textContent(trades.findElement(By.cssSelector("td.last .tnum")))).isNotBlank();
        assertThat(textContent(trades.findElement(By.cssSelector("td.last")))).endsWith(" by " + committer);
        WebElement positions = contentsRow("positions");
        assertThat(contentsCell(positions, "fmt")).isEqualTo("v3");
        assertThat(contentsCell(positions, "snaps")).as("created, never appended to").isEqualTo("0");
        assertThat(contentsCell(positions, "last")).as("no data change yet").isEqualTo("–");

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
        List<String> newest = JsonPath.read(totals.body(), "$.namespaces[0].newest-data-change.table");
        assertThat(newest).as("positions holds no data change").containsExactly("trades");

        browser.open("/ui/#" + at("namespace", equities, null, null));
        WebElement euRow = contentsRow("eu");
        assertThat(contentsCell(euRow, 1)).isEqualTo("eu · 2 tables");
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).doesNotContain("Format").contains("Kind");
        assertThat(euRow.findElements(By.cssSelector("td.fmt"))).isEmpty();
        // A namespace beside a view keeps Kind, each drawn as the tree draws it, its icon then its word, with no chip
        // around it (backlog 331).
        WebElement euKind = euRow.findElement(By.cssSelector("td.kind"));
        assertThat(textContent(euKind).trim()).isEqualTo("namespace");
        assertThat(euKind.findElements(By.cssSelector(".badge"))).isEmpty();
        assertThat(euKind.findElement(By.cssSelector("svg.ico-n use")).getDomAttribute("href")).isEqualTo("#i-ns");
        assertThat(contentsRow("daily").findElement(By.cssSelector("td.kind svg.ico-v")).isDisplayed()).isTrue();
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

        // Who made the current snapshot is on the table's Overview, and each snapshot's author on History, the
        // commit that added it (Atelier's review, F6; backlog 186). An old link to Snapshots opens History (backlog 199).
        browser.open("/ui/#" + at("table", eu, "trades", null));
        assertThat(textContent(browser.await(By.cssSelector("#overview-tab #current-by b")))).isEqualTo(committer);
        assertThat(textContent(driver.findElement(By.id("current-by")))).startsWith("by " + committer);
        browser.open("/ui/#" + at("table", eu, "trades", "snapshots"));
        browser.await(By.cssSelector("#metadata-log tr[data-id] td.by b"));
        assertThat(driver.findElement(By.cssSelector("#table-page nav.tabs a[aria-current]")).getDomAttribute("data-tab"))
                .isEqualTo("history");
        assertThat(driver.findElements(By.cssSelector("#metadata-log thead th"))).extracting(ConsoleBrowser::textContent)
                .contains("Snapshot", "By");
        assertThat(driver.findElements(By.cssSelector("#metadata-log tbody tr[data-id] td.by b"))).hasSize(3)
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
            // Folded, its caption keeps the panel's meta style, not the heading's bold, so the two do not run together
            // (the 27 Nov review; backlog 331).
            WebElement caption = folded.findElement(By.cssSelector("summary h2 .meta"));
            assertThat(textContent(caption)).isEqualTo("Iceberg's own, not the catalog's");
            assertThat(caption.getCssValue("font-weight")).isEqualTo("400");
            assertThat(folded.findElement(By.cssSelector("summary h2")).getCssValue("font-weight")).isEqualTo("600");
            assertThat(caption.getCssValue("color"))
                    .isNotEqualTo(folded.findElement(By.cssSelector("summary h2")).getCssValue("color"));
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

        // Partitioning and sort order are on Schema (backlog 199).
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

        // History: one row per commit, newest first, each with the snapshot it added (backlog 199).
        tab("history");
        List<WebElement> snapshots = browser.await("the commits that added snapshots are not shown", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("#metadata-log tbody tr[data-id]"));
            return found.size() == 3 ? found : null;
        });
        assertThat(snapshots).extracting(row -> row.getDomAttribute("data-id"))
                .containsExactly(String.valueOf(S3), String.valueOf(S2), String.valueOf(S1));
        assertThat(snapshots.get(0).getDomAttribute("class")).as("the current snapshot is selected").contains("is-sel");
        assertThat(cell(snapshots.get(0), 4)).isEqualTo(S3 + " · append");
        assertThat(cell(snapshots.get(0), 5)).isEqualTo("+10");
        assertThat(cell(snapshots.get(0), 7)).isEqualTo("main");
        assertThat(cell(snapshots.get(1), 7)).isEqualTo("review");
        assertThat(cell(snapshots.get(2), 7)).isEqualTo("close-day-1");
        assertThat(driver.findElements(By.cssSelector(".lanes circle[data-id]"))).hasSize(3);
        snapshots.get(2).findElement(By.cssSelector("button.row-pick")).click();
        browser.await("the oldest snapshot is not selected", d -> d.findElement(
                By.cssSelector("#metadata-log tr[data-id='" + S1 + "']")).getDomAttribute("class").contains("is-sel"));
        String summary = textContent(browser.await(By.cssSelector("#selected-snapshot pre.raw")));
        assertThat(JsonPath.<String>read(summary, "$.operation")).isEqualTo("append");
        assertThat(JsonPath.<String>read(summary, "$.total-records")).isEqualTo("10");

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
        assertThat(cell(commits.get(0), 6)).startsWith(principals.get(0));
        assertThat(cell(commits.get(0), 4)).as("the newest commit set refs and added no snapshot").isEqualTo("–");
        commits.get(0).findElement(By.cssSelector("td:last-child button")).click();
        String location = JsonPath.read(log.body(), "$.pointer.metadata-location");
        browser.awaitText(By.cssSelector("#metadata-log tr.mlog-det"), location);

        browser.open("/ui/#" + at("table", eu, "positions", null));
        browser.awaitText(By.cssSelector("#table-page .title-row .badge"), "iceberg v3");
        assertThat(fact("Next row id")).isNotBlank();
    }

    @Test
    void snapshotIdsPastTwoToTheFiftyThreeAreShownDigitForDigit() {
        // Iceberg's snapshot ids are 64-bit, and most are past 2^53, where a double cannot hold every integer: read
        // as numbers, 9007199254740993 would show as ...992 and 8809372496300770237 as ...770000 (backlog 204).
        long first = 8_809_372_496_300_770_237L;
        long second = 9_007_199_254_740_993L;
        List<String> levels = List.of("ids_" + Api.suffix());
        expect(api.rest("POST", "/namespaces", "{\"namespace\":" + json(levels) + "}"), 200);
        String location = create(levels, "ticks", 2);
        String root = location.substring(0, location.indexOf("/metadata/"));
        long now = System.currentTimeMillis();
        expect(api.rest("POST", tableRest(levels, "ticks"), """
                {"requirements":[],"updates":[
                  {"action":"add-snapshot","snapshot":{"snapshot-id":%d,"sequence-number":1,"timestamp-ms":%d,
                    "manifest-list":"%s/metadata/snap-1.avro","schema-id":0,"summary":{"operation":"append"}}},
                  {"action":"add-snapshot","snapshot":{"snapshot-id":%d,"parent-snapshot-id":%d,"sequence-number":2,
                    "timestamp-ms":%d,"manifest-list":"%s/metadata/snap-2.avro","schema-id":0,
                    "summary":{"operation":"append"}}},
                  {"action":"set-snapshot-ref","ref-name":"main","snapshot-id":%d,"type":"branch"}]}"""
                .formatted(first, now - 2_000, root, second, first, now - 1_000, root, second)), 200);
        String answer = expect(api.rest("GET", tableRest(levels, "ticks"), null), 200).body();
        java.util.regex.Matcher sent = java.util.regex.Pattern.compile("\"current-snapshot-id\"\\s*:\\s*(\\d+)")
                .matcher(answer);
        assertThat(sent.find()).isTrue();
        assertThat(sent.group(1)).as("the catalog's own text").isEqualTo(String.valueOf(second));

        signIn(tester());
        browser.open("/ui/#" + at("table", levels, "ticks", null));
        assertThat(fact("Current snapshot")).startsWith(sent.group(1) + " · append · ");
        tab("history");
        List<WebElement> rows = browser.await("the commit that added the snapshots is not shown", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("#metadata-log tbody tr[data-id]"));
            return found.isEmpty() ? null : found;
        });
        assertThat(rows).extracting(row -> row.getDomAttribute("data-id")).containsExactly(String.valueOf(first));
        // The commit shows its snapshot shortened, cut from the id's own digits, and whole on hover (backlog 326): cut
        // from a parsed number, 8809372496300770237 would show as 8809…0000.
        WebElement pick = rows.getFirst().findElement(By.cssSelector("button.row-pick"));
        assertThat(textContent(pick)).isEqualTo("8809…0237");
        assertThat(pick.getDomAttribute("title")).isEqualTo(String.valueOf(first));
        assertThat(textContent(driver.findElement(By.cssSelector("#selected-snapshot h2 .meta")))).startsWith("9007…0993 · ");
        assertThat(driver.findElements(By.cssSelector(".lanes circle[data-id]")))
                .extracting(c -> c.getDomAttribute("data-id"))
                .containsExactly(String.valueOf(first), String.valueOf(second));
        browser.await(By.xpath("//button[normalize-space()='Show the whole summary']")).click();
        String whole = textContent(browser.await(By.cssSelector("#selected-snapshot pre.raw")));
        assertThat(whole).contains("\"snapshot-id\": " + second + ",", "\"parent-snapshot-id\": " + first + ",");
    }

    /**
     * Snapshot expiries made one after another fold into one row of History, opened on click, and Contents names the
     * append before them as the last data change, not the expiry (Atelier, 2026-11-27; backlog 330).
     */
    @Test
    void expiriesFoldIntoOneRowAndContentsNamesTheLastDataChange() {
        List<String> levels = List.of("fold_" + Api.suffix());
        expect(api.rest("POST", "/namespaces", "{\"namespace\":" + json(levels) + "}"), 200);
        String location = create(levels, "lines", 2);
        String root = location.substring(0, location.indexOf("/metadata/"));
        long now = System.currentTimeMillis();
        StringBuilder added = new StringBuilder();
        for (int id = 1; id <= 4; id++) {
            added.append("""
                    {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,"timestamp-ms":%d,
                      "manifest-list":"%s/metadata/snap-%d.avro","schema-id":0,"summary":{"operation":"append"}}},"""
                    .formatted(id, id > 1 ? "\"parent-snapshot-id\":" + (id - 1) + "," : "", id, now - 5_000 + id,
                            root, id));
        }
        expect(api.rest("POST", tableRest(levels, "lines"), "{\"requirements\":[],\"updates\":[" + added
                + "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"snapshot-id\":4,\"type\":\"branch\"}]}"),
                200);
        // The snapshots are seconds old, and the deployment refuses to remove one younger than an hour: the shared clock
        // goes two hours on for the expiries and back after, rather than counting on an earlier test having moved it.
        clock.advance(Duration.ofHours(2));
        try {
            for (int id = 1; id <= 3; id++) {
                expect(api.rest("POST", tableRest(levels, "lines"), Api.removeSnapshot(id)), 200);
            }
        } finally {
            clock.advance(Duration.ofHours(-2));
        }
        HttpResponse<String> listed = expect(api.manage("GET", "/namespaces/" + ns(levels)
                + "/tables", null), 200);
        assertThat(JsonPath.<Integer>read(listed.body(), "$.tables[0].last-commit.version")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(listed.body(), "$.tables[0].last-data-change.version"))
                .as("the append, not the expiries after it").isEqualTo(2);

        signIn(tester());
        browser.open("/ui/#" + at("table", levels, "lines", "history"));
        WebElement fold = browser.await(By.cssSelector("#metadata-log tbody tr.mlog-fold"));
        assertThat(textContent(fold)).contains("Snapshots expired 3 times", " to ");
        assertThat(textContent(fold.findElement(By.xpath("./td[1]")))).isEqualTo("3–5");
        List<WebElement> expiries = driver.findElements(By.cssSelector("#metadata-log tbody tr[data-version='3'],"
                + " #metadata-log tbody tr[data-version='4'], #metadata-log tbody tr[data-version='5']"));
        assertThat(expiries).hasSize(3).noneMatch(WebElement::isDisplayed);
        assertThat(driver.findElement(By.cssSelector("#metadata-log tbody tr[data-version='2']")).isDisplayed())
                .as("the data change leads, unfolded").isTrue();
        WebElement open = fold.findElement(By.cssSelector("button[aria-expanded]"));
        assertThat(textContent(open)).isEqualTo("Show 3");
        open.click();
        assertThat(open.getDomAttribute("aria-expanded")).isEqualTo("true");
        assertThat(expiries).allMatch(WebElement::isDisplayed);
        open.click();
        assertThat(expiries).noneMatch(WebElement::isDisplayed);

        browser.open("/ui/#" + at("namespace", levels, null, null));
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).contains("Last data change");
        assertThat(textContent(contentsRow("lines").findElement(By.cssSelector("td.last"))))
                .endsWith(" by " + JsonPath.read(listed.body(), "$.tables[0].last-data-change.principal"));
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
        // The hold is said once, in its own panel (backlog 329): no pill in the status row.
        assertThat(textContent(browser.await(By.cssSelector("#legal-holds h2")))).isEqualTo("Legal holds 1 hold · nearest level first");
        assertThat(driver.findElements(By.cssSelector("#governance .status-held"))).isEmpty();
        assertThat(textContent(browser.await(By.cssSelector("#governance .status-row")))).doesNotContainIgnoringCase("legal hold");

        assertThat(fact("Owner")).startsWith("desk-a ").contains("inherited from namespace " + top);
        assertThat(fact("Classification")).startsWith("internal ").contains("this table");
        assertThat(fact("Retention class")).isEqualTo("–");

        List<WebElement> holds = driver.findElements(By.cssSelector("#table-page tr[data-hold]"));
        assertThat(holds).hasSize(1);
        assertThat(cell(holds.get(0), 1)).isEqualTo(HOLD_REASON);
        assertThat(cell(holds.get(0), 2)).isEqualTo("inherited from namespace " + top + ".equities");
        assertThat(cell(holds.get(0), 3)).endsWith("stella");
        // Where each value was set is folded inside the Governance panel, not a panel of its own (backlog 329).
        assertThat(driver.findElements(By.xpath("//div[contains(@class,'nc-main')]//*[contains(concat(' ',@class,' '),"
                + "' panel ')][h2[starts-with(normalize-space(),'Attributes')]]"))).isEmpty();
        WebElement whereSet = browser.await(By.cssSelector("#governance details#where-set"));
        assertThat(whereSet.getDomProperty("open")).isEqualTo("false");
        whereSet.findElement(By.tagName("summary")).click();
        assertThat(rows(whereSet)).extracting(row -> row.get(0) + " = " + row.get(1) + " @ " + row.get(2))
                .contains("Classification = internal @ this table",
                        "Owner = desk-a @ inherited from namespace " + top);

        // The level above sees the same hold as its own, and no freeze.
        browser.open("/ui/#" + at("namespace", equities, null, "governance"));
        WebElement hold = browser.await(By.cssSelector("#namespace-page tr[data-hold]"));
        assertThat(cell(hold, 2)).isEqualTo("this namespace");
        assertThat(browser.await(By.cssSelector("#governance .status-row")).getText()).contains("No DR freeze");
        assertThat(driver.findElements(By.cssSelector("#governance .status-frozen"))).isEmpty();
    }

    /**
     * A platform operator who holds nothing in the catalog sees on Governance the DR freezes he may lift, each with its
     * path, its reason as text, who placed it and when, and lifts one with Lift; no legal hold is listed (D55, item 323).
     */
    @Test
    void anOperatorSeesTheStandingFreezesAndLiftsOneFromGovernance() {
        List<String> drill = List.of(top, "drill_" + Api.suffix());
        expect(api.rest("POST", "/namespaces", "{\"namespace\":" + json(drill) + "}"), 200);
        String markup = "console test: <b>drill</b> & <img src=x>";
        expect(api.manage("PUT", "/namespaces/" + ns(drill) + "/dr-freeze", "{\"reason\":\"" + markup + "\"}", AUTH,
                api.operator()), 200);

        signIn(api.operator());
        browser.open("/ui/#p=governance&c=trading");
        WebElement eus = browser.await(By.cssSelector("#operator-freezes tr[data-on='" + String.join(".", eu) + "']"));
        assertThat(cell(eus, 1)).isEqualTo(String.join(".", eu) + " · namespace");
        assertThat(cell(eus, 2)).isEqualTo(FREEZE_REASON);
        assertThat(cell(eus, 3)).as("when and by whom").contains("\n").isNotBlank();
        assertThat(textContent(browser.await(By.id("operator-freezes")))).doesNotContain(HOLD_REASON);
        assertThat(driver.findElements(By.cssSelector("#operator-freezes tr[data-kind='hold']"))).isEmpty();

        By drilled = By.cssSelector("#operator-freezes tr[data-on='" + String.join(".", drill) + "']");
        WebElement row = browser.await(drilled);
        assertThat(cell(row, 2)).as("the reason is text, never markup").isEqualTo(markup);
        assertThat(row.findElements(By.cssSelector("td:nth-child(2) b, td:nth-child(2) img"))).isEmpty();
        row.findElement(By.cssSelector("[data-act='lift']")).click();
        WebElement dialog = browser.await(By.cssSelector("dialog.dlg"));
        browser.awaitText(By.cssSelector("dialog.dlg #dlg-h"), "Lift the DR freeze on " + drill.getLast());
        dialog.findElement(By.cssSelector("button[type='submit']")).click();
        // The list is drawn again after the lift, so the lifted row is gone while the list loads too: wait for the
        // list that holds eu's freeze and not the drill's.
        By eusRow = By.cssSelector("#operator-freezes tr[data-on='" + String.join(".", eu) + "']");
        browser.await("the lifted freeze stayed listed, or the others went with it",
                d -> d.findElements(drilled).isEmpty() && d.findElements(eusRow).size() == 1);
    }

    /**
     * The Access tab's "Your groups' grants here" lists only the grants that reach the object, the answer What you may
     * do reads, and every grant of the caller's groups in the catalog is one click away, for anyone (the 27 Nov review,
     * item 6; Atelier; backlog 329).
     */
    @Test
    void yourGroupsGrantsListOnlyThoseThatReachTheObject() {
        String desk = "desk-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, desk, "describe");
        for (List<String> levels : List.of(eu, ledger)) {
            expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":" + json(levels)
                    + "},\"group\":\"" + desk + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        }
        signIn(person("dana", desk));
        browser.open("/ui/#" + at("table", eu, "trades", "access"));
        WebElement own = browser.await(By.cssSelector("#access-tab [data-grants='own']"));
        assertThat(textContent(own.findElement(By.tagName("h2")))).isEqualTo("Your groups' grants here");
        List<WebElement> here = browser.await("no grants listed here", d -> {
            List<WebElement> found = d.findElements(By.cssSelector("#access-tab table.own-here tbody tr"));
            return found.size() == 2 ? found : null;
        });
        assertThat(here).extracting(r -> r.getDomAttribute("data-privilege") + " " + textContent(r.findElements(By.tagName("td")).get(2)))
                .as("the ledger's grant does not reach the table").containsExactly("describe catalog trading",
                        "select namespace " + String.join(".", eu));

        browser.await(By.cssSelector("#access-tab [data-act='own-grants-all']")).click();
        WebElement all = browser.await(By.cssSelector("dialog.dlg #own-grants-all"));
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg #dlg-h")))).isEqualTo("Your groups' grants in trading");
        List<WebElement> every = browser.await("not every grant listed", d -> {
            List<WebElement> found = all.findElements(By.cssSelector("tbody tr"));
            return found.size() == 3 ? found : null;
        });
        assertThat(every).extracting(r -> textContent(r.findElements(By.tagName("td")).get(2)))
                .contains("namespace " + String.join(".", ledger));

        // A grant admin has the same list and link beside the grants that reach the table.
        signIn(gary());
        browser.open("/ui/#" + at("table", eu, "trades", "access"));
        browser.await(By.cssSelector("#access-tab .grants-reach"));
        browser.await(By.cssSelector("#access-tab .grants-here [data-act='own-grants-all']"));
        assertThat(driver.findElements(By.xpath("//div[@id='access-tab']//h2[normalize-space()=\"Your groups' grants here\"]")))
                .hasSize(1);
    }

    @Test
    void refusalsAreShownExactlyAsTheCatalogAnswered() {
        // D27: an unclassified table in a regulated namespace is refused everything but describe. The console says it
        // is closed until classified in its own notice instead of the refusal (backlog 324; the notice's words are
        // ConsoleFlaggedTableBrowserTest's).
        signIn(tester());
        browser.open("/ui/#" + at("table", ledger, "accounts", null));
        assertThat(textContent(browser.await(By.id("closed-until-classified")))).startsWith("Closed until classified.");
        assertThat(driver.findElements(By.cssSelector("#table-page .tab-body .refusal"))).isEmpty();
        assertThat(JsonPath.<String>read(expect(api.send("GET", "/v1/trading/namespaces/" + ns(ledger)
                + "/tables/accounts?snapshots=all", null, AUTH, tester(), "X-Iceberg-Access-Delegation", "none"), 403)
                .body(), "$.error.message")).endsWith("it is closed until it is classified");

        // Reading an object's audit trail needs manage-grants: the tab is not offered, and a link to it says so
        // without asking the catalog (backlog 184), which would refuse it.
        browser.open("/ui/#" + at("table", eu, "trades", "audit"));
        assertThat(textContent(browser.await(By.cssSelector("#table-page [data-closed] h3"))))
                .isEqualTo("An object's audit trail is open to those who manage trading's grants.");
        assertThat(driver.findElements(By.cssSelector("#table-page nav.tabs a[data-tab='audit']"))).isEmpty();
        expect(api.manage("GET", tablePath(eu, "trades") + "/audit?limit=50", null, AUTH, tester()), 403);

        // Grant administrators are kept away from the data itself (D18): a table opens on Access for them (backlog
        // 245), and its Overview says so without asking (backlog 325). Exporting its metadata, which they ask for
        // themselves, shows the catalog's refusal of its load.
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("table", eu, "trades", "overview"));
        assertThat(textContent(browser.await(By.cssSelector("#table-page .tab-body [data-closed] h3"))))
                .isEqualTo(UNREAD_TABLE);
        browser.await(By.cssSelector("#table-page [data-act='export-meta']")).click();
        assertShownVerbatim(refusal("dialog.dlg"), api.send("GET", "/v1/trading/namespaces/" + ns(eu)
                + "/tables/trades?snapshots=all", null, AUTH, api.grantAdmin(), "X-Iceberg-Access-Delegation", "none"));
    }

    /**
     * With no group named, the Grants page lists the catalog's groups, each with its duties, to a grant admin, whose own
     * groups hold no grant (D18), and each opens its grants (Atelier's walk of backlog 248, 2026-11-24; backlog 260).
     */
    @Test
    void aGrantAdminSeesTheCatalogsGroupsWithTheirDutiesAndOpensOnesGrants() {
        signIn(gary());
        browser.open("/ui/#p=grants&c=trading");
        WebElement stewards = browser.await(By.cssSelector("#governance-grants table.grants-groups tr[data-group='"
                + GovernanceServer.STEWARDS + "']"));
        assertThat(textContent(browser.await(By.cssSelector("#grants-list h2")))).isEqualTo("Groups in trading");
        assertThat(cell(stewards, 2)).contains("Tags");
        assertThat(cell(stewards, 3)).contains("manage-tags");
        assertThat(textContent(browser.await(By.cssSelector("#governance-grants .grants-configured"))))
                .contains(GovernanceServer.GRANT_ADMINS);

        stewards.findElement(By.tagName("a")).click();
        browser.await(grantRow(GovernanceServer.STEWARDS, "manage-tags"));
        browser.await("the link did not name the group",
                d -> d.getCurrentUrl().endsWith("&g=" + GovernanceServer.STEWARDS));
        assertThat(browser.await(By.cssSelector("#governance-grants input[aria-label='Group']")).getDomProperty("value"))
                .isEqualTo(GovernanceServer.STEWARDS);
    }

    @Test
    void grantsAreMadeAndRevokedAndASeparationOfDutiesRefusalIsShownBeforeGrant() {
        String group = "console-readers-" + Api.suffix();
        signIn(api.grantAdmin());
        browser.open("/ui/#" + at("namespace", equities, null, "grants"));
        grant("select", group);
        assertThat(liveLine()).isEqualTo(group + " will read every table and view in " + dotted(equities)
                + ", now and later.");
        grantButton().click();
        browser.awaitText(By.cssSelector("dialog.dlg #grant-result .granted"),
                "Granted select on " + dotted(equities) + " to " + group + ".");
        closeDialog();
        WebElement row = browser.await(grantRow(group, "select"));
        assertThat(row.getDomAttribute("data-on")).isEqualTo("here");
        assertThat(row.getDomAttribute("class")).as("the grant just made is marked").isEqualTo("is-new");
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

        // Stewards hold manage-tags, a duty that write must stay apart from (D18): refused before Grant, said in the
        // duty the group already holds, and Grant cannot be pressed.
        grant("write", GovernanceServer.STEWARDS);
        WebElement refusal = browser.await(By.cssSelector("dialog.dlg .grant-line[data-answer='refused'] "
                + ".refusal[data-refusal='separation-of-duties']"));
        assertThat(textContent(refusal.findElement(By.cssSelector(".refusal-h")))).isEqualTo(GovernanceServer.STEWARDS
                + " sets labels and holds in trading, and data and tags never share a group.");
        assertThat(refusal.findElements(By.cssSelector(".fine"))).as("the catalog's reason, needed only when the "
                + "dialog cannot say which duty").isEmpty();
        assertThat(grantButton().isEnabled()).isFalse();
        closeDialog();

        // Grants by group, on the governance page, from a link that names the group.
        browser.open("/ui/#p=governance&c=trading&tab=grants&g=" + GovernanceServer.STEWARDS);
        WebElement stewards = browser.await(grantRow(GovernanceServer.STEWARDS, "manage-tags"));
        assertThat(cell(stewards, 3)).isEqualTo("the whole catalog");
        assertThat(browser.await(By.cssSelector("#governance-grants input[aria-label='Group']")).getDomProperty("value"))
                .isEqualTo(GovernanceServer.STEWARDS);
        assertThat(browser.await(By.cssSelector(".duty[data-duty='tags']")).getText()).contains("granted");
    }

    /**
     * The Grant access dialog (Atelier, 2026-11-24; backlog 248), as the demo's gary, a grant admin: the groups the
     * catalog knows, each with its duties, and the configured grant admins marked, not offered; the access in words, only
     * what means something on the object; a path found from the Grants page; and the live line before Grant, from the
     * grant's preview: one already held, one refused (his own group, and tags for a group that holds data), and one made,
     * marked in the list with its record a link away. On an Access tab the object is fixed, and a grant from above that
     * already gives the access is named.
     */
    @Test
    void theGrantAccessDialogShowsAGrantMadeOneAlreadyHeldAndOneRefused() {
        String group = "console-dialog-" + Api.suffix();
        signIn(gary());
        browser.open("/ui/#p=grants&c=trading");
        WebElement dialog = openGrantAccess("#governance-grants .ph");
        assertThat(driver.findElement(By.cssSelector("dialog.dlg #dlg-h")).getText()).isEqualTo("Grant access in trading");
        assertThat(liveLine()).isEqualTo("Pick a group and the access to grant.");
        assertThat(grantButton().isEnabled()).isFalse();
        assertThat(textContent(dialog.findElement(By.cssSelector(".on-now")))).isEqualTo("trading · the whole catalog");
        assertThat(dialog.findElements(By.cssSelector(".acc span"))).extracting(ConsoleBrowser::textContent).containsExactly(
                "See it", "Read its data", "Read and write rows", "Change it (schema, properties, maintenance)",
                "Create tables, views and namespaces in it", "Set labels and holds", "Manage grants");
        assertThat(dialog.findElements(By.cssSelector(".acc code"))).extracting(ConsoleBrowser::textContent).containsExactly(
                "describe", "select", "write", "modify", "create", "manage-tags", "manage-grants");

        // The groups that hold a grant here, each with its duties; the grant admins hold theirs by configuration and
        // can be granted nothing, so they are marked, not offered.
        WebElement stewards = browser.await(groupChoice(GovernanceServer.STEWARDS));
        assertThat(stewards.findElements(By.cssSelector(".grp-d .pill"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("Tags");
        assertThat(driver.findElement(groupChoice(IssuerForTests.GROUP)).findElements(By.cssSelector(".grp-d .pill")))
                .extracting(ConsoleBrowser::textContent).contains("Data");
        WebElement admins = driver.findElement(By.cssSelector("dialog.dlg .grp.cfg[data-group='"
                + GovernanceServer.GRANT_ADMINS + "']"));
        assertThat(admins.getTagName()).isEqualTo("span");
        assertThat(admins.getDomAttribute("aria-disabled")).isEqualTo("true");
        assertThat(admins.findElements(By.cssSelector(".pill"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("Manages grants on every catalog", "Yours");
        assertThat(textContent(dialog.findElement(By.cssSelector(".grp-cfg-h"))))
                .isEqualTo("Set by the deployment: these hold no grant.");

        // Already held: stewards' manage-tags on the catalog. Grant would change nothing.
        stewards.click();
        assertThat(browser.await(By.cssSelector("dialog.dlg input[aria-label='Group']")).getDomProperty("value"))
                .isEqualTo(GovernanceServer.STEWARDS);
        browser.await(By.cssSelector("dialog.dlg input[name='pv'][value='manage-tags']")).click();
        assertThat(answer()).isEqualTo("unchanged");
        assertThat(liveLine()).startsWith("Already held: manage-tags on trading, granted by ");
        assertThat(grantButton().isEnabled()).isFalse();

        // Refused: his own group, and tags for the testers, who hold data (D18). Grant stays off.
        grant("select", GovernanceServer.GRANT_ADMINS);
        assertThat(answer()).isEqualTo("refused");
        WebElement self = browser.await(By.cssSelector("dialog.dlg .refusal[data-refusal='self-grant']"));
        assertThat(textContent(self.findElement(By.cssSelector(".refusal-h"))))
                .isEqualTo("You belong to grant-admins. No one grants to their own group: ask another grant admin.");
        assertThat(self.findElements(By.cssSelector(".fine"))).isEmpty();
        assertThat(grantButton().isEnabled()).isFalse();
        grant("manage-tags", IssuerForTests.GROUP);
        assertThat(answer()).isEqualTo("refused");
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg .refusal[data-refusal='separation-of-duties'] "
                + ".refusal-h")))).isEqualTo(IssuerForTests.GROUP
                + " changes data in trading, and data and tags never share a group.");
        assertThat(grantButton().isEnabled()).isFalse();

        // Made: a group the catalog does not know yet, on a namespace found by its path.
        grant("select", group);
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-new-group]")))).isEqualTo(group
                + " holds no grant in trading yet. The catalog can't list your directory's groups, so check the spelling.");
        driver.findElement(By.cssSelector("dialog.dlg input[aria-label='Find a namespace, table or view']"))
                .sendKeys(top + " equities");
        browser.await(By.cssSelector("dialog.dlg button.on-hit[data-path='" + dotted(equities) + "']")).click();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg .on-now code")))).isEqualTo(dotted(equities));
        assertThat(answer()).isEqualTo("admitted");
        assertThat(liveLine()).isEqualTo(group + " will read every table and view in " + dotted(equities)
                + ", now and later.");
        grantButton().click();
        WebElement made = browser.awaitText(By.cssSelector("dialog.dlg #grant-result .granted"),
                "Granted select on " + dotted(equities) + " to " + group + ".");
        assertThat(made.getText()).contains("It is in the audit trail.");
        assertThat(made.findElement(By.cssSelector("a[data-act='see-record']")).getDomAttribute("href"))
                .isEqualTo("#p=audit&c=trading&kind=grant");
        assertThat(textContent(driver.findElement(By.cssSelector("dialog.dlg .dlg-acts button.ghost"))))
                .as("nothing is left to cancel").isEqualTo("Close");
        closeDialog();
        browser.await("the list is not of " + group, d -> d.getCurrentUrl().endsWith("#p=grants&c=trading&g=" + group));
        WebElement row = browser.await(By.cssSelector("#grants-list tr[data-group='" + group
                + "'][data-privilege='select'][data-path='" + dotted(equities) + "']"));
        assertThat(row.getDomAttribute("class")).isEqualTo("is-new");

        // A table's Access tab: the object is fixed, and create, which means nothing there, is not offered.
        browser.open("/ui/#" + at("table", eu, "trades", "access"));
        dialog = openGrantAccess("#access-tab > .access-acts");
        assertThat(driver.findElement(By.cssSelector("dialog.dlg #dlg-h")).getText())
                .isEqualTo("Grant access on " + dotted(eu) + ".trades");
        assertThat(textContent(dialog.findElement(By.cssSelector(".on-now")))).isEqualTo(dotted(eu) + ".trades · table");
        assertThat(dialog.findElements(By.cssSelector("input[aria-label='Find a namespace, table or view']"))).isEmpty();
        assertThat(dialog.findElements(By.cssSelector(".acc code"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("describe", "select", "write", "modify", "manage-tags", "manage-grants");
        closeDialog();

        // A view's: neither create nor write. The group reads it already through the namespace's grant, which the
        // line names; the grant made here is marked among those that reach the view.
        browser.open("/ui/#" + at("view", equities, "daily", "access"));
        dialog = openGrantAccess("#access-tab > .access-acts");
        assertThat(dialog.findElements(By.cssSelector(".acc code"))).extracting(ConsoleBrowser::textContent)
                .containsExactly("describe", "select", "modify", "manage-tags", "manage-grants");
        assertThat(dialog.findElements(By.cssSelector(".acc span"))).extracting(ConsoleBrowser::textContent)
                .contains("Change it (definition, properties)");
        grant("select", group);
        assertThat(answer()).isEqualTo("admitted");
        assertThat(liveLine()).isEqualTo(group + " will read " + dotted(equities) + ".daily.");
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg .grant-line [data-through]"))))
                .startsWith("It already has this through select on " + dotted(equities) + ", granted by gary ")
                .endsWith(". A grant here stands if that one is revoked.");
        grantButton().click();
        browser.awaitText(By.cssSelector("dialog.dlg #grant-result .granted"),
                "Granted select on " + dotted(equities) + ".daily to " + group + ".");
        closeDialog();
        WebElement here = browser.await(By.cssSelector(".grants-reach tr[data-group='" + group
                + "'][data-privilege='select'][data-on='here']"));
        assertThat(here.getDomAttribute("class")).isEqualTo("is-new");
        browser.await("the grant made is not brought into view", d -> (Boolean) driver.executeScript(
                "const r = document.querySelector('.grants-reach tr.is-new').getBoundingClientRect();"
                        + " return r.top >= 0 && r.bottom <= window.innerHeight;"));
        assertThat(driver.findElement(By.cssSelector(".grants-reach tr[data-group='" + group
                + "'][data-privilege='select'][data-on='namespace']")).getDomAttribute("class")).isNull();
    }

    /**
     * The grids are measured against the content column, not the window (backlog 326). Beside the menu and the tree, at
     * 1,280, 1,440 and 1,920 px, History stacks, so its Commits span the tab; each commit is one line, with no word broken,
     * and its snapshot shows whole or shortened from the id's own digits. Governance's holds and freezes and its
     * regulated areas fit their cards, with no word broken. Before, at 1,440 px the content beside the tree was about
     * 870 px and History kept two columns, so the Commits broke "snapshots expired" letter by letter.
     */
    @Test
    void besideTheMenuAndTheTreeHistoryAndGovernanceShowTheirTablesWhole() {
        signIn(tester());
        // The console's frame is hidden until a page signed in is drawn, so one is drawn before it is measured.
        browser.open("/ui/#" + at("table", eu, "trades", "history"));
        browser.await(By.cssSelector("#who strong"));
        for (int width : new int[] {1280, 1440, 1920}) {
            frameOf(width);
            browser.open("/ui/#" + at("table", eu, "trades", "history"));
            List<WebElement> snapshots = browser.await("the commits that added snapshots are not shown", d -> {
                List<WebElement> found = d.findElements(By.cssSelector("#metadata-log tbody tr[data-id]"));
                return found.size() == 3 ? found : null;
            });
            assertThat(driver.findElement(By.cssSelector(".browse > .tree")).isDisplayed()).as("the tree at %d px", width)
                    .isTrue();
            assertThat((Boolean) driver.executeScript("const tab = document.getElementById('history-tab').getBoundingClientRect();"
                    + " const log = document.getElementById('metadata-log').getBoundingClientRect();"
                    + " return Math.abs(tab.width - log.width) < 1"))
                    .as("the Commits span the History tab at %d px", width).isTrue();
            assertThat(cell(snapshots.get(0), 4)).isEqualTo(S3 + " · append");
            assertThat((List<?>) driver.executeScript(LINES_BROKEN, "#metadata-log tbody tr[data-version] > td:not(:last-child)"))
                    .as("the commits on more than one line at %d px", width).isEmpty();
            assertThat((List<?>) driver.executeScript(BROKEN_WORDS, "#metadata-log tbody tr[data-version] > td"))
                    .as("the words broken in the Commits at %d px", width).isEmpty();
            assertThat(sideways()).as("the page scrolls sideways at %d px", width).isFalse();

            browser.open("/ui/#p=governance&c=trading");
            browser.await(By.cssSelector("#regulated tr[data-on='" + top + ".ledger']"));
            browser.await(By.cssSelector("#governance-summary tr[data-kind='freeze'][data-on='" + top + ".equities.eu']"));
            for (String table : List.of("#governance-summary .grid-2 > .panel:first-child", "#regulated")) {
                assertThat((Boolean) driver.executeScript("const w = document.querySelector(arguments[0] + ' .wrap-tbl');"
                        + " return w.scrollWidth <= w.clientWidth + 1", table))
                        .as("%s fits its card at %d px", table, width).isTrue();
                assertThat((List<?>) driver.executeScript(BROKEN_WORDS, table + " tbody td"))
                        .as("the words broken in %s at %d px", table, width).isEmpty();
            }
            assertThat(sideways()).as("the page scrolls sideways at %d px", width).isFalse();
        }
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
        // Why the area is regulated and who marked it, as its flag records them (backlog 213).
        assertThat(cell(regulated, 2)).isEqualTo("console test");
        assertThat(cell(regulated, 3)).endsWith("stella");
        assertThat(cell(regulated, 4)).isEqualTo("1\n1 closed");
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
            // The commit came to the audit tables from the log, which gives no Kafka partition (backlog 205).
            Object.assign(records[2], {partition: -1, offset: 1795482234678168});
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              if (u.pathname.endsWith('/audit/summary')) {
                return json({'by-type': {'nuna.catalog.audit-read': 2, 'nuna.catalog.grant': 1, 'nuna.catalog.commit': 1},
                  from: new Date(now - 86400000).toISOString(), to: new Date(now).toISOString(),
                  busiest: [{kind: 'TABLE', path: ['equities', 'eu', 'positions_end_of_day_by_desk_and_book'], records: 1140,
                    reads: 1024, writer: 'svc-positions-loader-eu-west'}]});
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
     * Audit filters by kind (Atelier's review, F7; backlog 187): a grant admin reaches a grant's record in three steps,
     * Audit (where they land), the Grants chip, its record. Every filter is in the link, so Back keeps it; the audit's
     * own reads are hidden unless asked for; the counts' range names both days. By kind, which repeated the chips'
     * counts, is gone, and with no archive so is Hash chain (backlog 328).
     */
    @Test
    void auditFiltersByKindKeepsItsFiltersInTheLinkAndHidesItsOwnReads() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(AUDIT_TABLES_IN_THE_PAGE);
        browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant']"));
        assertThat(feedTypes()).containsExactly("grant", "commit");
        // All is every kind but Reads (backlog 328).
        assertThat(asked(lastAsked())).contains("nuna.catalog.grant", "nuna.catalog.commit")
                .doesNotContain("nuna.catalog.read", "nuna.catalog.audit-read", "nuna.catalog.export");
        browser.await("the chips have no counts", d -> !textContent(d.findElement(By.cssSelector("#audit-feed p[data-counts]"))).isEmpty());
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed p[data-counts]"))))
                .matches("Counts are of the last day, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d to \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d\\.");
        assertThat(driver.findElements(By.cssSelector("#audit-kinds, #hash-chain"))).isEmpty();

        // The chips' counts add up, the audit's own reads hidden and not counted; each badge is its chip's word, the raw
        // type its title (Atelier's re-walk after phase 2, backlog 214).
        assertThat(driver.findElements(By.cssSelector("#audit-feed .chip")).stream().map(c -> textContent(c).strip()).toList())
                .contains("All 2", "Grants 1", "Commits 1", "Reads 0");
        // A chip's title is its record types and their counts, as By kind's rows gave them.
        assertThat(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='all']")).getDomAttribute("title"))
                .isEqualTo("grant 1 · commit 1");
        // The headline counts every record; the chips leave out the hidden reads, and the checkbox says how many
        // (Atelier's re-walk after 212 to 217, backlog 220).
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-reads"))).strip())
                .isEqualTo("Show audit reads · 2 records hidden");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-kpis .kpi[data-kpi='records'] .kpi-v")))).isEqualTo("4");
        // The tile says how the chips share its total, so the two add up (backlog 328).
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-kpis .kpi[data-kpi='records'] .kpi-d"))))
                .isEqualTo("2 under All, 0 under Reads, 2 audit reads");
        // Most recorded keeps its number columns in its card at 1280, 1440 and 1920 px: the long path wraps after a dot
        // (backlog 220), never inside a word, as it broke letter by letter at 1440 (backlog 326).
        WebElement busiest = browser.await(By.cssSelector("#audit-busiest"));
        for (int width : new int[] {1280, 1440, 1920}) {
            frameOf(width);
            browser.await("Most recorded overflows its card at " + width + " px", d -> (Boolean) driver.executeScript(
                    "const t = arguments[0].querySelector('table'); return t.scrollWidth <= arguments[0].clientWidth", busiest));
            assertThat((List<?>) driver.executeScript(BROKEN_WORDS, "#audit-busiest tbody td"))
                    .as("the words broken in Most recorded at %d px", width).isEmpty();
        }
        driver.manage().window().setSize(new Dimension(1440, 900));
        assertThat(textContent(busiest.findElement(By.cssSelector("thead")))).contains("Records", "Reads");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='hold']")))).isEqualTo("Governance 0");
        WebElement grantBadge = driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] .badge"));
        assertThat(textContent(grantBadge)).isEqualTo("Grant");
        assertThat(grantBadge.getDomAttribute("title")).isEqualTo("nuna.catalog.grant");
        driver.findElement(By.cssSelector("#audit-feed .chip[data-f='grant']")).click();
        browser.await("the feed did not show only grants", d -> feedTypes().equals(List.of("grant")));
        assertThat(lastAsked()).isEqualTo("nuna.catalog.grant");
        assertThat(driver.getCurrentUrl()).contains("p=audit", "kind=grant");
        assertThat(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='grant']")).getDomAttribute("aria-pressed"))
                .isEqualTo("true");
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] button")).click();
        assertThat(textContent(browser.await(By.cssSelector("#audit-feed .feed-row .rec")))).contains("r2");
        // No archive is configured, so the record says so and offers no archive check, and its object's label is a word
        // (backlogs 216, 262).
        assertThat(driver.findElements(By.cssSelector("#audit-feed .feed-row .rec dt")).stream().map(ConsoleBrowser::textContent)
                .toList()).contains("Table").doesNotContain("Archive", "table");
        assertThat(driver.findElements(By.cssSelector("#audit-feed .feed-row .rec .chain-link"))).isEmpty();
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-row .rec"))))
                .contains("no archive is configured").doesNotContain("cannot be checked");

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
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-reads"))).strip()).isEqualTo("Show audit reads");
        assertThat(asked(lastAsked())).contains("nuna.catalog.audit-read", "nuna.catalog.grant").doesNotContain("nuna.catalog.read");
        assertThat(driver.getCurrentUrl()).contains("reads=all").doesNotContain("kind=");
    }

    /**
     * The audit feed in the page's own answers, each request's parameters kept in {@code window.feedAsked}: every page
     * is one ref record of the tag {@code window.refName}, whose id is {@code window.refId} (backlog 169), made with
     * the purpose {@code window.refPurpose} when the case sets one (backlog 167).
     */
    private static final String REF_RECORDS_IN_THE_PAGE = """
            const real = window.fetch;
            window.feedAsked = [];
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              const now = new Date().toISOString();
              if (u.pathname.endsWith('/audit/summary')) {
                return json({'by-type': {'nuna.catalog.ref': 1}, from: new Date(Date.now() - 86400000).toISOString(), to: now,
                  busiest: []});
              }
              if (u.pathname.endsWith('/audit/archive')) return json({chains: [], configured: false});
              if (u.pathname.endsWith('/audit/feed')) {
                window.feedAsked.push(Object.fromEntries(u.searchParams));
                // As the server answers: the audit table's last commit with the records (AuditReadRoutes#table).
                return json({table: {name: 'audit', 'committed-at': now},
                  records: [{time: now, type: 'nuna.catalog.ref', principal: 'tester', 'event-id': 'e1', partition: 0,
                  offset: 1, 'request-id': 'r1', branch: window.refName, object: null, chain: {status: 'no-archive'},
                  event: {type: 'nuna.catalog.ref', time: now, subject: window.refId, data: {action: 'CREATED', ref: window.refId,
                    name: window.refName, kind: 'TAG', from: 'main', description: window.refPurpose, tables: [],
                    actor: {principal: 'tester'}}}}]});
              }
              return real(url, init);
            };
            """;

    /**
     * The feed's {@code @name} search and a ref's Record tab (6.8; Atelier, 2026-11-25; backlog 169): {@code @name}
     * asks for the records through the ref from the live ref's making on, shows no day's counts beside them, and with no
     * live ref of that name says the records may be several refs'; the Record tab asks for the ref's records by its id
     * and links every record through it since it was made.
     */
    @Test
    void theFeedsRefSearchReadsFromTheLiveRefsMakingAndTheRecordTabAsksByItsId() {
        String tag = "since_" + Api.suffix();
        String gone = "gone_" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}"), 201);
        try {
            HttpResponse<String> made = expect(api.manage("GET", "/refs/" + tag, null), 200);
            String id = JsonPath.read(made.body(), "$.id");
            String createdAt = JsonPath.read(made.body(), "$.created-at");
            signIn(api.grantAdmin());
            browser.open("/ui/");
            browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
            driver.executeScript("window.refName = arguments[0]; window.refId = arguments[1];" + REF_RECORDS_IN_THE_PAGE
                    + "location.hash = '#p=audit&c=trading&path=%40' + arguments[0];", tag, id);

            browser.await("the feed did not ask for the tag's records", d -> tag.equals(lastFeedAsked().get("branch")));
            assertThat(lastFeedAsked()).as("from the live tag's making on").containsEntry("from", createdAt)
                    .doesNotContainKey("path");
            browser.await(By.cssSelector("#audit-feed .feed-row[data-type='ref']"));
            assertThat(driver.findElements(By.cssSelector("#audit-feed .chip .n"))).as("no counts beside a ref's records")
                    .isEmpty();
            assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed [data-ref-note]"))))
                    .startsWith("From @" + tag + "'s making, ")
                    .endsWith(": records through an earlier ref of the same name are left out. Include earlier refs named @"
                            + tag);
            browser.await("the status did not say where the feed starts", d -> textContent(d.findElement(
                    By.cssSelector("#audit-feed .feed-status"))).matches(".* shown, newest first, from .+, as of the last commit at .+"));

            // Up to sets only the end: the tag's making still starts the feed, and the tag made since an hour ago has
            // nothing through it before then, so the feed asks nothing.
            int asked = feedsAsked();
            new Select(driver.findElement(By.cssSelector("#audit-feed select[name='to']"))).selectByValue("1");
            assertThat(textContent(browser.await(By.cssSelector("#audit-feed .feed-none"))))
                    .startsWith("No record went through @" + tag + " by ")
                    .endsWith(". Set Up to later, or include earlier refs named @" + tag + ".");
            assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-status")))).isEmpty();
            assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed [data-ref-note]"))))
                    .as("Up to keeps the start").startsWith("From @" + tag + "'s making, ");
            assertThat(feedsAsked()).isEqualTo(asked);

            // The way out drops the start, keeps the search and Up to, and is kept in the link.
            driver.findElement(By.cssSelector("#audit-feed [data-ref-note] [data-act='any-time']")).click();
            browser.awaitText(By.cssSelector("#audit-feed [data-ref-note]"),
                    "Records through every ref that has had the name @" + tag + ".");
            assertThat(lastFeedAsked()).doesNotContainKey("from").containsEntry("branch", tag).containsKey("to");
            assertThat(driver.getCurrentUrl()).contains("from=any");

            WebElement path = driver.findElement(By.cssSelector("#audit-feed input[name='path']"));
            assertThat(path.getDomAttribute("placeholder")).isEqualTo("Table, namespace or @ref");
            path.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
            path.sendKeys("@" + gone);
            browser.await("the feed did not ask for the name's records", d -> gone.equals(lastFeedAsked().get("branch")));
            assertThat(lastFeedAsked()).doesNotContainKey("from");
            browser.awaitText(By.cssSelector("#audit-feed [data-ref-note]"), "No live ref is named @" + gone
                    + ": these are records through every ref that had the name.");

            driver.executeScript("location.hash = '#c=trading&k=ref&n=' + arguments[0] + '&tab=record';", tag);
            browser.awaitText(By.cssSelector("#ref-record .feed-row .what"), "Made the tag @" + tag
                    + " from main; it never expires");
            assertThat(lastFeedAsked()).containsEntry("subject", id).containsEntry("type", "nuna.catalog.ref");
            WebElement every = driver.findElement(By.cssSelector("#ref-record a[data-act='every-record']"));
            assertThat(textContent(every)).isEqualTo("Every record through @" + tag + " since it was made");
            every.click();
            browser.await("the feed did not open on the tag", d -> d.getCurrentUrl().contains("p=audit")
                    && tag.equals(lastFeedAsked().get("branch")));
            assertThat(lastFeedAsked()).as("the link's time, the tag's making").containsEntry("from", createdAt);
            browser.await("the linked making was not explained", d -> textContent(d.findElement(
                    By.cssSelector("#audit-feed [data-ref-note]"))).startsWith("From @" + tag + "'s making, "));

            // A time the link set that is not the ref's making.
            driver.executeScript("location.hash = '#p=audit&c=trading&path=%40' + arguments[0]"
                    + " + '&from=2020-01-01T00%3A00%3A00Z';", tag);
            browser.await("the feed did not ask from the link's time",
                    d -> "2020-01-01T00:00:00Z".equals(lastFeedAsked().get("from")));
            browser.await("the link's time was not explained", d -> textContent(d.findElement(
                    By.cssSelector("#audit-feed [data-ref-note]"))).endsWith(", as the link asked. Show earlier records too"));
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
        }
    }

    /** How many times the feed was asked, from {@link #REF_RECORDS_IN_THE_PAGE}. */
    private int feedsAsked() {
        return ((Number) driver.executeScript("return (window.feedAsked || []).length;")).intValue();
    }

    /** The parameters of the feed's last request, from {@link #REF_RECORDS_IN_THE_PAGE}; empty before any. */
    @SuppressWarnings("unchecked")
    private Map<String, String> lastFeedAsked() {
        Object asked = driver.executeScript("const a = window.feedAsked || []; return a[a.length - 1] || {};");
        return (Map<String, String>) asked;
    }

    /**
     * A record says where the audit tables read it from: Kafka, with its partition and offset, or the log
     * {@code nuna.audit}, with no Kafka place (backlog 205).
     */
    @Test
    void aRecordSaysWhetherItWasReadFromKafkaOrTheLog() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(AUDIT_TABLES_IN_THE_PAGE);
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] button")).click();
        WebElement fromKafka = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant'] .rec"));
        assertThat(textContent(fromKafka.findElement(By.cssSelector("[data-read-from]"))))
                .isEqualTo("Kafka, partition 0, offset 2");
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='commit'] button")).click();
        WebElement fromLog = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='commit'] .rec"));
        assertThat(textContent(fromLog.findElement(By.cssSelector("[data-read-from]")))).isEqualTo("the log nuna.audit");
        assertThat(textContent(fromLog)).doesNotContain("Kafka", "partition", "-1", "Check this record");
    }

    /** The feed and one record's place in its run's chain, as an archive gives them (backlog 272). */
    private static final String AUDIT_CHAIN_IN_THE_PAGE = """
            const real = window.fetch;
            window.asked = [];
            const now = Date.now();
            const run = 'a0000000-0000-4000-8000-000000000272';
            const rec = (i, type, chain, seq) => ({time: new Date(now - i * 60000).toISOString(), type: 'nuna.catalog.' + type,
              principal: 'gina', 'event-id': 'e0000000-0000-4000-8000-00000000000' + i, partition: -1, offset: i,
              'request-id': 'r' + i, object: null, event: {data: {}}, chain,
              'log-run': seq ? run : null, 'log-seq': seq || null});
            const at = new Date(now - 600000).toISOString();
            const records = [rec(1, 'grant', {status: 'verified', 'checked-at': at}, 41),
              rec(2, 'commit', {status: 'unverified', 'checked-at': at, missing: {from: 43, to: 44}}, 45),
              rec(3, 'drop', {status: 'no-place'}, null)];
            // The drop came from Kafka, whose copy carries no run.
            Object.assign(records[2], {partition: 0, offset: 7});
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              if (u.pathname.endsWith('/audit/summary')) {
                return json({'by-type': {'nuna.catalog.grant': 1, 'nuna.catalog.commit': 1, 'nuna.catalog.drop': 1},
                  from: new Date(now - 86400000).toISOString(), to: new Date(now).toISOString(), busiest: []});
              }
              if (u.pathname.endsWith('/audit/archive')) return json({configured: true, bucket: 'audit-archive', prefix: 'audit'});
              if (u.pathname.endsWith('/audit/feed')) return json({records});
              if (u.pathname.includes('/audit/chains/')) {
                window.asked.push(u.pathname + u.search);
                return json({chain: run, 'log-run': run, 'log-seq': 41, 'event-id': records[0]['event-id'], archived: true,
                  position: 43, hash: 'b'.repeat(64), 'previous-hash': 'a'.repeat(64), 'next-hash': null, 'link-holds': true,
                  verified: true, 'checked-at': at, segment: {key: 'audit/runs/' + run + '/0000000000000000040.log',
                    'first-position': 41, 'last-position': 43}});
              }
              return real(url, init);
            };
            location.hash = '#p=audit&c=trading&kind=all';
            """;

    /**
     * A record logged with its server run's number says whether the archive check has verified its run up to it, and
     * looks its place up in the run's chain; a record after one the check found missing says so; one from Kafka says it
     * cannot be checked (backlog 272, in Atelier's words).
     */
    @Test
    void aLoggedRecordSaysWhetherItsRunIsVerifiedAndLooksUpItsPlaceInTheChain() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(AUDIT_CHAIN_IN_THE_PAGE);
        browser.await(By.cssSelector("#audit-feed .feed-row[data-type='drop']"));

        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant']"))))
                .contains("Chain verified");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='commit']"))))
                .contains("Earlier record missing");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='drop']"))))
                .doesNotContain("Chain verified", "missing", "Not verified");

        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='commit'] button")).click();
        assertThat(textContent(browser.await(By.cssSelector("#audit-feed .feed-row[data-type='commit'] .rec"))))
                .contains("not verified: records 43 to 44 of its server run are missing from the archive; see Hash chain");
        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='drop'] button")).click();
        WebElement drop = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='drop'] .rec"));
        assertThat(textContent(drop)).contains(
                "cannot be checked from this page: the Kafka copy does not say which server run logged it");
        assertThat(drop.findElements(By.cssSelector(".chain-link"))).isEmpty();

        driver.findElement(By.cssSelector("#audit-feed .feed-row[data-type='grant'] button")).click();
        WebElement grant = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant'] .rec"));
        assertThat(textContent(grant.findElement(By.cssSelector("[data-read-from]"))))
                .startsWith("the log nuna.audit, record 41 of server run a0000000-0000-4000-8000-000000000272");
        assertThat(textContent(grant)).contains("verified by the check at").contains(
                "with every earlier record of its server run");
        grant.findElement(By.cssSelector(".chain-link button")).click();
        WebElement place = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant'] .chain-link .pill"));
        assertThat(textContent(place)).isEqualTo("Hash matches");
        assertThat(textContent(grant.findElement(By.cssSelector(".chain-link")))).contains(
                "place 43 in its server run's chain", "verified the chain through it",
                "its place differs from its number in the run", "the newest in its chain",
                "segment audit/runs/a0000000-0000-4000-8000-000000000272/0000000000000000040.log");
        assertThat(driver.executeScript("return window.asked")).asList().containsExactly(
                "/management/v1/audit/chains/a0000000-0000-4000-8000-000000000272/records/41"
                        + "?event-id=e0000000-0000-4000-8000-000000000001");
    }

    /** The types of the feed's rows, as drawn. */
    private List<String> feedTypes() {
        return driver.findElements(By.cssSelector("#audit-feed .feed-row")).stream()
                .map(row -> row.getDomAttribute("data-type")).toList();
    }

    /** A type filter's types. */
    private static List<String> asked(String types) {
        assertThat(types).as("the feed's type filter").isNotNull();
        return List.of(types.split(","));
    }

    /**
     * One engine polling a table: the day's reads, and a page of them with one grant among them (backlog 328, from the
     * review of 27 Nov, where 57,357 of 63,554 records were reads).
     */
    private static final String ENGINE_READS_IN_THE_PAGE = """
            const real = window.fetch;
            window.asked = [];
            const now = Date.now();
            let i = 0;
            const rec = (secs, type, who, engine, path, data) => ({time: new Date(now - secs * 1000).toISOString(),
              type: 'nuna.catalog.' + type, principal: who, engine, 'event-id': 'e' + (++i), partition: 0, offset: i,
              'request-id': 'r' + i, object: {kind: 'TABLE', path}, event: {data}, chain: {status: 'no-archive'}});
            const read = (secs, who, engine, path) => rec(secs, 'read', who, engine, path, {served: {version: 5}, branch: 'main'});
            const rates = ['markets', 'fx', 'rates'];
            const records = [];
            for (let k = 0; k < 5; k++) records.push(read(10 + k * 3, 'singlestore', 'singlestore-demo', rates));
            records.push(rec(40, 'grant', 'gina', null, ['equities', 'eu', 'trades'], {}));
            records.push(read(50, 'spark', 'spark', ['equities', 'eu', 'trades']));
            records.push(read(53, 'spark', 'spark', ['equities', 'eu', 'trades']));
            for (let k = 0; k < 3; k++) records.push(read(60 + k * 3, 'singlestore', 'singlestore-demo', rates));
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              if (u.pathname.endsWith('/audit/summary')) {
                return json({'by-type': {'nuna.catalog.read': 57357, 'nuna.catalog.audit-read': 8, 'nuna.catalog.grant': 1,
                  'nuna.catalog.commit': 6196}, from: new Date(now - 86400000).toISOString(), to: new Date(now).toISOString(), busiest: []});
              }
              if (u.pathname.endsWith('/audit/archive')) return json({configured: false});
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
     * One engine's polling reads no longer drown the feed (Atelier's review of 27 Nov, item 4; backlog 328): All leaves
     * the reads out and says so, the reads have their own chip, the tile says how the chips share its total, and a run of
     * like records in a row is one row, which says how many and when, and opens its records beneath it. The fold covers
     * only the page's own records, and the status line says so.
     */
    @Test
    void anEnginesPollingReadsFoldIntoRunsUnderTheirOwnChip() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(ENGINE_READS_IN_THE_PAGE);
        browser.await(By.cssSelector("#audit-feed .feed-row[data-type='grant']"));
        browser.await("the chips have no counts", d -> !textContent(d.findElement(By.cssSelector("#audit-feed p[data-counts]"))).isEmpty());

        assertThat(feedTypes()).containsExactly("grant");
        assertThat(asked(lastAsked())).doesNotContain("nuna.catalog.read");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='all']"))).strip()).isEqualTo("All 6,197");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .chip[data-f='read']"))).strip()).isEqualTo("Reads 57,357");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed p[data-counts]"))))
                .endsWith(" All leaves out the 57,357 reads, which engines make every few seconds: they have their own chip.");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-kpis .kpi[data-kpi='records'] .kpi-v")))).isEqualTo("63,562");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-kpis .kpi[data-kpi='records'] .kpi-d"))))
                .isEqualTo("6,197 under All, 57,357 under Reads, 8 audit reads");

        driver.findElement(By.cssSelector("#audit-feed .chip[data-f='read']")).click();
        browser.await("the feed did not show the reads", d -> feedTypes().size() == 4);
        assertThat(asked(lastAsked())).containsExactly("nuna.catalog.read", "nuna.catalog.export");
        assertThat(driver.getCurrentUrl()).contains("kind=read");
        // Five singlestore reads in a row, two spark reads (too few to fold), then three singlestore reads.
        List<WebElement> rows = driver.findElements(By.cssSelector("#audit-feed .feed-row"));
        assertThat(rows.stream().map(r -> String.valueOf(r.getDomAttribute("data-run"))).toList())
                .containsExactly("5", "null", "null", "3");
        assertThat(textContent(rows.get(0).findElement(By.cssSelector(".what"))))
                .matches("loaded version 5 of main · markets\\.fx\\.rates · 5 times, \\d\\d:\\d\\d:\\d\\d to \\d\\d:\\d\\d:\\d\\d");
        assertThat(textContent(rows.get(0).findElement(By.cssSelector(".via")))).isEqualTo("via singlestore-demo");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit-feed .feed-status"))))
                .contains("Like records in a row are folded into one, among the 50 of each page only.");

        // Nothing is hidden: the run opens its five records beneath it, each one a record, and closes again.
        WebElement open = rows.get(0).findElement(By.cssSelector("button"));
        assertThat(textContent(open)).isEqualTo("Show records");
        open.click();
        browser.await("the run did not open", d -> d.findElements(By.cssSelector("#audit-feed .feed-row.in-run")).size() == 5);
        assertThat(driver.findElements(By.cssSelector("#audit-feed .feed-row")).get(1).getDomAttribute("class")).contains("in-run");
        assertThat(textContent(open)).isEqualTo("Hide records");
        open.click();
        browser.await("the run did not close", d -> d.findElements(By.cssSelector("#audit-feed .feed-row.in-run")).isEmpty());

        // On a wide screen a record is one line.
        frameOf(1920);
        WebElement single = browser.await(By.cssSelector("#audit-feed .feed-row:not(.is-run)"));
        assertThat((Boolean) driver.executeScript("return arguments[0].querySelector('.line').getBoundingClientRect().height < 32",
                single))
                .as("a record on one line at 1920 px").isTrue();
        driver.manage().window().setSize(new Dimension(1440, 900));
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
        browser.await("the catalog's own trail did not load",
                d -> d.findElements(By.cssSelector("#audit-page #audit p.window")).isEmpty() ? null : true);
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
        assertThat(textContent(driver.findElement(By.cssSelector("#audit p.window"))))
                .startsWith("Read from nuna.catalog.audit, partition ");

        // The kind chips keep only the refusals.
        browser.await(By.cssSelector("#audit .chip[data-f='refusal']")).click();
        browser.await("rows other than refusals are shown", d -> d.findElements(By.cssSelector("#audit .feed-row"))
                .stream().filter(WebElement::isDisplayed)
                .allMatch(r -> "denial".equals(r.getDomAttribute("data-type"))));
    }

    /** An object's Audit tab as a server with the audit tables answers it: from the table, not Kafka (backlog 197). */
    private static final String AUDIT_TAB_FROM_THE_TABLE = """
            const real = window.fetch;
            const now = Date.now();
            const rec = (i, type) => ({specversion: '1.0', id: 'e0000000-0000-4000-8000-00000000019' + i, source: 'nuna',
              type: 'nuna.catalog.' + type, time: new Date(now - i * 60000).toISOString(),
              subject: 'a0000000-0000-4000-8000-000000000197', data: {actor: {principal: 'gina', requestId: 'r' + i}}});
            const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
            window.fetch = (url, init) => {
              const u = new URL(url, location.origin);
              if (u.pathname.endsWith('/tables/trades/audit')) {
                return json({target: {kind: 'table', name: 'trades'}, source: 'table', records: [rec(1, 'commit'), rec(2, 'read')],
                  complete: false, table: {name: 'nuna_audit.audit.events', 'committed-at': new Date(now - 60000).toISOString()}});
              }
              return real(url, init);
            };
            location.hash = arguments[0];
            """;

    /**
     * Where the audit tables are configured, an object's Audit tab reads its records from the audit table, which holds
     * every record the log does, Kafka or not, and says so (backlog 197, in Atelier's words).
     */
    @Test
    void anObjectsAuditTabSaysItsRecordsCameFromTheAuditTable() {
        signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript(AUDIT_TAB_FROM_THE_TABLE, "#" + at("table", eu, "trades", "audit"));
        browser.await(By.cssSelector("#audit .feed-row[data-type='read']"));

        assertThat(driver.findElements(By.cssSelector("#audit .feed-row")).stream()
                .map(row -> row.getDomAttribute("data-type")).toList()).containsExactly("commit", "read");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit p.window")))).isEqualTo(
                "Read from nuna_audit.audit.events as of its last commit: its writer commits about once a minute.");
        assertThat(textContent(driver.findElement(By.cssSelector("#audit .stack")))).contains(
                "No. Only the newest part of the audit table was searched: search the SIEM for the full trail.")
                .doesNotContain("Kafka", "partition", "offset");
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

    /**
     * The header counts the caller's groups, as the prototype does ("presenter · 4 groups · until 18:42"), and names
     * them on hover and on focus: named in full, the demo's presenter's five groups made the page 1,687 px wide at
     * 1440 px, with Sign out off the screen (backlog 229).
     */
    @Test
    void theHeaderCountsTheGroupsAndNamesThemOnHover() {
        List<String> groups = List.of(IssuerForTests.GROUP, "demo-analysts", "demo-auditors", "demo-grant-admins",
                "demo-operators", "demo-stewards");
        Instant now = clock.instant();
        Instant until = now.plus(Duration.ofHours(1));
        signIn(IssuerForTests.sign(IssuerForTests.claims("tester", "nuna-console", groups.toArray(String[]::new))
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(until)).build()));
        driver.manage().window().setSize(new Dimension(1440, 900));
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#catalog-page table.contents tr[data-kind='namespace'][data-name='" + top + "']"));

        WebElement count = browser.await(By.cssSelector("#who .who-groups"));
        String names = String.join(", ", groups);
        assertThat(driver.executeScript("return arguments[0].firstChild.textContent", count)).isEqualTo("6 groups");
        assertThat(count.getDomAttribute("title")).isEqualTo(names);
        assertThat(count.getDomAttribute("tabindex")).isEqualTo("0");
        assertThat(textContent(driver.findElement(By.cssSelector("#who .who-text")))).startsWith("tester · 6 groups")
                .contains(" · until ");
        assertThat(headerFits()).isTrue();
        assertThat(sideways()).isFalse();

        // The names drop below the count on hover, still within the window.
        new Actions(driver).moveToElement(count).perform();
        browser.await("the groups are not named on hover", d -> String.valueOf(driver.executeScript(
                "return getComputedStyle(arguments[0], '::after').content", count)).contains("demo-stewards"));
        assertThat(headerFits()).isTrue();
        assertThat(sideways()).isFalse();
    }

    /**
     * A frozen and held table's page fits a 1440 px window once its governance has loaded: the freeze's reason, in a pill
     * in Overview's governance facts, wraps in its column instead of running past the window (backlog 232).
     */
    @Test
    void aFrozenAndHeldTablesPageFitsTheWindowOnceItsGovernanceLoads() {
        signIn(tester());
        driver.manage().window().setSize(new Dimension(1440, 900));
        browser.open("/ui/#" + at("table", eu, "trades", null));
        browser.awaitText(By.cssSelector("#table-page h1"), "trades");
        WebElement reason = browser.await(By.cssSelector("#table-page dd .pill.frozen"));
        assertThat(textContent(reason)).contains(FREEZE_REASON);
        browser.await(By.cssSelector("#table-page .title-row .pill.frozen"));
        assertThat(sideways()).isFalse();
    }

    /**
     * Between a phone and a wide screen the header fits the window at every width, its parts side by side without
     * overlapping: what gives way as the window narrows is Atelier's order (backlog 231; Atelier, 2026-11-26), and every
     * control keeps a name that says what it does once its words are hidden. Before, the header ran past the window from
     * 761 to 1,016 px and from 1,241 to 1,297 px. It is checked on a catalog page reading main and on Governance carried
     * through a branch, which adds the branch chip and the note that the page shows main only. As in Atelier's harness,
     * the names are long, so each ends in an ellipsis: the name signed in, shown from {@code preferred_username} while the
     * catalog goes by the subject, and the branch's.
     */
    @Test
    void betweenAPhoneAndAWideScreenTheHeaderFitsAtEveryWidth() {
        List<String> groups = List.of(IssuerForTests.GROUP, "demo-analysts", "demo-auditors", "demo-grant-admins",
                "demo-operators", "demo-stewards");
        Instant now = clock.instant();
        signIn(IssuerForTests.sign(IssuerForTests.claims("tester", "nuna-console", groups.toArray(String[]::new))
                .claim("preferred_username", "tester-at-the-desk")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build()));
        int[] frames = {761, 800, 850, 900, 901, 1000, 1100, 1101, 1240, 1241, 1260, 1320, 1321, 1400, 1401, 1440};
        // The item's own window widths, whatever frame they give.
        int[] windows = {800, 1000, 1260};

        driver.manage().window().setSize(new Dimension(1440, 900));
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#catalog-page table.contents tr[data-kind='namespace'][data-name='" + top + "']"));
        browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]"));
        for (int frame : frames) {
            assertTheHeaderFits(frame, true);
        }
        for (int window : windows) {
            assertTheHeaderFits(window, false);
        }

        // With the header's words hidden, each control keeps its name.
        assertTheHeaderFits(850, true);
        assertThat(driver.findElement(By.cssSelector("#top .nc-go")).getAccessibleName()).isEqualTo("Go to a path");
        assertThat(driver.findElement(By.id("sign-out")).getAccessibleName()).isEqualTo("Sign out");
        assertThat(driver.findElement(By.id("catalog-pick")).getAccessibleName()).isEqualTo("Catalog trading");
        assertThat(driver.findElements(By.cssSelector("#top .seg button")).stream().map(WebElement::getAccessibleName))
                .containsExactly("Light", "Dark", "Auto");
        assertThat(driver.findElement(By.cssSelector("#who .avatar")).getAccessibleName())
                .startsWith("Signed in as tester-at-the-desk, 6 groups, until ");

        String branch = "wide_branch_" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + branch + "\",\"kind\":\"branch\"}"), 201);
        try {
            driver.manage().window().setSize(new Dimension(1440, 900));
            browser.open("/ui/#p=governance&c=trading&r=" + branch);
            browser.awaitText(By.cssSelector("#top #reading-slot [data-main-only]"), "This page shows main only");
            for (int frame : frames) {
                assertTheHeaderFits(frame, true);
            }
            for (int window : windows) {
                assertTheHeaderFits(window, false);
            }
        } finally {
            api.manage("DELETE", "/refs/" + branch, null);
        }
    }

    /**
     * Sizes the window so the console's frame is {@code width} wide (or the window itself, if not {@code frame}), then
     * asserts the header fits: no wider than the window, Sign out within it, and no part of it over another.
     */
    private void assertTheHeaderFits(int width, boolean frame) {
        if (frame) {
            frameOf(width);
        } else {
            driver.manage().window().setSize(new Dimension(width, 900));
        }
        assertThat(headerFits()).as("the header fits at %d px", width).isTrue();
        assertThat(sideways()).as("the page scrolls sideways at %d px", width).isFalse();
        assertThat((String) driver.executeScript(HEADER_OVERLAPS)).as("the header's parts at %d px", width).isEmpty();
    }

    /** Sizes the window so the console's frame, the window less its scroll bar, is {@code width} wide. */
    private void frameOf(int width) {
        driver.manage().window().setSize(new Dimension(width, 900));
        for (int i = 0; i < 3; i++) {
            int now = ((Number) driver.executeScript("return document.getElementById('console').clientWidth")).intValue();
            if (now == width) {
                break;
            }
            Dimension size = driver.manage().window().getSize();
            driver.manage().window().setSize(new Dimension(size.width + width - now, 900));
        }
        assertThat(((Number) driver.executeScript("return document.getElementById('console').clientWidth")).intValue())
                .as("the console's frame").isEqualTo(width);
    }

    /**
     * The words broken across two lines in the cells {@code arguments[0]} selects, each as the letters before the break,
     * a bar, and the letter after it: a line may end at a space, a dot, an underscore, a hyphen or a {@code <br>}, never
     * between two letters or digits (backlog 326).
     */
    private static final String BROKEN_WORDS = """
            const out = [];
            const word = c => /[\\p{L}\\p{N}]/u.test(c);
            for (const cell of document.querySelectorAll(arguments[0])) {
              const walk = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
              let prev = null;
              for (let n = walk.nextNode(); n; n = walk.nextNode()) {
                if (n.nodeType === Node.ELEMENT_NODE) {
                  if (n.tagName === 'BR') prev = null;
                  continue;
                }
                for (let i = 0; i < n.data.length; i++) {
                  const r = document.createRange();
                  r.setStart(n, i);
                  r.setEnd(n, i + 1);
                  const box = r.getClientRects()[0];
                  if (!box) continue;
                  const c = n.data[i];
                  if (prev && word(c) && word(prev.c) && box.top >= prev.bottom - 1) out.push(prev.text + '|' + c);
                  prev = {c, bottom: box.bottom, text: word(c) ? (prev && word(prev.c) ? prev.text : '') + c : ''};
                }
              }
            }
            return out;
            """;

    /** The cells {@code arguments[0]} selects whose words run onto a second line, by their text. */
    private static final String LINES_BROKEN = """
            const out = [];
            for (const cell of document.querySelectorAll(arguments[0])) {
              const r = document.createRange();
              r.selectNodeContents(cell);
              const tops = [...r.getClientRects()].filter(x => x.width > 0 && x.height > 0).map(x => x.top);
              if (tops.length && Math.max(...tops) - Math.min(...tops) > 8) out.push(cell.textContent);
            }
            return out;
            """;

    /**
     * Returns the first overlap in the header, or an empty string: two shown parts of {@code #top} side by side that
     * overlap, or a part whose shown content runs past its own box (the note that the page shows main only ran under Go
     * to a path while the page was still no wider than the window, in Atelier's harness). Content clipped by an element
     * that hides its overflow, and visually hidden words, are left out, as is a part hung under the header (on a phone,
     * the note that the page shows main only: backlog 297).
     */
    private static final String HEADER_OVERLAPS = """
            const shown = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
            const bar = document.getElementById('top').getBoundingClientRect();
            const parts = [...document.getElementById('top').children].filter(shown)
                .map(e => ({e, r: e.getBoundingClientRect()})).filter(p => p.r.width > 1)
                .sort((a, b) => a.r.left - b.r.left);
            const name = e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && e.className.baseVal === undefined ? '.' + String(e.className).trim().split(/\\s+/).join('.') : '');
            for (let i = 1; i < parts.length; i++) {
              const a = parts[i - 1], b = parts[i];
              if (a.r.right > b.r.left + 0.5 && a.r.top < b.r.bottom && b.r.top < a.r.bottom)
                return name(a.e) + ' overlaps ' + name(b.e) + ' by ' + (a.r.right - b.r.left).toFixed(1) + ' px';
            }
            const clipped = (e, part) => {
              for (let x = e.parentElement; x && x !== part; x = x.parentElement)
                if (getComputedStyle(x).overflowX !== 'visible') return true;
              return false;
            };
            for (const {e: part, r} of parts) {
              for (const d of part.querySelectorAll('*')) {
                if (!shown(d) || clipped(d, part)) continue;
                const q = d.getBoundingClientRect();
                if (q.width <= 1 || q.height <= 1) continue;
                if (getComputedStyle(d).position === 'absolute' && q.top >= bar.bottom - 0.5) continue;
                if (q.right > r.right + 0.5 || q.left < r.left - 0.5)
                  return name(d) + ' runs past ' + name(part) + ' (' + q.left.toFixed(1) + '-' + q.right.toFixed(1)
                      + ' against ' + r.left.toFixed(1) + '-' + r.right.toFixed(1) + ')';
              }
            }
            return '';
            """;

    /**
     * On a phone, a page that shows main only, carried through a branch, keeps the header within the window (backlog
     * 297; Atelier, 2026-11-26). Before, at 390 px the note sat on one line beside the Reading chip and the header was
     * 630 px wide, and the brand shrank to a sliver of the peak. Now the note hangs under the header as a strip across
     * the frame and the page moves down to clear it; the brand shows whole from 521 px and not at all below; the names
     * end in an ellipsis; and the chip, which can take focus where the note cannot, is described by the note. Chrome
     * lays out no window narrower than about 500 px, so the frames are set by emulating the screen.
     */
    @Test
    void onAPhoneABranchCarriedOntoAPageThatShowsMainOnlyKeepsTheHeaderInTheWindow() {
        signIn(tester());
        String branch = "wide_branch_" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + branch + "\",\"kind\":\"branch\"}"), 201);
        try {
            browser.open("/ui/#p=governance&c=trading&r=" + branch);
            browser.awaitText(By.cssSelector("#top #reading-slot [data-main-only]"), "This page shows main only");
            assertThat(driver.findElement(By.cssSelector("#top [data-ref-pick]")).getDomAttribute("aria-describedby"))
                    .isEqualTo("main-only");
            HasCdp cdp = (HasCdp) new Augmenter().augment(driver);
            for (int frame : new int[] {361, 390, 520, 521, 760}) {
                phoneFrame(cdp, frame);
                assertThat(headerFits()).as("the header fits at %d px", frame).isTrue();
                assertThat(sideways()).as("the page scrolls sideways at %d px", frame).isFalse();
                assertThat((String) driver.executeScript(HEADER_OVERLAPS)).as("the header's parts at %d px", frame).isEmpty();
                @SuppressWarnings("unchecked")
                Map<String, Object> at = (Map<String, Object>) driver.executeScript(PHONE_HEADER);
                assertThat(at.get("note")).as("the note at %d px", frame).isEqualTo("");
                assertThat(at.get("brand")).as("the brand at %d px", frame).isEqualTo(frame >= 521 ? "whole" : "none");
                assertThat(driver.findElement(By.id("catalog-pick")).getAccessibleName()).as("at %d px", frame)
                        .isEqualTo("Catalog trading");
            }
            cdp.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
        } finally {
            api.manage("DELETE", "/refs/" + branch, null);
        }
    }

    /**
     * On a phone the Reading chip shows main whole beside a long catalog name, and its menu opens inside the window
     * (backlog 300). Atelier found both going wrong in her prototype under rules the console shares: at 361 px "main"
     * ended in an ellipsis while the catalog's name kept its room, and the menu, hung from the chip, ran out of the
     * window. The test's catalog is "trading", so the picker is given a long name, as a long warehouse name would show.
     */
    @Test
    void onAPhoneTheReadingChipShowsMainWholeAndItsMenuOpensInTheWindow() {
        signIn(tester());
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#catalog-page table.contents tr[data-kind='namespace'][data-name='" + top + "']"));
        WebElement chip = browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]"));
        HasCdp cdp = (HasCdp) new Augmenter().augment(driver);
        for (int frame : new int[] {361, 390}) {
            phoneFrame(cdp, frame);
            driver.executeScript("document.querySelector('#catalog-pick [data-cat]').textContent = 'trading_archive_emea_2026'");
            assertThat(headerFits()).as("the header fits at %d px", frame).isTrue();
            assertThat((String) driver.executeScript(HEADER_OVERLAPS)).as("the header's parts at %d px", frame).isEmpty();
            assertThat((String) driver.executeScript(MAIN_AND_CATALOG)).as("at %d px", frame).isEqualTo("main whole, catalog cut");

            chip.click();
            browser.await("the Reading menu did not open", d -> "true".equals(chip.getDomAttribute("aria-expanded")));
            assertThat((String) driver.executeScript(MENU_IN_THE_WINDOW)).as("the Reading menu at %d px", frame).isEmpty();
            chip.click();
            browser.await("the Reading menu did not close", d -> "false".equals(chip.getDomAttribute("aria-expanded")));
        }
        cdp.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
    }

    /** Whether the header's Reading chip shows its ref's name whole and the catalog picker's name ends in an ellipsis. */
    private static final String MAIN_AND_CATALOG = """
            const nm = document.querySelector('#top .ref-pick .cref .nm');
            const cat = document.querySelector('#catalog-pick [data-cat]');
            const main = nm.scrollWidth <= nm.clientWidth ? 'main whole' : 'main cut to ' + nm.clientWidth + ' of ' + nm.scrollWidth + ' px';
            return main + ', catalog ' + (cat.scrollWidth > cat.clientWidth ? 'cut' : 'whole');
            """;

    /** Empty when the open Reading menu lies inside the window and the page does not scroll sideways, else what is wrong. */
    private static final String MENU_IN_THE_WINDOW = """
            const w = document.documentElement.clientWidth;
            const m = document.getElementById('ref-menu').getBoundingClientRect();
            if (m.left < -0.5 || m.right > w + 0.5) return 'the menu spans ' + m.left.toFixed(1) + '-' + m.right.toFixed(1) + ' in ' + w;
            if (document.documentElement.scrollWidth > w) return 'the page is ' + document.documentElement.scrollWidth + ' px wide in ' + w;
            return '';
            """;

    /** Emulates a screen on which the console's frame is {@code frame} wide. */
    private void phoneFrame(HasCdp cdp, int frame) {
        int width = frame;
        for (int i = 0; i < 4; i++) {
            cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride",
                    Map.of("width", width, "height", 844, "deviceScaleFactor", 1, "mobile", false));
            int now = ((Number) driver.executeScript("return document.getElementById('console').clientWidth")).intValue();
            if (now == frame) {
                return;
            }
            width += frame - now;
        }
        assertThat(((Number) driver.executeScript("return document.getElementById('console').clientWidth")).intValue())
                .as("the console's frame").isEqualTo(frame);
    }

    /**
     * On a phone: {@code note} is empty when the note that the page shows main only is shown under the header, within
     * the window and above the page's first part, or says what is wrong; {@code brand} is {@code whole}, {@code none},
     * or {@code cut} when it shows narrower than its content.
     */
    private static final String PHONE_HEADER = """
            const w = document.documentElement.clientWidth;
            const bar = document.getElementById('top').getBoundingClientRect();
            const n = document.querySelector('#top [data-main-only]').getBoundingClientRect();
            const first = document.querySelector('.slot > .nc-main').firstElementChild.getBoundingClientRect();
            let note = '';
            if (n.width < 1 || n.height < 1) note = 'not shown';
            else if (n.top < bar.bottom - 0.5) note = 'in the header (top ' + n.top + ', header ends ' + bar.bottom + ')';
            else if (n.right > w + 0.5 || n.left < -0.5) note = 'past the window (' + n.left + '-' + n.right + ' in ' + w + ')';
            else if (first.top < n.bottom - 0.5) note = 'over the page (ends ' + n.bottom + ', page starts ' + first.top + ')';
            const b = document.querySelector('#top .nc-brand');
            const brand = b.getClientRects().length === 0 ? 'none' : b.scrollWidth > b.clientWidth ? 'cut' : 'whole';
            return {note, brand};
            """;

    /** Whether the header and its last control, Sign out, end within the window. */
    private boolean headerFits() {
        return (Boolean) driver.executeScript("const w = document.documentElement.clientWidth;"
                + " return document.getElementById('top').scrollWidth <= w"
                + " && document.getElementById('sign-out').getBoundingClientRect().right <= w");
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

        for (String page : List.of(at("table", eu, "trades", "history"), at("table", eu, "trades", "schema"),
                at("table", eu, "trades", "settings"), at("namespace", equities, null, "access"),
                "p=governance&c=trading&tab=grants", "c=trading")) {
            browser.open("/ui/#" + page);
            browser.await("the page did not load: " + page, d -> d.findElements(
                    By.cssSelector(".nc-main [data-loading]")).isEmpty() && !d.findElements(
                    By.cssSelector(".nc-main h1")).isEmpty());
            assertThat(sideways()).as(page).isFalse();
        }
    }

    /**
     * Holds the console's requests for the namespaces of warehouse {@code trading@<arguments[0]>} until
     * {@code window.held.release()}: {@code window.held.page} is the page that asked first, and {@code window.held.filled}
     * says whether that page has drawn its head since.
     */
    /** Holds the catalog's name search for the words `arguments[0]` until `window.held.release()` (backlog 305). */
    private static final String HOLD_THE_SEARCH = """
            const words = arguments[0];
            const real = window.fetch;
            let release;
            const gate = new Promise(done => release = done);
            window.held = {asked: false, answered: false, release};
            window.fetch = (url, init) => {
              const asked = new URL(url, location.origin);
              if (!asked.pathname.endsWith('/management/v1/names') || asked.searchParams.get('q') !== words) {
                return real(url, init);
              }
              window.held.asked = true;
              return gate.then(() => real(url, init)).then(answer => {
                window.held.answered = true;
                return answer;
              });
            };
            """;

    private static final String HOLD_THE_BRANCHS_NAMESPACES = """
            const branch = arguments[0];
            const real = window.fetch;
            let release;
            const gate = new Promise(done => release = done);
            window.held = {page: null, release, filled: () => !!(window.held.page && window.held.page.querySelector('.ph'))};
            window.fetch = (url, init) => {
              const path = decodeURIComponent(new URL(url, location.origin).pathname);
              if (!path.endsWith('/trading@' + branch + '/namespaces')) return real(url, init);
              window.held.page = window.held.page || document.getElementById('catalog-page');
              return gate.then(() => real(url, init));
            };
            """;

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
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("fills")), 200);

        signIn(tester());
        browser.open("/ui/#c=trading");
        browser.await(By.cssSelector("#nav a[data-k='refs']")).click();
        browser.await(By.cssSelector("#refs-page .ph button[data-act='ref-new'], #refs-page .refs-empty button[data-act='ref-new']"))
                .click();
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

        // Read through the branch, the table's page says so wherever the catalog is named: the band, the first
        // crumb, and the Reading control in the header beside the catalog picker (Atelier's review, S4; backlog
        // 201), whose menu offers each ref's own page; the tree marks the table the branch changed.
        browser.open("/ui/#" + at("table", List.of(ns), "orders", null) + "&r=" + branch);
        browser.awaitText(By.cssSelector("#table-page h1"), "orders");
        assertThat(browser.awaitText(By.cssSelector("[data-ref-band='" + branch + "']"), "Reading the branch").getText())
                .contains("1 table changed on it");
        assertThat(JsonPath.<String>read(expect(api.manage("GET", "/refs/" + branch, null), 200).body(), "$.cut-at"))
                .isEqualTo(JsonPath.<String>read(made.body(), "$.created-at"));
        assertThat(textContent(browser.await(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading");
        assertThat(textContent(driver.findElement(By.cssSelector("#table-page nav.crumbs > a"))))
                .isEqualTo("trading@" + branch);
        WebElement reading = browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]"));
        assertThat(reading.getDomAttribute("aria-label")).isEqualTo("Reading branch " + branch + ". Choose a branch or tag");
        assertThat(driver.findElements(By.cssSelector("#table-page [data-ref-pick]"))).isEmpty();
        reading.click();
        assertThat(browser.await(By.cssSelector("#ref-menu [data-ref-item='" + branch + "'] a.ref-go"))
                .getDomAttribute("href")).isEqualTo("#c=trading&k=ref&n=" + branch);
        reading.click();
        browser.await(By.cssSelector(".tree[data-ref='" + branch + "'] [data-node='table:" + ns + ".orders'] [data-changed]"));
        browser.await(By.cssSelector(".tree[data-ref='" + branch + "'] [data-node='namespace:" + ns + "'] [data-changed]"));

        // Its Export is the branch's: the branch's metadata file and journal, in a file named for the branch (item 172).
        browser.await(By.cssSelector("#table-page button[data-act='export-meta']")).click();
        WebElement export = browser.await(By.cssSelector("dialog.dlg"));
        assertThat(textContent(export.findElement(By.id("dlg-h")))).isEqualTo("Export orders@" + branch);
        WebElement download = browser.await(By.cssSelector("dialog.dlg a[download]"));
        assertThat(download.getDomAttribute("download")).isEqualTo("trading." + ns + ".orders@" + branch + ".export.json");
        assertThat(textContent(export.findElement(By.cssSelector("pre")))).contains(".branch-", "\"name\": \"" + branch + "\"");
        export.findElement(By.cssSelector("button.dlg-x")).click();

        // The namespace's Contents through the branch fills its columns as the branch has each table (backlog 168):
        // orders changed here at the branch's own version 1, fills as at the point at main's version 1, both with
        // their snapshots. Both are format v2, so Format says one thing on every row and goes, said once beneath the
        // table instead (the 27 Nov review; backlog 331).
        browser.open("/ui/#" + at("namespace", List.of(ns), null, null) + "&r=" + branch);
        WebElement ordersHere = contentsRow("orders");
        assertThat(contentsCell(ordersHere, "snaps")).isEqualTo("0");
        assertThat(textContent(ordersHere.findElement(By.cssSelector(".on-ref")))).contains("changed here");
        WebElement fillsHere = contentsRow("fills");
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents td.fmt"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).doesNotContain("Format", "Kind");
        assertThat(textContent(fillsHere.findElement(By.cssSelector(".on-ref")))).isEqualTo("as at the point · v1");
        // Neither has changed data, orders a property and fills only made, so Last data change here says nothing on
        // any row and goes (backlogs 193 and 330).
        assertThat(driver.findElements(By.cssSelector("#namespace-page table.contents thead th")))
                .extracting(ConsoleBrowser::textContent).doesNotContain("Last data change here");
        assertThat(textContent(driver.findElement(By.cssSelector("#contents .fine"))))
                .contains("Every table here is Iceberg format v2. Format, snapshots and last data change are as @"
                        + branch + " has each table. Open one to"
                        + " read it through @" + branch + ".")
                .doesNotContain("A dash under Last data change");

        // A page that reads main only, Overview, keeps the control: it reads main, and a ref opens the catalog
        // through it.
        browser.open("/ui/#p=overview&c=trading");
        WebElement fromOverview = browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]"));
        assertThat(fromOverview.getDomAttribute("aria-label")).isEqualTo("Reading main. Choose a branch or tag");
        fromOverview.click();
        // The menu makes a branch or tag too, for one who may (backlog 221).
        assertThat(textContent(browser.await(By.cssSelector("#ref-menu [data-act='ref-new-menu']"))))
                .isEqualTo("Make a branch or tag");
        // The catalog reads its contents before it draws the Reading control: they are held here until the caller has
        // gone on to Governance, as a slow answer was in the overnight run of 2026-11-25 (backlog 271).
        driver.executeScript(HOLD_THE_BRANCHS_NAMESPACES, branch);
        driver.findElement(By.cssSelector("#ref-menu [data-ref-item='" + branch + "'] > a:first-child")).click();
        browser.await("the ref did not open the catalog through it",
                d -> d.getCurrentUrl().endsWith("#c=trading&r=" + branch));
        browser.await("the catalog did not ask for the branch's namespaces",
                d -> (Boolean) driver.executeScript("return window.held.page !== null"));
        // Going on to a page that shows main only keeps the branch, and says the page shows main (backlog 200).
        browser.await(By.cssSelector("#nav a[data-k='governance']")).click();
        browser.await("the branch was not kept on Governance",
                d -> d.getCurrentUrl().endsWith("#p=governance&c=trading&r=" + branch));
        assertThat(textContent(browser.await(By.cssSelector("#top #reading-slot [data-main-only]"))))
                .isEqualTo("This page shows main only");
        // The page left gets its contents and draws itself, out of sight: Governance keeps the header.
        driver.executeScript("window.held.release()");
        browser.await("the catalog page left did not draw itself",
                d -> (Boolean) driver.executeScript("return window.held.filled()"));
        driver.executeAsyncScript("setTimeout(arguments[arguments.length - 1], 500)");
        assertThat(textContent(browser.await(By.cssSelector("#top #reading-slot [data-main-only]"))))
                .isEqualTo("This page shows main only");
        assertThat(browser.await(By.cssSelector("#top #reading-slot [data-ref-pick]")).getDomAttribute("aria-label"))
                .isEqualTo("Reading branch " + branch + ". Choose a branch or tag");

        // The merge tab shows the dry run: the table moves from main's version to the next one.
        HttpResponse<String> plan = expect(api.manage("POST", "/refs/" + branch + "/merge?dry-run=true", null), 200);
        int cut = JsonPath.read(plan.body(), "$.tables[0].cut-version");
        int mainNow = JsonPath.read(plan.body(), "$.tables[0].main-version");
        int onBranch = JsonPath.read(plan.body(), "$.tables[0].branch-version");
        int mainAfter = JsonPath.read(plan.body(), "$.tables[0].version");
        // The branch's Changes tab says whether it merges, linked to the Merge tab, so what it changed and whether it
        // merges is one step (Atelier's re-walk after phase 2, backlog 212); its header copies the warehouse name.
        browser.open("/ui/#c=trading&k=ref&n=" + branch);
        WebElement onChanges = browser.awaitText(By.cssSelector(".tab-body .merge-verdict[data-verdict='ready']"),
                "Ready to merge: 1 table would move to main in one transaction.");
        browser.await(By.cssSelector("#ref-changes tr[data-path='" + ns + ".orders']"));
        assertThat(textContent(driver.findElement(By.cssSelector("button[aria-label='Copy the warehouse name trading@" + branch + "']"))))
                .isEqualTo("Copy trading@" + branch);
        // So does the catalog's page read through the branch: it copies the warehouse name with the branch (backlog 220).
        browser.open("/ui/#c=trading&r=" + branch);
        assertThat(textContent(browser.await(By.cssSelector(
                "#catalog-page .ph .side button[aria-label='Copy the warehouse name trading@" + branch + "']"))))
                .isEqualTo("Copy trading@" + branch);
        browser.open("/ui/#c=trading&k=ref&n=" + branch);
        onChanges = browser.awaitText(By.cssSelector(".tab-body .merge-verdict[data-verdict='ready']"),
                "Ready to merge: 1 table would move to main in one transaction.");
        WebElement preview = onChanges.findElement(By.linkText("Preview the merge"));
        assertThat(preview.getDomAttribute("href")).isEqualTo("#c=trading&k=ref&n=" + branch + "&tab=merge");
        preview.click();
        browser.awaitText(By.cssSelector(".merge-verdict[data-verdict='ready']"),
                "Ready to merge: 1 table would move to main in one transaction.");
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
     * Export through a branch (item 172) of a table main has dropped since the branch was made, and made again under
     * the same name: the export is the table the branch reads, by its own id and the cut's file, not main's new table.
     * (A table cannot be made on a branch alone: creates happen on main only, D33.)
     */
    @Test
    void aTableMainHasDroppedAndMadeAgainIsExportedAsTheBranchReadsIt() {
        String ns = "exported_" + Api.suffix();
        String branch = "kept_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + ns + "\"]}"), 200);
        HttpResponse<String> made = expect(api.rest("POST", "/namespaces/" + ns + "/tables",
                Api.TABLE.formatted("orders")), 200);
        String kept = JsonPath.read(made.body(), "$.metadata.table-uuid");
        String cutFile = JsonPath.read(made.body(), "$.metadata-location");
        expect(api.manage("POST", "/refs", "{\"name\":\"" + branch + "\",\"kind\":\"branch\"}"), 201);
        try {
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/orders", null), 204);
            HttpResponse<String> again = expect(api.rest("POST", "/namespaces/" + ns + "/tables",
                    Api.TABLE.formatted("orders")), 200);
            assertThat(JsonPath.<String>read(again.body(), "$.metadata.table-uuid")).isNotEqualTo(kept);

            signIn(tester());
            browser.open("/ui/#" + at("table", List.of(ns), "orders", null) + "&r=" + branch);
            browser.awaitText(By.cssSelector("#table-page h1"), "orders");
            browser.await(By.cssSelector("#table-page button[data-act='export-meta']")).click();
            WebElement export = browser.await(By.cssSelector("dialog.dlg"));
            assertThat(textContent(export.findElement(By.id("dlg-h")))).isEqualTo("Export orders@" + branch);
            assertThat(browser.await(By.cssSelector("dialog.dlg a[download]")).getDomAttribute("download"))
                    .isEqualTo("trading." + ns + ".orders@" + branch + ".export.json");
            assertThat(textContent(export.findElement(By.cssSelector("pre")))).contains(kept, cutFile, "\"dropped\"");
            export.findElement(By.cssSelector("button.dlg-x")).click();
        } finally {
            api.manage("DELETE", "/refs/" + branch, null);
        }
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
            assertThat(textContent(browser.await(By.cssSelector("#catalog-pick [data-cat]")))).isEqualTo("trading");
            assertThat(browser.await(By.cssSelector("#top [data-ref-pick]")).getDomAttribute("aria-label"))
                    .isEqualTo("Reading tag " + tag + ". Choose a branch or tag");
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
            // The old properties link opened the Settings drawer over Overview (backlog 199); closing it keeps the tag.
            driver.findElement(By.cssSelector("#settings-drawer [data-act='close-settings']")).click();
            browser.await("the drawer did not close", d -> d.findElements(By.id("settings-drawer")).isEmpty());
            assertThat(driver.getCurrentUrl()).contains("r=" + tag).doesNotContain("tab=");

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

    /**
     * A table's page when main or the ref read has no such table (backlog 170; Atelier, 2026-11-25): dropped on main
     * and read on main, with the refs that keep it or none; dropped on main and read through a tag that keeps it;
     * made on main after the tag's point; and taken out of the tag, which is not "made after the point".
     */
    @Test
    void aTableDroppedOnMainMadeAfterThePointOrTakenOutOfARefSaysWhich() {
        String ns = "kept_" + Api.suffix();
        String tag = "eod_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + ns + "\"]}"), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("fills")), 200);
        String fills = api.id(ns, "tables", "fills");
        expect(api.manage("POST", "/refs", "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}"), 201);
        try {
            expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("late")), 200);
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/orders", null), 204);
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/fills", null), 204);
            expect(api.manage("POST", "/objects/" + fills + "/refs/remove", "{\"refs\":[\"" + tag + "\"]}",
                    AUTH, api.steward()), 200);

            signIn(tester());
            // Dropped on main, read on main: the 404, when it was dropped, and the tag that still keeps it. Who dropped
            // it comes from the audit, which the tester may not read (auditOpen; D23): the words without an actor.
            browser.open("/ui/#" + at("table", List.of(ns), "orders", null));
            WebElement dropped = browser.await(By.id("dropped-on-main"));
            assertThat(textContent(dropped)).startsWith("It was dropped on ").contains("Main has no such table.",
                    "Still kept by @" + tag + ".");
            dropped.findElement(By.cssSelector("[data-kept-ref='" + tag + "']"));
            dropped.findElement(By.cssSelector("[data-kept-acts]"));
            assertThat(textContent(browser.await(By.cssSelector("#table-page .title-row [data-dropped-pill]"))))
                    .isEqualTo("Dropped on main");
            assertThat(driver.findElements(By.cssSelector("#table-page .refusal"))).isNotEmpty();

            // Dropped on main, and taken out of the tag: no branch or tag keeps it.
            browser.open("/ui/#" + at("table", List.of(ns), "fills", null));
            browser.awaitText(By.id("dropped-on-main"), "No branch or tag keeps it. Cleanup can purge it and delete its files.");

            // Dropped on main, read through the tag, which keeps it.
            browser.open("/ui/#" + at("table", List.of(ns), "orders", null) + "&r=" + tag);
            WebElement kept = browser.await(By.id("dropped-through-ref"));
            assertThat(textContent(kept)).startsWith(ns + ".orders was dropped on main on ").contains(
                    "@" + tag + " still keeps it, so it reads the table as it was; main answers 404 for it."
                            + " It stays readable here, and its files stay, until it is taken out of every branch and"
                            + " tag that keeps it.");
            assertThat(kept.findElement(By.cssSelector("a[data-kept-link]")).getDomAttribute("href"))
                    .contains("tab=access", "r=" + tag);
            assertThat(textContent(browser.await(By.cssSelector("#table-page .title-row [data-dropped-pill]"))))
                    .startsWith("Dropped on main ");

            // Made on main after the tag's point.
            browser.open("/ui/#" + at("table", List.of(ns), "late", null) + "&r=" + tag);
            assertThat(textContent(browser.await(By.id("made-after")))).startsWith(ns + ".late was made on ")
                    .contains(", after @" + tag + "'s point, ")
                    .endsWith("A ref holds only what main held then.");

            // Main had it at the point, and it was taken out of the tag: the fourth state.
            browser.open("/ui/#" + at("table", List.of(ns), "fills", null) + "&r=" + tag);
            assertThat(textContent(browser.await(By.id("taken-out")))).startsWith("@" + tag + " no longer keeps " + ns
                    + ".fills, though main had it at the point: it was taken out of it.");
            assertThat(driver.findElements(By.id("made-after"))).isEmpty();

            // Made again on main and dropped again: two dropped tables at the path, newest first, each with its own
            // refs and its own slot for item 279's control (Atelier's check, 2026-11-25).
            expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/orders", null), 204);
            browser.open("/ui/#" + at("table", List.of(ns), "orders", null));
            WebElement twice = browser.await(By.id("dropped-on-main"));
            browser.await("the second dropped table is not drawn",
                    d -> twice.findElements(By.cssSelector("[data-kept-object]")).size() == 2);
            List<WebElement> generations = twice.findElements(By.cssSelector("[data-kept-object]"));
            assertThat(textContent(generations.get(0))).startsWith("It was dropped on ").contains("Main has no such table.",
                    "No branch or tag keeps it. Cleanup can purge it and delete its files.");
            assertThat(textContent(generations.get(1))).startsWith("An earlier table at this path was dropped on ").contains(
                    "Still kept by @" + tag + ".");
            assertThat(generations.get(1).findElements(By.cssSelector("[data-kept-acts]"))).hasSize(1);

            // Made again on main: through the tag, the page reads the old table, and main's is a different one.
            expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);
            browser.open("/ui/#" + at("table", List.of(ns), "orders", null) + "&r=" + tag);
            WebElement replaced = browser.await(By.id("replaced-on-main"));
            assertThat(textContent(replaced)).startsWith(ns + ".orders was dropped on main on ").contains(
                    ", and main made a new table by that name on ",
                    "@" + tag + " still keeps the old one, so this page reads the table as it was. Main's " + ns
                            + ".orders is a different table. It stays readable here, and its files stay, until it is"
                            + " taken out of every branch and tag that keeps it.");
            assertThat(replaced.findElement(By.cssSelector("a[data-main-table]")).getDomAttribute("href"))
                    .contains("n=orders").doesNotContain("r=" + tag);
            assertThat(replaced.findElement(By.cssSelector("a[data-kept-link]")).getDomAttribute("href"))
                    .contains("tab=access", "r=" + tag);
            assertThat(textContent(browser.await(By.cssSelector("#table-page .title-row [data-dropped-pill]"))))
                    .startsWith("Dropped on main ");
            // Main's tabs read by path would show the new table's data: each says so instead. (Audit takes the same
            // guard, for a caller whose trail is open; the tester's may not be.)
            for (String tab : List.of("access", "maintenance")) {
                browser.open("/ui/#" + at("table", List.of(ns), "orders", tab) + "&r=" + tag);
                WebElement guard = browser.await(By.id("main-tabs-replaced"));
                assertThat(textContent(guard)).contains("These tabs are main's, and main's " + ns + ".orders is a"
                        + " different table from the one @" + tag + " reads. Open them on main's " + ns + ".orders.");
                assertThat(guard.findElement(By.cssSelector("a[data-main-tab]")).getDomAttribute("href"))
                        .contains("tab=" + tab).doesNotContain("r=" + tag);
                guard.findElement(By.cssSelector("[data-kept-panel]"));
            }
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
        }
    }

    /**
     * Taking a table out of branches and tags from its Access tab (backlog 279; Atelier, 2026-11-25). A reader without
     * {@code manage-tags} sees which refs keep a table main dropped and the steward's line, and no button; a tag steward,
     * who holds {@code manage-tags} and not {@code select}, takes it out of the tag through the dialog, which opens on
     * Cancel with nothing chosen, and reads what the answer left; the panel then says nothing keeps it.
     */
    @Test
    void aStewardTakesADroppedTableOutOfATagFromAccessAndAReaderSeesNoButton() {
        String ns = "out_" + Api.suffix();
        String tag = "eod_" + Api.suffix();
        expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"" + ns + "\"]}"), 200);
        expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("orders")), 200);
        expect(api.manage("POST", "/refs", "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}"), 201);
        try {
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/orders", null), 204);
            String access = "/ui/#" + at("table", List.of(ns), "orders", "access");

            // The tester holds create and modify, not manage-tags: the list and the steward's line, no button.
            signIn(tester());
            browser.open(access);
            WebElement panel = browser.await(By.cssSelector("#kept-by-refs .kept-panel"));
            browser.awaitText(By.cssSelector("#kept-by-refs [data-kept-lead='dropped']"), "Main dropped this table on ");
            assertThat(textContent(panel)).contains("Kept by branches and tags 1",
                    "These still read it and keep its files from cleanup; once none does, cleanup can purge it and"
                            + " delete its files.",
                    "@" + tag, "tag · made ",
                    "A tag steward, holding manage-tags on it, can take it out of them so cleanup can delete its files.");
            assertThat(panel.findElement(By.cssSelector("[data-kept-row='" + tag + "'] a")).getDomAttribute("href"))
                    .contains("r=" + tag);
            assertThat(driver.findElements(By.cssSelector("button[data-act='take-out']"))).isEmpty();

            // The steward: manage-tags on the catalog, and no select.
            signIn(person("stella", GovernanceServer.STEWARDS));
            browser.open(access);
            browser.await(By.cssSelector("#kept-by-refs button[data-act='take-out']")).click();
            WebElement dialog = browser.await(By.cssSelector("dialog.dlg"));
            assertThat(textContent(dialog.findElement(By.id("dlg-h")))).isEqualTo("Take orders out of branches and tags");
            assertThat(textContent(dialog)).contains("Each branch and tag you choose stops keeping " + ns + ".orders.",
                    "Main dropped it on ", "This cannot be undone:", "Every branch and tag that keeps it (1)",
                    "Only the ones I tick");
            // Focus starts on Cancel, and nothing is chosen.
            assertThat(textContent(driver.switchTo().activeElement())).isEqualTo("Cancel");
            assertThat(dialog.findElements(By.cssSelector("input:checked"))).isEmpty();
            WebElement submit = dialog.findElement(By.cssSelector("button[type='submit']"));
            assertThat(textContent(submit)).isEqualTo("Take it out");
            submit.click();
            browser.awaitText(By.cssSelector("dialog.dlg .dlg-out"), "Choose which branches and tags to take it out of.");

            // Ticking the tag chooses "Only the ones I tick", and the button names it.
            dialog.findElement(By.cssSelector("input[name='ref'][value='" + tag + "']")).click();
            assertThat(dialog.findElement(By.cssSelector("input[name='which'][value='some']")).isSelected()).isTrue();
            assertThat(textContent(submit)).isEqualTo("Take it out of @" + tag);
            submit.click();
            // The tag is a chip (cref), whose @ and name the browser's rendered text puts on lines of their own: the
            // wait reads up to the chip, and the words are checked as the page holds them.
            WebElement took = browser.awaitText(By.cssSelector("dialog.dlg .granted"), "Took " + ns + ".orders out of");
            assertThat(textContent(took)).contains("Took " + ns + ".orders out of @" + tag,
                    "No branch or tag keeps it now. Cleanup can purge it and delete its files.");
            assertThat(submit.isDisplayed()).isFalse();

            // On close the panel reads again.
            dialog.findElement(By.cssSelector("button.dlg-x")).click();
            browser.awaitText(By.cssSelector("#kept-by-refs [data-kept-none]"), "No branch or tag keeps this table.");
            assertThat(driver.findElements(By.cssSelector("button[data-act='take-out']"))).isEmpty();
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
        }
    }

    /**
     * A tag made with a purpose in the console (backlog 167; Atelier, 2026-11-22 and 11-25): the make dialog's field
     * in her words, the purpose under the tag's chip in the list and first on its page's sub-line, shown as the text
     * it is, markup included; a tag made without one shows no line and no filler.
     */
    @Test
    void aRefMadeWithAPurposeShowsItAsText() {
        String tag = "why_" + Api.suffix();
        String plain = "plain_" + Api.suffix();
        String purpose = "before the <b>November</b> restatement & <img src=x onerror=alert(1)>";
        expect(api.manage("POST", "/refs", "{\"name\":\"" + plain + "\",\"kind\":\"tag\"}"), 201);
        try {
            signIn(tester());
            browser.open("/ui/#c=trading");
            browser.await(By.cssSelector("#nav a[data-k='refs']")).click();
            browser.await(By.cssSelector("#refs-page .ph button[data-act='ref-new'], #refs-page .refs-empty button[data-act='ref-new']"))
                    .click();
            WebElement make = browser.await(By.cssSelector("dialog.dlg"));
            make.findElement(By.cssSelector("button[data-k='tag']")).click();
            WebElement why = make.findElement(By.cssSelector("input[name='description']"));
            assertThat(why.getDomAttribute("placeholder")).isEqualTo("why it exists, such as: before the November restatement");
            assertThat(why.getDomAttribute("maxlength")).isEqualTo("200");
            WebElement field = why.findElement(By.xpath("ancestor::label[1]"));
            assertThat(textContent(field.findElement(By.cssSelector(":scope > span:first-child")))).isEqualTo("Purpose (optional)");
            assertThat(textContent(field.findElement(By.cssSelector(":scope > span.fine")))).isEqualTo("One line, at most 200"
                    + " characters. Everyone who can see trading's branches and tags reads it, and the audit keeps it.");
            make.findElement(By.cssSelector("input[name='name']")).sendKeys(tag);
            why.sendKeys(purpose);
            make.findElement(By.cssSelector("button[type='submit']")).click();
            browser.awaitText(By.cssSelector("dialog.dlg .granted"), "Made the tag @" + tag);
            HttpResponse<String> made = expect(api.manage("GET", "/refs/" + tag, null), 200);
            assertThat(JsonPath.<String>read(made.body(), "$.description")).isEqualTo(purpose);

            browser.open("/ui/#c=trading&tab=refs");
            WebElement line = browser.await(By.cssSelector("tr[data-ref='" + tag + "'] td.name .why"));
            assertThat(textContent(line)).isEqualTo(purpose);
            assertThat(line.findElements(By.xpath("*"))).as("the purpose is text, not markup").isEmpty();
            assertThat(driver.findElements(By.cssSelector("tr[data-ref='" + plain + "'] td.name .why"))).isEmpty();

            browser.open("/ui/#c=trading&k=ref&n=" + tag);
            WebElement sub = browser.awaitText(By.cssSelector(".ph .sub"), "Made from main");
            assertThat(textContent(sub)).startsWith(purpose + ". Made from main by ");
            assertThat(sub.findElements(By.cssSelector("img"))).isEmpty();
            browser.open("/ui/#c=trading&k=ref&n=" + plain);
            assertThat(textContent(browser.awaitText(By.cssSelector(".ph .sub"), "Made from main")))
                    .startsWith("Made from main by ");

            // Its record's words end with the purpose in typographic quotes, as text (Atelier, 2026-11-25). The Record
            // tab is an audit reader's, and the tester reads no audit: a grant admin opens it. This server has no audit
            // tables, so the record of the tag's making, with its purpose as made, is answered in the page.
            String id = JsonPath.read(made.body(), "$.id");
            signIn(api.grantAdmin());
            browser.open("/ui/");
            browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
            driver.executeScript("window.refName = arguments[0]; window.refId = arguments[1]; window.refPurpose = arguments[2];"
                    + REF_RECORDS_IN_THE_PAGE + "location.hash = '#c=trading&k=ref&n=' + arguments[0] + '&tab=record';",
                    tag, id, purpose);
            WebElement record = browser.awaitText(By.cssSelector("#ref-record .what"), "Its purpose");
            assertThat(textContent(record)).endsWith("; it never expires. Its purpose: “" + purpose + "”");
            assertThat(record.findElements(By.cssSelector("img, b"))).isEmpty();
        } finally {
            api.manage("DELETE", "/refs/" + tag, null);
            api.manage("DELETE", "/refs/" + plain, null);
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
        return person("tester", IssuerForTests.GROUP);
    }

    /** The demo's ana (demo/README.md): create and modify on the catalog, and no grant to manage. */
    private String ana() {
        return person("ana", IssuerForTests.GROUP);
    }

    /** The demo's gary (demo/README.md): a grant admin, who reads no data (D18). */
    private String gary() {
        return person("gary", GovernanceServer.GRANT_ADMINS);
    }

    /** A console sign-in's token for {@code name} in {@code groups}. */
    private String person(String name, String... groups) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(name, "nuna-console", groups)
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

    /**
     * Opens Grant access from the page's button unless it is open, types the group, picks the access and waits for the
     * live line to answer (backlog 248).
     */
    private void grant(String privilege, String group) {
        if (driver.findElements(By.cssSelector("dialog.dlg form.grant-dlg")).isEmpty()) {
            openGrantAccess(".nc-main");
        }
        WebElement input = browser.await(By.cssSelector("dialog.dlg input[aria-label='Group']"));
        input.clear();
        input.sendKeys(group);
        browser.await(By.cssSelector("dialog.dlg input[name='pv'][value='" + privilege + "']")).click();
        answer();
    }

    /**
     * Opens Grant access from its button within {@code within}, scrolled to the middle of the window first: at the top
     * of an Access tab, scrolling it just into view leaves it under the sticky header.
     */
    private WebElement openGrantAccess(String within) {
        WebElement button = browser.await(By.cssSelector(within + " [data-act='grant-access']"));
        driver.executeScript("arguments[0].scrollIntoView({block: 'center'})", button);
        button.click();
        return browser.await(By.cssSelector("dialog.dlg form.grant-dlg"));
    }

    /** The Grant access dialog's live line, once the grant's preview has answered: admitted, unchanged or refused. */
    private String answer() {
        return browser.await("the live line did not answer", d -> {
            String answer = d.findElement(By.cssSelector("dialog.dlg .grant-line")).getDomAttribute("data-answer");
            return List.of("admitted", "unchanged", "refused", "error").contains(answer) ? answer : null;
        });
    }

    /** The live line's first sentence. */
    private String liveLine() {
        return textContent(browser.await(By.cssSelector("dialog.dlg .grant-line > p")));
    }

    private WebElement grantButton() {
        return driver.findElement(By.cssSelector("dialog.dlg .dlg-acts button[type='submit']"));
    }

    /** A group the dialog offers, by name. */
    private static By groupChoice(String group) {
        return By.cssSelector("dialog.dlg button.grp[data-group='" + group + "']");
    }

    private void closeDialog() {
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog did not close", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
    }


    /** A dotted path, as the console writes one for people. */
    private static String dotted(List<String> levels) {
        return String.join(".", levels);
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

    /** A table's Iceberg REST path below the catalog's prefix. */
    private static String tableRest(List<String> levels, String table) {
        return "/namespaces/" + ns(levels) + "/tables/" + table;
    }

    private static String json(List<String> levels) {
        return levels.stream().map(level -> "\"" + level + "\"").collect(
                java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
