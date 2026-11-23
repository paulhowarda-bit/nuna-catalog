package nuna.catalog.server;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import nuna.catalog.store.SafetyFinding;

/**
 * The store's self-check as this server ran it at start (D8.2, {@link StoreWiring}): when, and each finding
 * {@code nuna.database.allow-unsafe} let the server start despite. Any other finding refuses to start, so a server
 * that runs passed the rest. Health's Store panel shows it (backlog 139).
 *
 * @param at      when the check ran
 * @param allowed the findings allowed, in the order the store reported them; none when the database passed
 */
public record StoreSelfCheck(Instant at, List<SafetyFinding> allowed) {

    public StoreSelfCheck {
        Objects.requireNonNull(at, "at");
        allowed = List.copyOf(allowed);
    }
}
