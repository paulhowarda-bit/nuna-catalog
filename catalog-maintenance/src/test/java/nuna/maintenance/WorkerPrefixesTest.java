package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import nuna.catalog.objectstore.StoreKind;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The worker reads its storage prefixes from the catalog at each scan (item 256; D30, amended 2026-11-24). A scan whose
 * read fails, or names no prefix, lists and deletes nothing: its storage keeps no earlier answer, and the scan claims
 * no purge and deletes no due file. The prefix set is logged at the first read and whenever it changes, each new
 * prefix at WARN, and each profile the worker refuses once, at WARN.
 *
 * <p>Needs only Java: the catalog is a stand-in on the JDK's HTTP server, and the worker's own storage is the real
 * one on a store it never reaches, since it lists and deletes nothing here.
 */
class WorkerPrefixesTest {

    private static final String PREFIX = "s3://warehouse/tables";
    private static final String TABLE = PREFIX + "/ns/t";
    private static final Map<String, WorkerSettings.Store> STORES = Map.of("lake", new WorkerSettings.Store(
            StoreKind.MINIO, URI.create("http://store.invalid:9000"), "us-east-1", "nuna-maintenance", "secret", null));
    private static final String ERROR = "{\"error\":{\"message\":\"down\",\"type\":\"ServiceUnavailable\","
            + "\"code\":503}}";

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger workerLog = (Logger) LoggerFactory.getLogger(Worker.class);
    private HttpServer catalog;
    private volatile int status = 200;
    private volatile String prefixes = profiles(PREFIX);
    private volatile String purges = "{\"purges\":[]}";
    private volatile String deletions = "{\"deletions\":[]}";

    @BeforeEach
    void listen() {
        log.start();
        workerLog.addAppender(log);
    }

    @AfterEach
    void stop() {
        workerLog.detachAppender(log);
        if (catalog != null) {
            catalog.stop(0);
        }
    }

    @Test
    void aFailedOrEmptyReadListsAndDeletesNothingThatScan() throws Exception {
        purges = "{\"purges\":[{\"catalog\":\"lakehouse\",\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"table\","
                + "\"name\":\"t\",\"state\":\"dropped\",\"location\":\"" + TABLE + "\"}]}";
        deletions = "{\"deletions\":[{\"catalog\":\"lakehouse\",\"id\":\"" + UUID.randomUUID() + "\",\"object-id\":\""
                + UUID.randomUUID() + "\",\"location\":\"" + TABLE + "/data/a.parquet\","
                + "\"not-before\":\"2026-11-01T00:00:00Z\",\"refs\":[]}]}";
        OwnStorage storage = Storage.of(STORES);
        try (Worker worker = worker(storage)) {
            // What an earlier scan read.
            storage.reach(List.of(new Management.StorageProfile("lakehouse", "minio", "http://store.invalid:9000",
                    List.of(PREFIX))));
            assertThat(storage.store(TABLE + "/data/a.parquet")).contains("lake");

            status = 503;
            ScanReport failed = worker.runOnce();

            assertThat(failed.failuresByKind).containsEntry(ScanReport.Failure.STORAGE_PREFIXES, 1);
            assertThat(failed.failures).hasSize(1);
            assertThat(storageWork()).as("no purge claimed, no due file deleted").isEmpty();
            assertThatThrownBy(() -> storage.list(TABLE + "/")).as("the earlier answer is not kept")
                    .isInstanceOf(IllegalStateException.class);

            storage.reach(List.of(new Management.StorageProfile("lakehouse", "minio", "http://store.invalid:9000",
                    List.of(PREFIX))));
            status = 200;
            prefixes = "{\"profiles\":[]}";
            ScanReport empty = worker.runOnce();

            assertThat(empty.failures).isEmpty();
            assertThat(storageWork()).as("no purge claimed, no due file deleted").isEmpty();
            assertThatThrownBy(() -> storage.list(TABLE + "/")).as("the earlier answer is not kept")
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).as("the work list read in both scans").contains(
                "GET /management/v1/maintenance/work?section=tables");
    }

