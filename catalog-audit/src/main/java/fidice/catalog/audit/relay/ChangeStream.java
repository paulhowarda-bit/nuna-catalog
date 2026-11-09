package fidice.catalog.audit.relay;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventType;
import fidice.catalog.audit.publish.RelayTarget;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where the outbox goes (D23): every change to the audit feed, which the SIEM and the archive read,
 * and changes to catalog objects also to the CloudEvents change stream, the interim stand-in for
 * the REST spec's events endpoint. Governance, hold, grant and break-glass records stay on the audit
 * feed.
 */
public final class ChangeStream {

    /** The event types the change stream carries: what happened to namespaces, tables and views. */
    public static final Set<EventType> TYPES = EnumSet.of(EventType.COMMIT, EventType.DROP, EventType.UNDROP,
            EventType.PURGE, EventType.RENAME, EventType.NAMESPACE_CHANGE);

    private ChangeStream() {
    }

    /**
     * A target that delivers each batch whole to {@code audit}, then its object changes to
     * {@code changes}, in order. A failure on either fails the batch, which the relay then delivers
     * again to both.
     */
    public static RelayTarget fanOut(RelayTarget audit, RelayTarget changes) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(changes, "changes");
        return batch -> {
            audit.deliver(batch);
            List<CatalogEvent> objectChanges = batch.stream().filter(e -> TYPES.contains(e.type())).toList();
            if (!objectChanges.isEmpty()) {
                changes.deliver(objectChanges);
            }
        };
    }
}
