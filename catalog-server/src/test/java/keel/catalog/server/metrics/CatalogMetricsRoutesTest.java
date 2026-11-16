package keel.catalog.server.metrics;

import static keel.catalog.server.metrics.CatalogMetrics.CATALOG;
import static keel.catalog.server.metrics.CatalogMetrics.COMMITS;
import static keel.catalog.server.metrics.CatalogMetrics.ENGINE_NAME;
import static keel.catalog.server.metrics.CatalogMetrics.ERROR_TYPE;
import static keel.catalog.server.metrics.CatalogMetrics.OPERATION;
import static keel.catalog.server.metrics.CatalogMetrics.OPERATION_ID;
import static keel.catalog.server.metrics.CatalogMetrics.OUTCOME;
import static keel.catalog.server.metrics.CatalogMetrics.REPORT_TYPE;
import static keel.catalog.server.metrics.CatalogMetrics.REQUIREMENT_TYPE;
import static keel.catalog.server.metrics.CatalogMetrics.VENDS;
import static keel.catalog.server.metrics.RequestMetrics.REQUESTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import keel.catalog.audit.CredentialIssue;
import keel.catalog.iceberg.RestRequest.MetricsSummary;
import keel.catalog.server.IssuerForTests;
import keel.catalog.server.PostgresForTests;
import keel.catalog.server.S3ForTests;
import keel.catalog.server.rest.AuditPublisher;
import keel.catalog.server.rest.Operations;
import keel.catalog.server.rest.ServerAlerts;
import keel.catalog.server.storage.StsCredentialVending;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The catalog's own meters (backlog 39), read from the server's registry after driving its routes: every call timed
 * by the spec's operationId with its outcome and error type, in SLO buckets; commits by outcome and the requirement
 * that failed; loads, 304s and the metadata cache; credential vends, new or cached; alerts; client reports by the
 * units they state; and labels that never name a namespace, table, person or filter value. Each test reads what its
 * own calls added.
 * Every loaded metadata file is "large" here, so each first load of a file alerts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "keel.rest.alert-metadata-chars=1")
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, CatalogMetricsRoutesTest.Audit.class})
class CatalogMetricsRoutesTest {

    static final AtomicBoolean AUDIT_DOWN = new AtomicBoolean();

