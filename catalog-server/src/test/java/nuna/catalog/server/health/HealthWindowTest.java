package nuna.catalog.server.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.server.rest.ServerAlerts;
import nuna.catalog.server.tracing.Observed;
import nuna.catalog.server.tracing.ObservedPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * This server's figures for the health page: kept by a registry that never forgets, read every minute, and taken over
 * the last day as now's reading less the reading a day old (docs/observability/health-page.md).
 */
class HealthWindowTest {

    private static final Instant T0 = Instant.parse("2027-03-01T00:00:00Z");

    @Test
    void theRegistryKeepsEveryHistogramBucketWhereSimpleMeterRegistryLetsThemGo() {
        MockClock micrometer = new MockClock();
        HealthMeters health = new HealthMeters(micrometer);
        SimpleMeterRegistry simple = new SimpleMeterRegistry(io.micrometer.core.instrument.simple.SimpleConfig.DEFAULT,
                micrometer);
        for (MeterRegistry registry : new MeterRegistry[] {health, simple}) {
            load(registry, "2xx").record(Duration.ofMillis(4));
            load(registry, "2xx").record(Duration.ofMillis(40));
            load(registry, "2xx").record(Duration.ofMillis(400));
        }
        micrometer.add(Duration.ofMinutes(30));

        Map<String, Double> kept = Figures.read(health);
        assertThat(kept).containsEntry("load", 3.0).containsEntry("load.le.10", 1.0).containsEntry("load.le.50", 2.0)
                .containsEntry("load.le.300", 2.0);
        // Boot's own fallback registry rolls its histograms over every few minutes: the reason for HealthMeters.
        assertThat(Arrays.stream(load(simple, "2xx").takeSnapshot().histogramCounts()).mapToDouble(b -> b.count()))
                .containsOnly(0.0);
    }

