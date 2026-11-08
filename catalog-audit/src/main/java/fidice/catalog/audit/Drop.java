package fidice.catalog.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * A soft drop of a table or view (D25).
 *
 * @param object     the dropped table or view; its id survives an undrop
 * @param purgeAfter the end of the undrop window, fixed at drop time
 * @param actor      who dropped it
 */
public record Drop(Securable object, Instant purgeAfter, Actor actor) implements EventData {

    public Drop {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(purgeAfter, "purgeAfter");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.DROP;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
