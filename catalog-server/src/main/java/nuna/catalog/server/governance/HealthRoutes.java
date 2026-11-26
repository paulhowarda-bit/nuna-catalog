package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.ROOT;
import static nuna.catalog.server.governance.Management.TABLE;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.WorkerStorage;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Table health and the maintenance worker's scans, for the console's health pages (D38;
 * docs/observability/table-health.md). The worker, a maintenance principal acting as itself, reports each scan's
 * start and end, each table's assessment as it makes it, and each compaction's start and end; the catalog keeps the
 * latest of each, and the recent scans. A report names the table and its id, and is refused when the table no longer
 * exists or the name is now another table's. The reports are the worker's own bookkeeping, not changes to the
 * catalog, and write no audit record; a refusal is audited, as on every management route.
 *
 * <p>Anyone reads the neediest tables, those they may {@code select} only, and one table's health with
 * {@code select} on it; platform operators read the scans.
 */
@RestController
class HealthRoutes {

    static final String SCANS = ROOT + "/maintenance/scans";
    static final String SCAN = SCANS + "/{id}";
    static final String TABLES = ROOT + "/maintenance/tables";
    static final String ASSESSMENT = TABLES + "/{id}/assessment";
    static final String COMPACTION = TABLES + "/{id}/compaction";
    static final String HEALTH = TABLE + "/health";

    /** Scans kept besides the last that succeeded: more than a day's at the worker's default interval. */
    static final int KEEP_SCANS = 50;
    private static final int PAGE = 50;
    private static final int MAX_PAGE = 200;
    /** Rows read from the store at a time while the caller's grants filter them. */
    private static final int READ = 100;
    /** Rows one page looks at, at most; a page that finds fewer tables the caller may read ends early, with a token. */
    private static final int MAX_EXAMINED = 2000;
    private static final int MAX_NAME = 1000;
    private static final Authorizer.Operation LIST = new Authorizer.Operation("listNeediestTables", Privilege.SELECT,
            false);

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final Authorizer authorizer;
    private final CatalogNames catalogs;

