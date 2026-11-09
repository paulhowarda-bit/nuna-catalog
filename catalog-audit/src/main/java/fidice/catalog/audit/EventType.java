package fidice.catalog.audit;

import java.net.URI;

/**
 * The event types of D23, each with its current payload schema version. Changes (commits, grants,
 * drops, namespace changes, renames, break-glass, storage setup) travel through the outbox; reads, denials,
 * authentication failures and credential issues are published synchronously.
 */
public enum EventType {
    COMMIT("commit", 1, Commit.class),
    GRANT_CHANGE("grant", 1, GrantChange.class),
    DROP("drop", 1, Drop.class),
    BREAK_GLASS("break-glass", 1, BreakGlass.class),
    OBJECT_READ("read", 1, ObjectRead.class),
    DENIAL("denial", 1, Denial.class),
    AUTH_FAILURE("auth-failure", 1, AuthFailure.class),
    CREDENTIAL_ISSUE("credential-issue", 1, CredentialIssue.class),
    NAMESPACE_CHANGE("namespace", 1, NamespaceChange.class),
    RENAME("rename", 1, Rename.class),
    ENGINE_CHANGE("engine", 1, EngineChange.class),
    STORAGE_SETUP("storage-setup", 1, StorageSetup.class);

    private final String typeName;
    private final URI dataSchema;
    private final Class<? extends EventData> dataClass;

    EventType(String name, int version, Class<? extends EventData> dataClass) {
        this.typeName = "fidice.catalog." + name;
        this.dataSchema = URI.create("urn:fidice:catalog:event:" + name + ":" + version);
        this.dataClass = dataClass;
    }

    /** The CloudEvents {@code type} attribute. */
    public String typeName() {
        return typeName;
    }

    /** The CloudEvents {@code dataschema} attribute; it names the payload version. */
    public URI dataSchema() {
        return dataSchema;
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
