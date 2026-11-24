package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.server.ConsoleBrowser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
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
 * A view's Governance tab in a real browser (item 146): the labels a view carries, each with where it was set; its
 * classification and owner set from the console, recorded with provenance {@code console} under the steward's name
 * and carried by the view's load; no retention class or regulated mark offered, since neither does anything to a
 * view; and a legal hold placed from the tab refusing the view's drop until it is released there, in words that speak
 * of no snapshots.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleViewGovernanceBrowserTest {

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
    void aStewardSetsAViewsClassificationAndOwnerFromItsGovernanceTab() {
        String ns = api.namespace();
        api.view(ns, "daily");
        String view = "/namespaces/" + Api.ns(ns) + "/views/daily";
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ns) + "/attributes/classification",
                "{\"value\":\"confidential\",\"provenance\":\"api-test\"}", AUTH, steward), 200);
        browser.signIn(steward);

        browser.open("/ui/#" + at(ns, "daily"));
        awaitFact("Classification", "confidential · inherited from namespace " + ns + " · api-test");
        assertThat(driver.findElements(By.cssSelector("#governance [data-acts='labels'] button")))
                .extracting(b -> b.getDomAttribute("data-label"))
                .containsExactly("classification", "owner");
        assertThat(page()).doesNotContain("snapshot", "freeze for dr");
        assertThat(driver.findElement(By.id("view-page")).getText()).contains("Replacing the definition");

        String asked = dialog("classification");
        assertThat(asked).contains("Engines receive it with every load", "recorded as set from the console")
                .doesNotContain("regulated", "Everything beneath");
        new Select(browser.await(By.cssSelector("dialog.dlg select[name='cls']"))).selectByValue("internal");
        submit("is internal");
        awaitFact("Classification", "internal · this view · console");

        assertThat(dialog("owner")).contains("answerable for this view").doesNotContain("Everything beneath");
        driver.findElement(By.cssSelector("dialog.dlg input[name='owner']")).sendKeys("desk-v");
        submit("is owned by desk-v");
        awaitFact("Owner", "desk-v · this view · console");

        Map<String, Map<String, String>> recorded = own(view);
        for (String label : List.of("classification", "owner")) {
            assertThat(recorded.get(label)).as(label).containsEntry("provenance", "console")
                    .containsEntry("set-by", "stella");
        }
        assertThat(JsonPath.<Map<String, Object>>read(expect(api.rest("GET", view, null), 200).body(),
                "$.labels.object-labels")).isEqualTo(Map.of("classification", "internal", "owner", "desk-v"));
    }

    @Test
    void aHoldPlacedOnAViewFromItsTabRefusesItsDropUntilReleased() {
        String ns = api.namespace();
        api.view(ns, "held");
        String view = "/namespaces/" + Api.ns(ns) + "/views/held";
        browser.signIn(steward);
        browser.open("/ui/#" + at(ns, "held"));

        browser.await(By.xpath("//div[@id='governance']//button[normalize-space()='Place a hold']")).click();
        String asked = browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText();
        assertThat(asked).contains("stops this view being dropped or purged", "Its definition can still be replaced");
        assertThat(asked.toLowerCase(Locale.ROOT)).doesNotContain("snapshot");
        driver.findElement(By.cssSelector("dialog.dlg input[name='reason']")).sendKeys("Matter 2026-146");
        submit("Held: ");

        WebElement held = browser.await(By.cssSelector("#governance .status-held"));
        assertThat(held.getText()).startsWith("1 legal hold").contains("It cannot be dropped or purged.");
        assertThat(page()).doesNotContain("snapshot");
        expect(api.rest("DELETE", view, null), 403);

        browser.await(By.cssSelector("tr[data-hold] button")).click();
        assertThat(browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText())
                .contains("Matter 2026-146", "lets the view be dropped and purged again");
        submit("Released");
        browser.awaitText(By.cssSelector("#governance .status-row"), "No legal hold");
        expect(api.rest("DELETE", view, null), 204);
    }

    // ---- the page ----

    /** The governance tab of view {@code name} in the one-level namespace {@code ns}. */
    private static String at(String ns, String name) {
        return "c=trading&k=view&ns=" + ns + "&n=" + name + "&tab=governance";
    }

    /** The view page's words, in lower case. */
    private String page() {
        return driver.findElement(By.id("view-page")).getText().toLowerCase(Locale.ROOT);
    }

    /** Opens the dialog of {@code label} and returns its words. */
    private String dialog(String label) {
        browser.await(By.cssSelector("#governance [data-acts='labels'] button[data-label='" + label + "']")).click();
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