    /** Accepts every audit event, and refuses credential issues when told to, as a broker that is down would. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Audit {

        @Bean
        @Primary
        AuditPublisher acceptingAudit() {
            return event -> {
                if (AUDIT_DOWN.get() && event.data() instanceof CredentialIssue) {
                    throw new IllegalStateException("audit broker unreachable");
                }
            };
        }
    }

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"},
              {"id":2,"name":"ssn","required":false,"type":"string"}]}}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServerAlerts alerts;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    private String namespace;
    private String table;

    @BeforeEach
    void namespaceAndTable() throws Exception {
        AUDIT_DOWN.set(false);
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        namespace = "meters_" + UUID.randomUUID().toString().substring(0, 8);
        table = "ledger_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}").statusCode()).isEqualTo(200);
        assertThat(send("POST", tables(), TABLE.formatted(table)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("every Iceberg route the server serves is named by the operationId the spec gives it")
    void everyRouteIsNamedByTheSpecsOperationId() throws IOException {
        Map<String, String> spec = specOperationIds();
        List<String> checked = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/v1/")) {
                    continue;
                }
                // The token route answers any method with its fixed error; the spec names only its POST.
                for (RequestMethod verb : methods.isEmpty() ? Set.of(RequestMethod.POST) : methods) {
                    String route = verb.name() + " " + pattern;
                    String path = pattern.replaceAll("\\{[^}]+}", "x");
                    assertThat(Operations.of(verb.name(), path)).as(route).hasValue(spec.get(route));
                    checked.add(route);
                }
            }
        });
        assertThat(checked).hasSizeGreaterThan(25).contains("GET /v1/config", "POST /v1/oauth/tokens");
        assertThat(Operations.of("GET", "/v1/trading/namespaces/a%1Fb/tables/c%2Fd")).hasValue("loadTable");
        assertThat(Operations.of("PUT", "/v1/trading/namespaces")).isEmpty();
        assertThat(Operations.of("GET", "/management/v1/engines")).isEmpty();
    }

    @Test
    @DisplayName("a call is timed by its operationId, catalog, status class and error type, in buckets at the SLOs")
    void callsAreTimedByOperationOutcomeAndErrorType() throws Exception {
        Map<List<String>, Double> before = loadTimings();
        double warehouses = timerCount("listNamespaces", CatalogMetrics.NONE, "4xx", "NoSuchWarehouseException");

        HttpResponse<String> loaded = send("GET", table(), null);
        assertThat(loaded.statusCode()).isEqualTo(200);
        String etag = loaded.headers().firstValue("ETag").orElseThrow();
        assertThat(send("GET", table(), null, "If-None-Match", etag).statusCode()).isEqualTo(304);
        assertThat(send("GET", tables() + "/ghost", null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/v1/trading" + table(), null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/nowhere/namespaces", null, IssuerForTests.bearer()).statusCode()).isEqualTo(404);

        eventually(() -> {
            Map<List<String>, Double> after = loadTimings();
            assertThat(delta(before, after, "trading", "2xx", "none")).as("200").isEqualTo(1);
            assertThat(delta(before, after, "trading", "3xx", "none")).as("304").isEqualTo(1);
            assertThat(delta(before, after, "trading", "4xx", "NoSuchTableException")).isEqualTo(1);
            assertThat(delta(before, after, "none", "4xx", "NotAuthorizedException")).as("refused at the gate")
                    .isEqualTo(1);
            assertThat(timerCount("listNamespaces", CatalogMetrics.NONE, "4xx", "NoSuchWarehouseException"))
                    .as("an unknown warehouse is no catalog").isEqualTo(warehouses + 1);
        });
        Timer loads = registry.get(REQUESTS).tags(OPERATION_ID, "loadTable", CATALOG, "trading", OUTCOME, "2xx")
                .timer();
        assertThat(Arrays.stream(loads.takeSnapshot().histogramCounts())
                .map(bucket -> bucket.bucket(TimeUnit.MILLISECONDS)))
                .containsExactly(10.0, 50.0, 300.0);
    }

    @Test
    @DisplayName("commits count by outcome, and a refused requirement by its type")
    void commitsCountByOutcomeAndTheRequirementThatFailed() throws Exception {
        Map<List<String>, Double> before = commits();
        String uuid = JsonPath.read(send("GET", table(), null).body(), "$.metadata.table-uuid");
        String key = UUID.randomUUID().toString();

        assertThat(send("POST", tables(), TABLE.formatted(table + "_2")).statusCode()).isEqualTo(200);
        assertThat(send("POST", table(), commit("", setK("1"))).statusCode()).isEqualTo(200);
        assertThat(send("POST", table(), commit("", "")).statusCode()).as("no change").isEqualTo(200);
        assertThat(send("POST", table(), commit("", setK("2")), "Idempotency-Key", key).statusCode()).isEqualTo(200);
        assertThat(send("POST", table(), commit("", setK("2")), "Idempotency-Key", key).statusCode()).isEqualTo(200);
        assertThat(send("POST", table(), commit(
                "{\"type\":\"assert-ref-snapshot-id\",\"ref\":\"main\",\"snapshot-id\":123}", setK("3")))
                .statusCode()).isEqualTo(409);
        assertThat(send("POST", table(), commit(assertUuid(UUID.randomUUID().toString()), setK("3")))
                .statusCode()).isEqualTo(409);
        assertThat(send("POST", tables() + "/ghost", commit("", setK("3"))).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/transactions/commit", transaction(assertUuid(uuid), setK("4"))).statusCode())
                .isEqualTo(204);
        assertThat(send("POST", "/transactions/commit", transaction(assertUuid(UUID.randomUUID().toString()),
                setK("5"))).statusCode()).isEqualTo(409);

        Map<List<String>, Double> after = commits();
        Map<List<String>, Double> added = new HashMap<>();
        after.forEach((labels, count) -> {
            double more = count - before.getOrDefault(labels, 0.0);
            if (more > 0) {
                added.put(labels, more);
            }
        });
        assertThat(added).containsExactlyInAnyOrderEntriesOf(Map.of(
                List.of("createTable", "committed", "none", "none"), 1.0,
                List.of("updateTable", "committed", "none", "none"), 2.0,
                List.of("updateTable", "unchanged", "none", "none"), 1.0,
                List.of("updateTable", "replayed", "none", "none"), 1.0,
                List.of("updateTable", "requirement-failed", "assert-ref-snapshot-id", "CommitFailedException"), 1.0,
                List.of("updateTable", "requirement-failed", "assert-table-uuid", "CommitFailedException"), 1.0,
                List.of("updateTable", "refused", "none", "NoSuchTableException"), 1.0,
                List.of("commitTransaction", "committed", "none", "none"), 1.0,
                List.of("commitTransaction", "requirement-failed", "assert-table-uuid", "CommitFailedException"),
                1.0));
        eventually(() -> assertThat(timerCount("updateTable", "trading", "4xx", "CommitFailedException"))
                .isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("loads read metadata through the cache, whose hits are counted")
    void loadsCountMetadataCacheHits() throws Exception {
        double hits = cache("hit");
        double misses = cache("miss");

        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);

        assertThat(cache("hit")).as("the catalog cached the file it wrote").isEqualTo(hits + 2);
        assertThat(cache("miss")).isEqualTo(misses);
    }

    @Test
    @DisplayName("credential vends are timed, and a failed vend is counted with the error the client gets")
    void vendsAreTimedAndFailuresCounted() throws Exception {
        double cached = vends("cached", "none");
        double failed = vends("failed", "ServiceUnavailableException");

        // The table's create issued its creator credentials, so the creator's load is served them from the cache.
        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);
        assertThat(vends("cached", "none")).isEqualTo(cached + 1);

        AUDIT_DOWN.set(true);
        // A new table's credentials are new, and cannot be audited: its create answers without them, its load fails.
        assertThat(send("POST", tables(), TABLE.formatted(table + "_fresh")).statusCode()).isEqualTo(200);
        HttpResponse<String> load = send("GET", tables() + "/" + table + "_fresh", null);
        assertThat(load.statusCode()).isEqualTo(503);

        assertThat(vends("failed", "ServiceUnavailableException")).isEqualTo(failed + 2);
        Timer timer = registry.get(VENDS).tags(CATALOG, "trading", OUTCOME, "issued").timer();
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
        eventually(() -> assertThat(timerCount("loadTable", "trading", "5xx", "ServiceUnavailableException"))
                .isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("a vend that mints new credentials counts as issued, and one served from the cache as cached")
    void cachedVendsAreCountedApartFromNewIssues() throws Exception {
        // Only the table's creator has credentials for it so far.
        String bob = IssuerForTests.bearer("bob-meters", IssuerForTests.GROUP);
        double issued = vends("issued", "none");
        double cached = vends("cached", "none");
        double assumed = assumeRoles();

        assertThat(call("GET", "/v1/trading" + table(), null, bob).statusCode()).isEqualTo(200);
        assertThat(vends("issued", "none")).as("Bob's first load mints").isEqualTo(issued + 1);
        assertThat(vends("cached", "none")).isEqualTo(cached);
        assertThat(assumeRoles()).as("with one AssumeRole").isEqualTo(assumed + 1);

        assertThat(call("GET", "/v1/trading" + table(), null, bob).statusCode()).isEqualTo(200);
        assertThat(vends("cached", "none")).as("his second is served from the cache").isEqualTo(cached + 1);
        assertThat(vends("issued", "none")).isEqualTo(issued + 1);
        assertThat(assumeRoles()).as("with none").isEqualTo(assumed + 1);
    }

    @Test
    @DisplayName("each alert kind is counted, large metadata once per file")
    void alertsAreCountedByKind() throws Exception {
        double large = counter(ServerAlerts.LARGE_METADATA);
        double integrity = counter(ServerAlerts.INTEGRITY);
        double regulated = counter(ServerAlerts.REGULATED_SCHEMA);

        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);
        assertThat(counter(ServerAlerts.LARGE_METADATA)).as("once per file").isEqualTo(large + 1);

        alerts.metadataIntegrity(UUID.randomUUID(), "s3://warehouse/x.metadata.json", "aa", "bb");
        alerts.regulatedSchemaChange(UUID.randomUUID(), UUID.randomUUID());
        assertThat(counter(ServerAlerts.INTEGRITY)).isEqualTo(integrity + 1);
        assertThat(counter(ServerAlerts.REGULATED_SCHEMA)).isEqualTo(regulated + 1);
    }

    @Test
    @DisplayName("client report results are aggregated by the units they state; unknown results are skipped")
    void clientReportsAreAggregatedByTheirUnits() throws Exception {
        String engine = "spark-" + UUID.randomUUID().toString().substring(0, 4);
        String commitReport = """
                {"report-type":"commit-report","table-name":"trading.%s.%s","snapshot-id":1,"sequence-number":1,
                 "operation":"append","metrics":{
                   "total-duration":{"count":1,"time-unit":"%s","total-duration":%d},
                   "attempts":{"unit":"count","value":2},
                   "added-files-size-bytes":{"unit":"bytes","value":4096},
                   "added-data-files":{"unit":"bytes","value":3},
                   "made-up-result":{"unit":"count","value":9}},
                 "metadata":{"engine-name":"%s","engine-version":"4.0"}}""";
        assertThat(send("POST", table() + "/metrics", commitReport.formatted(namespace, table, "milliseconds", 250,
                engine)).statusCode()).isEqualTo(204);
        assertThat(send("POST", table() + "/metrics", commitReport.formatted(namespace, table, "nanoseconds",
                250_000_000L, engine)).statusCode()).isEqualTo(204);
        assertThat(send("POST", table() + "/metrics", """
                {"report-type":"scan-report","table-name":"trading.%s.%s","snapshot-id":1,
                 "filter":{"type":"eq","term":"ssn","value":"123-45-6789"},
                 "schema-id":0,"projected-field-ids":[1],"projected-field-names":["id"],"metrics":{
                   "total-planning-duration":{"count":1,"time-unit":"microseconds","total-duration":1500},
                   "skipped-data-manifests":{"unit":"count","value":7}},
                 "metadata":{"engine-name":"%s"}}""".formatted(namespace, table, engine)).statusCode())
                .isEqualTo(204);

        Timer commitTime = registry.get("keel.catalog.report.total.duration")
                .tags(CATALOG, "trading", REPORT_TYPE, MetricsSummary.COMMIT, OPERATION, "append", ENGINE_NAME, engine)
                .timer();
        assertThat(commitTime.count()).isEqualTo(2);
        assertThat(commitTime.totalTime(TimeUnit.MILLISECONDS)).as("250 ms, stated in two units").isEqualTo(500);
        DistributionSummary attempts = registry.get("keel.catalog.report.attempts").tag(ENGINE_NAME, engine)
                .summary();
        assertThat(attempts.count()).isEqualTo(2);
        assertThat(attempts.totalAmount()).isEqualTo(4);
        DistributionSummary size = registry.get("keel.catalog.report.added.files.size.bytes").tag(ENGINE_NAME, engine)
                .summary();
        assertThat(size.totalAmount()).isEqualTo(8192);
        assertThat(size.getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(registry.find("keel.catalog.report.added.data.files").tag(ENGINE_NAME, engine).meters())
                .as("a count sent in bytes").isEmpty();
        assertThat(registry.find("keel.catalog.report.made.up.result").meters()).as("no such result").isEmpty();

        Timer planning = registry.get("keel.catalog.report.total.planning.duration")
                .tags(REPORT_TYPE, MetricsSummary.SCAN, OPERATION, CatalogMetrics.NONE, ENGINE_NAME, engine).timer();
        assertThat(planning.totalTime(TimeUnit.MICROSECONDS)).isEqualTo(1500);
        assertThat(registry.get("keel.catalog.report.skipped.data.manifests").tag(ENGINE_NAME, engine).summary()
                .totalAmount()).isEqualTo(7);
    }

    @Test
    @DisplayName("labels hold only the allowed names, and never a namespace, table, person, prefix or filter value")
    void labelsNameNoTableNamespacePersonOrFilterValue() throws Exception {
        String stranger = "nowhere_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("GET", table(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", table(), commit(assertUuid(UUID.randomUUID().toString()), setK("1"))).statusCode())
                .isEqualTo(409);
        assertThat(call("GET", "/v1/" + stranger + "/namespaces", null, IssuerForTests.bearer("alice-meters",
                IssuerForTests.GROUP)).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/v1/trading" + table(), null, "Bearer not-a-token").statusCode()).isEqualTo(401);
        assertThat(send("POST", table() + "/metrics", """
                {"report-type":"scan-report","table-name":"trading.%s.%s","snapshot-id":1,
                 "filter":{"type":"eq","term":"ssn","value":"123-45-6789"},
                 "schema-id":0,"projected-field-ids":[1],"projected-field-names":["id"],"metrics":{
                   "result-data-files":{"unit":"count","value":1}},
                 "metadata":{"engine-name":"trino","app-id":"alice-meters"}}""".formatted(namespace, table))
                .statusCode()).isEqualTo(204);

        eventually(() -> {
            List<Meter.Id> ids = registry.getMeters().stream().map(Meter::getId)
                    .filter(id -> id.getName().startsWith("keel.")).toList();
            assertThat(ids).extracting(Meter.Id::getName).contains(REQUESTS, COMMITS, VENDS,
                    "keel.catalog.report.result.data.files");
            List<Tag> tags = ids.stream().flatMap(id -> id.getTags().stream()).toList();
            // The label names are the catalog meters' (catalog-metrics.md). The timers of the store, object store,
            // STS and Kafka calls beneath a request (keel.store and the rest, spans.md) have their own; their values
            // are held to the same rule below.
            List<Meter.Id> catalog = ids.stream().filter(id -> id.getName().startsWith("keel.catalog.")).toList();
            // The registry's own bucket gauges carry each bucket's bound, as a histogram's buckets do in Prometheus.
            assertThat(catalog.stream().filter(id -> !id.getName().endsWith(".histogram"))
                    .flatMap(id -> id.getTags().stream())).extracting(Tag::getKey)
                    .allMatch(CatalogMetrics.LABELS::contains);
            assertThat(catalog.stream().filter(id -> id.getName().endsWith(".histogram"))
                    .flatMap(id -> id.getTags().stream())).extracting(Tag::getKey)
                    .allMatch(key -> key.equals("le") || CatalogMetrics.LABELS.contains(key));
            assertThat(tags).extracting(Tag::getValue).noneMatch(value -> Stream.of(namespace, table, stranger,
                    "alice-meters", "tester", "123-45-6789").anyMatch(value::contains));
        });
    }

    // ---- helpers ----

    /** "METHOD path" to operationId, read from the vendored spec. */
    private static Map<String, String> specOperationIds() throws IOException {
        Map<String, String> ids = new HashMap<>();
        Pattern path = Pattern.compile("^  (/v1/\\S*):$");
        Pattern verb = Pattern.compile("^    (get|post|put|head|delete):$");
        Pattern id = Pattern.compile("^      operationId: (\\w+)$");
        String currentPath = null;
        String currentVerb = null;
        for (String line : Files.readAllLines(Path.of(System.getProperty("repo.root"), "spec",
                "rest-catalog-open-api.yaml"))) {
            Matcher m;
            if ((m = path.matcher(line)).matches()) {
                currentPath = m.group(1);
                currentVerb = null;
            } else if ((m = verb.matcher(line)).matches()) {
                currentVerb = m.group(1).toUpperCase();
            } else if ((m = id.matcher(line)).matches() && currentPath != null && currentVerb != null) {
                ids.put(currentVerb + " " + currentPath, m.group(1));
            }
        }
        return ids;
    }

