package keel.catalog.server.health;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This server's figures over the last day. Every minute it reads {@link HealthMeters}; the last day's figures are now's
 * reading less the newest reading a day old or older, so the window is a day and at most a minute more. A server
 * younger than a day counts from its start, when every figure was zero. Readings older than that are let go: about
 * 1,440 are kept.
 */
final class HealthWindow implements AutoCloseable {

    static final Duration DAY = Duration.ofDays(1);
    static final Duration EVERY = Duration.ofMinutes(1);

    /** A server's figures from {@code from} to {@code to}. */
    record Window(UUID id, String name, Instant startedAt, Instant from, Instant to, Map<String, Double> figures) {
    }

    private record Reading(Instant at, Map<String, Double> figures) {
    }

    private final MeterRegistry meters;
    private final Clock clock;
    private final UUID id = UUID.randomUUID();
    private final String name;
    private final Instant startedAt;
    /** Oldest first; the first is the baseline once the server is a day old. */
    private final List<Reading> readings = new ArrayList<>();
    private ScheduledExecutorService ticker;

    HealthWindow(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
        this.name = serverName();
        this.startedAt = clock.instant();
        readings.add(new Reading(startedAt, Map.of()));
    }

    /** Takes a reading every minute, from a thread of its own. */
    void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("keel-health-readings")
                .factory());
        ticker.scheduleAtFixedRate(this::tick, EVERY.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Takes a reading now. */
    synchronized void tick() {
        Instant now = clock.instant();
        readings.add(new Reading(now, Figures.read(meters)));
        prune(now);
    }

    /** The figures of the last day, or since the server started if that is later. */
    synchronized Window lastDay() {
        Instant now = clock.instant();
        prune(now);
        Reading baseline = readings.getFirst();
        return new Window(id, name, startedAt, baseline.at(), now, Figures.since(Figures.read(meters),
                baseline.figures()));
    }

    UUID id() {
        return id;
    }

    /** Drops the readings older than the newest one a day old or older, which stays as the baseline. */
    private void prune(Instant now) {
        Instant dayAgo = now.minus(DAY);
        while (readings.size() > 1 && !readings.get(1).at().isAfter(dayAgo)) {
            readings.removeFirst();
        }
    }

    /** The pod's name, which the Helm chart passes in, or the host's. */
    private static String serverName() {
        String pod = System.getenv("KEEL_POD_NAME");
        if (pod != null && !pod.isBlank()) {
            return pod;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unnamed";
        }
    }

    @Override
    public void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }
}
