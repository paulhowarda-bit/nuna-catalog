package nuna.catalog.server.tracing;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The timers the observed calls make, as they are exported (docs/observability/spans.md). Public for the telemetry
 * tests, which load it beside Boot's metrics auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class TracingWiring {

    /**
     * Histogram buckets for the audit publish timer ({@value ObservedPublisher#NAME}) at
     * {@link ObservedPublisher#BUCKETS} (backlog 71): over OTLP it arrives with them, so Prometheus has its
     * {@code _bucket} series and Grafana a p99. A meter filter bean: Boot adds it to every meter registry it
     * configures, the exporting one included. A registry that already gives the timer buckets of its own keeps them:
     * the health page's does, with the same bounds, through a filter of its own that runs first.
     */
    @Bean
    MeterFilter auditPublishBuckets() {
        DistributionStatisticConfig buckets = DistributionStatisticConfig.builder()
                .serviceLevelObjectives(ObservedPublisher.BUCKETS.stream().mapToDouble(Duration::toNanos).toArray())
                .build();
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                return id.getName().equals(ObservedPublisher.NAME) ? config.merge(buckets) : config;
            }
        };
    }
}
