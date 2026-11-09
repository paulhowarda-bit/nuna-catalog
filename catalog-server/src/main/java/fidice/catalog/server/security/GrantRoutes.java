package fidice.catalog.server.security;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import fidice.catalog.audit.GrantChange;
import fidice.catalog.core.CatalogError;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.security.Authorizer;
import fidice.catalog.security.Authorizer.Decision;
import fidice.catalog.security.Authorizer.Operation;
import fidice.catalog.security.Privilege;
import fidice.catalog.security.Separation;
import fidice.catalog.server.rest.Callers;
import fidice.catalog.server.rest.RequestContext;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.Grant;
import fidice.catalog.store.GrantRecord;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Page;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.Securable;
import fidice.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grant administration (D18, D19): grant and revoke to groups, list by principal (its groups), list by object,
 * the batch check, and the export for access certification. Every refusal is audited; every grant change goes
 * through the outbox in the same transaction (D23).
 */
@RestController
class GrantRoutes {

    static final String GRANTS = "/management/v1/catalogs/{catalog}/grants";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Operation GRANT = new Operation("grant", Privilege.MANAGE_GRANTS, false);
    private static final Operation REVOKE = new Operation("revoke", Privilege.MANAGE_GRANTS, false);
    private static final Operation LIST = new Operation("listGrants", Privilege.MANAGE_GRANTS, false);
    private static final Operation EXPORT = new Operation("exportGrants", Privilege.MANAGE_GRANTS, false);
    private static final int EXPORT_PAGE = 500;

    record GrantSpec(Securables.Spec securable, String group, String privilege) {
    }

    record CheckSpec(Securables.Spec securable, String privilege) {
    }

    record Checks(List<CheckSpec> checks) {
    }

    private final CatalogStore store;
    private final Authorizer authorizer;
    private final Separation separation;
    private final Callers callers;
    private final Denials denials;
    private final RestSettings settings;
    private final Clock clock;

