package keel.catalog.audit;

import java.util.Objects;

/**
 * A load of a table or view (D23).
 *
 * @param object the table or view
 * @param branch the branch read (D33)
 * @param served the pointer whose metadata was returned
 * @param actor  who read it
 */
public record ObjectRead(Securable object, String branch, PointerState served, Actor actor) implements EventData {

    public ObjectRead {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(served, "served");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.OBJECT_READ;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
