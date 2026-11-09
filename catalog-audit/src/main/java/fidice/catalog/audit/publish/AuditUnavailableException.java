package fidice.catalog.audit.publish;

import fidice.catalog.audit.CatalogEvent;

/**
 * Kafka did not acknowledge an event within the delivery timeout. The caller must not go on as
 * if it had: a credential issue stops here (D23). The event may still arrive later, at most once.
 */
public final class AuditUnavailableException extends RuntimeException {

    public AuditUnavailableException(CatalogEvent event, Throwable cause) {
        super("event " + event.id() + " (" + event.type().typeName() + ") was not acknowledged by Kafka", cause);
    }
}
