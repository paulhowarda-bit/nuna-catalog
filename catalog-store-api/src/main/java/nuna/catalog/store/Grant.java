package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A privilege on a securable, granted to a group, never to a person (D18, D19). The store keeps
 * privilege names as given; the ladder and inheritance are the core's.
 *
 * @param branch the branch the grant holds on, by ref id, or empty for one that holds on every ref, main included
 *               (D33, amended 2026-11-25; backlog 113)
 */
public record Grant(UUID catalogId, Securable securable, String group, String privilege, Optional<UUID> branch) {

    public Grant {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(securable, "securable");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(privilege, "privilege");
        Objects.requireNonNull(branch, "branch");
    }

    /** A grant that holds on every ref. */
    public Grant(UUID catalogId, Securable securable, String group, String privilege) {
        this(catalogId, securable, group, privilege, Optional.empty());
    }

    /** Whether the grant holds on a request through {@code ref}: empty for main. */
    public boolean holdsOn(Optional<UUID> ref) {
        return branch.isEmpty() || branch.equals(ref);
    }
}