    /** loadTable timings by [catalog, outcome, error.type]. */
    private Map<List<String>, Double> loadTimings() {
        Map<List<String>, Double> counts = new HashMap<>();
        registry.find(REQUESTS).tag(OPERATION_ID, "loadTable").timers().forEach(timer -> counts.put(List.of(
                timer.getId().getTag(CATALOG), timer.getId().getTag(OUTCOME), timer.getId().getTag(ERROR_TYPE)),
                (double) timer.count()));
        return counts;
    }

    private static double delta(Map<List<String>, Double> before, Map<List<String>, Double> after, String... labels) {
        List<String> key = List.of(labels);
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    private double timerCount(String operation, String catalog, String outcome, String error) {
        Timer timer = registry.find(REQUESTS).tags(OPERATION_ID, operation, CATALOG, catalog, OUTCOME, outcome,
                ERROR_TYPE, error).timer();
        return timer == null ? 0 : timer.count();
    }

    /** Commits of catalog trading by [operation.id, outcome, requirement.type, error.type]. */
    private Map<List<String>, Double> commits() {
        Map<List<String>, Double> counts = new HashMap<>();
        registry.find(COMMITS).tag(CATALOG, "trading").counters().forEach(counter -> counts.put(List.of(
                counter.getId().getTag(OPERATION_ID), counter.getId().getTag(OUTCOME),
                counter.getId().getTag(REQUIREMENT_TYPE), counter.getId().getTag(ERROR_TYPE)), counter.count()));
        return counts;
    }

    private double cache(String outcome) {
        FunctionCounter counter = registry.find(MetricsWiring.CACHE).tag(OUTCOME, outcome).functionCounter();
        return counter.count();
    }

    private double vends(String outcome, String error) {
        Timer timer = registry.find(VENDS).tags(CATALOG, "trading", OUTCOME, outcome, ERROR_TYPE, error).timer();
        return timer == null ? 0 : timer.count();
    }

    /** STS AssumeRole calls, each one observation (spans.md). */
    private double assumeRoles() {
        return registry.find(StsCredentialVending.ASSUME_ROLE).timers().stream().mapToDouble(Timer::count).sum();
    }

    private double counter(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The request timer is recorded as the response leaves, which may be just after the client has it. */
    private static void eventually(ThrowingRunnable assertion) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private String tables() {
        return "/namespaces/" + namespace + "/tables";
    }

    private String table() {
        return tables() + "/" + table;
    }

    private static String commit(String requirement, String update) {
        return "{\"requirements\":[" + requirement + "],\"updates\":[" + update + "]}";
    }

    private String transaction(String requirement, String update) {
        return "{\"table-changes\":[{\"identifier\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + table
                + "\"},\"requirements\":[" + requirement + "],\"updates\":[" + update + "]}]}";
    }

    private static String setK(String value) {
        return "{\"action\":\"set-properties\",\"updates\":{\"k\":\"" + value + "\"}}";
    }

    private static String assertUuid(String uuid) {
        return "{\"type\":\"assert-table-uuid\",\"uuid\":\"" + uuid + "\"}";
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        return call(method, "/v1/trading" + path, body, IssuerForTests.bearer(), headers);
    }

    private HttpResponse<String> call(String method, String path, String body, String bearer, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