    HealthRoutes(CatalogStore store, Management management, ManagementGuard guard, Authorizer authorizer,
                 CatalogNames catalogs) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.authorizer = authorizer;
        this.catalogs = catalogs;
    }

    // ---- the worker's reports ----

    /**
     * A scan's start, or its end: {@code {"started-at": ..., "ended-at": ..., "outcome": "succeeded"|"failed",
     * "failures": {"kind": n, ...}, "error": ..., "storage": [...]}}, all but {@code started-at} and {@code storage}
     * only once it has ended. {@code storage} is what the worker's own storage identities reach (backlog 250): for each,
     * its {@code kind} and {@code endpoint}, and its {@code prefixes}, each with {@code listed} ({@code ok},
     * {@code refused} or {@code failed}) and its {@code list-code}, and the {@code deleted}, {@code delete-refused}
     * and {@code delete-failed} counts with the first such delete's {@code delete-code}. The worker is the caller, and
     * the catalog keeps the groups its token carries with the scan. A scan that has ended keeps its end. Answers 204.
     */
    @PutMapping(path = SCAN)
    ResponseEntity<Void> reportScan(@PathVariable String id, @RequestBody(required = false) String body,
                                    HttpServletRequest http) {
        Request request = management.request(http);
        guard.requireMaintenance(request, null, "reportMaintenanceScan");
        UUID scanId = uuid(id, "scan");
        JsonNode json = Management.body(body);
        MaintenanceScan scan;
        try {
            scan = new MaintenanceScan(scanId, request.caller().principal().id(), instant(json, "started-at"),
                    optionalInstant(json, "ended-at"), optionalText(json, "outcome").map(outcome ->
                            MaintenanceScan.Outcome.valueOf(constant(outcome))), failures(json),
                    optionalText(json, "error"), Optional.of(new TreeSet<>(request.caller().principal().groups())),
                    storage(json));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a scan report: " + e.getMessage());
        }
        store.recordScan(scan, KEEP_SCANS);
        return ResponseEntity.noContent().build();
    }

    /**
     * A table's assessment, as the worker makes it: {@code {"catalog", "namespace": [...], "name", "assessed-at",
     * "data-files", "small-files", "position-delete-files", "deletion-vectors", "equality-delete-files",
     * "reasons": [...], "score"}}; the table needs compaction when it has a reason. An assessment older than the one
     * kept changes nothing. Answers 204; 404 when the table no longer exists, 409 when the name is now another table.
     */
    @PutMapping(path = ASSESSMENT)
    ResponseEntity<Void> reportAssessment(@PathVariable String id, @RequestBody(required = false) String body,
                                          HttpServletRequest http) {
        Request request = management.request(http);
        guard.requireMaintenance(request, audit(id), "reportTableAssessment");
        JsonNode json = Management.body(body);
        CatalogObject table = reported(uuid(id, "table"), json, request, "reportTableAssessment");
        TableAssessment assessment;
        try {
            assessment = new TableAssessment(instant(json, "assessed-at"), count(json, "data-files"),
                    count(json, "small-files"), count(json, "position-delete-files"), count(json, "deletion-vectors"),
                    count(json, "equality-delete-files"), reasons(json), score(json));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an assessment: " + e.getMessage());
        }
        return answer(store.recordAssessment(table.id(), assessment), table);
    }

    /**
     * How a table's compaction stands: {@code {"catalog", "namespace": [...], "name", "started-at", "ended-at",
     * "outcome": "running"|"succeeded"|"failed"|"cut-off"}}, {@code ended-at} once it has ended. A compaction that
     * started before the one kept changes nothing, nor does one reported running once it has ended. Answers 204; 404
     * when the table no longer exists, 409 when the name is now another table.
     */
    @PutMapping(path = COMPACTION)
    ResponseEntity<Void> reportCompaction(@PathVariable String id, @RequestBody(required = false) String body,
                                          HttpServletRequest http) {
        Request request = management.request(http);
        guard.requireMaintenance(request, audit(id), "reportTableCompaction");
        JsonNode json = Management.body(body);
        CatalogObject table = reported(uuid(id, "table"), json, request, "reportTableCompaction");
        CompactionRun compaction;
        try {
            compaction = new CompactionRun(instant(json, "started-at"), optionalInstant(json, "ended-at"),
                    CompactionRun.Outcome.valueOf(constant(Management.text(json, "outcome", 20))));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a compaction report: " + e.getMessage());
        }
        return answer(store.recordCompaction(table.id(), compaction), table);
    }

    // ---- reads ----

    /**
     * The neediest tables the caller may {@code select}, neediest first: tables with equality deletes, then the
     * highest score. Each page holds at most {@code pageSize} (default 50, at most 200); a page may hold fewer when
     * the caller may read few of them, and {@code next-page-token} is there while more may follow.
     */
    @GetMapping(path = TABLES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> neediest(@RequestParam(required = false) String pageToken,
                                    @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        Request request = management.request(http);
        int size = pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE);
        Optional<String> cursor = Optional.ofNullable(pageToken).filter(token -> !token.isEmpty());
        Map<UUID, Optional<List<String>>> levels = new HashMap<>();
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode tables = body.putArray("tables");
        int examined = 0;
        read:
        while (true) {
            Page<TableHealth> page;
            try {
                page = store.listNeediestTables(new PageRequest(cursor, READ));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            List<TableHealth> items = page.items();
            List<Decision> decisions = items.isEmpty() ? List.of() : authorizer.decide(request.caller(),
                    items.stream().map(health -> new Authorizer.Request(
                            new Authorizer.Target.TableOrView(health.table()), LIST)).toList());
            for (int i = 0; i < items.size(); i++) {
                examined++;
                if (decisions.get(i).allowed()) {
                    named(items.get(i), levels).ifPresent(tables::add);
                }
                if (tables.size() == size || examined == MAX_EXAMINED) {
                    if (i < items.size() - 1 || page.nextToken().isPresent()) {
                        body.put("next-page-token", items.get(i).rank().token());
                    }
                    break read;
                }
            }
            if (page.nextToken().isEmpty()) {
                break;
            }
            cursor = page.nextToken();
        }
        return GovernanceRoutes.json(200, body);
    }

    /**
     * One table's health, with {@code select} on it: its latest assessment and last compaction, when there are, and
     * its requested jobs (backlog 114), each waiting or started, with who asked and when.
     */
    @GetMapping(path = HEALTH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> table(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                 @PathVariable String table, HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "loadTableHealth", Privilege.SELECT, prefix, namespace, table, null);
        guard.require(request, target, "loadTableHealth", Privilege.SELECT);
        CatalogObject object = target.object().orElseThrow();
        TableHealth health = store.readTableHealth(object.id())
                .orElse(new TableHealth(object, Optional.empty(), Optional.empty()));
        List<String> levels = management.levels(object.catalogId(), object.namespaceId()).orElse(List.of());
        ObjectNode body = health(target.catalog().name(), levels, health);
        body.set("requests", MaintenanceRequests.nodes(store.listMaintenanceRequests(object.id())));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * The worker's last scans, the last started first, and the last that succeeded, each with the groups its token
     * carried and the storage it reached (backlog 250); for platform operators.
     */
    @GetMapping(path = SCANS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> scans(HttpServletRequest http) {
        guard.requireOperator(management.request(http), null, "listMaintenanceScans");
        List<MaintenanceScan> kept = store.listScans();
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode scans = body.putArray("scans");
        kept.forEach(scan -> scans.add(scan(scan)));
        kept.stream().filter(scan -> scan.outcome().equals(Optional.of(MaintenanceScan.Outcome.SUCCEEDED)))
                .findFirst().ifPresent(scan -> body.set("last-success", scan(scan)));
        return GovernanceRoutes.json(200, body);
    }

    // ---- helpers ----

    /**
     * The active table a report names, by id: refused when there is none (404), or when its catalog, namespace and
     * name now name another table (409) or nothing (404). Its catalog is found as every route finds one, for
     * {@code operation}.
     */
    private CatalogObject reported(UUID id, JsonNode json, Request request, String operation) {
        String catalogName = Management.text(json, "catalog", MAX_NAME);
        List<String> levels = levels(json);
        String name = Management.text(json, "name", MAX_NAME);
        String qualified = String.join(".", levels) + "." + name;
        CatalogObject table = store.readObject(id).filter(found -> found.kind() == CatalogObject.Kind.TABLE
                && found.state() == CatalogObject.State.ACTIVE).orElseThrow(() -> CatalogError.of(404,
                "NoSuchTableException", "Table " + id + " no longer exists"));
        CatalogRecord catalog = management.catalog(catalogName, request, operation, null);
        Optional<CatalogObject> named = store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList())
                .flatMap(namespace -> store.findObject(namespace.id(), Names.normalize(name)))
                .filter(found -> found.kind() == CatalogObject.Kind.TABLE);
        if (named.isEmpty()) {
            throw CatalogError.of(404, "NoSuchTableException", "Table does not exist: " + qualified);
        }
        if (!named.get().id().equals(table.id())) {
            throw CatalogError.of(409, "TableReplacedException", qualified + " is now another table, "
                    + named.get().id() + ", not " + id);
        }
        return table;
    }

    private static ResponseEntity<Void> answer(WriteResult result, CatalogObject table) {
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NoSuchTableException",
                    "Table " + table.id() + " no longer exists");
            default -> throw new IllegalStateException("a health report answered " + result);
        };
    }

    /** The table a report's path names, as a refusal's audit record names it; null when there is none. */
    private nuna.catalog.audit.Securable audit(String id) {
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return store.readObject(objectId).map(object -> {
            List<String> path = new ArrayList<>(management.levels(object.catalogId(), object.namespaceId())
                    .orElse(List.of()));
            path.add(object.name().display());
            return new nuna.catalog.audit.Securable(object.kind() == CatalogObject.Kind.TABLE
                    ? nuna.catalog.audit.Securable.Kind.TABLE : nuna.catalog.audit.Securable.Kind.VIEW,
                    object.id(), catalogs.name(object.catalogId()), path);
        }).orElse(null);
    }

    /** A listed table's health, named; none when its catalog or namespace is gone. */
    private Optional<ObjectNode> named(TableHealth health, Map<UUID, Optional<List<String>>> levels) {
        CatalogObject table = health.table();
        String catalog = catalogs.name(table.catalogId());
        Optional<List<String>> namespace = levels.computeIfAbsent(table.namespaceId(),
                id -> management.levels(table.catalogId(), id));
        if (catalog == null || namespace.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(health(catalog, namespace.get(), health));
    }

    /** A table's health as the read routes show it (docs/observability/table-health.md). */
    static ObjectNode health(String catalog, List<String> namespace, TableHealth health) {
        CatalogObject table = health.table();
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", table.id().toString());
        node.put("catalog", catalog);
        ArrayNode levels = node.putArray("namespace");
        namespace.forEach(levels::add);
        node.put("name", table.name().display());
        health.assessment().ifPresent(assessment -> {
            ObjectNode assessed = node.putObject("assessment");
            assessed.put("assessed-at", assessment.assessedAt().toString());
            assessed.put("needs-compaction", assessment.needsCompaction());
            ArrayNode reasons = assessed.putArray("reasons");
            assessment.reasons().stream().sorted().forEach(reasons::add);
            assessed.put("score", assessment.score());
            assessed.put("data-files", assessment.dataFiles());
            assessed.put("small-files", assessment.smallFiles());
            assessed.put("small-file-share", assessment.dataFiles() == 0 ? 0
                    : Math.round(10_000.0 * assessment.smallFiles() / assessment.dataFiles()) / 10_000.0);
            assessed.put("delete-files", assessment.deleteFiles());
            assessed.put("position-delete-files", assessment.positionDeleteFiles());
            assessed.put("deletion-vectors", assessment.deletionVectors());
            assessed.put("equality-delete-files", assessment.equalityDeleteFiles());
        });
        health.lastCompaction().ifPresent(compaction -> {
            ObjectNode last = node.putObject("last-compaction");
            last.put("started-at", compaction.startedAt().toString());
            compaction.endedAt().ifPresent(at -> last.put("ended-at", at.toString()));
            last.put("outcome", label(compaction.outcome()));
        });
        return node;
    }

    private static ObjectNode scan(MaintenanceScan scan) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", scan.id().toString());
        node.put("worker", scan.worker());
        node.put("started-at", scan.startedAt().toString());
        scan.endedAt().ifPresent(at -> node.put("ended-at", at.toString()));
        scan.outcome().ifPresent(outcome -> node.put("outcome", label(outcome)));
        ObjectNode failures = node.putObject("failures");
        scan.failures().forEach(failures::put);
        scan.error().ifPresent(error -> node.put("error", error));
        scan.groups().ifPresent(groups -> {
            ArrayNode names = node.putArray("groups");
            groups.forEach(names::add);
        });
        scan.storage().ifPresent(stores -> {
            ArrayNode storage = node.putArray("storage");
            stores.forEach(store -> {
                ObjectNode identity = storage.addObject();
                identity.put("kind", store.kind());
                store.endpoint().ifPresent(endpoint -> identity.put("endpoint", endpoint));
                ArrayNode prefixes = identity.putArray("prefixes");
                store.prefixes().forEach(prefix -> {
                    ObjectNode covered = prefixes.addObject();
                    covered.put("prefix", prefix.location());
                    covered.put("listed", label(prefix.listed()));
                    prefix.listCode().ifPresent(code -> covered.put("list-code", code));
                    covered.put("deleted", prefix.deleted());
                    covered.put("delete-refused", prefix.deleteRefused());
                    covered.put("delete-failed", prefix.deleteFailed());
                    prefix.deleteCode().ifPresent(code -> covered.put("delete-code", code));
                });
            });
        });
        return node;
    }

    /**
     * What the worker's storage identities reach, as a scan report gives it (backlog 250); empty when it gives none, as
     * a scan that could not read its storage prefixes (item 275) and a worker of an earlier release do. Counts it
     * leaves out are 0.
     */
    private static Optional<List<WorkerStorage>> storage(JsonNode json) {
        JsonNode value = json.get("storage");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("storage is not an array");
        }
        List<WorkerStorage> stores = new ArrayList<>();
        for (JsonNode store : value) {
            JsonNode prefixes = store.get("prefixes");
            if (!store.isObject() || prefixes == null || !prefixes.isArray()) {
                throw new IllegalArgumentException("a store is an object with an array of prefixes: " + store);
            }
            List<WorkerStorage.Prefix> covered = new ArrayList<>();
            for (JsonNode prefix : prefixes) {
                if (!prefix.isObject()) {
                    throw new IllegalArgumentException("a prefix is an object: " + prefix);
                }
                covered.add(new WorkerStorage.Prefix(requiredText(prefix, "prefix"),
                        WorkerStorage.Listed.valueOf(constant(requiredText(prefix, "listed"))),
                        optionalText(prefix, "list-code"), countOrNone(prefix, "deleted"),
                        countOrNone(prefix, "delete-refused"), countOrNone(prefix, "delete-failed"),
                        optionalText(prefix, "delete-code")));
            }
            stores.add(new WorkerStorage(requiredText(store, "kind"), optionalText(store, "endpoint"), covered));
        }
        return Optional.of(stores);
    }

    private static String requiredText(JsonNode json, String field) {
        return optionalText(json, field).orElseThrow(() -> new IllegalArgumentException("missing " + field));
    }

    private static long countOrNone(JsonNode json, String field) {
        return json.has(field) ? count(json, field) : 0;
    }

    /** An enum constant as the routes write it: {@code CUT_OFF} is {@code cut-off}. */
    private static String label(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The constant a label names: {@code cut-off} is {@code CUT_OFF}. */
    private static String constant(String label) {
        return label.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static UUID uuid(String id, String what) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a " + what + " id: " + id);
        }
    }

    /** A required time, kept to the millisecond as every store keeps it. */
    private static Instant instant(JsonNode json, String field) {
        return optionalInstant(json, field).orElseThrow(() -> new IllegalArgumentException("missing " + field));
    }

    private static Optional<Instant> optionalInstant(JsonNode json, String field) {
        return optionalText(json, field).map(text -> {
            try {
                return Instant.parse(text).truncatedTo(ChronoUnit.MILLIS);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " is not an ISO-8601 instant: " + text);
            }
        });
    }

    private static Optional<String> optionalText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " is not a string");
        }
        return Optional.of(value.asString());
    }

    private static long count(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " is not a whole number");
        }
        return value.longValue();
    }

    private static double score(JsonNode json) {
        JsonNode value = json.get("score");
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("score is not a number");
        }
        return value.doubleValue();
    }

    private static Set<String> reasons(JsonNode json) {
        JsonNode value = json.get("reasons");
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("reasons is not an array");
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (JsonNode reason : value) {
            if (!reason.isString()) {
                throw new IllegalArgumentException("a reason is not a string: " + reason);
            }
            reasons.add(reason.asString());
        }
        return reasons;
    }

    private static Map<String, Integer> failures(JsonNode json) {
        JsonNode value = json.get("failures");
        Map<String, Integer> failures = new LinkedHashMap<>();
        if (value == null || value.isNull()) {
            return failures;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("failures is not an object");
        }
        for (Map.Entry<String, JsonNode> failure : value.properties()) {
            if (!failure.getValue().isIntegralNumber() || !failure.getValue().canConvertToInt()) {
                throw new IllegalArgumentException("failures of " + failure.getKey() + " is not a whole number");
            }
            failures.put(failure.getKey(), failure.getValue().intValue());
        }
        return failures;
    }

    private static List<String> levels(JsonNode json) {
        JsonNode value = json.get("namespace");
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw CatalogError.badRequest("Missing field namespace: an array of its levels");
        }
        List<String> levels = new ArrayList<>();
        for (JsonNode level : value) {
            if (!level.isString() || level.asString().isEmpty() || level.asString().length() > MAX_NAME) {
                throw CatalogError.badRequest("A namespace level is a non-empty string: " + level);
            }
            levels.add(level.asString());
        }
        return levels;
    }
}
