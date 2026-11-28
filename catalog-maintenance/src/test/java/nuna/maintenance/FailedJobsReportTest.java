package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.iceberg.io.FileInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A scan names each failed job on a table, with the table and why, in its report to the catalog (backlog 327): the
 * console's review found "table-load 1" at every scan and no table named. The kinds are still counted in
 * {@code failures}, every one, for the console and an older catalog; at most {@value ScanReport#MAX_NAMED} are named.
 *
 * <p>Needs only Java: the catalog is a stand-in on the JDK's HTTP server whose work list names tables with snapshot
 * expiry on, each of which it refuses to load as the real catalog does: closed until classified (403), gone (404) or
 * failing (500). It keeps each scan report.
 */
class FailedJobsReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLOSED = "table beneficial_owners is in a regulated area and has no classification;"
            + " it is closed until it is classified";

    private final List<String> scanReports = Collections.synchronizedList(new ArrayList<>());
    private HttpServer catalog;

    @AfterEach
    void stop() {
        if (catalog != null) {
            catalog.stop(0);
        }
    }

    @Test
    void aFailedLoadNamesItsTableAndWhy() throws Exception {
        UUID closed = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        URI uri = catalog(List.of(table(closed, "party", "beneficial_owners"), table(gone, "party", "gone"),
                table(broken, "party", "broken")));

        try (Worker worker = worker(uri)) {
            ScanReport report = worker.runOnce();
            assertThat(report.failuresByKind).containsEntry(ScanReport.Failure.TABLE_LOAD, 3);
        }

        JsonNode ended = JSON.readTree(scanReports.getLast());
        assertThat(ended.path("failures").path("table-load").asInt()).as("still counted by kind").isEqualTo(3);
        assertThat(ended.path("failed-jobs")).hasSize(3);
        JsonNode first = ended.path("failed-jobs").get(0);
        assertThat(first.path("kind").asText()).isEqualTo("table-load");
        assertThat(first.path("catalog").asText()).isEqualTo("brokerage");
        assertThat(first.path("namespace").toString()).isEqualTo("[\"party\"]");
        assertThat(first.path("name").asText()).isEqualTo("beneficial_owners");
        assertThat(first.path("id").asText()).isEqualTo(closed.toString());
        assertThat(first.path("reason").asText()).as("closed until classified, from the catalog's refusal")
                .isEqualTo("closed");
        assertThat(first.path("message").asText()).contains(CLOSED);
        assertThat(reasons(ended)).containsExactly("beneficial_owners=closed", "gone=not-found", "broken=error");
        assertThat(ended.path("failed-jobs")).allSatisfy(job -> assertThat(job.path("message").asText())
                .as("one short line, never a stack").doesNotContain("\n", "\tat ").hasSizeLessThanOrEqualTo(
                        ScanReport.MAX_MESSAGE));
        assertThat(JSON.readTree(scanReports.getFirst()).path("failed-jobs")).as("the start names none").isEmpty();
    }

    @Test
    void aScanNamesTheFirstTwentyAndCountsThemAll() throws Exception {
        List<String> tables = IntStream.range(0, ScanReport.MAX_NAMED + 5)
                .mapToObj(i -> table(UUID.randomUUID(), "party", "refused_" + i)).toList();
        URI uri = catalog(tables);

        try (Worker worker = worker(uri)) {
            worker.runOnce();
        }

        JsonNode ended = JSON.readTree(scanReports.getLast());
        assertThat(ended.path("failures").path("table-load").asInt()).isEqualTo(ScanReport.MAX_NAMED + 5);
        assertThat(ended.path("failed-jobs")).hasSize(ScanReport.MAX_NAMED);
        assertThat(ended.path("failed-jobs")).allSatisfy(job -> assertThat(job.path("reason").asText())
                .as("refused, not closed").isEqualTo("refused"));
    }

    private static List<String> reasons(JsonNode report) {
        List<String> reasons = new ArrayList<>();
        report.path("failed-jobs").forEach(job -> reasons.add(job.path("name").asText() + "="
                + job.path("reason").asText()));
        return reasons;
    }

    private static String table(UUID id, String namespace, String name) {
        return "{\"catalog\":\"brokerage\",\"id\":\"" + id + "\",\"namespace\":[\"" + namespace + "\"],\"name\":\""
                + name + "\",\"location\":\"s3://warehouse/tables/" + name + "\",\"policy\":{"
                + "\"snapshot-expiry.enabled\":\"true\",\"snapshot-expiry.max-snapshot-age\":\"P5D\","
                + "\"snapshot-expiry.min-snapshots-to-keep\":\"1\"}}";
    }

    private static Worker worker(URI uri) {
        FakeClock clock = new FakeClock();
        LocalTime turn = LocalTime.ofInstant(clock.instant().minus(Duration.ofHours(12)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
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
                new Catalogs(uri, tokens, Map.of(), TraceHeaders.NONE), new NoFiles(), Compactor.NONE, clock,
                new CleanupWindow(turn, turn, ZoneOffset.UTC), Duration.ofDays(1), Duration.ofHours(2), 2,
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    /**
     * The catalog as the worker sees it: the work list naming {@code tables}, each refused its load as its name says,
     * and every report of the worker's own work taken, each scan's kept.
     */
    private URI catalog(List<String> tables) throws IOException {
        String work = "{\"tables\":[" + tables.stream().collect(Collectors.joining(",")) + "]}";
        catalog = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        catalog.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/management/v1/maintenance/work")) {
                answer(exchange, 200, query.contains("section=tables") ? work : "{}");
            } else if (path.equals(TestReach.ROUTE)) {
                answer(exchange, 200, TestReach.ONE_PROFILE);
            } else if (path.equals("/v1/config")) {
                answer(exchange, 200, "{\"defaults\":{},\"overrides\":{}}");
            } else if (path.equals("/v1/namespaces/party/tables/beneficial_owners")) {
                answer(exchange, 403, error(CLOSED, "ForbiddenException", 403));
            } else if (path.equals("/v1/namespaces/party/tables/gone")) {
                answer(exchange, 404, error("Table does not exist: party.gone", "NoSuchTableException", 404));
            } else if (path.equals("/v1/namespaces/party/tables/broken")) {
                answer(exchange, 500, error("the store did not answer\n\tat somewhere", "ServiceFailureException",
                        500));
            } else if (path.startsWith("/v1/namespaces/party/tables/refused_")) {
                answer(exchange, 403, error("svc-maintenance holds no privilege that gives select for loadTable",
                        "ForbiddenException", 403));
            } else if (path.startsWith("/management/v1/maintenance/scans/")) {
                scanReports.add(body);
                answer(exchange, 204, null);
            } else if (exchange.getRequestMethod().equals("PUT") && path.startsWith("/management/v1/maintenance/")) {
                answer(exchange, 204, null);
            } else {
                answer(exchange, 404, error("no route " + path, "NotFound", 404));
            }
        });
        catalog.start();
        return URI.create("http://localhost:" + catalog.getAddress().getPort() + "/");
    }

    /** The worker's own storage: it reaches every profile and holds no file; no table here loads, so none is read. */
    private static final class NoFiles implements Storage {

        @Override
        public Iterable<FileInfo> list(String prefix) {
            return List.of();
        }

        @Override
        public void delete(Collection<String> locations) {
            throw new AssertionError("nothing to delete: " + locations);
        }

        @Override
        public Prefixes reach(List<Management.StorageProfile> profiles) {
            return TestReach.everyProfile(profiles);
        }

        @Override
        public void close() {
        }
    }

    private static String error(String message, String type, int code) {
        return JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", message).put("type", type)
                .put("code", code)).toString();
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
