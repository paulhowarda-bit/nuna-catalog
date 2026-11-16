package keel.catalog.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Chrome in a container (Testcontainers' Selenium module, P8), driving the web console that the server under test
 * serves. The server runs on the host; the browser reaches it through Testcontainers' host port forwarding, at
 * {@code http://host.testcontainers.internal:<console port>}. That origin is plain HTTP on a name other than
 * localhost, which a browser does not count as a secure context, and the console's PKCE sign-in needs one
 * ({@code crypto.subtle}). A deployment serves the console over HTTPS, so Chrome is told to treat this one origin as
 * secure; nothing else about the browser is changed.
 *
 * <p>One container serves a test class; each test opens its own {@link #session()}, a fresh browser profile with
 * empty storage and no cookies, so no test sees another's sign-in.
 */
public final class ConsoleBrowser implements AutoCloseable {

    /** How long a step may take to show its result: loads go through the server, Kafka and the store. */
    public static final Duration WAIT = Duration.ofSeconds(30);

    private final BrowserWebDriverContainer chrome;
    private final String origin;
    private RemoteWebDriver driver;

    /**
     * Starts the browser.
     *
     * @param serverPort  the port the server under test listens on, on the host
     * @param consolePort the port the browser reaches it on, at {@code host.testcontainers.internal}
     * @param network     a Docker network to join, to reach containers there by their aliases; null for none
     */
    public ConsoleBrowser(int serverPort, int consolePort, Network network) {
        Testcontainers.exposeHostPorts(Map.of(serverPort, consolePort));
        origin = "http://" + GenericContainer.INTERNAL_HOST_HOSTNAME + ":" + consolePort;
        chrome = new BrowserWebDriverContainer(DockerImageName.parse(System.getProperty("chrome.image")))
                .withAccessToHost(true);
        if (network != null) {
            chrome.withNetwork(network);
        }
        chrome.start();
    }

    /** The console's origin as the browser sees it. */
    public String origin() {
        return origin;
    }

    /** Ends the open session, if any, and starts a new one with an empty profile. */
    public RemoteWebDriver session() {
        quit();
        ChromeOptions options = new ChromeOptions()
                .addArguments("--unsafely-treat-insecure-origin-as-secure=" + origin, "--window-size=1600,1000");
        driver = new RemoteWebDriver(chrome.getSeleniumAddress(), options);
        return driver;
    }

    public RemoteWebDriver driver() {
        return driver;
    }

    /** Loads a console URL afresh, as a reload would: {@code path} is {@code /ui/...}, with any query or fragment. */
    public void open(String path) {
        driver.get("about:blank");
        driver.get(origin + path);
    }

    /** Signs the console in with a bearer token, as its token box would: the token goes into this tab's storage. */
    public void signIn(String bearer) {
        open("/ui/");
        driver.executeScript("sessionStorage.clear(); sessionStorage.setItem('keel.token', arguments[0]);",
                bearer.replaceFirst("^Bearer ", ""));
    }

    /**
     * Waits up to {@link #WAIT} for {@code condition} to give a non-null, non-false value, then returns it. An element
     * the console redraws between being found and being read is stale; the wait looks again rather than failing.
     */
    public <T> T await(String what, Function<WebDriver, T> condition) {
        return new WebDriverWait(driver, WAIT)
                .withMessage(() -> what + "; the page shows:\n" + visibleText())
                .ignoring(StaleElementReferenceException.class)
                .until(condition);
    }

    /** The first displayed element {@code by} finds, once there is one. */
    public WebElement await(By by) {
        return await("no displayed " + by, d -> d.findElements(by).stream().filter(WebElement::isDisplayed)
                .findFirst().orElse(null));
    }

    /** The first displayed element {@code by} finds whose text holds {@code text}, once there is one. */
    public WebElement awaitText(By by, String text) {
        return await("no displayed " + by + " holding \"" + text + "\"", d -> d.findElements(by).stream()
                .filter(e -> e.isDisplayed() && e.getText().contains(text)).findFirst().orElse(null));
    }

    /** Every displayed element {@code by} finds, now. */
    public List<WebElement> displayed(By by) {
        return driver.findElements(by).stream().filter(WebElement::isDisplayed).toList();
    }

    /** An element's text exactly as the page holds it, hidden parts included and with no whitespace folded. */
    public static String textContent(WebElement element) {
        return element.getDomProperty("textContent");
    }

    /** What the page shows, for a failure message. */
    public String visibleText() {
        try {
            return driver.findElement(By.tagName("body")).getText();
        } catch (RuntimeException e) {
            return "(unreadable: " + e + ")";
        }
    }

    private void quit() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }

    @Override
    public void close() {
        try {
            quit();
        } finally {
            chrome.stop();
        }
    }
}
