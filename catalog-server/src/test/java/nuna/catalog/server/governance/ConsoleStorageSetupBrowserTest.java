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
 * What a platform operator sets up outside the catalog (backlog 254, Atelier's design and her answers of 2026-11-24):
 * Service's "Worker storage" panel and the dialog "Set up the worker's storage for <catalog>", from item 253's route,
 * as {@code oscar}, a platform operator holding no grant, with the run's store in each state the worker's reports give
 * it: not reached (the worker holds no identity there, so the worker's settings are shown), reached, refused and not
 * answering. Maintenance's steps 2 and 3 open the dialog for trading's profile, and Open Worker storage opens Service
 * at the profile's row.
 *
 * <p>The server has one storage profile, protected, on the run's store, so an unprotected profile and the AWS S3 and Ceph
 * forms of the store's policy are answered in the page, in the route's own shape (docs/observability/table-health.md,
 * "Storage setup"). A caller who is not an operator is offered neither the panel nor the dialog, and the console never
 * asks the route for them: the catalog would refuse and audit it.
 */
@Tag("heavy")
@GovernanceServer
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleStorageSetupBrowserTest {

    private static final String SETUP = "/maintenance/storage-setup";
    private static final String TWO = "Two changes outside the catalog, one in the worker's settings and one in the store. "
            + "The steps on the Maintenance page turn to Done at the worker's next scan after each.";
    private static final String ONE = "One change outside the catalog, in the store. Its step on the Maintenance page turns "
            + "to Done at the worker's next scan after it.";
    private static final String FOOT = "The catalog writes these policies but never applies them: its own keys would then "
            + "need the right to undo them.";
    private static final String LISTED = "Listed (\\d{1,2} [A-Z][a-z]{2}( \\d{4})? )?\\d\\d:\\d\\d";

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
    void noScans() {
        driver = browser.session();
        jdbc.update("DELETE FROM maintenance_scan");
    }

    @Test
    void aStoreTheWorkerDoesNotReachGetsTheWorkersSettingsAndThePolicies() {
        // The worker has reported, but no identity at the profile's store.
        report("{\"kind\":\"" + kind() + "\",\"endpoint\":\"https://elsewhere.example:9000\",\"prefixes\":[{\"prefix\":"
                + "\"s3://elsewhere/tables\",\"listed\":\"ok\",\"deleted\":0,\"delete-refused\":0,\"delete-failed\":0}]}");
        oscar();
        browser.open("/ui/#p=service");

        WebElement row = row(S3ForTests.PROFILE);
        assertThat(textContent(browser.await(By.cssSelector("#health-storage .panel > h2")))).isEqualTo("Worker storage");
        assertThat(cells(row)).containsExactly(S3ForTests.PROFILE, String.join(", ", catalogs()), allowed, "No identity",
                "Not checked", "Generated for " + profile().get("maintenance-principal") + "; the catalog can't see whether "
                        + "it is applied", "What to set up");
        row.findElement(By.cssSelector("[data-act='what-to-set-up']")).click();

        WebElement dialog = dialog("Set up the worker's storage for " + catalogWords());
        assertThat(dialog.findElement(By.cssSelector(".setup-intro")).getText()).isEqualTo(TWO);
        WebElement settings = dialog.findElement(By.cssSelector("[data-block='worker-settings']"));
        Map<String, Object> ws = JsonPath.read(profile(), "$.worker-settings");
        assertThat(settings.findElement(By.cssSelector("h3")).getText()).isEqualTo("1. The worker's settings");
        assertThat(paragraphs(settings)).first().isEqualTo("Add a profile for " + profile().get("endpoint") + ". Its key "
                + "and secret are the worker's own on that store, never the catalog's:");
        assertThat(textContent(settings.findElement(By.cssSelector("pre[data-text='settings']"))))
                .isEqualTo(String.valueOf(ws.get("yaml")).stripTrailing());
        assertThat(settings.findElement(By.cssSelector("pre[data-text='settings'] mark.fill-in")).getText())
                .isEqualTo("<the worker's access key on this store>");
        assertThat(paragraphs(settings)).contains("Put the secret in the release's secrets as "
                + "nuna.maintenance.storage." + S3ForTests.PROFILE + ".secret-access-key, then upgrade the release."
                + (ws.containsKey("note") ? " On AWS S3 with a role for the worker (IRSA or Pod Identity), leave both keys "
                + "out." : ""));
        WebElement copy = settings.findElement(By.cssSelector("[data-act='copy-settings']"));
        assertThat(copy.getText()).isEqualTo("Copy the settings");
        copy.click();
        browser.await("the settings were not copied", d -> copy.getText().equals("Copied") ? true : null);

        assertStoreBlock(dialog.findElement(By.cssSelector("[data-block='store']")));
        assertThat(dialog.findElement(By.cssSelector(".setup-foot")).getText()).isEqualTo(FOOT);
    }

    @Test
    void aStoreTheWorkerReachesNeedsOnlyThePolicyAndShowsWhenItWasListed() {
        report(identity("\"listed\":\"ok\""));
        oscar();
        browser.open("/ui/#p=service");

        WebElement row = row(S3ForTests.PROFILE);
        assertThat(cell(row, 3)).isEqualTo("Has an identity");
        assertThat(cell(row, 4)).matches(LISTED);
        row.findElement(By.cssSelector("[data-act='what-to-set-up']")).click();
        WebElement dialog = dialog("Set up the worker's storage for " + catalogWords());
        assertThat(dialog.findElement(By.cssSelector(".setup-intro")).getText()).isEqualTo(ONE);
        assertThat(dialog.findElements(By.cssSelector("[data-block='worker-settings']"))).isEmpty();
        assertStoreBlock(dialog.findElement(By.cssSelector("[data-block='store']")));
    }

    @Test
    void aStoreThatRefusedTheListingIsFirstAndWhatToApplyOpensTheDialogForTrading() {
        report(identity("\"listed\":\"refused\",\"list-code\":\"AccessDenied\""));
        oscar();
        browser.open("/ui/#p=service");
        assertThat(cell(row(S3ForTests.PROFILE), 4)).isEqualTo("Listing refused: AccessDenied");

        // Maintenance's step 3 opens the dialog for the page's catalog, not a link to Service.
        browser.open("/ui/#p=maintenance&c=trading");
        WebElement apply = browser.await(By.cssSelector("#readiness li.step[data-step='store'] button[data-act='what-to-apply']"));
        assertThat(apply.getText()).isEqualTo("What to apply");
        apply.click();
        WebElement dialog = dialog("Set up the worker's storage for trading");
        assertThat(dialog.findElement(By.cssSelector(".setup-intro")).getText()).isEqualTo(ONE);
        assertStoreBlock(dialog.findElement(By.cssSelector("[data-block='store']")));
    }

    @Test
    void whatToAddOpensTheDialogWithTheWorkersSettings() {
        report("{\"kind\":\"" + kind() + "\",\"endpoint\":\"https://elsewhere.example:9000\",\"prefixes\":[{\"prefix\":"
                + "\"s3://elsewhere/tables\",\"listed\":\"ok\",\"deleted\":0,\"delete-refused\":0,\"delete-failed\":0}]}");
        oscar();
        browser.open("/ui/#p=maintenance&c=trading");
        WebElement add = browser.await(By.cssSelector("#readiness li.step[data-step='storage'] button[data-act='what-to-add']"));
        assertThat(add.getText()).isEqualTo("What to add");
        add.click();
        WebElement dialog = dialog("Set up the worker's storage for trading");
        assertThat(dialog.findElement(By.cssSelector(".setup-intro")).getText()).isEqualTo(TWO);
        assertThat(dialog.findElements(By.cssSelector("[data-block='worker-settings']"))).hasSize(1);
    }

    @Test
    void aStoreThatDidNotAnswerOpensServiceAtItsRow() {
        report(identity("\"listed\":\"failed\",\"list-code\":\"SdkClientException\""));
        oscar();
        browser.open("/ui/#p=maintenance&c=trading");
        WebElement open = browser.await(By.cssSelector("#readiness li.step[data-step='store'] a[data-act='open-worker-storage']"));
        assertThat(open.getText()).isEqualTo("Open Worker storage");
        assertThat(open.getDomAttribute("href")).isEqualTo("#p=service&tab=worker-storage&g=" + S3ForTests.PROFILE);
        open.click();

        WebElement row = row(S3ForTests.PROFILE);
        browser.await("the profile's row is not marked", d -> row.getDomAttribute("class") != null
                && row.getDomAttribute("class").contains("is-sel") ? true : null);
        assertThat(cell(row, 4)).isEqualTo("No answer: SdkClientException");
    }

    @Test
    void anUnprotectedProfileAndTheAwsAndCephPoliciesAsTheRouteGivesThem() {
        report(identity("\"listed\":\"ok\""));
        oscar();
        browser.open("/ui/#p=maintenance&c=trading");
        browser.await(By.id("readiness"));
        driver.executeScript(SETUP_IN_THE_PAGE);

        // Those with something not done first, each kept in the route's order: then audit, which is done.
        browser.await("the answer in the page is not shown", d -> d.findElements(
                By.cssSelector("#health-storage tr[data-profile='scratch']")).isEmpty() ? null : true);
        assertThat(driver.findElements(By.cssSelector("#health-storage tbody tr")).stream()
                .map(r -> r.getDomAttribute("data-profile")).toList())
                .containsExactly("ceph", "eu", "lake", "scratch", "audit");
        assertThat(cells(row("scratch"))).containsExactly("scratch", "–", "s3://scratch/tables", "No identity",
                "Not checked", "Unprotected", "What to set up");
        assertThat(cell(row("lake"), 1)).isEqualTo("Finance, ledger, ops2, sales, warehouse");
        assertThat(cell(row("lake"), 4)).isEqualTo("Deletes refused: AccessDenied");
        assertThat(cell(row("audit"), 3)).isEqualTo("Has an identity");
        assertThat(cell(row("audit"), 4)).matches(LISTED);

        // Unprotected: the setting the catalog lacks, after the worker's settings for a store it does not reach.
        open("scratch", "Set up the worker's storage for profile scratch");
        WebElement store = browser.await(By.cssSelector("dialog.dlg [data-block='store']"));
        assertThat(paragraphs(store)).containsExactly("The catalog's profile scratch names no maintenance principal, so "
                + "the catalog generates no delete policy for it and nothing stops other keys deleting there. Name the "
                + "worker's storage identity in the catalog's settings, then upgrade the catalog and come back:");
        assertThat(textContent(store.findElement(By.cssSelector("pre[data-text='setting']"))))
                .isEqualTo("nuna.storage.profiles.scratch.maintenance-principal");
        assertThat(store.findElement(By.cssSelector("[data-act='copy-setting']")).getText()).isEqualTo("Copy the setting");
        WebElement settings = browser.await(By.cssSelector("dialog.dlg [data-block='worker-settings']"));
        assertThat(paragraphs(settings)).doesNotContain("Put the store's CA bundle where the worker can read it, and give "
                + "its path as ca-bundle.");
        assertThat(String.join(" ", paragraphs(settings))).doesNotContain("IRSA");
        closeDialog();

        // Ceph: a new store behind a private CA, and its bucket's policy with the store's endpoint.
        open("ceph", "Set up the worker's storage for ops");
        settings = browser.await(By.cssSelector("dialog.dlg [data-block='worker-settings']"));
        assertThat(paragraphs(settings)).containsExactly("Add a profile for https://rgw.internal:7480. Its key and secret "
                + "are the worker's own on that store, never the catalog's:", "Put the store's CA bundle where the worker "
                + "can read it, and give its path as ca-bundle.", "Put the secret in the release's secrets as "
                + "nuna.maintenance.storage.ceph.secret-access-key, then upgrade the release.");
        assertThat(settings.findElements(By.cssSelector("pre[data-text='settings'] mark.fill-in"))).hasSize(2);
        store = browser.await(By.cssSelector("dialog.dlg [data-block='store']"));
        assertThat(paragraphs(store)).first().isEqualTo("As an identity that may, put this policy on bucket ledger. A bucket "
                + "holds one policy: merge these statements into any it already has.");
        assertThat(textContent(store.findElement(By.cssSelector("pre[data-text='commands']"))))
                .startsWith("aws s3api put-bucket-policy --bucket ledger --endpoint-url https://rgw.internal:7480 --policy");
        assertThat(store.findElement(By.cssSelector("[data-act='copy-commands']")).getText()).isEqualTo("Copy the command");
        assertFolded(store, "The policy, to merge into the bucket's own");
        closeDialog();

        // AWS S3: a new region, where a worker role needs no keys; a bucket two profiles share, applied once.
        open("eu", "Set up the worker's storage for profile eu");
        assertThat(paragraphs(browser.await(By.cssSelector("dialog.dlg [data-block='worker-settings']")))).contains(
                "Put the secret in the release's secrets as nuna.maintenance.storage.eu.secret-access-key, then upgrade "
                        + "the release. On AWS S3 with a role for the worker (IRSA or Pod Identity), leave both keys out.");
        closeDialog();
        open("audit", "Set up the worker's storage for profile audit");
        assertThat(browser.await(By.cssSelector("dialog.dlg .setup-intro")).getText()).isEqualTo(ONE);
        assertThat(paragraphs(browser.await(By.cssSelector("dialog.dlg [data-block='store']")))).first().isEqualTo(
                "As an identity that may, put this policy on bucket lake. A bucket holds one policy: merge these statements "
                        + "into any it already has. It serves profiles audit and lake: apply it once.");
        closeDialog();
        open("lake", "Set up the worker's storage for Finance, ledger, ops2 and 2 more");
    }

    @Test
    void neitherThePanelNorTheDialogIsOfferedToOneWhoIsNotAnOperator() {
        report(identity("\"listed\":\"refused\",\"list-code\":\"AccessDenied\""));
        browser.signIn(token("ana", IssuerForTests.GROUP));
        browser.open("/ui/#p=maintenance&c=trading");
        browser.await(By.id("readiness"));
        driver.executeScript("const real = window.fetch; window.asked = []; window.fetch = (url, init) => "
                + "{ window.asked.push(String(url)); return real(url, init); };");
        // Rows 2 and 3 offer her the note for an operator, never the operator's dialog or link.
        location("#p=service");
        browser.await("Service is not closed to her", d -> d.findElements(By.cssSelector("[data-closed]")).isEmpty()
                ? null : true);
        location("#p=maintenance&c=trading");
        WebElement panel = browser.await(By.id("readiness"));
        browser.await(By.cssSelector("#readiness li.step[data-step='store'] [data-act='copy-note']"));
        assertThat(panel.findElements(By.cssSelector("[data-act='what-to-add'], [data-act='what-to-apply'], "
                + "[data-act='open-worker-storage']"))).isEmpty();
        assertThat(driver.findElements(By.id("health-storage"))).isEmpty();
        assertThat((List<?>) driver.executeScript("return window.asked")).noneMatch(url -> String.valueOf(url)
                .contains("storage-setup"));
    }

    // ---- the store's policy, as the route gives it for the run's store ----

    /**
     * Block 2 for the server's profile, whatever store the run uses: MinIO's two policies, each with its commands and
     * its policy folded, or the bucket's policy on AWS S3 and Ceph.
     */
    private void assertStoreBlock(WebElement store) {
        assertThat(store.findElement(By.cssSelector("h3")).getText()).isEqualTo("2. The store's policy");
        List<Map<String, Object>> policies = JsonPath.read(profile(), "$.store.policies");
        for (Map<String, Object> policy : policies) {
            @SuppressWarnings("unchecked")
            List<String> commands = (List<String>) policy.get("commands");
            String key = (String) (policy.containsKey("bucket") ? policy.get("bucket") : policy.get("name"));
            WebElement block = store.findElement(By.cssSelector(".setup-policy[data-policy='" + key + "']"));
            assertThat(textContent(block.findElement(By.cssSelector("pre[data-text='commands']"))))
                    .isEqualTo(String.join("\n", commands));
            if (policy.containsKey("to")) {
                assertThat(paragraphs(block)).first().isEqualTo("As a store admin, let the worker's user list and delete "
                        + "beneath " + allowed + ":");
                assertThat(block.findElements(By.cssSelector("pre[data-text='commands'] mark.fill-in")).stream()
                        .map(WebElement::getText).toList()).containsOnly("<alias>");
                assertThat(block.findElement(By.cssSelector("[data-act='copy-commands']")).getText())
                        .isEqualTo("Copy the commands");
                assertFolded(block, "The policy, to save as " + policy.get("file"));
            } else if (policy.containsKey("except")) {
                assertThat(paragraphs(block)).containsExactly("Then attach " + policy.get("name") + " to every other user "
                        + "of the store, the catalog's own included, so only the worker deletes there:",
                        "Run the attach line once for each user.");
                assertThat(block.findElements(By.cssSelector("pre[data-text='commands'] mark.fill-in")).stream()
                        .map(WebElement::getText).toList()).contains("<alias>", "<user>");
                assertFolded(block, "The policy, to save as " + policy.get("file"));
            } else {
                assertThat(paragraphs(block).getFirst()).startsWith("As an identity that may, put this policy on "
                        + "bucket " + policy.get("bucket") + ". A bucket holds one policy: merge these statements into any "
                        + "it already has.");
                assertFolded(block, "The policy, to merge into the bucket's own");
            }
        }
        // MinIO names its policies per profile (item 253), never plain nuna-no-delete.
        if ("minio".equals(kind())) {
            assertThat(store.getText()).contains("nuna-no-delete-" + S3ForTests.PROFILE)
                    .doesNotContain("nuna-no-delete ");
        }
    }

    /** The policy is folded, under its summary, and Copy the policy copies it all the same. */
    private void assertFolded(WebElement block, String summary) {
        WebElement fold = block.findElement(By.cssSelector("details.setup-fold"));
        assertThat(fold.getDomAttribute("open")).isNull();
        assertThat(fold.findElement(By.cssSelector("summary")).getText()).isEqualTo(summary);
        assertThat(textContent(fold.findElement(By.cssSelector("pre[data-text='policy']")))).startsWith("{");
        WebElement copy = block.findElement(By.cssSelector("[data-act='copy-policy']"));
        assertThat(copy.getText()).isEqualTo("Copy the policy");
    }

    // ---- helpers ----

    /** The route's answer for the server's one profile, as a platform operator reads it. */
    private Map<String, Object> profile() {
        List<Map<String, Object>> profiles = JsonPath.read(expect(api.manage("GET", SETUP, null, "Authorization",
                api.operator()), 200).body(), "$.profiles[?(@.name == '" + S3ForTests.PROFILE + "')]");
        return profiles.getFirst();
    }

    private String kind() {
        return (String) profile().get("kind");
    }

    @SuppressWarnings("unchecked")
    private List<String> catalogs() {
        return (List<String>) profile().get("catalogs");
    }

    /** The dialog's title from a Service row: the profile's catalogs, up to three. */
    private String catalogWords() {
        List<String> catalogs = catalogs();
        if (catalogs.isEmpty()) {
            return "profile " + S3ForTests.PROFILE;
        }
        if (catalogs.size() > 3) {
            return String.join(", ", catalogs.subList(0, 3)) + " and " + (catalogs.size() - 3) + " more";
        }
        return catalogs.size() == 1 ? catalogs.getFirst() : String.join(", ", catalogs.subList(0, catalogs.size() - 1))
                + " and " + catalogs.getLast();
    }

    /** The worker's identity at the profile's own store, its one prefix answered as {@code listed} says. */
    private String identity(String listed) {
        return "{\"kind\":\"" + kind() + "\",\"endpoint\":\"" + profile().get("endpoint") + "\",\"prefixes\":[{\"prefix\":\""
                + allowed + "\"," + listed + ",\"deleted\":0,\"delete-refused\":0,\"delete-failed\":0}]}";
    }

    /** A scan that ended now, reporting {@code storage}. */
    private void report(String storage) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String body = "{\"started-at\":\"" + at.minusSeconds(20) + "\",\"ended-at\":\"" + at + "\",\"outcome\":"
                + "\"succeeded\",\"failures\":{},\"storage\":[" + storage + "]}";
        String id = UUID.nameUUIDFromBytes(at.toString().getBytes(StandardCharsets.UTF_8)).toString();
        expect(api.manage("PUT", "/maintenance/scans/" + id, body, GovernanceServer.PRINCIPAL, "maintenance",
                GovernanceServer.GROUPS, "workers-" + Api.suffix()), 204);
    }

    private void oscar() {
        browser.signIn(token("oscar", GovernanceServer.PLATFORM_OPS));
    }

    private void location(String hash) {
        driver.executeScript("location.hash = arguments[0]", hash);
    }

    private WebElement row(String profile) {
        return browser.await(By.cssSelector("#health-storage tr[data-profile='" + profile + "']"));
    }

    private static List<String> cells(WebElement row) {
        return row.findElements(By.cssSelector("td")).stream().map(WebElement::getText).toList();
    }

    private static String cell(WebElement row, int index) {
        return cells(row).get(index);
    }

    private static List<String> paragraphs(WebElement block) {
        return block.findElements(By.cssSelector("p")).stream().map(WebElement::getText).toList();
    }

    private WebElement dialog(String title) {
        assertThat(textContent(browser.await(By.cssSelector("dialog.dlg #dlg-h")))).isEqualTo(title);
        assertThat(browser.await(By.cssSelector("dialog.dlg .setup-foot")).getText()).isEqualTo(FOOT);
        return browser.await(By.cssSelector("dialog.dlg .setup"));
    }

    private void open(String profile, String title) {
        row(profile).findElement(By.cssSelector("[data-act='what-to-set-up']")).click();
        dialog(title);
    }

    private void closeDialog() {
        driver.findElement(By.cssSelector("dialog.dlg button.dlg-x")).click();
        browser.await("the dialog did not close", d -> d.findElements(By.cssSelector("dialog.dlg")).isEmpty() ? true : null);
    }

    /** A token for {@code user} in {@code groups}, valid for an hour by the server's clock. */
    private String token(String user, String... groups) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(user, "nuna-console", groups)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
    }

    /**
     * The route's answer in the page, for profiles this server does not have: an unprotected MinIO profile on a store
     * the worker does not reach; Ceph behind a private CA, not reached; AWS S3 in a region the worker does not reach,
     * where a worker role needs no keys; and two AWS S3 profiles sharing bucket lake, one with refused deletes, the other
     * listed. Policy texts shortened.
     */
    private static final String SETUP_IN_THE_PAGE = """
            const real = window.fetch;
            const now = new Date().toISOString();
            const policy = '{"Version":"2012-10-17","Statement":[]}';
            const reached = prefixes => ({state: 'reported', prefixes});
            const bucket = (name, profiles, endpoint) => ({'apply-as': 'bucket-policy', bucket: name, profiles, policy,
              commands: [`aws s3api put-bucket-policy --bucket ${name}` + (endpoint ? ` --endpoint-url ${endpoint}` : '')
                + ` --policy '${policy}'`]});
            const answer = {'reported-at': now, profiles: [
              {name: 'audit', catalogs: [], kind: 's3', endpoint: 'https://s3.us-east-1.amazonaws.com',
               prefixes: ['s3://lake/audit'], 'maintenance-principal': 'arn:aws:iam::123456789012:role/nuna-maintenance',
               worker: reached([{prefix: 's3://lake/audit', listed: 'ok', deleted: 2, 'delete-refused': 0, 'delete-failed': 0}]),
               store: {state: 'generated', policies: [bucket('lake', ['audit', 'lake'])]}},
              {name: 'ceph', catalogs: ['ops'], kind: 'ceph', endpoint: 'https://rgw.internal:7480',
               prefixes: ['s3://ledger/tables'], 'maintenance-principal': 'arn:aws:iam:::user/nuna-maintenance',
               worker: {state: 'not-reported'},
               'worker-settings': {store: 'ceph', yaml: "ceph:\\n  kind: ceph\\n  endpoint: https://rgw.internal:7480\\n"
                 + "  region: us-east-1\\n  access-key-id: <the worker's access key on this store>\\n"
                 + "  ca-bundle: <the store's CA bundle in the worker's pod, from storageCaConfigMap>\\n",
                 secret: 'nuna.maintenance.storage.ceph.secret-access-key'},
               store: {state: 'generated', policies: [bucket('ledger', ['ceph'], 'https://rgw.internal:7480')]}},
              {name: 'eu', catalogs: [], kind: 's3', endpoint: 'https://s3.eu-west-1.amazonaws.com',
               prefixes: ['s3://lake-eu/tables'], 'maintenance-principal': 'arn:aws:iam::123456789012:role/nuna-maintenance',
               worker: {state: 'not-reported'},
               'worker-settings': {store: 'eu', yaml: "eu:\\n  kind: s3\\n  endpoint: https://s3.eu-west-1.amazonaws.com\\n"
                 + "  region: eu-west-1\\n  access-key-id: <the worker's access key on this store>\\n",
                 secret: 'nuna.maintenance.storage.eu.secret-access-key',
                 note: 'With a role for the worker (IRSA or Pod Identity), leave access-key-id out and add no secret.'},
               store: {state: 'generated', policies: [bucket('lake-eu', ['eu'])]}},
              {name: 'lake', catalogs: ['Finance', 'ledger', 'ops2', 'sales', 'warehouse'], kind: 's3',
               endpoint: 'https://s3.us-east-1.amazonaws.com', prefixes: ['s3://lake/tables'],
               'maintenance-principal': 'arn:aws:iam::123456789012:role/nuna-maintenance',
               worker: reached([{prefix: 's3://lake/tables', listed: 'ok', deleted: 3, 'delete-refused': 1, 'delete-failed': 0,
                 'delete-code': 'AccessDenied'}]),
               store: {state: 'generated', policies: [bucket('lake', ['audit', 'lake'])]}},
              {name: 'scratch', catalogs: [], kind: 'minio', endpoint: 'http://silo:9000', prefixes: ['s3://scratch/tables'],
               worker: {state: 'not-reported'},
               'worker-settings': {store: 'scratch', yaml: "scratch:\\n  kind: minio\\n  endpoint: http://silo:9000\\n"
                 + "  region: us-east-1\\n  access-key-id: <the worker's access key on this store>\\n",
                 secret: 'nuna.maintenance.storage.scratch.secret-access-key'},
               store: {state: 'unprotected', setting: 'nuna.storage.profiles.scratch.maintenance-principal'}}]};
            window.fetch = (url, init) => new URL(url, location.origin).pathname.endsWith('/maintenance/storage-setup')
              ? Promise.resolve(new Response(JSON.stringify(answer), {headers: {'Content-Type': 'application/json'}}))
              : real(url, init);
            location.hash = '#p=service';
            """;
}
