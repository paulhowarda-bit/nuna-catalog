package nuna.catalog.server.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.server.rest.ServerAlerts;
import nuna.catalog.server.tracing.Observed;
import nuna.catalog.server.tracing.ObservedPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The figures of the health page, read from {@link HealthMeters} as flat named counts that only grow, so that two
 * readings subtract and the readings of several servers add up. {@link #shape} turns a set of them into the page's
 * answer. The names (a count of what each says, since the server started):
 *
 * <ul>
 *   <li>{@code alerts.metadata-integrity}, {@code alerts.regulated-schema-change}, {@code alerts.large-metadata}: the
 *       alerts fired (catalog-metrics.md, "The alerts").</li>
 *   <li>{@code audit.acknowledged}, with {@code audit.acknowledged.ms} their time in all and
 *       {@code audit.acknowledged.le.<ms>} those acknowledged within each bound; {@code audit.failed}: the
 *       synchronous audit records published to Kafka (spans.md, {@code nuna.audit.publish}).</li>
 *   <li>{@code vends.<outcome>}, and {@code vends.failed.<error type>}: credential vends (catalog-metrics.md).</li>
 *   <li>{@code load}, {@code not-modified}, {@code commit}, each with {@code .le.<ms>} at the SLO bounds: loads
 *       answered 200, loads answered 304, and commits ({@code updateTable}, {@code commitTransaction}) answered
 *       200.</li>
 * </ul>
 */
final class Figures {

    static final String LOAD = "load";
    static final String NOT_MODIFIED = "not-modified";
    static final String COMMIT = "commit";
    /** What a figure's name may be, as a peer's answer is checked. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACKNOWLEDGED = "audit.acknowledged";
    /** The SLOs the page judges, with the bound each p99 must stay within (R7, M8; catalog-metrics.md). */
    private static final Map<String, Duration> SLOS = Map.of(LOAD, Duration.ofMillis(50),
            NOT_MODIFIED, Duration.ofMillis(10), COMMIT, Duration.ofMillis(300));
    private static final List<String> VEND_OUTCOMES = List.of("issued", "cached", "none", "failed");

    private Figures() {
    }

    /** What the registry has counted since the server started. */
    static Map<String, Double> read(MeterRegistry meters) {
        Map<String, Double> figures = new TreeMap<>();
        counter(meters, figures, "alerts.metadata-integrity", ServerAlerts.INTEGRITY);
        counter(meters, figures, "alerts.regulated-schema-change", ServerAlerts.REGULATED_SCHEMA);
        counter(meters, figures, "alerts.large-metadata", ServerAlerts.LARGE_METADATA);
        for (Timer timer : meters.find(ObservedPublisher.NAME).timers()) {
            if ("acknowledged".equals(timer.getId().getTag(Observed.OUTCOME))) {
                timed(figures, ACKNOWLEDGED, timer);
                add(figures, ACKNOWLEDGED + ".ms", timer.totalTime(TimeUnit.MILLISECONDS));
            } else {
                add(figures, "audit.failed", timer.count());
            }
        }
        for (Timer timer : meters.find(CatalogMetrics.VENDS).timers()) {
            String outcome = timer.getId().getTag(CatalogMetrics.OUTCOME);
            add(figures, "vends." + outcome, timer.count());
            if ("failed".equals(outcome)) {
                add(figures, "vends.failed." + timer.getId().getTag(CatalogMetrics.ERROR_TYPE), timer.count());
            }
        }
        for (Timer timer : meters.find(RequestMetrics.REQUESTS).timers()) {
            String family = family(timer.getId().getTag(CatalogMetrics.OPERATION_ID),
                    timer.getId().getTag(CatalogMetrics.OUTCOME));
            if (family != null) {
                timed(figures, family, timer);
            }
        }
        return figures;
    }

    /** Which SLO a request timer counts toward, or null for none. */
    private static String family(String operation, String outcome) {
        if ("loadTable".equals(operation)) {
            return "2xx".equals(outcome) ? LOAD : "3xx".equals(outcome) ? NOT_MODIFIED : null;
        }
        boolean commit = "updateTable".equals(operation) || "commitTransaction".equals(operation);
        return commit && "2xx".equals(outcome) ? COMMIT : null;
    }

    private static void counter(MeterRegistry meters, Map<String, Double> figures, String name, String meter) {
        for (Counter counter : meters.find(meter).counters()) {
            add(figures, name, counter.count());
        }
    }

    /** A timer's count, and its cumulative bucket counts by bound in milliseconds. */
    private static void timed(Map<String, Double> figures, String name, Timer timer) {
        add(figures, name, timer.count());
        for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
            add(figures, name + ".le." + Math.round(bucket.bucket(TimeUnit.MILLISECONDS)), bucket.count());
        }
    }

    private static void add(Map<String, Double> figures, String name, double value) {
        figures.merge(name, value, Double::sum);
    }

    /** {@code now} less {@code then}, name by name; a name {@code then} lacks counted from zero. */
    static Map<String, Double> since(Map<String, Double> now, Map<String, Double> then) {
        Map<String, Double> difference = new TreeMap<>();
        now.forEach((name, value) -> difference.put(name, value - then.getOrDefault(name, 0.0)));
        return difference;
    }

    /** The page's answer for a set of figures: one server's, or the sum of several. */
    static ObjectNode shape(Map<String, Double> figures) {
        ObjectNode out = JSON.createObjectNode();
        ObjectNode alerts = out.putObject("alerts");
        for (String kind : List.of("metadata-integrity", "regulated-schema-change", "large-metadata")) {
            alerts.put(kind, count(figures, "alerts." + kind));
        }

        ObjectNode audit = out.putObject("audit");
        long acknowledged = count(figures, ACKNOWLEDGED);
        audit.put("acknowledged", acknowledged);
        audit.put("failed", count(figures, "audit.failed"));
        if (acknowledged > 0) {
            audit.put("mean-ms", Math.round(10 * figures.getOrDefault(ACKNOWLEDGED + ".ms", 0.0) / acknowledged)
                    / 10.0);
            audit.set("p99", p99(figures, ACKNOWLEDGED, ObservedPublisher.BUCKETS));
        }

        ObjectNode vends = out.putObject("credential-vends");
        VEND_OUTCOMES.forEach(outcome -> vends.put(outcome, count(figures, "vends." + outcome)));
        ObjectNode failures = vends.putObject("failures");
        figures.forEach((name, value) -> {
            if (name.startsWith("vends.failed.") && Math.round(value) > 0) {
                failures.put(name.substring("vends.failed.".length()), Math.round(value));
            }
        });

        ObjectNode slos = out.putObject("slos");
        for (String family : List.of(LOAD, NOT_MODIFIED, COMMIT)) {
            Duration objective = SLOS.get(family);
            ObjectNode slo = slos.putObject(family);
            long count = count(figures, family);
            long within = count(figures, family + ".le." + objective.toMillis());
            slo.put("objective-ms", objective.toMillis());
            slo.put("count", count);
            slo.put("within", within);
            if (count > 0) {
                // p99 within the objective: at least 99 in 100 within it.
                slo.put("met", 100 * within >= 99 * count);
                slo.set("p99", p99(figures, family, RequestMetrics.SLO));
            }
        }
        return out;
    }

    /**
     * Where the 99th percentile lies, as far as the buckets tell: {@code at-most-ms}, the least bound that holds 99 in
     * 100 of the count, or {@code over-ms}, the greatest bound, when none does.
     */
    private static ObjectNode p99(Map<String, Double> figures, String name, List<Duration> bounds) {
        long count = count(figures, name);
        ObjectNode p99 = JSON.createObjectNode();
        for (Duration bound : bounds) {
            if (100 * count(figures, name + ".le." + bound.toMillis()) >= 99 * count) {
                return p99.put("at-most-ms", bound.toMillis());
            }
        }
        return p99.put("over-ms", bounds.getLast().toMillis());
    }

    private static long count(Map<String, Double> figures, String name) {
        return Math.round(figures.getOrDefault(name, 0.0));
    }
}
