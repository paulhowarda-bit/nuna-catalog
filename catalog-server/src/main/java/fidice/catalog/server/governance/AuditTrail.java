package fidice.catalog.server.governance;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import fidice.catalog.audit.EventData;
import fidice.catalog.audit.publish.AuditUnavailableException;
import fidice.catalog.audit.publish.EventPublisher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The synchronous audit records of D23: reads, denials, authentication failures and credential
 * issues, published to the audit topic before the response is sent.
 *
 * <p>A Kafka outage blocks credential issuance and nothing else (the M7 gate). {@link #requireRecorded}
 * fails closed. {@link #record} does not: when Kafka does not acknowledge, the record goes to the
 * {@code fidice.audit.fallback} log as its CloudEvent JSON, and for the probe interval after that
 * every such record goes there without waiting on Kafka, so an outage does not slow every read by the
 * delivery timeout. Safe for concurrent use.
 */
public final class AuditTrail {

    private static final Logger FALLBACK = LoggerFactory.getLogger("fidice.audit.fallback");

    private final Optional<EventPublisher> publisher;
    private final URI source;
    private final Clock clock;
    private final Duration probeInterval;
    private volatile Instant skipUntil = Instant.MIN;

    /** @param publisher the audit topic's publisher; empty when Kafka is not configured */
    public AuditTrail(Optional<EventPublisher> publisher, URI source, Clock clock, Duration probeInterval) {
        this.publisher = publisher;
        this.source = source;
        this.clock = clock;
        this.probeInterval = probeInterval;
    }

    /**
     * Publishes a read, denial or authentication failure. Never throws for an outage: an unacknowledged
     * record is written to the fallback log instead.
     */
    public void record(EventData data) {
        record(CatalogEvent.create(source, clock.instant(), data));
    }

    /** {@link #record(EventData)} for an event another seam built. */
    public void record(CatalogEvent event) {
        if (publisher.isEmpty() || clock.instant().isBefore(skipUntil)) {
            fallback(event, null);
            return;
        }
        try {
            publisher.get().publish(event);
        } catch (AuditUnavailableException e) {
            skipUntil = clock.instant().plus(probeInterval);
            fallback(event, e);
        }
    }

    /**
     * Publishes a record the response depends on, a credential issue (D21, D23): return only once
     * Kafka acknowledged it, and hand nothing out when it throws. Always tries Kafka.
     *
     * @throws AuditUnavailableException if Kafka did not acknowledge it, or is not configured; the
     *                                   REST layer answers 503
     */
    public void requireRecorded(EventData data) {
        requireRecorded(CatalogEvent.create(source, clock.instant(), data));
    }

    /** {@link #requireRecorded(EventData)} for an event another seam built. */
    public void requireRecorded(CatalogEvent event) {
        if (publisher.isEmpty()) {
            throw new AuditUnavailableException(event, new IllegalStateException("fidice.audit.kafka-bootstrap-servers is not set"));
        }
        publisher.get().publish(event);
        skipUntil = Instant.MIN;
    }

    private static void fallback(CatalogEvent event, Exception cause) {
        FALLBACK.warn("audit record not acknowledged by Kafka{}: {}", cause == null ? "" : " (" + cause.getMessage() + ")",
                new String(EventCodec.encode(event), StandardCharsets.UTF_8));
    }
}
