package nuna.catalog.audit;

/**
 * The payload of a {@link CatalogEvent}: one record per event type D23 lists. Each record is
 * version 1 of its type's schema; a change to its JSON form is a new version, not an edit.
 */
public sealed interface EventData
        permits Commit, GrantChange, Drop, BreakGlass, ObjectRead, Denial, AuthFailure, CredentialIssue,
        NamespaceChange, Rename, EngineChange, StorageSetup, GovernanceChange, HoldChange, Undrop, Purge,
        AuditRead, Unregister, LabelChange, PurgeClaim, FileDeletion, MaintenanceExemption, RefChange, RowColumnRuleChange,
        RunEnd {

    EventType type();

    /** The id of the securable the event is about, or null when there is none. */
    String subject();
}
