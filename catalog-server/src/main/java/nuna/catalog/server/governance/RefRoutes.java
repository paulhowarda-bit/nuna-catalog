package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;
import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventData;
import nuna.catalog.audit.RefChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.commit.Merge;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.governance.ManagementIdempotency.Reply;
import nuna.catalog.server.rest.AuditEvents;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A catalog's branches and tags (D33, item 26's choices 6, 11, 12 and 13; item 106): list, create, read, renew and
 * delete under {@code /management/v1/catalogs/{catalog}/refs}, and the maintenance worker's two routes, which warn of a
 * branch about to expire and end one past its expiry, and the merge of a branch into main (backlog 107). Making,
 * renewing, deleting and merging a ref needs {@code create} on the catalog; listing, reading and a merge's dry run need
 * any holding in the catalog (D18 as amended), and show who made a ref only to a
 * caller holding {@code create} there (item 106, security finding 9). Every write takes an Idempotency-Key
 * (D10) and writes its {@code ref} audit record to the outbox with the change. P11's limits and expiry are
 * {@link RefSettings}: the store refuses a create past a limit, and a renewal sets a branch's expiry
 * {@code branch-idle-expiry} ahead, never past {@code branch-max-life} from its creation. A branch past its expiry is
 * gone for every route here, as for the Iceberg routes, though it holds its name until the worker ends it.
 */
@RestController
class RefRoutes {

    static final String REFS = CATALOG + "/refs";
    static final String REF = REFS + "/{ref}";
    static final String RENEW = REF + "/renew";
    static final String MERGE = REF + "/merge";
    static final String WARN = ROOT + "/maintenance/refs/{id}/warn";
    static final String END = ROOT + "/maintenance/refs/{id}/end";
    static final String REMOVE = CATALOG + "/objects/{id}/refs/remove";
    /** The refs that keep a table or view, by id and by path (backlog 170). */
    static final String KEPT = CATALOG + "/objects/{id}/refs";
    static final String PATH_KEPT = NAMESPACE + "/objects/{name}/refs";

    /** The principal of the warning markers in the idempotency store, which no caller's id can be. */
    static final String WARNINGS = "nuna.refs";

    private static final Logger LOG = LoggerFactory.getLogger(RefRoutes.class);
    private static final int PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final int WALK_PAGE = 1000;
    /**
     * How many dropped and purging objects of a namespace the read of which refs keep a path reads at most, to find
     * those by its name (the store has no lookup of dropped objects by name): past it, the answer says it was cut short
     * (the Nuna Security Expert's review of backlog 170).
     */
    static final int KEPT_WALK_LIMIT = 10_000;
    /**
     * How many dropped objects in one answer of that read are looked up in the audit table for who dropped them, the
     * newest first: each lookup is a feed query the feed's own rate limit does not see, so the rest say they were left
     * out (the same review).
     */
    static final int DROPPERS_READ = 5;
    private static final int MAX_NAME = 1000;
    private static final Duration WARNING_LEASE = Duration.ofMinutes(1);
    /** What a merge is authorized and audited as. */
    private static final String MERGE_OP = "mergeRef";
    /** What taking a table or view out of refs is authorized and audited as (backlog 156). */
    private static final String REMOVE_OP = "removeFromRefs";
    /** What reading which refs keep a table or view is authorized and audited as (backlog 170). */
    private static final String KEPT_OP = "readObjectRefs";
    /** How many times a removal reads the refs again when they changed under it. */
    private static final int REMOVE_ATTEMPTS = 3;
    /** The most refs one removal names. */
    private static final int MAX_REMOVE_REFS = 1000;

    /**
     * Where a record goes that changes nothing in the store and so cannot ride the outbox: the audit topic and the
     * change stream, before the route answers ({@link AuditRuntime#notices}).
     */
    @FunctionalInterface
    interface Notices {
        void send(CatalogEvent event);
    }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final ManagementIdempotency idempotency;
    private final Authorizer authorizer;
    private final RefSettings settings;
    private final CatalogNames catalogs;
    private final Notices notices;
    private final RestSettings rest;
    private final Merge.Factory merges;
    private final AuditEvents audit;
    private final Drops drops;
    private final Clock clock;

    RefRoutes(CatalogStore store, Management management, ManagementGuard guard, ManagementIdempotency idempotency,
              Authorizer authorizer, RefSettings settings, CatalogNames catalogs, Notices notices, RestSettings rest,
              Merge.Factory merges, AuditEvents audit, Drops drops, Clock clock) {
        this.drops = drops;
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.idempotency = idempotency;
        this.authorizer = authorizer;
        this.settings = settings;
        this.catalogs = catalogs;
        this.notices = notices;
        this.rest = rest;
        this.merges = merges;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The catalog's refs by name, paged. A branch past its expiry is left out, unless {@code expired=true}: then it is
     * listed with {@code expired}, as it holds its name until the maintenance worker ends it. Each branch has its
     * {@code changed-count}, the tables and views with a pointer of its own, all of them, as the merge's dry run counts
     * them to the same callers, and each ref when its cut was taken ({@code cut-at}, {@link #cut}). The answer carries the catalog's {@code limits} (P11's settings) and what the caller
     * {@code may} do, asked, not required, so the console offers only what the server would allow (backlog 109).
     */
    @GetMapping(path = REFS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> list(@PathVariable("catalog") String prefix,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) Integer pageSize,
                                @RequestParam(defaultValue = "false") boolean expired, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "listRefs", null);
        Instant now = now();
        Page<Ref> page;
        try {
            page = store.listRefs(catalog.id(), new PageRequest(Optional.ofNullable(pageToken)
                    .filter(token -> !token.isEmpty()), pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE)));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + pageToken);
        }
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode refs = body.putArray("refs");
        boolean creators = mayCreate(request, catalog, "listRefs");
        page.items().stream().filter(ref -> expired || !ref.expiredAt(now)).forEach(ref -> {
            ObjectNode node = ref(ref, now, creators);
            if (ref.kind() == Ref.Kind.BRANCH) {
                node.put("changed-count", store.countOwnPointers(ref.id()));
            }
            if (ref.expiredAt(now)) {
                node.put("expired", true);
            }
            refs.add(node);
        });
        page.nextToken().ifPresent(token -> body.put("next-page-token", token));
        body.set("limits", limits());
        body.putObject("may").put("create", creators);
        return GovernanceRoutes.json(200, body);
    }

    /** P11's settings, as the console words them: {@code nuna.refs.*}. */
    private ObjectNode limits() {
        ObjectNode limits = Management.JSON.createObjectNode();
        limits.put("max-branches", settings.maxBranches());
        limits.put("max-tags", settings.maxTags());
        limits.put("max-merge-tables", settings.maxMergeTables());
        limits.put("branch-idle-expiry", settings.branchIdleExpiry().toString());
        limits.put("branch-max-life", settings.branchMaxLife().toString());
        limits.put("expiry-warning", settings.expiryWarning().toString());
        return limits;
    }

    /**
     * One ref, with when its cut was taken ({@code cut-at}, {@link #cut}); for a branch, also the tables and views it
     * changed that the caller can describe.
     */
    @GetMapping(path = REF, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> read(@PathVariable("catalog") String prefix, @PathVariable("ref") String name,
                                HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "loadRef", null);
        Instant now = now();
        Ref ref = live(catalog, name, now);
        boolean creators = mayCreate(request, catalog, "loadRef");
        ObjectNode body = ref(ref, now, creators);
        if (ref.kind() == Ref.Kind.BRANCH) {
            body.set("changed", changed(request, ref));
        }
        body.putObject("may").put("create", creators);
        return GovernanceRoutes.json(200, body);
    }

