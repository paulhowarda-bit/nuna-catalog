package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.RefChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.governance.ManagementIdempotency.Reply;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
 * branch about to expire and end one past its expiry. Making, renewing and deleting a ref needs {@code create} on the
 * catalog; listing and reading need any holding in the catalog (D18 as amended), and show who made a ref only to a
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
    static final String WARN = ROOT + "/maintenance/refs/{id}/warn";
    static final String END = ROOT + "/maintenance/refs/{id}/end";

    /** The principal of the warning markers in the idempotency store, which no caller's id can be. */
    static final String WARNINGS = "nuna.refs";

    private static final Logger LOG = LoggerFactory.getLogger(RefRoutes.class);
    private static final int PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final int WALK_PAGE = 1000;
    private static final int MAX_NAME = 1000;
    private static final Duration WARNING_LEASE = Duration.ofMinutes(1);

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
    private final Clock clock;

    RefRoutes(CatalogStore store, Management management, ManagementGuard guard, ManagementIdempotency idempotency,
              Authorizer authorizer, RefSettings settings, CatalogNames catalogs, Notices notices, RestSettings rest,
              Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.idempotency = idempotency;
        this.authorizer = authorizer;
        this.settings = settings;
        this.catalogs = catalogs;
        this.notices = notices;
        this.rest = rest;
        this.clock = clock;
    }

    /** The catalog's refs by name, a branch past its expiry left out; paged. */
    @GetMapping(path = REFS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> list(@PathVariable("catalog") String prefix,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
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
        page.items().stream().filter(ref -> !ref.expiredAt(now)).forEach(ref -> refs.add(ref(ref, now, creators)));
        page.nextToken().ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /** One ref; for a branch, also the tables and views it changed that the caller can describe. */
    @GetMapping(path = REF, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> read(@PathVariable("catalog") String prefix, @PathVariable("ref") String name,
                                HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "loadRef", null);
        Instant now = now();
        Ref ref = live(catalog, name, now);
        ObjectNode body = ref(ref, now, mayCreate(request, catalog, "loadRef"));
        if (ref.kind() == Ref.Kind.BRANCH) {
            body.set("changed", changed(request, ref));
        }
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
        return new RefChange(action, ref.id(), ref.name().display(), ref.kind() == Ref.Kind.BRANCH
                ? RefChange.Kind.BRANCH : RefChange.Kind.TAG, catalog, ref.fromName(), expiresAt.orElse(null),
                List.of(), request.actor());
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
        return node;
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
