package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ObjectLockArchive;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The archive check while the server runs (D23, amended 2026-11-25; backlogs 125 and 262): where an archive is
 * configured, a minute after the server starts and every hour after that, the checkpoints are followed and each run's
 * chain is verified up to the newest head they gave it ({@link ArchiveCheck}). What it verified is kept in the bucket,
 * so every server shares it and a restart reads only what was archived since. Every server checks; two checking at once
 * read the same segments and record the same heads.
 *
 * <p>After each check it gives, as the gauge {@value #ARCHIVED} tagged with the run, the highest number the check
 * verified in each run's chain not yet done (backlog 272): set beside a server's own count ({@value #LOGGED}), it shows
 * a running server's records that never reached the archive.
 */
@Component
class AuditArchiveCheck implements SmartLifecycle, MeterBinder {

    /** The highest number verified in each run's chain not yet done, by {@value #RUN}. */
    static final String ARCHIVED = "nuna.audit.archive.highest";
    /** The records a server has logged in its run, by {@value #RUN} ({@link AuditTrail#logged}). */
    static final String LOGGED = "nuna.audit.logged";
    /** The label naming a server run, as its records' stamp does. */
    static final String RUN = "run";

    private static final Logger LOG = LoggerFactory.getLogger(AuditArchiveCheck.class);
    static final Duration FIRST_RUN = Duration.ofMinutes(1);
    static final Duration INTERVAL = Duration.ofHours(1);

    private final AuditSettings settings;
    private final Optional<ObjectLockArchive> archive;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Optional<ArchiveCheck.Result> last = Optional.empty();
    /** What the bucket said was verified, read before this server's first check; null until read. */
    private volatile Map<String, ArchiveCheck.Verified> kept;
    private volatile MultiGauge archived;
    private Thread thread;

    @Autowired
    AuditArchiveCheck(AuditSettings settings, AuditRuntime runtime, Clock clock) {
        this(settings, runtime.archive(), clock);
    }

    AuditArchiveCheck(AuditSettings settings, Optional<ObjectLockArchive> archive, Clock clock) {
        this.settings = settings;
        this.archive = archive;
        this.clock = clock;
    }

    /** Whether an archive is configured on this server. */
    boolean configured() {
        return archive.isPresent();
    }

    /** The archive's settings, for the status route. */
    AuditSettings.Archive archiveSettings() {
        return settings.archive();
    }

    /** The last run's outcome on this server; empty before its first. */
    Optional<ArchiveCheck.Result> last() {
        return last;
    }

    /**
     * How far each chain is verified, by chain: as this server's last check left it, or before its first, as the bucket
     * keeps it from any server's, read once. Empty without an archive, or while the bucket cannot be read.
     */
    Map<String, ArchiveCheck.Verified> verified() {
        Optional<ArchiveCheck.Result> result = last();
        if (result.isPresent()) {
            return result.get().chains().stream()
                    .collect(Collectors.toMap(ArchiveCheck.ChainResult::chain, ArchiveCheck.ChainResult::verified));
        }
        if (archive.isEmpty()) {
            return Map.of();
        }
        Map<String, ArchiveCheck.Verified> read = kept;
        if (read == null) {
            try {
                read = Map.copyOf(new ArchiveCheck(archive.get(), clock).verified());
                kept = read;
            } catch (RuntimeException e) {
                LOG.warn("what the audit archive check verified could not be read from the bucket: {}", e.toString());
                return Map.of();
            }
        }
        return read;
    }

    /**
     * The numbers of each run the last check reported missing from its chain: gaps older than two checkpoint intervals,
     * so no longer on their way (backlog 272). Empty before this server's first check.
     */
    Map<String, List<ArchiveCheck.Range>> missing() {
        return last().map(result -> result.chains().stream().filter(c -> !c.missing().isEmpty())
                        .collect(Collectors.toMap(ArchiveCheck.ChainResult::chain, ArchiveCheck.ChainResult::missing)))
                .orElse(Map.of());
    }

    /**
     * Where record {@code number} of server run {@code run}, with the event id {@code eventId}, lies in the run's chain
     * (backlog 272): looked for in the segment its number puts it in and the one on each side, and in the whole chain
     * when the check has verified every number up to it. Empty when it is not found.
     */
    Optional<ObjectLockArchive.Placement> place(UUID run, long number, UUID eventId) {
        ObjectLockArchive store = archive.orElseThrow();
        String chain = run.toString();
        Optional<ObjectLockArchive.Placement> near = store.place(chain, number, eventId.toString(), 1);
        if (near.isPresent()) {
            return near;
        }
        ArchiveCheck.Verified verified = verified().get(chain);
        return verified != null && number <= verified.through()
                ? store.place(chain, number, eventId.toString(), Integer.MAX_VALUE) : Optional.empty();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        archived = MultiGauge.builder(ARCHIVED)
                .description("The highest number the archive check verified in each server run's chain not yet done")
                .register(registry);
        last().ifPresent(this::gauge);
    }

    private void gauge(ArchiveCheck.Result result) {
        MultiGauge gauge = archived;
        if (gauge != null) {
            gauge.register(result.chains().stream().filter(c -> c.run() && !c.verified().done())
                    .map(c -> MultiGauge.Row.of(Tags.of(RUN, c.chain()), c.verified().highest())).toList(), true);
        }
    }

    /** Runs the check once now, as the hourly run does, and keeps its outcome. */
    ArchiveCheck.Result runOnce() {
        ArchiveCheck.Result result = new ArchiveCheck(archive.orElseThrow(), clock).run();
        last = Optional.of(result);
        gauge(result);
        if (!result.intact()) {
            LOG.error("audit archive check: the archive is not intact: checkpoints {}, chains {}",
                    result.checkpoints(), result.chains().stream()
                            .filter(c -> !c.problems().isEmpty() || c.failed().isPresent()).toList());
        }
        if (!result.complete()) {
            LOG.error("audit archive check: records are missing or a run stopped without its end: {}",
                    result.chains().stream().filter(c -> !c.missing().isEmpty() || c.unended()).toList());
        }
        return result;
    }

    @Override
    public void start() {
        if (archive.isEmpty() || !running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual().name("audit-archive-check").start(this::loop);
    }

    private void loop() {
        Duration wait = FIRST_RUN;
        while (running.get() && sleep(wait)) {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.error("audit archive check failed; retrying at the next interval", e);
            }
            wait = INTERVAL;
        }
    }

    /** False when the wait was cut short by {@link #stop}. */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