    @Test
    void theAuditPublishTimerHasBucketsHereOnly() {
        HealthMeters health = new HealthMeters(new MockClock());
        Timer publish = Timer.builder(ObservedPublisher.NAME).tag(Observed.OUTCOME, "acknowledged").register(health);
        publish.record(Duration.ofMillis(7));
        publish.record(Duration.ofMillis(30));
        Timer.builder(ObservedPublisher.NAME).tag(Observed.OUTCOME, "error").register(health)
                .record(Duration.ofSeconds(3));

        Map<String, Double> figures = Figures.read(health);
        assertThat(figures).containsEntry("audit.acknowledged", 2.0).containsEntry("audit.acknowledged.le.5", 0.0)
                .containsEntry("audit.acknowledged.le.10", 1.0).containsEntry("audit.acknowledged.le.50", 2.0)
                .containsEntry("audit.failed", 1.0);
        assertThat(figures.get("audit.acknowledged.ms")).isCloseTo(37.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(new SimpleMeterRegistry().timer(ObservedPublisher.NAME).takeSnapshot().histogramCounts()).isEmpty();
    }

    @Test
    void theLastDayIsNowLessTheNewestReadingADayOld() {
        MutableClock clock = new MutableClock(T0);
        HealthMeters meters = new HealthMeters(new MockClock());
        Counter integrity = Counter.builder(ServerAlerts.INTEGRITY).register(meters);
        HealthWindow window = new HealthWindow(meters, clock);

        integrity.increment(2);
        clock.set(T0.plus(Duration.ofHours(1)));
        window.tick();
        integrity.increment(3);
        clock.set(T0.plus(Duration.ofHours(2)));
        window.tick();
        integrity.increment(4);

        HealthWindow.Window young = window.lastDay();
        assertThat(young.from()).as("younger than a day: from its start").isEqualTo(T0);
        assertThat(young.figures()).containsEntry("alerts.metadata-integrity", 9.0);

        clock.set(T0.plus(Duration.ofHours(25)).plus(Duration.ofMinutes(30)));
        HealthWindow.Window day = window.lastDay();
        assertThat(day.from()).as("the newest reading a day old or older").isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(day.to()).isEqualTo(clock.instant());
        assertThat(day.figures()).containsEntry("alerts.metadata-integrity", 7.0);

        clock.set(T0.plus(Duration.ofHours(26)));
        assertThat(window.lastDay().figures()).containsEntry("alerts.metadata-integrity", 4.0);
        assertThat(window.lastDay().from()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void theFiguresAreShapedAgainstTheSlos() {
        Map<String, Double> figures = new java.util.TreeMap<>(Map.of(
                "load", 200.0, "load.le.10", 150.0, "load.le.50", 198.0, "load.le.300", 200.0,
                "commit", 100.0, "commit.le.10", 0.0, "commit.le.50", 10.0, "commit.le.300", 98.0));
        figures.putAll(Map.of("vends.issued", 7.0, "vends.failed", 3.0, "vends.failed.ServiceUnavailableException", 2.0,
                "vends.failed.ForbiddenException", 1.0, "alerts.large-metadata", 1.0));
        figures.putAll(Map.of("audit.acknowledged", 10.0, "audit.acknowledged.ms", 55.0,
                "audit.acknowledged.le.5", 8.0, "audit.acknowledged.le.10", 9.0, "audit.acknowledged.le.25", 10.0));

        ObjectNode shaped = Figures.shape(figures);
        assertThat(shaped.at("/slos/load/met").asBoolean()).as("198 of 200 within 50 ms").isTrue();
        assertThat(shaped.at("/slos/load/p99/at-most-ms").asLong()).isEqualTo(50);
        assertThat(shaped.at("/slos/commit/met").asBoolean()).as("98 of 100 within 300 ms").isFalse();
        assertThat(shaped.at("/slos/commit/p99/over-ms").asLong()).isEqualTo(300);
        assertThat(shaped.at("/slos/not-modified/count").asLong()).isZero();
        assertThat(shaped.at("/slos/not-modified").has("met")).as("no 304s: nothing to judge").isFalse();
        assertThat(shaped.at("/credential-vends/failed").asLong()).isEqualTo(3);
        assertThat(shaped.at("/credential-vends/failures/ServiceUnavailableException").asLong()).isEqualTo(2);
        assertThat(shaped.at("/alerts/large-metadata").asLong()).isEqualTo(1);
        assertThat(shaped.at("/alerts/metadata-integrity").asLong()).isZero();
        assertThat(shaped.at("/audit/mean-ms").asDouble()).isEqualTo(5.5);
        assertThat(shaped.at("/audit/p99/at-most-ms").asLong()).isEqualTo(25);
    }

    @Test
    void vendsCountByOutcomeAndFailuresByType() {
        HealthMeters meters = new HealthMeters(new MockClock());
        vend(meters, "issued", CatalogMetrics.NONE).record(Duration.ofMillis(3));
        vend(meters, "failed", "ServiceUnavailableException").record(Duration.ofMillis(3));
        vend(meters, "failed", "ServiceUnavailableException").record(Duration.ofMillis(3));
        load(meters, "4xx").record(Duration.ofMillis(1));
        assertThat(Figures.read(meters)).containsEntry("vends.issued", 1.0).containsEntry("vends.failed", 2.0)
                .containsEntry("vends.failed.ServiceUnavailableException", 2.0).doesNotContainKey("load");
    }

    private static Timer load(MeterRegistry registry, String outcome) {
        return Timer.builder(RequestMetrics.REQUESTS).tags(CatalogMetrics.CATALOG, "trading",
                        CatalogMetrics.OPERATION_ID, "loadTable", CatalogMetrics.OUTCOME, outcome,
                        CatalogMetrics.ERROR_TYPE, CatalogMetrics.NONE)
                .serviceLevelObjectives(RequestMetrics.SLO.toArray(Duration[]::new)).register(registry);
    }

    private static Timer vend(MeterRegistry registry, String outcome, String error) {
        return Timer.builder(CatalogMetrics.VENDS).tags(CatalogMetrics.CATALOG, "trading", CatalogMetrics.OUTCOME,
                outcome, CatalogMetrics.ERROR_TYPE, error).register(registry);
    }

    /** A clock the test sets. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
