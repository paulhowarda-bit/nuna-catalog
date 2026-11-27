package nuna.catalog.server.security;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.Export;
import nuna.catalog.audit.GrantChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RequestContext;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantGroup;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Ref;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Grant administration (D18, D19): grant and revoke to groups, a grant's preview (backlog 246), list by principal (its
 * groups), list by object, the effective grants on an object (D45), the groups that hold grants (backlog 247), the
 * batch check, and the export for access certification. Every refusal is audited; one on a namespace, table
 * or view the caller holds nothing on is answered as if it did not exist (D18, item 73), as is every route on a
 * catalog the caller holds nothing on ({@link Holdings#catalog}, item 68). Every grant change goes
 * through the outbox in the same transaction (D23). The separation-of-duties check runs inside the store's write
 * of the grant, so two grants made at once cannot together break it (backlog 13).
 */
@RestController
class GrantRoutes {

    static final String GRANTS = "/management/v1/catalogs/{catalog}/grants";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Operation GRANT = new Operation("grant", Privilege.MANAGE_GRANTS, false);
    private static final Operation REVOKE = new Operation("revoke", Privilege.MANAGE_GRANTS, false);
    private static final Operation LIST = new Operation("listGrants", Privilege.MANAGE_GRANTS, false);
    private static final Operation EXPORT = new Operation("exportGrants", Privilege.MANAGE_GRANTS, false);
    private static final Operation EFFECTIVE = new Operation("listEffectiveGrants", Privilege.MANAGE_GRANTS, false);
    private static final Operation LIST_GROUPS = new Operation("listGrantGroups", Privilege.MANAGE_GRANTS, false);
    private static final Operation PREVIEW = new Operation("previewGrant", Privilege.MANAGE_GRANTS, false);
    private static final int EXPORT_PAGE = 500;
    private static final String GROUPS_NOTE = "Only groups that hold a grant in this catalog are listed, and the"
            + " deployment's grant-admin and platform-operator groups: the catalog knows groups from its grants and"
            + " does not read the directory, so a group that holds nothing here is not listed.";

    /** @param branch the branch the grant holds through, by name; null for a grant on every ref (backlog 113) */
    record GrantSpec(Securables.Spec securable, String group, String privilege, String branch) {
    }

    /**
     * A grant's body, read and resolved, on a securable the caller may manage grants on.
     *
     * @param branch the branch the grant names; empty for a grant on every ref
     */
    private record Asked(Securables securables, Securables.Resolved on, String group, Privilege privilege,
                         Optional<Ref> branch) {
    }

    /** Why a grant is refused: the grant route's answer and the preview's {@code refusal} and {@code reason}. */
    private record Refusal(Kind kind, String reason) {

        enum Kind {
            PRIVILEGE_NOT_ON_OBJECT, PRIVILEGE_NOT_ON_A_BRANCH, SELF_GRANT, SEPARATION_OF_DUTIES;

            String wireName() {
                return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
        }
    }

    /** @param branch the branch to ask through, by name; null for main (backlog 113) */
    record CheckSpec(Securables.Spec securable, String privilege, String branch) {
    }

    record Checks(List<CheckSpec> checks) {
    }

    private final CatalogStore store;
    private final Authorizer authorizer;
    private final Separation separation;
    private final Callers callers;
    private final Denials denials;
    private final Holdings holdings;
    private final RestSettings settings;
    private final OwnGroups ownGroups;
    private final Clock clock;

    GrantRoutes(CatalogStore store, Authorizer authorizer, Separation separation, Callers callers, Denials denials,
                Holdings holdings, RestSettings settings, OwnGroups ownGroups, Clock clock) {
        this.store = store;
        this.authorizer = authorizer;
        this.separation = separation;
        this.callers = callers;
        this.denials = denials;
        this.holdings = holdings;
        this.settings = settings;
        this.ownGroups = ownGroups;
        this.clock = clock;
    }

    /**
     * Grants a privilege to a group. Needs {@code manage-grants} on the securable or above it; refused when the
     * caller is in the group (no self-grant) or the group would hold two separated duties in the catalog, and
     * answered 400 for a privilege that means nothing on the securable ({@code create} on a table or view, backlog
     * 246). {@link #preview} answers what this would do, through the same checks.
     *
     * <p>{@code branch} names a branch the grant holds through, and through no other ref (backlog 113): only
     * {@code write} and {@code modify}, what a commit on the branch needs, since reads, listings and every change
     * outside commits ask through main. A tag, which takes no write, or a branch that does not exist or has expired
     * is answered 400 or 404. Without it the grant holds through every ref, main included.
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
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), LIST.name(), null);
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
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), LIST.name(),
                LIST.privilege());
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(new Securables.Spec(type, namespace, name));
        require(request, on, LIST);
        Page<GrantRecord> page = paged(() -> store.listGrantsOn(on.stored(), page(pageToken, pageSize)));
        return json(listing(page, securables));
    }

    /**
     * The effective grants on one securable (D45): every grant that reaches it, on its catalog, on each namespace
     * above it and on it, in that order from the catalog down, each with the securable it was granted on. Named as
     * {@code /on} names it: {@code ?type=table&namespace=a&namespace=b&name=t}. The authorizer resolves them along
     * the chain it decides on ({@link Authorizer#effectiveGrants}), so the answer never disagrees with an access
     * decision; a privilege this version does not know gives nothing and is left out. {@code grant-admin-groups}
     * names the deployment's grant admins, who hold {@code manage-grants} on every catalog by configuration, not
     * by a grant. One answer, not paged: only the grants on one chain of securables. Needs {@code manage-grants} on
     * the securable or above it, as list by object does; a refusal is answered as there.
     */
    @GetMapping(GRANTS + "/effective")
    ResponseEntity<String> listEffective(@PathVariable String catalog,
                                         @RequestParam String type,
                                         @RequestParam(required = false) List<String> namespace,
                                         @RequestParam(required = false) String name,
                                         HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), EFFECTIVE.name(),
                EFFECTIVE.privilege());
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(new Securables.Spec(type, namespace, name));
        require(request, on, EFFECTIVE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("securable", securables.render(on.stored()));
        Branches branches = new Branches();
        out.put("grants", authorizer.effectiveGrants(on.target()).stream().map(g -> render(g, securables, branches))
                .toList());
        out.put("grant-admin-groups", separation.grantAdminGroups().stream().sorted().toList());
        return json(out);
    }

    /**
     * The groups that hold a grant in the catalog, for the console's group picker (backlog 247), paged by group:
     * each with its duties (D18: {@code read}, {@code data}, {@code grants}, {@code tags}, as {@link Separation.Duty}
     * names them) and the privileges it holds anywhere in the catalog, by wire name; a privilege this version does
     * not know is listed but gives no duty. With them, on every page, the deployment's grant-admin and
     * platform-operator groups, which hold their duty by configuration, not by a grant, and may be granted nothing.
     * The catalog knows groups only from its grants, never the directory's, and {@code note} says so. Needs
     * {@code manage-grants} on the catalog, as the export does; a refusal is answered as there.
     */
    @GetMapping(GRANTS + "/groups")
    ResponseEntity<String> listGroups(@PathVariable String catalog,
                                      @RequestParam(required = false) String pageToken,
                                      @RequestParam(required = false) String pageSize,
                                      HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), LIST_GROUPS.name(),
                LIST_GROUPS.privilege());
        Securables securables = new Securables(store, record);
        require(request, securables.catalogItself(), LIST_GROUPS);
        Page<GrantGroup> page = paged(() -> store.listGrantGroups(record.id(), page(pageToken, pageSize)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", page.items().stream().map(GrantRoutes::render).toList());
        out.put("grant-admin-groups", separation.grantAdminGroups().stream().sorted().toList());
        out.put("platform-operator-groups", separation.platformOperatorGroups().stream().sorted().toList());
        out.put("note", GROUPS_NOTE);
        page.nextToken().ifPresent(token -> out.put("next-page-token", token));
        return json(out);
    }

    /**
     * The batch check: may the caller do each of these? Answers for the caller's own subject, with every
     * governance rule applied. A check is a question, not a refused request, so its answers are not audited. A
     * no on a namespace, table or view the caller holds nothing on reads as the answer for one that does not
     * exist (D18, amended 2026-11-19). A catalog the caller holds nothing on is refused as every route refuses it,
     * audited.
     */
    @PostMapping(GRANTS + "/check")
    ResponseEntity<String> check(@PathVariable String catalog, @RequestBody(required = false) String body,
                                 HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), "check", null);
        Caller caller = request.caller();
        Checks checks = read(body, Checks.class);
        if (checks.checks() == null || checks.checks().isEmpty()) {
            throw CatalogError.badRequest("Nothing to check: send {\"checks\":[{\"securable\":...,\"privilege\":...}]}");
        }
        Securables securables = new Securables(store, record);
        Instant now = clock.instant();
        List<Authorizer.Request> requests = new ArrayList<>();
        List<Securables.Resolved> resolvedAsked = new ArrayList<>();
        List<Integer> asked = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (CheckSpec spec : checks.checks()) {
            Privilege privilege = privilege(spec.privilege());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("privilege", privilege.wireName());
            Optional<Ref> through = spec.branch() == null ? Optional.empty()
                    : Optional.of(liveRef(record, spec.branch(), now));
            through.ifPresent(ref -> result.put("branch", ref.name().display()));
            try {
                Securables.Resolved resolved = securables.resolve(spec.securable());
                result.put("securable", securables.render(resolved.stored()));
                requests.add(new Authorizer.Request(resolved.target(), new Operation("check", privilege, false),
                        through.map(Ref::id)));
                resolvedAsked.add(resolved);
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
            Securables.Resolved resolved = resolvedAsked.get(i);
            result.put("allowed", decisions.get(i).allowed());
            if (decisions.get(i) instanceof Decision.Denied denied) {
                if (holdings.something(caller, resolved.target())) {
                    result.put("reason", denied.message());
                } else {
                    result.remove("securable");
                    result.put("reason", resolved.missing().getMessage());
                }
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
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), EXPORT.name(),
                EXPORT.privilege());
        Securables securables = new Securables(store, record);
        require(request, securables.catalogItself(), EXPORT);
        List<Map<String, Object>> grants = new ArrayList<>();
        Branches branches = new Branches();
        exportOn(new Securable(Securable.Kind.CATALOG, record.id()), securables, branches, grants);
        exportBelow(record.id(), securables, branches, grants);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog", record.name());
        out.put("exported-at", clock.instant().toString());
        out.put("exported-by", request.caller().subject().id());
        out.put("grants", grants);
        // The whole access map leaves with the caller: who took it, and how much, is audited (backlog 312).
        denials.record(new Export(new nuna.catalog.audit.Securable(
                nuna.catalog.audit.Securable.Kind.CATALOG, record.id(), record.name(), List.of()), Export.GRANTS,
                grants.size(), request.actor()));
        return json(out);
    }

    /**
     * What a grant would do, writing nothing (backlog 246), for the console's grant dialog to show before Grant is
     * pressed. It takes the grant route's body and asks the grant route's checks, in its order and through the same
     * code: {@code admitted}; {@code unchanged}, already held, with {@code granted-by} and {@code granted-at}; or
     * {@code refused}, with {@code refusal} ({@code privilege-not-on-object}, {@code self-grant} or
     * {@code separation-of-duties}) and {@code reason}, the message the grant route would answer. A caller that may
     * not manage grants on the securable is refused as the grant route refuses it, audited. The answers are a
     * question, not a refused request, so they are not audited, as the batch check's are not. The separation of
     * duties is read here, not inside a write, so a grant made meanwhile may still change the grant's own answer.
     * What that tells: a caller that may manage grants on any one securable learns, for any group it names, which
     * duties the group holds anywhere in the catalog, and one privilege giving each, never where. The grant route's
     * 403 says the same, but each try there leaves a {@code Denial} or a grant; a preview leaves nothing (the
     * security review of backlog 246, 2026-11-24).
     */
    @PostMapping(GRANTS + "/preview")
    ResponseEntity<String> preview(@PathVariable String catalog, @RequestBody(required = false) String body,
                                   HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), PREVIEW.name(),
                PREVIEW.privilege());
        Asked asked = asked(record, body, request, PREVIEW);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("securable", asked.securables().render(asked.on().stored()));
        out.put("group", asked.group());
        out.put("privilege", asked.privilege().wireName());
        asked.branch().ifPresent(ref -> out.put("branch", ref.name().display()));
        Optional<Refusal> refusal = refusal(request.caller(), asked);
        Optional<GrantRecord> held = Optional.empty();
        if (refusal.isEmpty()) {
            List<GrantRecord> grants = all(page -> store.listGrantsOfGroups(record.id(), Set.of(asked.group()), page));
            AtomicReference<String> separated = new AtomicReference<>();
            if (!admits(asked.group(), asked.privilege(), grants.stream().map(g -> g.grant().privilege()).toList(),
                    separated)) {
                refusal = Optional.of(new Refusal(Refusal.Kind.SEPARATION_OF_DUTIES, separated.get()));
            } else {
                held = grants.stream().filter(g -> g.grant().securable().equals(asked.on().stored())
                        && g.grant().privilege().equals(asked.privilege().wireName())
                        && g.grant().branch().equals(asked.branch().map(Ref::id))).findFirst();
            }
        }
        if (refusal.isPresent()) {
            out.put("answer", "refused");
            out.put("refusal", refusal.get().kind().wireName());
            out.put("reason", refusal.get().reason());
        } else if (held.isPresent()) {
            out.put("answer", "unchanged");
            out.put("granted-by", held.get().grantedBy());
            out.put("granted-at", held.get().grantedAt().toString());
        } else {
            out.put("answer", "admitted");
        }
        return json(out);
    }

    private ResponseEntity<String> change(String catalog, String body, HttpServletRequest http, boolean grant) {
        RequestContext request = RequestContext.of(http, callers);
        Operation operation = grant ? GRANT : REVOKE;
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), operation.name(),
                operation.privilege());
        Asked asked = asked(record, body, request, operation);
        Securables.Resolved on = asked.on();
        Privilege privilege = asked.privilege();
        if (grant) {
            Optional<Refusal> refusal = refusal(request.caller(), asked);
            if (refusal.isPresent()) {
                throw refused(request, operation, asked, refusal.get());
            }
        }
        Grant stored = new Grant(record.id(), on.stored(), asked.group(), privilege.wireName(),
                asked.branch().map(Ref::id));
        Instant now = clock.instant();
        CatalogEvent event = CatalogEvent.create(settings.auditSource(), now, new GrantChange(
                grant ? GrantChange.Action.GRANTED : GrantChange.Action.REVOKED, on.named(), asked.group(),
                privilege.wireName(), request.actor(), asked.branch().map(ref -> ref.name().display()).orElse(null),
                asked.branch().map(Ref::id).orElse(null)));
        Change change = new Change(UUID.randomUUID(), now, List.of(new OutboxEvent(event.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
        AtomicReference<String> separated = new AtomicReference<>();
        WriteResult result;
        try {
            result = grant ? store.putGrantIf(stored, request.caller().subject().id(), change,
                    held -> admits(asked.group(), privilege, held, separated))
                    : store.revokeGrant(stored, change);
        } catch (CommitOutcomeUnknownException e) {
            // Both are safe to repeat: a repeated grant answers unchanged, a repeated revoke not found.
            throw CatalogError.of(500, "ServiceFailureException", (grant ? "The grant" : "The revoke")
                    + " may or may not have been applied; repeat it: " + e.getMessage());
        }
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_ADMITTED ->
                    throw refused(request, operation, asked,
                            new Refusal(Refusal.Kind.SEPARATION_OF_DUTIES, separated.get()));
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.GRANT ->
                    throw CatalogError.of(404, "NotFoundException", "No such grant: " + privilege.wireName()
                            + " to " + asked.group() + asked.branch().map(ref -> " on branch "
                            + ref.name().display()).orElse(""));
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.REF ->
                    throw CatalogError.of(404, "NoSuchBranchException", "The branch no longer exists: "
                            + asked.branch().map(ref -> ref.name().display()).orElse(""));
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NotFoundException",
                    "The securable no longer exists: " + missing.subject());
            default -> throw new IllegalStateException((grant ? "putGrant" : "revokeGrant") + " answered " + result);
        };
    }

    /**
     * A grant, revoke or preview as its body asks it, on a securable the caller may manage grants on: a caller that
     * may not is refused, audited, as {@link #require} answers it.
     */
    private Asked asked(CatalogRecord record, String body, RequestContext request, Operation operation) {
        GrantSpec spec = read(body, GrantSpec.class);
        if (spec.group() == null || spec.group().isBlank()) {
            // The rule behind this message: D19.
            throw CatalogError.badRequest("A grant names the group it goes to; grants never go to individuals");
        }
        Privilege privilege = privilege(spec.privilege());
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(spec.securable());
        require(request, on, operation);
        Optional<Ref> branch = Optional.empty();
        if (spec.branch() != null) {
            // A grant goes only to a live branch; a revoke may name one that has expired, whose grants are still kept.
            Ref ref = operation == REVOKE ? anyRef(record, spec.branch()) : liveRef(record, spec.branch(), clock.instant());
            if (ref.kind() != Ref.Kind.BRANCH) {
                throw CatalogError.badRequest("A grant names a branch, and " + ref.name().display()
                        + " is a tag, which takes no write");
            }
            branch = Optional.of(ref);
        }
        return new Asked(securables, on, spec.group(), privilege, branch);
    }

    /** The ref of the catalog with this name, whatever its expiry: 404 when there is none. */
    private Ref anyRef(CatalogRecord catalog, String name) {
        return store.findRef(catalog.id(), Names.normalize(name))
                .orElseThrow(() -> CatalogError.of(404, "NoSuchBranchException", "Branch does not exist: " + name));
    }

    /** The ref of the catalog with this name, live at {@code now}: 404 when there is none or it has expired. */
    private Ref liveRef(CatalogRecord catalog, String name, Instant now) {
        Ref ref = anyRef(catalog, name);
        if (ref.expiredAt(now)) {
            throw CatalogError.of(404, "NoSuchBranchException", "Branch does not exist: " + name);
        }
        return ref;
    }

    /**
     * Why a grant is refused before the store's write, in the order the grant route asks: a privilege that means
     * nothing on the securable ({@code create} on a table or view, which has nothing inside it to create, and
     * {@code write} on a view, which has no rows, backlog 258), then a grant to one of the caller's own groups,
     * unless {@link OwnGroups} names them. Empty when only the separation of duties is left, which the store asks
     * inside its write ({@link #admits}).
     */
    private Optional<Refusal> refusal(Caller caller, Asked asked) {
        Securable.Kind kind = asked.on().stored().kind();
        if (asked.privilege() == Privilege.CREATE && (kind == Securable.Kind.TABLE || kind == Securable.Kind.VIEW)) {
            return Optional.of(new Refusal(Refusal.Kind.PRIVILEGE_NOT_ON_OBJECT, "create means nothing on a "
                    + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + ": it creates namespaces, tables and views in a catalog or namespace"));
        }
        if (asked.privilege() == Privilege.WRITE && kind == Securable.Kind.VIEW) {
            return Optional.of(new Refusal(Refusal.Kind.PRIVILEGE_NOT_ON_OBJECT, "write means nothing on a view: it"
                    + " has no rows; select reads it, and modify changes its definition"));
        }
        if (asked.branch().isPresent() && asked.privilege() != Privilege.WRITE
                && asked.privilege() != Privilege.MODIFY) {
            return Optional.of(new Refusal(Refusal.Kind.PRIVILEGE_NOT_ON_A_BRANCH, asked.privilege().wireName()
                    + " is not granted on one branch: a grant that names a branch gives write or modify, what a"
                    + " commit on it needs; reads and every other change ask through main"));
        }
        if (!ownGroups.mayActOn(caller, asked.group())) {
            return Optional.of(new Refusal(Refusal.Kind.SELF_GRANT,
                    "no one grants to a group they belong to: " + asked.group()));
        }
        return Optional.empty();
    }

    /**
     * The grant route's answer to a refusal: a privilege that means nothing on its securable is a mistake in the
     * request, 400; a self-grant and a separation conflict are refused requests, 403 and audited.
     */
    private RuntimeException refused(RequestContext request, Operation operation, Asked asked, Refusal refusal) {
        if (refusal.kind() == Refusal.Kind.PRIVILEGE_NOT_ON_OBJECT
                || refusal.kind() == Refusal.Kind.PRIVILEGE_NOT_ON_A_BRANCH) {
            return CatalogError.badRequest(refusal.reason());
        }
        return denials.refuse(request.actor(), operation.name(), asked.on().named(), asked.privilege().wireName(),
                "separation-of-duties", refusal.reason());
    }

    /**
     * The separation check, which the store asks inside its write: whether {@code group} may be granted
     * {@code privilege}, given every privilege it holds anywhere in the catalog, by wire name. Why not goes into
     * {@code refusal}; the store's last call decides.
     */
    private boolean admits(String group, Privilege privilege, List<String> held, AtomicReference<String> refusal) {
        List<Privilege> known = new ArrayList<>();
        for (String name : held) {
            try {
                known.add(Privilege.fromWireName(name));
            } catch (IllegalArgumentException e) {
                // A privilege from a newer version: it conflicts with nothing this version knows.
            }
        }
        Optional<String> conflict = separation.refusal(group, privilege, known);
        refusal.set(conflict.orElse(null));
        return conflict.isEmpty();
    }

    /**
     * A refusal is audited, then answered 403 to a caller that holds something on the namespace, table or view,
     * and as if it did not exist to any other (D18, amended 2026-11-19), as on the Iceberg routes.
     */
    private void require(RequestContext request, Securables.Resolved on, Operation operation) {
        if (authorizer.decide(request.caller(), on.target(), operation) instanceof Decision.Denied denied) {
            AccessDenied refused = denials.refuse(request.actor(), operation.name(), on.named(),
                    denied.privilege() == null ? null : denied.privilege().wireName(),
                    denied.reason().name().toLowerCase(java.util.Locale.ROOT), denied.message());
            throw holdings.something(request.caller(), on.target()) ? refused : on.missing();
        }
    }

    private void exportBelow(UUID parentId, Securables securables, Branches branches,
                             List<Map<String, Object>> grants) {
        for (NamespaceRecord namespace : all(page -> store.listNamespaces(parentId, page))) {
            exportOn(new Securable(Securable.Kind.NAMESPACE, namespace.id()), securables, branches, grants);
            for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
                for (CatalogObject object : all(page -> store.listObjects(namespace.id(), kind,
                        CatalogObject.State.ACTIVE, page))) {
                    exportOn(object.securable(), securables, branches, grants);
                }
            }
            exportBelow(namespace.id(), securables, branches, grants);
        }
    }

    private void exportOn(Securable securable, Securables securables, Branches branches,
                          List<Map<String, Object>> grants) {
        for (GrantRecord grant : all(page -> store.listGrantsOn(securable, page))) {
            grants.add(render(grant, securables, branches));
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

    private Map<String, Object> listing(Page<GrantRecord> page, Securables securables) {
        Map<String, Object> out = new LinkedHashMap<>();
        Branches branches = new Branches();
        out.put("grants", page.items().stream().map(g -> render(g, securables, branches)).toList());
        page.nextToken().ifPresent(token -> out.put("next-page-token", token));
        return out;
    }

    /**
     * A grant as the routes answer it; {@code branch}, the name of the branch a grant holds through when it names one
     * (backlog 113), is left out for a grant on every ref.
     */
    private static Map<String, Object> render(GrantRecord grant, Securables securables, Branches branches) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("securable", securables.render(grant.grant().securable()));
        out.put("group", grant.grant().group());
        out.put("privilege", grant.grant().privilege());
        grant.grant().branch().ifPresent(id -> out.put("branch", branches.name(id)));
        out.put("granted-by", grant.grantedBy());
        out.put("granted-at", grant.grantedAt().toString());
        return out;
    }

    /**
     * The names of the branches one answer's grants name, each read once. A grant's branch is deleted with it, so a
     * name is missing only in a race with the deletion; its id stands in.
     */
    private final class Branches {

        private final Map<UUID, String> names = new HashMap<>();

        String name(UUID id) {
            return names.computeIfAbsent(id, ref -> store.readRef(ref).map(found -> found.name().display())
                    .orElse(ref.toString()));
        }
    }

    private static Map<String, Object> render(GrantGroup group) {
        Set<Separation.Duty> duties = EnumSet.noneOf(Separation.Duty.class);
        for (String name : group.privileges()) {
            try {
                duties.add(Separation.Duty.of(Privilege.fromWireName(name)));
            } catch (IllegalArgumentException e) {
                // A privilege from a newer version: listed, but no duty this version knows.
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", group.group());
        out.put("duties", duties.stream().map(Separation.Duty::wireName).toList());
        out.put("privileges", group.privileges());
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
