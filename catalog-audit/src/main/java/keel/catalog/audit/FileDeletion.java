package keel.catalog.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * Files of a table the maintenance worker no longer needs (D30): recorded as pending deletions, which wait
 * out the database's PITR window, then reported deleted once the worker has deleted them with its own
 * storage identity.
 *
 * @param action    recorded or deleted
 * @param object    the table the files belonged to
 * @param files     how many files
 * @param notBefore when recorded, the earliest time they may be deleted; null when deleted
 * @param actor     the maintenance principal
 */
public record FileDeletion(Action action, Securable object, int files, Instant notBefore, Actor actor)
        implements EventData {

    public enum Action { RECORDED, DELETED }

    public FileDeletion {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.FILE_DELETION;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
