package keel.catalog.audit.publish;

import keel.catalog.audit.CatalogEvent;

/** Publishes one event synchronously (D23). */
@FunctionalInterface
public interface EventPublisher {

    /**
     * Returns once the event is acknowledged.
     *
     * @throws AuditUnavailableException if it was not acknowledged in time
     */
    void publish(CatalogEvent event);
}
