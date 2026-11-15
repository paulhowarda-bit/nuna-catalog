package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import keel.catalog.server.telemetry.OtlpReceiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker's telemetry (backlog 38). With OTLP endpoints set, its metrics, a span and its log lines arrive at a
 * receiver standing in for a collector, from keel-maintenance, and its log lines go to stdout as JSON; with none,
 * nothing exports. The worker runs as the service does, pointed at a catalog that is not there: it logs that it
 * starts scanning, and a WARN line for each part of its first scan, all failing. It makes no spans of its own yet (backlog 40), so the test makes one
 * through the worker's ObservationRegistry, as its code will.
 */
@ExtendWith(OutputCaptureExtension.class)
class TelemetryTest {

    private static final Duration ARRIVAL = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path folder;

    @Test
    void withACollectorMetricsSpansAndLogLinesArrive(CapturedOutput output) throws Exception {
        try (OtlpReceiver receiver = OtlpReceiver.start();
             ConfigurableApplicationContext worker = start(
                     "--management.otlp.metrics.export.url=" + receiver.url("metrics"),
                     "--management.otlp.metrics.export.step=1s",
                     "--management.opentelemetry.tracing.export.otlp.endpoint=" + receiver.url("traces"),
                     "--management.opentelemetry.tracing.export.schedule-delay=100ms",
                     "--management.opentelemetry.logging.export.otlp.endpoint=" + receiver.url("logs"),
                     "--management.opentelemetry.logging.export.schedule-delay=100ms",
                     "--management.tracing.sampling.probability=1.0")) {
            Observation.createNotStarted("keel.telemetry-test", worker.getBean(ObservationRegistry.class))
                    .observe(() -> { });

            assertThat(receiver.awaitSpan(s -> s.item().getName().equals("keel.telemetry-test"), ARRIVAL).service())
                    .isEqualTo("keel-maintenance");
            assertThat(receiver.awaitMetric(m -> m.item().getName().startsWith("jvm."), ARRIVAL).service())
                    .isEqualTo("keel-maintenance");
            assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scanning "), ARRIVAL)
                    .service()).isEqualTo("keel-maintenance");
            assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scan failure: "), ARRIVAL)
                    .service()).isEqualTo("keel-maintenance");
        }
        JsonNode line = Stream.of(output.getOut().split("\\R")).filter(l -> l.startsWith("{")).map(JSON::readTree)
                .filter(json -> json.path("message").asString().startsWith("Scanning ")).findFirst()
                .orElseThrow(() -> new AssertionError("no JSON log line for the scans"));
        assertThat(line.path("service").path("name").asString()).isEqualTo("keel-maintenance");
        assertThat(line.path("log").path("level").asString()).isEqualTo("INFO");
    }

    /**
     * What the Helm charts set, or work's platform injects: the standard OTEL_* variables alone, no Boot properties.
     * Metrics need an endpoint and no switch ({@link Telemetry.MetricsExportNeedsEndpoint}).
     */
    @Test
    void theStandardVariablesAloneTurnExportOn() throws Exception {
        try (OtlpReceiver receiver = OtlpReceiver.start()) {
            // The worker's main in a JVM of its own, on this JVM's class path (in an argument file: on Windows it is
            // longer than a command line may be).
            Path classPath = Files.writeString(folder.resolve("classpath.args"),
                    "-cp \"" + System.getProperty("java.class.path").replace('\\', '/') + "\"");
            List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
                    "@" + classPath, WorkerApplication.class.getName()));
            command.addAll(arguments());
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(folder.resolve("worker.log").toFile());
            builder.environment().putAll(java.util.Map.of(
                    "OTEL_EXPORTER_OTLP_ENDPOINT", receiver.base(),
                    "OTEL_SERVICE_NAME", "keel-maintenance-test",
                    "OTEL_RESOURCE_ATTRIBUTES", "k8s.pod.name=worker-pod-1",
                    "OTEL_METRIC_EXPORT_INTERVAL", "1000",
                    "OTEL_BLRP_SCHEDULE_DELAY", "100"));
            Process worker = builder.start();
            try {
                OtlpReceiver.Received<io.opentelemetry.proto.metrics.v1.Metric> metric = receiver.awaitMetric(m ->
                        m.item().getName().startsWith("jvm."), ARRIVAL);
                assertThat(metric.service()).isEqualTo("keel-maintenance-test");
                assertThat(OtlpReceiver.attribute(metric.resource().getAttributesList(), "k8s.pod.name"))
                        .hasValue("worker-pod-1");
                assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scanning "),
                        ARRIVAL).service()).isEqualTo("keel-maintenance-test");
            } finally {
                worker.destroyForcibly();
                worker.waitFor();
            }
        }
    }

    @Test
    void withNoEndpointNothingExports() throws Exception {
        try (ConfigurableApplicationContext worker = start()) {
            List<String> exporters = new ArrayList<>();
            // micrometer-registry-otlp is on the runtime class path only.
            if (worker.getBeansOfType(MeterRegistry.class).values().stream()
                    .anyMatch(registry -> registry.getClass().getSimpleName().equals("OtlpMeterRegistry"))) {
                exporters.add("metrics");
            }
            if (!worker.getBeansOfType(SpanExporter.class).isEmpty()) {
                exporters.add("traces");
            }
            if (!worker.getBeansOfType(LogRecordExporter.class).isEmpty()) {
                exporters.add("logs");
            }
            if (!worker.getBeansOfType(Telemetry.OtlpLogs.class).isEmpty()) {
                exporters.add("log lines");
            }
            assertThat(exporters).isEmpty();
        }
    }

    /** The worker service, its catalog a port nothing listens on, its scans an hour apart. */
    private ConfigurableApplicationContext start(String... telemetry) throws IOException {
        List<String> args = new ArrayList<>(arguments());
        args.addAll(List.of(telemetry));
        return new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private List<String> arguments() throws IOException {
        Path token = Files.writeString(folder.resolve("token"), "not-a-token");
        return List.of(
                "--keel.maintenance.catalog-uri=http://127.0.0.1:" + closedPort() + "/",
                "--keel.maintenance.scan-interval=PT1H",
                "--keel.maintenance.token.file=" + token,
                "--keel.maintenance.storage.test.prefixes[0]=s3://telemetry-test/tables",
                // The server, on this module's test class path only, brings JDBC; the worker has no database.
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
