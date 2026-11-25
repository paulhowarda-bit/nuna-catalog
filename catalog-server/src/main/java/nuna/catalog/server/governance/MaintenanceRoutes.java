package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.audit.FileDeletion;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.security.Authorizer;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.commit.GovernanceChain;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The maintenance worker's side of the catalog (D30), for a maintenance principal acting as itself only: the
 * work list it scans, and the files it records for deletion and reports deleted. Every file the worker deletes
 * waits out the database's PITR window as a pending deletion first, so a database restored to any point it
 * still holds never names a deleted file. Each route asks {@link ManagementGuard} before any other work. The work
 * list also names the branches to warn of and to end (D33, choice 11), which {@link RefRoutes} does.
 *
 * <p>Also the worker's nights (D45, backlog 126; {@link MaintenanceNights}): the worker reports each job it runs, and
 * anyone signed in reads each night's totals and the jobs on its slots, a job's table named only to a caller who may
 * select it.
 *
 * <p>And every table, one row each, for the console's ice line (D45, backlog 127; {@link MaintenanceEstate}): anyone
 * signed in reads the tables they may select, never counting or naming another.
 */
@RestController
class MaintenanceRoutes {

    static final String WORK = ROOT + "/maintenance/work";
    static final String DELETIONS = CATALOG + "/objects/{id}/pending-deletions";
    static final String DELETIONS_DONE = DELETIONS + "/complete";
    static final String JOB = ROOT + "/maintenance/jobs/{id}";
    static final String NIGHTS = ROOT + "/maintenance/nights";
    static final String ESTATE = ROOT + "/maintenance/estate";

    private static final int PAGE = 100;
    private static final int MAX_FILES = 1000;
    /**
     * How long before a deletion was recorded a ref made counts as made since (D33, item 26's choice 14): a ref whose
     * create was under way while the worker read the table's refs, or dated by another server's clock, is still
     * checked against.
     */
    static final Duration RECHECK_MARGIN = Duration.ofHours(1);
    static final List<String> JOBS = List.of(MaintenancePolicy.EXPIRY_ENABLED, MaintenancePolicy.ORPHAN_ENABLED,
            MaintenancePolicy.COMPACTION_ENABLED, MaintenancePolicy.REWRITE_ENABLED);

    /** The work list's sections, in the order its pages walk them. */
    private enum Section { TABLES, PURGES, DELETIONS, REQUESTS, REFS }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final CatalogNames catalogs;
    private final RefSettings refSettings;
    private final StorageSettings storage;
    private final Clock clock;
    private final MaintenanceNights nights;
    private final MaintenanceEstate estate;

    MaintenanceRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                      CatalogNames catalogs, RefSettings refSettings, StorageSettings storage, Clock clock,
                      Authorizer authorizer) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.catalogs = catalogs;
        this.refSettings = refSettings;
        this.storage = storage;
        this.clock = clock;
        this.nights = new MaintenanceNights(store, management, authorizer, catalogs, clock);
        this.estate = new MaintenanceEstate(store, authorizer, catalogs, clock);
    }

    /**
     * What the maintenance worker has to do, page by page: first every active table whose resolved maintenance
     * policy (D30) runs a job, with the policy in force, which leaves out what a hold or a DR freeze stops, and the
     * refs whose cut names it, whose files cleanup keeps (D33, {@link #refs(List, ArrayNode)}); then
     * the purge queue (dropped objects past their window that no hold or freeze covers, and every claimed one);
     * then the pending deletions that are due, of active or dropped tables that no hold or freeze covers, each with
     * the refs made since it was recorded; then the
     * requested jobs waiting (backlog 114), by the time asked, each with its table as the tables section shows it,
     * of tables whose resolved policy runs the job still; then the branches past their expiry, to end, and those
     * within {@code nuna.refs.expiry-warning} of it that have not been warned of, to warn of ({@link #refs}). Each
     * page holds items of one section; the token walks on to the next section, unless {@code section}
     * ({@code tables}, {@code purges}, {@code deletions}, {@code requests} or {@code refs}) asks for that section
     * alone.
     */
    @GetMapping(path = WORK, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> work(@RequestParam(required = false) String section,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        guard.requireMaintenance(management.request(http), null, "listMaintenanceWork");
        Optional<Section> only;
        try {
            only = Optional.ofNullable(section).map(name -> Section.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("No work list section " + section
                    + "; there are tables, purges, deletions, requests and refs");
        }
        Section current = only.orElse(Section.TABLES);
        Optional<String> inner = Optional.empty();
        if (pageToken != null && !pageToken.isEmpty()) {
            try {
                List<String> parts = PageToken.decode(pageToken, 2);
                current = Section.valueOf(parts.get(0));
                inner = Optional.of(parts.get(1)).filter(token -> !token.isEmpty());
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            if (only.isPresent() && only.get() != current) {
                throw CatalogError.badRequest("The page token is not one of section " + section);
            }
        }
        PageRequest page = new PageRequest(inner, pageSize == null ? PAGE : Math.clamp(pageSize, 1, 1000));
        Instant now = clock.instant();
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode tables = body.putArray("tables");
        ArrayNode purges = body.putArray("purges");
        ArrayNode deletions = body.putArray("deletions");
        ArrayNode refs = body.putArray("refs");
        ArrayNode requests = body.putArray("requests");
        Optional<String> next;
        try {
            next = switch (current) {
                case TABLES -> {
                    Page<CatalogObject> listed = store.listActiveObjects(CatalogObject.Kind.TABLE, page);
                    listed.items().forEach(table -> due(table).ifPresent(tables::add));
                    yield listed.nextToken();
                }
                case PURGES -> {
                    Page<CatalogObject> listed = store.listPurgeEligible(now, page);
                    listed.items().forEach(o -> purges.add(LifecycleRoutes.object(catalogs.name(o.catalogId()), o)));
                    yield listed.nextToken();
                }
                case DELETIONS -> {
                    Page<PendingDeletion> listed = store.listDueDeletions(now, page);
                    Map<UUID, List<RefPointers>> named = new HashMap<>();
                    listed.items().forEach(deletion -> deletions.add(deletion(deletion, named)));
                    yield listed.nextToken();
                }
                case REQUESTS -> {
                    Page<MaintenanceRequest> listed = store.listWaitingRequests(page);
                    listed.items().forEach(waiting -> requested(waiting).ifPresent(requests::add));
                    yield listed.nextToken();
                }
                case REFS -> refs(page, now, refs);
            };
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
        Section listed = current;
        Optional<String> token = next.map(t -> PageToken.encode(listed.name(), t)).or(() ->
                only.isPresent() || listed == Section.REFS ? Optional.empty()
                        : Optional.of(PageToken.encode(Section.values()[listed.ordinal() + 1].name(), "")));
        token.ifPresent(t -> body.put("next-page-token", t));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * Records files of an active or dropped table that the worker no longer needs, to delete once the database's
     * PITR window has passed (D30). Body: {@code {"locations": [...], "refs": [...]}}: the locations each beneath the
     * table's location, at most 1,000; the refs the ids of the catalog refs whose metadata the worker checked them
     * against (D33, item 26's choice 14), empty when none named the table. Answers 201 with how many are now pending
     * and when the new ones may be deleted. A file already pending keeps its earlier time; when every one was, nothing
     * is written and the answer is 200. Answers 409 when a ref now naming the table is not among {@code refs}, made
     * since the worker read the table's refs, with {@code refs} the table's refs now, as the work list shows them: the
     * worker checks the files again against those.
     */
    @PostMapping(path = DELETIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> record(@PathVariable("catalog") String prefix, @PathVariable String id,
                                  @RequestBody(required = false) String body, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "recordPendingDeletions", null);
        CatalogObject object = existing(catalog, id);
        nuna.catalog.audit.Securable audit = audit(catalog, object);
        guard.requireMaintenance(request, audit, "recordPendingDeletions");
        String root = object.locationRoot().endsWith("/") ? object.locationRoot() : object.locationRoot() + "/";
        JsonNode json = Management.body(body);
        Set<String> locations = new LinkedHashSet<>();
        for (JsonNode location : list(json, "locations")) {
            if (!location.isString() || !beneath(location.asString(), root)) {
                throw CatalogError.badRequest("Every location must lie beneath the table's location " + root
                        + ", with no empty, . or .. segment, not " + location);
            }
            locations.add(location.asString());
        }
        JsonNode sent = json.get("refs");
        if (sent == null || !sent.isArray()) {
            throw CatalogError.badRequest("Field refs must be an array of the ids of the refs the files were checked"
                    + " against, empty when none named the table");
        }
        Set<UUID> checked = new HashSet<>();
        for (JsonNode ref : sent) {
            try {
                checked.add(UUID.fromString(ref.asString()));
            } catch (RuntimeException e) {
                throw CatalogError.badRequest("Not a ref id: " + ref);
            }
        }
        List<RefPointers> named = store.listRefPointers(object.id());
        if (!named.stream().allMatch(ref -> checked.contains(ref.ref().id()))) {
            ObjectNode refused = Management.JSON.createObjectNode();
            refused.putObject("error").put("message", "A ref naming " + id + " was made since its files were checked"
                    + " against its refs: check them against refs").put("type", "RefsChangedException")
                    .put("code", 409);
            refs(named, refused.putArray("refs"));
            return GovernanceRoutes.json(409, refused);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant notBefore = now.plus(settings.pitrWindow());
        WriteResult result = Management.write("Recording deletions of " + id, () -> store.recordDeletions(object.id(),
                        List.copyOf(locations), notBefore, management.change(now, new FileDeletion(
                                FileDeletion.Action.RECORDED, audit, locations.size(), notBefore, request.actor()))),
                () -> false);
        ObjectNode answer = Management.JSON.createObjectNode();
        answer.put("recorded", locations.size());
        answer.put("not-before", notBefore.toString());
        return switch (result) {
            case WriteResult.Applied applied -> GovernanceRoutes.json(201, answer);
            case WriteResult.Unchanged unchanged -> GovernanceRoutes.json(200, answer);
            case WriteResult.Missing missing -> throw notLive(id, store.readObject(object.id()).orElse(object));
            default -> throw new IllegalStateException("recordDeletions answered " + result);
        };
    }

    /**
     * Reports pending deletions of the object done, once the worker has deleted their files. Body:
     * {@code {"ids": [...]}}, at most 1,000. Ids already reported, or of another object, change nothing.
     */
    @PostMapping(path = DELETIONS_DONE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> complete(@PathVariable("catalog") String prefix, @PathVariable String id,
                                    @RequestBody(required = false) String body, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "completePendingDeletions", null);
        CatalogObject object = existing(catalog, id);
        nuna.catalog.audit.Securable audit = audit(catalog, object);
        guard.requireMaintenance(request, audit, "completePendingDeletions");
        List<UUID> ids = new ArrayList<>();
        for (JsonNode deletion : list(Management.body(body), "ids")) {
            try {
                ids.add(UUID.fromString(deletion.asString()));
            } catch (RuntimeException e) {
                throw CatalogError.badRequest("Not a pending deletion id: " + deletion);
            }
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        WriteResult result = Management.write("Completing deletions of " + id, () -> store.completeDeletions(object.id(),
                        ids, management.change(now, new FileDeletion(FileDeletion.Action.DELETED, audit, ids.size(), null,
                                request.actor()))),
                () -> false);
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            default -> throw new IllegalStateException("completeDeletions answered " + result);
        };
    }

    /**
     * One page of the refs section: one page of the refs of one declared catalog, the catalogs taken by name. Of each
     * branch it lists the one past its expiry, to end, and the one within {@code nuna.refs.expiry-warning} of it whose
     * warning was not sent ({@link RefRoutes#warned}), to warn of (D33, choice 11; the worker's side is item 108). Its
     * token names the catalog and the store's token in it.
     */
    private Optional<String> refs(PageRequest page, Instant now, ArrayNode items) {
        List<String> names = storage.catalogs().keySet().stream().sorted(Comparator.comparing(Names::normalize))
                .toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        String catalog = names.getFirst();
        Optional<String> within = Optional.empty();
        if (page.token().isPresent()) {
            List<String> parts = PageToken.decode(page.token().get(), 2);
            catalog = parts.get(0);
            within = Optional.of(parts.get(1)).filter(token -> !token.isEmpty());
            if (!names.contains(catalog)) {
                throw new IllegalArgumentException("no declared catalog " + catalog);
            }
        }
        Optional<String> next = Optional.empty();
        Optional<CatalogRecord> record = store.findCatalog(Names.normalize(catalog));
        if (record.isPresent()) {
            Page<Ref> listed = store.listRefs(record.get().id(), new PageRequest(within, page.size()));
            for (Ref ref : listed.items()) {
                String action = ref.expiredAt(now) ? "end" : refSettings.expiring(ref, now)
                        && !(store.readKey(RefRoutes.warned(ref.id(), ref.expiresAt().orElseThrow()), now)
                        instanceof KeyState.Done) ? "warn" : null;
                if (action != null) {
                    ObjectNode item = items.addObject();
                    item.put("id", ref.id().toString());
                    item.put("catalog", catalog);
                    item.put("name", ref.name().display());
                    item.put("expires-at", ref.expiresAt().orElseThrow().toString());
                    item.put("action", action);
                }
            }
            next = listed.nextToken();
        }
        String listedCatalog = catalog;
        int at = names.indexOf(catalog);
        return next.map(token -> PageToken.encode(listedCatalog, token)).or(() -> at + 1 < names.size()
                ? Optional.of(PageToken.encode(names.get(at + 1), "")) : Optional.empty());
    }

    /**
     * A job of the worker's night, as the worker reports it (docs/observability/maintenance-nights.md): {@code {"night",
     * "opens", "closes", "kind", "object-id", "slot", "started-at", "ended-at", "outcome", "files-in", "files-out",
     * "snapshots-expired", "files-deleted", "bytes-deleted"}}. The worker is the caller. A report repeated late, or a
     * running report of a job that has ended, changes nothing. Answers 204; 404 when the catalog never had the object.
     * The report is the worker's bookkeeping and writes no audit record; a refusal is audited.
     */
    @PutMapping(path = JOB)
    ResponseEntity<Void> reportJob(@PathVariable String id, @RequestBody(required = false) String body,
                                   HttpServletRequest http) {
        Request request = management.request(http);
        guard.requireMaintenance(request, null, "reportMaintenanceJob");
        UUID jobId;
        try {
            jobId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a job id: " + id);
        }
        JsonNode json = Management.body(body);
        MaintenanceJob job = MaintenanceNights.job(jobId, request.caller().principal().id(), json);
        Optional<Instant> asked = requestedAt(json);
        nights.record(job);
        asked.ifPresent(at -> started(job, at));
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears the request a job was started for (backlog 114): the report's {@code requested-at} names it, with the
     * job's kind and object. A request no longer waiting, or made at another time, is left as it is.
     */
    private void started(MaintenanceJob job, Instant requestedAt) {
        MaintenanceRequest.Job asked = switch (job.kind()) {
            case COMPACTION -> MaintenanceRequest.Job.COMPACTION;
            case ORPHAN_SCAN -> MaintenanceRequest.Job.ORPHAN_SCAN;
            default -> null;
        };
        if (asked != null) {
            store.startMaintenanceRequest(job.objectId(), asked, requestedAt, job.startedAt());
        }
    }

    /** The request a job report names, by the time it was asked; none in a report of the night's own work. */
    private static Optional<Instant> requestedAt(JsonNode json) {
        JsonNode value = json.get("requested-at");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.asString()).truncatedTo(ChronoUnit.MILLIS));
        } catch (RuntimeException e) {
            throw CatalogError.badRequest("Not a maintenance job report: requested-at is not an ISO-8601 instant: "
                    + value);
        }
    }

    /**
     * The worker's last nights, newest first, for anyone signed in: the newest {@code days} nights that have a job (30
     * by default, 1 to 32), each with its totals, and the first {@code jobs} of them (1 by default, 0 to {@code days})
     * with their compaction Jobs and orphan scans, by start. A job names its table only when the caller may select it.
     */
    @GetMapping(path = NIGHTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> nights(@RequestParam(required = false) Integer days,
                                  @RequestParam(required = false) Integer jobs, HttpServletRequest http) {
        Request request = management.request(http);
        int asked = days == null ? MaintenanceNights.DAYS : Math.clamp(days, 1, MaintenanceNights.KEEP_NIGHTS);
        int withJobs = jobs == null ? 1 : Math.clamp(jobs, 0, asked);
        return GovernanceRoutes.json(200, nights.nights(request.caller(), asked, withJobs));
    }

    /**
     * Every active table the caller may {@code select}, in every catalog, by id (docs/observability/table-health.md,
     * "Every table"): {@code {"night", "tables": [{"id", "catalog", "namespace", "name", "assessment": {"assessed-at",
     * "needs-compaction", "reasons", "score", "data-files"}, "held", "frozen", "closed", "fixed-last-night"}],
     * "next-page-token"}}. {@code night} is the newest night that has a job, the one {@code fixed-last-night} speaks
     * of; {@code assessment} is absent until the worker has assessed the table. A table closed until classified (D27)
     * counts for a caller whose grants give select on it, with {@code closed} true. Each page holds at most
     * {@code pageSize} rows (200 by default, 1 to 500), and fewer when the caller may select few of the tables it
     * looks at (2,000 at most); {@code next-page-token} is there while more may follow.
     */
    @GetMapping(path = ESTATE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> estate(@RequestParam(required = false) String pageToken,
                                  @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        Request request = management.request(http);
        int size = pageSize == null ? MaintenanceEstate.PAGE : Math.clamp(pageSize, 1, MaintenanceEstate.MAX_PAGE);
        try {
            return GovernanceRoutes.json(200, estate.tables(request.caller(),
                    Optional.ofNullable(pageToken).filter(token -> !token.isEmpty()), size));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + pageToken);
        }
    }

    /** The table as the work list shows it, when its resolved policy runs a job. */
    private Optional<ObjectNode> due(CatalogObject table) {
        Map<String, MaintenancePolicy.Setting> resolved = GovernanceRoutes.resolvePolicy(settings,
                GovernanceChain.of(store, table));
        if (JOBS.stream().noneMatch(job -> "true".equals(resolved.get(job).value()))) {
            return Optional.empty();
        }
        return work(table, resolved);
    }

    /**
     * A waiting request as the work list shows it: its table as the tables section shows it, with {@code job},
     * {@code requested-at} and {@code requested-by}. None when the table is gone, or when its resolved policy no
     * longer runs the job, a hold or freeze placed since included: the request waits until it does.
     */
    private Optional<ObjectNode> requested(MaintenanceRequest request) {
        Optional<CatalogObject> table = store.readObject(request.objectId())
                .filter(object -> object.kind() == CatalogObject.Kind.TABLE
                        && object.state() == CatalogObject.State.ACTIVE);
        if (table.isEmpty()) {
            return Optional.empty();
        }
        Map<String, MaintenancePolicy.Setting> resolved = GovernanceRoutes.resolvePolicy(settings,
                GovernanceChain.of(store, table.get()));
        if (!"true".equals(resolved.get(MaintenanceRequests.key(request.job())).value())) {
            return Optional.empty();
        }
        return work(table.get(), resolved).map(node -> {
            node.put("job", MaintenanceRequests.label(request.job()));
            node.put("requested-at", request.requestedAt().toString());
            node.put("requested-by", request.requestedBy());
            return node;
        });
    }

    /** The table and its resolved policy as the work list shows them; none when its namespace is gone. */
    private Optional<ObjectNode> work(CatalogObject table, Map<String, MaintenancePolicy.Setting> resolved) {
        Optional<List<String>> levels = management.levels(table.catalogId(), table.namespaceId());
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode node = LifecycleRoutes.object(catalogs.name(table.catalogId()), table);
        ArrayNode namespace = node.putArray("namespace");
        levels.get().forEach(namespace::add);
        ObjectNode policy = node.putObject("policy");
        resolved.forEach((key, setting) -> policy.put(key, setting.value()));
        refs(store.listRefPointers(table.id()), node.putArray("refs"));
        return Optional.of(node);
    }

    /**
     * Refs whose cut names a table, as the work list and a refused record show them (D33, item 26's choice 14):
     * {@code {"id", "kind", "metadata-locations"}}, the metadata each reaches for the table, a tag's cut, a branch's
     * cut and its own head once it has one. Every ref the store holds counts, a branch past its expiry until it is
     * ended.
     */
    private static void refs(List<RefPointers> named, ArrayNode items) {
        for (RefPointers ref : named) {
            ObjectNode item = items.addObject();
            item.put("id", ref.ref().id().toString());
            item.put("kind", ref.ref().kind().name().toLowerCase(Locale.ROOT));
            ArrayNode locations = item.putArray("metadata-locations");
            locations.add(ref.cut().metadataLocation());
            ref.head().ifPresent(head -> locations.add(head.metadataLocation()));
        }
    }

    /**
     * A due deletion as the work list shows it. With {@code refs}, the refs naming its table that were made since it
     * was recorded, less {@link #RECHECK_MARGIN} (D33, item 26's choice 14), which the worker checks it against before
     * deleting it; and when there are some and the table is active, its {@code namespace} and {@code name} now, to load
     * it by. {@code named} holds each object's refs, read once a page.
     */
    private ObjectNode deletion(PendingDeletion deletion, Map<UUID, List<RefPointers>> named) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", deletion.id().toString());
        node.put("object-id", deletion.objectId().toString());
        String catalog = catalogs.name(deletion.catalogId());
        if (catalog != null) {
            node.put("catalog", catalog);
        }
        node.put("location", deletion.location());
        node.put("not-before", deletion.notBefore().toString());
        Instant since = deletion.recordedAt().minus(RECHECK_MARGIN);
        List<RefPointers> later = named.computeIfAbsent(deletion.objectId(), store::listRefPointers).stream()
                .filter(ref -> ref.ref().createdAt().isAfter(since)).toList();
        refs(later, node.putArray("refs"));
        if (!later.isEmpty()) {
            store.readObject(deletion.objectId()).filter(object -> object.state() == CatalogObject.State.ACTIVE)
                    .ifPresent(table -> management.levels(table.catalogId(), table.namespaceId()).ifPresent(levels -> {
                        ArrayNode namespace = node.putArray("namespace");
                        levels.forEach(namespace::add);
                        node.put("name", table.name().display());
                    }));
        }
        return node;
    }

    private CatalogObject existing(CatalogRecord catalog, String id) {
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an object id: " + id);
        }
        return store.readObject(objectId).filter(o -> o.catalogId().equals(catalog.id())).orElseThrow(
                () -> CatalogError.of(404, "NoSuchObjectException", "No table or view " + id + " in " + catalog.name()));
    }

    private nuna.catalog.audit.Securable audit(CatalogRecord catalog, CatalogObject object) {
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        return Management.audit(catalog, levels, object.name().display(), object);
    }

    /**
     * Whether {@code location} lies beneath {@code root} (which ends in {@code /}) as written: a writer chooses its
     * data files' paths, and a store or proxy that resolved a {@code ..} would delete another table's file, so no
     * segment after the root may be empty, {@code .} or {@code ..}.
     */
    static boolean beneath(String location, String root) {
        if (!location.startsWith(root) || location.length() == root.length()) {
            return false;
        }
        for (String segment : location.substring(root.length()).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** A required array field of 1 to 1,000 items. */
    private static List<JsonNode> list(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > MAX_FILES) {
            throw CatalogError.badRequest("Field " + field + " must be an array of 1 to " + MAX_FILES + " items");
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    private static CatalogError notLive(String id, CatalogObject object) {
        return CatalogError.of(409, "NotDeletableException", id + " is " + object.state().name().toLowerCase(Locale.ROOT)
                + ": only an active or dropped table's files are recorded for deletion");
    }
}
