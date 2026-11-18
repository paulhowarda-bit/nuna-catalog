package nuna.catalog.audit;

import java.util.Objects;

/**
 * An authorization decision that refused a request; every decision is audited (D18).
 *
 * @param operation the operation refused
 * @param securable what it was refused on, or null when the refusal is about no securable (the
 *                  deployment's trusted-engine registry, D17)
 * @param privilege the privilege that was missing, or null when the refusal is not about one
 *                  (a flagged table and an untrusted engine (D20), an unclassified table in a
 *                  regulated namespace (D27))
 * @param reason    why, in words
 * @param actor     who was refused
 */
public record Denial(String operation, Securable securable, String privilege, String reason, Actor actor)
        implements EventData {

    public Denial {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.DENIAL;
    }

    @Override
    public String subject() {
        return securable == null ? null : securable.id().toString();
    }
}
