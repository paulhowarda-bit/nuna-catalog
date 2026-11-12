package keel.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A legal hold on a securable (D24). A hold on a catalog or namespace holds everything under it:
 * the store refuses to drop or purge a held object, or drop a held namespace.
 */
public record LegalHold(UUID id, Securable target, String reason, String placedBy, Instant placedAt) {

    public LegalHold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(placedBy, "placedBy");
        Objects.requireNonNull(placedAt, "placedAt");
    }
}
