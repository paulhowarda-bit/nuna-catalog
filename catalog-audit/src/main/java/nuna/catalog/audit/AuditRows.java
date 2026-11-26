package nuna.catalog.audit;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Maps each audit record to its row of the queryable audit table (D23, amended 2026-11-20): every event type says
 * who made the request, what it was about, and the operation, privilege and reason where it has them. Everything
 * else stays in the row's {@code data}, the payload as JSON, so a new payload version needs no new column. A record
 * from the log also carries where it was logged ({@link LogStamp}), in {@code log_run} and {@code log_seq} (backlog
 * 272), which place it in its run's archive chain. A commit, a read and a ref record name the catalog ref in
 * {@code branch}, and a ref record lists its ref and its tables in {@code subject_ids} (backlog 169), so the feed
 * filters by them without reading payloads.
 */
public final class AuditRows {

    private AuditRows() {
    }

    /**
     * The row for one record that carries no stamp: from a server older than the stamp (item 262), which did not
     * stamp its log lines, or before item 276, its copies on the audit topic.
     *
     * @param partition the topic partition it came from
     * @param offset    its offset there
     * @throws IllegalArgumentException for an event type with no mapping here
     */
    public static AuditRow of(CatalogEvent event, int partition, long offset) {
        return of(event, Optional.empty(), partition, offset);
    }

    /**
     * The row for one record, with where it was logged when it came from the log.
     *
     * @param stamp     its run and number in the run, from the log's line or the topic's copy (item 276); empty for a
     *                  record that carries none
     * @param partition the topic partition it came from, or the log writer's
     * @param offset    its offset there, or the number the log writer gave it
     * @throws IllegalArgumentException for an event type with no mapping here
     */
    public static AuditRow of(CatalogEvent event, Optional<LogStamp> stamp, int partition, long offset) {
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
            case RowColumnRuleChange e -> on(e.actor(), e.table());
            case HoldChange e -> new Parts(e.actor(), e.actor().requestId(), e.target(), null, null, e.reason());
            case Undrop e -> on(e.actor(), e.object());
            case Purge e -> on(e.actor(), e.object());
            case AuditRead e -> on(e.actor(), e.target());
            case Unregister e -> on(e.actor(), e.object());
            // A ref is no securable: its row names the catalog it belongs to.
            case RefChange e -> on(e.actor(), e.catalog());
            case LabelChange e -> on(e.actor(), e.target());
            case PurgeClaim e -> on(e.actor(), e.object());
            case FileDeletion e -> on(e.actor(), e.object());
            case MaintenanceExemption e -> new Parts(e.actor(), e.actor().requestId(), e.object(), e.operation(), null,
                    null);
            // A server's own record: no one asked for it, and it is about no object.
            case RunEnd e -> new Parts(null, null, null, null, null, null);
            // A type added without a mapping fails here and in AuditRowsTest, never written with empty columns.
            default -> throw new IllegalArgumentException("no audit table mapping for " + event.type().typeName());
        };
        Actor actor = parts.actor();
        Securable object = parts.object();
        // The ref a record went through or is about, and what a ref record is about besides its catalog (backlog 169);
        // a grant's branch, when it names one (backlog 113).
        String branch = switch (event.data()) {
            case Commit e -> e.branch();
            case ObjectRead e -> e.branch();
            case RefChange e -> e.name();
            case GrantChange e -> e.branch();
            default -> null;
        };
        List<String> subjectIds = event.data() instanceof RefChange e
                ? Stream.concat(Stream.of(e.ref().toString()), e.tables().stream().map(t -> t.id().toString())).toList()
                : null;
        return new AuditRow(event.id().toString(), event.time(), event.type().typeName(), event.source().toString(),
                actor == null ? null : actor.principal(), actor == null ? null : actor.endUser(),
                actor == null ? null : actor.engine(), parts.requestId(),
                object == null ? null : object.catalog(), object == null ? null : object.kind().name(),
                object == null ? null : object.id().toString(), object == null ? null : object.path(),
                parts.operation(), parts.privilege(), parts.reason(), EventCodec.payload(event),
                partition, offset, stamp.map(s -> s.run().toString()).orElse(null),
                stamp.map(LogStamp::sequence).orElse(null), branch, subjectIds);
    }

    /**
     * The record a row holds, rebuilt from its envelope columns and its payload: the event as the topic and the archive
     * carry it, which {@link EventCodec#encode} writes back out. The row's time is kept to the microsecond.
     *
     * @throws IllegalArgumentException if the row's type is unknown or its payload is not that type's
     */
    public static CatalogEvent event(AuditRow row) {
        EventType type = EventType.fromTypeName(row.type());
        try {
            EventData data = EventCodec.payload(type, row.data());
            return new CatalogEvent(java.util.UUID.fromString(row.eventId()), java.net.URI.create(row.source()),
                    row.time(), data);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("the row of " + row.eventId() + " holds no " + row.type() + " payload: "
                    + e.getMessage(), e);
        }
    }

    private static Parts on(Actor actor, Securable object) {
        return new Parts(actor, actor.requestId(), object, null, null, null);
    }

    /** What a type contributes to its row beyond the envelope and the payload. */
    private record Parts(Actor actor, String requestId, Securable object, String operation, String privilege,
                         String reason) {
    }
}