    @Test
    void thePrefixSetIsLoggedAtTheFirstReadAndWheneverItChangesEachNewPrefixAtWarn() throws Exception {
        try (Worker worker = worker(Storage.of(STORES))) {
            worker.runOnce();
            assertThat(lines(Level.INFO)).anyMatch(line -> line.contains("the storage prefixes the catalog gives it")
                    && line.contains(PREFIX + " (profile lakehouse, store lake)"));
            assertThat(lines(Level.WARN)).isEmpty();

            log.list.clear();
            prefixes = profiles(PREFIX, "s3://archive/tables");
            worker.runOnce();
            assertThat(lines(Level.WARN)).as("only the new prefix").singleElement().asString()
                    .contains("a new storage prefix").contains("s3://archive/tables").doesNotContain(PREFIX);

            log.list.clear();
            status = 503;
            worker.runOnce();
            assertThat(lines(Level.WARN)).singleElement().asString().startsWith("Cannot read the storage prefixes");
            assertThat(lines(Level.INFO)).noneMatch(line -> line.contains("no longer"));

            log.list.clear();
            status = 200;
            worker.runOnce();
            assertThat(lines(Level.WARN)).as("the same set as before the failed read").isEmpty();
            assertThat(lines(Level.INFO)).noneMatch(line -> line.contains("storage prefix"));

            log.list.clear();
            prefixes = profiles("s3://archive/tables");
            worker.runOnce();
            assertThat(lines(Level.WARN)).isEmpty();
            assertThat(lines(Level.INFO)).singleElement().asString().contains("no longer").contains(PREFIX);

            log.list.clear();
            prefixes = "{\"profiles\":[" + profile("lakehouse", "s3://archive/tables") + ","
                    + "{\"name\":\"cold\",\"kind\":\"ceph\",\"endpoint\":\"https://rgw.invalid\","
                    + "\"prefixes\":[\"s3://cold/tables\"]}]}";
            ScanReport refusing = worker.runOnce();
            assertThat(lines(Level.WARN)).singleElement().asString()
                    .startsWith("The worker refuses storage profile cold");
            assertThat(refusing.failuresByKind).containsEntry(ScanReport.Failure.STORAGE_PREFIXES, 1);

            log.list.clear();
            ScanReport again = worker.runOnce();
            assertThat(lines(Level.WARN)).as("a refusal logged once").isEmpty();
            assertThat(again.failuresByKind).as("and counted at every scan")
                    .containsEntry(ScanReport.Failure.STORAGE_PREFIXES, 1);
        }
    }

    /** The worker, on a stand-in catalog, its cleanup window closed all day. */
    private Worker worker(Storage storage) throws IOException {
        URI uri = catalog();
        TokenSource tokens = new TokenSource() {
            @Override
            public String token() {
                return "token";
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of();
            }
        };
        return new Worker(new Management(uri, tokens, HttpClient.newHttpClient(), 100),
                new Catalogs(uri, tokens, Map.of(), TraceHeaders.NONE), storage, Compactor.NONE, new FakeClock(),
                new CleanupWindow(LocalTime.of(1, 0), LocalTime.of(1, 1), ZoneOffset.UTC), Duration.ofDays(1),
                Duration.ofHours(2), 2, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    /** The calls that would claim a purge or delete a due file. */
    private List<String> storageWork() {
        return calls.stream().filter(call -> call.contains("/purge") || call.contains("/deletions")).toList();
    }

    private List<String> lines(Level level) {
        return log.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static String profiles(String... prefixes) {
        return "{\"profiles\":[" + profile("lakehouse", prefixes) + "]}";
    }

    private static String profile(String name, String... prefixes) {
        return "{\"name\":\"" + name + "\",\"kind\":\"minio\",\"endpoint\":\"http://store.invalid:9000\","
                + "\"prefixes\":[\"" + String.join("\",\"", prefixes) + "\"]}";
    }

    private URI catalog() throws IOException {
        catalog = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        catalog.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            calls.add(exchange.getRequestMethod() + " " + path + (path.endsWith("/work")
                    ? "?section=" + query.replaceAll(".*section=([a-z]+).*", "$1") : ""));
            if (path.equals(TestReach.ROUTE)) {
                answer(exchange, status, status == 200 ? prefixes : ERROR);
            } else if (path.equals("/management/v1/maintenance/work")) {
                String section = query.replaceAll(".*section=([a-z]+).*", "$1");
                answer(exchange, 200, switch (section) {
                    case "purges" -> purges;
                    case "deletions" -> deletions;
                    default -> "{\"" + section + "\":[]}";
                });
            } else if (exchange.getRequestMethod().equals("PUT") && path.startsWith("/management/v1/maintenance/")) {
                answer(exchange, 204, null);
            } else {
                answer(exchange, 404, "{\"error\":{\"message\":\"no route " + path + "\",\"type\":\"NotFound\","
                        + "\"code\":404}}");
            }
        });
        catalog.start();
        return URI.create("http://localhost:" + catalog.getAddress().getPort() + "/");
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body == null ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
