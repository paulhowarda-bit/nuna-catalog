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
 * the redirect URI and web origin the realm's client allows ({@code keycloak/console-realm.json}).
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

    /** Starts Keycloak with the console's realm, and points the catalog and the console at it. */
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
        browser.awaitText(By.cssSelector("section.pane"), "REST prefix");
        browser.await(By.cssSelector("nav.tree button[aria-label='catalog trading']"));
        assertThat(driver.getCurrentUrl()).as("the code and state leave the address bar")
                .startsWith(browser.origin() + "/ui/").doesNotContain("code=", "state=");

        // The token is the realm's, for the catalog, from the console's client, and kept in this tab only.
        assertThat(storageKeys("sessionStorage")).containsExactly("nuna.token");
        assertThat(storageKeys("localStorage")).doesNotContain("nuna.token");
        String claims = claims((String) driver.executeScript("return sessionStorage.getItem('nuna.token')"));
        assertThat(JsonPath.<String>read(claims, "$.iss")).isEqualTo(ISSUER);
        assertThat(JsonPath.<String>read(claims, "$.azp")).isEqualTo("nuna-console");
        assertThat(JsonPath.<Object>read(claims, "$.aud").toString()).contains("nuna-catalog");

        driver.findElement(By.id("sign-out")).click();
        browser.awaitText(By.cssSelector("main .notice"), "Signed out.");
        assertThat(storageKeys("sessionStorage")).isEmpty();
    }

    @Test
    void aSignInAnswerThisTabDidNotStartIsRefused() {
        RemoteWebDriver driver = browser.session();
        // No sign-in started in this tab.
        browser.open("/ui/?code=forged&state=forged");
        browser.awaitText(By.cssSelector("main .notice"), "does not match a sign-in this tab started");

        // A sign-in started here, then an answer carrying another state: refused before any code is traded.
        browser.open("/ui/");
        toTheIdp();
        assertThat(driver.getCurrentUrl()).contains("code_challenge_method=S256").contains("code_challenge=");
        browser.open("/ui/?code=forged&state=forged");
        browser.awaitText(By.cssSelector("main .notice"),
                "The sign-in answer does not match a sign-in this tab started; start again.");
        assertThat(driver.getCurrentUrl()).doesNotContain("code=", "state=");
        assertThat(storageKeys("sessionStorage")).doesNotContain("nuna.token", "nuna.oidc.pending");
        assertThat(driver.findElement(By.id("who")).getText()).isEmpty();
    }

    /**
     * Starts a sign-in from the console's sign-in screen and expects Keycloak's sign-in page. Whatever comes first
     * decides: the page, or anything the console or Keycloak shows instead (a refusal sent back to the console, a
     * failure on the way, Keycloak's own error page). So a refused request fails here with what was shown, and only
     * a slow IdP runs out the wait.
     */
    private void toTheIdp() {
        browser.awaitText(By.cssSelector("main button.primary"), "Sign in with keycloak:8080").click();
        browser.await("neither Keycloak's sign-in page nor a refusal", d -> !d.findElements(By.cssSelector(
                "#username, main .notice, main .alert, #kc-error-message")).isEmpty());
        assertThat(browser.driver().findElements(By.id("username"))).as("Keycloak's sign-in page; the page shows:%n%s",
                browser.visibleText()).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<String> storageKeys(String storage) {
        return (List<String>) browser.driver().executeScript("return Object.keys(" + storage + ")");
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
