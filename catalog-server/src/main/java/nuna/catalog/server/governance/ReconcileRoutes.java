package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;

import nuna.catalog.audit.archive.ArchivedCommits;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.core.commit.Reconcile;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.rest.AuditEvents;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reconcile-from-storage step of the DR runbook (D37), for platform operators: every active table of a
 * catalog is walked forward along its metadata-log chain ({@link Reconcile}). Body, optional:
 * {@code {"apply": false, "accept": ["<metadata location>", ...], "accept-archived": false}}. Without {@code apply}
 * it only reports; with it, tables under a DR freeze move through their proven files and any tip named in
 * {@code accept}. With {@code accept-archived}, the audit archive is read whole first, and a tip whose swap it records
 * is taken too; an {@code accept} entry it contradicts stops its table and is reported (backlog 10).
 * The answer lists the tables with something to report, and table locations in storage that no table or
 * view of any catalog holds (written after the database's last change; the operator registers them by name).
 */
@RestController
class ReconcileRoutes {

    static final String RECONCILE = CATALOG + "/dr-reconcile";

    private static final int PAGE = 200;

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final CatalogStorage storage;
    private final AuditEvents audit;
    private final AuditRuntime runtime;
    private final Clock clock;

    ReconcileRoutes(CatalogStore store, Management management, ManagementGuard guard, CatalogStorage storage,
                    AuditEvents audit, AuditRuntime runtime, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.storage = storage;
        this.audit = audit;
        this.runtime = runtime;
        this.clock = clock;
    }

    @PostMapping(path = RECONCILE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> reconcile(@PathVariable("catalog") String prefix, @RequestBody(required = false) String body,
                                     HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "reconcileFromStorage", null);
        guard.requireOperator(request, new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG,
                catalog.id(), catalog.name(), List.of()), "reconcileFromStorage");
        JsonNode json = Management.body(body);
        boolean apply = json.has("apply") && json.get("apply").asBoolean();
        Set<String> accept = new HashSet<>();
        if (json.has("accept")) {
            json.get("accept").forEach(location -> accept.add(location.asString()));
        }
        boolean fromArchive = json.has("accept-archived") && json.get("accept-archived").asBoolean();
        // Read before any table is walked, so an archive that cannot be read fails the request with nothing applied.
        Optional<ArchivedCommits.Read> archived = fromArchive ? Optional.of(archived(runtime.archive())) : Optional.empty();
        TableFiles files = storage.forCatalog(catalog.id()).files();
        // The bounds the server's commit engines take (Engines): a step whose outcome was lost is decided as a commit.
        Reconcile reconcile = new Reconcile(store, files, CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);

        ObjectNode answer = Management.JSON.createObjectNode();
        answer.put("apply", apply);
        answer.put("accept-archived", fromArchive);
        archived.ifPresent(read -> {
            ObjectNode archive = answer.putObject("archive");
            archive.put("chains", read.chains());
            archive.put("commit-records", read.records());
        });
        ArrayNode reported = answer.putArray("tables");
        Set<String> used = new HashSet<>();
        Set<String> contradicted = new TreeSet<>();
        int[] checked = {0};
        forEachTable(catalog.id(), new ArrayList<>(), (table, levels) -> {
            checked[0]++;
            Reconcile.Plan plan = reconcile.plan(table, accept,
                    archived.map(read -> read.commits().getOrDefault(table.id(), List.of())));
            plan.steps().forEach(step -> used.add(step.location()));
            contradicted.addAll(plan.contradicted());
            Reconcile.Applied applied = null;
            if (apply && !plan.steps().isEmpty()) {
                List<String> path = new ArrayList<>(levels);
                path.add(table.name().display());
                applied = reconcile.apply(plan, request.caller(), audit.commits(catalog.name(), path, request.actor()));
            }
            if (plan.steps().isEmpty() && plan.tips().isEmpty() && plan.problem().isEmpty()) {
                return;
            }
            reported.add(table(plan, levels, applied));
        });
        answer.put("tables-checked", checked[0]);
        ArrayNode unknown = answer.putArray("unknown-locations");
        for (String location : files.tableLocations()) {
            if (!known(location)) {
                unknown.add(location);
            }
        }
        ArrayNode unused = answer.putArray("unused-accepts");
        accept.stream().filter(location -> !used.contains(location)).sorted().forEach(unused::add);
        ArrayNode refuted = answer.putArray("contradicted-accepts");
        contradicted.forEach(refuted::add);
        return GovernanceRoutes.json(200, answer);
    }

    /**
     * Every swap the archive records, each chain checked before any of its records counts (D23). With no archive, or
     * one that cannot be read whole, the request fails rather than walk as though the archive recorded nothing, which
     * would take every {@code accept} entry unchecked.
     */
    static ArchivedCommits.Read archived(Optional<ObjectLockArchive> archive) {
        ObjectLockArchive configured = archive.orElseThrow(() -> CatalogError.of(409, "ArchiveNotConfiguredException",
                "accept-archived reads the audit archive, and this server has none: nuna.audit.archive.bucket is not set"));
        try {
            return ArchivedCommits.read(configured);
        } catch (IllegalStateException e) {
            throw CatalogError.of(500, "ArchiveIntegrityException",
                    "The audit archive does not verify, so it proves no tip: " + e.getMessage());
        } catch (RuntimeException e) {
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "The audit archive cannot be read, so it proves no tip: " + e.getMessage());
        }
    }

    /** Active tables, depth first through the namespace tree; {@code levels} are the display names above each. */
    private void forEachTable(UUID parentId, List<String> levels, TableVisitor visit) {
        page(page -> store.listObjects(parentId, CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, page),
                table -> visit.visit(table, List.copyOf(levels)));
        page(page -> store.listNamespaces(parentId, page), namespace -> {
            levels.add(namespace.name().display());
            forEachTable(namespace.id(), levels, visit);
            levels.removeLast();
        });
    }

    private interface TableVisitor {
        void visit(CatalogObject table, List<String> levels);
    }

    private interface Lister<T> {
        Page<T> list(PageRequest page);
    }

    private static <T> void page(Lister<T> lister, Consumer<T> each) {
        PageRequest page = PageRequest.first(PAGE);
        while (true) {
            Page<T> listed = lister.list(page);
            listed.items().forEach(each);
            if (listed.nextToken().isEmpty()) {
                return;
            }
            page = page.after(listed);
        }
    }

    /** A location the catalog gave out holds an object id as its last segment; any catalog's object counts. */
    private boolean known(String location) {
        try {
            return store.readObject(UUID.fromString(location.substring(location.lastIndexOf('/') + 1))).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ObjectNode table(Reconcile.Plan plan, List<String> levels, Reconcile.Applied applied) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", plan.table().id().toString());
        ArrayNode path = node.putArray("path");
        levels.forEach(path::add);
        path.add(plan.table().name().display());
        ObjectNode from = node.putObject("from");
        from.put("metadata-location", plan.from().metadataLocation());
        from.put("version", plan.from().version());
        ArrayNode steps = node.putArray("steps");
        for (Reconcile.Step step : plan.steps()) {
            ObjectNode s = steps.addObject();
            s.put("metadata-location", step.location());
            s.put("version", step.version());
            s.put("proof", step.proof().name().toLowerCase(Locale.ROOT));
        }
        ArrayNode tips = node.putArray("tips");
        plan.tips().forEach(tips::add);
        plan.problem().ifPresent(problem -> node.put("problem", problem));
        if (applied != null) {
            node.put("steps-applied", applied.stepsApplied());
            applied.problem().ifPresent(problem -> node.put("apply-problem", problem));
        }
        return node;
    }
}
