package nuna.catalog.server.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.S3ForTests;
import nuna.catalog.server.governance.GovernanceServer;
import nuna.catalog.server.telemetry.RequestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A request's span tree, from an in-memory span exporter on the server with PostgreSQL, the run's S3 store, its STS
 * and a Kafka broker (backlog 41): beneath a commit's server span, the object store's write, the store's pointer move,
 * STS and the credential issue's publish to Kafka; beneath a load's, the store's reads and the read audit's publish.
 * No attribute of these spans names a person, a table, a location or a credential.
 */
@GovernanceServer
@TestPropertySource(properties = {
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.schedule-delay=100ms"})
@Tag("heavy")
class SpanTreeTest {

    private static final Duration ARRIVAL = Duration.ofSeconds(20);
    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}}""";
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"k":"v"}}]}""";
    /** The keys each kind of call may carry: each value comes from a small fixed set. */
    private static final Map<String, Set<String>> KEYS = Map.of(
            "store ", Set.of("backend", "operation", Observed.OUTCOME, Observed.EXCEPTION),
            "objectstore ", Set.of("profile", "operation", Observed.OUTCOME, Observed.EXCEPTION),
            "sts ", Set.of("profile", "access", Observed.OUTCOME, Observed.EXCEPTION),
            "audit ", Set.of("topic", "event", Observed.OUTCOME, Observed.EXCEPTION));

    /** The span exporter Boot adds to its own, behind its batch processor. */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryExporter {

        @Bean
        SpanRecorder spanRecorder() {
            return new SpanRecorder();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private SpanRecorder exported;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${local.server.port}")
    private int port;

    private String namespace;

    @BeforeEach
    void freshNamespace() throws Exception {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        namespace = "spans_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/v1/trading/namespaces",
                "{\"namespace\":[\"" + namespace + "\"]}", "alice");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("a commit: beneath the server span, the object store's write, then the store's pointer move, then STS"
            + " and the credential issue's publish to Kafka")
    void aCommitsSpanTree() throws Exception {
        create("t", "alice");
        // Bob has had no credentials for this table, so the commit's response asks STS and audits the issue.
        HttpResponse<String> commit = send("POST", table("t"), SET_PROPERTY, "bob");
        assertThat(commit.statusCode()).as(commit.body()).isEqualTo(200);
        Trace trace = await(commit);

        SpanData write = trace.one("objectstore write");
        assertThat(attributes(write)).containsEntry("profile", S3ForTests.PROFILE).containsEntry("operation", "write")
                .containsEntry(Observed.OUTCOME, "written").containsEntry(Observed.EXCEPTION, "none");
        SpanData pointerMove = trace.one("store commit");
        assertThat(attributes(pointerMove)).containsEntry("backend", "postgresql").containsEntry("operation", "commit")
                .containsEntry(Observed.OUTCOME, "committed").containsEntry(Observed.EXCEPTION, "none");
        assertThat(write.getEndEpochNanos()).as("the metadata is written before the pointer moves")
                .isLessThanOrEqualTo(pointerMove.getStartEpochNanos());
        SpanData assumeRole = trace.one("sts assume-role");
        assertThat(attributes(assumeRole)).containsEntry("profile", S3ForTests.PROFILE)
                .containsEntry("access", "read-write").containsEntry(Observed.OUTCOME, "issued");
        assertThat(assumeRole.getStartEpochNanos()).isGreaterThanOrEqualTo(pointerMove.getEndEpochNanos());
        SpanData publish = trace.one("audit publish");
        assertThat(attributes(publish)).containsEntry("topic", "nuna.catalog.audit")
                .containsEntry("event", "nuna.catalog.credential-issue").containsEntry(Observed.OUTCOME, "acknowledged");
        assertThat(publish.getStartEpochNanos()).as("credentials are audited before they are handed out")
                .isGreaterThanOrEqualTo(assumeRole.getEndEpochNanos());
        assertThat(trace.beneath("store ")).as("the store's reads, each with its outcome").hasSizeGreaterThan(1)
                .allSatisfy(span -> assertThat(attributes(span)).containsKey(Observed.OUTCOME));
    }

    @Test
    @DisplayName("a load: beneath the server span, the store's reads and the read audit's publish to Kafka")
    void aLoadsSpanTree() throws Exception {
        create("t", "alice");
        HttpResponse<String> load = send("GET", table("t"), null, "alice");
        assertThat(load.statusCode()).as(load.body()).isEqualTo(200);
        Trace trace = await(load);

        assertThat(trace.beneath("audit publish")).filteredOn(span -> "nuna.catalog.read"
                .equals(attributes(span).get("event"))).singleElement().satisfies(span ->
                assertThat(attributes(span)).containsEntry("topic", "nuna.catalog.audit")
                        .containsEntry(Observed.OUTCOME, "acknowledged").containsEntry(Observed.EXCEPTION, "none"));
        assertThat(trace.beneath("store readPointer")).isNotEmpty().allSatisfy(span -> assertThat(attributes(span))
                .containsEntry("backend", "postgresql").containsEntry(Observed.OUTCOME, "found"));
    }

    @Test
    @DisplayName("no attribute of a store, object store, STS or Kafka span names a person, a namespace or table, a"
            + " location or a credential, and each carries only its own keys")
    void noPersonPathOrCredentialInAttributes() throws Exception {
        String creator = "pia-7f3a";
        String committer = "cato-41b9";
        String name = "ledger_" + UUID.randomUUID().toString().substring(0, 6);
        HttpResponse<String> created = create(name, creator);
        HttpResponse<String> commit = send("POST", table(name), SET_PROPERTY, committer);
        assertThat(commit.statusCode()).as(commit.body()).isEqualTo(200);
        HttpResponse<String> load = send("GET", table(name), null, committer);
        assertThat(load.statusCode()).as(load.body()).isEqualTo(200);
        List<String> forbidden = Stream.of(creator, committer, name, namespace, "s3://", S3ForTests.bucket(),
                credential(load, "s3.access-key-id"), credential(load, "s3.secret-access-key"),
                credential(load, "s3.session-token")).toList();

        List<SpanData> calls = Stream.of(created, commit, load).flatMap(response -> await(response).spans().stream())
                .filter(span -> KEYS.keySet().stream().anyMatch(span.getName()::startsWith)).toList();
        assertThat(calls).extracting(SpanData::getName).contains("store commit", "objectstore write",
                "sts assume-role", "audit publish");
        for (SpanData span : calls) {
            Set<String> allowed = KEYS.entrySet().stream().filter(kind -> span.getName().startsWith(kind.getKey()))
                    .findFirst().orElseThrow().getValue();
            assertThat(attributes(span).keySet()).as(span.getName()).isSubsetOf(allowed);
            List<String> values = Stream.concat(Stream.of(span.getName(), span.getStatus().getDescription()),
                    Stream.concat(span.getAttributes().asMap().values().stream(), span.getEvents().stream()
                            .flatMap(event -> event.getAttributes().asMap().values().stream()))
                            .map(String::valueOf)).toList();
            assertThat(values).as(span.getName()).noneMatch(value -> forbidden.stream().anyMatch(value::contains));
        }
    }

    /** A request's spans, once its server span has arrived; the server span ends after every call it made. */
    private Trace await(HttpResponse<String> response) {
        String traceId = response.headers().firstValue(RequestIds.HEADER).orElseThrow();
        long deadline = System.nanoTime() + ARRIVAL.toNanos();
        while (true) {
            List<SpanData> spans = exported.spans().stream().filter(span -> span.getTraceId().equals(traceId))
                    .toList();
            List<SpanData> server = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
            if (!server.isEmpty()) {
                assertThat(server).as("one server span").hasSize(1);
                return new Trace(server.getFirst(), spans);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no server span for trace " + traceId + " in " + ARRIVAL);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** One request's trace: its server span and every span in it. */
    private record Trace(SpanData server, List<SpanData> spans) {

        /** The spans beneath the server span, at any depth, whose name starts with {@code name}. */
        List<SpanData> beneath(String name) {
            Map<String, SpanData> byId = new HashMap<>();
            spans.forEach(span -> byId.put(span.getSpanId(), span));
            return spans.stream().filter(span -> span.getName().startsWith(name)).filter(span -> {
                SpanData parent = byId.get(span.getParentSpanId());
                while (parent != null && parent != server) {
                    parent = byId.get(parent.getParentSpanId());
                }
                return parent == server;
            }).toList();
        }

        /** The one span of this name beneath the server span. */
        SpanData one(String name) {
            List<SpanData> named = beneath(name).stream().filter(span -> span.getName().equals(name)).toList();
            assertThat(named).as("spans named %s beneath the server span, among %s", name,
                    spans.stream().map(SpanData::getName).toList()).hasSize(1);
            return named.getFirst();
        }
    }

    private static Map<String, String> attributes(SpanData span) {
        Map<String, String> attributes = new HashMap<>();
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));
        return attributes;
    }

    private static String credential(HttpResponse<String> load, String key) {
        return JsonPath.read(load.body(), "$.config['" + key + "']");
    }

    private HttpResponse<String> create(String name, String principal) throws Exception {
        HttpResponse<String> created = send("POST", "/v1/trading/namespaces/" + namespace + "/tables",
                TABLE.formatted(name), principal);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        return created;
    }

    private String table(String name) {
        return "/v1/trading/namespaces/" + namespace + "/tables/" + name;
    }

    private HttpResponse<String> send(String method, String path, String body, String principal) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", IssuerForTests.bearer(principal, IssuerForTests.GROUP));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
