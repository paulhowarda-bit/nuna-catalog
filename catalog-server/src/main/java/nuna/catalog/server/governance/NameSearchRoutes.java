package nuna.catalog.server.governance;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every namespace, table and view whose name holds a fragment, among those the caller may describe, for the console's
 * Go to a path (Atelier's review, S1; backlog 198): it finds a name typed alone, in any catalog, without the console
 * having listed its namespace first.
 *
 * <p>Only what the caller may describe is listed, by the rules the other listings use: a declared catalog only where
 * the caller holds something ({@link Holdings#inCatalog}, as the catalog list counts it); a namespace only where a
 * grant reaches it or anything in it (as {@link Holdings#onAboveOrIn} answers, read once per catalog from the caller's
 * own grants there); a table or view only where the authorizer allows {@code describe} on it, as a namespace's Contents
 * lists it. Anything else is skipped, never named or counted, and nothing is refused, so searching writes no denial.
 * One catalog may be asked for by name: it is found as every route that names a catalog finds it ({@link
 * Holdings#catalog}, item 68), so one the caller holds nothing in is answered as one that does not exist, 404, and that
 * refusal is audited; so is one the deployment does not declare, unaudited, as it is served by no route.
 *
 * <p>What a search costs does not depend on hidden names (D18; the security review of 2026-11-23): every object of a
 * catalog the caller holds something in is decided, a batch at a time, before its name is looked at, and which
 * namespaces the caller may see is known before the walk, so a hidden match costs no more than no match. A caller who
 * holds nothing in any catalog is answered at once.
 *
 * <p>Namespaces come first, every match in the first page (at most a page of them; {@code more-namespaces} when there
 * were more, or the walk stopped at its limit), then tables, then views, each walked by id {@value #LOOK} at a time; a
 * page looks at {@value #MAX_EXAMINED} objects at most and ends early with a token when it has not found enough.
 */
@RestController
class NameSearchRoutes {

    static final String NAMES = Management.ROOT + "/names";
    /** Rows a page holds unless it asks. */
    static final int PAGE = 50;
    /** Rows a page holds at most. */
    static final int MAX_PAGE = 200;
    /** Objects, or namespaces, one page looks at, at most. */
    static final int MAX_EXAMINED = 5000;
    /** Objects asked of the store, and decided, at a time, whatever the page size. */
    static final int LOOK = 200;
    private static final Authorizer.Operation DESCRIBE = new Authorizer.Operation("searchNames", Privilege.DESCRIBE,
            false);
    private static final String TABLES = "t";
    private static final String VIEWS = "v";

    private final CatalogStore store;
    private final Management management;
    private final Holdings holdings;
    private final Authorizer authorizer;
    private final StorageSettings storage;

    NameSearchRoutes(CatalogStore store, Management management, Holdings holdings, Authorizer authorizer,
                     StorageSettings storage) {
        this.store = store;
        this.management = management;
        this.holdings = holdings;
        this.authorizer = authorizer;
        this.storage = storage;
    }

    /**
     * {@code {"names": [{"catalog", "kind": "namespace" | "table" | "view", "namespace": [...], "name"}],
     * "next-page-token", "more-namespaces"}}: {@code namespace} is the levels above the name, from the outermost.
     */
    @GetMapping(path = NAMES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> names(@RequestParam String q, @RequestParam(required = false) String catalog,
                                 @RequestParam(required = false) String pageToken,
                                 @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        Request request = management.request(http);
        String fragment = q.strip().toLowerCase(Locale.ROOT);
        if (fragment.isEmpty()) {
            throw CatalogError.badRequest("Give part of a name to search for");
        }
        int size = pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE);
        String phase;
        Optional<String> cursor;
        int skip;
        if (pageToken == null || pageToken.isEmpty()) {
            phase = null;
            cursor = Optional.empty();
            skip = 0;
        } else {
            try {
                List<String> parts = PageToken.decode(pageToken, 3);
                phase = parts.get(0);
                cursor = Optional.of(parts.get(1)).filter(token -> !token.isEmpty());
                skip = Integer.parseInt(parts.get(2));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            if (!phase.equals(TABLES) && !phase.equals(VIEWS) || skip < 0 || skip >= LOOK) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
        }

        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode rows = body.putArray("names");
        Map<UUID, String> catalogs = catalogs(request, catalog);
        if (catalogs.isEmpty()) {
            return GovernanceRoutes.json(200, body);
        }
        Map<UUID, Optional<NamespaceRecord>> namespaces = new HashMap<>();
        if (phase == null) {
            if (namespaces(request.caller(), catalogs, fragment, size, rows, namespaces)) {
                body.put("more-namespaces", true);
            }
            phase = TABLES;
        }
        int examined = 0;
        // Where the next page starts when this one ends before looking further: a page the namespaces filled.
        Optional<String> next = Optional.of(token(phase, cursor, skip));
        while (rows.size() < size && examined < MAX_EXAMINED) {
            CatalogObject.Kind kind = phase.equals(TABLES) ? CatalogObject.Kind.TABLE : CatalogObject.Kind.VIEW;
            Page<CatalogObject> page;
            try {
                page = store.listActiveObjects(kind, new PageRequest(cursor, LOOK));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            List<CatalogObject> items = page.items();
            // Every object of a catalog the caller holds something in is decided, whatever its name, so what a look
            // costs says nothing of the names the caller may not see.
            List<CatalogObject> held = items.subList(Math.min(skip, items.size()), items.size()).stream()
                    .filter(object -> catalogs.containsKey(object.catalogId())).toList();
            List<Decision> decisions = held.isEmpty() ? List.of() : authorizer.decide(request.caller(), held.stream()
                    .map(object -> new Authorizer.Request(new Target.TableOrView(object), DESCRIBE)).toList());
            Set<UUID> described = new HashSet<>();
            for (int i = 0; i < held.size(); i++) {
                if (decisions.get(i).allowed()) {
                    described.add(held.get(i).id());
                }
            }
            int consumed = skip;
            while (consumed < items.size() && rows.size() < size && examined < MAX_EXAMINED) {
                CatalogObject object = items.get(consumed++);
                examined++;
                if (described.contains(object.id()) && holds(object.name().display(), fragment)) {
                    path(object.namespaceId(), object.catalogId(), namespaces).ifPresent(levels -> rows.add(
                            row(catalogs.get(object.catalogId()), kind.name().toLowerCase(Locale.ROOT), levels,
                                    object.name().display())));
                }
            }
            if (consumed < items.size()) {
                // The page ended inside this look: the next one looks again from the same place, past what this used.
                next = Optional.of(token(phase, cursor, consumed));
                break;
            }
            skip = 0;
            if (page.nextToken().isPresent()) {
                cursor = page.nextToken();
                next = Optional.of(token(phase, cursor, 0));
            } else if (phase.equals(TABLES)) {
                phase = VIEWS;
                cursor = Optional.empty();
                next = Optional.of(token(VIEWS, cursor, 0));
            } else {
                next = Optional.empty();
                break;
            }
        }
        next.ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    private static String token(String phase, Optional<String> cursor, int skip) {
        return PageToken.encode(phase, cursor.orElse(""), Integer.toString(skip));
    }

    /**
     * The declared catalogs the caller holds something in, by id, sorted by name; or the one named, which the caller
     * must hold something in ({@link Management#catalog}).
     */
    private Map<UUID, String> catalogs(Request request, String only) {
        Map<UUID, String> found = new LinkedHashMap<>();
        if (only != null) {
            String declared = storage.catalogs().keySet().stream()
                    .filter(name -> Names.normalize(name).equals(Names.normalize(only))).findFirst()
                    .orElseThrow(() -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + only));
            found.put(management.catalog(only, request, "searchNames", Privilege.DESCRIBE).id(), declared);
            return found;
        }
        storage.catalogs().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(name -> store.findCatalog(Names.normalize(name))
                        .filter(record -> holdings.inCatalog(request.caller(), record.id()))
                        .ifPresent(record -> found.put(record.id(), name)));
        return found;
    }

    /**
     * The namespaces of every catalog whose name holds the fragment and which the caller may see, walked from each
     * catalog down, up to a page of them; true when there were more, or the walk stopped at its limit.
     */
    private boolean namespaces(Caller caller, Map<UUID, String> catalogs, String fragment, int size, ArrayNode rows,
                               Map<UUID, Optional<NamespaceRecord>> namespaces) {
        int examined = 0;
        for (Map.Entry<UUID, String> catalog : catalogs.entrySet()) {
            Seen seen = seen(caller, catalog.getKey(), namespaces);
            // Each namespace to look in, with the levels down to it and whether a grant on it or above opens it.
            record Parent(UUID id, List<String> levels, boolean open) {
            }
            Deque<Parent> todo = new ArrayDeque<>();
            todo.add(new Parent(catalog.getKey(), List.of(), seen.all()));
            while (!todo.isEmpty()) {
                if (examined >= MAX_EXAMINED) {
                    return true;
                }
                Parent parent = todo.removeFirst();
                PageRequest request = PageRequest.first(Math.min(MAX_PAGE, MAX_EXAMINED - examined));
                while (true) {
                    Page<NamespaceRecord> page = store.listNamespaces(parent.id(), request);
                    examined += page.items().size();
                    for (NamespaceRecord namespace : page.items()) {
                        boolean open = parent.open() || seen.opened().contains(namespace.id());
                        List<String> levels = new ArrayList<>(parent.levels());
                        levels.add(namespace.name().display());
                        todo.add(new Parent(namespace.id(), List.copyOf(levels), open));
                        if (holds(namespace.name().display(), fragment)
                                && (open || seen.shown().contains(namespace.id()))) {
                            if (rows.size() == size) {
                                return true;
                            }
                            rows.add(row(catalog.getValue(), "namespace", parent.levels(),
                                    namespace.name().display()));
                        }
                    }
                    if (page.nextToken().isEmpty()) {
                        break;
                    }
                    if (examined >= MAX_EXAMINED) {
                        return true;
                    }
                    request = request.after(page);
                }
            }
        }
        return false;
    }

    /**
     * Which namespaces of the catalog the caller may see, from its groups' own grants there, read once: every one
     * when a grant on the catalog reaches it (or it is a grant admin); below a namespace a grant opens, every one; and
     * every namespace above anything granted. {@link Holdings#onAboveOrIn} answers the same for one namespace.
     */
    private Seen seen(Caller caller, UUID catalogId, Map<UUID, Optional<NamespaceRecord>> namespaces) {
        Set<String> groups = caller.subject().groups();
        if (!authorizer.privileges(groups, new Target.Catalog(catalogId)).isEmpty()) {
            return new Seen(true, Set.of(), Set.of());
        }
        Set<UUID> opened = new HashSet<>();
        Set<UUID> shown = new HashSet<>();
        if (groups.isEmpty()) {
            return new Seen(false, opened, shown);
        }
        PageRequest request = PageRequest.first(MAX_PAGE);
        while (true) {
            Page<GrantRecord> grants = store.listGrantsOfGroups(catalogId, groups, request);
            for (GrantRecord grant : grants.items()) {
                Securable on = grant.grant().securable();
                switch (on.kind()) {
                    case CATALOG -> {
                    }
                    case NAMESPACE -> {
                        if (!opened.contains(on.id())
                                && !authorizer.privileges(groups, new Target.Namespace(catalogId, on.id())).isEmpty()) {
                            opened.add(on.id());
                        }
                        namespaces.computeIfAbsent(on.id(), store::readNamespace)
                                .ifPresent(namespace -> above(namespace.parentId(), catalogId, shown, namespaces));
                    }
                    case TABLE, VIEW -> store.readObject(on.id())
                            .ifPresent(object -> above(object.namespaceId(), catalogId, shown, namespaces));
                }
            }
            if (grants.nextToken().isEmpty()) {
                return new Seen(false, opened, shown);
            }
            request = request.after(grants);
        }
    }

    /** What {@link #seen} finds: all, or the namespaces opened from above and those shown for what is in them. */
    private record Seen(boolean all, Set<UUID> opened, Set<UUID> shown) {
    }

    /** Marks the namespace and every one above it, up to the catalog. */
    private void above(UUID namespaceId, UUID catalogId, Set<UUID> shown,
                       Map<UUID, Optional<NamespaceRecord>> namespaces) {
        UUID next = namespaceId;
        while (!next.equals(catalogId) && shown.add(next)) {
            Optional<NamespaceRecord> namespace = namespaces.computeIfAbsent(next, store::readNamespace);
            if (namespace.isEmpty()) {
                return;
            }
            next = namespace.get().parentId();
        }
    }

    /** The levels from the catalog down to the namespace; none when one of them is gone. */
    private Optional<List<String>> path(UUID namespaceId, UUID catalogId,
                                        Map<UUID, Optional<NamespaceRecord>> namespaces) {
        List<String> levels = new ArrayList<>();
        UUID next = namespaceId;
        while (!next.equals(catalogId)) {
            Optional<NamespaceRecord> namespace = namespaces.computeIfAbsent(next, store::readNamespace);
            if (namespace.isEmpty()) {
                return Optional.empty();
            }
            levels.addFirst(namespace.get().name().display());
            next = namespace.get().parentId();
        }
        return Optional.of(levels);
    }

    private static boolean holds(String name, String fragment) {
        return name.toLowerCase(Locale.ROOT).contains(fragment);
    }

    private static ObjectNode row(String catalog, String kind, List<String> namespace, String name) {
        ObjectNode row = Management.JSON.createObjectNode();
        row.put("catalog", catalog);
        row.put("kind", kind);
        ArrayNode levels = row.putArray("namespace");
        namespace.forEach(levels::add);
        row.put("name", name);
        return row;
    }
}
