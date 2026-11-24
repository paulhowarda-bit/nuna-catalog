package nuna.catalog.audit.publish;

import nuna.catalog.audit.CatalogEvent;

/**
 * Kafka did not acknowledge an event within the delivery timeout. The event may still arrive later, at most once.
 * The catalog's audit trail catches it: the record is in the log already, and nothing is refused for want of Kafka
 * (D23, amended 2026-11-23).
 */
public final class AuditUnavailableException extends RuntimeException {

    public AuditUnavailableException(CatalogEvent event, Throwable cause) {
        super("event " + event.id() + " (" + event.type().typeName() + ") was not acknowledged by Kafka", cause);
    }
}
