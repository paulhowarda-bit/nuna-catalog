package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the worker tells the catalog it reaches at each scan (backlog 250), through its own storage identity
 * ({@link Storage#of}): for each prefix, under its store's kind and endpoint, whether the store let it list there, and
 * the deletes it did, the store refused and failed, each with the store's code, never a key, an object or the store's
 * message.
 *
 * <p>Needs only Java: the stores are stand-ins on the JDK's HTTP server answering as S3 does. One lets the worker list
 * and delete beneath {@code s3://lake/tables} and refuses it {@code s3://ledger-warehouse/tables} (AccessDenied), one
 * has no bucket {@code gone} (NoSuchBucket), and a third endpoint answers nothing. The catalog is a stand-in whose work
 * list holds due deletions beneath the lake's and the ledger's prefixes and which keeps each scan report.
 */
class StorageReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCESS_KEY = "AKIAWORKERREPORTTEST";
    private static final String SECRET = "worker-report-secret";
    private static final String LEDGER_KEY = "tables/ns/ledger/data/00001.parquet";
    private static final Pattern KEY = Pattern.compile("<Key>([^<]*)</Key>");

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> s3Requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scanReports = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void eachScanReportsWhatTheStoreLetTheWorkerListAndDeleteBeneathEachPrefix() throws Exception {
        URI silo = s3(StorageReportTest::silo);
        URI gone = s3(request -> error(404, "NoSuchBucket"));
        URI away;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            away = URI.create("http://127.0.0.1:" + closed.getLocalPort());
        }
        Map<String, WorkerSettings.StorageProfile> profiles = new LinkedHashMap<>();
        profiles.put("tables", profile(silo, "s3://lake/tables", "s3a://ledger-warehouse/tables"));
        profiles.put("old", profile(gone, "s3://gone"));
        profiles.put("away", profile(away, "s3://away/tables"));
        URI catalog = catalog();

        try (Worker worker = worker(catalog, Storage.of(profiles))) {
            ScanReport first = worker.runOnce();
            assertThat(first.failuresByKind).as("the ledger's deletions failed")
                    .containsEntry(ScanReport.Failure.DELETION, 1);
            assertThat(scanReports).hasSize(2);

            JsonNode started = JSON.readTree(scanReports.get(0));
            assertThat(started.path("storage")).as("one identity each kind and endpoint").hasSize(3);
            assertThat(prefix(started, "s3://lake/tables")).isEqualTo(reach(silo, "ok", null, 0, 0, 0, null));
            assertThat(prefix(started, "s3://ledger-warehouse/tables")).as("an s3a prefix, as S3FileIO reads it")
                    .isEqualTo(reach(silo, "refused", "AccessDenied", 0, 0, 0, null));
            assertThat(prefix(started, "s3://gone")).isEqualTo(reach(gone, "refused", "NoSuchBucket", 0, 0, 0, null));
            assertThat(prefix(started, "s3://away/tables"))
                    .isEqualTo(reach(away, "failed", "SdkClientException", 0, 0, 0, null));
            assertThat(s3Requests).as("one key at most, listed beneath the prefix as the delete policy reads it")
                    .anySatisfy(request -> assertThat(request).startsWith("GET /lake?").contains("max-keys=1")
                            .contains("prefix=tables%2F"));

            JsonNode ended = JSON.readTree(scanReports.get(1));
            assertThat(prefix(ended, "s3://lake/tables")).isEqualTo(reach(silo, "ok", null, 2, 0, 0, null));
            assertThat(prefix(ended, "s3://ledger-warehouse/tables"))
                    .isEqualTo(reach(silo, "refused", "AccessDenied", 0, 2, 0, "AccessDenied"));
            assertThat(prefix(ended, "s3://gone")).isEqualTo(reach(gone, "refused", "NoSuchBucket", 0, 0, 0, null));

            worker.runOnce();
            JsonNode again = JSON.readTree(scanReports.get(3));
            assertThat(prefix(again, "s3://lake/tables")).as("each scan counts its own deletes")
                    .isEqualTo(reach(silo, "ok", null, 2, 0, 0, null));
        }
        assertThat(scanReports).allSatisfy(report -> assertThat(report).doesNotContain(ACCESS_KEY, SECRET,
                LEDGER_KEY, "00001.parquet", "Access Denied", "The specified bucket does not exist"));
    }

    /** The prefix's entry in a scan report, with its store's kind and endpoint. */
    private static String prefix(JsonNode report, String location) {
        for (JsonNode store : report.path("storage")) {
            for (JsonNode prefix : store.path("prefixes")) {
                if (prefix.path("prefix").asText().equals(location)) {
                    ObjectNode entry = JSON.createObjectNode().put("kind", store.path("kind").asText())
                            .put("endpoint", store.path("endpoint").asText());
                    entry.setAll((ObjectNode) prefix.deepCopy());
                    return entry.without("prefix").toString();
                }
            }
        }
        throw new AssertionError("no " + location + " in " + report);
    }

    private static String reach(URI endpoint, String listed, String listCode, long deleted, long refused,
                                  long failed, String deleteCode) {
        ObjectNode reach = JSON.createObjectNode().put("kind", "s3").put("endpoint", endpoint.toString())
                .put("listed", listed);
        if (listCode != null) {
            reach.put("list-code", listCode);
        }
        reach.put("deleted", deleted).put("delete-refused", refused).put("delete-failed", failed);
        if (deleteCode != null) {
            reach.put("delete-code", deleteCode);
        }
        return reach.toString();
    }

    private static WorkerSettings.StorageProfile profile(URI endpoint, String... prefixes) {
        return new WorkerSettings.StorageProfile(List.of(prefixes), endpoint, "us-east-1", true, ACCESS_KEY, SECRET,
                null);
    }

    private static Worker worker(URI catalog, Storage storage) {
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
        return new Worker(new Management(catalog, tokens, HttpClient.newHttpClient(), 100),
                new Catalogs(catalog, tokens, Map.of(), TraceHeaders.NONE), storage, Compactor.NONE, clock,
                new CleanupWindow(turn, turn, ZoneOffset.UTC), Duration.ofDays(1), Duration.ofHours(2), 2,
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    /**
     * The store holding the lake and the ledger: the worker's identity lists and deletes beneath the lake's prefix; the
     * ledger's bucket refuses it every listing and every key of a delete, as a delete policy that does not cover it
     * does.
     */
    private static Answer silo(Request request) {
        if (request.path().equals("/lake") && request.method().equals("GET")) {
            return new Answer(200, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>lake</Name>\
                    <KeyCount>0</KeyCount><MaxKeys>1</MaxKeys><IsTruncated>false</IsTruncated></ListBucketResult>""");
        }
        if (request.method().equals("POST") && request.query().contains("delete")) {
            StringBuilder result = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult"
                    + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
            Matcher keys = KEY.matcher(request.body());
            while (keys.find()) {
                result.append(request.path().equals("/lake") ? "<Deleted><Key>" + keys.group(1) + "</Key></Deleted>"
                        : "<Error><Key>" + keys.group(1) + "</Key><Code>AccessDenied</Code><Message>Access Denied"
                                + "</Message></Error>");
            }
            return new Answer(200, result.append("</DeleteResult>").toString());
        }
        return error(403, "AccessDenied");
    }

    private static Answer error(int status, String code) {
        return new Answer(status, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>"
                + (status == 404 ? "The specified bucket does not exist" : "Access Denied")
                + "</Message><RequestId>1</RequestId></Error>");
    }

    private record Request(String method, String path, String query, String body) {
    }

    private record Answer(int status, String xml) {
    }

    private URI s3(Function<Request, Answer> answers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestURI().getRawQuery()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            s3Requests.add(request.method() + " " + request.path() + "?" + request.query());
            Answer answer = answers.apply(request);
            byte[] bytes = answer.xml().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /**
     * The catalog as the worker sees it: a work list of due deletions, two of a table beneath the lake's prefix and
     * two of one beneath the ledger's, none ever completed, and every report taken, each scan's kept.
     */
    private URI catalog() throws IOException {
        UUID lake = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();
        String deletions = "{\"deletions\":[" + deletion(lake, "s3://lake/tables/ns/t/data/a.parquet")
                + "," + deletion(lake, "s3://lake/tables/ns/t/data/b.parquet")
                + "," + deletion(ledger, "s3a://ledger-warehouse/" + LEDGER_KEY)
                + "," + deletion(ledger, "s3a://ledger-warehouse/tables/ns/ledger/data/00002.parquet")
                + "]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/management/v1/maintenance/work")) {
                answer(exchange, 200, String.valueOf(exchange.getRequestURI().getQuery()).contains("section=deletions")
                        ? deletions : "{}");
            } else if (path.startsWith("/management/v1/maintenance/scans/")) {
                scanReports.add(body);
                answer(exchange, 204, null);
            } else if (path.startsWith("/management/")) {
                answer(exchange, 204, null);
            } else {
                answer(exchange, 404, "{\"error\":{\"message\":\"no route\",\"type\":\"NotFound\",\"code\":404}}");
            }
        });
        server.start();
        servers.add(server);
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/");
    }

    private static String deletion(UUID object, String location) {
        return "{\"catalog\":\"lakehouse\",\"id\":\"" + UUID.randomUUID() + "\",\"object-id\":\"" + object
                + "\",\"location\":\"" + location + "\",\"not-before\":\"2026-01-01T00:00:00Z\",\"refs\":[]}";
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
