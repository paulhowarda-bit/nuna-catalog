package nuna.catalog.server.rest;

import static nuna.catalog.security.Privilege.CREATE;
import static nuna.catalog.security.Privilege.DESCRIBE;
import static nuna.catalog.security.Privilege.MODIFY;
import static nuna.catalog.security.Privilege.SELECT;
import static nuna.catalog.security.Privilege.WRITE;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.MaintenanceExemption;
import nuna.catalog.audit.Securable;
import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.security.Denials;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.NamespaceRecord;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorization for the Iceberg routes (plan 2.2 step 3; D18). Each route asks before its work, after the
 * idempotency replay; a refusal is audited and answers 403 through {@link Denials} (D18). Storage access given
 * by the maintenance exemption (D20, D30) is audited before it is given; if the audit fails it is refused (503).
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
    /** What a client reports on a table it read or wrote: {@code select}, which every reader and writer holds. */
    static final Operation REPORT_METRICS = new Operation("reportMetrics", SELECT, false);
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
    /**
     * What snapshot expiry also sends, for a maintenance principal acting as itself only (D30): the schemas and
     * partition specs no snapshot uses any more. For anyone else they change the definition.
     */
    private static final Set<String> EXPIRY_UPDATES = Set.of("remove-schemas", "remove-partition-specs");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(Guard.class);

    private final Authorizer authorizer;
    private final Denials denials;
    private final AuditPublisher audit;
    private final RestSettings settings;
    private final Clock clock;

    Guard(Authorizer authorizer, Denials denials, AuditPublisher audit, RestSettings settings, Clock clock) {
        this.authorizer = authorizer;
        this.denials = denials;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * A table commit needs {@code write} when every update only adds or removes snapshots, refs or statistics,
     * or, for a maintenance principal acting as itself, removes unused schemas and partition specs as snapshot
     * expiry does (D30); and {@code modify} otherwise. A body that cannot be read is judged as the stricter; the
     * commit refuses it anyway.
     */
    Operation commit(RequestContext request, String body) {
        boolean maintenance = authorizer.maintenance(request.caller());
        Privilege needed = WRITE;
        try {
            JsonNode updates = JSON.readTree(body == null ? "" : body).path("updates");
            for (JsonNode update : updates) {
                String action = update.path("action").asString("");
                if (!DATA_UPDATES.contains(action) && !(maintenance && EXPIRY_UPDATES.contains(action))) {
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
        decided(request, AuditEvents.securable(object, catalog.name(), path), operation, decisions.getFirst());
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
        decided(request, named, operation, authorizer.decide(request.caller(), target, operation));
    }

    /** Refuses a denial, audits a maintenance exemption before the access is given, and lets the rest through. */
    private void decided(RequestContext request, Securable named, Operation operation, Decision decision) {
        switch (decision) {
            case Decision.Denied denied -> throw denials.refuse(request.actor(), operation.name(), named,
                    denied.privilege() == null ? null : denied.privilege().wireName(),
                    denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
            case Decision.Exempted exempted -> {
                try {
                    audit.publish(CatalogEvent.create(settings.auditSource(), clock.instant(),
                            new MaintenanceExemption(named, operation.name(), request.actor())));
                } catch (RuntimeException e) {
                    LOG.warn("no maintenance exemption for {} on {}: its audit failed", operation.name(), named.id(), e);
                    throw CatalogError.of(503, "ServiceUnavailableException",
                            "Storage access under the maintenance exemption cannot be audited now; retry later");
                }
            }
            case Decision.Allowed allowed -> {
            }
        }
    }
}
