package keel.catalog.store;

import java.util.Objects;
import java.util.UUID;

/**
 * A privilege on a securable, granted to a group, never to a person (D18, D19). The store keeps
 * privilege names as given; the ladder and inheritance are the core's.
 */
public record Grant(UUID catalogId, Securable securable, String group, String privilege) {

    public Grant {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(securable, "securable");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(privilege, "privilege");
    }
}
