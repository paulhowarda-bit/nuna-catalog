package nuna.catalog.server.governance;

import static nuna.catalog.server.ConsoleBrowser.textContent;
import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.server.ConsoleBrowser;
import nuna.catalog.server.IssuerForTests;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
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
 * Row filters and column masks in the console, in a real browser (D20; Atelier, 2026-11-24; backlog 243): the grant
 * admin adds, edits and removes each group's rule on a table's Access tab, told what follows and refused in words; a
 * steward sees how many rules a table has and for which groups, never the expressions; a reader sees their own view
 * of the table; and the Governance page counts the flagged tables and lists them with their rules.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleRowColumnRulesBrowserTest {

    private static final String AUTH = "Authorization";
    private static final String FLAG = "row-column-policy";
    private static final String NOT_FLAGGED = "Rules need the flag. A steward sets it, so only trusted engines read"
            + " the files.";
    private static final String NO_RULES = "Flagged. No rules yet: Trino shows every row to anyone who may select;"
            + " other tools get no files.";
    private static final String TABLE = """
            {"name":"positions","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"},
              {"id":2,"name":"desk","required":false,"type":"string"},
              {"id":3,"name":"trader","required":false,"type":"string"},
              {"id":4,"name":"pnl","required":false,"type":"double"}]},"properties":{"format-version":"2"}}""";
    private static final String FILTER = "desk = 'EQ'";

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

    @BeforeAll
    void fixturesAndBrowser() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
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
    void theGrantAdminAddsEditsAndRemovesRulesAndIsToldWhatFollows() {
        String ns = positions();
        String analysts = "analysts-" + Api.suffix();
        String traders = "traders-" + Api.suffix();
        browser.signIn(api.grantAdmin());

        // Before the steward flags the table, rules wait for the flag.
        openAccess(ns);
        assertThat(textContent(browser.await(By.cssSelector("#row-column-rules [data-rules='not-flagged']"))))
                .isEqualTo(NOT_FLAGGED);
        assertThat(driver.findElements(addButton())).isEmpty();

        flag(ns, true);
        openAccess(ns);
        assertThat(textContent(browser.await(By.cssSelector("#row-column-rules [data-rules='none']")))).isEqualTo(NO_RULES);

        // The first rule: the dialog warns what a rule does to everyone else, and checks the fields before it sends.
        browser.await(addButton()).click();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-first-rule]"))))
                .isEqualTo("Groups without a rule will see no rows.");
        assertThat(textContent(browser.await(By.id("dlg-h")))).isEqualTo("Add a rule for a group");
        save();
        assertThat(hints()).containsExactly("Name the group.");
        dialog().findElement(By.name("group")).sendKeys(analysts);
        dialog().findElement(By.cssSelector("input[name='rows'][value='only']")).click();
        dialog().findElement(By.cssSelector("button[data-act='add-mask']")).click();
        save();
        assertThat(hints()).containsExactly("Write which rows the group sees.", "Choose a column on each line.");
        driver.executeScript("arguments[0].value = 'x'.repeat(4001);", dialog().findElement(By.name("filter")));
        save();
        assertThat(hints()).contains("The row filter is longer than 4,000 characters.");
        WebElement filter = dialog().findElement(By.name("filter"));
        filter.clear();
        filter.sendKeys(FILTER);
        // The columns come from the table's schema (backlog 242), though the console cannot load a flagged table.
        Select column = new Select(dialog().findElement(By.name("column")));
        assertThat(column.getOptions().stream().map(WebElement::getText).toList())
                .containsExactly("Choose a column", "id", "desk", "trader", "pnl");
        column.selectByValue("trader");
        new Select(dialog().findElement(By.name("mask"))).selectByValue("'***'");
        saveAndClose("Saved the rule for " + analysts);
        assertThat(rules(ns)).containsExactly(Map.of("group", analysts, "row-filter", FILTER,
                "column-masks", Map.of("trader", "'***'")));
        WebElement row = browser.await(ruleRow(analysts));
        assertThat(textContent(row.findElement(By.cssSelector("[data-rows]")))).isEqualTo("Only rows where " + FILTER);
        assertThat(textContent(row.findElement(By.cssSelector("[data-masks]")))).isEqualTo("trader '***'");
        assertThat(textContent(row)).contains("gina");

        // A second rule, masks only: every row; no first-rule warning.
        browser.await(addButton()).click();
        browser.await(By.cssSelector("dialog.dlg input[name='group']"));
        assertThat(dialog().findElements(By.cssSelector("[data-first-rule]"))).isEmpty();
        dialog().findElement(By.name("group")).sendKeys(traders);
        dialog().findElement(By.cssSelector("button[data-act='add-mask']")).click();
        new Select(dialog().findElement(By.name("column"))).selectByValue("pnl");
        saveAndClose("Saved the rule for " + traders);
        assertThat(rules(ns)).contains(Map.of("group", traders, "column-masks", Map.of("pnl", "NULL")));
        assertThat(textContent(browser.await(ruleRow(traders)).findElement(By.cssSelector("[data-rows]"))))
                .isEqualTo("Every row");

        // Removing one of two: that group sees no rows.
        browser.await(ruleRow(traders)).findElement(By.cssSelector("button[data-act='remove-rule']")).click();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-follows]"))))
                .isEqualTo(traders + " will see no rows.");
        saveAndClose("Removed the rule for " + traders);
        browser.await("the removed rule is still listed", d -> d.findElements(ruleRow(traders)).isEmpty());

        // Edit opens with the group fixed and the rule as it stands; it now lets every row through, trader masked.
        browser.await(ruleRow(analysts)).findElement(By.cssSelector("button[data-act='edit-rule']")).click();
        assertThat(textContent(browser.await(By.id("dlg-h")))).isEqualTo("Edit the rule for " + analysts);
        WebElement group = dialog().findElement(By.name("group"));
        assertThat(group.getDomProperty("value")).isEqualTo(analysts);
        assertThat(group.getDomProperty("readOnly")).isEqualTo("true");
        assertThat(dialog().findElement(By.name("filter")).getDomProperty("value")).isEqualTo(FILTER);
        assertThat(new Select(dialog().findElement(By.name("mask"))).getFirstSelectedOption().getDomAttribute("value"))
                .isEqualTo("'***'");
        dialog().findElement(By.cssSelector("input[name='rows'][value='every']")).click();
        new Select(dialog().findElement(By.name("mask"))).selectByValue("custom");
        dialog().findElement(By.name("custom")).sendKeys("substr(trader, 1, 1)");
        saveAndClose("Saved the rule for " + analysts);
        assertThat(rules(ns)).containsExactly(Map.of("group", analysts,
                "column-masks", Map.of("trader", "substr(trader, 1, 1)")));

        // A rule for the grant admin's own group is another grant admin's: read only here, and refused in words.
        String other = otherGrantAdmin(ns);
        expect(api.manage("POST", "/row-column-rules", rule(ns, GovernanceServer.GRANT_ADMINS), AUTH, other), 204);
        openAccess(ns);
        WebElement own = browser.await(ruleRow(GovernanceServer.GRANT_ADMINS));
        assertThat(textContent(own.findElement(By.cssSelector("[data-own]"))))
                .isEqualTo("Another grant admin sets this group's rule.");
        assertThat(own.findElements(By.tagName("button"))).isEmpty();
        browser.await(addButton()).click();
        browser.await(By.cssSelector("dialog.dlg input[name='group']")).sendKeys(GovernanceServer.GRANT_ADMINS);
        save();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-refused='own-group']"))))
                .isEqualTo("You belong to " + GovernanceServer.GRANT_ADMINS + ". Another grant admin sets this group's rule.");
        closeDialog();
        expect(api.manage("POST", "/row-column-rules/remove", rule(ns, GovernanceServer.GRANT_ADMINS), AUTH, other), 204);

        // Removing the last rule: everyone with select sees every row.
        openAccess(ns);
        browser.await(ruleRow(analysts)).findElement(By.cssSelector("button[data-act='remove-rule']")).click();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-follows]"))))
                .isEqualTo("Everyone with select will see every row through Trino.");
        saveAndClose("Removed the rule for " + analysts);
        assertThat(textContent(browser.await(By.cssSelector("#row-column-rules [data-rules='none']")))).isEqualTo(NO_RULES);
        assertThat(rules(ns)).isEmpty();

        // A steward removes the flag while the dialog is open: the catalog's refusal, in words.
        browser.await(addButton()).click();
        browser.await(By.cssSelector("dialog.dlg input[name='group']")).sendKeys(analysts);
        flag(ns, false);
        save();
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg [data-refused='not-flagged']"))))
                .isEqualTo(NOT_FLAGGED);
    }

    @Test
    void aStewardSeesHowManyRulesAndForWhichGroupsButNoExpression() {
        String ns = positions();
        flag(ns, true);
        String analysts = "analysts-" + Api.suffix();
        String traders = "traders-" + Api.suffix();
        expect(api.manage("POST", "/row-column-rules", rule(ns, analysts), AUTH, api.grantAdmin()), 204);
        expect(api.manage("POST", "/row-column-rules", rule(ns, traders), AUTH, api.grantAdmin()), 204);

        browser.signIn(api.steward());
        openAccess(ns);
        WebElement panel = browser.await(By.cssSelector("#row-column-rules .rule-counts"));
        assertThat(textContent(panel)).contains("Rules", "2", "Groups").contains(analysts, traders);
        String all = textContent(browser.await(By.id("access-tab")));
        assertThat(all).doesNotContain(FILTER, "'***'");
        assertThat(driver.findElements(addButton())).isEmpty();
        assertThat(driver.findElements(By.cssSelector("#row-column-rules button"))).isEmpty();
        assertThat(driver.findElements(By.id("your-view"))).isEmpty();
    }

    @Test
    void aReaderSeesTheirOwnViewOfAFlaggedTableAndNoExpression() {
        String ns = positions();
        browser.signIn(tester());

        // Not flagged: nothing to say.
        openAccess(ns);
        browser.await(By.cssSelector("#governance"));
        assertThat(driver.findElements(By.id("your-view"))).isEmpty();
        assertThat(driver.findElements(By.id("row-column-rules"))).isEmpty();

        flag(ns, true);
        expect(api.manage("POST", "/row-column-rules", rule(ns, IssuerForTests.GROUP), AUTH, api.grantAdmin()), 204);
        openAccess(ns);
        WebElement view = browser.await(By.id("your-view"));
        assertThat(textContent(view.findElement(By.tagName("h2")))).isEqualTo("Your view of this table");
        assertThat(textContent(view.findElement(By.tagName("p"))))
                .isEqualTo("Read through Trino only. Your rows are filtered. Masked for you: trader.");
        // It sits under What you may do, and neither it nor anything else on the tab shows an expression.
        assertThat((Boolean) driver.executeScript("return document.getElementById('you-may').compareDocumentPosition("
                + "document.getElementById('your-view')) === Node.DOCUMENT_POSITION_FOLLOWING;")).isTrue();
        assertThat(textContent(browser.await(By.id("access-tab")))).doesNotContain(FILTER, "'***'");
        assertThat(driver.findElements(By.id("row-column-rules"))).isEmpty();
    }

    @Test
    void theGovernancePageCountsFlaggedTablesAndListsThemWithTheirRules() {
        String ns = positions();
        flag(ns, true);
        expect(api.manage("POST", "/row-column-rules", rule(ns, "analysts-" + Api.suffix()), AUTH, api.grantAdmin()), 204);
        expect(api.manage("POST", "/row-column-rules", rule(ns, "traders-" + Api.suffix()), AUTH, api.grantAdmin()), 204);
        String summary = expect(api.manage("GET", "/governance/summary", null, AUTH, api.steward()), 200).body();
        int flagged = JsonPath.read(summary, "$.tables.flagged");
        assertThat(flagged).isPositive();

        browser.signIn(api.steward());
        browser.open("/ui/#p=governance&c=trading");
        WebElement kpi = browser.await(By.cssSelector("#governance-kpis [data-kpi='flagged']"));
        assertThat(textContent(kpi.findElement(By.cssSelector(".kpi-l")))).isEqualTo("Flagged tables");
        assertThat(textContent(kpi.findElement(By.cssSelector(".kpi-v")))).isEqualTo(String.valueOf(flagged));
        assertThat(textContent(browser.await(By.cssSelector("#flagged h2")))).isEqualTo("Flagged tables");
        WebElement row = browser.await(By.cssSelector("#flagged tr[data-on='" + ns + ".positions']"));
        assertThat(textContent(row.findElement(By.cssSelector("td.num")))).isEqualTo("2");
        assertThat(driver.findElements(By.cssSelector("#flagged tbody tr"))).hasSize(flagged);

        // A row opens the table's Access tab, where its rules are.
        row.findElement(By.cssSelector("td.name a")).click();
        browser.await(By.cssSelector("#row-column-rules .rule-counts"));
    }

    // ---- fixtures ----

    /** A new namespace holding a table {@code positions} with columns id, desk, trader and pnl. */
    private String positions() {
        String ns = api.namespace();
        expect(api.rest("POST", "/namespaces/" + Api.ns(ns) + "/tables", TABLE), 200);
        return ns;
    }

    /** Sets or removes the table's flag, as the steward. */
    private void flag(String ns, boolean on) {
        String path = "/namespaces/" + Api.ns(ns) + "/tables/positions/attributes/" + FLAG;
        expect(on ? api.manage("PUT", path, "{\"value\":\"true\",\"provenance\":\"test\"}", AUTH, api.steward())
                : api.manage("DELETE", path, null, AUTH, api.steward()), on ? 200 : 204);
    }

    /** A rule for {@code group} on the table: rows where desk is EQ, trader masked as '***'. */
    private static String rule(String ns, String group) {
        return "{\"table\":{\"type\":\"table\",\"namespace\":[\"" + ns + "\"],\"name\":\"positions\"},\"group\":\""
                + group + "\",\"row-filter\":\"" + FILTER + "\",\"column-masks\":{\"trader\":\"'***'\"}}";
    }

    /** The table's rules as the grant admin's list gives them: group, row-filter and column-masks. */
    private List<Map<String, Object>> rules(String ns) {
        String body = expect(api.manage("GET", "/row-column-rules?namespace=" + ns + "&name=positions", null, AUTH,
                api.grantAdmin()), 200).body();
        List<Map<String, Object>> rules = JsonPath.read(body, "$.rules");
        return rules.stream().map(r -> {
            Map<String, Object> out = new java.util.HashMap<>(r);
            out.keySet().retainAll(List.of("group", "row-filter", "column-masks"));
            return out;
        }).toList();
    }

    /**
     * A grant admin outside the deployment's grant-admin group: a group of its own holding manage-grants on the
     * namespace, so it may set a rule for the grant-admin group.
     */
    private String otherGrantAdmin(String ns) {
        String group = "rule-admins-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + ns
                + "\"]},\"group\":\"" + group + "\",\"privilege\":\"manage-grants\"}", AUTH, api.grantAdmin()), 204);
        return token("rhea", group);
    }

    /** The tester: create and modify on the catalog, so select. */
    private String tester() {
        return token("tester", IssuerForTests.GROUP);
    }

    private String token(String user, String group) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(user, "nuna-console", group)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    // ---- the page ----

    private void openAccess(String ns) {
        browser.open("/ui/#c=trading&k=table&ns=" + ns + "&n=positions&tab=access");
        browser.await(By.id("access-tab"));
    }

    private static By addButton() {
        return By.cssSelector("#row-column-rules button[data-act='add-rule']");
    }

    private static By ruleRow(String group) {
        return By.cssSelector("#row-column-rules tr[data-group='" + group + "']");
    }

    private WebElement dialog() {
        return browser.await(By.cssSelector("dialog.dlg form.dlg-form"));
    }

    private void save() {
        dialog().findElement(By.cssSelector("button[type='submit']")).click();
    }

    /** The hints the dialog gave instead of sending: it checks the form as the save is clicked, before any request. */
    private List<String> hints() {
        WebElement box = browser.await(By.cssSelector("dialog.dlg [data-hints]"));
        return box.findElements(By.tagName("p")).stream().map(ConsoleBrowser::textContent).toList();
    }

    /** Saves the dialog, waits for the catalog's answer naming {@code done}, and closes it. */
    private void saveAndClose(String done) {
        save();
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), done);
        closeDialog();
    }

    private void closeDialog() {
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog is still open", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
    }
}
