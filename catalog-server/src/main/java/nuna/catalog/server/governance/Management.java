package nuna.catalog.server.governance;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventData;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the management routes share: resolving the catalog, namespace, table or view a path names,
 * the caller and request id, the change records of a write, and its unknown outcome (D9, D23).
 */
@Component
class Management {

    static final String ROOT = "/management/v1";
    static final String CATALOG = ROOT + "/catalogs/{catalog}";
    static final String NAMESPACE = CATALOG + "/namespaces/{namespace}";
    static final String TABLE = NAMESPACE + "/tables/{table}";
    static final String VIEW = NAMESPACE + "/views/{view}";
    static final String REQUEST_ID = RequestIds.HEADER;

    static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A catalog, namespace, table or view named by a management path.
     *
     * @param securable what grants, attributes and holds attach to
     * @param audit     the same, as audit records name it
     * @param object    the table or view, when the path names one
     * @param chain     its holds and attributes, and those of every level above it
     * @param missing   the answer for a path that names nothing, which a refused caller that holds nothing on the
     *                  namespace, table or view gets too (D18)
     */
    record Target(CatalogRecord catalog, Securable securable, nuna.catalog.audit.Securable audit,
                  Optional<CatalogObject> object, GovernanceChain chain, CatalogError missing) {
    }

    /** The caller of one request and the actor its audit records name. */
    record Request(Caller caller, Actor actor) {
    }

    private final CatalogStore store;
    private final Callers callers;
    private final RestSettings settings;
    private final Holdings holdings;

    Management(CatalogStore store, Callers callers, RestSettings settings, Holdings holdings) {
        this.store = store;
        this.callers = callers;
        this.settings = settings;
        this.holdings = holdings;
    }

    /**
     * The catalog {@code prefix} names. A caller that holds nothing on it is answered as for a name no catalog has,
     * its refusal of {@code operation} audited (D18, amended 2026-11-20; {@link Holdings#catalog}).
     *
     * @param privilege what the operation needs, or null when it needs no one privilege
     */
    CatalogRecord catalog(String prefix, Request request, String operation, Privilege privilege) {
        return holdings.catalog(prefix, request.caller(), request.actor(), operation, privilege);
    }

