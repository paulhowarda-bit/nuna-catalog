package nuna.catalog.audit;

import java.util.Objects;

/**
 * A dropped table or view claimed for purge by a maintenance principal, before it deletes the files (D25,
 * D30). From the claim on, the object can no longer be undropped, and a hold placed after it does not stop
 * the purge; the {@link Purge} event marks its end.
 *
 * @param object the table or view; its path is the name it held when dropped
 * @param actor  the maintenance principal that holds the claim
 */
public record PurgeClaim(Securable object, Actor actor) implements EventData {

    public PurgeClaim {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.PURGE_CLAIM;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
