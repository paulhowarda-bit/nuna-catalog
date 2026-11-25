package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.Securable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The catalog's audit log reaches the audit table writer (D23, amended 2026-11-23): a record the catalog writes to its
 * log {@code nuna.audit} goes, through the server's own log wiring and Boot's OTLP log exporter with a logs endpoint
 * and the token header set as the demo and the charts set them, to the writer's OTLP receiver, which takes it; the
 * catalog's other log lines it leaves. Run on the telemetry auto-configuration and the server's wiring alone.
 */
class AuditLogToWriterTest {

    private static final String TOKEN = "an-audit-token-of-at-least-32-characters";

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservationAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
            OpenTelemetryTracingAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
            OpenTelemetryLoggingAutoConfiguration.class, OtlpLoggingAutoConfiguration.class})
    static class Telemetry {
    }

    @Test
    void theCatalogsAuditLogReachesTheWriterAndNothingElseDoes() throws Exception {
        LogWriter writer = new LogWriter(() -> {
            throw new AssertionError("no commit in this test");
        }, "nuna-audit-tables", Duration.ofHours(1), 1_000, Duration.ofMinutes(15), Clock.systemUTC());
        CatalogEvent issue = CatalogEvent.create(URI.create("urn:nuna:test"), Instant.now(), new CredentialIssue(
                new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "lake", List.of("sales", "orders")), "s3",
                "s3://lake/orders/", CredentialIssue.Access.READ, "nuna-reader", Instant.now().plusSeconds(1800),
                new Actor("reader", null, null, "req-1")));

        try (OtlpLogsReceiver receiver = OtlpLogsReceiver.start(0, TOKEN, writer);
             ConfigurableApplicationContext context = new SpringApplicationBuilder(Telemetry.class,
                     // The server's log wiring is package-private in its own package.
                     Class.forName("nuna.catalog.server.telemetry.TelemetryWiring"))
                     .web(WebApplicationType.NONE)
                     .run("--management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:" + receiver.port()
                                     + "/v1/logs",
                             "--management.opentelemetry.logging.export.otlp.headers.X-Nuna-Audit-Token=" + TOKEN,
                             "--management.opentelemetry.logging.export.schedule-delay=100ms")) {
            LoggerFactory.getLogger("nuna.catalog.server.SomethingElse").info("a line that is no audit record");
            LoggerFactory.getLogger("nuna.audit").info("{}", new String(EventCodec.encode(issue), StandardCharsets.UTF_8));

            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (writer.waiting() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }

        assertThat(writer.waiting()).as("the audit record, and only it").isEqualTo(1);
        assertThat(writer.offer(List.of(new LogWriter.Logged(issue, java.util.Optional.empty()))))
                .as("it was the credential issue")
                .isEqualTo(new LogWriter.Taken(0, 1, 0, 0));
    }
}
