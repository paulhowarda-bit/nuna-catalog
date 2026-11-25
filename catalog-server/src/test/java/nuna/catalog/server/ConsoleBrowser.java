package nuna.catalog.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.openqa.selenium.By;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.testcontainers.DockerClientFactory;
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
 * <p>One container serves every class that drives the same server with no network of its own (the governance tests'
 * classes, which share one test context): the first starts it, and it stops when that context closes
 * ({@link StopWithServer}), not with each class, which started one of its own and spent 3 to 19 s on it (backlog 209).
 * A class that joins a network gets a container of its own, which {@link #close()} stops. Each test opens its own
 * {@link #session()}, a fresh browser profile with empty storage and no cookies, so no test sees another's sign-in.
 *
 * <p>No browser starts while the nuna-demo stack's pods run, unless {@code -Pnuna.demo-beside} says to: beside it the
 * tests ran past half an hour, Chrome unreachable and their waits running out, for want of memory (backlog 209). The
 * refusal tells the agent to take the demo down and run them, telling every session (D49, backlog 218): a test cannot
 * tell them, and {@code demo/demo.sh up} from a worktree would put that branch on the shared demo.
 */
public final class ConsoleBrowser implements AutoCloseable {

    /** How long a step may take to show its result: loads go through the server, Kafka and the store. */
    public static final Duration WAIT = Duration.ofSeconds(30);

    /** How often a wait looks again. Selenium's default, 500 ms, had a step ready just after a look wait out the rest. */
    private static final Duration POLL = Duration.ofMillis(100);

    /** The container the classes on one server share, and that server's port; null when none runs. */
    private static BrowserWebDriverContainer shared;
    private static Map<Integer, Integer> sharedPorts;

    private final BrowserWebDriverContainer chrome;
    private final boolean own;
    private final String origin;
    private RemoteWebDriver driver;

    /**
     * Starts the browser, or with no network joins the one already driving this server.
     *
     * @param serverPort  the port the server under test listens on, on the host
     * @param consolePort the port the browser reaches it on, at {@code host.testcontainers.internal}
     * @param network     a Docker network to join, to reach containers there by their aliases; null for none
     */
    public ConsoleBrowser(int serverPort, int consolePort, Network network) {
        origin = "http://" + GenericContainer.INTERNAL_HOST_HOSTNAME + ":" + consolePort;
        own = network != null;
        chrome = own ? start(serverPort, consolePort, network) : shared(serverPort, consolePort);
    }

    private static synchronized BrowserWebDriverContainer shared(int serverPort, int consolePort) {
        Map<Integer, Integer> ports = Map.of(serverPort, consolePort);
        if (shared != null && !(ports.equals(sharedPorts) && shared.isRunning())) {
            stopShared(null);
        }
        if (shared == null) {
            shared = start(serverPort, consolePort, null);
            sharedPorts = ports;
        }
        return shared;
    }

    private static BrowserWebDriverContainer start(int serverPort, int consolePort, Network network) {
        refuseBesideTheDemo();
        Testcontainers.exposeHostPorts(Map.of(serverPort, consolePort));
        BrowserWebDriverContainer chrome =
                new BrowserWebDriverContainer(DockerImageName.parse(System.getProperty("chrome.image")))
                        .withAccessToHost(true);
        if (network != null) {
            chrome.withNetwork(network);
        }
        chrome.start();
        return chrome;
    }

    /** Stops the shared container if it drives the server on {@code serverPort}, or whichever it drives for null. */
    private static synchronized void stopShared(Integer serverPort) {
        if (shared != null && (serverPort == null || sharedPorts.containsKey(serverPort))) {
            try {
                shared.stop();
            } finally {
                shared = null;
                sharedPorts = null;
            }
        }
    }

    private static void refuseBesideTheDemo() {
        if (Boolean.getBoolean("nuna.demo-beside")) {
            return;
        }
        int demo = DockerClientFactory.instance().client().listContainersCmd()
                .withLabelFilter(Map.of("io.kubernetes.pod.namespace", "nuna-demo")).exec().size();
        if (demo > 0) {
            throw new IllegalStateException("The nuna-demo stack is running (" + demo + " of its containers). Beside "
                    + "it Docker and Windows run short of memory, and the console's browser tests have run past half an "
                    + "hour with Chrome unreachable and their waits running out (backlog 209). Take the demo down and "
                    + "run them (D49, backlog 218): tell every active session, run demo/demo.sh down, run these tests "
                    + "again, then run demo/demo.sh up from the main checkout and tell them again. "
                    + "-Pnuna.demo-beside runs them beside it anyway.");
        }
    }

    /**
     * Stops the shared browser when the test context whose server it drives closes: this module keeps one context at a
     * time ({@code spring.properties}), so that is when the classes on that server are done. Registered in
     * {@code META-INF/spring.factories}, which the module's test jar leaves out.
     */
    public static final class StopWithServer implements ApplicationListener<ContextClosedEvent> {
        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            Integer port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", Integer.class);
            if (port != null) {
                stopShared(port);
            }
        }
    }

    /** The console's origin as the browser sees it. */
    public String origin() {
        return origin;
    }

    /**
     * Ends the open session, if any, and starts a new one with an empty profile. A session Selenium could not create
     * is asked for again, up to three times in all: once, the first session of a class found the container started
     * but Selenium resetting the connection (backlog 121), with nothing of the console yet under test.
     */
    public RemoteWebDriver session() {
        quit();
        ChromeOptions options = new ChromeOptions()
                .addArguments("--unsafely-treat-insecure-origin-as-secure=" + origin, "--window-size=1600,1000");
        for (int attempt = 1; ; attempt++) {
            try {
                driver = new RemoteWebDriver(chrome.getSeleniumAddress(), options);
                return driver;
            } catch (SessionNotCreatedException notCreated) {
                if (attempt == 3) {
                    throw notCreated;
                }
                System.out.println("ConsoleBrowser: session not created (attempt " + attempt + "), asking again: "
                        + notCreated.getMessage().lines().findFirst().orElse(""));
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw notCreated;
                }
            }
        }
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
        driver.executeScript("sessionStorage.clear(); sessionStorage.setItem('nuna.token', arguments[0]);",
                bearer.replaceFirst("^Bearer ", ""));
    }

    /**
     * Waits up to {@link #WAIT} for {@code condition} to give a non-null, non-false value, then returns it. An element
     * the console redraws between being found and being read is stale; the wait looks again rather than failing.
     */
    public <T> T await(String what, Function<WebDriver, T> condition) {
        return new WebDriverWait(driver, WAIT)
                .pollingEvery(POLL)
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

    /** Ends the open session, and stops the container if it is this browser's own; a shared one stops with its server. */
    @Override
    public void close() {
        try {
            quit();
        } finally {
            if (own) {
                chrome.stop();
            }
        }
    }
}
