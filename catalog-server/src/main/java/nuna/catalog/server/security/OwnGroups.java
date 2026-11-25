package nuna.catalog.server.security;

import nuna.catalog.core.principal.Caller;
import java.util.Set;

/**
 * Who may grant to, and set row filters and column masks for, a group they belong to: no one, unless
 * {@code nuna.security.own-group-principals} names them (D18, amended 2026-11-25). The demo names its presenter,
 * who belongs to every group and shows everything in one sign-in; a deployment at work names no one. Separation of
 * duties between groups still holds for them.
 *
 * @param principals the users, by principal id, who may act on their own groups
 */
public record OwnGroups(Set<String> principals) {

    /** No one acts on their own groups. */
    public static final OwnGroups NONE = new OwnGroups(Set.of());

    public OwnGroups {
        principals = Set.copyOf(principals);
    }

    /** Whether the caller may grant to or set rules for {@code group}: not one of theirs, or they are named. */
    public boolean mayActOn(Caller caller, String group) {
        return !caller.subject().groups().contains(group) || principals.contains(caller.subject().id());
    }
}
