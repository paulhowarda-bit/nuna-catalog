package keel.catalog.server.governance;

import static keel.catalog.server.governance.Management.CATALOG;
import static keel.catalog.server.governance.Management.ROOT;

import keel.catalog.audit.FileDeletion;
import keel.catalog.core.CatalogError;
import keel.catalog.server.governance.Management.Request;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PendingDeletion;
import keel.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import keel.catalog.core.commit.GovernanceChain;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 */
@RestController
class MaintenanceRoutes {

    static final String WORK = ROOT + "/maintenance/work";
    static final String DELETIONS = CATALOG + "/objects/{id}/pending-deletions";
    static final String DELETIONS_DONE = DELETIONS + "/complete";

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

    MaintenanceRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                      CatalogNames catalogs, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.catalogs = catalogs;
        this.clock = clock;
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
     * 1,000. Answers when they may be deleted.
     */
    @PostMapping(path = DELETIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> record(@PathVariable("catalog") String prefix, @PathVariable String id,
                                  @RequestBody(required = false) String body, HttpServletRequest http) {
        CatalogRecord catalog = management.catalog(prefix);
        CatalogObject object = existing(catalog, id);
        Request request = management.request(http);
        keel.catalog.audit.Securable audit = audit(catalog, object);
        guard.requireMaintenance(request, audit, "recordPendingDeletions");
        String root = object.locationRoot().endsWith("/") ? object.locationRoot() : object.locationRoot() + "/";
        List<String> locations = new ArrayList<>();
        for (JsonNode location : list(Management.body(body), "locations")) {
            if (!location.isString() || !location.asString().startsWith(root)) {
                throw CatalogError.badRequest("Every location must lie beneath the table's location " + root
                        + ", not " + location);
            }
            locations.add(location.asString());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant notBefore = now.plus(settings.pitrWindow());
        WriteResult result = Management.write("Recording deletions of " + id, () -> store.recordDeletions(object.id(),
                        locations, notBefore, management.change(now, new FileDeletion(FileDeletion.Action.RECORDED, audit,
                                locations.size(), notBefore, request.actor()))),
                () -> false);
        return switch (result) {
            case WriteResult.Applied applied -> {
                ObjectNode answer = Management.JSON.createObjectNode();
                answer.put("recorded", locations.size());
                answer.put("not-before", notBefore.toString());
                yield GovernanceRoutes.json(201, answer);
            }
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
        CatalogRecord catalog = management.catalog(prefix);
        CatalogObject object = existing(catalog, id);
        Request request = management.request(http);
        keel.catalog.audit.Securable audit = audit(catalog, object);
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

    private keel.catalog.audit.Securable audit(CatalogRecord catalog, CatalogObject object) {
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        return Management.audit(catalog, levels, object.name().display(), object);
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
