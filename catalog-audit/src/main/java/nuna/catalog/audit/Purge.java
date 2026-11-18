package nuna.catalog.audit;

import java.util.Objects;

/**
 * A dropped table or view marked purged by the maintenance principal once its files are gone (D25).
 * The record is kept as history; its grants are deleted.
 *
 * @param object the table or view; its path is the name it held when dropped
 * @param actor  the maintenance principal that purged it
 */
public record Purge(Securable object, Actor actor) implements EventData {

    public Purge {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.PURGE;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
