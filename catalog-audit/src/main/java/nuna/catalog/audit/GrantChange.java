package nuna.catalog.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * A privilege granted to or revoked from a group (D18, D19). Grant changes are archived so they
 * can be replayed after a DR event (D37).
 *
 * @param action    granted or revoked
 * @param securable what the privilege applies to
 * @param group     the IdP group; grants never go to individuals (D19)
 * @param privilege the privilege's name
 * @param actor     who changed it
 * @param branch    the branch the grant names, by name, when it holds only through that branch (backlog 113); null
 *                  for a grant on every ref
 * @param branchId  that branch's id; null with {@code branch}
 */
public record GrantChange(Action action, Securable securable, String group, String privilege, Actor actor,
                          String branch, UUID branchId) implements EventData {

    public enum Action { GRANTED, REVOKED }

    /** A change to a grant on every ref. */
    public GrantChange(Action action, Securable securable, String group, String privilege, Actor actor) {
        this(action, securable, group, privilege, actor, null, null);
    }

    public GrantChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(securable, "securable");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(privilege, "privilege");
        Objects.requireNonNull(actor, "actor");
        if ((branch == null) != (branchId == null)) {
            throw new IllegalArgumentException("a grant change names a branch by both its name and its id, or neither");
        }
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
