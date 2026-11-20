package nuna.catalog.server.health;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nuna.catalog.server.tracing.ObservedPublisher;
import nuna.catalog.server.tracing.TracingWiring;
import java.time.Duration;

/**
 * The registry the health page reads (docs/observability/health-page.md). It is a meter registry bean of its own, so
 * Boot records every meter in it too: beside the exporting registry, through a composite, when metrics go out over
 * OTLP, or alone when nothing exports. Whatever the exporting registry does with its counts (cumulative or delta,
 * histograms that roll over every few minutes), this one keeps every count, sum and histogram bucket since the server
 * started and never expires one, so the page can read it each minute and take the difference of two readings a day
 * apart.
 *
 * <p>The audit's publish timer ({@value ObservedPublisher#NAME}) gets histogram buckets here at
 * {@link ObservedPublisher#BUCKETS}, for the page to tell its p99. The exported timer has the same buckets
 * ({@link TracingWiring}). The filter giving them here is this registry's own, so it has them without Boot too.
 */
public final class HealthMeters extends SimpleMeterRegistry {

    /** Long enough that no histogram here ever rolls over: five years, as Micrometer's cumulative registries keep. */
    private static final Duration NEVER = Duration.ofDays(1825);

    HealthMeters(Clock clock) {
        super(SimpleConfig.DEFAULT, clock);
        config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals(ObservedPublisher.NAME)) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(
                                ObservedPublisher.BUCKETS.stream().mapToDouble(Duration::toNanos).toArray())
                        .build().merge(config);
            }
        });
    }

    /** One buffer that lasts five years: a cumulative histogram, which SimpleMeterRegistry's own is not. */
    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder().expiry(NEVER).bufferLength(1).build()
                .merge(DistributionStatisticConfig.DEFAULT);
    }
}
