package fidice.catalog.audit;

import java.util.Objects;

/**
 * A dropped table or view made active again within its undrop window, keeping its id (D25).
 *
 * @param object the table or view; its path is the name it holds now
 * @param actor  who undropped it
 */
public record Undrop(Securable object, Actor actor) implements EventData {

    public Undrop {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.UNDROP;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
