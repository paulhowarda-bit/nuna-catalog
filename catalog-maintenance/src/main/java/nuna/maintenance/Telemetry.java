package nuna.maintenance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The worker's telemetry (docs/observability/README.md), wired as the server's is (nuna.catalog.server.telemetry),
 * which the worker does not depend on: Boot exports metrics, traces and logs over OTLP when an endpoint is set; this
 * keeps metrics export off until one is, and sends log lines over OTLP as well as to stdout when a logs endpoint is.
 */
@Configuration(proxyBeanMethods = false)
class Telemetry {

    @Bean
    @ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
    OtlpLogs otlpLogs(OpenTelemetry openTelemetry) {
        return new OtlpLogs(openTelemetry);
    }

    /** Every log line from the moment the worker is wired, to the OTLP logs exporter; removed again on shutdown. */
    static final class OtlpLogs implements DisposableBean {

        private final OpenTelemetryAppender appender = new OpenTelemetryAppender();
        private final Logger root;

        OtlpLogs(OpenTelemetry openTelemetry) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            appender.setContext(context);
            appender.setName("OTLP");
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            root.addAppender(appender);
        }

        @Override
        public void destroy() {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * Exports metrics over OTLP only when an endpoint is set, as Boot already does for traces and logs; Boot's own
     * default pushes them to localhost:4318 every minute, collector or not. The endpoint is
     * {@code management.otlp.metrics.export.url}, OTEL_EXPORTER_OTLP_METRICS_ENDPOINT or OTEL_EXPORTER_OTLP_ENDPOINT;
     * {@code management.otlp.metrics.export.enabled} (or OTEL_METRICS_EXPORTER), when set anywhere, decides instead.
     */
    public static final class MetricsExportNeedsEndpoint implements EnvironmentPostProcessor, Ordered {

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
}