    NamespaceRecord namespace(CatalogRecord catalog, List<String> levels) {
        return store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList())
                .orElseThrow(() -> noSuchNamespace(levels));
    }

    private static CatalogError noSuchNamespace(List<String> levels) {
        return CatalogError.of(404, "NoSuchNamespaceException", "Namespace does not exist: " + String.join(".", levels));
    }

    /**
     * The target a path names: a catalog, or a namespace, or a table or view in it. Its catalog is found as
     * {@link #catalog} finds it, for {@code operation}. A table or view path whose namespace does not exist answers as
     * one whose table or view does not, as the Iceberg routes do, so it does not tell whether the namespace exists
     * (D18).
     */
    Target target(Request request, String operation, Privilege privilege, String prefix, String namespace,
                  String table, String view) {
        CatalogRecord catalog = catalog(prefix, request, operation, privilege);
        if (namespace == null) {
            return new Target(catalog, new Securable(Securable.Kind.CATALOG, catalog.id()),
                    new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                            catalog.name(), List.of()),
                    Optional.empty(), GovernanceChain.ofCatalog(store, catalog.id()),
                    CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
        }
        List<String> levels = RestRequest.namespaceLevels(namespace);
        if (table == null && view == null) {
            NamespaceRecord record = namespace(catalog, levels);
            return new Target(catalog, new Securable(Securable.Kind.NAMESPACE, record.id()),
                    new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.NAMESPACE, record.id(),
                            catalog.name(), levels),
                    Optional.empty(), GovernanceChain.of(store, record), noSuchNamespace(levels));
        }
        CatalogObject.Kind kind = table != null ? CatalogObject.Kind.TABLE : CatalogObject.Kind.VIEW;
        String name = table != null ? table : view;
        String qualified = String.join(".", levels) + "." + name;
        CatalogError missing = kind == CatalogObject.Kind.TABLE
                ? CatalogError.of(404, "NoSuchTableException", "Table does not exist: " + qualified)
                : CatalogError.of(404, "NoSuchViewException", "View does not exist: " + qualified);
        CatalogObject object = store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList())
                .flatMap(record -> store.findObject(record.id(), Names.normalize(name)))
                .filter(o -> o.kind() == kind)
                .orElseThrow(() -> missing);
        return new Target(catalog, object.securable(), audit(catalog, levels, name, object),
                Optional.of(object), GovernanceChain.of(store, object), missing);
    }

    static nuna.catalog.audit.Securable audit(CatalogRecord catalog, List<String> levels, String name,
                                                CatalogObject object) {
        List<String> path = new ArrayList<>(levels);
        path.add(name);
        return new nuna.catalog.audit.Securable(object.kind() == CatalogObject.Kind.TABLE
                ? nuna.catalog.audit.Securable.Kind.TABLE : nuna.catalog.audit.Securable.Kind.VIEW,
                object.id(), catalog.name(), path);
    }

    /** The namespace levels of a namespace id, outermost first, by display name; empty when it is gone. */
    Optional<List<String>> levels(UUID catalogId, UUID namespaceId) {
        List<String> levels = new ArrayList<>();
        UUID next = namespaceId;
        while (!next.equals(catalogId)) {
            Optional<NamespaceRecord> namespace = store.readNamespace(next);
            if (namespace.isEmpty()) {
                return Optional.empty();
            }
            levels.addFirst(namespace.get().name().display());
            next = namespace.get().parentId();
        }
        return Optional.of(levels);
    }

    Request request(HttpServletRequest http) {
        Caller caller = callers.caller(http);
        return new Request(caller, new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), RequestIds.of(http)));
    }

    /** The change that writes {@code data} to the outbox with the mutation, at {@code at}. */
    Change change(Instant at, EventData data) {
        return change(at, List.of(data));
    }

    /** The change that writes each of {@code data}, in order, to the outbox with the mutation, at {@code at}. */
    Change change(Instant at, List<EventData> data) {
        return new Change(UUID.randomUUID(), at, data.stream().map(each -> new nuna.catalog.store.OutboxEvent(
                each.type().typeName(), new String(EventCodec.encode(CatalogEvent.create(settings.auditSource(), at,
                        each)), StandardCharsets.UTF_8))).toList(), Optional.empty());
    }

    /**
     * Runs a write; an unknown outcome is settled by reading back, and a 500 if the read cannot tell.
     *
     * @param appliedIfSeen whether the write's effect is visible, asked only after an unknown outcome
     */
    static WriteResult write(String what, Supplier<WriteResult> write, BooleanSupplier appliedIfSeen) {
        try {
            return write.get();
        } catch (CommitOutcomeUnknownException e) {
            boolean seen;
            try {
                seen = appliedIfSeen.getAsBoolean();
            } catch (RuntimeException readFailed) {
                seen = false;
            }
            if (seen) {
                return WriteResult.APPLIED;
            }
            throw CatalogError.of(500, "ServiceFailureException", what + " may or may not have been applied: "
                    + e.getMessage());
        }
    }

    /** The request body as JSON; an empty body is an empty object. */
    static JsonNode body(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (!node.isObject()) {
                throw CatalogError.badRequest("The request body must be a JSON object");
            }
            return node;
        } catch (tools.jackson.core.JacksonException e) {
            throw CatalogError.badRequest("The request body is not JSON: " + e.getOriginalMessage());
        }
    }

    /** A required string field of a JSON body. */
    static String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw CatalogError.badRequest("Missing field " + field + ": a non-empty string");
        }
        if (value.asString().length() > maxLength) {
            throw CatalogError.badRequest("Field " + field + " is longer than " + maxLength + " characters");
        }
        return value.asString();
    }
}
