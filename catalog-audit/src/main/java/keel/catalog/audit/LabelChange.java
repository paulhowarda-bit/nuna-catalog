package keel.catalog.audit;

import java.util.Objects;

/**
 * A label engines are sent changed on one catalog, namespace, table or view (D27): classification,
 * owner or retention class. It names only the level it was set or removed on; a consumer resolves
 * what that means for the tables and views beneath, as the catalog does, nearest level first. Where
 * the value came from stays on the audit feed's {@link GovernanceChange}.
 *
 * @param target the level it changed on
 * @param label  {@code classification}, {@code owner} or {@code retention-class}
 * @param value  the value now set on that level; null when removed
 * @param actor  who changed it
 */
public record LabelChange(Securable target, String label, String value, Actor actor) implements EventData {

    public LabelChange {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.LABEL_CHANGE;
    }

    @Override
    public String subject() {
        return target.id().toString();
    }
}