    GrantRoutes(CatalogStore store, Authorizer authorizer, Separation separation, Callers callers, Denials denials,
                RestSettings settings, Clock clock) {
        this.store = store;
        this.authorizer = authorizer;
        this.separation = separation;
        this.callers = callers;
        this.denials = denials;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Grants a privilege to a group. Needs {@code manage-grants} on the securable or above it; refused when the
     * caller is in the group (no self-grant) or the group would hold two separated duties in the catalog.
     */
    @PostMapping(GRANTS)
    ResponseEntity<String> grant(@PathVariable String catalog, @RequestBody(required = false) String body,
                                 HttpServletRequest http) {
        return change(catalog, body, http, true);
    }

    /** Revokes a privilege from a group. Needs {@code manage-grants} on the securable or above it. */
    @PostMapping(GRANTS + "/revoke")
    ResponseEntity<String> revoke(@PathVariable String catalog, @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        return change(catalog, body, http, false);
    }

    /**
     * Grants held by groups in the catalog: list by principal. With no {@code group}, the caller's own groups.
     * Another group's grants need {@code manage-grants} on the catalog.
     */
    @GetMapping(GRANTS)
    ResponseEntity<String> listByGroups(@PathVariable String catalog,
                                        @RequestParam(name = "group", required = false) List<String> groups,
                                        @RequestParam(required = false) String pageToken,
                                        @RequestParam(required = false) String pageSize,
                                        HttpServletRequest http) {
        CatalogRecord record = Securables.catalog(store, catalog);
        RequestContext request = RequestContext.of(http, callers);
        Securables securables = new Securables(store, record);
        Set<String> own = request.caller().subject().groups();
        Set<String> asked = groups == null || groups.isEmpty() ? own : Set.copyOf(groups);
        if (!own.containsAll(asked)) {
            require(request, securables.catalogItself(), LIST);
        }
        if (asked.isEmpty()) {
            return json(Map.of("grants", List.of()));
        }
        Page<GrantRecord> page = paged(() -> store.listGrantsOfGroups(record.id(), asked, page(pageToken, pageSize)));
        return json(listing(page, securables));
    }

    /** Grants on one securable: list by object. Needs {@code manage-grants} on it or above it. */
    @GetMapping(GRANTS + "/on")
    ResponseEntity<String> listOn(@PathVariable String catalog,
                                  @RequestParam String type,
                                  @RequestParam(required = false) List<String> namespace,
                                  @RequestParam(required = false) String name,
                                  @RequestParam(required = false) String pageToken,
                                  @RequestParam(required = false) String pageSize,
                                  HttpServletRequest http) {
        CatalogRecord record = Securables.catalog(store, catalog);
        RequestContext request = RequestContext.of(http, callers);
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(new Securables.Spec(type, namespace, name));
        require(request, on, LIST);
        Page<GrantRecord> page = paged(() -> store.listGrantsOn(on.stored(), page(pageToken, pageSize)));
        return json(listing(page, securables));
    }

    /**
     * The batch check: may the caller do each of these? Answers for the caller's own subject, with every
     * governance rule applied. A check is a question, not a refused request, so its answers are not audited.
     */
    @PostMapping(GRANTS + "/check")
    ResponseEntity<String> check(@PathVariable String catalog, @RequestBody(required = false) String body,
                                 HttpServletRequest http) {
        CatalogRecord record = Securables.catalog(store, catalog);
        Caller caller = callers.caller(http);
        Checks checks = read(body, Checks.class);
        if (checks.checks() == null || checks.checks().isEmpty()) {
            throw CatalogError.badRequest("Nothing to check: send {\"checks\":[{\"securable\":...,\"privilege\":...}]}");
        }
        Securables securables = new Securables(store, record);
        List<Authorizer.Request> requests = new ArrayList<>();
        List<Integer> asked = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (CheckSpec spec : checks.checks()) {
            Privilege privilege = privilege(spec.privilege());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("privilege", privilege.wireName());
            try {
                Securables.Resolved resolved = securables.resolve(spec.securable());
                result.put("securable", securables.render(resolved.stored()));
                requests.add(new Authorizer.Request(resolved.target(), new Operation("check", privilege, false)));
                asked.add(results.size());
            } catch (CatalogError e) {
                if (e.status() != 404) {
                    throw e;
                }
                result.put("allowed", false);
                result.put("reason", e.getMessage());
            }
            results.add(result);
        }
        List<Decision> decisions = authorizer.decide(caller, requests);
        for (int i = 0; i < decisions.size(); i++) {
            Map<String, Object> result = results.get(asked.get(i));
            result.put("allowed", decisions.get(i).allowed());
            if (decisions.get(i) instanceof Decision.Denied denied) {
                result.put("reason", denied.message());
            }
        }
        return json(Map.of("results", results));
    }

    /**
     * Every grant in the catalog, for access certification (D19; the format is open, SEC-4). Walks the catalog,
     * every namespace and every active table and view. Needs {@code manage-grants} on the catalog.
     */
    @GetMapping(GRANTS + "/export")
    ResponseEntity<String> export(@PathVariable String catalog, HttpServletRequest http) {
        CatalogRecord record = Securables.catalog(store, catalog);
        RequestContext request = RequestContext.of(http, callers);
        Securables securables = new Securables(store, record);
        require(request, securables.catalogItself(), EXPORT);
        List<Map<String, Object>> grants = new ArrayList<>();
        exportOn(new Securable(Securable.Kind.CATALOG, record.id()), securables, grants);
        exportBelow(record.id(), securables, grants);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog", record.name());
        out.put("exported-at", clock.instant().toString());
        out.put("exported-by", request.caller().subject().id());
        out.put("grants", grants);
        return json(out);
    }

    private ResponseEntity<String> change(String catalog, String body, HttpServletRequest http, boolean grant) {
        CatalogRecord record = Securables.catalog(store, catalog);
        RequestContext request = RequestContext.of(http, callers);
        GrantSpec spec = read(body, GrantSpec.class);
        if (spec.group() == null || spec.group().isBlank()) {
            throw CatalogError.badRequest("A grant names the group it goes to; grants never go to individuals (D19)");
        }
        Privilege privilege = privilege(spec.privilege());
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(spec.securable());
        Operation operation = grant ? GRANT : REVOKE;
        require(request, on, operation);
        if (grant) {
            if (request.caller().subject().groups().contains(spec.group())) {
                throw denials.refuse(request.actor(), operation.name(), on.named(), privilege.wireName(),
                        "separation-of-duties", "no one grants to a group they belong to: " + spec.group());
            }
            Optional<String> conflict = separation.refusal(spec.group(), privilege, held(record.id(), spec.group()));
            if (conflict.isPresent()) {
                throw denials.refuse(request.actor(), operation.name(), on.named(), privilege.wireName(),
                        "separation-of-duties", conflict.get());
            }
        }
        Grant stored = new Grant(record.id(), on.stored(), spec.group(), privilege.wireName());
        Instant now = clock.instant();
        CatalogEvent event = CatalogEvent.create(settings.auditSource(), now, new GrantChange(
                grant ? GrantChange.Action.GRANTED : GrantChange.Action.REVOKED, on.named(), spec.group(),
                privilege.wireName(), request.actor()));
        Change change = new Change(UUID.randomUUID(), now, List.of(new OutboxEvent(event.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
        WriteResult result;
        try {
            result = grant ? store.putGrant(stored, request.caller().subject().id(), change)
                    : store.revokeGrant(stored, change);
        } catch (CommitOutcomeUnknownException e) {
            // Both are safe to repeat: a repeated grant answers unchanged, a repeated revoke not found.
            throw CatalogError.of(500, "ServiceFailureException", (grant ? "The grant" : "The revoke")
                    + " may or may not have been applied; repeat it: " + e.getMessage());
        }
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.GRANT ->
                    throw CatalogError.of(404, "NotFoundException", "No such grant: " + privilege.wireName()
                            + " to " + spec.group());
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NotFoundException",
                    "The securable no longer exists: " + missing.subject());
            default -> throw new IllegalStateException((grant ? "putGrant" : "revokeGrant") + " answered " + result);
        };
    }

    /** Every privilege the group holds anywhere in the catalog, for the separation check. */
    private List<Privilege> held(UUID catalogId, String group) {
        List<Privilege> held = new ArrayList<>();
        PageRequest request = PageRequest.first(EXPORT_PAGE);
        while (true) {
            Page<GrantRecord> page = store.listGrantsOfGroups(catalogId, Set.of(group), request);
            for (GrantRecord grant : page.items()) {
                try {
                    held.add(Privilege.fromWireName(grant.grant().privilege()));
                } catch (IllegalArgumentException e) {
                    // A privilege from a newer version: it conflicts with nothing this version knows.
                }
            }
            if (page.nextToken().isEmpty()) {
                return held;
            }
            request = request.after(page);
        }
    }

    private void require(RequestContext request, Securables.Resolved on, Operation operation) {
        if (authorizer.decide(request.caller(), on.target(), operation) instanceof Decision.Denied denied) {
            throw denials.refuse(request.actor(), operation.name(), on.named(),
                    denied.privilege() == null ? null : denied.privilege().wireName(),
                    denied.reason().name().toLowerCase(java.util.Locale.ROOT), denied.message());
        }
    }

    private void exportBelow(UUID parentId, Securables securables, List<Map<String, Object>> grants) {
        for (NamespaceRecord namespace : all(page -> store.listNamespaces(parentId, page))) {
            exportOn(new Securable(Securable.Kind.NAMESPACE, namespace.id()), securables, grants);
            for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
                for (CatalogObject object : all(page -> store.listObjects(namespace.id(), kind,
                        CatalogObject.State.ACTIVE, page))) {
                    exportOn(object.securable(), securables, grants);
                }
            }
            exportBelow(namespace.id(), securables, grants);
        }
    }

    private void exportOn(Securable securable, Securables securables, List<Map<String, Object>> grants) {
        for (GrantRecord grant : all(page -> store.listGrantsOn(securable, page))) {
            grants.add(render(grant, securables));
        }
    }

    private static <T> List<T> all(Function<PageRequest, Page<T>> list) {
        List<T> items = new ArrayList<>();
        PageRequest request = PageRequest.first(EXPORT_PAGE);
        while (true) {
            Page<T> page = list.apply(request);
            items.addAll(page.items());
            if (page.nextToken().isEmpty()) {
                return items;
            }
            request = request.after(page);
        }
    }

    private static Map<String, Object> listing(Page<GrantRecord> page, Securables securables) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grants", page.items().stream().map(g -> render(g, securables)).toList());
        page.nextToken().ifPresent(token -> out.put("next-page-token", token));
        return out;
    }

