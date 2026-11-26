package nuna.catalog.server.governance;

import static nuna.catalog.server.ConsoleBrowser.textContent;
import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
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
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Maintenance's "What the worker needs to maintain trading" and its page head (backlog 252, Atelier's design), on a
 * catalog the worker can't maintain: its group holds no grant there, it reports no storage that covers the catalog's,
 * and the catalog's policy turns every job off. As the demo's three kinds of caller, from item 251's readiness:
 *
 * <ul>
 *   <li>{@code ana}, who may modify the catalog: the names, "Ask a grant admin.", a note for an operator to copy, and
 *       the catalog's policy to edit.</li>
 *   <li>{@code gary}, a grant admin: "Grant write to" the worker's group, which opens item 248's Grant access dialog
 *       filled in; once granted, the step is done.</li>
 *   <li>{@code oscar}, a platform operator holding no grant: the steps outside the catalog open his dialog for the
 *       catalog's profile (item 254), and the steps an operator can't do say whom to ask, so nothing he is
 *       offered draws an audited refusal (catalog-security, 2026-11-24).</li>
 * </ul>
 *
 * <p>Also a tag steward, who may see no names: "the worker" and "trading's storage" in their place. Before any worker
 * reports, the first three steps are not known; after a scan that did not report its access (item 277), they are still
 * not known, and say that scan's time in place of "No maintenance worker has reported".
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleReadinessBrowserTest {

    private static final String NOT_KNOWN = "No maintenance worker has reported, so the catalog can't check this.";
    private static final String ASK_NAMES = "Ask someone with modify on trading or a platform operator.";
    /** Steps 1 to 3 after a scan that did not report its access (item 277), at a time the console writes. */
    private static final String UNREAD = "The worker's last scan, at (\\d{1,2} [A-Z][a-z]{2}( \\d{4})? )?\\d\\d:\\d\\d, did "
            + "not report its access, so the catalog can't check this until a scan does\\.";

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
    /** The worker's group in each test, which holds nothing in the catalog until gary grants it write. */
    private String workers;
    private Map<String, Object> policyBefore;
    private final String allowed = "s3://" + S3ForTests.bucket() + "/tables";

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
    void aCatalogWithNoGrantAndNoStorage() {
        driver = browser.session();
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
    void anaSeesWhatIsMissingWithNamesAndCopiesANoteForAnOperator() {
        reportNoStorage();
        String ana = token("ana", IssuerForTests.GROUP);
        browser.signIn(ana);
        browser.open("/ui/#p=maintenance&c=trading");

        WebElement panel = browser.await(By.id("readiness"));
        assertThat(textContent(panel.findElement(By.cssSelector("h2"))))
                .startsWith("What the worker needs to maintain trading 0 of 4 done · as of its scan at ");
        assertStep("grant", "Missing", workers + ", the worker's group, holds no write on trading, so the worker can't "
                + "read or commit its tables.", "Who: a grant admin.");
        assertThat(step("grant").findElement(By.cssSelector(".step-ask")).getText()).isEqualTo("Ask a grant admin.");
        assertStep("storage", "Missing", noIdentity() + " Until it does, files that expiry and compaction replace are "
                + "kept, and no orphan is found.", "Who: a platform operator, in the worker's settings.");
        assertStep("store", "Waits on step 2", "The worker checks this at its first scan once step 2 is done.",
                "Who: a platform operator, in the store.");
        assertStep("policy", "Missing", "Every job is off in trading's policy.", "Who: someone with modify on trading.");
        assertThat(panel.findElement(By.cssSelector(".step-foot")).getText()).isEqualTo("The catalog can't change the "
                + "worker's settings or the store's policies: the worker's keys stay out of the catalog, and a catalog "
                + "that could set store policies could undo the one that keeps delete from it.");
        assertThat(panel.findElements(By.cssSelector("[data-act='grant-write'], [data-act='what-to-add'], "
                + "[data-act='what-to-apply']"))).as("a grant admin's and an operator's actions").isEmpty();

        // The head: the steps left, in place of what the queue would say, unless a table she may read is queued.
        if (queued(ana).isEmpty() && (estate(ana).isEmpty() || estate(ana).stream().anyMatch(t -> t.get("assessment")
                == null))) {
            assertThat(head()).isEqualTo("The worker can't maintain trading yet: 4 of 4 steps left.");
        }

        // The note for an operator, copied: the button says so, and whom to send it to.
        WebElement copy = step("storage").findElement(By.cssSelector("[data-act='copy-note']"));
        assertThat(copy.getText()).isEqualTo("Copy a note for an operator");
        assertThat(copy.getDomAttribute("data-note")).isEqualTo("The maintenance worker can't delete old files in "
                + "catalog trading. Its tables are in " + allowed + ". " + noIdentity()
                + " What to apply is on the console's Service page, under Worker storage: " + browser.origin()
                + "/ui/#p=service&tab=worker-storage&g=" + S3ForTests.PROFILE);
        copy.click();
        browser.await("the note was not copied", d -> copy.getText().equals("Copied") ? true : null);
        assertThat(step("storage").findElement(By.cssSelector(".step-sent")).getText())
                .isEqualTo("Send it to a platform operator.");

        // She may modify the catalog: its policy opens from the step.
        step("policy").findElement(By.cssSelector("[data-act='edit-policy']")).click();
        browser.awaitText(By.cssSelector("dialog.dlg #dlg-h"), "Policy for trading");
    }

    @Test
    void garyGrantsWriteToTheWorkersGroupFromItsDialogFilledIn() {
        reportNoStorage();
        browser.signIn(token("gary", GovernanceServer.GRANT_ADMINS));
        browser.open("/ui/#p=maintenance&c=trading");

        // He reads no table, so the head says the steps left in place of "Nothing in trading you may read."
        assertThat(head()).isEqualTo("The worker can't maintain trading yet: 4 of 4 steps left.");
        assertThat(step("policy").findElement(By.cssSelector(".step-ask")).getText())
                .isEqualTo("Ask someone with modify on trading.");
        assertThat(step("policy").findElements(By.cssSelector("[data-act='edit-policy']"))).isEmpty();
        assertThat(step("storage").findElements(By.cssSelector("[data-act='copy-note']"))).hasSize(1);

        WebElement grant = step("grant").findElement(By.cssSelector("[data-act='grant-write']"));
        assertThat(grant.getText()).isEqualTo("Grant write to " + workers);
        grant.click();
        WebElement dialog = browser.await(By.cssSelector("dialog.dlg form.grant-dlg"));
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg #dlg-h")))).isEqualTo("Grant access on trading");
        assertThat(dialog.findElement(By.cssSelector("input[aria-label='Group']")).getDomProperty("value"))
                .isEqualTo(workers);
        assertThat(dialog.findElement(By.cssSelector("input[name='pv'][value='write']")).isSelected()).isTrue();
        browser.await("the live line did not admit the grant", d -> "admitted".equals(d.findElement(
                By.cssSelector("dialog.dlg .grant-line")).getDomAttribute("data-answer")) ? true : null);
        dialog.findElement(By.cssSelector("button[type='submit']")).click();
        assertThat(browser.await(By.cssSelector("dialog.dlg #grant-result .granted")).getText())
                .contains("Granted write on trading to " + workers + ".");
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();

        // The page draws again: the grant step reads the grants as they are now.
        browser.await("the grant step is not done", d -> "done".equals(step("grant").getDomAttribute("data-state"))
                ? true : null);
        assertThat(step("grant").findElement(By.cssSelector(".step-line")).getText())
                .isEqualTo(workers + ", the worker's group, holds write on trading.");
        assertThat(step("grant").findElements(By.cssSelector("[data-act='grant-write']"))).isEmpty();
        assertThat(head()).isEqualTo("The worker can't maintain trading yet: 3 of 4 steps left.");
    }

    @Test
    void oscarOpensTheStorageDialogForTheStoreStepsAndIsAskedForTheRest() {
        browser.signIn(token("oscar", GovernanceServer.PLATFORM_OPS));

        // Before any worker reports, the first three steps are not known, and none offers an action.
        browser.open("/ui/#p=maintenance&c=trading");
        WebElement panel = browser.await(By.id("readiness"));
        assertThat(textContent(panel.findElement(By.cssSelector("h2"))))
                .isEqualTo("What the worker needs to maintain trading 0 of 4 done");
        for (String step : List.of("grant", "storage", "store")) {
            assertThat(step(step).findElement(By.cssSelector(".pill")).getText()).as(step).isEqualTo("Not known");
            assertThat(step(step).findElement(By.cssSelector(".step-line")).getText()).as(step).isEqualTo(NOT_KNOWN);
            assertThat(step(step).findElements(By.cssSelector(".step-act"))).as(step).isEmpty();
        }
        assertThat(head()).isEqualTo("Nothing in trading you may read.");

        // A scan that could not read its storage prefixes, as just after a restart (items 275, 277): the steps are still
        // not known, but a worker did report, and when.
        reportUnreadPrefixes();
        browser.open("/ui/#p=maintenance&c=trading");
        browser.await("the scan without storage is not shown", d -> step("grant").findElement(
                By.cssSelector(".step-line")).getText().matches(UNREAD) ? true : null);
        for (String step : List.of("grant", "storage", "store")) {
            assertThat(step(step).findElement(By.cssSelector(".pill")).getText()).as(step).isEqualTo("Not known");
            assertThat(step(step).findElement(By.cssSelector(".step-line")).getText()).as(step).matches(UNREAD);
            assertThat(step(step).findElements(By.cssSelector(".step-act"))).as(step).isEmpty();
        }
        assertThat(textContent(browser.await(By.id("readiness")).findElement(By.cssSelector("h2"))))
                .isEqualTo("What the worker needs to maintain trading 0 of 4 done");
        assertThat(head()).isEqualTo("Nothing in trading you may read.");

        reportNoStorage();
        browser.open("/ui/#p=maintenance&c=trading");
        browser.await("the worker's report is not shown", d -> "missing".equals(step("grant").getDomAttribute(
                "data-state")) ? true : null);
        assertThat(head()).isEqualTo("The worker can't maintain trading yet: 4 of 4 steps left.");
        assertStep("grant", "Missing", workers + ", the worker's group, holds no write on trading, so the worker can't "
                + "read or commit its tables.", "Who: a grant admin.");
        // He holds no grant: neither a grant nor the policy is offered, since the catalog would refuse and audit both.
        assertThat(step("grant").findElement(By.cssSelector(".step-ask")).getText()).isEqualTo("Ask a grant admin.");
        assertThat(step("policy").findElement(By.cssSelector(".step-ask")).getText())
                .isEqualTo("Ask someone with modify on trading.");
        // The steps outside the catalog open his dialog for trading's profile (backlog 254).
        WebElement add = step("storage").findElement(By.cssSelector("button[data-act='what-to-add']"));
        assertThat(add.getText()).isEqualTo("What to add");
        WebElement apply = step("store").findElement(By.cssSelector("button[data-act='what-to-apply']"));
        assertThat(apply.getText()).isEqualTo("What to apply");
        assertThat(driver.findElements(By.cssSelector("#readiness [data-act='grant-write'], #readiness "
                + "[data-act='edit-policy'], #readiness [data-act='copy-note']"))).isEmpty();
        apply.click();
        browser.awaitText(By.cssSelector("dialog.dlg #dlg-h"), "Set up the worker's storage for trading");
    }

    @Test
    void aStewardSeesTheStepsWithoutNames() {
        reportNoStorage();
        browser.signIn(api.steward());
        browser.open("/ui/#p=maintenance&c=trading");

        assertStep("grant", "Missing", "The worker holds no write on trading, so it can't read or commit its tables.",
                "Who: a grant admin.");
        assertStep("storage", "Missing", "The worker holds no identity for the store that holds trading's tables. Until "
                + "it does, files that expiry and compaction replace are kept, and no orphan is found.",
                "Who: a platform operator, in the worker's settings.");
        for (String step : List.of("grant", "storage", "store")) {
            assertThat(step(step).findElement(By.cssSelector(".step-ask")).getText()).as(step).isEqualTo(ASK_NAMES);
        }
        assertThat(browser.await(By.id("readiness")).getText()).doesNotContain(workers, S3ForTests.bucket());
        assertThat(driver.findElements(By.cssSelector("#readiness [data-act]"))).isEmpty();
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

    /**
     * A scan in {@link #workers} that could not read its storage prefixes (item 275), as the worker reports it: no
     * {@code storage} at all, and a {@code storage-prefixes} failure.
     */
    private void reportUnreadPrefixes() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String body = "{\"started-at\":\"" + at.minusSeconds(20) + "\",\"ended-at\":\"" + at + "\",\"outcome\":"
                + "\"succeeded\",\"failures\":{\"storage-prefixes\":1}}";
        String id = UUID.nameUUIDFromBytes(at.toString().getBytes(StandardCharsets.UTF_8)).toString();
        expect(api.manage("PUT", "/maintenance/scans/" + id, body, GovernanceServer.PRINCIPAL, "maintenance",
                GovernanceServer.GROUPS, workers), 204);
    }

    /**
     * Step 2's gap as one who may see names reads it (item 256): no identity for the store at the profile's endpoint, or
     * for AWS S3's own, for which the route gives no endpoint.
     */
    private String noIdentity() {
        Map<String, Object> storage = JsonPath.read(expect(api.send("GET", "/management/v1/catalogs/" + Api.CATALOG
                + "/maintenance/readiness", null), 200).body(),
                "$.storage");
        return storage.containsKey("endpoint") ? "The worker holds no identity for the store at " + storage.get("endpoint")
                + "." : "The worker holds no identity for AWS S3.";
    }

    private WebElement step(String step) {
        return browser.await(By.cssSelector("#readiness li.step[data-step='" + step + "']"));
    }

    private void assertStep(String step, String pill, String line, String who) {
        WebElement row = step(step);
        assertThat(row.findElement(By.cssSelector(".pill")).getText()).as(step).isEqualTo(pill);
        assertThat(row.findElement(By.cssSelector(".step-line")).getText()).as(step).isEqualTo(line);
        assertThat(row.findElement(By.cssSelector(".step-who")).getText()).as(step).isEqualTo(who);
    }

    private String head() {
        return browser.await(By.cssSelector("#maintenance .ph h1")).getText();
    }

    /** The tables queued for tonight in trading that the caller may read. */
    private List<Map<String, Object>> queued(String bearer) {
        List<Map<String, Object>> tables = JsonPath.read(expect(api.send("GET",
                "/management/v1/maintenance/tables?pageSize=200", null, "Authorization", bearer), 200).body(), "$.tables");
        return tables.stream().filter(t -> Api.CATALOG.equals(t.get("catalog"))).toList();
    }

    /** The tables in trading the caller may read, one row each. */
    private List<Map<String, Object>> estate(String bearer) {
        List<Map<String, Object>> tables = JsonPath.read(expect(api.send("GET",
                "/management/v1/maintenance/estate?pageSize=500", null, "Authorization", bearer), 200).body(), "$.tables");
        return tables.stream().filter(t -> Api.CATALOG.equals(t.get("catalog"))).toList();
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
