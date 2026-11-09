package fidice.catalog.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * A legal hold placed or released (D24). A hold on a catalog or namespace holds everything under it.
 *
 * @param action placed or released
 * @param holdId the hold's id
 * @param target what the hold is on
 * @param reason the reason given when it was placed
 * @param actor  who placed or released it
 */
public record HoldChange(Action action, UUID holdId, Securable target, String reason, Actor actor)
        implements EventData {

    public enum Action { PLACED, RELEASED }

    public HoldChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.HOLD_CHANGE;
    }

    @Override
    public String subject() {
        return target.id().toString();
    }
}
