package nuna.catalog.audit;

import java.net.URI;
import java.util.List;

/**
 * The event types of D23, each with its current payload schema version. Changes (commits, grants,
 * drops, undrops, purge claims and purges, unregisters, namespace changes, renames, governance attributes
 * and the label changes they make, holds, break-glass, storage setup, pending file deletions, refs, row filters and column masks) travel through
 * the outbox; reads, denials, authentication failures, credential issues, audit reads and maintenance
 * exemptions are published synchronously, and so is a branch's expiry warning, which changes no record. A server's
 * run end is the last record it writes to the log (backlog 262).
 *
 * <p>A type whose current version added fields to the one before still writes the one before whenever all of those
 * fields would be null, so a reader that knows only the earlier version reads every record it could have read before.
 * A ref record made with a description is version 2; every other ref record, version 1 (backlog 167). A grant that
 * names a branch is version 2; a grant on every ref, version 1. A credential that writes one branch's prefix only is
 * version 2; one that writes its whole table, version 1 (backlog 113).
 */
public enum EventType {
    COMMIT("commit", 1, Commit.class),
    GRANT_CHANGE("grant", 2, GrantChange.class, "branch", "branchId"),
    DROP("drop", 1, Drop.class),
    BREAK_GLASS("break-glass", 1, BreakGlass.class),
    OBJECT_READ("read", 1, ObjectRead.class),
    DENIAL("denial", 1, Denial.class),
    AUTH_FAILURE("auth-failure", 1, AuthFailure.class),
    CREDENTIAL_ISSUE("credential-issue", 2, CredentialIssue.class, "writePrefix"),
    NAMESPACE_CHANGE("namespace", 1, NamespaceChange.class),
    RENAME("rename", 1, Rename.class),
    ENGINE_CHANGE("engine", 1, EngineChange.class),
    STORAGE_SETUP("storage-setup", 1, StorageSetup.class),
    GOVERNANCE_CHANGE("governance", 1, GovernanceChange.class),
    HOLD_CHANGE("hold", 1, HoldChange.class),
    UNDROP("undrop", 1, Undrop.class),
    PURGE("purge", 1, Purge.class),
    AUDIT_READ("audit-read", 1, AuditRead.class),
    UNREGISTER("unregister", 1, Unregister.class),
    LABEL_CHANGE("label", 1, LabelChange.class),
    PURGE_CLAIM("purge-claim", 1, PurgeClaim.class),
    FILE_DELETION("file-deletion", 1, FileDeletion.class),
    MAINTENANCE_EXEMPTION("maintenance-exemption", 1, MaintenanceExemption.class),
    /** Version 2 adds {@code description}, the purpose a ref was made with (backlog 167). */
    REF("ref", 2, RefChange.class, "description"),
    ROW_COLUMN_RULE_CHANGE("row-column-rule", 1, RowColumnRuleChange.class),
    RUN_END("run-end", 1, RunEnd.class);

    private final String typeName;
    private final String name;
    private final int version;
    private final Class<? extends EventData> dataClass;
    private final List<String> added;

    /** @param added the fields {@code version} added to the version before it; none when it is version 1 */
    EventType(String name, int version, Class<? extends EventData> dataClass, String... added) {
        if ((version == 1) != (added.length == 0)) {
            throw new IllegalArgumentException(name + ": a later version names the fields it added");
        }
        this.typeName = "nuna.catalog." + name;
        this.name = name;
        this.version = version;
        this.dataClass = dataClass;
        this.added = List.of(added);
    }

    /** The CloudEvents {@code type} attribute. */
    public String typeName() {
        return typeName;
    }

    /** The CloudEvents {@code dataschema} attribute of the current payload version. */
    public URI dataSchema() {
        return dataSchema(version);
    }

    /** The CloudEvents {@code dataschema} attribute naming one payload version. */
    public URI dataSchema(int version) {
        return URI.create("urn:nuna:catalog:event:" + name + ":" + version);
    }

    /** The current payload version. */
    public int version() {
        return version;
    }

    /** The fields the current version added to the one before; a payload of the one before has none of them. */
    List<String> added() {
        return added;
    }

    Class<? extends EventData> dataClass() {
        return dataClass;
    }

    static EventType fromTypeName(String typeName) {
        for (EventType type : values()) {
            if (type.typeName.equals(typeName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown event type " + typeName);
    }
}
