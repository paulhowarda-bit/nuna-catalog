package nuna.catalog.audit.publish;

import nuna.catalog.audit.StampedEvent;

/** Publishes one record synchronously (D23), as the log holds it: stamped with its run and number (item 276). */
@FunctionalInterface
public interface EventPublisher {

    /**
     * Returns once the record is acknowledged.
     *
     * @throws AuditUnavailableException if it was not acknowledged in time
     */
    void publish(StampedEvent record);
}
