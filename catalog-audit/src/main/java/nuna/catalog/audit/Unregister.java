package nuna.catalog.audit;

import java.util.Objects;

/**
 * A table unregistered (D13): the catalog stops tracking it and deletes nothing. Its id and history
 * stay, it is never undropped or purged, and registering the metadata file it was unregistered at
 * brings it back, with its id.
 *
 * @param object  the table; its path is the name it held
 * @param pointer the main pointer it was unregistered at, which names that metadata file
 * @param actor   who unregistered it
 */
public record Unregister(Securable object, PointerState pointer, Actor actor) implements EventData {

    public Unregister {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.UNREGISTER;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
