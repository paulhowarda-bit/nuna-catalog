package nuna.catalog.audit.relay;

import nuna.catalog.audit.EventType;
import nuna.catalog.audit.StampedEvent;
import nuna.catalog.audit.publish.StampedTarget;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where the outbox goes (D23): every change to the audit feed, which the SIEM and the archive read,
 * and changes to catalog objects and refs also to the CloudEvents change stream, the interim stand-in for
 * the REST spec's events endpoint. Label changes go to both (D27, amended 2026-11-12), so engines can
 * refresh the labels they were sent; the governance records behind them, with their provenance, and
 * hold, grant and break-glass records stay on the audit feed. Each record goes to both as the log holds it, with
 * its run and number (item 276).
 */
public final class ChangeStream {

    /**
     * The event types the change stream carries: what happened to namespaces, tables and views, their labels, and the
     * catalog's branches and tags (D33, choice 11: a branch about to expire is announced here).
     */
    public static final Set<EventType> TYPES = EnumSet.of(EventType.COMMIT, EventType.DROP, EventType.UNDROP,
            EventType.PURGE, EventType.RENAME, EventType.NAMESPACE_CHANGE, EventType.UNREGISTER,
            EventType.LABEL_CHANGE, EventType.REF);

    private ChangeStream() {
    }

    /**
     * A target that delivers each batch whole to {@code audit}, then its object changes to
     * {@code changes}, in order. A failure on either fails the batch, which the relay then delivers
     * again to both.
     */
    public static StampedTarget fanOut(StampedTarget audit, StampedTarget changes) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(changes, "changes");
        return batch -> {
            audit.deliver(batch);
            List<StampedEvent> objectChanges = batch.stream().filter(e -> TYPES.contains(e.event().type())).toList();
            if (!objectChanges.isEmpty()) {
                changes.deliver(objectChanges);
            }
        };
    }
}
