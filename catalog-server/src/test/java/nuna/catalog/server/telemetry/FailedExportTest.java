package nuna.catalog.server.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An export that fails never fails or slows a request (backlog 38): spans and log records wait in bounded queues that
 * drop when full, and metrics go out on a thread of their own. The collector here takes connections and never
 * answers, the slowest way to fail, and the queues hold 8, so they are full after the first few requests. Run on the
 * server's telemetry and web auto-configuration alone, without a database.
 */
class FailedExportTest {

    private static final Logger log = LoggerFactory.getLogger(FailedExportTest.class);
    private static final int REQUESTS = 300;
    /** Each export's timeout, shorter than the default 10 s so that closing the server waits less. */
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(4);

    @RestController
    static class Route {

        @GetMapping("/export-test")
        ResponseEntity<Void> answer() {
            log.info("a line for the logs exporter");
            return ResponseEntity.noContent().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
            OtlpMetricsExportAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
            OpenTelemetryLoggingAutoConfiguration.class, OtlpLoggingAutoConfiguration.class,
            MicrometerTracingAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
            OtlpTracingAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            WebMvcObservationAutoConfiguration.class})
    @Import({TelemetryWiring.class, Route.class})
    static class Server {
    }

    @Test
    void aCollectorThatNeverAnswersSlowsAndFailsNoRequest() throws Exception {
        // Connections wait in the backlog, accepted by the operating system and never answered.
        try (ServerSocket collector = new ServerSocket(0, 1000, InetAddress.getLoopbackAddress());
             ConfigurableApplicationContext server = new SpringApplicationBuilder(Server.class).run(
                     "--server.port=0",
                     "--management.otlp.metrics.export.url=" + endpoint(collector, "metrics"),
                     "--management.otlp.metrics.export.step=1s",
                     // Off the request path; shortened only so that closing the server, which publishes the
                     // metrics once more, waits less.
                     "--management.otlp.metrics.export.read-timeout=1s",
                     "--management.opentelemetry.tracing.export.otlp.endpoint=" + endpoint(collector, "traces"),
                     "--management.opentelemetry.tracing.export.schedule-delay=50ms",
                     "--management.opentelemetry.tracing.export.max-queue-size=8",
                     "--management.opentelemetry.tracing.export.otlp.timeout=" + EXPORT_TIMEOUT.toMillis() + "ms",
                     "--management.opentelemetry.logging.export.otlp.endpoint=" + endpoint(collector, "logs"),
                     "--management.opentelemetry.logging.export.schedule-delay=50ms",
                     "--management.opentelemetry.logging.export.max-queue-size=8",
                     "--management.opentelemetry.logging.export.otlp.timeout=" + EXPORT_TIMEOUT.toMillis() + "ms",
                     "--management.tracing.sampling.probability=1.0")) {
            int port = ((WebServerApplicationContext) server).getWebServer().getPort();
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/export-test"))
                    .build();
            Duration slowest = Duration.ZERO;
            for (int i = 0; i < REQUESTS; i++) {
                long start = System.nanoTime();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                Duration took = Duration.ofNanos(System.nanoTime() - start);
                slowest = took.compareTo(slowest) > 0 ? took : slowest;
                assertThat(response.statusCode()).isEqualTo(204);
                assertThat(response.headers().firstValue(RequestIds.HEADER)).isPresent();
            }
            // An export blocking a request would hold it for the exporter's timeout.
            assertThat(slowest).isLessThan(EXPORT_TIMEOUT.dividedBy(2));
        }
    }

    private static String endpoint(ServerSocket collector, String signal) {
        return "http://127.0.0.1:" + collector.getLocalPort() + "/v1/" + signal;
    }
}
