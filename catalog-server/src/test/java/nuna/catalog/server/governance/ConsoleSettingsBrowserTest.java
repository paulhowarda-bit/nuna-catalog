package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventType;
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
 * The console's settings editor in a real browser (D45, amended 2026-11-22; item 141): a listed table setting changed
 * and reset through its editor, as an audited commit, and read back from the table's metadata; a value outside the
 * allowed ones refused in the dialog and never sent; the catalog's refusal of a caller without {@code modify} shown
 * with its request id, audited as a denial; a property an engine or tool set shown read-only with where it came from;
 * a namespace's comment set, its owner taken from governance rather than its Iceberg properties; and no free-form key
 * entry anywhere in the console.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleSettingsBrowserTest {

    private static final String AUTH = "Authorization";

    /** Every setting the console offers on a table, in the order it shows them. */
    private static final List<String> TABLE_SETTINGS = List.of("write.format.default",
            "write.parquet.compression-codec", "write.parquet.compression-level", "write.target-file-size-bytes",
            "write.distribution-mode", "write.delete.mode", "write.update.mode", "write.merge.mode",
            "write.metadata.metrics.default", "write.metadata.previous-versions-max");

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
    void aListedSettingIsChangedAndResetThroughItsEditorAsAnAuditedCommit() {
        String ns = api.namespace();
        api.table(ns, "orders");
        browser.signIn(tester());
        browser.open("/ui/#" + at("table", ns, "orders"));
        // Iceberg writes the codec into every table it creates, so a new table shows it as set.
        awaitValue("write.parquet.compression-codec", "zstd · set");

        String asked = change("write.parquet.compression-codec");
        assertThat(asked).contains("How engines compress the Parquet files they write");
        new Select(browser.await(By.cssSelector("dialog.dlg select[name='value']"))).selectByValue("gzip");
        String requestId = submit("write.parquet.compression-codec is gzip");
        assertThat(tableProperties(ns, "orders")).containsEntry("write.parquet.compression-codec", "gzip");
        // The commit also vends credentials under the same request id, and that record comes first.
        CatalogEvent commit = api.awaitRecord(json -> json.contains("\"" + requestId + "\"")
                && json.contains("\"type\":\"" + EventType.COMMIT.typeName() + "\""));
        assertThat(commit.data().type()).isEqualTo(EventType.COMMIT);
        awaitValue("write.parquet.compression-codec", "gzip · set");

        // A size is typed in words and sent as bytes.
        change("write.target-file-size-bytes");
        WebElement size = driver.findElement(By.cssSelector("dialog.dlg input[name='value']"));
        size.clear();
        size.sendKeys("256 MB");
        submit("write.target-file-size-bytes is 268435456");
        assertThat(tableProperties(ns, "orders")).containsEntry("write.target-file-size-bytes", "268435456");
        awaitValue("write.target-file-size-bytes", "256 MB (268,435,456 bytes) · set");

        // Reset removes the property, and the default applies again.
        setting("write.parquet.compression-codec").findElement(By.cssSelector("button[data-act='reset']")).click();
        submit("write.parquet.compression-codec is reset");
        assertThat(tableProperties(ns, "orders")).doesNotContainKey("write.parquet.compression-codec")
                .containsEntry("write.target-file-size-bytes", "268435456");
        awaitValue("write.parquet.compression-codec", "zstd · default");
    }

    @Test
    void aValueOutsideTheAllowedOnesIsRefusedInTheDialogAndNeverSent() {
        String ns = api.namespace();
        api.table(ns, "orders");
        browser.signIn(tester());
        browser.open("/ui/#" + at("table", ns, "orders"));

        change("write.target-file-size-bytes");
        typeAndSubmit("3 TB");
        browser.awaitText(By.cssSelector("dialog.dlg .grant-hint"), "A size from 16 MB to 4.0 GB");
        closeDialog();

        change("write.metadata.previous-versions-max");
        typeAndSubmit("0");
        browser.awaitText(By.cssSelector("dialog.dlg .grant-hint"), "A whole number from 1 to 10,000.");
        assertThat(driver.findElements(By.cssSelector("dialog.dlg .granted, dialog.dlg .refusal"))).isEmpty();
        closeDialog();

        assertThat(tableProperties(ns, "orders")).doesNotContainKeys("write.target-file-size-bytes",
                "write.metadata.previous-versions-max");
    }

    @Test
    void theCatalogsRefusalIsShownWithItsRequestId() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String readers = "readers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + ns
                + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        browser.signIn(token("rita", readers));
        browser.open("/ui/#" + at("table", ns, "orders"));
        change("write.format.default");
        new Select(browser.await(By.cssSelector("dialog.dlg select[name='value']"))).selectByValue("avro");
        driver.findElement(By.cssSelector("dialog.dlg button[type='submit']")).click();

        WebElement refusal = browser.await(By.cssSelector("dialog.dlg .refusal"));
        assertThat(refusal.getText()).contains("Refused by the catalog", "holds no privilege that gives modify",
                "The refusal is recorded in the audit.").doesNotContain("X-Request-ID");
        // Its status and request id are behind Technical details (Atelier's review, F10; backlog 188).
        refusal.findElement(By.cssSelector("details.tech > summary")).click();
        assertThat(refusal.getText()).contains("403", "audited as a denial");
        String requestId = refusal.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid");
        assertThat(requestId).isNotBlank();
        assertThat(refusal.getText()).contains("X-Request-ID " + requestId);
        assertThat(api.awaitRequest(requestId).data().type()).isEqualTo(EventType.DENIAL);
        assertThat(tableProperties(ns, "orders")).doesNotContainKey("write.format.default");
    }

    @Test
    void aPropertySetByAToolIsShownReadOnlyWithWhereItCameFrom() {
        String ns = api.namespace();
        api.table(ns, "orders");
        expect(api.rest("POST", "/namespaces/" + Api.ns(ns) + "/tables/orders", """
                {"requirements":[],"updates":[{"action":"set-properties","updates":{"made-by":"nightly-load",
                 "write.metadata.delete-after-commit.enabled":"true","history.expire.max-snapshot-age-ms":"86400000",
                 "write.format.default":"orc"}}]}"""), 200);
        browser.signIn(tester());
        browser.open("/ui/#" + at("table", ns, "orders"));

        WebElement tool = property("made-by");
        assertThat(tool.getText()).contains("nightly-load", "Set outside the console, by an engine or tool. Read-only here.");
        assertThat(tool.findElements(By.cssSelector("button, input, select"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector("#settings tr[data-setting='made-by']"))).isEmpty();
        assertThat(property("write.metadata.delete-after-commit.enabled").getText()).contains("Changes nothing here");
        assertThat(property("history.expire.max-snapshot-age-ms").findElement(By.tagName("a")).getDomAttribute("href"))
                .contains("tab=maintenance");
        assertThat(driver.findElements(By.cssSelector("#all-properties button"))).isEmpty();

        // A listed key a tool set is a setting like any other.
        assertThat(property("write.format.default").getText()).contains("A setting: File format of new data, above.");
        awaitValue("write.format.default", "orc · set");
    }

    @Test
    void noKeyIsTypedAnywhereAndANamespaceOffersOnlyItsComment() {
        String ns = api.namespace();
        api.table(ns, "orders");
        expect(api.rest("POST", "/namespaces/" + Api.ns(ns) + "/properties",
                "{\"updates\":{\"location\":\"s3://elsewhere/" + ns + "\",\"owner\":\"someone-else\"},\"removals\":[]}"), 200);
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ns) + "/attributes/owner",
                "{\"value\":\"desk-a\",\"provenance\":\"console test\"}", AUTH, api.steward()), 200);
        browser.signIn(tester());

        // The subtitle's owner is the governance owner, not the Iceberg property.
        browser.open("/ui/#c=trading&k=namespace&ns=" + ns);
        browser.awaitText(By.cssSelector("#namespace-page [data-owner]"), "desk-a");
        noFreeFormEntry();

        // The namespace's one setting is its comment; its location and owner properties are read-only, and say why.
        browser.open("/ui/#c=trading&k=namespace&ns=" + ns + "&tab=properties");
        browser.await(By.id("all-properties"));
        assertThat(driver.findElements(By.cssSelector("#settings tr[data-setting]")))
                .extracting(r -> r.getDomAttribute("data-setting")).containsExactly("comment");
        assertThat(property("location").getText()).contains("Changes nothing");
        assertThat(property("owner").getText()).contains("governance owner");
        noFreeFormEntry();
        change("comment");
        typeAndSubmit("Orders as booked");
        browser.awaitText(By.cssSelector("dialog.dlg .granted"), "comment is Orders as booked");
        closeDialog();
        String namespace = expect(api.rest("GET", "/namespaces/" + Api.ns(ns), null), 200).body();
        assertThat(JsonPath.<Map<String, String>>read(namespace, "$.properties"))
                .containsEntry("comment", "Orders as booked").containsEntry("location", "s3://elsewhere/" + ns);

        // A table offers exactly the listed settings.
        browser.open("/ui/#" + at("table", ns, "orders"));
        browser.await(By.id("all-properties"));
        assertThat(driver.findElements(By.cssSelector("#settings tr[data-setting]")))
                .extracting(r -> r.getDomAttribute("data-setting")).containsExactlyElementsOf(TABLE_SETTINGS);
        noFreeFormEntry();

        // The catalog's properties stay read-only and point at its maintenance policy.
        browser.open("/ui/#c=trading&tab=properties");
        browser.await(By.id("catalog-properties"));
        assertThat(browser.await(By.cssSelector("a[data-act='policy']")).getDomAttribute("href")).contains("p=maintenance");
        assertThat(driver.findElements(By.cssSelector("#settings, #all-properties"))).isEmpty();
        noFreeFormEntry();
    }

    /** No page offers a key to type: no "Set a property", and no field named for a key. */
    private void noFreeFormEntry() {
        assertThat(driver.findElements(By.xpath("//button[contains(normalize-space(), 'Set a property')]"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector("input[name='key']"))).isEmpty();
    }

    /** The tester: create and modify on the catalog. */
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

    /** The properties tab of a table in the one-level namespace {@code ns}. */
    private static String at(String kind, String ns, String name) {
        return "c=trading&k=" + kind + "&ns=" + ns + "&n=" + name + "&tab=properties";
    }

    private WebElement setting(String key) {
        return browser.await(By.cssSelector("#settings tr[data-setting='" + key + "']"));
    }

    private WebElement property(String key) {
        return browser.await(By.cssSelector("#all-properties tr[data-key='" + key + "']"));
    }

    /** Opens the editor of {@code key} and returns its words. */
    private String change(String key) {
        setting(key).findElement(By.cssSelector("button[data-act='change']")).click();
        return browser.await(By.cssSelector("dialog.dlg .dlg-body")).getText();
    }

    private void typeAndSubmit(String value) {
        WebElement input = browser.await(By.cssSelector("dialog.dlg input[name='value']"));
        input.clear();
        input.sendKeys(value);
        driver.findElement(By.cssSelector("dialog.dlg button[type='submit']")).click();
    }

    /** Submits the open dialog, waits for the catalog's answer naming {@code done}, closes it; returns its request id. */
    private String submit(String done) {
        browser.await(By.cssSelector("dialog.dlg button[type='submit']")).click();
        WebElement granted = browser.awaitText(By.cssSelector("dialog.dlg .granted"), done);
        String requestId = granted.findElement(By.cssSelector("[data-rid]")).getDomAttribute("data-rid");
        closeDialog();
        return requestId;
    }

    private void closeDialog() {
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog is still open", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty());
    }

    /** Waits until the value shown for setting {@code key} reads {@code text}, as the tab redraws. */
    private void awaitValue(String key, String text) {
        By value = By.cssSelector("#settings tr[data-setting='" + key + "'] td.setting-v");
        browser.await("the value of " + key + " is not " + text, d -> d.findElements(value).stream()
                .anyMatch(td -> td.getText().replaceAll("\\s+", " ").trim().equals(text)));
    }

    // ---- what the catalog holds ----

    private Map<String, String> tableProperties(String ns, String name) {
        String table = expect(api.rest("GET", "/namespaces/" + Api.ns(ns) + "/tables/" + name, null), 200).body();
        return JsonPath.read(table, "$.metadata.properties");
    }
}
