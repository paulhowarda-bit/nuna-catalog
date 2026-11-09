package fidice.catalog.audit;

import java.util.List;
import java.util.Objects;

/**
 * A table or view renamed or moved to another namespace (D23). Its id stays the same.
 *
 * @param object   the table or view, under its new name
 * @param fromPath its namespace levels and name before, as given
 * @param actor    who renamed it
 */
public record Rename(Securable object, List<String> fromPath, Actor actor) implements EventData {

    public Rename {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(actor, "actor");
        fromPath = List.copyOf(fromPath);
    }

    @Override
    public EventType type() {
        return EventType.RENAME;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
