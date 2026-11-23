package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import nuna.catalog.server.telemetry.OtlpReceiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker's telemetry (backlog 38). With OTLP endpoints set, its metrics, a span and its log lines arrive at a
 * receiver standing in for a collector, from nuna-maintenance, and its log lines go to stdout as JSON; with none,
 * nothing exports. The worker runs as the service does, pointed at a catalog that is not there: it logs that it
 * starts scanning, and a WARN line for each part of its first scan, all failing, and its meters (backlog 40) count
 * that scan. The test makes a span of its own through the worker's ObservationRegistry; WorkerTraceTest checks the
 * worker's scans' spans (backlog 49).
 */
@ExtendWith(OutputCaptureExtension.class)
class TelemetryTest {

    private static final Duration ARRIVAL = Duration.ofSeconds(20);
    /**
     * For the worker's own JVM to start. With this class running alone it took 5.8 to 8.4 s (2026-11-19); in the heavy
     * build of that night the worker's context in this JVM took 74 s, 25 times its usual 3 s, which would put the cold
     * JVM near 3.5 minutes.
     */
    private static final Duration START = Duration.ofMinutes(5);
    private static final int TAIL = 80;
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
            Observation.createNotStarted("nuna.telemetry-test", worker.getBean(ObservationRegistry.class))
                    .observe(() -> { });

