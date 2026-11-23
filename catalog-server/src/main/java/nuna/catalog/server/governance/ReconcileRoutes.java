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
 * Under {@code refs} it lists every branch and tag whose cut, or a branch's own pointer, names a metadata file storage
 * lacks, with the tables and views concerned ({@link Reconcile#checkRefs}): a report only, with or without
 * {@code apply}, since no branch is walked or rewound (D33, amended 2026-11-20: "A report only"; item 111).
 *
 * <p>Each step is taken only when storage holds every file it adds, data included, and the walk stops at the first
 * that lacks one, with or without a DR watermark (D37, amended 2026-11-20: "Walk with a file check"; item 83). With
 * {@code {"rewind": true}} (and neither {@code accept} nor {@code accept-archived}) the step moves the other way: each
 * table and view whose pointer is above its DR watermark, or that has none, is reported with the journaled commits a
 * rewind gives up; with {@code apply}, each such table and view under a DR freeze moves back to its watermark in one
 * commit ({@link Reconcile#rewind}).
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
        boolean rewind = json.has("rewind") && json.get("rewind").asBoolean();
        if (rewind && (json.has("accept") || fromArchive)) {
            throw CatalogError.badRequest("rewind takes neither accept nor accept-archived: it moves each table back"
                    + " to its DR watermark, never forward");
        }
        // Read before any table is walked, so an archive that cannot be read fails the request with nothing applied.
        Optional<ArchivedCommits.Read> archived = fromArchive ? Optional.of(archived(runtime.archive())) : Optional.empty();
        TableFiles files = storage.forCatalog(catalog.id()).files();
        // The bounds the server's commit engines take (Engines): a step whose outcome was lost is decided as a commit.
        Reconcile reconcile = new Reconcile(store, files, CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
        if (rewind) {
            return GovernanceRoutes.json(200, rewind(catalog, request, reconcile, apply));
        }
        // Refs are reported, never moved (D33, amended 2026-11-20: "A report only"; item 111). Read before any table is
        // walked, so a store that fails them fails the request with nothing applied.
        List<Reconcile.RefCheck> checks = reconcile.checkRefs(catalog.id());

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
        forEach(catalog.id(), CatalogObject.Kind.TABLE, new ArrayList<>(), (table, levels) -> {
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
        ArrayNode refs = answer.putArray("refs");
        checks.stream().filter(check -> !check.missing().isEmpty() || check.problem().isPresent())
                .forEach(check -> refs.add(ref(check)));
        answer.put("refs-checked", checks.size());
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
     * The rewind (D37, amended 2026-11-20): every active table, then every active view ("Rewind views too"), whose
     * pointer is not at its DR watermark, with the commits a rewind gives up; with {@code apply}, those under a DR
     * freeze are moved back. One with no watermark is reported, since none of its versions is known whole in the DR
     * store, and is not moved: its owner decides.
     */
    private ObjectNode rewind(CatalogRecord catalog, Request request, Reconcile reconcile, boolean apply) {
        ObjectNode answer = Management.JSON.createObjectNode();
        answer.put("rewind", true);
        answer.put("apply", apply);
        ArrayNode tables = answer.putArray("tables");
        answer.put("tables-checked", rewind(catalog, CatalogObject.Kind.TABLE, request, reconcile, apply, tables));
        ArrayNode views = answer.putArray("views");
        answer.put("views-checked", rewind(catalog, CatalogObject.Kind.VIEW, request, reconcile, apply, views));
        return answer;
    }

    /** Rewinds the catalog's active objects of one kind into {@code reported}; how many it checked. */
    private int rewind(CatalogRecord catalog, CatalogObject.Kind kind, Request request, Reconcile reconcile,
                       boolean apply, ArrayNode reported) {
        int[] checked = {0};
        forEach(catalog.id(), kind, new ArrayList<>(), (object, levels) -> {
            checked[0]++;
            Reconcile.Rewind plan = reconcile.planRewind(object);
            if (plan.to().filter(to -> to.version() >= plan.from().version()).isPresent()) {
                return;
            }
            Reconcile.Rewound rewound = null;
            if (apply && plan.problem().isEmpty()) {
                List<String> path = new ArrayList<>(levels);
                path.add(object.name().display());
                rewound = reconcile.rewind(plan, request.caller(), audit.commits(catalog.name(), path,
                        request.actor()));
            }
            reported.add(rewound(plan, levels, rewound));
        });
        return checked[0];
    }

    private static ObjectNode rewound(Reconcile.Rewind plan, List<String> levels, Reconcile.Rewound rewound) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", plan.object().id().toString());
        ArrayNode path = node.putArray("path");
        levels.forEach(path::add);
        path.add(plan.object().name().display());
        ObjectNode from = node.putObject("from");
        from.put("metadata-location", plan.from().metadataLocation());
        from.put("version", plan.from().version());
        plan.to().ifPresentOrElse(to -> {
            ObjectNode watermark = node.putObject("watermark");
            watermark.put("metadata-location", to.metadataLocation());
            watermark.put("version", to.version());
        }, () -> node.putNull("watermark"));
        ArrayNode lost = node.putArray("lost");
        for (Reconcile.Lost commit : plan.lost()) {
            ObjectNode l = lost.addObject();
            l.put("version", commit.version());
            l.put("metadata-location", commit.metadataLocation());
            l.put("commit-id", commit.commitId().toString());
            l.put("committed-at", commit.committedAt().toString());
            l.put("principal", commit.principal());
        }
        plan.problem().ifPresent(problem -> node.put("problem", problem));
        if (rewound != null) {
            rewound.to().ifPresent(to -> {
                ObjectNode moved = node.putObject("rewound-to");
                moved.put("metadata-location", to.metadataLocation());
                moved.put("version", to.version());
            });
            rewound.problem().ifPresent(problem -> node.put("apply-problem", problem));
        }
        return node;
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

    /**
     * Active tables or views, depth first through the namespace tree; {@code levels} are the display names above
     * each.
     */
    private void forEach(UUID parentId, CatalogObject.Kind kind, List<String> levels, ObjectVisitor visit) {
        page(page -> store.listObjects(parentId, kind, CatalogObject.State.ACTIVE, page),
                object -> visit.visit(object, List.copyOf(levels)));
        page(page -> store.listNamespaces(parentId, page), namespace -> {
            levels.add(namespace.name().display());
            forEach(namespace.id(), kind, levels, visit);
            levels.removeLast();
        });
    }

    private interface ObjectVisitor {
        void visit(CatalogObject object, List<String> levels);
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

    /**
     * A branch or tag that names metadata files storage lacks: each with its table or view as the ref's cut names it,
     * and whether the cut's pointer names it or the branch's own.
     */
    private static ObjectNode ref(Reconcile.RefCheck check) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", check.ref().id().toString());
        node.put("name", check.ref().name().display());
        node.put("kind", check.ref().kind().name().toLowerCase(Locale.ROOT));
        ArrayNode missing = node.putArray("missing");
        for (Reconcile.RefFile file : check.missing()) {
            ObjectNode m = missing.addObject();
            m.put("id", file.object().toString());
            m.put("kind", file.kind().name().toLowerCase(Locale.ROOT));
            ArrayNode path = m.putArray("path");
            file.path().forEach(path::add);
            m.put("pointer", file.pointer().name().toLowerCase(Locale.ROOT));
            m.put("version", file.version());
            m.put("metadata-location", file.metadataLocation());
        }
        check.problem().ifPresent(problem -> node.put("problem", problem));
        return node;
    }
}
