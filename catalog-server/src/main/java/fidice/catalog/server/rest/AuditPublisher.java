package fidice.catalog.server.rest;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the audit events that do not travel through the outbox: denials, authentication failures,
 * reads and credential issues (D23). M7 replaces the logging default with the Kafka publisher on the
 * audit topic; a test sink records them.
 */
@FunctionalInterface
public interface AuditPublisher {

    Logger AUDIT_LOG = LoggerFactory.getLogger("fidice.audit");

    /** Until M7 wires Kafka: one line per event, the CloudEvent as it would be published. */
    AuditPublisher LOG = event -> AUDIT_LOG.info(new String(EventCodec.encode(event), StandardCharsets.UTF_8));

    /**
     * Returns once the event is durably accepted.
     *
     * @throws RuntimeException when it was not, for example {@code AuditUnavailableException}
     */
    void publish(CatalogEvent event);
}
