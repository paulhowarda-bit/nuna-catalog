package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ObjectLockArchive;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
 */
@Component
class AuditArchiveCheck implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AuditArchiveCheck.class);
    static final Duration FIRST_RUN = Duration.ofMinutes(1);
    static final Duration INTERVAL = Duration.ofHours(1);

    private final AuditSettings settings;
    private final Optional<ObjectLockArchive> archive;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Optional<ArchiveCheck.Result> last = Optional.empty();
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

    /** Runs the check once now, as the hourly run does, and keeps its outcome. */
    ArchiveCheck.Result runOnce() {
        ArchiveCheck.Result result = new ArchiveCheck(archive.orElseThrow(), clock).run();
        last = Optional.of(result);
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
