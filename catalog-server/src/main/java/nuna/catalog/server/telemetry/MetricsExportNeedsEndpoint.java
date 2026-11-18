package nuna.catalog.server.telemetry;

import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Exports metrics over OTLP only when an endpoint is set, as Boot already does for traces and logs. Boot's own default
 * pushes them to localhost:4318 every minute, collector or not, and a test, the demo or a laptop has none. The
 * endpoint is {@code management.otlp.metrics.export.url}, OTEL_EXPORTER_OTLP_METRICS_ENDPOINT or
 * OTEL_EXPORTER_OTLP_ENDPOINT; {@code management.otlp.metrics.export.enabled} (or OTEL_METRICS_EXPORTER), when set
 * anywhere, decides instead. The worker has the same rule (nuna.maintenance.Telemetry).
 */
public final class MetricsExportNeedsEndpoint implements EnvironmentPostProcessor, Ordered {

    static final String ENABLED = "management.otlp.metrics.export.enabled";
    private static final List<String> ENDPOINTS = List.of("management.otlp.metrics.export.url",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (ENDPOINTS.stream().noneMatch(environment::containsProperty)) {
            // Last: below every property source, so any explicit setting wins.
            environment.getPropertySources().addLast(new MapPropertySource("nunaMetricsExportNeedsEndpoint",
                    Map.of(ENABLED, "false")));
        }
    }

    /** After the configuration files are read, so an endpoint set in one counts. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