            assertThat(receiver.awaitSpan(s -> s.item().getName().equals("nuna.telemetry-test"), ARRIVAL).service())
                    .isEqualTo("nuna-maintenance");
            assertThat(receiver.awaitMetric(m -> m.item().getName().startsWith("jvm."), ARRIVAL).service())
                    .isEqualTo("nuna-maintenance");
            assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scanning "), ARRIVAL)
                    .service()).isEqualTo("nuna-maintenance");
            assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scan failure: "), ARRIVAL)
                    .service()).isEqualTo("nuna-maintenance");
            // The worker's own meters, from Boot's registry: its first scan read none of the work list's four sections.
            assertThat(receiver.awaitMetric(m -> m.item().getName().equals(WorkerMetrics.FAILURES)
                    && m.item().getSum().getDataPointsList().stream().anyMatch(point -> point.getAsDouble() == 4
                    && OtlpReceiver.attribute(point.getAttributesList(), WorkerMetrics.KIND).orElse("")
                            .equals(ScanReport.Failure.WORK_LIST.label)), ARRIVAL).service())
                    .isEqualTo("nuna-maintenance");
            OtlpReceiver.Received<io.opentelemetry.proto.metrics.v1.Metric> scan = receiver.awaitMetric(m ->
                    m.item().getName().equals(WorkerMetrics.SCAN) && m.item().getHistogram().getDataPointsList()
                            .stream().anyMatch(point -> point.getCount() == 1 && OtlpReceiver.attribute(
                                    point.getAttributesList(), WorkerMetrics.OUTCOME).orElse("")
                                    .equals(WorkerMetrics.FAILURE)), ARRIVAL);
            assertThat(scan.item().getUnit()).as("over OTLP, Micrometer's base time unit").isEqualTo("milliseconds");
            assertThat(receiver.awaitMetric(m -> m.item().getName().equals(WorkerMetrics.LAST_SUCCESS), ARRIVAL)
                    .item().getGauge().getDataPoints(0).getAsDouble()).as("no scan has succeeded").isZero();
        }
        JsonNode line = Stream.of(output.getOut().split("\\R")).filter(l -> l.startsWith("{")).map(JSON::readTree)
                .filter(json -> json.path("message").asString().startsWith("Scanning ")).findFirst()
                .orElseThrow(() -> new AssertionError("no JSON log line for the scans"));
        assertThat(line.path("service").path("name").asString()).isEqualTo("nuna-maintenance");
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
            Path log = folder.resolve("worker.log");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of(
                    "OTEL_EXPORTER_OTLP_ENDPOINT", receiver.base(),
                    "OTEL_SERVICE_NAME", "nuna-maintenance-test",
                    "OTEL_RESOURCE_ATTRIBUTES", "k8s.pod.name=worker-pod-1",
                    "OTEL_METRIC_EXPORT_INTERVAL", "1000",
                    "OTEL_BLRP_SCHEDULE_DELAY", "100"));
            Process worker = builder.start();
            try {
                // Its start has an allowance of its own, and ARRIVAL counts from there, as for the worker in this
                // JVM: beside a heavy build's tests a cold JVM and its context took over 20 s (backlog 62). The line,
                // in this test's output, says how long the start took.
                System.out.println(awaitStarted(worker, log));
                try {
                    OtlpReceiver.Received<io.opentelemetry.proto.metrics.v1.Metric> metric = receiver.awaitMetric(m ->
                            m.item().getName().startsWith("jvm."), ARRIVAL);
                    assertThat(metric.service()).isEqualTo("nuna-maintenance-test");
                    assertThat(OtlpReceiver.attribute(metric.resource().getAttributesList(), "k8s.pod.name"))
                            .hasValue("worker-pod-1");
                    assertThat(receiver.awaitLog(l -> l.item().getBody().getStringValue().startsWith("Scanning "),
                            ARRIVAL).service()).isEqualTo("nuna-maintenance-test");
                } catch (AssertionError e) {
                    throw new AssertionError(e.getMessage() + " after the worker started\n" + tail(log), e);
                }
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

    /**
     * The worker keeps Boot's metrics push off until an endpoint is set, by a rule it registers itself. The contexts
     * above cannot show it: this module's tests have the server on their class path too, and the server's rule.
     */
    @Test
    void withNoEndpointMetricsStayOff() throws IOException {
        List<String> factories = new ArrayList<>();
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            try (InputStream in = url.openStream()) {
                factories.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(factories).anyMatch(text -> text.contains(Telemetry.MetricsExportNeedsEndpoint.class.getName()));

        StandardEnvironment none = environment(Map.of());
        new Telemetry.MetricsExportNeedsEndpoint().postProcessEnvironment(none, new SpringApplication());
        assertThat(none.getProperty(Telemetry.MetricsExportNeedsEndpoint.ENABLED)).isEqualTo("false");
        StandardEnvironment set = environment(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318"));
        new Telemetry.MetricsExportNeedsEndpoint().postProcessEnvironment(set, new SpringApplication());
        assertThat(set.getProperty(Telemetry.MetricsExportNeedsEndpoint.ENABLED)).isNull();
    }

    /** Spring's line saying the worker has started, from its log; fails with the log if it exits or takes START. */
    private static String awaitStarted(Process worker, Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + START.toNanos();
        while (true) {
            boolean alive = worker.isAlive();
            Optional<String> started = lines(log).filter(line -> line.contains("Started WorkerApplication in "))
                    .findFirst();
            if (started.isPresent()) {
                return started.get();
            }
            if (!alive) {
                throw new AssertionError("the worker exited with " + worker.exitValue() + " before it started\n"
                        + tail(log));
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the worker did not start within " + START + "\n" + tail(log));
            }
            Thread.sleep(100);
        }
    }

    /** The worker's log for a failure's message, its last lines if it is long. */
    private static String tail(Path log) throws IOException {
        List<String> lines = lines(log).toList();
        int from = Math.max(0, lines.size() - TAIL);
        return "worker.log" + (from == 0 ? "" : ", the last " + TAIL + " of its " + lines.size() + " lines") + ":\n"
                + String.join("\n", lines.subList(from, lines.size()));
    }

    /** Not {@link Files#readString}: the line the worker is writing may end mid-character. */
    private static Stream<String> lines(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines();
    }

    /** An environment holding only {@code properties}: none of this machine's variables or system properties. */
    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
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
                "--nuna.maintenance.catalog-uri=http://127.0.0.1:" + closedPort() + "/",
                "--nuna.maintenance.scan-interval=PT1H",
                "--nuna.maintenance.cleanup-window.start=22:30",
                "--nuna.maintenance.cleanup-window.end=04:00",
                "--nuna.maintenance.cleanup-window.zone=Europe/London",
                "--nuna.maintenance.token.file=" + token,
                "--nuna.maintenance.storage.test.prefixes[0]=s3://telemetry-test/tables",
                // The server, on this module's test class path only, brings JDBC; the worker has no database.
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
