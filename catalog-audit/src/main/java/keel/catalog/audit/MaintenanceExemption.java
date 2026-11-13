package keel.catalog.audit;

import java.util.Objects;

/**
 * Storage access to a table with a row/column policy, given to a maintenance principal acting as itself
 * though it is no trusted engine (D20, D30): the maintenance exemption. Never for an end user. It is
 * published before the access is given; if it cannot be published the access is refused.
 *
 * @param object    the flagged table
 * @param operation the operation that needed storage access
 * @param actor     the maintenance principal
 */
public record MaintenanceExemption(Securable object, String operation, Actor actor) implements EventData {

    public MaintenanceExemption {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.MAINTENANCE_EXEMPTION;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
