package keel.catalog.server.health;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import keel.catalog.server.tracing.ObservedPublisher;
import java.time.Duration;
import java.util.List;

/**
 * The registry the health page reads (docs/observability/health-page.md). It is a meter registry bean of its own, so
 * Boot records every meter in it too: beside the exporting registry, through a composite, when metrics go out over
 * OTLP, or alone when nothing exports. Whatever the exporting registry does with its counts (cumulative or delta,
 * histograms that roll over every few minutes), this one keeps every count, sum and histogram bucket since the server
 * started and never expires one, so the page can read it each minute and take the difference of two readings a day
 * apart.
 *
 * <p>The audit's publish timer ({@value ObservedPublisher#NAME}) gets histogram buckets here, and only here, for the
 * page to tell its p99; the exported timer is unchanged.
 */
public final class HealthMeters extends SimpleMeterRegistry {

    /** The audit publish timer's bucket bounds in this registry. */
    static final List<Duration> AUDIT_BUCKETS = List.of(Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5));

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
                        .serviceLevelObjectives(AUDIT_BUCKETS.stream().mapToDouble(Duration::toNanos).toArray())
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
