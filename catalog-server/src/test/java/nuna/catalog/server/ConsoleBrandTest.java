package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebElement;
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

/**
 * The console with a brand pack set (backlog 333): the neutral test pack, Larchmere, light-only and with a mark at the
 * largest the header allows. Its words reach {@code config.json}; in a real browser the page is the pack's from first
 * paint, the light theme pinned and both theme switches gone, the header fits at every width with its mark whole, and
 * the header's button (Paul, 2026-11-28; D56) turns the pack off for this viewer, kept across a reload, and on again.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {IssuerForTests.class, ConsoleBrandTest.Pack.class})
@Import({PostgresForTests.class, S3ForTests.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleBrandTest {

    /** The test pack, copied to a folder of its own before the server starts, as an install's mounted folder. */
    static final class Pack implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static final Path FOLDER;

        static {
            try {
                FOLDER = Files.createTempDirectory("nuna-brand-pack");
                for (String file : List.of("brand.css", "mark.svg", "favicon.svg")) {
                    try (InputStream in = ConsoleBrandTest.class.getResourceAsStream("/brand-pack/" + file)) {
                        Files.write(FOLDER.resolve(file), in.readAllBytes());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("brand-pack", Map.of(
                    "nuna.ui.brand.dir", FOLDER.toString(),
                    "nuna.ui.brand.name", "Larchmere",
                    "nuna.ui.brand.mark-label", "Larchmere Data",
                    "nuna.ui.brand.show-name", "false",
                    "nuna.ui.brand.toggle-label", "Larchmere mode")));
        }
    }

    /** Whether any two shown parts of the header overlap, or the header runs past the window; empty when it fits. */
    private static final String HEADER_FAULT = """
            const top = document.getElementById('top');
            if (document.documentElement.scrollWidth > document.documentElement.clientWidth + 1) return 'the page scrolls sideways';
            const parts = [...top.children].filter(e => e.getClientRects().length > 0)
                .map(e => ({e, r: e.getBoundingClientRect()})).filter(p => p.r.width > 1).sort((a, b) => a.r.left - b.r.left);
            for (let i = 1; i < parts.length; i++) {
              const a = parts[i - 1], b = parts[i];
              if (a.r.right > b.r.left + 0.5) return a.e.className + ' overlaps ' + b.e.className;
            }
            const last = parts[parts.length - 1];
            if (last && last.r.right > top.getBoundingClientRect().right + 0.5) return last.e.className + ' runs past the header';
            return '';
            """;

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ConsoleBrowser browser;
    private RemoteWebDriver driver;

    @BeforeAll
    void startBrowser() {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "describe");
        browser = new ConsoleBrowser(port, port, null);
    }

    @AfterAll
    void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
    }

    @Test
    void configCarriesThePacksWordsAndTheSlotServesThePack() throws Exception {
        String config = get("/ui/config.json");
        assertThat(JsonPath.<Map<String, Object>>read(config, "$.brand")).containsOnlyKeys("name", "mark-label",
                "show-name", "toggle-label");
        assertThat(JsonPath.<String>read(config, "$.brand.name")).isEqualTo("Larchmere");
        assertThat(JsonPath.<String>read(config, "$.brand['mark-label']")).isEqualTo("Larchmere Data");
        assertThat(JsonPath.<Boolean>read(config, "$.brand['show-name']")).isFalse();
        assertThat(JsonPath.<String>read(config, "$.brand['toggle-label']")).isEqualTo("Larchmere mode");
        assertThat(config).as("the folder stays on the server").doesNotContain(Pack.FOLDER.getFileName().toString());
        assertThat(get("/ui/brand/brand.css")).contains("--brand-pack").contains("Larchmere");
        assertThat(get("/docs/assets/brand/brand.css")).contains("--brand-pack");
    }

    @Test
    void thePackShowsFromFirstPaintAndTheButtonTurnsItOffAndOn() {
        signIn();
        assertThat(attr("data-brand")).isEqualTo("on");
        assertThat(attr("data-pack-light")).isEqualTo("");
        assertThat(attr("data-theme")).as("a light-only pack pins light").isEqualTo("light");
        assertThat(driver.executeScript("return getComputedStyle(document.body).fontFamily")).asString()
                .contains("Larchmere");
        assertThat(driver.executeScript("return document.querySelector('link[rel=icon]').getAttribute('href')"))
                .isEqualTo("brand/favicon.svg");
        assertThat(driver.getTitle()).endsWith("· Larchmere");
        assertThat(browser.displayed(By.cssSelector("[data-theme-seg]"))).as("both theme switches go").isEmpty();

        // The mark stands alone, whole, in its fixed box; the name goes to the eyebrow.
        WebElement mark = driver.findElement(By.cssSelector("#top .nc-brand .mark"));
        assertThat(mark.isDisplayed()).isTrue();
        assertThat(mark.getRect().getWidth()).isEqualTo(120);
        assertThat(mark.getRect().getHeight()).isEqualTo(20);
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand .peak")).isDisplayed()).isFalse();
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand .nm")).isDisplayed()).isFalse();
        // The mark is drawn on the header's link, not on <html>, which carries data-brand too while a pack is set.
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand")).getAccessibleName())
                .isEqualTo("Larchmere Data, overview");
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand")).getDomAttribute("href"))
                .as("the mark leads home").isNotEqualTo("#").startsWith("#");

        WebElement button = driver.findElement(By.cssSelector("#top .nc-pack"));
        assertThat(button.isDisplayed()).isTrue();
        assertThat(button.getAccessibleName()).isEqualTo("Larchmere mode");
        assertThat(button.getDomAttribute("aria-pressed")).isEqualTo("true");

        // Off: Ice line, its theme switch and its name, kept in this browser across a reload.
        button.click();
        browser.await("the pack turned off", d -> "off".equals(attr("data-brand")));
        assertThat(driver.executeScript("return document.querySelector('link[data-brand-css]').disabled")).isEqualTo(true);
        assertThat(driver.executeScript("return getComputedStyle(document.body).fontFamily")).asString()
                .contains("Instrument Sans").doesNotContain("Larchmere");
        assertThat(browser.displayed(By.cssSelector("#top [data-theme-seg]"))).hasSize(1);
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand .peak")).isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector("#top .nc-brand .nm")).getText()).isEqualTo("nuna");
        assertThat(driver.getTitle()).endsWith("· nuna");
        assertThat(button.getDomAttribute("aria-pressed")).isEqualTo("false");
        assertThat(driver.executeScript("return localStorage.getItem('nuna.brand')")).isEqualTo("off");
        driver.navigate().refresh();
        browser.await(By.cssSelector("#who strong"));
        assertThat(attr("data-brand")).isEqualTo("off");
        assertThat(driver.executeScript("return document.querySelector('link[rel=icon]').getAttribute('href')"))
                .isEqualTo("favicon.svg");

        // On again.
        driver.findElement(By.cssSelector("#top .nc-pack")).click();
        browser.await("the pack turned on", d -> "on".equals(attr("data-brand")));
        assertThat(attr("data-theme")).isEqualTo("light");
        assertThat(driver.executeScript("return localStorage.getItem('nuna.brand')")).isNull();
    }

    @Test
    void withTheLargestMarkTheHeaderFitsAtEveryWidthAndOnAPhoneTheButtonIsInTheMenu() {
        signIn();
        for (boolean on : List.of(true, false)) {
            for (int width : new int[] {761, 800, 901, 1000, 1101, 1241, 1321, 1401, 1440, 1600}) {
                driver.manage().window().setSize(new Dimension(width, 900));
                assertThat((String) driver.executeScript(HEADER_FAULT)).as("the header at %d px, pack %s", width,
                        on ? "on" : "off").isEmpty();
            }
            if (on) {
                driver.manage().window().setSize(new Dimension(1440, 900));
                driver.findElement(By.cssSelector("#top .nc-pack")).click();
                browser.await("the pack turned off", d -> "off".equals(attr("data-brand")));
            }
        }
        driver.executeScript("localStorage.removeItem('nuna.brand')");
        driver.manage().window().setSize(new Dimension(600, 900));
        driver.navigate().refresh();
        browser.await(By.cssSelector("#who .avatar"));
        assertThat(attr("data-brand")).isEqualTo("on");
        assertThat(driver.findElement(By.cssSelector("#top .nc-pack")).isDisplayed()).isFalse();
        assertThat((String) driver.executeScript(HEADER_FAULT)).as("the header at 600 px").isEmpty();
        driver.findElement(By.cssSelector(".nc-menu")).click();
        WebElement inMenu = browser.await(By.cssSelector(".drawer-x [data-act='pack']"));
        assertThat(inMenu.getText()).isEqualTo("Larchmere mode");
        assertThat(browser.displayed(By.cssSelector(".drawer-x [data-theme-seg]"))).isEmpty();
    }

    private void signIn() {
        driver = browser.session();
        driver.manage().window().setSize(new Dimension(1440, 900));
        browser.signIn(IssuerForTests.bearer("tester", IssuerForTests.GROUP));
        browser.open("/ui/");
        browser.await(By.cssSelector("#who strong"));
    }

    private Object attr(String name) {
        return driver.executeScript("return document.documentElement.getAttribute(arguments[0])", name);
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }
}
