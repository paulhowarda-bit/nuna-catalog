package nuna.catalog.server.telemetry;

import static nuna.catalog.server.telemetry.OtlpReceiver.attribute;
import static nuna.catalog.server.telemetry.OtlpReceiver.attributeValues;
import static nuna.catalog.server.telemetry.OtlpReceiver.hex;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.server.AuditSinkForTests;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.PostgresForTests;
import nuna.catalog.server.S3ForTests;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server's telemetry against a small OTLP receiver standing in for a collector (backlog 38): a REST call's span
 * and metric arrive; log lines go to stdout as JSON and over OTLP, each carrying the trace, span and request ids; the
 * request id is the client's {@code X-Request-ID} or else the trace id, and the response, the audit event and the STS
 * session carry the same one; every response carries it; and no metric label or span attribute names a person or
 * holds a filter value. The endpoints are set the way a deployment sets them, before the server starts, so the
 * metrics export is switched on by its endpoint ({@link MetricsExportNeedsEndpoint}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.otlp.metrics.export.url=${telemetry-test.otlp}/v1/metrics",
        "management.otlp.metrics.export.step=1s",
        "management.opentelemetry.tracing.export.otlp.endpoint=${telemetry-test.otlp}/v1/traces",
        "management.opentelemetry.tracing.export.schedule-delay=100ms",
        "management.opentelemetry.logging.export.otlp.endpoint=${telemetry-test.otlp}/v1/logs",
        "management.opentelemetry.logging.export.schedule-delay=100ms",
        "management.tracing.sampling.probability=1.0"})
@Tag("heavy")
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, AuditSinkForTests.class, TelemetryTest.FailingRoute.class})
@ExtendWith(OutputCaptureExtension.class)
class TelemetryTest {

    private static final OtlpReceiver RECEIVER = OtlpReceiver.start();
    private static final Duration ARRIVAL = Duration.ofSeconds(20);
    private static final String TRACE_ID = "[0-9a-f]{32}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}}""";

    /** A route that fails as no catalog route should: the server logs it at ERROR while the request runs. */
    @RestController
    static class FailingRoute {

        static final String PATH = "/telemetry-test/fails";

        @GetMapping(PATH)
        String fails() {
            throw new IllegalStateException("a failure only this test makes");
        }
    }

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        registry.add("telemetry-test.otlp", RECEIVER::base);
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditSinkForTests.Sink audit;

    private String namespace;

