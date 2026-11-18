package nuna.catalog.server.rest;

import nuna.catalog.audit.NamespaceChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.RestResponse;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.WriteResult;
import nuna.catalog.server.rest.Idempotency.Reply;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The namespace routes of D13, nested to any depth (D28). */
@RestController
class NamespaceRoutes {

    static final String NAMESPACES = "/v1/{prefix}/namespaces";
    static final String NAMESPACE = NAMESPACES + "/{namespace}";
    static final String PROPERTIES = NAMESPACE + "/properties";

    /** Attempts of a properties update that loses to concurrent updates before it gives up. */
    private static final int PROPERTY_ATTEMPTS = 5;

    private final CatalogStore store;
    private final Lookup lookup;
    private final Idempotency idempotency;
    private final AuditEvents audit;
    private final Callers callers;
    private final Guard guard;
    private final Clock clock;

    NamespaceRoutes(CatalogStore store, Lookup lookup, Idempotency idempotency, AuditEvents audit, Callers callers,
                    Guard guard, Clock clock) {
        this.store = store;
        this.lookup = lookup;
        this.idempotency = idempotency;
        this.audit = audit;
        this.callers = callers;
        this.guard = guard;
        this.clock = clock;
    }

    @GetMapping(NAMESPACES)
    ResponseEntity<String> list(@PathVariable String prefix,
                                @RequestParam(required = false) String parent,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) String pageSize,
                                HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> parentLevels = parent == null || parent.isEmpty() ? List.of() : Lookup.levels(parent);
        UUID parentId = parentLevels.isEmpty() ? catalog.id() : lookup.existingNamespace(catalog, parentLevels).id();
        guard.onParent(RequestContext.of(http, callers), catalog, parentId, parentLevels, Guard.LIST_NAMESPACES);
        Page<NamespaceRecord> page = lookup.listing(pageToken, pageSize,
                request -> store.listNamespaces(parentId, request));
        List<List<String>> namespaces = page.items().stream()
                .map(ns -> AuditEvents.path(parentLevels, ns.name().display()))
                .toList();
        return Http.json(200, RestResponse.namespaces(namespaces, page.nextToken().orElse(null)));
    }

    @PostMapping(NAMESPACES)
    ResponseEntity<String> create(@PathVariable String prefix,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        RequestContext request = RequestContext.of(http, callers);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "CREATE_NAMESPACE", catalog.id().toString(),
                body, ticket -> {
                    RestRequest.NewNamespace created = RestRequest.createNamespace(body == null ? "" : body);
                    List<String> levels = created.levels();
                    List<String> parentLevels = levels.subList(0, levels.size() - 1);
                    UUID parentId = parentLevels.isEmpty()
                            ? catalog.id() : lookup.existingNamespace(catalog, parentLevels).id();
                    guard.onParent(request, catalog, parentId, parentLevels, Guard.CREATE_NAMESPACE);
                    String last = levels.getLast();
                    NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(), parentId,
                            new Name(Names.normalize(last), last), created.properties(), 1);
                    String answer = RestResponse.namespace(levels, created.properties());
                    Instant now = clock.instant();
                    Change change = ticket.change(now, List.of(audit.event(now, new NamespaceChange(
                            NamespaceChange.Action.CREATED, AuditEvents.namespace(record.id(), catalog.name(), levels),
                            created.properties(), List.of(), request.actor()))), 200, answer);
                    WriteResult result;
                    try {
                        result = store.createNamespace(record, change);
                    } catch (CommitOutcomeUnknownException e) {
                        boolean applied = Http.readBack(() -> lookup.namespace(catalog, levels)
                                .filter(ns -> ns.id().equals(record.id())).isPresent());
                        if (!applied) {
                            throw Http.outcomeUnknown("Creating namespace " + Http.dotted(levels), e);
                        }
                        result = WriteResult.APPLIED;
                    }
                    return switch (result) {
                        case WriteResult.Applied applied -> new Reply(200, answer, true);
                        case WriteResult.NameTaken taken -> Reply.error(409, "AlreadyExistsException",
                                "Namespace already exists: " + Http.dotted(levels));
                        case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.CATALOG ->
                                Reply.error(404, "NoSuchWarehouseException",
                                        "The given warehouse does not exist: " + prefix);
                        case WriteResult.Missing missing -> Reply.error(404, "NoSuchNamespaceException",
                                "Namespace does not exist: " + Http.dotted(parentLevels));
                        default -> throw new IllegalStateException("createNamespace answered " + result);
                    };
                });
    }

    @GetMapping(NAMESPACE)
    ResponseEntity<String> load(@PathVariable String prefix, @PathVariable String namespace,
                                HttpServletRequest http) {
        List<String> levels = Lookup.levels(namespace);
        CatalogRecord catalog = lookup.catalog(prefix);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        guard.onNamespace(RequestContext.of(http, callers), catalog, record, levels, Guard.LOAD_NAMESPACE);
        return Http.json(200, RestResponse.namespace(levels, record.properties()));
    }

    @RequestMapping(path = NAMESPACE, method = RequestMethod.HEAD)
    ResponseEntity<Void> exists(@PathVariable String prefix, @PathVariable String namespace,
                                HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        Optional<NamespaceRecord> found = lookup.namespace(catalog, levels);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        guard.onNamespace(RequestContext.of(http, callers), catalog, found.get(), levels, Guard.NAMESPACE_EXISTS);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(NAMESPACE)
    ResponseEntity<String> drop(@PathVariable String prefix, @PathVariable String namespace,
                                @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        RequestContext request = RequestContext.of(http, callers);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "DROP_NAMESPACE",
                catalog.id() + "/" + String.join("\u001f", levels), "", ticket -> {
                    NamespaceRecord record = lookup.existingNamespace(catalog, levels);
                    guard.onNamespace(request, catalog, record, levels, Guard.DROP_NAMESPACE);
                    Instant now = clock.instant();
                    Change change = ticket.change(now, List.of(audit.event(now, new NamespaceChange(
                            NamespaceChange.Action.DROPPED, AuditEvents.namespace(record.id(), catalog.name(), levels),
                            Map.of(), List.of(), request.actor()))), 204, "");
                    WriteResult result;
                    try {
                        result = store.dropNamespace(record.id(), change);
                    } catch (CommitOutcomeUnknownException e) {
                        if (!Http.readBack(() -> store.readNamespace(record.id()).isEmpty())) {
                            throw Http.outcomeUnknown("Dropping namespace " + Http.dotted(levels), e);
                        }
                        result = WriteResult.APPLIED;
                    }
                    return switch (result) {
                        case WriteResult.Applied applied -> new Reply(204, "", true);
                        case WriteResult.Missing missing -> throw Http.noSuchNamespace(levels);
                        case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_EMPTY ->
                                Reply.error(409, "NamespaceNotEmptyException",
                                        "Namespace " + Http.dotted(levels) + " is not empty");
                        case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD ->
                                throw Http.forbidden("Namespace " + Http.dotted(levels)
                                        + " is under a legal hold and cannot be dropped");
                        default -> throw new IllegalStateException("dropNamespace answered " + result);
                    };
                });
    }

    @PostMapping(PROPERTIES)
    ResponseEntity<String> updateProperties(@PathVariable String prefix, @PathVariable String namespace,
                                            @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                            @RequestBody(required = false) String body,
                                            HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        RequestContext request = RequestContext.of(http, callers);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "UPDATE_NAMESPACE_PROPERTIES",
                catalog.id() + "/" + String.join("\u001f", levels), body, ticket -> {
                    RestRequest.PropertyChanges changes = RestRequest.updateNamespaceProperties(body == null ? "" : body);
                    guard.onNamespace(request, catalog, lookup.existingNamespace(catalog, levels), levels,
                            Guard.UPDATE_PROPERTIES);
                    for (int attempt = 1; attempt <= PROPERTY_ATTEMPTS; attempt++) {
                        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
                        Map<String, String> properties = new HashMap<>(record.properties());
                        List<String> removed = new ArrayList<>();
                        List<String> missing = new ArrayList<>();
                        for (String removal : new TreeSet<>(changes.removals())) {
                            (properties.remove(removal) != null ? removed : missing).add(removal);
                        }
                        properties.putAll(changes.updates());
                        String answer = RestResponse.propertiesUpdated(new TreeSet<>(changes.updates().keySet()),
                                removed, missing);
                        if (properties.equals(record.properties())) {
                            return Reply.of(200, answer);
                        }
                        Instant now = clock.instant();
                        Change change = ticket.change(now, List.of(audit.event(now, new NamespaceChange(
                                NamespaceChange.Action.PROPERTIES_CHANGED,
                                AuditEvents.namespace(record.id(), catalog.name(), levels), changes.updates(), removed,
                                request.actor()))), 200, answer);
                        WriteResult result;
                        try {
                            result = store.updateNamespaceProperties(record.id(), record.version(), properties, change);
                        } catch (CommitOutcomeUnknownException e) {
                            if (!Http.readBack(() -> store.readNamespace(record.id())
                                    .filter(ns -> ns.version() == record.version() + 1
                                            && ns.properties().equals(properties)).isPresent())) {
                                throw Http.outcomeUnknown("Updating properties of namespace " + Http.dotted(levels), e);
                            }
                            result = WriteResult.APPLIED;
                        }
                        switch (result) {
                            case WriteResult.Applied applied -> {
                                return new Reply(200, answer, true);
                            }
                            case WriteResult.Missing gone -> throw Http.noSuchNamespace(levels);
                            case WriteResult.VersionMismatch lost -> {
                            }
                            default -> throw new IllegalStateException("updateNamespaceProperties answered " + result);
                        }
                    }
                    throw CatalogError.of(500, Http.SERVICE_FAILURE, "Namespace " + Http.dotted(levels)
                            + " changed " + PROPERTY_ATTEMPTS + " times while its properties were updated; nothing was applied");
                });
    }
}
