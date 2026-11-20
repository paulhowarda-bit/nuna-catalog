package nuna.catalog.audit;

import java.util.List;

/**
 * Maps each audit record to its row of the queryable audit table (D23, amended 2026-11-20): every event type says
 * who made the request, what it was about, and the operation, privilege and reason where it has them. Everything
 * else stays in the row's {@code data}, the payload as JSON, so a new payload version needs no new column.
 */
public final class AuditRows {

    private AuditRows() {
    }

    /**
     * The row for one record of the audit topic.
     *
     * @param partition the topic partition it came from
     * @param offset    its offset there
     * @throws IllegalArgumentException for an event type with no mapping here
     */
    public static AuditRow of(CatalogEvent event, int partition, long offset) {
        Parts parts = switch (event.data()) {
            case Commit e -> on(e.actor(), e.object());
            case GrantChange e -> new Parts(e.actor(), e.actor().requestId(), e.securable(), null, e.privilege(), null);
            case Drop e -> on(e.actor(), e.object());
            case BreakGlass e -> on(e.actor(), e.securable());
            case ObjectRead e -> on(e.actor(), e.object());
            case Denial e -> new Parts(e.actor(), e.actor().requestId(), e.securable(), e.operation(), e.privilege(),
                    e.reason());
            // Nobody authenticated: the claimed subject and client are in the payload, not in principal.
            case AuthFailure e -> new Parts(null, e.requestId(), null, null, null, e.reason());
            case CredentialIssue e -> on(e.actor(), e.object());
            case NamespaceChange e -> on(e.actor(), e.namespace());
            case Rename e -> on(e.actor(), e.object());
            case EngineChange e -> on(e.actor(), null);
            // A created catalog is the object; a storage profile is no securable.
            case StorageSetup e -> on(e.actor(), e.action() == StorageSetup.Action.CATALOG_CREATED
                    ? new Securable(Securable.Kind.CATALOG, e.id(), e.name(), List.of()) : null);
            case GovernanceChange e -> on(e.actor(), e.target());
            case HoldChange e -> new Parts(e.actor(), e.actor().requestId(), e.target(), null, null, e.reason());
            case Undrop e -> on(e.actor(), e.object());
            case Purge e -> on(e.actor(), e.object());
            case AuditRead e -> on(e.actor(), e.target());
            case Unregister e -> on(e.actor(), e.object());
            case LabelChange e -> on(e.actor(), e.target());
            case PurgeClaim e -> on(e.actor(), e.object());
            case FileDeletion e -> on(e.actor(), e.object());
            case MaintenanceExemption e -> new Parts(e.actor(), e.actor().requestId(), e.object(), e.operation(), null,
                    null);
            // A type added without a mapping fails here and in AuditRowsTest, never written with empty columns.
            default -> throw new IllegalArgumentException("no audit table mapping for " + event.type().typeName());
        };
        Actor actor = parts.actor();
        Securable object = parts.object();
        return new AuditRow(event.id().toString(), event.time(), event.type().typeName(), event.source().toString(),
                actor == null ? null : actor.principal(), actor == null ? null : actor.endUser(),
                actor == null ? null : actor.engine(), parts.requestId(),
                object == null ? null : object.catalog(), object == null ? null : object.kind().name(),
                object == null ? null : object.id().toString(), object == null ? null : object.path(),
                parts.operation(), parts.privilege(), parts.reason(), EventCodec.MAPPER.writeValueAsString(event.data()),
                partition, offset);
    }

    private static Parts on(Actor actor, Securable object) {
        return new Parts(actor, actor.requestId(), object, null, null, null);
    }

    /** What a type contributes to its row beyond the envelope and the payload. */
    private record Parts(Actor actor, String requestId, Securable object, String operation, String privilege,
                         String reason) {
    }
}
