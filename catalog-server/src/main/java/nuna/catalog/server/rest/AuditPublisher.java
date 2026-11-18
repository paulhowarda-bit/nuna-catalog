package nuna.catalog.server.rest;

import nuna.catalog.audit.CatalogEvent;

/**
 * Publishes the audit events that do not travel through the outbox: denials, authentication failures,
 * reads and credential issues (D23). The bean publishes to the audit topic through M7's {@code AuditTrail};
 * a test sink records them.
 */
@FunctionalInterface
public interface AuditPublisher {

    /**
     * Returns once the event is durably accepted.
     *
     * @throws RuntimeException when it was not, for example {@code AuditUnavailableException}
     */
    void publish(CatalogEvent event);
}
