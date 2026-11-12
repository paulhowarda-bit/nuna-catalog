package keel.catalog.audit;

import java.util.Objects;

/**
 * A use of break-glass access (D23).
 *
 * @param securable     what was accessed
 * @param justification the reason the caller gave
 * @param actor         who used it
 */
public record BreakGlass(Securable securable, String justification, Actor actor) implements EventData {

    public BreakGlass {
        Objects.requireNonNull(securable, "securable");
        Objects.requireNonNull(justification, "justification");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.BREAK_GLASS;
    }

    @Override
    public String subject() {
        return securable.id().toString();
    }
}
