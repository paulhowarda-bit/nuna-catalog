package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.Alerts;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.rest.ServerAlerts;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.Securable;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tables of one namespace with each one's format version, snapshot count and last commit, for the console's
 * Contents (D45, backlog 137; docs/observability/namespace-tables.md). On main everything comes from what the catalog
 * keeps: the projection stored beside each main pointer (D5) and the commit journal's entry for that pointer's
 * version. No metadata file is read and no table is loaded, so nothing is audited per table.
 *
 * <p>A table is listed only to a caller who may {@code describe} it; any other is skipped, never named. The namespace
 * is answered as Iceberg's listing answers it to a caller who holds something on it, above it or in it; any other
 * caller is refused, audited, and answered as for a namespace that does not exist (D18).
 *
 * <p>Beside it, the child namespaces of a namespace or of the catalog, each with its totals over every table beneath
 * it that the caller may describe (backlog 147): how many, their snapshots summed, and the newest commit among them.
 * The same two reads a table; a table the caller may not describe is not counted.
 *
 * <p>With {@code ref}, both read through the catalog's branch or tag of that name (D33, backlog 168): its cut's
 * namespaces and tables, by the names the cut has, and each table as the ref reads it: a branch's own pointer where it
 * has one, else the pointer its cut took. Rights are main's, as on every ref read (item 26's choice 12): a table is
 * listed to a caller who may describe it on main, and the namespace answered as above by its id. A cut's pointer
 * keeps no projection, so its format and snapshots come from main's projection while main's pointer is still the
 * cut's file, and otherwise from the cut's metadata file, checked against its SHA-256, read through the metadata cache
 * and audited no more than a projection is.
 */
@RestController
class NamespaceTablesRoutes {

    static final String TABLES = NAMESPACE + "/tables";
    /** The child namespaces of the catalog, and of a namespace, with their totals. */
    static final String CATALOG_NAMESPACES = CATALOG + "/namespaces";
    static final String NAMESPACES = NAMESPACE + "/namespaces";
    /** Rows a page holds unless it asks. */
    static final int PAGE = 100;
    /** Rows a page holds at most. */
    static final int MAX_PAGE = 200;
    /** Tables one page looks at, at most; a page that finds fewer the caller may describe ends early, with a token. */
    static final int MAX_EXAMINED = 1000;
    private static final String OPERATION = "listNamespaceTables";
    private static final Authorizer.Operation DESCRIBE = new Authorizer.Operation(OPERATION, Privilege.DESCRIBE,
            false);
    private static final String TOTALS = "listNamespaceTotals";
    private static final Authorizer.Operation DESCRIBE_COUNTED = new Authorizer.Operation(TOTALS,
            Privilege.DESCRIBE, false);
    /** Commits a search for a table's last data change reads at most, newest first, and how many each read holds. */
    static final int DATA_LOOK_BACK = 200;
    private static final int DATA_WINDOW = 50;

    /**
     * The catalog's own read of a metadata file, from the metadata cache, which holds only files that matched their
     * SHA-256, or from the object store, with whether it still matches.
     */
    @FunctionalInterface
    interface MetadataFiles {
        TableFiles.Read read(UUID catalogId, String location, String sha256);
    }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final Holdings holdings;
    private final Authorizer authorizer;
    private final MetadataFiles files;
    private final Alerts alerts;
    private final Clock clock;

    @Autowired
    NamespaceTablesRoutes(CatalogStore store, Management management, ManagementGuard guard, Holdings holdings,
                          Authorizer authorizer, CatalogStorage storage, MetadataCache cache, ServerAlerts alerts,
                          Clock clock) {
        this(store, management, guard, holdings, authorizer, (catalogId, location, sha256) -> {
            String cached = cache.get(location);
            if (cached != null) {
                return new TableFiles.Read(cached, sha256, true);
            }
            TableFiles.Read read = storage.forCatalog(catalogId).files().read(location, sha256);
            if (read.intact()) {
                cache.put(location, read.json());
            }
            return read;
        }, alerts, clock);
    }

