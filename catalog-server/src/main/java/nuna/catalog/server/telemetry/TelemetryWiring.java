package nuna.catalog.server.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The server's telemetry (docs/observability/README.md): Boot exports metrics, traces and logs over OTLP when an
 * endpoint is set ({@link MetricsExportNeedsEndpoint}); this adds the request id every response, audit event and log
 * line carries, and sends log lines over OTLP as well as to stdout.
 */
@Configuration(proxyBeanMethods = false)
class TelemetryWiring {

    /** Boot's server observation filter, which starts each request's trace, runs at this order. */
    static final int OBSERVATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Bean
    FilterRegistrationBean<RequestIds> requestIds(Tracer tracer) {
        FilterRegistrationBean<RequestIds> registration = new FilterRegistrationBean<>(new RequestIds(tracer));
        // After the trace starts, and before authentication, whose refusals are audited with the id.
        registration.setOrder(OBSERVATION_FILTER_ORDER + 1);
        return registration;
    }

    @Bean
    @ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
    OtlpLogs otlpLogs(OpenTelemetry openTelemetry) {
        return new OtlpLogs(openTelemetry);
    }

    /**
     * Every log line from the moment the server is wired, to the OTLP logs exporter through Boot's OpenTelemetry,
     * with the request id; the record's trace and span come from the current trace. Removed again on shutdown.
     */
    static final class OtlpLogs implements DisposableBean {

        private final OpenTelemetryAppender appender = new OpenTelemetryAppender();
        private final Logger root;

        OtlpLogs(OpenTelemetry openTelemetry) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            appender.setContext(context);
            appender.setName("OTLP");
            appender.setCaptureMdcAttributes(RequestIds.MDC_KEY);
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
}
