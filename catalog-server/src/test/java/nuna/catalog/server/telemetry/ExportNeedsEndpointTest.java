package nuna.catalog.server.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Export stays off, quietly, until an endpoint is set (backlog 38): the tests, the demo and a laptop have no collector.
 * With none, no OTLP meter registry, span exporter or log exporter exists, so nothing tries to reach a collector or
 * logs that it could not. Each endpoint switches its own export on; for metrics that is
 * {@link MetricsExportNeedsEndpoint}, where Boot alone would push to localhost:4318. Run on the server's telemetry
 * auto-configuration and wiring alone, without a database.
 */
class ExportNeedsEndpointTest {

    private static final OtlpReceiver RECEIVER = OtlpReceiver.start();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
            OtlpMetricsExportAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
            OpenTelemetryLoggingAutoConfiguration.class, OtlpLoggingAutoConfiguration.class,
            MicrometerTracingAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
            OtlpTracingAutoConfiguration.class})
    @Import(TelemetryWiring.class)
    static class Telemetry {
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    @Test
    void withNoEndpointNothingExports() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().getProperty(MetricsExportNeedsEndpoint.ENABLED)).isEqualTo("false");
            assertThat(exporters(context)).isEmpty();
        }
    }

    @Test
    void eachEndpointSwitchesItsExportOn() {
        try (ConfigurableApplicationContext context = start(
                "--management.otlp.metrics.export.url=" + RECEIVER.url("metrics"),
                "--management.opentelemetry.tracing.export.otlp.endpoint=" + RECEIVER.url("traces"),
                "--management.opentelemetry.logging.export.otlp.endpoint=" + RECEIVER.url("logs"))) {
            assertThat(exporters(context)).containsExactlyInAnyOrder("metrics", "traces", "logs", "log lines");
        }
    }

    @Test
    void anExplicitSettingDecidesMetrics() {
        try (ConfigurableApplicationContext context = start(
                "--management.otlp.metrics.export.url=" + RECEIVER.url("metrics"),
                "--management.otlp.metrics.export.enabled=false")) {
            assertThat(exporters(context)).isEmpty();
        }
    }

    @Test
    void theStandardVariablesCountAsAnEndpoint() {
        for (String variable : List.of("OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(variable, RECEIVER.base())));
            new MetricsExportNeedsEndpoint().postProcessEnvironment(environment, new SpringApplication());
            assertThat(environment.getProperty(MetricsExportNeedsEndpoint.ENABLED)).as(variable).isNull();
        }
        StandardEnvironment none = new StandardEnvironment();
        none.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new MetricsExportNeedsEndpoint().postProcessEnvironment(none, new SpringApplication());
        assertThat(none.getProperty(MetricsExportNeedsEndpoint.ENABLED)).isEqualTo("false");
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Telemetry.class).web(WebApplicationType.NONE).run(args);
    }

    /** Which signals the context would export, by the exporters it holds. */
    private static List<String> exporters(ConfigurableApplicationContext context) {
        List<String> found = new ArrayList<>();
        // micrometer-registry-otlp is on the runtime class path only.
        if (context.getBeansOfType(MeterRegistry.class).values().stream()
                .anyMatch(registry -> registry.getClass().getSimpleName().equals("OtlpMeterRegistry"))) {
            found.add("metrics");
        }
        if (!context.getBeansOfType(SpanExporter.class).isEmpty()) {
            found.add("traces");
        }
        if (!context.getBeansOfType(LogRecordExporter.class).isEmpty()) {
            found.add("logs");
        }
        if (!context.getBeansOfType(TelemetryWiring.OtlpLogs.class).isEmpty()) {
            found.add("log lines");
        }
        return found;
    }
}