    private static Map<String, Object> render(GrantRecord grant, Securables securables) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("securable", securables.render(grant.grant().securable()));
        out.put("group", grant.grant().group());
        out.put("privilege", grant.grant().privilege());
        out.put("granted-by", grant.grantedBy());
        out.put("granted-at", grant.grantedAt().toString());
        return out;
    }

    private PageRequest page(String pageToken, String pageSize) {
        int size = settings.defaultPageSize();
        if (pageSize != null && !pageSize.isEmpty()) {
            try {
                size = Integer.parseInt(pageSize);
            } catch (NumberFormatException e) {
                throw CatalogError.badRequest("Invalid page size: " + pageSize);
            }
            if (size < 1) {
                throw CatalogError.badRequest("Invalid page size: " + pageSize);
            }
        }
        Optional<String> token = pageToken == null || pageToken.isEmpty() ? Optional.empty() : Optional.of(pageToken);
        return new PageRequest(token, Math.min(size, settings.maxPageSize()));
    }

    private static <T> T paged(java.util.function.Supplier<T> list) {
        try {
            return list.get();
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
    }

    private static Privilege privilege(String name) {
        if (name == null) {
            throw CatalogError.badRequest("A privilege is needed");
        }
        try {
            return Privilege.fromWireName(name);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest(e.getMessage());
        }
    }

    static <T> T read(String body, Class<T> type) {
        try {
            T value = JSON.readValue(body == null ? "" : body, type);
            if (value == null) {
                throw CatalogError.badRequest("A JSON body is needed");
            }
            return value;
        } catch (JacksonException e) {
            throw CatalogError.badRequest("Invalid JSON body: " + e.getOriginalMessage());
        }
    }

    static ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(value));
    }
}
