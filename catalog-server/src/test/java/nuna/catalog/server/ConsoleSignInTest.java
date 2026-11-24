package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The console's sign-in through the real OIDC authorization code flow with PKCE, against Keycloak in a container:
 * a person signs in on Keycloak's own page, the console trades the code and its verifier for a token, and the
 * catalog accepts that token. Entra ID and Ping are checked at work (backlog item 17).
 *
 * <p>One issuer URL for everyone: Keycloak's hostname is fixed at {@value #ISSUER_ORIGIN}, so every token it issues
 * names {@value #ISSUER} whichever way it is reached. The browser reaches it by that name, on a Docker network the
 * two share; the catalog, on the host, fetches the realm's keys through the mapped port instead, as the demo's does
 * inside its cluster. The browser sees the console at {@code host.testcontainers.internal:}{@value #CONSOLE_PORT},
 * the redirect URI, post-logout redirect URI and web origin the realm's client allows
 * ({@code keycloak/console-realm.json}).
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = ConsoleSignInTest.Idp.class)
@Import({PostgresForTests.class, S3ForTests.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleSignInTest {

    static final String ISSUER_ORIGIN = "http://keycloak:8080";
    static final String ISSUER = ISSUER_ORIGIN + "/realms/console";
    static final int CONSOLE_PORT = 8181;
    static final String GROUP = "console-users";

    private static final Network NETWORK = Network.newNetwork();

    /**
     * Starts Keycloak with the console's realm, and points the catalog and the console at it; it stops with the
     * context.
     */
    static final class Idp implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                DockerImageName.parse(System.getProperty("keycloak.image")))
                .withNetwork(NETWORK)
                .withNetworkAliases("keycloak")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m")
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/console-realm.json"),
                        "/opt/keycloak/data/import/console-realm.json")
                .withCommand("start-dev", "--import-realm", "--hostname=" + ISSUER_ORIGIN)
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/console").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (!KEYCLOAK.isRunning()) {
                KEYCLOAK.start();
            }
            StopWithContext.register(context, "keycloak-for-tests", KEYCLOAK::stop);
            String keys = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080)
                    + "/realms/console/protocol/openid-connect/certs";
            Map<String, Object> properties = new HashMap<>();
            properties.put("nuna.security.issuers[0].issuer", ISSUER);
            properties.put("nuna.security.issuers[0].jwks-uri", keys);
            properties.put("nuna.security.issuers[0].audiences", "nuna-catalog");
            properties.put("nuna.security.issuers[0].algorithms", "RS256");
            properties.put("nuna.security.issuers[0].user-id-claim", "preferred_username");
            properties.put("nuna.security.issuers[0].groups-claim", "groups");
            properties.put("nuna.security.issuers[0].client-id-claim", "azp");
            properties.put("nuna.ui.oidc.issuer", ISSUER);
            properties.put("nuna.ui.oidc.client-id", "nuna-console");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("console-idp", properties));
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ConsoleBrowser browser;

    @BeforeAll
    void startBrowser() {
        IssuerForTests.grant(jdbc, "trading", GROUP, "describe");
        browser = new ConsoleBrowser(port, CONSOLE_PORT, NETWORK);
    }

    @AfterAll
    void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
    }

    @Test
    void signsInThroughThePkceFlowAndTheCatalogAcceptsTheToken() {
        RemoteWebDriver driver = browser.session();
        browser.open("/ui/");
        toTheIdp();

        // Keycloak's own page: the console never sees the password.
        driver.findElement(By.id("username")).sendKeys("ana");
        driver.findElement(By.id("password")).sendKeys("pw-ana");
        driver.findElement(By.id("kc-login")).click();

        // Back in the console with a token the catalog accepts: the catalog's own answers fill the page.
        browser.awaitText(By.id("who"), "ana");
        assertThat(driver.findElement(By.id("who")).getText()).contains(GROUP);
        browser.awaitText(By.cssSelector("#catalog-pick [data-cat]"), "trading");
        // Describe alone reads no data, so ana lands on the catalog, not its Overview (backlog 184).
        browser.awaitText(By.cssSelector("#catalog-page h1"), "trading");
        assertThat(driver.getCurrentUrl()).as("the code and state leave the address bar")
                .startsWith(browser.origin() + "/ui/").doesNotContain("code=", "state=");

        // The token is the realm's, for the catalog, from the console's client, and kept in this tab only,
        // beside the ID token that names the session at sign-out (backlog 193).
        assertThat(storageKeys("sessionStorage")).containsExactlyInAnyOrder("nuna.token", "nuna.id-token");
        assertThat(storageKeys("localStorage")).doesNotContain("nuna.token", "nuna.id-token");
        String claims = claims((String) driver.executeScript("return sessionStorage.getItem('nuna.token')"));
        assertThat(JsonPath.<String>read(claims, "$.iss")).isEqualTo(ISSUER);
        assertThat(JsonPath.<String>read(claims, "$.azp")).isEqualTo("nuna-console");
        assertThat(JsonPath.<Object>read(claims, "$.aud").toString()).contains("nuna-catalog");
    }

    /**
     * "Sign out" ends the person's session at the IdP too (D16, amended 2026-11-19), by OpenID Connect RP-initiated
     * logout: the console forgets its token and sends the browser to Keycloak's end-session endpoint, which sends it
     * back to the console's page. The next "Sign in" then lands on Keycloak's password page again. Forgetting the
     * token alone, as "Sign out" did before, goes straight back in on Keycloak's session, as the test shows first.
     */
    @Test
    void signOutEndsTheIdpSessionSoTheNextSignInAsksForThePassword() {
        RemoteWebDriver driver = browser.session();
        browser.open("/ui/");
        toTheIdp();
        driver.findElement(By.id("username")).sendKeys("ana");
        driver.findElement(By.id("password")).sendKeys("pw-ana");
        driver.findElement(By.id("kc-login")).click();
        browser.awaitText(By.id("who"), "ana");

        // Keycloak's session is there: with the console's token forgotten, "Sign in" goes straight back in.
        driver.executeScript("sessionStorage.clear()");
        browser.open("/ui/");
        signIn();
        assertThat(driver.findElements(By.cssSelector("#who strong"))).as(
                "straight back in on Keycloak's session, without a password; the page shows:%n%s",
                browser.visibleText()).isNotEmpty();
        browser.awaitText(By.id("who"), "ana");

        // "Sign out": to Keycloak's end-session endpoint, for the console's client, back to the console's page. The
        // console names the session by the sign-in's ID token (id_token_hint), so Keycloak ends it without asking the
        // person to confirm on its own page (Atelier's review, F13; backlog 193).
        assertThat(storageKeys("sessionStorage")).contains("nuna.id-token");
        driver.findElement(By.id("sign-out")).click();
        browser.await("neither the console signed out nor anything shown instead", d -> !d.findElements(
                By.cssSelector("#kc-logout, main .notice, main .alert, #kc-error-message")).isEmpty());
        assertThat(driver.findElements(By.id("kc-logout"))).as(
                "Keycloak ends the session without asking; the page shows:%n%s", browser.visibleText()).isEmpty();
        browser.awaitText(By.cssSelector("main .notice"), "Signed out.");
        assertThat(driver.getCurrentUrl()).isEqualTo(browser.origin() + "/ui/");
        assertThat(storageKeys("sessionStorage")).isEmpty();
        assertThat(driver.findElement(By.id("who")).getText()).isEmpty();

        // Keycloak's session is gone: "Sign in" asks for the password again.
        browser.open("/ui/");
        toTheIdp();
    }

    @Test
    void aSignInAnswerThisTabDidNotStartIsRefused() {
        RemoteWebDriver driver = browser.session();
        // No sign-in started in this tab.
        browser.open("/ui/?code=forged&state=forged");
        browser.awaitText(By.cssSelector("main .notice"), "This sign-in was not started in this tab");

        // A sign-in started here, then an answer carrying another state: refused before any code is traded.
        browser.open("/ui/");
        toTheIdp();
        assertThat(driver.getCurrentUrl()).contains("code_challenge_method=S256").contains("code_challenge=");
        browser.open("/ui/?code=forged&state=forged");
        browser.awaitText(By.cssSelector("main .notice"),
                "This sign-in was not started in this tab, or has expired. Start again.");
        assertThat(driver.getCurrentUrl()).doesNotContain("code=", "state=");
        assertThat(storageKeys("sessionStorage")).doesNotContain("nuna.token", "nuna.oidc.pending");
        assertThat(driver.findElement(By.id("who")).getText()).isEmpty();

        // Start again opens the sign-in screen afresh, with no half-finished sign-in left in the tab (backlog 193).
        driver.findElement(By.cssSelector("main [data-act='start-again']")).click();
        browser.awaitText(By.cssSelector("main button.primary"), "Sign in with keycloak:8080");
        assertThat(driver.findElements(By.cssSelector("main .notice"))).isEmpty();
        assertThat(storageKeys("sessionStorage")).doesNotContain("nuna.oidc.pending");
    }

    /**
     * An IdP that does not answer is named, with what to do, instead of "Loading…" for a minute: the console gives up
     * on it after 15 s (Atelier's review, F13; backlog 193). The page's fetch is made to time out at once here, as
     * AbortSignal.timeout would after its wait.
     */
    @Test
    void anIdpThatDoesNotAnswerIsNamedWithStartAgain() {
        RemoteWebDriver driver = browser.session();
        browser.open("/ui/");
        browser.awaitText(By.cssSelector("main button.primary"), "Sign in with keycloak:8080");
        driver.executeScript("const real = window.fetch; window.fetch = (url, init) => String(url).includes('keycloak')"
                + " ? Promise.reject(new DOMException('signal timed out', 'TimeoutError')) : real(url, init);");
        driver.findElement(By.cssSelector("main button.primary")).click();
        browser.awaitText(By.cssSelector("main .refusal"), "keycloak:8080 is not answering: no reply in 15 s.");
        driver.findElement(By.cssSelector("main [data-act='start-again']")).click();
        browser.awaitText(By.cssSelector("main button.primary"), "Sign in with keycloak:8080");
        assertThat(driver.findElements(By.cssSelector("main .refusal"))).isEmpty();
    }

    /**
     * Starts a sign-in from the console's sign-in screen and expects Keycloak's sign-in page. Whatever comes first
     * decides: the page, or anything the console or Keycloak shows instead (a refusal sent back to the console, a
     * failure on the way, Keycloak's own error page, the console signed in on a session Keycloak still holds). So a
     * refused request fails here with what was shown, and only a slow IdP runs out the wait.
     */
    private void toTheIdp() {
        signIn();
        assertThat(browser.driver().findElements(By.id("username"))).as("Keycloak's sign-in page; the page shows:%n%s",
                browser.visibleText()).isNotEmpty();
    }

    /** Clicks "Sign in" on the console's sign-in screen and waits for what comes of it, as {@link #toTheIdp} says. */
    private void signIn() {
        browser.awaitText(By.cssSelector("main button.primary"), "Sign in with keycloak:8080").click();
        browser.await("neither Keycloak's sign-in page, the console signed in, nor a refusal", d -> !d.findElements(
                By.cssSelector("#username, #who strong, main .notice, main .alert, #kc-error-message")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<String> storageKeys(String storage) {
        return (List<String>) browser.driver().executeScript("return Object.keys(" + storage + ")");
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