    /**
     * Makes a ref: {@code {"name": ..., "kind": "branch" | "tag", "from": "main" | a ref's name}}, {@code from}
     * defaulting to main. Answers 201 with the ref; 404 when {@code from} names no ref; 409 when the name is taken or
     * the catalog holds as many refs of the kind as P11 allows, naming the setting.
     */
    @PostMapping(path = REFS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> create(@PathVariable("catalog") String prefix,
                                  @RequestHeader(name = ManagementIdempotency.HEADER, required = false) String key,
                                  @RequestBody(required = false) String body, HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "createRef", Privilege.CREATE, prefix, null, null, null);
        guard.require(request, target, "createRef", Privilege.CREATE);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "CREATE_REF",
                target.catalog().id().toString(), body, ticket -> create(target, request, body, ticket));
    }

    private Reply create(Target target, Request request, String body, ManagementIdempotency.Ticket ticket) {
        JsonNode json = Management.body(body);
        String given = Management.text(json, "name", MAX_NAME);
        Name name = new Name(Names.normalize(given), given);
        try {
            Ref.checkName(name);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid ref name: " + e.getMessage());
        }
        Ref.Kind kind = switch (Management.text(json, "kind", MAX_NAME).toLowerCase(Locale.ROOT)) {
            case "branch" -> Ref.Kind.BRANCH;
            case "tag" -> Ref.Kind.TAG;
            default -> throw CatalogError.badRequest("Field kind must be branch or tag");
        };
        String fromGiven = json.has("from") ? Management.text(json, "from", MAX_NAME) : Ref.MAIN;
        CatalogRecord catalog = target.catalog();
        Instant now = now();
        Optional<Ref> from = Names.normalize(fromGiven).equals(Ref.MAIN) ? Optional.empty()
                : Optional.of(live(catalog, fromGiven, now));
        Ref ref = new Ref(catalog.id(), UUID.randomUUID(), name, kind, now, request.actor().principal(),
                from.map(Ref::id), from.map(r -> r.name().display()).orElse(Ref.MAIN),
                kind == Ref.Kind.BRANCH ? Optional.of(settings.expiryAfter(now, now)) : Optional.empty());
        String answer = Management.JSON.writeValueAsString(ref(ref, now));
        RefChange event = event(RefChange.Action.CREATED, ref, ref.expiresAt(), target.audit(), request);
        WriteResult result = Management.write("Creating ref " + given, () -> store.createRef(ref, settings.limits(),
                ticket.change(management.change(now, event), 201, answer)), () -> store.readRef(ref.id()).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> new Reply(201, answer, true);
            // The store's own retry found the ref it had made (RefStore#createRef checks the name first).
            case WriteResult.NameTaken taken when taken.holder().equals(ref.id()) -> new Reply(201, answer, true);
            case WriteResult.NameTaken taken -> throw nameTaken(given, taken.holder(), now);
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.CATALOG ->
                    throw target.missing();
            case WriteResult.Missing missing -> throw noSuchRef(fromGiven);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.REF_LIMIT ->
                    throw limit(catalog, kind);
            default -> throw new IllegalStateException("createRef answered " + result);
        };
    }

    /**
     * Renews a branch: its expiry moves {@code branch-idle-expiry} ahead of now, never past {@code branch-max-life}
     * from its creation, and never back. Answers 200 with the branch; 400 for a tag, which never expires.
     */
    @PostMapping(path = RENEW, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> renew(@PathVariable("catalog") String prefix, @PathVariable("ref") String name,
                                 @RequestHeader(name = ManagementIdempotency.HEADER, required = false) String key,
                                 HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "renewRef", Privilege.CREATE, prefix, null, null, null);
        guard.require(request, target, "renewRef", Privilege.CREATE);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "RENEW_REF",
                target.catalog().id() + "/" + Names.normalize(name), "", ticket -> renew(target, request, name, ticket));
    }

    private Reply renew(Target target, Request request, String name, ManagementIdempotency.Ticket ticket) {
        Instant now = now();
        Ref ref = live(target.catalog(), name, now);
        if (ref.kind() == Ref.Kind.TAG) {
            throw notABranch(ref);
        }
        Instant expiry = settings.expiryAfter(ref.createdAt(), now);
        if (!expiry.isAfter(ref.expiresAt().orElseThrow())) {
            // At its whole life's end already, or renewed since: a renewal never shortens a branch's life.
            return Reply.of(200, Management.JSON.writeValueAsString(ref(ref, now)));
        }
        Ref renewed = new Ref(ref.catalogId(), ref.id(), ref.name(), ref.kind(), ref.createdAt(), ref.createdBy(),
                ref.fromRef(), ref.fromName(), Optional.of(expiry));
        String answer = Management.JSON.writeValueAsString(ref(renewed, now));
        RefChange event = event(RefChange.Action.RENEWED, ref, Optional.of(expiry), target.audit(), request);
        WriteResult result = Management.write("Renewing ref " + name, () -> store.renewRef(ref.id(), expiry,
                        ticket.change(management.change(now, event), 200, answer)),
                () -> store.readRef(ref.id()).flatMap(Ref::expiresAt).filter(expiry::equals).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> new Reply(200, answer, true);
            case WriteResult.Unchanged unchanged -> Reply.of(200, answer);
            case WriteResult.Missing missing -> throw noSuchRef(name);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_A_BRANCH ->
                    throw notABranch(ref);
            default -> throw new IllegalStateException("renewRef answered " + result);
        };
    }

    /** Deletes a ref, its cut and a branch's own pointers; its name is free at once. Answers 204. */
    @DeleteMapping(path = REF)
    ResponseEntity<String> delete(@PathVariable("catalog") String prefix, @PathVariable("ref") String name,
                                  @RequestHeader(name = ManagementIdempotency.HEADER, required = false) String key,
                                  HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "deleteRef", Privilege.CREATE, prefix, null, null, null);
        guard.require(request, target, "deleteRef", Privilege.CREATE);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "DELETE_REF",
                target.catalog().id() + "/" + Names.normalize(name), "",
                ticket -> delete(target, request, name, ticket));
    }

    private Reply delete(Target target, Request request, String name, ManagementIdempotency.Ticket ticket) {
        Instant now = now();
        Ref ref = live(target.catalog(), name, now);
        RefChange event = event(RefChange.Action.DELETED, ref, ref.expiresAt(), target.audit(), request);
        WriteResult result = Management.write("Deleting ref " + name, () -> store.deleteRef(ref.id(),
                ticket.change(management.change(now, event), 204, "")), () -> store.readRef(ref.id()).isEmpty());
        return switch (result) {
            case WriteResult.Applied applied -> new Reply(204, "", true);
            case WriteResult.Missing missing -> throw noSuchRef(name);
            default -> throw new IllegalStateException("deleteRef answered " + result);
        };
    }

    /**
     * Merges a branch into main (D33, backlog 107): every table and view the branch changed moves onto main in one
     * transaction that ends the branch, or nothing moves ({@link Merge}). It needs {@code create} on the catalog, and on
     * each table or view what a commit of the change from main's metadata to the branch's needs on main: {@code write}
     * for data alone, {@code modify} for a definition change, asked in one batch as a commit asks it, so D27, D20 and
     * D17 hold (item 26's choices 8 and 12; security finding 3). Answers 200 with main's new version of each; 409
     * {@code RefMergeConflictException} listing every table or view main also changed or dropped, or
     * {@code RefLimitException} past {@code nuna.refs.max-merge-tables}; 403 when a right or main's rules refuse one,
     * audited as a denial; 404 for a branch gone. {@code create} on the catalog lets the caller describe every table and
     * view in it, so every answer names what it is about. The {@code ref} event (merged) names the tables moved, and
     * each has its {@code Commit} event on main.
     *
     * <p>With {@code dry-run=true} it writes nothing and needs any holding in the catalog: 200 with what a merge would
     * do now, the answer it would get, and whether the caller may merge, naming only the tables and views the caller
     * can describe and counting the rest (security finding 2).
     */
    @PostMapping(path = MERGE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> merge(@PathVariable("catalog") String prefix, @PathVariable("ref") String name,
                                 @RequestParam(name = "dry-run", defaultValue = "false") boolean dryRun,
                                 @RequestHeader(name = ManagementIdempotency.HEADER, required = false) String key,
                                 HttpServletRequest http) {
        Request request = management.request(http);
        if (dryRun) {
            CatalogRecord catalog = management.catalog(prefix, request, MERGE_OP, null);
            Instant now = now();
            Ref ref = branch(catalog, name, now);
            return GovernanceRoutes.json(200, dryRun(request, catalog, merging(catalog, request)
                    .plan(ref, settings.maxMergeTables()), now));
        }
        Target target = management.target(request, MERGE_OP, Privilege.CREATE, prefix, null, null, null);
        guard.require(request, target, MERGE_OP, Privilege.CREATE);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "MERGE_REF",
                target.catalog().id() + "/" + Names.normalize(name), "", ticket -> merge(target, request, name, ticket));
    }

    private Reply merge(Target target, Request request, String name, ManagementIdempotency.Ticket ticket) {
        CatalogRecord catalog = target.catalog();
        Instant now = now();
        Ref ref = branch(catalog, name, now);
        String[] answer = new String[1];
        Merge.Outcome outcome = merging(catalog, request).merge(ref, settings.maxMergeTables(), request.caller(),
                plan -> require(request, catalog, plan), merged -> {
                    answer[0] = Management.JSON.writeValueAsString(merged(merged, now));
                    RefChange event = event(RefChange.Action.MERGED, ref, ref.expiresAt(), target.audit(), request,
                            merged.moved().stream().map(moved -> securable(catalog, moved.table())).toList());
                    return ticket.change(management.change(now, event), 200, answer[0]);
                });
        return switch (outcome) {
            case Merge.Outcome.Merged merged -> new Reply(200, answer[0], true);
            case Merge.Outcome.Refused refused when refused.plan().overCap() -> throw tooMany(refused.plan());
            case Merge.Outcome.Refused refused when !refused.plan().conflicts().isEmpty() ->
                    Reply.of(409, Management.JSON.writeValueAsString(conflicts(refused.plan(), now)));
            case Merge.Outcome.Refused refused -> throw ruled(request, catalog, refused.plan().refused().orElseThrow());
            case Merge.Outcome.Gone gone -> throw noSuchRef(name);
            case Merge.Outcome.Failed failed -> throw switch (failed.reply()) {
                case CommitReply.Failure failure -> CatalogError.of(failure.status(), failure.type(), failure.message());
                case CommitReply.ServerError error -> CatalogError.of(500, error.type(), error.message());
                default -> new IllegalStateException("a merge failed with " + failed.reply());
            };
        };
    }

    /** The caller's right to move each table and view of the plan, refused as the authorizer refuses it. */
    private void require(Request request, CatalogRecord catalog, Merge.Plan plan) {
        Rights rights = rights(request, catalog, plan);
        for (int i = 0; i < plan.tables().size(); i++) {
            if (rights.move().get(i) instanceof Authorizer.Decision.Denied denied) {
                throw guard.refuse(request, securable(catalog, plan.tables().get(i)), MERGE_OP, denied);
            }
        }
    }

    /** Main's rules refusing a table or view: audited as a commit's refusal is when it has a reason of governance. */
    private RuntimeException ruled(Request request, CatalogRecord catalog, Merge.Table table) {
        CommitReply.Failure failure = table.verdict().orElseThrow();
        return failure.refusal().<RuntimeException>map(refusal -> guard.ruled(request, securable(catalog, table),
                        MERGE_OP, operation(table).privilege(), refusal.reason(), failure.message()))
                .orElse(CatalogError.of(failure.status(), failure.type(), failure.message()));
    }

    /**
     * What a merge would do now, writing nothing: the answer it would get ({@code answer}), whether the caller holds
     * what it needs ({@code may-merge}), and each table and view the caller can describe, with what main did to it
     * since the cut, its conflict or main's verdict on its change, and the right it needs. The rest are counted
     * ({@code hidden}). Who committed on main is shown only to a caller holding {@code create} on the catalog.
     */
    private ObjectNode dryRun(Request request, CatalogRecord catalog, Merge.Plan plan, Instant now) {
        Rights rights = rights(request, catalog, plan);
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("dry-run", true);
        body.set("ref", ref(plan.ref(), now, rights.create()));
        body.put("changed", plan.changed());
        body.put("max-tables", plan.maxTables());
        ObjectNode answer = body.putObject("answer");
        if (plan.overCap()) {
            error(answer, tooMany(plan));
        } else if (!plan.conflicts().isEmpty()) {
            List<Merge.Table> seen = new ArrayList<>();
            for (int i = 0; i < plan.tables().size(); i++) {
                if (!plan.tables().get(i).moves() && rights.visible().get(i)) {
                    seen.add(plan.tables().get(i));
                }
            }
            error(answer, conflict(plan, seen));
        } else if (plan.refused().isPresent()) {
            CommitReply.Failure failure = plan.refused().get().verdict().orElseThrow();
            error(answer, CatalogError.of(failure.status(), failure.type(), failure.message()));
        } else {
            answer.put("code", 200);
        }
        boolean may = rights.create();
        int hidden = 0;
        ArrayNode tables = body.putArray("tables");
        for (int i = 0; i < plan.tables().size(); i++) {
            Merge.Table table = plan.tables().get(i);
            Authorizer.Decision move = rights.move().get(i);
            may &= !table.moves() || move.allowed();
            if (!rights.visible().get(i)) {
                hidden++;
                continue;
            }
            ObjectNode item = table(tables.addObject(), table);
            ObjectNode since = item.putObject("main-commits");
            since.put("count", table.since().commits());
            if (rights.create()) {
                table.since().by().forEach(since.putArray("by")::add);
            }
            if (table.moves()) {
                ObjectNode verdict = item.putObject("verdict");
                verdict.put("allowed", table.verdict().isEmpty());
                table.verdict().ifPresent(failure -> {
                    verdict.put("type", failure.type());
                    verdict.put("message", failure.message());
                    failure.refusal().ifPresent(refusal -> verdict.put("reason", refusal.reason()));
                });
                ObjectNode right = item.putObject("right");
                right.put("needs", operation(table).privilege().wireName());
                right.put("allowed", move.allowed());
            }
        }
        body.put("hidden", hidden);
        body.put("may-merge", may);
        return body;
    }

    /** A merge's answer: the branch it ended and main's new version of each table and view it moved. */
    private ObjectNode merged(Merge.Outcome.Merged merged, Instant now) {
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("ref", ref(merged.plan().ref(), now));
        ArrayNode tables = body.putArray("tables");
        for (Merge.Moved moved : merged.moved()) {
            table(tables.addObject(), moved.table()).put("metadata-location", moved.after().metadataLocation());
        }
        return body;
    }

    /** The 409 of a merge that conflicts: the error, and every table and view in conflict. */
    private ObjectNode conflicts(Merge.Plan plan, Instant now) {
        ObjectNode body = (ObjectNode) Management.JSON.readTree(Wire.error(409, "RefMergeConflictException",
                conflict(plan, plan.conflicts()).getMessage()));
        body.set("ref", ref(plan.ref(), now));
        ArrayNode tables = body.putArray("conflicts");
        plan.conflicts().forEach(table -> table(tables.addObject(), table));
        return body;
    }

    /**
     * A table or view of a merge: its name on main (the cut's, once purged), the cut's, main's and the branch's
     * versions, and main's new one if it moves; else its conflict, and when main dropped it. {@code cut-version} is
     * main's version when the cut was taken, in main's numbering as {@code main-version} is, even for a branch made
     * from a branch whose cut took that branch's own pointer (item 105 numbers those from 1); it is left out when
     * main's position at the cut cannot be known, which is a conflict. {@code branch-version} is on the branch's own
     * line.
     */
    private ObjectNode table(ObjectNode item, Merge.Table table) {
        levels(table).forEach(item.putArray("namespace")::add);
        item.put("name", name(table));
        item.put("kind", table.entry().kind().name().toLowerCase(Locale.ROOT));
        table.base().ifPresent(base -> item.put("cut-version", base.version()));
        table.main().ifPresent(main -> item.put("main-version", main.version()));
        item.put("branch-version", table.branch().version());
        item.put("moves", table.moves());
        if (table.moves()) {
            item.put("version", table.main().orElseThrow().version() + 1);
            item.put("rebased", table.rebased());
            table.definition().ifPresent(change -> item.put("definition-change", change));
        }
        table.conflict().ifPresent(conflict -> item.put("conflict", conflict));
        table.object().filter(object -> object.state() != CatalogObject.State.ACTIVE)
                .flatMap(CatalogObject::droppedAt).ifPresent(at -> item.put("dropped-at", at.toString()));
        return item;
    }

    private static void error(ObjectNode answer, CatalogError error) {
        answer.put("code", error.status());
        answer.put("type", error.type());
        answer.put("message", error.getMessage());
    }

    /**
     * The 409 of a merge that conflicts, naming {@code named} of its conflicts, each with the cut's, main's and the
     * branch's versions, and counting the others.
     */
    private CatalogError conflict(Merge.Plan plan, List<Merge.Table> named) {
        List<String> each = new ArrayList<>();
        for (Merge.Table table : named) {
            boolean dropped = table.object().map(object -> object.state() != CatalogObject.State.ACTIVE).orElse(true);
            each.add(String.join(".", levels(table)) + "." + name(table) + " (cut version "
                    + table.base().map(base -> Long.toString(base.version())).orElse("unknown")
                    + ", main " + (dropped ? "dropped" : "version " + table.main().orElseThrow().version())
                    + ", branch version " + table.branch().version() + "): " + table.conflict().orElseThrow());
        }
        int others = plan.conflicts().size() - named.size();
        return CatalogError.of(409, "RefMergeConflictException", "Branch " + plan.ref().name().display()
                + " conflicts with main in " + plan.conflicts().size() + " of the " + plan.changed()
                + " tables and views it changed, so nothing moved: " + String.join("; ", each)
                + (others == 0 ? "" : (each.isEmpty() ? "" : "; and ") + others + " the caller cannot describe"));
    }

    private static CatalogError tooMany(Merge.Plan plan) {
        return CatalogError.of(409, "RefLimitException", "Branch " + plan.ref().name().display() + " changed "
                + plan.changed() + " tables and views, and one merge moves at most " + plan.maxTables()
                + " (nuna.refs.max-merge-tables)");
    }

    /**
     * What the caller holds for a plan, decided in one batch as a transaction's tables are: {@code create} on the
     * catalog; and for each table and view, whether it can describe it, and the right to move it.
     */
    private record Rights(boolean create, List<Boolean> visible, List<Authorizer.Decision> move) {
    }

    private Rights rights(Request request, CatalogRecord catalog, Merge.Plan plan) {
        List<Authorizer.Request> asks = new ArrayList<>();
        asks.add(new Authorizer.Request(new Authorizer.Target.Catalog(catalog.id()),
                new Authorizer.Operation(MERGE_OP, Privilege.CREATE, false)));
        for (Merge.Table table : plan.tables()) {
            Authorizer.Target on = table.object().<Authorizer.Target>map(Authorizer.Target.TableOrView::new)
                    .orElse(new Authorizer.Target.Catalog(catalog.id()));
            asks.add(new Authorizer.Request(on, new Authorizer.Operation(MERGE_OP, Privilege.DESCRIBE, false)));
            asks.add(new Authorizer.Request(on, operation(table)));
        }
        List<Authorizer.Decision> decided = authorizer.decide(request.caller(), asks);
        List<Boolean> visible = new ArrayList<>();
        List<Authorizer.Decision> move = new ArrayList<>();
        for (int i = 0; i < plan.tables().size(); i++) {
            visible.add(decided.get(1 + 2 * i).allowed());
            Authorizer.Decision decision = decided.get(2 + 2 * i);
            move.add(decision instanceof Authorizer.Decision.Exempted ? new Authorizer.Decision.Denied(
                    Authorizer.Reason.FLAGGED_TABLE, null, "table " + name(plan.tables().get(i)) + " has a row/column"
                    + " policy; the maintenance exemption is for maintenance, so only a trusted engine that enforces it"
                    + " may merge it") : decision);
        }
        return new Rights(decided.getFirst().allowed(), visible, move);
    }

    /**
     * What moving a table or view asks of the caller on main, as a commit of its change would (D18): {@code write}
     * for data alone, {@code modify} for a definition change, a view's always; storage access for a table (D20).
     */
    private static Authorizer.Operation operation(Merge.Table table) {
        return new Authorizer.Operation(MERGE_OP, table.definition().isPresent() ? Privilege.MODIFY : Privilege.WRITE,
                table.entry().kind() == RefEntry.Kind.TABLE);
    }

    /** A catalog's merge, whose {@code Commit} events name each table and view by its path on main. */
    private Merge merging(CatalogRecord catalog, Request request) {
        return merges.of(catalog, audit.commits(catalog.name(), object -> {
            List<String> path = new ArrayList<>(management.levels(object.catalogId(), object.namespaceId())
                    .orElseThrow());
            path.add(object.name().display());
            return path;
        }, request.actor()));
    }

    /** A table's or view's namespace on main; the cut's once main has no such namespace. */
    private List<String> levels(Merge.Table table) {
        return table.object().flatMap(object -> management.levels(object.catalogId(), object.namespaceId()))
                .orElse(table.namespace());
    }

    private static String name(Merge.Table table) {
        return table.object().map(object -> object.name().display()).orElse(table.entry().name().display());
    }

    private nuna.catalog.audit.Securable securable(CatalogRecord catalog, Merge.Table table) {
        return Management.audit(catalog, levels(table), name(table), table.object().orElseThrow());
    }

    /** The catalog's branch of that name, live; a tag never merges. */
    private Ref branch(CatalogRecord catalog, String name, Instant now) {
        Ref ref = live(catalog, name, now);
        if (ref.kind() == Ref.Kind.TAG) {
            throw CatalogError.badRequest("Ref " + ref.name().display() + " is a tag; only a branch merges");
        }
        return ref;
    }

    /**
     * The maintenance worker's warning of a branch within {@code expiry-warning} of its expiry (choice 11): one
     * {@code ref} event (expiring) on the audit topic and the change stream, once for each expiry the branch reaches,
     * so a renewed branch is warned of again as its new expiry nears. Answers 200 with {@code "sent"}: true when this
     * call sent the warning, false when it had been sent; 409 when the branch is not within the warning's reach.
     */
    @PostMapping(path = WARN, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> warn(@PathVariable String id, HttpServletRequest http) {
        Request request = management.request(http);
        Optional<Ref> found = ref(id);
        guard.requireMaintenance(request, found.map(this::catalogOf).orElse(null), "warnRefExpiry");
        Instant now = now();
        Ref ref = found.filter(r -> !r.expiredAt(now)).orElseThrow(() -> noSuchRef(id));
        if (!settings.expiring(ref, now)) {
            throw CatalogError.of(409, "RefNotExpiringException", "Ref " + ref.name().display() + " is not within "
                    + settings.expiryWarning() + " of an expiry (nuna.refs.expiry-warning)");
        }
        Instant expiry = ref.expiresAt().orElseThrow();
        String marker = warned(ref.id(), expiry);
        UUID claim = UUID.randomUUID();
        KeyState state = store.claimKey(new IdempotencyClaim(marker, WARNINGS, hash(marker), claim, now,
                now.plus(WARNING_LEASE), expiry));
        boolean sent = switch (state) {
            case KeyState.Claimed claimed -> {
                send(ref, expiry, request, marker, claim, now);
                yield true;
            }
            case KeyState.Done done -> false;
            case KeyState.InFlight inFlight -> {
                if (store.awaitKey(marker, WARNING_LEASE) instanceof KeyState.Done) {
                    yield false;
                }
                throw CatalogError.of(409, "RefWarningInFlightException", "Another call is sending the warning of ref "
                        + ref.name().display() + "; ask again later");
            }
            default -> throw new IllegalStateException("the warning marker of ref " + ref.id() + " answered " + state);
        };
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("id", ref.id().toString());
        body.put("expires-at", expiry.toString());
        body.put("sent", sent);
        return GovernanceRoutes.json(200, body);
    }

    private void send(Ref ref, Instant expiry, Request request, String marker, UUID claim, Instant now) {
        try {
            notices.send(CatalogEvent.create(rest.auditSource(), now, event(RefChange.Action.EXPIRING, ref,
                    Optional.of(expiry), catalogOf(ref), request)));
        } catch (RuntimeException e) {
            try {
                store.releaseKey(marker, claim);
            } catch (RuntimeException released) {
                LOG.warn("releasing a ref warning's marker failed; it lapses with its lease", released);
            }
            throw e;
        }
        try {
            store.completeKey(new IdempotencyResult(marker, WARNINGS, hash(marker), 201, "{}", expiry), claim);
        } catch (RuntimeException e) {
            // The warning went out; if the marker is lost, it may go out once more, as the relay's delivery may.
            LOG.warn("recording that ref {} was warned of failed", ref.id(), e);
        }
    }

    /**
     * The maintenance worker's end of a branch past its expiry (choice 11): deleted as {@link #delete} deletes, its
     * {@code ref} event (expired) naming the worker. Answers 204; 409 for a tag or a branch not yet expired.
     */
    @PostMapping(path = END)
    ResponseEntity<String> end(@PathVariable String id, HttpServletRequest http) {
        Request request = management.request(http);
        Optional<Ref> found = ref(id);
        guard.requireMaintenance(request, found.map(this::catalogOf).orElse(null), "endExpiredRef");
        Ref ref = found.orElseThrow(() -> noSuchRef(id));
        Instant now = now();
        if (!ref.expiredAt(now)) {
            throw CatalogError.of(409, "RefNotExpiredException", "Ref " + ref.name().display()
                    + (ref.kind() == Ref.Kind.TAG ? " is a tag, which never expires" : " expires at "
                    + ref.expiresAt().orElseThrow()));
        }
        RefChange event = event(RefChange.Action.EXPIRED, ref, ref.expiresAt(), catalogOf(ref), request);
        WriteResult result = Management.write("Ending ref " + id, () -> store.deleteRef(ref.id(),
                management.change(now, event)), () -> store.readRef(ref.id()).isEmpty());
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw noSuchRef(id);
            default -> throw new IllegalStateException("deleteRef answered " + result);
        };
    }

    /**
     * The marker in the idempotency store that the warning of {@code ref}'s expiry at {@code expiry} was sent. It is
     * longer than any key a client may send (128 characters), so no request can take it, and it lapses at the expiry.
     */
    static String warned(UUID ref, Instant expiry) {
        String named = "nuna-ref-expiring:" + ref + ":" + expiry;
        try {
            return named + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(named.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    /** The request hash a warning marker is held with: the SHA-256 it ends with. */
    private static String hash(String marker) {
        return marker.substring(marker.lastIndexOf(':') + 1);
    }

    /**
     * The tables and views of the branch's cut that it has its own pointer for, which the caller can describe on
     * main: each with its namespace, the cut's version and the branch's.
     */
    private ArrayNode changed(Request request, Ref ref) {
        List<RefEntry> objects = new ArrayList<>();
        List<Pointer> pointers = new ArrayList<>();
        List<List<String>> paths = new ArrayList<>();
        Deque<Level> levels = new ArrayDeque<>();
        levels.add(new Level(ref.catalogId(), List.of()));
        while (!levels.isEmpty()) {
            Level parent = levels.removeFirst();
            for (RefEntry namespace : walk(page -> store.listRefNamespaces(ref.id(), parent.id(), page))) {
                List<String> path = new ArrayList<>(parent.path());
                path.add(namespace.name().display());
                levels.add(new Level(namespace.id(), List.copyOf(path)));
                for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
                    for (RefEntry object : walk(page -> store.listRefObjects(ref.id(), namespace.id(), kind, page))) {
                        Optional<Pointer> own = store.readPointer(ref.key(object.id()));
                        if (own.isPresent()) {
                            objects.add(object);
                            pointers.add(own.get());
                            paths.add(List.copyOf(path));
                        }
                    }
                }
            }
        }
        List<Authorizer.Request> asks = new ArrayList<>();
        for (RefEntry object : objects) {
            Authorizer.Target on = store.readObject(object.id())
                    .<Authorizer.Target>map(Authorizer.Target.TableOrView::new)
                    .orElse(new Authorizer.Target.Catalog(ref.catalogId()));
            asks.add(new Authorizer.Request(on, new Authorizer.Operation("loadRef", Privilege.DESCRIBE, false)));
        }
        List<Authorizer.Decision> decisions = asks.isEmpty() ? List.of() : authorizer.decide(request.caller(), asks);
        ArrayNode changed = Management.JSON.createArrayNode();
        for (int i = 0; i < objects.size(); i++) {
            if (!decisions.get(i).allowed()) {
                continue;
            }
            RefEntry object = objects.get(i);
            ObjectNode item = changed.addObject();
            ArrayNode namespace = item.putArray("namespace");
            paths.get(i).forEach(namespace::add);
            item.put("name", object.name().display());
            item.put("kind", object.kind().name().toLowerCase(Locale.ROOT));
            object.pointer().ifPresent(at -> item.put("cut-version", at.version()));
            item.put("version", pointers.get(i).version());
        }
        return changed;
    }

    /** A namespace of a cut, or the catalog, with its levels by display name. */
    private record Level(UUID id, List<String> path) {
    }

    private static List<RefEntry> walk(java.util.function.Function<PageRequest, Page<RefEntry>> list) {
        List<RefEntry> all = new ArrayList<>();
        PageRequest page = PageRequest.first(WALK_PAGE);
        while (true) {
            Page<RefEntry> listed = list.apply(page);
            all.addAll(listed.items());
            if (listed.nextToken().isEmpty()) {
                return all;
            }
            page = page.after(listed);
        }
    }

    // ---- taking a table or view out of refs (backlog 156) ----

    /**
     * Takes a table or view out of the catalog's refs (D33, amended 2026-11-22; backlog 156, security finding 6), so a
     * dropped one's data can leave tags: its entry leaves each ref's cut, and a branch's own pointer for it goes with
     * it, in one transaction ({@link nuna.catalog.store.RefStore#removeFromRefs}). Reads of it through those refs then
     * answer 404, as for a name the cut never held, and once no ref names a dropped one its purge claim goes through.
     * The object is named by id, so a dropped or unregistered one is reached. Body: {@code {"refs": [name, ...]}} for
     * the live refs it names, or {@code {"all-refs": true}} for every ref whose cut names it, a branch past its expiry
     * included.
     *
     * <p>It needs {@code manage-tags} on the object, the tag steward's duty, which D18 keeps apart from data, so a data
     * writer cannot erase history with it; and it is refused under a legal hold (D24) or a DR freeze (D37) on the
     * object, a namespace above it or the catalog. Each refusal is audited as a denial. Answers 200 with the object and
     * the refs it left, each with its {@code ref} record (object-removed) naming the object, written with the change; 404
     * for an object the catalog does not hold or the caller holds nothing on, and for a named ref that is gone or does
     * not hold the object; 403 when the right, a hold or a freeze refuses it. With every ref asked and none naming the
     * object, it answers 200 with no refs and writes nothing.
     */
    @PostMapping(path = REMOVE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> remove(@PathVariable("catalog") String prefix, @PathVariable String id,
                                  @RequestHeader(name = ManagementIdempotency.HEADER, required = false) String key,
                                  @RequestBody(required = false) String body, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, REMOVE_OP, Privilege.MANAGE_TAGS);
        Target target = objectTarget(catalog, id);
        guard.require(request, target, REMOVE_OP, Privilege.MANAGE_TAGS);
        return idempotency.run(Optional.ofNullable(key), request.caller(), "REMOVE_FROM_REFS",
                catalog.id() + "/" + target.securable().id(), body, ticket -> remove(target, request, body, ticket));
    }

    private Reply remove(Target target, Request request, String body, ManagementIdempotency.Ticket ticket) {
        Optional<List<String>> named = refNames(Management.body(body));
        CatalogObject object = target.object().orElseThrow();
        String what = String.join(".", target.audit().path());
        if (target.chain().held()) {
            throw guard.held(request, target.audit(), REMOVE_OP, what + " is under a legal hold, so its refs keep it");
        }
        guard.requireUnfrozen(request, target.chain(), target.audit(), REMOVE_OP, what);
        for (int attempt = 1; ; attempt++) {
            Instant now = now();
            List<Ref> refs = leaving(target.catalog(), object, named, now);
            String answer = Management.JSON.writeValueAsString(removed(target, refs, now));
            if (refs.isEmpty()) {
                return Reply.of(200, answer);
            }
            Set<UUID> ids = refs.stream().map(Ref::id).collect(Collectors.toCollection(LinkedHashSet::new));
            nuna.catalog.audit.Securable catalog = new nuna.catalog.audit.Securable(
                    nuna.catalog.audit.Securable.Kind.CATALOG, target.catalog().id(), target.catalog().name(), List.of());
            List<EventData> events = refs.stream().<EventData>map(ref -> event(RefChange.Action.OBJECT_REMOVED, ref,
                    ref.expiresAt(), catalog, request, List.of(target.audit()))).toList();
            WriteResult result = Management.write("Taking " + what + " out of refs", () -> store.removeFromRefs(
                            object.id(), ids, named.isEmpty(), ticket.change(management.change(now, events), 200, answer)),
                    () -> store.listRefPointers(object.id()).stream().noneMatch(held -> ids.contains(held.ref().id())));
            switch (result) {
                case WriteResult.Applied applied -> {
                    return new Reply(200, answer, true);
                }
                case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.OBJECT ->
                        throw target.missing();
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD ->
                        throw guard.held(request, target.audit(), REMOVE_OP, what + " is under a legal hold, so its refs"
                                + " keep it");
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.FROZEN ->
                        throw guard.frozen(request, target.audit(), REMOVE_OP, what + " is frozen for DR reconcile");
                // A ref read above was deleted, or another came to name the object: read the refs again.
                case WriteResult.Missing missing -> {
                }
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.REFERENCED -> {
                }
                default -> throw new IllegalStateException("removeFromRefs answered " + result);
            }
            if (attempt >= REMOVE_ATTEMPTS) {
                throw refsChanged(what);
            }
        }
    }

    /** The table or view {@code id} names in the catalog, dropped or not, until it is purged, as a target. */
    private Target objectTarget(CatalogRecord catalog, String id) {
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an object id: " + id);
        }
        CatalogError missing = CatalogError.of(404, "NoSuchObjectException", "No table or view " + id + " in "
                + catalog.name());
        CatalogObject object = store.readObject(objectId).filter(o -> o.catalogId().equals(catalog.id())
                && o.state() != CatalogObject.State.PURGED).orElseThrow(() -> missing);
        return objectTarget(catalog, object, missing);
    }

    /** A table or view of the catalog as a target, answered {@code missing} to a caller who holds nothing on it. */
    private Target objectTarget(CatalogRecord catalog, CatalogObject object, CatalogError missing) {
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        return new Target(catalog, object.securable(), Management.audit(catalog, levels, object.name().display(),
                object), Optional.of(object), GovernanceChain.of(store, object), missing);
    }

    /**
     * The refs a removal names: {@code {"refs": [name, ...]}}; empty for {@code {"all-refs": true}}, every ref. One of
     * the two, never both, so a body that names nothing never means every ref.
     */
    private static Optional<List<String>> refNames(JsonNode body) {
        JsonNode all = body.get("all-refs");
        JsonNode refs = body.get("refs");
        if (all != null && !all.isBoolean()) {
            throw CatalogError.badRequest("Field all-refs must be true or absent");
        }
        boolean every = all != null && all.asBoolean();
        if (every == (refs != null)) {
            throw CatalogError.badRequest("Name the refs to take the table or view out of, {\"refs\": [name, ...]},"
                    + " or ask for every ref that names it, {\"all-refs\": true}");
        }
        if (every) {
            return Optional.empty();
        }
        if (!refs.isArray() || refs.isEmpty() || refs.size() > MAX_REMOVE_REFS) {
            throw CatalogError.badRequest("Field refs must be an array of 1 to " + MAX_REMOVE_REFS + " ref names");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : refs) {
            if (!name.isString() || name.asString().isBlank() || name.asString().length() > MAX_NAME) {
                throw CatalogError.badRequest("Field refs must hold ref names, each a non-empty string of at most "
                        + MAX_NAME + " characters");
            }
            names.add(name.asString());
        }
        return Optional.of(names);
    }

    /**
     * The refs a removal leaves, by name: each one named, a live ref of the catalog whose cut names the object; or,
     * with none named, every ref whose cut names it.
     */
    private List<Ref> leaving(CatalogRecord catalog, CatalogObject object, Optional<List<String>> named, Instant now) {
        List<Ref> naming = store.listRefPointers(object.id()).stream().map(RefPointers::ref).toList();
        if (named.isEmpty()) {
            return naming;
        }
        Map<UUID, Ref> refs = new LinkedHashMap<>();
        for (String name : named.get()) {
            Ref ref = live(catalog, name, now);
            if (naming.stream().noneMatch(holder -> holder.id().equals(ref.id()))) {
                throw CatalogError.of(404, "NoSuchObjectException", "No table or view " + object.id() + " on ref "
                        + ref.name().display() + " of " + catalog.name());
            }
            refs.putIfAbsent(ref.id(), ref);
        }
        return List.copyOf(refs.values());
    }

    /** A removal's answer: the object, and the refs it left, as the list shows them to a caller without create. */
    private ObjectNode removed(Target target, List<Ref> refs, Instant now) {
        CatalogObject object = target.object().orElseThrow();
        List<String> path = target.audit().path();
        ObjectNode body = Management.JSON.createObjectNode();
        ObjectNode node = body.putObject("object");
        node.put("id", object.id().toString());
        path.subList(0, path.size() - 1).forEach(node.putArray("namespace")::add);
        node.put("name", object.name().display());
        node.put("kind", object.kind().name().toLowerCase(Locale.ROOT));
        node.put("state", object.state().name().toLowerCase(Locale.ROOT));
        ArrayNode left = body.putArray("refs");
        refs.forEach(ref -> left.add(ref(ref, now, false)));
        return body;
    }

    private static CatalogError refsChanged(String what) {
        return CatalogError.of(409, "RefsChangedException", "The refs naming " + what + " kept changing while it was"
                + " being taken out of them; ask again");
    }

    // ---- which refs keep a table or view (backlog 170, 279) ----

    /**
     * The tables and views at a path that the catalog still tracks, live or dropped, and the refs that keep each
     * (backlog 170, item 279's spec): {@code {"objects": [...]}}, newest first, the live one, then each dropped one by
     * when it was dropped, so a name dropped, made again and dropped again is two objects ({@link #keptAnswer}).
     * Needs {@code describe}: each object the caller holds nothing on is left out, and when that leaves none, the
     * answer is 404 {@code NoSuchObjectException}, as for a path that has none, the refusal audited as Holdings has
     * it. A view is reached by the same path, since tables and views share their names.
     */
    @GetMapping(path = PATH_KEPT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> keptAt(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                  @PathVariable String name, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, KEPT_OP, Privilege.DESCRIBE);
        List<String> levels = RestRequest.namespaceLevels(namespace);
        CatalogError missing = CatalogError.of(404, "NoSuchObjectException", "No table or view "
                + String.join(".", levels) + "." + name + " in " + catalog.name());
        AtPath found = store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList())
                .map(at -> atPath(at.id(), Names.normalize(name))).orElse(new AtPath(List.of(), false));
        return keptAnswer(request, catalog, found.found(), found.cutShort(), missing);
    }

    /** {@link #keptAt} for the one table or view an id names, until it is purged or unregistered. */
    @GetMapping(path = KEPT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> kept(@PathVariable("catalog") String prefix, @PathVariable String id,
                                HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, KEPT_OP, Privilege.DESCRIBE);
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an object id: " + id);
        }
        CatalogError missing = CatalogError.of(404, "NoSuchObjectException", "No table or view " + id + " in "
                + catalog.name());
        return keptAnswer(request, catalog, store.readObject(objectId).filter(o -> o.catalogId().equals(catalog.id())
                && tracked(o)).stream().toList(), false, missing);
    }

    /** Whether the catalog still tracks an object for this read: live, dropped, or being purged. */
    private static boolean tracked(CatalogObject object) {
        return object.state() == CatalogObject.State.ACTIVE || object.state() == CatalogObject.State.DROPPED
                || object.state() == CatalogObject.State.PURGING;
    }

    /** The objects found at a path, and whether the walk for its dropped ones stopped at {@link #KEPT_WALK_LIMIT}. */
    private record AtPath(List<CatalogObject> found, boolean cutShort) {
    }

    /**
     * The tables and views a namespace holds by a name, newest first: the live one, then by when each was dropped. The
     * dropped ones are found by walking the namespace's dropped and purging objects, at most {@link #KEPT_WALK_LIMIT}.
     */
    private AtPath atPath(UUID namespaceId, String normalizedName) {
        List<CatalogObject> found = new ArrayList<>();
        store.findObject(namespaceId, normalizedName).ifPresent(found::add);
        List<CatalogObject> dropped = new ArrayList<>();
        int read = 0;
        boolean cutShort = false;
        walk:
        for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
            for (CatalogObject.State state : List.of(CatalogObject.State.DROPPED, CatalogObject.State.PURGING)) {
                if (read >= KEPT_WALK_LIMIT) {
                    cutShort = true;
                    break walk;
                }
                PageRequest page = PageRequest.first(WALK_PAGE);
                while (true) {
                    Page<CatalogObject> listed = store.listObjects(namespaceId, kind, state, page);
                    read += listed.items().size();
                    listed.items().stream().filter(o -> o.name().normalized().equals(normalizedName))
                            .forEach(dropped::add);
                    if (listed.nextToken().isEmpty()) {
                        break;
                    }
                    if (read >= KEPT_WALK_LIMIT) {
                        cutShort = true;
                        break walk;
                    }
                    page = page.after(listed);
                }
            }
        }
        dropped.sort(java.util.Comparator.comparing((CatalogObject o) -> o.droppedAt().orElseThrow()).reversed());
        found.addAll(dropped);
        return new AtPath(found, cutShort);
    }

    /**
     * Each object of {@code found} the caller may describe: {@code id}, {@code kind}, {@code namespace},
     * {@code name}, {@code state} ({@code active}, {@code dropped} or {@code purging}), {@code made-at} (main's first
     * commit of it, from the journal), {@code dropped-at} and {@code purge-after} once dropped, and {@code dropped-by}
     * ({@code principal}, {@code end-user}) where {@link Drops} finds the drop's audit record for this caller;
     * {@code refs}, each ref whose cut keeps it ({@code name}, {@code kind}, {@code created-at}, {@code expires-at},
     * {@code expired}: past its expiry, its name still held until the worker ends it; and {@code own-version}, a
     * branch's own pointer's version where one of its commits changed it), by name; and {@code may.remove}, whether
     * the caller holds {@code manage-tags} on it, which taking it out of refs needs. Who made each ref is left out, as
     * for a caller without create. Who dropped it is looked up for the newest {@link #DROPPERS_READ} dropped objects
     * only; each past them has {@code dropped-by-left-out: true}. A walk stopped at {@link #KEPT_WALK_LIMIT} adds
     * {@code cut-short: true} to the answer.
     */
    private ResponseEntity<String> keptAnswer(Request request, CatalogRecord catalog, List<CatalogObject> found,
                                              boolean cutShort, CatalogError missing) {
        List<Authorizer.Request> asks = new ArrayList<>();
        for (CatalogObject object : found) {
            Authorizer.Target on = new Authorizer.Target.TableOrView(object);
            asks.add(new Authorizer.Request(on, new Authorizer.Operation(KEPT_OP, Privilege.DESCRIBE, false)));
            asks.add(new Authorizer.Request(on, new Authorizer.Operation(REMOVE_OP, Privilege.MANAGE_TAGS, false)));
        }
        List<Authorizer.Decision> decisions = asks.isEmpty() ? List.of() : authorizer.decide(request.caller(), asks);
        Instant now = now();
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode objects = body.putArray("objects");
        int droppers = 0;
        for (int i = 0; i < found.size(); i++) {
            if (decisions.get(2 * i).allowed()) {
                CatalogObject object = found.get(i);
                boolean dropped = object.state() != CatalogObject.State.ACTIVE;
                objects.add(keptObject(request, catalog, object, decisions.get(2 * i + 1).allowed(),
                        dropped && droppers < DROPPERS_READ, now));
                droppers += dropped ? 1 : 0;
            }
        }
        if (cutShort) {
            body.put("cut-short", true);
        }
        if (objects.isEmpty()) {
            if (!found.isEmpty()) {
                guard.require(request, objectTarget(catalog, found.getFirst(), missing), KEPT_OP, Privilege.DESCRIBE);
            }
            throw missing;
        }
        return GovernanceRoutes.json(200, body);
    }

    private ObjectNode keptObject(Request request, CatalogRecord catalog, CatalogObject object, boolean mayRemove,
                                  boolean readDropper, Instant now) {
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", object.id().toString());
        node.put("kind", object.kind().name().toLowerCase(Locale.ROOT));
        levels.forEach(node.putArray("namespace")::add);
        node.put("name", object.name().display());
        node.put("state", object.state().name().toLowerCase(Locale.ROOT));
        store.readJournal(object.mainKey(), JournalStore.EARLIEST, JournalStore.LATEST, JournalStore.afterVersion(0, 1))
                .items().stream().filter(entry -> entry.newVersion() == 1).findFirst()
                .ifPresent(made -> node.put("made-at", made.committedAt().toString()));
        if (object.state() != CatalogObject.State.ACTIVE) {
            node.put("dropped-at", object.droppedAt().orElseThrow().toString());
            object.purgeAfter().ifPresent(at -> node.put("purge-after", at.toString()));
            if (readDropper) {
                drops.droppedBy(request, catalog, object, Management.audit(catalog, levels,
                        object.name().display(), object)).ifPresent(by -> {
                            ObjectNode who = node.putObject("dropped-by");
                            who.put("principal", by.principal());
                            if (by.endUser() != null) {
                                who.put("end-user", by.endUser());
                            }
                        });
            } else {
                // Past the newest DROPPERS_READ drops, no one is looked up, and the answer says so.
                node.put("dropped-by-left-out", true);
            }
        }
        ArrayNode refs = node.putArray("refs");
        store.listRefPointers(object.id()).stream()
                .sorted(java.util.Comparator.comparing((RefPointers held) -> held.ref().name().normalized()))
                .forEach(held -> {
                    Ref ref = held.ref();
                    ObjectNode each = refs.addObject();
                    each.put("name", ref.name().display());
                    each.put("kind", ref.kind().name().toLowerCase(Locale.ROOT));
                    each.put("created-at", ref.createdAt().toString());
                    ref.expiresAt().ifPresent(at -> each.put("expires-at", at.toString()));
                    each.put("expired", ref.expiredAt(now));
                    held.head().ifPresent(own -> each.put("own-version", own.version()));
                });
        node.putObject("may").put("remove", mayRemove);
        return node;
    }

    /**
     * Who dropped a table or view, from its drop's audit record (backlog 170): the catalog does not keep it with the
     * object. Empty where the caller may not read the audit, or the record cannot be read or found.
     */
    @FunctionalInterface
    interface Drops {
        Optional<Dropper> droppedBy(Request request, CatalogRecord catalog, CatalogObject object,
                                    nuna.catalog.audit.Securable named);
    }

    /** Who dropped it: the principal, and the end user a trusted engine named, or null. */
    record Dropper(String principal, String endUser) {
    }

    // ---- helpers ----

    /** The catalog's ref of that name, unless it is a branch past its expiry, which is gone. */
    private Ref live(CatalogRecord catalog, String name, Instant now) {
        return store.findRef(catalog.id(), Names.normalize(name)).filter(ref -> !ref.expiredAt(now))
                .orElseThrow(() -> noSuchRef(name));
    }

    /** The ref a maintenance route's path names, by id. */
    private Optional<Ref> ref(String id) {
        try {
            return store.readRef(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a ref id: " + id);
        }
    }

    private nuna.catalog.audit.Securable catalogOf(Ref ref) {
        String name = catalogs.name(ref.catalogId());
        return new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG, ref.catalogId(),
                name == null ? ref.catalogId().toString() : name, List.of());
    }

    private static RefChange event(RefChange.Action action, Ref ref, Optional<Instant> expiresAt,
                                   nuna.catalog.audit.Securable catalog, Request request) {
        return event(action, ref, expiresAt, catalog, request, List.of());
    }

    private static RefChange event(RefChange.Action action, Ref ref, Optional<Instant> expiresAt,
                                   nuna.catalog.audit.Securable catalog, Request request,
                                   List<nuna.catalog.audit.Securable> tables) {
        return new RefChange(action, ref.id(), ref.name().display(), ref.kind() == Ref.Kind.BRANCH
                ? RefChange.Kind.BRANCH : RefChange.Kind.TAG, catalog, ref.fromName(), expiresAt.orElse(null),
                tables, request.actor());
    }

    /** A ref as the create and renew routes answer it, to a caller holding create on the catalog. */
    private ObjectNode ref(Ref ref, Instant now) {
        return ref(ref, now, true);
    }

    /**
     * A ref as the routes show it. {@code created-by}, a principal's id, which is personal data, only when
     * {@code withCreator}: to a caller holding create on the catalog, who may make and delete its refs.
     */
    private ObjectNode ref(Ref ref, Instant now, boolean withCreator) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", ref.id().toString());
        node.put("name", ref.name().display());
        node.put("kind", ref.kind().name().toLowerCase(Locale.ROOT));
        node.put("from", ref.fromName());
        node.put("created-at", ref.createdAt().toString());
        if (withCreator) {
            node.put("created-by", ref.createdBy());
        }
        ref.expiresAt().ifPresent(expiry -> {
            node.put("expires-at", expiry.toString());
            node.put("max-expires-at", settings.lastExpiry(ref.createdAt()).toString());
            node.put("expiring", settings.expiring(ref, now));
        });
        cut(node, ref);
        return node;
    }

    /**
     * When the ref's cut was taken from main, and the branches whose changes it holds besides (backlog 168), from the
     * refs it was made from. A ref made from main was cut when it was made. One made from another ref took that ref's
     * cut, so the walk goes down the refs each was made from, to the one made from main: {@code cut-at} is its making.
     * A ref made from a branch also took that branch's own pointers as they were at its making: {@code branch-changes}
     * names each such branch, nearest first, with {@code as-of}, when they were taken. A tag in the chain carries what
     * it took on. Where a ref in the chain has been deleted (or merged, which ends it), what it was made from is not
     * kept, so the walk stops: {@code cut-at} is left out and {@code cut-from-ended} names that ref, as it was named
     * when the one above was made from it. A branch past its expiry is kept until the worker ends it, so the walk
     * passes through it.
     */
    private void cut(ObjectNode node, Ref ref) {
        Ref at = ref;
        ArrayNode changes = Management.JSON.createArrayNode();
        while (at.fromRef().isPresent()) {
            Optional<Ref> source = store.readRef(at.fromRef().get());
            if (source.isEmpty()) {
                node.put("cut-from-ended", at.fromName());
                break;
            }
            if (source.get().kind() == Ref.Kind.BRANCH) {
                changes.addObject().put("branch", source.get().name().display())
                        .put("as-of", at.createdAt().toString());
            }
            at = source.get();
        }
        if (at.fromRef().isEmpty()) {
            node.put("cut-at", at.createdAt().toString());
        }
        if (!changes.isEmpty()) {
            node.set("branch-changes", changes);
        }
    }

    /** Whether the caller holds create on the catalog; asked, not required, so a no is no refusal. */
    private boolean mayCreate(Request request, CatalogRecord catalog, String operation) {
        return authorizer.decide(request.caller(), List.of(new Authorizer.Request(
                new Authorizer.Target.Catalog(catalog.id()), new Authorizer.Operation(operation, Privilege.CREATE,
                        false)))).getFirst().allowed();
    }

    private CatalogError nameTaken(String name, UUID holder, Instant now) {
        boolean expired = store.readRef(holder).map(ref -> ref.expiredAt(now)).orElse(false);
        return CatalogError.of(409, "AlreadyExistsException", "Ref already exists: " + name + (expired
                ? " (a branch past its expiry keeps its name until the maintenance worker ends it)" : ""));
    }

    private CatalogError limit(CatalogRecord catalog, Ref.Kind kind) {
        return kind == Ref.Kind.BRANCH
                ? CatalogError.of(409, "RefLimitException", "Catalog " + catalog.name() + " holds "
                        + settings.maxBranches() + " open branches, as many as nuna.refs.max-branches allows")
                : CatalogError.of(409, "RefLimitException", "Catalog " + catalog.name() + " holds "
                        + settings.maxTags() + " tags, as many as nuna.refs.max-tags allows");
    }

    private static CatalogError noSuchRef(String name) {
        return CatalogError.of(404, "NoSuchRefException", "Ref does not exist: " + name);
    }

    private static CatalogError notABranch(Ref ref) {
        return CatalogError.badRequest("Ref " + ref.name().display() + " is a tag, which never expires");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
