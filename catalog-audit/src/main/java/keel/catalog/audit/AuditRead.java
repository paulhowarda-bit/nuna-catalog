package keel.catalog.audit;

import java.util.Objects;

/**
 * A read of the recent audit records of a catalog, namespace, table or view (D23): who looked at who used it.
 *
 * @param target   what the records are about
 * @param returned how many records the reader was given
 * @param actor    who read them
 */
public record AuditRead(Securable target, int returned, Actor actor) implements EventData {

    public AuditRead {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actor, "actor");
        if (returned < 0) {
            throw new IllegalArgumentException("returned must not be negative: " + returned);
        }
    }

    @Override
    public EventType type() {
        return EventType.AUDIT_READ;
    }

    @Override
    public String subject() {
        return target.id().toString();
    }
}
