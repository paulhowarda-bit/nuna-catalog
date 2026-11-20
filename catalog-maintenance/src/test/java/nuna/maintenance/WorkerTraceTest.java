package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import nuna.catalog.server.telemetry.OtlpReceiver;
import nuna.catalog.tests.s3.TestS3;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The worker's trace (backlog 49). The worker runs as the service does, against a catalog that exports its spans to the
 * same OTLP receiver, standing in for a collector. Its first scan is one trace: the scan's span at the root, a span for
 * each part of it beneath, and beneath those the catalog's own spans for the calls each part made, which carried the
 * scan's trace ({@code traceparent}): a work list read through the JDK's client, observed by Micrometer, and a table's
 * load through Iceberg's REST client. No attribute of the worker's spans names the table, its namespace, location or
 * catalog, or the worker's principal.
 *
 * <p>Not on AWS (backlog 91). The service builds its storage from its settings alone ({@link Storage#of}), which on AWS
 * name no keys: it signs in through the SDK's default chain, its pod's role in a deployment. In this JVM that chain is
 * the run's sign-in, the catalog servers' own identity, which the delete policy refuses deletes beneath
 * {@code tables/}, and no setting gives the in-process service the worker's role instead; changing the chain would
 * change the catalog's too. What this test checks, the scan's trace and the catalog's spans for its calls, does not
 * depend on the store, and runs on Silo and Ceph; the worker's lists and deletes as its role on AWS are the other
 * tests' ({@link TestCatalog#workerStorage}).
 */
@Tag("heavy")
class WorkerTraceTest {

    private static final Duration ARRIVAL = Duration.ofSeconds(30);
    /** Every attribute a worker's span may carry: its parts' ({@link ScanSpans}) and Micrometer's HTTP client's. */
    private static final Set<String> ATTRIBUTES = Set.of(ScanSpans.PART_KEY, ScanSpans.OUTCOME, ScanSpans.EXCEPTION,
            ScanSpans.SECTION, ScanSpans.REPORT, "method", "uri", "status");

    private final TestCatalog catalog = TestCatalog.get();

    @TempDir
    Path folder;

    /** Before {@link TestCatalog#get} starts anything. */
    @BeforeAll
    static void notOnAws() {
        assumeTrue(TestS3.kindFromSystemProperty() != TestS3.Kind.AWS,
                "the in-process worker service would sign in as the run's sign-in, not the worker's role");
    }

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void aScanAndTheCatalogsSpansForItsCallsAreOneTrace() throws Exception {
        try (OtlpReceiver receiver = OtlpReceiver.start(); RESTCatalog writer = catalog.writer()) {
            TestCatalog traced = TestCatalog.traced(receiver.url("traces"));
            // A table whose policy expires snapshots, so the scan loads it.
            TableIdentifier id = TestCatalog.table(writer, "traced");
            Table table = writer.loadTable(id);
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1"));

            try (ConfigurableApplicationContext worker = worker(traced, receiver)) {
                Span scan = receiver.awaitSpan(s -> isWorker(s) && s.item().getName().equals(ScanSpans.SCAN_SPAN),
                        ARRIVAL).item();
                ByteString trace = scan.getTraceId();
                assertThat(scan.getParentSpanId().isEmpty()).as("the scan is the trace's root").isTrue();
                assertThat(attribute(scan, ScanSpans.OUTCOME)).isEqualTo(WorkerMetrics.SUCCESS);

                // The work list's tables section: the scan, its read, Micrometer's span for the JDK client's call, and
                // the catalog's span for answering it, all one trace.
                Span answered = receiver.awaitSpan(s -> isCatalog(s) && s.item().getTraceId().equals(trace)
                        && s.item().getName().equals("http get /management/v1/maintenance/work"), ARRIVAL).item();
                Span call = parent(receiver, answered);
                assertThat(call.getName()).isEqualTo("HTTP GET");
                assertThat(attribute(call, "uri")).isEqualTo("/management/v1/maintenance/work");
                Span read = parent(receiver, call);
                assertThat(read.getName()).isEqualTo("maintenance work-list");
                assertThat(read.getParentSpanId()).isEqualTo(scan.getSpanId());

                // The table's load, through Iceberg's REST client: the catalog's span is the load's child. The expiry's
                // commit loads the table again on the same route, and the catalog may end that span first, so the
                // catalog's span is found from the load rather than the load from the first such span to arrive.
                Span load = receiver.awaitSpan(s -> isWorker(s) && s.item().getTraceId().equals(trace)
                        && s.item().getName().equals("maintenance table-load"), ARRIVAL).item();
                assertThat(attribute(load, ScanSpans.OUTCOME)).isEqualTo("loaded");
                assertThat(load.getParentSpanId()).isEqualTo(scan.getSpanId());
                receiver.awaitSpan(s -> isCatalog(s) && s.item().getTraceId().equals(trace)
                        && s.item().getParentSpanId().equals(load.getSpanId())
                        && s.item().getName().startsWith("http get /v1/")
                        && s.item().getName().endsWith("/tables/{table}"), ARRIVAL);

                // A report of the scan, and the catalog's span for keeping it.
                Span reported = receiver.awaitSpan(s -> isCatalog(s) && s.item().getTraceId().equals(trace)
                        && s.item().getName().equals("http put /management/v1/maintenance/scans/{id}"), ARRIVAL)
                        .item();
                Span put = parent(receiver, reported);
                assertThat(attribute(put, "uri")).as("the route, not the scan's id")
                        .isEqualTo("/management/v1/maintenance/scans/{id}");
                Span report = parent(receiver, put);
                assertThat(report.getName()).isEqualTo("maintenance report");
                assertThat(attribute(report, ScanSpans.OUTCOME)).isEqualTo("kept");

                List<Span> mine = receiver.spans().stream().filter(s -> isWorker(s)
                        && s.item().getTraceId().equals(trace)).map(OtlpReceiver.Received::item).toList();
                assertThat(mine).extracting(Span::getName).contains("maintenance work-list", "maintenance table-load",
                        "maintenance snapshot-expiry", "maintenance report", "HTTP GET", "HTTP PUT");
                assertThat(mine).filteredOn(span -> span.getName().equals("maintenance work-list"))
                        .extracting(span -> attribute(span, ScanSpans.SECTION))
                        .contains("tables", "purges", "deletions");
                assertThat(mine).filteredOn(span -> span.getName().equals("maintenance report"))
                        .extracting(span -> attribute(span, ScanSpans.REPORT)).contains("scan-start", "scan-end");
                List<String> named = List.of(id.name(), id.namespace().level(0), table.uuid().toString(),
                        table.location(), TestCatalog.CATALOG, TestCatalog.WORKER);
                for (Span span : mine) {
                    assertThat(span.getAttributesList()).as(span.getName()).extracting(KeyValue::getKey)
                            .isSubsetOf(ATTRIBUTES);
                    assertThat(OtlpReceiver.attributeValues(span).toList()).as(span.getName())
                            .noneMatch(value -> named.stream().anyMatch(value::contains));
                }
            }
        }
    }

    private static boolean isWorker(OtlpReceiver.Received<Span> span) {
        return "nuna-maintenance".equals(span.service());
    }

    private static boolean isCatalog(OtlpReceiver.Received<Span> span) {
        return "nuna-catalog".equals(span.service());
    }

    /** The span's parent, from the worker: every catalog span here answers a call of the worker's. */
    private static Span parent(OtlpReceiver receiver, Span child) {
        Predicate<OtlpReceiver.Received<Span>> parent = s -> isWorker(s)
                && s.item().getTraceId().equals(child.getTraceId())
                && s.item().getSpanId().equals(child.getParentSpanId());
        return receiver.awaitSpan(parent, ARRIVAL).item();
    }

    private static String attribute(Span span, String key) {
        return OtlpReceiver.attribute(span.getAttributesList(), key).orElse(null);
    }

    /**
     * The worker service, signing in with a token file, its storage identity the tests', exporting its spans to the
     * receiver and keeping every trace; its first scan starts at once, the next an hour later.
     */
    private ConfigurableApplicationContext worker(TestCatalog traced, OtlpReceiver receiver) throws IOException {
        Path token = Files.writeString(folder.resolve("token"), catalog.workerTokens().token());
        WorkerSettings.StorageProfile store = catalog.storageProfiles().get("test");
        List<String> args = new ArrayList<>(List.of(
                "--nuna.maintenance.catalog-uri=" + traced.uri,
                "--nuna.maintenance.scan-interval=PT1H",
                "--nuna.maintenance.cleanup-window.start=22:30",
                "--nuna.maintenance.cleanup-window.end=04:00",
                "--nuna.maintenance.cleanup-window.zone=Europe/London",
                "--nuna.maintenance.token.file=" + token,
                "--nuna.maintenance.storage.test.prefixes[0]=" + store.prefixes().getFirst(),
                "--nuna.maintenance.storage.test.region=" + store.region(),
                "--nuna.maintenance.storage.test.path-style-access=" + store.pathStyleAccess(),
                "--management.opentelemetry.tracing.export.otlp.endpoint=" + receiver.url("traces"),
                "--management.opentelemetry.tracing.export.schedule-delay=100ms",
                "--management.tracing.sampling.probability=1.0",
                // The server, on this module's test class path only, brings JDBC; the worker has no database.
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"));
        if (store.endpoint() != null) {
            args.add("--nuna.maintenance.storage.test.endpoint=" + store.endpoint());
        }
        if (store.accessKeyId() != null) {
            args.add("--nuna.maintenance.storage.test.access-key-id=" + store.accessKeyId());
            args.add("--nuna.maintenance.storage.test.secret-access-key=" + store.secretAccessKey());
        }
        return new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }
}
