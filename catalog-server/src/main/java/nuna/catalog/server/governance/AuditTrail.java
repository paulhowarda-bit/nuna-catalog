package nuna.catalog.server.governance;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventData;
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.RunEnd;
import nuna.catalog.audit.StampedEvent;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.audit.publish.StampedTarget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where every audit record goes (D23, amended 2026-11-23: "Log as the main audit path"). Each record is written as its
 * CloudEvent JSON, at INFO, to the logger {@value #LOGGER} before the method returns; the log lines reach stdout as
 * ECS JSON and, where a logs endpoint is set, the OTLP log exporter (docs/observability/audit-log.md). That log is the
 * record of audit.
 *
 * <p>Each record logged carries this trail's run, a UUID made when the server starts, and its number in the run, from 1
 * ({@link LogStamp}, D23, amended 2026-11-25): the archive keeps each run's records in a chain of its own, and a number
 * the archive never received shows as missing. The run's last record is its run end ({@link #end}), so a run that
 * stopped without one shows too. Kafka's copy carries them too, byte for byte the log's line (D23, amended
 * 2026-11-25; item 276), so a row the audit tables take from the topic is placed in its chain as one from the log.
 *
 * <p>Where Kafka is configured, each record also goes to the audit topic, and a relayed batch's object changes to the
 * change stream, best effort: a record Kafka does not acknowledge is in the log all the same, nothing throws, and for
 * the probe interval after a failure every record skips Kafka, so an outage does not add the delivery timeout to every
 * request. Nothing a caller asks for waits on Kafka or is refused for want of it: a credential issue is recorded as a
 * read is. Safe for concurrent use.
 */
public final class AuditTrail {

    /** The logger every audit record is written to. */
    public static final String LOGGER = "nuna.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER);
    private static final Logger LOG = LoggerFactory.getLogger(AuditTrail.class);

    private final Optional<EventPublisher> topic;
    private final Optional<StampedTarget> stream;
    private final URI source;
    private final Clock clock;
    private final Duration probeInterval;
    private volatile Instant skipUntil = Instant.MIN;
    private final UUID run = UUID.randomUUID();
    private final AtomicLong logged = new AtomicLong();
    private final AtomicBoolean ended = new AtomicBoolean();

    /** A trail whose relayed batches go only to the log; {@code topic} takes each synchronous record's copy. */
    public AuditTrail(Optional<EventPublisher> topic, URI source, Clock clock, Duration probeInterval) {
        this(topic, Optional.empty(), source, clock, probeInterval);
    }

    /**
     * @param topic  the audit topic's publisher for the synchronous records; empty when Kafka is not configured
     * @param stream where a relayed batch's copy goes: the audit topic and the change stream; empty without Kafka
     */
    public AuditTrail(Optional<EventPublisher> topic, Optional<StampedTarget> stream, URI source, Clock clock,
                      Duration probeInterval) {
        this.topic = topic;
        this.stream = stream;
        this.source = source;
        this.clock = clock;
        this.probeInterval = probeInterval;
    }

    /**
     * Refuses to go on when {@code logger} would drop an audit record: the log is the record of audit, so a setting
     * that turns it off ({@code logging.level.nuna.audit} coarser than INFO) stops the server at startup.
     */
    static void requireLogged(Logger logger) {
        if (!logger.isInfoEnabled()) {
            throw new IllegalStateException("logging.level." + LOGGER + " must be INFO or finer: every audit record"
                    + " is written to the log " + LOGGER + ", and that log is the record of audit (D23)");
        }
    }

    /** {@link #requireLogged(Logger)} for the audit logger itself. */
    static void requireLogged() {
        requireLogged(AUDIT);
    }

    /** This server run, as every record's stamp names it. */
    public UUID run() {
        return run;
    }

    /**
     * How many records this run has logged: the number of the last. Exported as {@code nuna.audit.logged}, tagged with
     * the run, beside the archive's highest number for it (backlog 272).
     */
    public long logged() {
        return logged.get();
    }

    /**
     * Logs the run's run-end record, once: the last number of the run, so the archive check knows the run ended and
     * that no number after it is missing. Only the log takes it. Called as the server stops, after every other part
     * that records has stopped.
     */
    void end(String host) {
        if (ended.compareAndSet(false, true)) {
            log(CatalogEvent.create(source, clock.instant(), new RunEnd(host)));
            LOG.info("Audit run {} ended with record {}", run, logged.get());
        }
    }

    /** Records a read, denial, authentication failure or credential issue. Never throws for Kafka. */
    public void record(EventData data) {
        record(CatalogEvent.create(source, clock.instant(), data));
    }

    /** {@link #record(EventData)} for an event another seam built. */
    public void record(CatalogEvent event) {
        StampedEvent copy = log(event);
        if (topic.isPresent() && kafkaTried()) {
            try {
                topic.get().publish(copy);
            } catch (RuntimeException e) {
                kafkaFailed(1, e);
            }
        }
    }

    /**
     * Records a batch the outbox relay claimed, or a notice that changes nothing in the store, in order: each to the
     * log, then, where Kafka is configured, the batch to the audit topic and its object changes to the change stream.
     * Never throws for Kafka, so the relay marks the batch relayed once it is in the log.
     */
    public void deliver(List<CatalogEvent> orderedBatch) {
        List<StampedEvent> copies = new ArrayList<>(orderedBatch.size());
        for (CatalogEvent event : orderedBatch) {
            copies.add(log(event));
        }
        if (stream.isPresent() && kafkaTried()) {
            try {
                stream.get().deliver(copies);
            } catch (RuntimeException e) {
                kafkaFailed(orderedBatch.size(), e);
            }
        }
    }

    /** Writes the record to the log with the next number of the run, and returns it as logged. */
    private StampedEvent log(CatalogEvent event) {
        StampedEvent stamped = new StampedEvent(event, new LogStamp(run, logged.incrementAndGet()));
        AUDIT.info("{}", new String(stamped.encode(), StandardCharsets.UTF_8));
        return stamped;
    }

    private boolean kafkaTried() {
        return !clock.instant().isBefore(skipUntil);
    }

    private void kafkaFailed(int records, RuntimeException cause) {
        skipUntil = clock.instant().plus(probeInterval);
        LOG.warn("Kafka did not take {} audit record(s), which are in the log {} all the same; records go only to the"
                + " log for the next {}: {}", records, LOGGER, probeInterval, cause.toString());
    }
}
