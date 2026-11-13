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
    static final Operation UNREGISTER_TABLE = new Operation("unregisterTable", MODIFY, false);
    static final Operation LOAD_TABLE = new Operation("loadTable", SELECT, true);
    static final Operation TABLE_EXISTS = new Operation("tableExists", DESCRIBE, false);
    static final Operation DROP_TABLE = new Operation("dropTable", MODIFY, false);
    static final Operation RENAME_TABLE = new Operation("renameTable", MODIFY, false);
    static final Operation RENAME_TABLE_INTO = new Operation("renameTable", CREATE, false);
    static final Operation LOAD_CREDENTIALS = new Operation("loadCredentials", SELECT, true);
    static final Operation LIST_VIEWS = new Operation("listViews", DESCRIBE, false);
    static final Operation CREATE_VIEW = new Operation("createView", CREATE, false);
    static final Operation REGISTER_VIEW = new Operation("registerView", CREATE, false);
    static final Operation LOAD_VIEW = new Operation("loadView", SELECT, false);
    static final Operation VIEW_EXISTS = new Operation("viewExists", DESCRIBE, false);
    static final Operation REPLACE_VIEW = new Operation("replaceView", MODIFY, false);
    static final Operation DROP_VIEW = new Operation("dropView", MODIFY, false);
    static final Operation RENAME_VIEW = new Operation("renameView", MODIFY, false);
    static final Operation RENAME_VIEW_INTO = new Operation("renameView", CREATE, false);
    /**
     * Bringing a dropped table or view back by registering its file is an undrop (D13, D25): it needs what the
     * management undrop needs, {@code create} on the namespace the object was dropped from.
     */
    static final Operation UNDROP = new Operation("undropObject", CREATE, false);
    /** The least a table commit needs: a caller allowed it gets read-write credentials, any other read-only (D21). */
    static final Operation WRITE_DATA = new Operation("updateTable", WRITE, true);

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

    /** {@link #UNDROP} for a dropped object, on the namespace it was dropped from, named by {@code levels}. */
    void onDropped(RequestContext request, CatalogRecord catalog, CatalogObject dropped, List<String> levels) {
        require(request, new Target.Namespace(catalog.id(), dropped.namespaceId()),
                AuditEvents.namespace(dropped.namespaceId(), catalog.name(), levels), UNDROP);
    }

    void onObject(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                  Operation operation) {
        require(request, new Target.TableOrView(object), AuditEvents.securable(object, catalog.name(), path), operation);
    }

    /**
     * {@link #onObject} for a route that vends storage access. Answers whether the caller may also commit data to
     * the table, decided as a commit would be, in the same batch; a no is not a refusal and is not audited.
     */
    boolean onObjectMayWrite(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                             Operation operation) {
        Target target = new Target.TableOrView(object);
        List<Decision> decisions = authorizer.decide(request.caller(),
                List.of(new Authorizer.Request(target, operation), new Authorizer.Request(target, WRITE_DATA)));
        refuseIfDenied(request, AuditEvents.securable(object, catalog.name(), path), operation, decisions.getFirst());
        return decisions.get(1).allowed();
    }

    /**
     * Refuses an operation the caller's privileges allow but the object's governance forbids, such as
     * unregistering a regulated table: audited as a denial with {@code reason} (D18), and answered 403.
     */
    RuntimeException refuse(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                            Operation operation, String reason, String message) {
        return denials.refuse(request.actor(), operation.name(), AuditEvents.securable(object, catalog.name(), path),
                operation.privilege().wireName(), reason, message);
    }

    /** Whether the caller may commit data to the table, decided as a commit would be; not audited. */
    boolean mayWrite(RequestContext request, CatalogObject object) {
        return authorizer.decide(request.caller(), new Target.TableOrView(object), WRITE_DATA).allowed();
    }

    private void require(RequestContext request, Target target, Securable named, Operation operation) {
        refuseIfDenied(request, named, operation, authorizer.decide(request.caller(), target, operation));
    }

    private void refuseIfDenied(RequestContext request, Securable named, Operation operation, Decision decision) {
        if (!(decision instanceof Decision.Denied denied)) {
            return;
        }
        throw denials.refuse(request.actor(), operation.name(), named,
                denied.privilege() == null ? null : denied.privilege().wireName(),
                denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
    }
}
