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
import nuna.catalog.server.security.Holdings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.NamespaceRecord;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorization for the Iceberg routes (plan 2.2 step 3; D18). Each route asks before its work, after the
 * idempotency replay; every refusal is audited through {@link Denials}, naming what was refused (D18). Names are
 * hidden from callers without access (D18, amended 2026-11-19): a caller refused on a namespace, table or view it
 * holds nothing on ({@link Holdings}, which the management routes ask too) gets the route's own answer for one
 * that does not exist, {@code missing}, so it cannot tell whether the name exists; a caller that holds something on
 * it gets 403. That answer is a {@link CatalogError}, so an idempotency key records it as it records a missing
 * name's. A refusal on the catalog itself is still 403: whether catalog names are hidden there is item 68's
 * question. Storage access given by the maintenance exemption (D20, D30) is audited before it is given; if the
 * audit fails it is refused (503). Operation names are the REST spec's operation ids.
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
    private final Holdings holdings;

    Guard(Authorizer authorizer, Denials denials, AuditPublisher audit, RestSettings settings, Clock clock,
          Holdings holdings) {
        this.authorizer = authorizer;
        this.denials = denials;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.holdings = holdings;
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

    /** On the catalog itself: a refusal is 403 for every caller (item 68 asks whether catalog names are hidden). */
    void onCatalog(RequestContext request, CatalogRecord catalog, Operation operation) {
        allows(request, new Target.Catalog(catalog.id()),
                new Securable(Securable.Kind.CATALOG, catalog.id(), catalog.name(), List.of()), operation, () -> true);
    }

    /**
     * On a namespace. {@code missing} is the route's answer for a namespace that does not exist, which a refused
     * caller gets when it holds nothing on the namespace, above it or in it.
     */
    void onNamespace(RequestContext request, CatalogRecord catalog, NamespaceRecord namespace, List<String> levels,
                     Operation operation, CatalogError missing) {
        if (!allowsOnNamespace(request, catalog, namespace, levels, operation)) {
            throw missing;
        }
    }

    /**
     * {@link #onNamespace} answering instead of throwing, for HEAD, whose answer for a missing namespace is no
     * error: true when allowed; false when refused to a caller that may not learn the namespace exists, which the
     * route then answers as a missing one. A refusal to any other caller is 403.
     */
    boolean allowsOnNamespace(RequestContext request, CatalogRecord catalog, NamespaceRecord namespace,
                              List<String> levels, Operation operation) {
        return allows(request, new Target.Namespace(catalog.id(), namespace.id()),
                AuditEvents.namespace(namespace.id(), catalog.name(), levels), operation,
                () -> holdings.onAboveOrIn(request.caller(), catalog.id(), namespace.id()));
    }

    /**
     * On the parent of something new: the catalog for a top-level namespace, otherwise that namespace, as
     * {@link #onNamespace}, with {@code missing} the route's answer for a parent that does not exist.
     */
    void onParent(RequestContext request, CatalogRecord catalog, UUID parentId, List<String> parentLevels,
                  Operation operation, CatalogError missing) {
        if (parentLevels.isEmpty()) {
            onCatalog(request, catalog, operation);
        } else if (!allows(request, new Target.Namespace(catalog.id(), parentId),
                AuditEvents.namespace(parentId, catalog.name(), parentLevels), operation,
                () -> holdings.onAboveOrIn(request.caller(), catalog.id(), parentId))) {
            throw missing;
        }
    }

    /**
     * On the namespace of a commit or replace whose name no object holds, which would create one. The name is
     * what is hidden, so the caller is judged as for an object of that name there: one that holds nothing on the
     * namespace or above it gets {@code missing}, whatever it holds on other objects in the namespace, as it would
     * if an object it may not see held the name.
     */
    void onNewObject(RequestContext request, CatalogRecord catalog, NamespaceRecord namespace, List<String> levels,
                     Operation operation, CatalogError missing) {
        Target target = new Target.Namespace(catalog.id(), namespace.id());
        if (!allows(request, target, AuditEvents.namespace(namespace.id(), catalog.name(), levels), operation,
                () -> holdings.onOrAbove(request.caller(), target))) {
            throw missing;
        }
    }

    /**
     * {@link #UNDROP} for a dropped object, on the namespace it was dropped from, named by {@code levels}. A refusal
     * is 403: the caller named the object's own file and holds create on the namespace it registers into, which a
     * dropped object comes back only in (D13).
     */
    void onDropped(RequestContext request, CatalogRecord catalog, CatalogObject dropped, List<String> levels) {
        allows(request, new Target.Namespace(catalog.id(), dropped.namespaceId()),
                AuditEvents.namespace(dropped.namespaceId(), catalog.name(), levels), UNDROP, () -> true);
    }

    /**
     * On a table or view. {@code missing} is the route's answer for one that does not exist, which a refused caller
     * gets when it holds nothing on the object or above it.
     */
    void onObject(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                  Operation operation, CatalogError missing) {
        if (!allowsOnObject(request, catalog, object, path, operation)) {
            throw missing;
        }
    }

    /**
     * {@link #onObject} answering instead of throwing, for HEAD, whose answer for a missing object is no error,
     * and unregister, whose answer is the engine's: true when allowed; false when refused to a caller that may not
     * learn the object exists, which the route then answers as a missing one. A refusal to any other caller is 403.
     */
    boolean allowsOnObject(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                           Operation operation) {
        Target target = new Target.TableOrView(object);
        return allows(request, target, AuditEvents.securable(object, catalog.name(), path), operation,
                () -> holdings.onOrAbove(request.caller(), target));
    }

    /**
     * {@link #onObject} for a route that vends storage access. Answers whether the caller may also commit data to
     * the table, decided as a commit would be, in the same batch; a no is not a refusal and is not audited.
     */
    boolean onObjectMayWrite(RequestContext request, CatalogRecord catalog, CatalogObject object, List<String> path,
                             Operation operation, CatalogError missing) {
        Target target = new Target.TableOrView(object);
        List<Decision> decisions = authorizer.decide(request.caller(),
                List.of(new Authorizer.Request(target, operation), new Authorizer.Request(target, WRITE_DATA)));
        if (!decided(request, AuditEvents.securable(object, catalog.name(), path), operation, decisions.getFirst(),
                () -> holdings.onOrAbove(request.caller(), target))) {
            throw missing;
        }
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

    private boolean allows(RequestContext request, Target target, Securable named, Operation operation,
                           BooleanSupplier holdsSomething) {
        return decided(request, named, operation, authorizer.decide(request.caller(), target, operation),
                holdsSomething);
    }

    /**
     * Audits a denial, then refuses it with 403 when the caller holds something on the target, or answers false
     * when it holds nothing there; audits a maintenance exemption before the access is given; answers true for
     * the rest.
     */
    private boolean decided(RequestContext request, Securable named, Operation operation, Decision decision,
                            BooleanSupplier holdsSomething) {
        switch (decision) {
            case Decision.Denied denied -> {
                AccessDenied refused = denials.refuse(request.actor(), operation.name(), named,
                        denied.privilege() == null ? null : denied.privilege().wireName(),
                        denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
                if (holdsSomething.getAsBoolean()) {
                    throw refused;
                }
                return false;
            }
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
        return true;
    }
}
