package nuna.catalog.server.governance;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventData;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.audit.publish.RelayTarget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where every audit record goes (D23, amended 2026-11-23: "Log as the main audit path"). Each record is written as its
 * CloudEvent JSON, at INFO, to the logger {@value #LOGGER} before the method returns; the log lines reach stdout as
 * ECS JSON and, where a logs endpoint is set, the OTLP log exporter (docs/observability/audit-log.md). That log is the
 * record of audit.
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
    private final Optional<RelayTarget> stream;
    private final URI source;
    private final Clock clock;
    private final Duration probeInterval;
    private volatile Instant skipUntil = Instant.MIN;

    /** A trail whose relayed batches go only to the log; {@code topic} takes each synchronous record's copy. */
    public AuditTrail(Optional<EventPublisher> topic, URI source, Clock clock, Duration probeInterval) {
        this(topic, Optional.empty(), source, clock, probeInterval);
    }

    /**
     * @param topic  the audit topic's publisher for the synchronous records; empty when Kafka is not configured
     * @param stream where a relayed batch's copy goes: the audit topic and the change stream; empty without Kafka
     */
    public AuditTrail(Optional<EventPublisher> topic, Optional<RelayTarget> stream, URI source, Clock clock,
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

    /** Records a read, denial, authentication failure or credential issue. Never throws for Kafka. */
    public void record(EventData data) {
        record(CatalogEvent.create(source, clock.instant(), data));
    }

    /** {@link #record(EventData)} for an event another seam built. */
    public void record(CatalogEvent event) {
        log(event);
        if (topic.isPresent() && kafkaTried()) {
            try {
                topic.get().publish(event);
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
        orderedBatch.forEach(AuditTrail::log);
        if (stream.isPresent() && kafkaTried()) {
            try {
                stream.get().deliver(orderedBatch);
            } catch (RuntimeException e) {
                kafkaFailed(orderedBatch.size(), e);
            }
        }
    }

    private static void log(CatalogEvent event) {
        AUDIT.info("{}", new String(EventCodec.encode(event), StandardCharsets.UTF_8));
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
