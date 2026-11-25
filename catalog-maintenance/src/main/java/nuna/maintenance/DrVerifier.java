package nuna.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.java11.instrument.binder.jdk.MicrometerHttpClient;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DR replication-watermark verifier's passes (D37, amended 2026-11-20; docs/observability/dr-watermark.md). Each
 * pass reads the catalog's whole DR work list, checks each table and view behind its watermark in the DR store
 * ({@link DrVerify}) and reports the newest version it found whole; the catalog keeps it only if it is higher than the
 * one kept, so the watermark never moves back. A table checked whole whose check failed is checked whole again no
 * sooner than {@code wholeRetry} later, since a whole check lists its whole location.
 *
 * <p>Its meters are table-free (docs/observability/worker-metrics.md, "The DR verifier"): how many tables and views
 * are behind and the largest lag after the last pass, when a pass last read the whole work list, and the versions
 * verified and checks failed.
 */
final class DrVerifier implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DrVerifier.class);

    static final String PREFIX = WorkerMetrics.PREFIX + "dr.";
    /** A timer: each pass's duration, by outcome. */
    static final String PASS = PREFIX + "pass";
    /** A gauge: when the last pass that read the whole work list ended, in seconds since the epoch; 0 until one has. */
    static final String LAST_SUCCESS = PREFIX + "pass.last.success";
    /** A gauge: the tables and views behind their DR watermark after the last successful pass. */
    static final String BEHIND = PREFIX + "tables.behind";
    /** A gauge: the largest lag after the last successful pass, in seconds. */
    static final String LAG = PREFIX + "lag.max";
    /** A counter: versions the watermarks moved past. */
    static final String VERIFIED = PREFIX + "versions.verified";
    /** A counter: tables and views whose check or report failed. */
    static final String FAILURES = PREFIX + "failures";

    /** The catalog's DR routes, as the verifier calls them. */
    interface Catalog {

        Management.Page<DrVerify.Item> work(Optional<String> token);

        /** False when the catalog refused the report: the object gone, or a higher watermark kept. */
        boolean report(UUID id, DrVerify.Version version);
    }

    /** One object's check in the DR store. */
    interface Check {

        DrVerify.Result check(DrVerify.Item item);
    }

    /**
     * What one pass did.
     *
     * @param objects  objects the work list named
     * @param reported watermarks the catalog took
     * @param verified versions those moved past
     * @param behind   objects still behind their pointer
     * @param maxLag   the largest lag among them
     * @param failed   objects whose check or report failed
     * @param deferred objects whose last whole check failed too recently to repeat
     */
    record Pass(int objects, int reported, long verified, int behind, Duration maxLag, int failed, int deferred) {
    }

    private final Catalog catalog;
    private final Check check;
    private final Clock clock;
    private final Duration wholeRetry;
    private final AutoCloseable resources;
    private final MeterRegistry registry;
    private final Map<UUID, Instant> wholeFailed = new HashMap<>();
    private final Timer succeeded;
    private final Timer failedPass;
    private final Counter verifiedCount;
    private final Counter failures;
    private volatile double lastSuccess;
    private volatile double behind;
    private volatile double lag;

    /** @param resources closed with the verifier: the DR store */
    DrVerifier(Catalog catalog, Check check, Clock clock, Duration wholeRetry, MeterRegistry registry,
               AutoCloseable resources) {
        this.catalog = catalog;
        this.check = check;
        this.clock = clock;
        this.wholeRetry = wholeRetry;
        this.registry = registry;
        this.resources = resources;
        succeeded = timer("success");
        failedPass = timer("failure");
        Gauge.builder(LAST_SUCCESS, this, verifier -> verifier.lastSuccess).strongReference(true).baseUnit("seconds")
                .description("When the last DR verifier pass that read the whole work list ended, in seconds since"
                        + " the epoch; 0 until one has")
                .register(registry);
        Gauge.builder(BEHIND, this, verifier -> verifier.behind).strongReference(true)
                .description("Tables and views behind their DR watermark after the last pass").register(registry);
        Gauge.builder(LAG, this, verifier -> verifier.lag).strongReference(true).baseUnit("seconds")
                .description("The largest DR lag after the last pass: the time since the oldest commit not found"
                        + " whole in the DR store")
                .register(registry);
        verifiedCount = Counter.builder(VERIFIED).description("Versions the DR watermarks moved past")
                .register(registry);
        failures = Counter.builder(FAILURES).description("Tables and views whose DR check or report failed")
                .register(registry);
    }

    /**
     * The verifier as the service runs it: the catalog reached as the worker reaches it, as a maintenance principal,
     * and the DR store through the replicas {@code dr} names. Each pass reads the storage prefixes the catalog gives
     * the worker (item 256) before the work list's first page, and the DR store reads beneath those alone: none when
     * that read fails, which fails the pass.
     */
    static DrVerifier of(WorkerSettings settings, WorkerSettings.Dr dr, Clock clock, MeterRegistry meters,
                         ObservationRegistry observations) {
        HttpClient http = MicrometerHttpClient.instrumentationBuilder(HttpClient.newHttpClient(), meters)
                .observationRegistry(observations).uriMapper(Management::route).build();
        Management management = new Management(settings.catalogUri(), TokenSource.of(settings.token(), clock, http),
                http, settings.pageSize());
        DrStores stores = DrStores.of(settings.storage(), dr.replicas());
        DrVerify verify = new DrVerify(stores);
        return new DrVerifier(new Catalog() {
            @Override
            public Management.Page<DrVerify.Item> work(Optional<String> token) {
                if (token.isEmpty()) {
                    stores.reach(Prefixes.NONE);
                    stores.reach(Prefixes.resolve(management.storageProfiles(), settings.storage()));
                }
                return management.drWork(token);
            }

            @Override
            public boolean report(UUID id, DrVerify.Version version) {
                return management.reportDrWatermark(id, version);
            }
        }, verify::check, clock, settings.scanInterval(), meters, stores);
    }

    private Timer timer(String outcome) {
        return Timer.builder(PASS).description("Each DR verifier pass's duration; it fails when it cannot read the"
                + " DR work list").tag(WorkerMetrics.OUTCOME, outcome).register(registry);
    }

    /** One pass over the whole DR work list. Throws when a page of it cannot be read; what it reported stays. */
    Pass runOnce() {
        long start = registry.config().clock().monotonicTime();
        Instant now = clock.instant();
        Tally tally = new Tally();
        boolean read = false;
        try {
            Set<UUID> listed = new HashSet<>();
            Optional<String> token = Optional.empty();
            do {
                Management.Page<DrVerify.Item> page = catalog.work(token);
                for (DrVerify.Item item : page.items()) {
                    listed.add(item.id());
                    verify(item, now, tally);
                }
                token = page.nextToken();
            } while (token.isPresent());
            wholeFailed.keySet().retainAll(listed);
            read = true;
        } finally {
            (read ? succeeded : failedPass).record(registry.config().clock().monotonicTime() - start,
                    TimeUnit.NANOSECONDS);
            verifiedCount.increment(tally.verified);
            failures.increment(tally.failed);
            if (read) {
                behind = tally.behind;
                lag = tally.maxLag.toMillis() / 1000.0;
                lastSuccess = clock.millis() / 1000.0;
            }
        }
        return new Pass(tally.objects, tally.reported, tally.verified, tally.behind, tally.maxLag, tally.failed,
                tally.deferred);
    }

    private void verify(DrVerify.Item item, Instant now, Tally tally) {
        tally.objects++;
        long kept = item.watermark().map(DrVerify.Version::version).orElse(0L);
        long safe = kept;
        boolean whole = item.whole();
        Instant failedAt = wholeFailed.get(item.id());
        if (whole && failedAt != null && now.isBefore(failedAt.plus(wholeRetry))) {
            tally.deferred++;
        } else {
            try {
                DrVerify.Result result = check.check(item);
                if (result.safe().isPresent() && result.safe().get().version() > kept
                        && catalog.report(item.id(), result.safe().get())) {
                    safe = result.safe().get().version();
                    tally.reported++;
                    tally.verified += safe - kept;
                }
                result.missing().ifPresent(missing -> LOG.debug("DR store lacks {} of {} {}", missing, item.kind(),
                        item.id()));
                if (whole && safe < item.pointer().version()) {
                    wholeFailed.put(item.id(), now);
                } else {
                    wholeFailed.remove(item.id());
                }
            } catch (RuntimeException e) {
                tally.failed++;
                LOG.warn("DR check of {} {} failed", item.kind(), item.id(), e);
            }
        }
        if (safe < item.pointer().version()) {
            tally.behind++;
            oldestUnverified(item, kept, safe).map(oldest -> Duration.between(oldest, now))
                    .filter(lagged -> lagged.compareTo(tally.maxLag) > 0).ifPresent(lagged -> tally.maxLag = lagged);
        }
    }

    /**
     * When the oldest version above {@code safe} was committed: the listed version after it, or, when the watermark
     * did not move, the oldest the work list names. When every listed version passed and the pointer is still ahead
     * (the work list names at most 100), the last listed one's time, so the lag reads at most what it is.
     */
    private static Optional<Instant> oldestUnverified(DrVerify.Item item, long kept, long safe) {
        if (safe == kept) {
            return item.oldestUnverifiedAt();
        }
        return item.versions().stream().filter(version -> version.version() == safe + 1).findFirst()
                .flatMap(DrVerify.Version::committedAt)
                .or(() -> item.versions().isEmpty() ? Optional.empty() : item.versions().getLast().committedAt());
    }

    @Override
    public void close() throws Exception {
        resources.close();
    }

    private static final class Tally {
        int objects;
        int reported;
        long verified;
        int behind;
        Duration maxLag = Duration.ZERO;
        int failed;
        int deferred;
    }
}
