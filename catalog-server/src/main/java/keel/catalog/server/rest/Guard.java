package keel.catalog.server.rest;

import static keel.catalog.security.Privilege.CREATE;
import static keel.catalog.security.Privilege.DESCRIBE;
import static keel.catalog.security.Privilege.MODIFY;
import static keel.catalog.security.Privilege.SELECT;
import static keel.catalog.security.Privilege.WRITE;

import keel.catalog.audit.Securable;
import keel.catalog.security.Authorizer;
import keel.catalog.security.Authorizer.Decision;
import keel.catalog.security.Authorizer.Operation;
import keel.catalog.security.Authorizer.Target;
import keel.catalog.security.Privilege;
import keel.catalog.server.security.Denials;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.NamespaceRecord;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorization for the Iceberg routes (plan 2.2 step 3; D18). Each route asks before its work, after the
 * idempotency replay; a refusal is audited and answers 403 through {@link Denials} (D18).
 * Operation names are the REST spec's operation ids.
 */
@Component
class Guard {

    static final Operation LIST_NAMESPACES = new Operation("listNamespaces", DESCRIBE, false);
    static final Operation CREATE_NAMESPACE = new Operation("createNamespace", CREATE, false);
    static final Operation LOAD_NAMESPACE = new Operation("loadNamespaceMetadata", DESCRIBE, false);
    static final Operation NAMESPACE_EXISTS = new Operation("namespaceExists", DESCRIBE, false);
    static final Operation DROP_NAMESPACE = new Operation("dropNamespace", MODIFY, false);
    static final Operation UPDATE_PROPERTIES = new Operation("updateProperties", MODIFY, false);
    static final Operation LIST_TABLES = new Operation("listTables", DESCRIBE, false);
    static final Operation CREATE_TABLE = new Operation("createTable", CREATE, false);
    static final Operation REGISTER_TABLE = new Operation("registerTable", CREATE, false);
    static final Operation LOAD_TABLE = new Operation("loadTable", SELECT, true);
    static final Operation TABLE_EXISTS = new Operation("tableExists", DESCRIBE, false);
    static final Operation DROP_TABLE = new Operation("dropTable", MODIFY, false);
    static final Operation RENAME_TABLE = new Operation("renameTable", MODIFY, false);
    static final Operation RENAME_TABLE_INTO = new Operation("renameTable", CREATE, false);
    static final Operation LOAD_CREDENTIALS = new Operation("loadCredentials", SELECT, true);
    static final Operation LIST_VIEWS = new Operation("listViews", DESCRIBE, false);
    static final Operation CREATE_VIEW = new Operation("createView", CREATE, false);
    static final Operation LOAD_VIEW = new Operation("loadView", SELECT, false);
    static final Operation VIEW_EXISTS = new Operation("viewExists", DESCRIBE, false);
    static final Operation REPLACE_VIEW = new Operation("replaceView", MODIFY, false);
    static final Operation DROP_VIEW = new Operation("dropView", MODIFY, false);
    static final Operation RENAME_VIEW = new Operation("renameView", MODIFY, false);
    static final Operation RENAME_VIEW_INTO = new Operation("renameView", CREATE, false);

    /** Updates that change only data and its bookkeeping: {@code write}. Anything else changes the definition. */
    private static final Set<String> DATA_UPDATES = Set.of("add-snapshot", "remove-snapshots", "set-snapshot-ref",
            "remove-snapshot-ref", "set-statistics", "remove-statistics", "set-partition-statistics",
            "remove-partition-statistics");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Authorizer authorizer;
    private final Denials denials;

    Guard(Authorizer authorizer, Denials denials) {
        this.authorizer = authorizer;
        this.denials = denials;
    }

    /**
     * A table commit needs {@code write} when every update only adds or removes snapshots, refs or statistics,
     * and {@code modify} otherwise. A body that cannot be read is judged as the stricter; the commit refuses it anyway.
     */
    static Operation commit(String body) {
        Privilege needed = WRITE;
        try {
            JsonNode updates = JSON.readTree(body == null ? "" : body).path("updates");
            for (JsonNode update : updates) {
                if (!DATA_UPDATES.contains(update.path("action").asString(""))) {
                    needed = MODIFY;
                }
            }
        } catch (RuntimeException e) {
            needed = MODIFY;
        }
        return new Operation("updateTable", needed, true);
    }

    void onCatalog(RequestContext request, CatalogRecord catalog, Operation operation) {
        require(request, new Target.Catalog(catalog.id()),
                new Securable(Securable.Kind.CATALOG, catalog.id(), catalog.name(), List.of()), operation);
    }

    void onNamespace(RequestContext request, CatalogRecord catalog, NamespaceRecord namespace, List<String> levels,
                     Operation operation) {
        require(request, new Target.Namespace(catalog.id(), namespace.id()),
                AuditEvents.namespace(namespace.id(), catalog.name(), levels), operation);
    }

    /** On the parent of something new: the catalog for a top-level namespace, otherwise that namespace. */
    void onParent(RequestContext request, CatalogRecord catalog, UUID parentId, List<String> parentLevels,
                  Operation operation) {
        if (parentLevels.isEmpty()) {
            onCatalog(request, catalog, operation);
        } else {
            require(request, new Target.Namespace(catalog.id(), parentId),
                    AuditEvents.namespace(parentId, catalog.name(), parentLevels), operation);
        }
    }

    void onObject(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                  Operation operation) {
        require(request, new Target.TableOrView(object), AuditEvents.securable(object, catalog.name(), path), operation);
    }

    private void require(RequestContext request, Target target, Securable named, Operation operation) {
        if (!(authorizer.decide(request.caller(), target, operation) instanceof Decision.Denied denied)) {
            return;
        }
        throw denials.refuse(request.actor(), operation.name(), named,
                denied.privilege() == null ? null : denied.privilege().wireName(),
                denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
    }
}
