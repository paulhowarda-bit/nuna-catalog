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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * A table flagged for row and column rules in the console, in a real browser (D20; Atelier, 2026-11-24; backlog 241):
 * its Overview, Schema and History say it is read through Trino instead of the catalog's refusal; a steward flags it
 * and removes the flag from its Access tab, and is told in words that the rules stand while they do; and a rule change
 * has its own kind and a sentence in the object's trail and on the Audit page. A flagged table that is closed until
 * classified says that instead, and leads a steward to classify it (backlog 324).
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleFlaggedTableBrowserTest {

    private static final String AUTH = "Authorization";
    private static final String TRINO = "Its data is read through Trino, which applies each group's rule. The console"
            + " shows no rows.";
    private static final String FLAG = "row-column-policy";

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
    private String steward;

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        steward = api.steward();
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
    void aStewardFlagsATableItsTabsSayItIsReadThroughTrinoAndTheFlagStaysWhileRulesStand() {
        String ns = api.namespace();
        api.table(ns, "positions");
        String table = "/namespaces/" + Api.ns(ns) + "/tables/positions";
        browser.signIn(steward);

        // A namespace offers no flag: it is a table's own (backlog 239).
        browser.open("/ui/#" + at(ns, null, "access"));
        browser.await(By.cssSelector("#governance [data-acts='labels'] button[data-label='owner']"));
        assertThat(driver.findElements(flagButton())).isEmpty();

        // The steward flags the table.
        browser.open("/ui/#" + at(ns, "positions", "access"));
        assertThat(browser.await(flagButton()).getText()).isEqualTo("Flag for row and column rules");
        browser.await(flagButton()).click();
        assertThat(browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText())
                .contains("only a trusted engine reads or writes its files, and the console shows no rows",
                        "Until the grant admin sets rules, Trino shows every row to anyone who may select.",
                        "recorded as set from the console");
        submit("is flagged for row and column rules");
        browser.awaitText(flagButton(), "Remove the flag");
        assertThat(own(table).get(FLAG)).containsEntry("value", "true").containsEntry("provenance", "console")
                .containsEntry("set-by", "stella");

        // A reader's Overview, Schema and History say why the console shows no rows, and no refusal.
        expect(api.send("GET", "/v1/trading" + table, null, AUTH, tester()), 403);
        browser.signIn(tester());
        for (String tab : new String[] {null, "schema", "history"}) {
            browser.open("/ui/#" + at(ns, "positions", tab));
            WebElement words = browser.await(By.id("read-through-trino"));
            assertThat(textContent(words)).as(tab).isEqualTo(TRINO);
            assertThat(driver.findElements(By.cssSelector("#table-page .tab-body .refusal"))).as(tab).isEmpty();
        }

        // While the grant admin's rule stands, the flag stays, and the steward is told so in words.
        String rule = rule(ns, "positions", "readers-" + Api.suffix());
        expect(api.manage("POST", "/row-column-rules", rule, AUTH, api.grantAdmin()), 204);
        browser.signIn(steward);
        browser.open("/ui/#" + at(ns, "positions", "access"));
        browser.awaitText(flagButton(), "Remove the flag").click();
        assertThat(browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText())
                .contains("While rules stand it is refused: the grant admin removes them first.");
        browser.await(By.cssSelector("dialog.dlg button[type='submit']")).click();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-rules-stand]"))))
                .isEqualTo("Rules stand: the grant admin removes them first.");
        assertThat(own(table).get(FLAG)).containsEntry("value", "true");
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();

        // Once the rule is gone, the flag comes off and the table reads again.
        expect(api.manage("POST", "/row-column-rules/remove", rule, AUTH, api.grantAdmin()), 204);
        browser.open("/ui/#" + at(ns, "positions", "access"));
        browser.awaitText(flagButton(), "Remove the flag").click();
        submit("is no longer flagged");
        browser.awaitText(flagButton(), "Flag for row and column rules");
        assertThat(own(table)).doesNotContainKey(FLAG);
        expect(api.send("GET", "/v1/trading" + table, null, AUTH, tester()), 200);
    }

    /**
     * A flagged table in a regulated area with no classification of its own is closed (D27), and the console says so
     * from the table's own answer, not as read through Trino (the 27 Nov review, item 2; backlog 324): one notice on
     * each tab that needs its load, a pill in the header, a mark in the tree and its legend, and "nothing until it is
     * classified" in What you may do, with no reader's-view refusal; a steward who reads it sets its classification
     * from the notice, and the table opens, to the Trino words.
     */
    @Test
    void aTableClosedUntilClassifiedSaysSoAndAStewardClassifiesItFromTheNotice() {
        String ns = api.namespace();
        api.table(ns, "owners");
        String table = "/namespaces/" + Api.ns(ns) + "/tables/owners";
        expect(api.manage("PUT", table + "/attributes/" + FLAG, "{\"value\":\"true\",\"provenance\":\"test\"}", AUTH,
                steward), 200);
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ns) + "/attributes/regulated",
                "{\"value\":\"true\",\"provenance\":\"test\"}", AUTH, steward), 200);
        assertThat(JsonPath.<String>read(expect(api.send("GET", "/v1/trading" + table, null, AUTH, tester()), 403).body(),
                "$.error.message")).endsWith("it is closed until it is classified");
        String closed = "Closed until classified. owners is in the regulated area " + ns + " and has no classification"
                + " of its own, so nobody can read or change it. A steward opens it by setting a classification.";

        // A reader: one notice on each tab that needs the load, never the Trino words or the refusal, and no button.
        browser.signIn(tester());
        for (String tab : new String[] {null, "schema", "history"}) {
            browser.open("/ui/#" + at(ns, "owners", tab));
            assertThat(textContent(browser.await(By.id("closed-until-classified")))).as(tab).isEqualTo(closed);
            assertThat(driver.findElements(By.id("read-through-trino"))).as(tab).isEmpty();
            assertThat(driver.findElements(By.cssSelector("#table-page .tab-body .refusal"))).as(tab).isEmpty();
            assertThat(driver.findElements(By.cssSelector("[data-act='classify-closed']"))).as(tab).isEmpty();
        }
        assertThat(textContent(browser.await(By.cssSelector("#table-page .title-row .pill[data-closed]"))))
                .isEqualTo("Closed until classified");
        browser.await(By.cssSelector(".tree a.node[data-node='table:" + ns + ".owners'] .mk i.m-closed"));
        assertThat(driver.findElements(By.cssSelector(".tree-key span")).stream().map(ConsoleBrowser::textContent))
                .contains("closed until classified");

        // Its Access tab: the notice in place of the reader's view, and nothing until it is classified.
        browser.open("/ui/#" + at(ns, "owners", "access"));
        assertThat(textContent(browser.await(By.cssSelector("#access-tab #closed-until-classified")))).isEqualTo(closed);
        assertThat(textContent(browser.await(By.cssSelector("#you-may [data-closed]"))))
                .isEqualTo("Nothing until it is classified; then:");
        assertThat(driver.findElements(By.id("your-view"))).isEmpty();
        assertThat(driver.findElements(By.xpath("//div[@id='access-tab']//div[contains(@class,'refusal')]"
                + "[contains(., 'closed until it is classified')]"))).isEmpty();

        // A steward who reads it: Set a classification in the notice, the Access tab's dialog; the table opens.
        browser.signIn(readingSteward());
        browser.open("/ui/#" + at(ns, "owners", "overview"));
        WebElement set = browser.await(By.cssSelector("#closed-until-classified button[data-act='classify-closed']"));
        assertThat(set.getText()).isEqualTo("Set a classification");
        assertThat(set.getDomAttribute("class")).contains("primary");
        set.click();
        assertThat(browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText())
                .contains("A table in a regulated area stays closed until it has a classification of its own.");
        new org.openqa.selenium.support.ui.Select(browser.await(By.cssSelector("dialog.dlg select[name='cls']")))
                .selectByValue("internal");
        submit("is internal");
        assertThat(textContent(browser.await(By.id("read-through-trino")))).isEqualTo(TRINO);
        assertThat(driver.findElements(By.id("closed-until-classified"))).isEmpty();
        browser.await("the header still says closed", d -> d.findElements(
                By.cssSelector("#table-page .title-row .pill[data-closed]")).isEmpty());
        browser.await("the tree still marks it closed", d -> d.findElements(
                By.cssSelector(".tree a.node[data-node='table:" + ns + ".owners'] .mk i.m-closed")).isEmpty());
        assertThat(own(table).get("classification")).containsEntry("value", "internal").containsEntry("provenance", "console");
    }

    /** A rule set and removed is its own kind in the table's trail, each with who did it, for which group, on which table. */
    @Test
    void aRuleChangeHasItsOwnKindAndASentenceInTheTablesTrail() {
        String ns = api.namespace();
        api.table(ns, "positions");
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ns) + "/tables/positions/attributes/" + FLAG,
                "{\"value\":\"true\",\"provenance\":\"test\"}", AUTH, steward), 200);
        String group = "readers-" + Api.suffix();
        String rule = rule(ns, "positions", group);
        expect(api.manage("POST", "/row-column-rules", rule, AUTH, api.grantAdmin()), 204);
        expect(api.manage("POST", "/row-column-rules/remove", rule, AUTH, api.grantAdmin()), 204);
        String on = " the rule for " + group;

        browser.signIn(api.grantAdmin());
        browser.open("/ui/#" + at(ns, "positions", "audit"));
        long deadline = System.nanoTime() + ConsoleBrowser.WAIT.toNanos();
        List<String> said;
        while (true) {
            browser.await("the trail did not load", d -> !d.findElements(By.cssSelector("#audit p.window")).isEmpty()
                    && d.findElements(By.cssSelector("#audit .feed [data-loading]")).isEmpty());
            said = driver.findElements(By.cssSelector("#audit .feed-row[data-type='row-column-rule'] .what")).stream()
                    .map(ConsoleBrowser::textContent).toList();
            if (said.size() == 2 || System.nanoTime() > deadline) {
                break;
            }
            // The relay publishes a change a moment after it is made: ask again.
            driver.findElement(By.xpath("//div[@id='audit']//button[normalize-space()='Refresh']")).click();
        }
        assertThat(said).containsExactly("removed" + on, "set" + on);
        assertThat(driver.findElements(By.cssSelector("#audit .feed-row[data-type='row-column-rule']")))
                .allSatisfy(row -> assertThat(row.getDomAttribute("data-kind")).isEqualTo("rule"))
                .allSatisfy(row -> assertThat(textContent(row.findElement(By.cssSelector(".badge")))).isEqualTo("Rule"));
        assertThat(textContent(browser.await(By.cssSelector("#audit .chip[data-f='rule']")))).isEqualTo("Row and column rules 2");
    }

    /**
     * The Audit page's feed and counts, answered in the page, since this server has no audit tables: a rule change is
     * counted, filtered and badged as its own kind, and said in a sentence.
     */
    @Test
    void theAuditPageCountsAndFiltersRuleChangesAsTheirOwnKind() {
        browser.signIn(api.grantAdmin());
        browser.open("/ui/");
        browser.await("the grant admin did not land on Audit", d -> d.getCurrentUrl().endsWith("#p=audit&c=trading"));
        driver.executeScript("""
                const real = window.fetch;
                window.asked = [];
                const now = Date.now();
                const rec = (i, type, data) => ({time: new Date(now - i * 60000).toISOString(), type: 'nuna.catalog.' + type,
                  principal: 'gina', 'event-id': 'e' + i, partition: 0, offset: i, 'request-id': 'r' + i,
                  object: {kind: 'TABLE', path: ['equities', 'eu', 'positions']}, event: {data}, chain: {status: 'no-archive'}});
                const records = [rec(1, 'row-column-rule', {action: 'SET', group: 'demo-analysts', rowFilter: "desk = 'EQ'",
                    columnMasks: {}, table: {kind: 'TABLE', catalog: 'trading', path: ['equities', 'eu', 'positions']},
                    actor: {principal: 'gina'}}),
                  rec(2, 'grant', {})];
                const json = body => Promise.resolve(new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}}));
                window.fetch = (url, init) => {
                  const u = new URL(url, location.origin);
                  if (u.pathname.endsWith('/audit/summary')) {
                    return json({'by-type': {'nuna.catalog.row-column-rule': 1, 'nuna.catalog.grant': 1},
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
                """);
        WebElement row = browser.await(By.cssSelector("#audit-feed .feed-row[data-type='row-column-rule']"));
        assertThat(textContent(row.findElement(By.cssSelector(".badge")))).isEqualTo("Rule");
        assertThat(textContent(row.findElement(By.cssSelector(".what"))))
                .startsWith("set the rule for demo-analysts");
        assertThat(driver.findElements(By.cssSelector("#audit-kinds tr:has(td)")).stream()
                .map(r -> textContent(r.findElements(By.tagName("td")).get(0)).trim() + " "
                        + textContent(r.findElements(By.tagName("td")).get(1)).trim()).toList())
                .contains("Row and column rules 1", "Grants 1");

        browser.await(By.cssSelector("#audit-feed .chip[data-f='rule']")).click();
        browser.await("the feed did not show only rule changes", d -> d.findElements(By.cssSelector("#audit-feed .feed-row"))
                .stream().map(r -> r.getDomAttribute("data-type")).toList().equals(List.of("row-column-rule")));
        assertThat((String) driver.executeScript("return window.asked[window.asked.length - 1];"))
                .isEqualTo("nuna.catalog.row-column-rule");
        assertThat(driver.getCurrentUrl()).contains("kind=rule");
    }

    /** The tester: create and modify on the catalog, so select, and no trusted engine. */
    private String tester() {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims("tester", "nuna-console", IssuerForTests.GROUP)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    /** A steward who also reads, as the demo's stella does: the tester's grants and manage-tags. */
    private String readingSteward() {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims("stella", "nuna-console", IssuerForTests.GROUP,
                        GovernanceServer.STEWARDS)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    /** A rule for {@code group} on table {@code name} in the one-level namespace {@code ns}: one row filter. */
    private static String rule(String ns, String name, String group) {
        return "{\"table\":{\"type\":\"table\",\"namespace\":[\"" + ns + "\"],\"name\":\"" + name + "\"},"
                + "\"group\":\"" + group + "\",\"row-filter\":\"id > 0\"}";
    }

    // ---- the page ----

    /** A namespace, or a table in the one-level namespace {@code ns}, on {@code tab} (null for its overview). */
    private static String at(String ns, String name, String tab) {
        return "c=trading&k=" + (name == null ? "namespace" : "table") + "&ns=" + ns + (name == null ? "" : "&n=" + name)
                + Optional.ofNullable(tab).map(t -> "&tab=" + t).orElse("");
    }

    private static By flagButton() {
        return By.cssSelector("#governance [data-acts='labels'] button[data-label='" + FLAG + "']");
    }

    /** Submits the open dialog, waits for the catalog's answer naming {@code done}, and closes it. */
    private void submit(String done) {
        browser.await(By.cssSelector("dialog.dlg button[type='submit']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), done);
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog is still open", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
    }

    /** The labels set on the target itself, by name: each one's value, provenance and setter. */
    private Map<String, Map<String, String>> own(String path) {
        HttpResponse<String> governance = expect(api.manage("GET", path + "/governance", null, AUTH, steward), 200);
        String here = JsonPath.read(governance.body(), "$.target.id");
        List<Map<String, Object>> attributes = JsonPath.read(governance.body(), "$.attributes");
        return attributes.stream().filter(a -> String.valueOf(a.get("level")).endsWith(":" + here))
                .collect(java.util.stream.Collectors.toMap(a -> (String) a.get("attribute"), a -> Map.of(
                        "value", String.valueOf(a.get("value")), "provenance", String.valueOf(a.get("provenance")),
                        "set-by", String.valueOf(a.get("set-by")))));
    }
}
