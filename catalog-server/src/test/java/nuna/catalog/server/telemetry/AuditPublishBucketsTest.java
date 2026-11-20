package nuna.catalog.server.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import nuna.catalog.server.tracing.Observed;
import nuna.catalog.server.tracing.ObservedPublisher;
import nuna.catalog.server.tracing.ObservedStore;
import nuna.catalog.server.tracing.TracingWiring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The audit publish timer is exported with histogram buckets from 5 ms to 5 s, the console health page's, so
 * Prometheus gets {@code nuna_audit_publish_milliseconds_bucket} and Grafana its p99 (backlog 71); other timers are
 * exported as before. Run on Boot's metrics auto-configuration, the server's tracing wiring and a small OTLP receiver,
 * without a database. Beside the exporting registry is a second one, as the health page's is in the server, so Boot
 * records through a composite as it does there.
 */
class AuditPublishBucketsTest {

    /** The health page's bounds (HealthMeters), in milliseconds, the unit timers are exported in over OTLP. */
    private static final List<Double> HEALTH_PAGE_BOUNDS = List.of(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0,
            1000.0, 2500.0, 5000.0);
    /** The bucket the second registry gives the timer of its own accord. */
    private static final Duration OWN_BUCKET = Duration.ofMillis(42);

    private final OtlpReceiver receiver = OtlpReceiver.start();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
            OtlpMetricsExportAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class})
    @Import(TracingWiring.class)
    static class Metrics {

        /** Gives the audit publish timer a bucket of its own before Boot configures it, as the health page's does. */
        @Bean
        SimpleMeterRegistry ownBuckets() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                    return id.getName().equals(ObservedPublisher.NAME) ? DistributionStatisticConfig.builder()
                            .serviceLevelObjectives(OWN_BUCKET.toNanos()).build().merge(config) : config;
                }
            });
            return registry;
        }
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    @Test
    void theAuditPublishTimerArrivesWithTheHealthPagesBuckets() {
        try (ConfigurableApplicationContext context = start()) {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            Timer publish = Timer.builder(ObservedPublisher.NAME).tag(Observed.OUTCOME, "acknowledged")
                    .register(meters);
            publish.record(Duration.ofMillis(7));
            publish.record(Duration.ofMillis(30));
            publish.record(Duration.ofSeconds(3));
            Timer.builder(ObservedStore.NAME).tag(Observed.OUTCOME, "committed").register(meters)
                    .record(Duration.ofMillis(7));
        }
        // Closing the context pushes the meters.

        Metric publish = metric(ObservedPublisher.NAME);
        assertThat(publish.getUnit()).isEqualTo("milliseconds");
        HistogramDataPoint point = only(publish);
        assertThat(point.getCount()).isEqualTo(3);
        assertThat(point.getExplicitBoundsList()).isEqualTo(HEALTH_PAGE_BOUNDS);
        // 7 ms in (5, 10], 30 ms in (25, 50], 3 s in (2500, 5000], nothing past 5 s.
        assertThat(point.getBucketCountsList()).containsExactly(0L, 1L, 0L, 1L, 0L, 0L, 0L, 0L, 0L, 1L, 0L);

        assertThat(only(metric(ObservedStore.NAME)).getExplicitBoundsList()).as("another timer").isEmpty();
    }

    @Test
    void aRegistryThatGivesTheTimerBucketsOfItsOwnKeepsThem() {
        try (ConfigurableApplicationContext context = start()) {
            Timer.builder(ObservedPublisher.NAME).register(context.getBean(MeterRegistry.class))
                    .record(Duration.ofMillis(7));
            Timer own = context.getBean(SimpleMeterRegistry.class).get(ObservedPublisher.NAME).timer();
            assertThat(Arrays.stream(own.takeSnapshot().histogramCounts()).map(CountAtBucket::bucket))
                    .containsExactly((double) OWN_BUCKET.toNanos());
        }
        assertThat(only(metric(ObservedPublisher.NAME)).getExplicitBoundsList()).isEqualTo(HEALTH_PAGE_BOUNDS);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Metrics.class).web(WebApplicationType.NONE)
                .run("--management.otlp.metrics.export.url=" + receiver.url("metrics"));
    }

    private Metric metric(String name) {
        return receiver.awaitMetric(m -> m.item().getName().equals(name), Duration.ofSeconds(10)).item();
    }

    private static HistogramDataPoint only(Metric metric) {
        assertThat(metric.getHistogram().getDataPointsList()).as(metric.getName()).hasSize(1);
        return metric.getHistogram().getDataPoints(0);
    }
}
