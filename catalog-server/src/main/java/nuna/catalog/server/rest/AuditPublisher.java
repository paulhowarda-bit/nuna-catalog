package nuna.catalog.server.rest;

import nuna.catalog.audit.CatalogEvent;

/**
 * Publishes the audit events that do not travel through the outbox: denials, authentication failures,
 * reads and credential issues (D23). The bean writes them to the log {@code nuna.audit}, and to the audit topic
 * where Kafka is configured, through M7's {@code AuditTrail} (D23, amended 2026-11-23); a test sink records them.
 */
@FunctionalInterface
public interface AuditPublisher {

    /**
     * Returns once the event is recorded; the bean's record is written to the log before it returns.
     *
     * @throws RuntimeException when it was not; the bean throws none for want of Kafka
     */
    void publish(CatalogEvent event);
}
