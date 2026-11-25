package nuna.catalog.server.governance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * The lines of the audit log {@value AuditTrail#LOGGER} while it is open, decoded; closing restores the logger. In a
 * Spring test, make it once the context is up ({@code @BeforeAll} or {@code @BeforeEach}), not as a field: a context
 * that starts resets logback, which detaches the capture, and it would see nothing (backlog 270).
 */
public final class AuditLogCapture implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AuditTrail.LOGGER);
    private final Level level = logger.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public AuditLogCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    /** Sets the audit logger's own level until closed. */
    AuditLogCapture level(Level set) {
        logger.setLevel(set);
        return this;
    }

    /** Every audit line written so far, in order, as written: the record's CloudEvent JSON. */
    List<String> lines() {
        attached();
        synchronized (appender) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    /**
     * The first audit line holding every one of {@code texts}, waiting up to 30 seconds for it: the relay writes a
     * change shortly after its commit.
     */
    public String await(String... texts) {
        return await(1, texts).getFirst();
    }

    /**
     * The audit lines holding every one of {@code texts}, waiting up to 30 seconds for at least {@code count} of them:
     * the relay writes a change shortly after its commit, and takes it out of the outbox.
     */
    public List<String> await(int count, String... texts) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (true) {
            List<String> holding = lines().stream()
                    .filter(line -> java.util.Arrays.stream(texts).allMatch(line::contains)).toList();
            if (holding.size() >= count) {
                return holding;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(holding.size() + " audit lines, not " + count + ", hold all of "
                        + java.util.Arrays.toString(texts));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Every audit record written so far, in order, as the log line's CloudEvent. */
    List<CatalogEvent> records() {
        attached();
        List<ILoggingEvent> lines;
        synchronized (appender) {
            lines = List.copyOf(appender.list);
        }
        return lines.stream().map(line -> {
            if (line.getLevel() != Level.INFO) {
                throw new AssertionError("an audit line at " + line.getLevel() + ": " + line.getFormattedMessage());
            }
            return EventCodec.decode(line.getFormattedMessage().getBytes(StandardCharsets.UTF_8));
        }).toList();
    }

    /** Fails at once if logback has detached the capture since it was made, rather than reading nothing. */
    private void attached() {
        if (!appender.isStarted() || !logger.isAttached(appender)) {
            throw new IllegalStateException("the audit log capture was detached, most likely by a Spring context that"
                    + " started after it was made: make it in @BeforeAll or @BeforeEach");
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(level);
    }
}
