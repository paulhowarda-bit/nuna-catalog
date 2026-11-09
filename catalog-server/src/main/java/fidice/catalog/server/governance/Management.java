package fidice.catalog.server.governance;

import fidice.catalog.audit.Actor;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import fidice.catalog.audit.EventData;
import fidice.catalog.core.CatalogError;
import fidice.catalog.core.Names;
import fidice.catalog.core.commit.GovernanceChain;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.server.rest.Callers;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.GovernanceAttribute;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Securable;
import fidice.catalog.store.WriteResult;
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
    static final String REQUEST_ID = "X-Request-ID";

    static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A catalog, namespace, table or view named by a management path.
     *
     * @param securable what grants, attributes and holds attach to
     * @param audit     the same, as audit records name it
     * @param object    the table or view, when the path names one
     * @param chain     its holds and attributes, and those of every level above it
     */
    record Target(CatalogRecord catalog, Securable securable, fidice.catalog.audit.Securable audit,
                  Optional<CatalogObject> object, GovernanceChain chain) {
    }

    /** The caller of one request and the actor its audit records name. */
    record Request(Caller caller, Actor actor) {
    }

    private final CatalogStore store;
    private final Callers callers;
    private final RestSettings settings;

    Management(CatalogStore store, Callers callers, RestSettings settings) {
        this.store = store;
        this.callers = callers;
        this.settings = settings;
    }

    CatalogRecord catalog(String prefix) {
        return store.findCatalog(Names.normalize(prefix)).orElseThrow(
                () -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
    }

    NamespaceRecord namespace(CatalogRecord catalog, List<String> levels) {
        return store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList())
                .orElseThrow(() -> CatalogError.of(404, "NoSuchNamespaceException",
                        "Namespace does not exist: " + String.join(".", levels)));
    }

    /** The target a path names: a catalog, or a namespace, or a table or view in it. */
    Target target(String prefix, String namespace, String table, String view) {
        CatalogRecord catalog = catalog(prefix);
        if (namespace == null) {
            return new Target(catalog, new Securable(Securable.Kind.CATALOG, catalog.id()),
                    new fidice.catalog.audit.Securable(fidice.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                            catalog.name(), List.of()),
                    Optional.empty(), GovernanceChain.ofCatalog(store, catalog.id()));
        }
        List<String> levels = RestRequest.namespaceLevels(namespace);
        NamespaceRecord record = namespace(catalog, levels);
        if (table == null && view == null) {
            return new Target(catalog, new Securable(Securable.Kind.NAMESPACE, record.id()),
                    new fidice.catalog.audit.Securable(fidice.catalog.audit.Securable.Kind.NAMESPACE, record.id(),
                            catalog.name(), levels),
                    Optional.empty(), GovernanceChain.of(store, record));
        }
        CatalogObject.Kind kind = table != null ? CatalogObject.Kind.TABLE : CatalogObject.Kind.VIEW;
        String name = table != null ? table : view;
        String qualified = String.join(".", levels) + "." + name;
        CatalogObject object = store.findObject(record.id(), Names.normalize(name))
                .filter(o -> o.kind() == kind)
                .orElseThrow(() -> kind == CatalogObject.Kind.TABLE
                        ? CatalogError.of(404, "NoSuchTableException", "Table does not exist: " + qualified)
                        : CatalogError.of(404, "NoSuchViewException", "View does not exist: " + qualified));
        return new Target(catalog, object.securable(), audit(catalog, levels, name, object),
                Optional.of(object), GovernanceChain.of(store, object));
    }

    static fidice.catalog.audit.Securable audit(CatalogRecord catalog, List<String> levels, String name,
                                                CatalogObject object) {
        List<String> path = new ArrayList<>(levels);
        path.add(name);
        return new fidice.catalog.audit.Securable(object.kind() == CatalogObject.Kind.TABLE
                ? fidice.catalog.audit.Securable.Kind.TABLE : fidice.catalog.audit.Securable.Kind.VIEW,
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
        String id = http.getHeader(REQUEST_ID);
        if (id == null || id.isBlank() || id.length() > 128) {
            id = UUID.randomUUID().toString();
        }
        return new Request(caller, new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), id));
    }

    /** The change that writes {@code data} to the outbox with the mutation, at {@code at}. */
    Change change(Instant at, EventData data) {
        CatalogEvent event = CatalogEvent.create(settings.auditSource(), at, data);
        return new Change(UUID.randomUUID(), at, List.of(new fidice.catalog.store.OutboxEvent(data.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
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

    /** Whether a DR freeze covers the chain; throws 403 when it does. */
    static void requireUnfrozen(GovernanceChain chain, String what) {
        chain.nearest(GovernanceAttribute.Kind.DR_FREEZE).ifPresent(freeze -> {
            throw CatalogError.of(403, "ForbiddenException", what + " is frozen for DR reconcile: " + freeze.value());
        });
    }
}