    NamespaceTablesRoutes(CatalogStore store, Management management, ManagementGuard guard, Holdings holdings,
                          Authorizer authorizer, MetadataFiles files, Alerts alerts, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.holdings = holdings;
        this.authorizer = authorizer;
        this.files = files;
        this.alerts = alerts;
        this.clock = clock;
    }

    /**
     * {@code {"tables": [{"id", "name", "format-version", "snapshots", "last-commit": {"version", "committed-at",
     * "principal", "end-user", "engine", "maintenance"}, "last-data-change": {...}}], "next-page-token"}}, by name. Each page holds at most {@code pageSize} rows
     * (100 by default, 1 to 200), and fewer when the caller may describe few of the tables it looks at (1,000 at
     * most); {@code next-page-token} is there while more may follow.
     *
     * <p>{@code last-data-change} is the newest commit that changed the table's rows ({@link DataChanges}; backlog
     * 330), in {@code last-commit}'s form, found among the newest {@value #DATA_LOOK_BACK} commits of the journal that
     * wrote the version the row reads, and left out when none of them did. {@code maintenance} is {@code true} on a
     * commit a maintenance principal made acting as itself, and left out otherwise.
     *
     * <p>Through {@code ref}, each row also has {@code "ref": {"pointer", "version", "line"}}: {@code pointer} is
     * {@code own} for a branch's own pointer and {@code cut} for the one its cut took; {@code version} is that
     * pointer's, numbered on {@code line}, the line whose journal wrote it: the ref's own name for its own pointer
     * (item 105 numbers a branch's own versions from 1), else {@code main}, or the ref the cut took a branch's own
     * pointer from, by the name it had then. {@code line} is left out when the journal that wrote the cut's file
     * cannot be found (a ref the cut came from has ended, and with it what that one was made from); then
     * {@code last-commit} is left out too. {@code last-commit.version} is {@code ref.version}, on the same line, which
     * {@code last-commit.line} names. 404 {@code NoSuchRefException} when no live ref has the name, and
     * {@code NoSuchNamespaceException} when its cut has no such namespace.
     */
    @GetMapping(path = TABLES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> tables(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                  @RequestParam(required = false) String pageToken,
                                  @RequestParam(required = false) Integer pageSize,
                                  @RequestParam(required = false) String ref, HttpServletRequest http) {
        Request request = management.request(http);
        Scope scope = scope(request, OPERATION, prefix, namespace, ref);
        Management.Target target = scope.target();
        if (!holdings.onAboveOrIn(request.caller(), target.catalog().id(), target.securable().id())) {
            guard.require(request, target, OPERATION, Privilege.DESCRIBE);
        }
        int size = pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode rows = body.putArray("tables");
        Optional<String> cursor = Optional.ofNullable(pageToken).filter(token -> !token.isEmpty());
        Optional<String> next;
        int examined = 0;
        while (true) {
            Listing page;
            try {
                page = tables(scope, target.securable().id(), new PageRequest(cursor,
                        Math.min(size - rows.size(), MAX_EXAMINED - examined)));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            List<Listed> tables = page.items();
            examined += page.looked();
            List<Decision> decisions = decide(request.caller(), tables, DESCRIBE);
            for (int i = 0; i < tables.size(); i++) {
                if (decisions.get(i).allowed()) {
                    rows.add(row(scope, tables.get(i)));
                }
            }
            next = page.next();
            if (next.isEmpty() || rows.size() == size || examined >= MAX_EXAMINED) {
                break;
            }
            cursor = next;
        }
        next.ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * {@code {"namespaces": [{"name", "tables", "snapshots", "complete", "newest-commit": {"table", "version",
     * "committed-at", "principal", "end-user", "engine", "maintenance"}, "newest-data-change": {...}}],
     * "next-page-token"}}, by name: each child namespace the
     * caller holds something on, above or in, with its totals over the tables beneath it, at every depth, that the
     * caller may describe; {@code table} names the newest commit's table by its levels below the child, and
     * {@code newest-data-change} is the newest among the tables' {@code last-data-change}s, in the same form. A page holds
     * at most {@code pageSize} rows (100 by default, 1 to 200) and looks at 1,000 tables and namespaces at most: a
     * child whose walk would pass that waits for the next page, unless it is the page's first, whose totals then cover
     * what it looked at and say {@code "complete": false}.
     *
     * <p>Through {@code ref}, the cut's namespaces and tables, each table counted as the ref reads it, as
     * {@link #tables} reads it; {@code newest-commit.version} is on {@code newest-commit.line}'s numbering, as
     * {@code last-commit}'s there, and a table whose commit cannot be found is counted without one.
     */
    @GetMapping(path = {CATALOG_NAMESPACES, NAMESPACES}, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> namespaces(@PathVariable("catalog") String prefix,
                                      @PathVariable(required = false) String namespace,
                                      @RequestParam(required = false) String pageToken,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String ref, HttpServletRequest http) {
        Request request = management.request(http);
        Scope scope = scope(request, TOTALS, prefix, namespace, ref);
        Management.Target target = scope.target();
        UUID catalogId = target.catalog().id();
        if (namespace != null && !holdings.onAboveOrIn(request.caller(), catalogId, target.securable().id())) {
            guard.require(request, target, TOTALS, Privilege.DESCRIBE);
        }
        int size = pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode rows = body.putArray("namespaces");
        Optional<String> cursor = Optional.ofNullable(pageToken).filter(token -> !token.isEmpty());
        Optional<String> next;
        int examined = 0;
        while (true) {
            Page<Child> page;
            try {
                page = namespaces(scope, target.securable().id(), new PageRequest(cursor, 1));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            next = page.nextToken();
            if (page.items().isEmpty()) {
                break;
            }
            Child child = page.items().getFirst();
            examined++;
            if (holdings.onAboveOrIn(request.caller(), catalogId, child.id())) {
                Walk walk = walk(request.caller(), scope, child, MAX_EXAMINED - examined);
                if (!walk.complete() && !rows.isEmpty()) {
                    next = cursor;
                    break;
                }
                examined += walk.examined();
                rows.add(totals(scope, child, walk));
            }
            if (next.isEmpty() || rows.size() == size || examined >= MAX_EXAMINED) {
                break;
            }
            cursor = next;
        }
        next.ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    // ---- what a listing reads: main, or a ref's cut ----

    /** What a listing reads: main, or a live ref's cut; and the catalog or namespace it lists, as a guard's target. */
    private record Scope(Management.Target target, Optional<Ref> ref) {
    }

    /** A table as a listing reads it: main's record of it, its name as read, and through a ref the cut's pointer. */
    private record Listed(CatalogObject table, String name, Optional<RefEntry.At> cut) {
    }

    /** A page of tables: those listed, how many the store gave to make it, and the next page's token. */
    private record Listing(List<Listed> items, int looked, Optional<String> next) {
    }

    /** A namespace as a listing reads it. */
    private record Child(UUID id, String name) {
    }

    /**
     * The catalog or namespace a route names, on main or in the cut of the live ref {@code ref} names. Through a ref
     * the namespace is the cut's, by its id, which grants on main name too, whatever main has done with it since.
     */
    private Scope scope(Request request, String operation, String prefix, String namespace, String ref) {
        if (ref == null || ref.isEmpty()) {
            return new Scope(management.target(request, operation, Privilege.DESCRIBE, prefix, namespace, null, null),
                    Optional.empty());
        }
        CatalogRecord catalog = management.catalog(prefix, request, operation, Privilege.DESCRIBE);
        Ref on = store.findRef(catalog.id(), Names.normalize(ref)).filter(r -> !r.expiredAt(clock.instant()))
                .orElseThrow(() -> CatalogError.of(404, "NoSuchRefException", "Ref does not exist: " + ref));
        if (namespace == null) {
            return new Scope(new Management.Target(catalog, new Securable(Securable.Kind.CATALOG, catalog.id()),
                    new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                            catalog.name(), List.of()), Optional.empty(), GovernanceChain.ofCatalog(store, catalog.id()),
                    CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix)), Optional.of(on));
        }
        List<String> levels = RestRequest.namespaceLevels(namespace);
        RefEntry entry = store.findRefNamespace(on.id(), levels.stream().map(Names::normalize).toList())
                .orElseThrow(() -> Management.noSuchNamespace(levels));
        NamespaceRecord record = store.readNamespace(entry.id()).orElse(new NamespaceRecord(catalog.id(), entry.id(),
                entry.parentId(), entry.name(), Map.of(), 1));
        return new Scope(new Management.Target(catalog, new Securable(Securable.Kind.NAMESPACE, entry.id()),
                new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.NAMESPACE, entry.id(),
                        catalog.name(), levels), Optional.empty(), GovernanceChain.of(store, record),
                Management.noSuchNamespace(levels)), Optional.of(on));
    }

    /**
     * A page of the tables in a namespace: main's active ones, or the cut's, less any main has unregistered, which the
     * catalog no longer tracks and no ref serves.
     */
    private Listing tables(Scope scope, UUID namespaceId, PageRequest request) {
        if (scope.ref().isEmpty()) {
            Page<CatalogObject> page = store.listObjects(namespaceId, CatalogObject.Kind.TABLE,
                    CatalogObject.State.ACTIVE, request);
            return new Listing(page.items().stream().map(table -> new Listed(table, table.name().display(),
                    Optional.empty())).toList(), page.items().size(), page.nextToken());
        }
        Page<RefEntry> page = store.listRefObjects(scope.ref().get().id(), namespaceId, CatalogObject.Kind.TABLE,
                request);
        List<Listed> items = new ArrayList<>();
        for (RefEntry entry : page.items()) {
            store.readObject(entry.id()).filter(table -> table.state() != CatalogObject.State.UNREGISTERED)
                    .ifPresent(table -> items.add(new Listed(table, entry.name().display(), entry.pointer())));
        }
        return new Listing(items, page.items().size(), page.nextToken());
    }

    /** A page of the namespaces under a namespace or the catalog: main's, or the cut's. */
    private Page<Child> namespaces(Scope scope, UUID parentId, PageRequest request) {
        if (scope.ref().isEmpty()) {
            Page<NamespaceRecord> page = store.listNamespaces(parentId, request);
            return new Page<>(page.items().stream().map(ns -> new Child(ns.id(), ns.name().display())).toList(),
                    page.nextToken());
        }
        Page<RefEntry> page = store.listRefNamespaces(scope.ref().get().id(), parentId, request);
        return new Page<>(page.items().stream().map(ns -> new Child(ns.id(), ns.name().display())).toList(),
                page.nextToken());
    }

    /** Whether the caller may describe each table, on main, decided in one batch. */
    private List<Decision> decide(Caller caller, List<Listed> tables, Authorizer.Operation operation) {
        return tables.isEmpty() ? List.of() : authorizer.decide(caller, tables.stream()
                .map(listed -> new Authorizer.Request(new Authorizer.Target.TableOrView(listed.table()), operation))
                .toList());
    }

    // ---- a child namespace's totals ----

    /** A table the caller may describe, found by a walk, with its levels below the walk's top. */
    private record Counted(Listed table, List<String> path) {
    }

    /** What a walk counted, how many tables and namespaces it looked at, and whether it saw all beneath its top. */
    private record Walk(List<Counted> counted, int examined, boolean complete) {
    }

    private record Level(Child namespace, List<String> path) {
    }

    /**
     * The tables beneath {@code top}, at every depth, that the caller may describe, depth first by name, looking at
     * {@code budget} tables and namespaces at most; incomplete when it reaches that before it knows it has seen all.
     */
    private Walk walk(Caller caller, Scope scope, Child top, int budget) {
        List<Counted> counted = new ArrayList<>();
        Deque<Level> pending = new ArrayDeque<>();
        pending.push(new Level(top, List.of()));
        int examined = 0;
        while (!pending.isEmpty()) {
            Level level = pending.pop();
            Optional<String> token = Optional.empty();
            do {
                if (examined >= budget) {
                    return new Walk(counted, examined, false);
                }
                Listing page = tables(scope, level.namespace().id(), new PageRequest(token, budget - examined));
                List<Listed> tables = page.items();
                examined += page.looked();
                List<Decision> decisions = decide(caller, tables, DESCRIBE_COUNTED);
                for (int i = 0; i < tables.size(); i++) {
                    if (decisions.get(i).allowed()) {
                        counted.add(new Counted(tables.get(i), below(level.path(), tables.get(i).name())));
                    }
                }
                token = page.next();
            } while (token.isPresent());
            List<Level> under = new ArrayList<>();
            token = Optional.empty();
            do {
                if (examined >= budget) {
                    return new Walk(counted, examined, false);
                }
                Page<Child> page = namespaces(scope, level.namespace().id(), new PageRequest(token, budget - examined));
                examined += page.items().size();
                // Through a ref a namespace of the cut may hold a table main has since moved where the caller may
                // describe it; its newest commit would then name the namespace, which the caller may not list. So a
                // walk through a ref goes down only into a namespace the caller holds something on, above or in.
                page.items().stream().filter(ns -> scope.ref().isEmpty()
                                || holdings.onAboveOrIn(caller, scope.target().catalog().id(), ns.id()))
                        .forEach(ns -> under.add(new Level(ns, below(level.path(), ns.name()))));
                token = page.nextToken();
            } while (token.isPresent());
            for (int i = under.size() - 1; i >= 0; i--) {
                pending.push(under.get(i));
            }
        }
        return new Walk(counted, examined, true);
    }

    private static List<String> below(List<String> path, String name) {
        return Stream.concat(path.stream(), Stream.of(name)).toList();
    }

    /** A child namespace's row: the tables counted, their snapshots summed, and the newest commit among them. */
    private ObjectNode totals(Scope scope, Child child, Walk walk) {
        ObjectNode row = Management.JSON.createObjectNode();
        row.put("name", child.name());
        row.put("tables", walk.counted().size());
        long snapshots = 0;
        Counted newest = null;
        Read newestRead = null;
        Counted newestData = null;
        Read newestDataRead = null;
        for (Counted counted : walk.counted()) {
            Optional<Read> read = read(scope, counted.table());
            if (read.isEmpty()) {
                continue;
            }
            snapshots += read.get().facts().map(facts -> facts.path("snapshots").asLong(0)).orElse(0L);
            Optional<JournalRecord> entry = read.get().entry();
            if (entry.isPresent() && (newestRead == null
                    || entry.get().committedAt().isAfter(newestRead.entry().orElseThrow().committedAt()))) {
                newest = counted;
                newestRead = read.get();
            }
            Optional<Read> data = read.get().dataChange();
            if (data.isPresent() && (newestDataRead == null || data.get().entry().orElseThrow().committedAt()
                    .isAfter(newestDataRead.entry().orElseThrow().committedAt()))) {
                newestData = counted;
                newestDataRead = data.get();
            }
        }
        row.put("snapshots", snapshots);
        row.put("complete", walk.complete());
        if (newest != null) {
            ObjectNode commit = commit(newestRead);
            ArrayNode table = commit.putArray("table");
            newest.path().forEach(table::add);
            row.set("newest-commit", commit);
        }
        if (newestData != null) {
            ObjectNode commit = commit(newestDataRead);
            ArrayNode table = commit.putArray("table");
            newestData.path().forEach(table::add);
            row.set("newest-data-change", commit);
        }
        return row;
    }

    // ---- a table as a listing reads it ----

    /**
     * The pointer a listing reads for a table: {@code pointer} names it ({@code main}, {@code own} or {@code cut}),
     * {@code version} is its version on {@code line}'s numbering ({@code main} or a ref's name; empty on main's
     * listing, and where it cannot be found), {@code facts} holds its format version and snapshot count, and
     * {@code entry} the journal's entry that wrote it.
     */
    private record Read(String pointer, long version, Optional<String> line, Optional<JsonNode> facts,
                        Optional<JournalRecord> entry, Optional<JournalRecord> data) {

        /** The read with its last data change as its entry, on the same line; empty when none was found. */
        Optional<Read> dataChange() {
            return data.map(found -> new Read(pointer, found.newVersion(), line, facts, Optional.of(found),
                    Optional.empty()));
        }
    }

    /** The table's row: its format version and snapshot count, its last commit, and through a ref what it reads. */
    private ObjectNode row(Scope scope, Listed listed) {
        ObjectNode row = Management.JSON.createObjectNode();
        row.put("id", listed.table().id().toString());
        row.put("name", listed.name());
        read(scope, listed).ifPresent(read -> {
            read.facts().ifPresent(facts -> {
                if (facts.path("format-version").isNumber()) {
                    row.put("format-version", facts.get("format-version").asInt());
                }
                if (facts.path("snapshots").isNumber()) {
                    row.put("snapshots", facts.get("snapshots").asLong());
                }
            });
            if (scope.ref().isPresent()) {
                ObjectNode on = row.putObject("ref");
                on.put("pointer", read.pointer());
                on.put("version", read.version());
                read.line().ifPresent(line -> on.put("line", line));
            }
            if (read.entry().isPresent()) {
                row.set("last-commit", commit(read));
            }
            read.dataChange().ifPresent(data -> row.set("last-data-change", commit(data)));
        });
        return row;
    }

    /**
     * What a listing reads of a table. On main, its main pointer, with the projection stored beside it. Through a
     * branch, its own pointer where it has one, with its projection. Otherwise the pointer the cut took, on the line
     * {@link #line} finds, with main's projection while main's pointer is still that file, else the file's own facts.
     */
    private Optional<Read> read(Scope scope, Listed listed) {
        CatalogObject table = listed.table();
        if (scope.ref().isEmpty()) {
            return store.readPointer(table.mainKey()).map(pointer -> read(PointerKey.MAIN, pointer.version(),
                    Optional.empty(), Optional.of(Management.JSON.readTree(pointer.projection())),
                    entry(table.mainKey(), pointer.version(), Optional.empty())));
        }
        Ref ref = scope.ref().get();
        if (ref.kind() == Ref.Kind.BRANCH) {
            Optional<Pointer> own = store.readPointer(ref.key(table.id()));
            if (own.isPresent()) {
                return Optional.of(read("own", own.get().version(), Optional.of(ref.name().display()),
                        Optional.of(Management.JSON.readTree(own.get().projection())),
                        entry(ref.key(table.id()), own.get().version(), Optional.of(own.get().metadataLocation()))));
            }
        }
        RefEntry.At cut = listed.cut().orElseThrow();
        Optional<Line> line = line(ref, table, cut);
        return Optional.of(read("cut", cut.version(), line.map(Line::name),
                facts(scope.target().catalog(), table, cut), line.map(Line::entry)));
    }

    /** A read whose journal entry is {@code entry}, with the last data change found at or below it. */
    private Read read(String pointer, long version, Optional<String> line, Optional<JsonNode> facts,
                      Optional<JournalRecord> entry) {
        return new Read(pointer, version, line, facts, entry, entry.flatMap(this::dataChange));
    }

    /**
     * The newest commit at or below {@code last} on its journal that changed the table's rows ({@link DataChanges}),
     * reading the journal down from it {@value #DATA_WINDOW} versions at a time, {@value #DATA_LOOK_BACK} commits at
     * most; empty when none of those did.
     */
    private Optional<JournalRecord> dataChange(JournalRecord last) {
        if (changesData(last)) {
            return Optional.of(last);
        }
        long before = last.newVersion();
        int looked = 1;
        while (before > 1 && looked < DATA_LOOK_BACK) {
            long low = Math.max(1, before - Math.min(DATA_WINDOW, DATA_LOOK_BACK - looked));
            List<JournalRecord> window = store.readJournal(last.key(), JournalStore.EARLIEST, JournalStore.LATEST,
                    JournalStore.afterVersion(low - 1, (int) (before - low))).items();
            for (int i = window.size() - 1; i >= 0; i--) {
                JournalRecord entry = window.get(i);
                if (entry.newVersion() >= before) {
                    continue;
                }
                looked++;
                if (changesData(entry)) {
                    return Optional.of(entry);
                }
            }
            before = low;
        }
        return Optional.empty();
    }

    private static boolean changesData(JournalRecord entry) {
        return DataChanges.changesData(Management.JSON.readTree(entry.entry().updatesJson()));
    }

    /** The journal a cut's pointer is on, by the name a listing gives it, and its entry that wrote the pointer. */
    private record Line(String name, JournalRecord entry) {
    }

    /**
     * The line whose journal wrote the pointer a ref's cut took (backlog 168), as item 107's {@code Merge.base} and
     * item 109's metadata log find it: down the refs each was made from, the first whose own journal wrote the cut's
     * file, at its version, is the one whose own pointer the cut took; a branch's journal stays after it ends, keyed by
     * its id. A ref that kept the pointer as its own cut had it from the one it was made from, so the walk goes on
     * through it, and stops at one that has ended, whose own source is not kept. Main's when main's journal wrote it.
     */
    private Optional<Line> line(Ref ref, CatalogObject table, RefEntry.At cut) {
        Ref at = ref;
        while (at.fromRef().isPresent()) {
            UUID from = at.fromRef().get();
            Optional<JournalRecord> wrote = entry(new PointerKey(table.id(), from.toString()), cut.version(),
                    Optional.of(cut.metadataLocation()));
            if (wrote.isPresent()) {
                return Optional.of(new Line(at.fromName(), wrote.get()));
            }
            Optional<Ref> source = store.readRef(from);
            if (source.isEmpty()) {
                break;
            }
            at = source.get();
        }
        return entry(table.mainKey(), cut.version(), Optional.of(cut.metadataLocation()))
                .map(entry -> new Line(PointerKey.MAIN, entry));
    }

    /**
     * The format version and snapshot count of the file a cut took: main's projection while main's pointer is that
     * file; else the file's own, read and checked against its SHA-256. A file that no longer matches raises the
     * metadata integrity alert, as every load of one does (D26), and gives no facts.
     */
    private Optional<JsonNode> facts(CatalogRecord catalog, CatalogObject table, RefEntry.At cut) {
        Optional<Pointer> main = store.readPointer(table.mainKey());
        if (main.isPresent() && main.get().metadataLocation().equals(cut.metadataLocation())) {
            return Optional.of(Management.JSON.readTree(main.get().projection()));
        }
        TableFiles.Read read = files.read(catalog.id(), cut.metadataLocation(), cut.sha256());
        if (!read.intact()) {
            alerts.metadataIntegrity(table.id(), cut.metadataLocation(), cut.sha256(), read.sha256());
            return Optional.empty();
        }
        JsonNode file = Management.JSON.readTree(read.json());
        ObjectNode facts = Management.JSON.createObjectNode();
        facts.put("format-version", file.path("format-version").asInt(1));
        facts.put("snapshots", file.path("snapshots").size());
        return Optional.of(facts);
    }

    /** The journal's entry of {@code key} at {@code version}, and when {@code location} is given, that wrote it. */
    private Optional<JournalRecord> entry(PointerKey key, long version, Optional<String> location) {
        return store.readJournal(key, JournalStore.EARLIEST, JournalStore.LATEST,
                        JournalStore.afterVersion(version - 1, 1)).items().stream()
                .filter(entry -> entry.newVersion() == version
                        && location.map(entry.newLocation()::equals).orElse(true))
                .findFirst();
    }

    /**
     * A commit as a row shows it: when, and by whom, with whether that was a maintenance principal acting as itself;
     * through a ref, the line its version is on.
     */
    private ObjectNode commit(Read read) {
        JournalRecord entry = read.entry().orElseThrow();
        ObjectNode commit = Management.JSON.createObjectNode();
        commit.put("version", entry.newVersion());
        read.line().ifPresent(line -> commit.put("line", line));
        commit.put("committed-at", entry.committedAt().toString());
        commit.put("principal", entry.entry().principal());
        if (entry.entry().endUser() != null) {
            commit.put("end-user", entry.entry().endUser());
        }
        if (entry.entry().engine() != null) {
            commit.put("engine", entry.entry().engine());
        }
        if (authorizer.maintenance(entry.entry().principal(), entry.entry().endUser())) {
            commit.put("maintenance", true);
        }
        return commit;
    }
}
