package keel.catalog.audit;

import java.util.List;
import java.util.Objects;

/**
 * A shared engine registered in, or removed from, the trusted-engine registry (D17). A registered
 * engine may assert the end users it acts for, so every change to the registry is audited.
 *
 * @param action       registered (or re-registered with new values) or removed
 * @param issuer       the exact {@code iss} of the engine's tokens
 * @param clientId     the engine's client id at that issuer
 * @param endUserClaim the claim the engine names its end users by; empty for a removal
 * @param flags        the registration's flags, sorted; empty for a removal
 * @param actor        who changed the registry
 */
public record EngineChange(Action action, String issuer, String clientId, String endUserClaim, List<String> flags,
                           Actor actor) implements EventData {

    public enum Action { REGISTERED, REMOVED }

    public EngineChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(endUserClaim, "endUserClaim");
        flags = List.copyOf(flags);
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.ENGINE_CHANGE;
    }

    @Override
    public String subject() {
        return null;
    }
}
