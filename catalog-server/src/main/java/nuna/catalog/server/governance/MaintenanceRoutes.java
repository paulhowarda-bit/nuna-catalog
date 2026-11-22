package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.audit.FileDeletion;
import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Authorizer;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * still holds never names a deleted file. Each route asks {@link ManagementGuard} before any other work.
 *
 * <p>Also the worker's nights (D45, backlog 126; {@link MaintenanceNights}): the worker reports each job it runs, and
 * anyone signed in reads each night's totals and the jobs on its slots, a job's table named only to a caller who may
 * select it.
 */
@RestController
class MaintenanceRoutes {

    static final String WORK = ROOT + "/maintenance/work";
    static final String DELETIONS = CATALOG + "/objects/{id}/pending-deletions";
    static final String DELETIONS_DONE = DELETIONS + "/complete";
    static final String JOB = ROOT + "/maintenance/jobs/{id}";
    static final String NIGHTS = ROOT + "/maintenance/nights";

    private static final int PAGE = 100;
    private static final int MAX_FILES = 1000;
    private static final List<String> JOBS = List.of(MaintenancePolicy.EXPIRY_ENABLED, MaintenancePolicy.ORPHAN_ENABLED,
            MaintenancePolicy.COMPACTION_ENABLED, MaintenancePolicy.REWRITE_ENABLED);

    /** The work list's sections, in the order its pages walk them. */
    private enum Section { TABLES, PURGES, DELETIONS }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final CatalogNames catalogs;
    private final Clock clock;
    private final MaintenanceNights nights;

    MaintenanceRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                      CatalogNames catalogs, Clock clock, Authorizer authorizer) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.catalogs = catalogs;
        this.clock = clock;
        this.nights = new MaintenanceNights(store, management, authorizer, catalogs, clock);
    }

    /**
     * What the maintenance worker has to do, page by page: first every active table whose resolved maintenance
     * policy (D30) runs a job, with the policy in force, which leaves out what a hold or a DR freeze stops; then
     * the purge queue (dropped objects past their window that no hold or freeze covers, and every claimed one);
     * then the pending deletions that are due, of active or dropped tables that no hold or freeze covers. Each
     * page holds items of one section; the token walks on to the next section, unless {@code section}
     * ({@code tables}, {@code purges} or {@code deletions}) asks for that section alone.
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
            throw CatalogError.badRequest("No work list section " + section + "; there are tables, purges and deletions");
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
                    listed.items().forEach(deletion -> deletions.add(deletion(deletion)));
                    yield listed.nextToken();
                }
            };
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
        Section listed = current;
        Optional<String> token = next.map(t -> PageToken.encode(listed.name(), t)).or(() ->
                only.isPresent() || listed == Section.DELETIONS ? Optional.empty()
                        : Optional.of(PageToken.encode(Section.values()[listed.ordinal() + 1].name(), "")));
        token.ifPresent(t -> body.put("next-page-token", t));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * Records files of an active or dropped table that the worker no longer needs, to delete once the database's
     * PITR window has passed (D30). Body: {@code {"locations": [...]}}, each beneath the table's location, at most
     * 1,000. Answers 201 with how many are now pending and when the new ones may be deleted. A file already pending
     * keeps its earlier time; when every one was, nothing is written and the answer is 200.
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
        Set<String> locations = new LinkedHashSet<>();
        for (JsonNode location : list(Management.body(body), "locations")) {
            if (!location.isString() || !beneath(location.asString(), root)) {
                throw CatalogError.badRequest("Every location must lie beneath the table's location " + root
                        + ", with no empty, . or .. segment, not " + location);
            }
            locations.add(location.asString());
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
        nights.record(MaintenanceNights.job(jobId, request.caller().principal().id(), Management.body(body)));
        return ResponseEntity.noContent().build();
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

    /** The table as the work list shows it, when its resolved policy runs a job. */
    private Optional<ObjectNode> due(CatalogObject table) {
        Map<String, MaintenancePolicy.Setting> resolved = GovernanceRoutes.resolvePolicy(settings,
                GovernanceChain.of(store, table));
        if (JOBS.stream().noneMatch(job -> "true".equals(resolved.get(job).value()))) {
            return Optional.empty();
        }
        Optional<List<String>> levels = management.levels(table.catalogId(), table.namespaceId());
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode node = LifecycleRoutes.object(catalogs.name(table.catalogId()), table);
        ArrayNode namespace = node.putArray("namespace");
        levels.get().forEach(namespace::add);
        ObjectNode policy = node.putObject("policy");
        resolved.forEach((key, setting) -> policy.put(key, setting.value()));
        return Optional.of(node);
    }

    private ObjectNode deletion(PendingDeletion deletion) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", deletion.id().toString());
        node.put("object-id", deletion.objectId().toString());
        String catalog = catalogs.name(deletion.catalogId());
        if (catalog != null) {
            node.put("catalog", catalog);
        }
        node.put("location", deletion.location());
        node.put("not-before", deletion.notBefore().toString());
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
