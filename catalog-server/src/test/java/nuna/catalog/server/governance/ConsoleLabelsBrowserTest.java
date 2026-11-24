package nuna.catalog.server.governance;

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
 * The four labels set from the console's governance tab, in a real browser (D27, amended 2026-11-22; item 140): the
 * owner, a retention class chosen from those the server is configured with, the regulated mark on a namespace and the
 * classification, each recorded with provenance {@code console} under the steward's name; the regulated mark closing
 * an unclassified table and its classification opening it; the mark cleared; a table offering no regulated mark and the
 * catalog all four; an empty owner never sent; and a caller without {@code manage-tags} shown the catalog's refusal.
 * A view's Governance tab is {@link ConsoleViewGovernanceBrowserTest}'s (item 146).
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleLabelsBrowserTest {

    private static final String AUTH = "Authorization";

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
    void aStewardSetsTheOwnerARetentionClassTheRegulatedMarkAndTheClassificationFromTheConsole() {
        String ns = api.namespace();
        api.table(ns, "trades");
        browser.signIn(steward);

        // The owner, on the namespace.
        browser.open("/ui/#" + at("namespace", ns, null));
        String asked = dialog("owner");
        assertThat(asked).contains("person or team answerable", "Everything beneath this namespace inherits it",
                "recorded as set from the console");
        WebElement owner = driver.findElement(By.cssSelector("dialog.dlg input[name='owner']"));
        owner.sendKeys("desk-a");
        submit("is owned by desk-a");
        awaitFact("Owner", "desk-a · this namespace · console");

        // A retention class, chosen from the one the server is configured with.
        assertThat(dialog("retention-class")).contains("no commit and no maintenance job may remove a snapshot");
        Select classes = new Select(browser.await(By.cssSelector("dialog.dlg select[name='rc']")));
        assertThat(classes.getOptions()).extracting(WebElement::getText)
                .containsExactly("trade-records · snapshots kept at least 30 days");
        submit("is in retention class trade-records");
        awaitFact("Retention class", "trade-records · this namespace · console");

        // The regulated mark closes the unclassified table beneath it.
        assertThat(button("regulated").getText()).isEqualTo("Mark as a regulated area");
        assertThat(dialog("regulated")).contains("closes every table beneath this namespace");
        submit("is a regulated area");
        awaitFact("Regulated area", "true · this namespace · console");
        browser.awaitText(labelButton("regulated"), "Clear the regulated mark");
        expect(api.rest("GET", "/namespaces/" + Api.ns(ns) + "/tables/trades", null), 403);

        Map<String, Map<String, String>> recorded = own("/namespaces/" + Api.ns(ns));
        for (String label : List.of("owner", "retention-class", "regulated")) {
            assertThat(recorded.get(label)).as(label).containsEntry("provenance", "console")
                    .containsEntry("set-by", "stella");
        }
        assertThat(recorded.get("owner")).containsEntry("value", "desk-a");
        assertThat(recorded.get("retention-class")).containsEntry("value", "trade-records");
        assertThat(recorded.get("regulated")).containsEntry("value", "true");

        // The table inherits them, offers no regulated mark, and opens once it is classified.
        browser.open("/ui/#" + at("table", ns, "trades"));
        awaitFact("Owner", "desk-a · inherited from namespace " + ns + " · console");
        assertThat(button("retention-class").getText()).isEqualTo("Change the retention class");
        assertThat(driver.findElements(labelButton("regulated"))).isEmpty();
        dialog("classification");
        new Select(browser.await(By.cssSelector("dialog.dlg select[name='cls']"))).selectByValue("internal");
        submit("is internal");
        awaitFact("Classification", "internal · this table · console");
        expect(api.rest("GET", "/namespaces/" + Api.ns(ns) + "/tables/trades", null), 200);
        assertThat(own("/namespaces/" + Api.ns(ns) + "/tables/trades").get("classification"))
                .containsEntry("value", "internal").containsEntry("provenance", "console");

        // Clearing the mark records it as false, set from the console.
        browser.open("/ui/#" + at("namespace", ns, null));
        assertThat(dialog("regulated")).contains("Clearing the mark opens the tables beneath this namespace");
        submit("is no longer a regulated area");
        awaitFact("Regulated area", "false · this namespace · console");
        browser.awaitText(labelButton("regulated"), "Mark as a regulated area");
        assertThat(own("/namespaces/" + Api.ns(ns)).get("regulated")).containsEntry("value", "false")
                .containsEntry("provenance", "console");
    }

    @Test
    void theCatalogOffersAllFourLabels() {
        browser.signIn(steward);

        browser.open("/ui/#p=governance&c=trading&tab=holds");
        browser.await(labelButton("regulated"));
        assertThat(driver.findElements(By.cssSelector("#governance [data-acts='labels'] button")))
                .extracting(b -> b.getDomAttribute("data-label"))
                .containsExactly("classification", "owner", "retention-class", "regulated");
    }

    @Test
    void anEmptyOwnerIsNotSent() {
        String ns = api.namespace();
        browser.signIn(steward);
        browser.open("/ui/#" + at("namespace", ns, null));

        dialog("owner");
        driver.findElement(By.cssSelector("dialog.dlg input[name='owner']")).sendKeys("   ");
        driver.findElement(By.cssSelector("dialog.dlg button[type='submit']")).click();

        browser.awaitText(By.cssSelector("dialog.dlg .grant-hint"), "Name the person or team.");
        assertThat(own("/namespaces/" + Api.ns(ns))).doesNotContainKey("owner");
    }

    /**
     * A caller whose groups hold no manage-tags in the catalog is offered no label and no hold (backlog 184), which
     * the catalog would refuse them.
     */
    @Test
    void aCallerWithoutManageTagsIsOfferedNoLabelOrHold() {
        String ns = api.namespace();
        browser.signIn(tester());
        browser.open("/ui/#" + at("namespace", ns, null));
        browser.await(By.cssSelector("#governance .status-row"));
        assertThat(driver.findElements(By.cssSelector("#governance [data-acts='labels']"))).isEmpty();
        assertThat(driver.findElements(By.xpath("//div[@id='governance']//button[starts-with(normalize-space(),"
                + "'Place a hold')]"))).isEmpty();
        HttpResponse<String> refused = api.send("PUT", "/management/v1/catalogs/trading/namespaces/" + Api.ns(ns)
                + "/attributes/owner", "{\"value\":\"desk-b\",\"provenance\":\"console\"}", "Authorization", tester());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(own("/namespaces/" + Api.ns(ns))).doesNotContainKey("owner");
    }

    /** The tester: create and modify on the catalog, and no manage-tags. */
    private String tester() {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims("tester", "nuna-console", IssuerForTests.GROUP)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    // ---- the page ----

    /** The governance tab of a namespace, or of a table in the one-level namespace {@code ns}. */
    private static String at(String kind, String ns, String name) {
        return "c=trading&k=" + kind + "&ns=" + ns + (name == null ? "" : "&n=" + name) + "&tab=governance";
    }

    private static By labelButton(String label) {
        return By.cssSelector("#governance [data-acts='labels'] button[data-label='" + label + "']");
    }

    private WebElement button(String label) {
        return browser.await(labelButton(label));
    }

    /** Opens the dialog of {@code label} and returns its words. */
    private String dialog(String label) {
        button(label).click();
        return browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText();
    }

    /** Submits the open dialog, waits for the catalog's answer naming {@code done}, and closes it. */
    private void submit(String done) {
        browser.await(By.cssSelector("dialog.dlg button[type='submit']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), done);
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog is still open", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
    }

    /** Waits until the governance tab's effective {@code label} reads {@code text}, as the tab redraws. */
    private void awaitFact(String label, String text) {
        By value = By.xpath("//div[@id='governance']//dl[contains(@class,'effective')]/dt[normalize-space()='"
                + label + "']/following-sibling::dd[1]");
        browser.await("the governance tab's " + label + " is not " + text, d -> d.findElements(value).stream()
                .anyMatch(dd -> dd.getText().replaceAll("\\s+", " ").trim().equals(text)));
    }

    // ---- what the catalog recorded ----

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
