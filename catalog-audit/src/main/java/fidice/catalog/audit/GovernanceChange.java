package fidice.catalog.audit;

import java.util.Objects;

/**
 * A governance attribute set or removed on a catalog, namespace, table or view: a classification,
 * owner, retention class, regulated or row/column-policy flag pushed with its provenance (D27), the
 * maintenance policy (D30) or a DR freeze (D37).
 *
 * @param action     set or removed
 * @param target     what the attribute is on
 * @param attribute  the attribute's name, e.g. {@code classification} or {@code maintenance-policy}
 * @param value      the value set; null when removed
 * @param provenance where the value came from, as the pushing system named it; null when removed
 * @param actor      who changed it
 */
public record GovernanceChange(Action action, Securable target, String attribute, String value, String provenance,
                               Actor actor) implements EventData {

    public enum Action { SET, REMOVED }

    public GovernanceChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.GOVERNANCE_CHANGE;
    }

    @Override
    public String subject() {
        return target.id().toString();
    }
}
