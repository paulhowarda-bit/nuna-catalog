package nuna.catalog.audit;

import java.util.Objects;

/**
 * A privilege granted to or revoked from a group (D18, D19). Grant changes are archived so they
 * can be replayed after a DR event (D37).
 *
 * @param action    granted or revoked
 * @param securable what the privilege applies to
 * @param group     the IdP group; grants never go to individuals (D19)
 * @param privilege the privilege's name
 * @param actor     who changed it
 */
public record GrantChange(Action action, Securable securable, String group, String privilege, Actor actor)
        implements EventData {

    public enum Action { GRANTED, REVOKED }

    public GrantChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(securable, "securable");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(privilege, "privilege");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.GRANT_CHANGE;
    }

    @Override
    public String subject() {
        return securable.id().toString();
    }
}