    @BeforeEach
    void freshNamespace() throws Exception {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        namespace = "telemetry_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("POST", "/v1/trading/namespaces", "{\"namespace\":[\"" + namespace + "\"]}",
                bearer("alice")).statusCode()).isEqualTo(200);
        audit.clear();
    }

    @Test
    @DisplayName("a REST call's span and its http.server.requests metric arrive over OTLP, from nuna-catalog")
    void aRestCallsSpanAndMetricArrive() throws Exception {
        HttpResponse<String> config = send("GET", "/v1/config?warehouse=trading", null, bearer("alice"));
        assertThat(config.statusCode()).isEqualTo(200);
        String id = requestId(config);

        OtlpReceiver.Received<Span> span = awaitServerSpan(id);
        assertThat(span.service()).isEqualTo("nuna-catalog");
        assertThat(span.item().getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_SERVER);
        assertThat(attribute(span.item().getAttributesList(), "uri")).hasValue("/v1/config");

        OtlpReceiver.Received<io.opentelemetry.proto.metrics.v1.Metric> metric = RECEIVER.awaitMetric(m ->
                m.item().getName().equals("http.server.requests")
                        && attributeValues(m.item(), "uri").contains("/v1/config"), ARRIVAL);
        assertThat(metric.service()).isEqualTo("nuna-catalog");
    }

    @Test
    @DisplayName("with no X-Request-ID the request id is the trace id: in the response, the audit event and the STS"
            + " session")
    void withoutAClientIdTheRequestIdIsTheTraceId() throws Exception {
        createTable("t");
        audit.clear();
        HttpResponse<String> load = send("GET", "/v1/trading/namespaces/" + namespace + "/tables/t", null,
                bearer("tess"));
        assertThat(load.statusCode()).as(load.body()).isEqualTo(200);
        String id = requestId(load);
        assertThat(id).matches(TRACE_ID);
        awaitServerSpan(id);
        assertThat(audit.events()).extracting(CatalogEvent::data).filteredOn(CredentialIssue.class::isInstance)
                .singleElement().satisfies(data -> {
                    CredentialIssue issue = (CredentialIssue) data;
                    assertThat(issue.actor().requestId()).isEqualTo(id);
                    // The session name and the nuna-request session tag both come from the request id
                    // (SessionIdentity), and CloudTrail shows both.
                    assertThat(issue.sessionName()).isEqualTo("tess_" + id);
                });

        HttpResponse<String> refused = send("GET", "/v1/trading/namespaces", null, null);
        assertThat(refused.statusCode()).isEqualTo(401);
        String refusedId = requestId(refused);
        assertThat(refusedId).matches(TRACE_ID).isNotEqualTo(id);
        awaitServerSpan(refusedId);
        assertThat(audit.authFailures()).last().satisfies(failure ->
                assertThat(failure.requestId()).isEqualTo(refusedId));

        HttpResponse<String> denied = send("GET", "/management/v1/catalogs/trading/namespaces/" + namespace
                + "/tables/t/governance", null, IssuerForTests.bearer("otto", "outsiders"));
        assertThat(denied.statusCode()).as("a management route").isEqualTo(403);
        String deniedId = requestId(denied);
        assertThat(deniedId).matches(TRACE_ID);
        awaitServerSpan(deniedId);
        assertThat(audit.denials()).last().satisfies(denial ->
                assertThat(denial.actor().requestId()).isEqualTo(deniedId));
    }

    @Test
    @DisplayName("a client's X-Request-ID of 1 to 128 characters is kept and echoed; a blank or longer one is not")
    void aClientsIdIsKeptWhenUsable() throws Exception {
        String longest = "r".repeat(128);
        for (String sent : List.of("client-req-1", longest)) {
            HttpResponse<String> refused = send("GET", "/v1/trading/namespaces", null, null, sent);
            assertThat(requestId(refused)).isEqualTo(sent);
            assertThat(audit.authFailures()).last().satisfies(failure ->
                    assertThat(failure.requestId()).isEqualTo(sent));
        }
        for (String sent : List.of("r".repeat(129), "   ")) {
            HttpResponse<String> refused = send("GET", "/v1/trading/namespaces", null, null, sent);
            String id = requestId(refused);
            assertThat(id).matches(TRACE_ID);
            assertThat(audit.authFailures()).last().satisfies(failure ->
                    assertThat(failure.requestId()).isEqualTo(id));
        }
    }

    @Test
    @DisplayName("every response carries X-Request-ID: success, 401, 404, 500, the token route and the console's files")
    void everyResponseCarriesTheId() throws Exception {
        assertThat(send("GET", "/v1/config?warehouse=trading", null, bearer("alice"))).satisfies(r -> {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(requestId(r)).matches(TRACE_ID);
        });
        assertThat(send("GET", "/v1/trading/namespaces", null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> missing = send("GET", "/v1/trading/no-such-route", null, bearer("alice"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(requestId(missing)).matches(TRACE_ID);
        HttpResponse<String> failed = send("GET", FailingRoute.PATH, null, bearer("alice"), "failed-1");
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(requestId(failed)).isEqualTo("failed-1");
        HttpResponse<String> token = send("POST", "/v1/oauth/tokens", "", null);
        assertThat(token.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(requestId(token)).matches(TRACE_ID);
        HttpResponse<String> console = send("GET", "/ui/config.json", null, null);
        assertThat(console.statusCode()).isEqualTo(200);
        assertThat(requestId(console)).matches(TRACE_ID);
    }

    @Test
    @DisplayName("a line logged while a request runs carries its trace, span and request ids, on stdout as JSON and"
            + " over OTLP")
    void logLinesCarryTheIds(CapturedOutput output) throws Exception {
        HttpResponse<String> failed = send("GET", FailingRoute.PATH, null, bearer("alice"));
        assertThat(failed.statusCode()).isEqualTo(500);
        String id = requestId(failed);
        assertThat(id).matches(TRACE_ID);
        Span span = awaitServerSpan(id).item();

        JsonNode line = Stream.of(output.getOut().split("\\R")).filter(l -> l.startsWith("{") && l.contains(id))
                .map(JSON::readTree).filter(json -> json.path("message").asString().equals("unhandled request failure"))
                .findFirst().orElseThrow(() -> new AssertionError("no JSON log line for request " + id));
        assertThat(line.path("log").path("level").asString()).isEqualTo("ERROR");
        assertThat(line.path("traceId").asString()).isEqualTo(id);
        assertThat(line.path("spanId").asString()).isEqualTo(hex(span.getSpanId()));
        assertThat(line.path("requestId").asString()).isEqualTo(id);
        assertThat(line.path("service").path("name").asString()).isEqualTo("nuna-catalog");

        OtlpReceiver.Received<LogRecord> log = RECEIVER.awaitLog(l -> hex(l.item().getTraceId()).equals(id)
                && l.item().getBody().getStringValue().equals("unhandled request failure"), ARRIVAL);
        assertThat(log.service()).isEqualTo("nuna-catalog");
        assertThat(hex(log.item().getSpanId())).isEqualTo(hex(span.getSpanId()));
        assertThat(attribute(log.item().getAttributesList(), RequestIds.MDC_KEY)).hasValue(id);
    }

    @Test
    @DisplayName("no metric label or span attribute names the principal or the end user, or holds a filter value")
    void noPersonOrFilterValueInLabelsOrAttributes() throws Exception {
        String principal = "pia-7f3a";
        String endUser = "ulla-9c2e";
        String filterValue = "424242";
        createTable("t");
        String metricsRoute = "/v1/trading/namespaces/" + namespace + "/tables/t/metrics";
        HttpResponse<String> report = send("POST", metricsRoute, """
                {"report-type":"scan-report","table-name":"%s.t","snapshot-id":1,
                 "filter":{"type":"eq","term":"id","value":%s},"schema-id":0,"projected-field-ids":[1],
                 "projected-field-names":["id"],"metrics":{}}""".formatted(namespace, filterValue), bearer(principal));
        assertThat(report.statusCode()).as(report.body()).isEqualTo(204);
        HttpResponse<String> config = send("GET", "/v1/config?warehouse=trading", null, bearer(principal));
        assertThat(config.statusCode()).isEqualTo(200);
        HttpResponse<String> onBehalf = send("GET", "/v1/config?warehouse=trading", null, bearer(principal),
                null, "X-Nuna-End-User", endUser);
        assertThat(onBehalf.statusCode()).as("not a registered engine").isEqualTo(403);

        for (HttpResponse<String> response : List.of(report, config, onBehalf)) {
            String id = requestId(response);
            awaitServerSpan(id);
        }
        RECEIVER.awaitMetric(m -> m.item().getName().equals("http.server.requests")
                && attributeValues(m.item(), "status").contains("403"), ARRIVAL);

        List<String> values = Stream.concat(
                RECEIVER.spans().stream().flatMap(s -> attributeValues(s.item())),
                RECEIVER.metrics().stream().flatMap(m -> attributeValues(m.item()))).toList();
        assertThat(values).isNotEmpty().noneMatch(v -> v.contains(principal) || v.contains(endUser)
                || v.contains(filterValue));
    }

    private void createTable(String name) throws Exception {
        HttpResponse<String> created = send("POST", "/v1/trading/namespaces/" + namespace + "/tables",
                TABLE.formatted(name), bearer("alice"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    }

    /**
     * The request's own span, the server span of its trace. The calls it makes to the store, the object store, STS
     * and Kafka have spans of their own beneath it (docs/observability/spans.md), and may arrive first.
     */
    private static OtlpReceiver.Received<Span> awaitServerSpan(String traceId) {
        return RECEIVER.awaitSpan(s -> hex(s.item().getTraceId()).equals(traceId)
                && s.item().getKind() == Span.SpanKind.SPAN_KIND_SERVER, ARRIVAL);
    }

    private static String bearer(String principal) {
        return IssuerForTests.bearer(principal, IssuerForTests.GROUP);
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue(RequestIds.HEADER)
                .orElseThrow(() -> new AssertionError("no " + RequestIds.HEADER + " on a " + response.statusCode()));
    }

    /**
     * @param token     the Authorization header, or null for none
     * @param requestId the X-Request-ID to send, or null for none
     */
    private HttpResponse<String> send(String method, String path, String body, String token, String requestId,
                                      String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (requestId != null) {
            request.header(RequestIds.HEADER, requestId);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        return send(method, path, body, token, null);
    }
}
