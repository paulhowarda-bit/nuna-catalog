package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Resolves what a route names: the catalog and ref its prefix names, namespaces, tables and views (D8.6, D28), on
 * main or, through a ref, as of the ref's cut (D33, as amended 2026-11-20).
 */
@Component
class Lookup {

    /**
     * A table or view as a read sees it. {@code object} is main's record of it: its id, and the grants and governance
     * it holds on main, which hold on every ref (item 26's choice 12). Through a ref, {@code cut} is the pointer the
     * ref's cut took for it; on main it is empty.
     */
    record Seen(CatalogObject object, Optional<RefEntry.At> cut) {
    }

    private final CatalogStore store;
    private final RestSettings settings;
    private final Prefixes prefixes;

    Lookup(CatalogStore store, RestSettings settings, Prefixes prefixes) {
        this.store = store;
        this.settings = settings;
        this.prefixes = prefixes;
    }

    /**
     * What the prefix names, its catalog labelling the request's meters ({@link Prefixes#resolve}). A caller that
     * holds nothing on the catalog is answered as for a prefix no catalog has, its refusal of {@code operation}
     * audited.
     */
    OnRef onRef(String prefix, RequestContext request, Operation operation) {
        OnRef on = prefixes.resolve(prefix, request.caller(), request.actor(), operation.name(),
                operation.privilege());
        RequestMetrics.catalog(on.catalog().name());
        return on;
    }

    /**
     * The catalog the prefix names, for a create, drop, rename, register, unregister or namespace change: on a ref it
     * is refused ({@link OnRef#mainOnly}).
     */
    CatalogRecord catalog(String prefix, RequestContext request, Operation operation) {
        return onRef(prefix, request, operation).mainOnly(operation.name());
    }

    /** The catalog the prefix names, for a commit or a view's replace: refused on a ref ({@link OnRef#forCommit}). */
    CatalogRecord forCommit(String prefix, RequestContext request, Operation operation) {
        return onRef(prefix, request, operation).forCommit(operation.name());
    }

    /** The namespace path segment: levels joined by 0x1F. */
    static List<String> levels(String encoded) {
        return RestRequest.namespaceLevels(encoded);
    }

    Optional<NamespaceRecord> namespace(CatalogRecord catalog, List<String> levels) {
        return store.findNamespace(catalog.id(), normalized(levels));
    }

    NamespaceRecord existingNamespace(CatalogRecord catalog, List<String> levels) {
        return namespace(catalog, levels).orElseThrow(() -> Http.noSuchNamespace(levels));
    }

    /**
     * The namespace at {@code levels} as main or the ref sees it. Through a ref its id, parent and name are the cut's
     * and its properties are live: main's, or none for a namespace main has dropped since (D33, as amended
     * 2026-11-20).
     */
    Optional<NamespaceRecord> namespace(OnRef on, List<String> levels) {
        if (on.ref().isEmpty()) {
            return namespace(on.catalog(), levels);
        }
        return store.findRefNamespace(on.ref().get().id(), normalized(levels)).map(entry -> {
            Optional<NamespaceRecord> live = store.readNamespace(entry.id());
            return new NamespaceRecord(on.catalog().id(), entry.id(), entry.parentId(), entry.name(),
                    live.map(NamespaceRecord::properties).orElse(Map.of()),
                    live.map(NamespaceRecord::version).orElse(1L));
        });
    }

    NamespaceRecord existingNamespace(OnRef on, List<String> levels) {
        return namespace(on, levels).orElseThrow(() -> Http.noSuchNamespace(levels));
    }

    /** The active table or view of {@code kind} holding the name; an object of the other kind does not count. */
    Optional<CatalogObject> object(NamespaceRecord namespace, String name, CatalogObject.Kind kind) {
        return store.findObject(namespace.id(), Names.normalize(name)).filter(o -> o.kind() == kind);
    }

    /**
     * The table or view of {@code kind} holding the name in the namespace at {@code levels}, as main or the ref sees
     * it: through a ref, the one its cut names there, whatever main has done with it since (D33, as amended
     * 2026-11-20).
     */
    Optional<Seen> seen(OnRef on, NamespaceRecord namespace, List<String> levels, String name,
                        CatalogObject.Kind kind) {
        if (on.ref().isEmpty()) {
            return object(namespace, name, kind).map(object -> new Seen(object, Optional.empty()));
        }
        Ref ref = on.ref().get();
        return store.resolveOnRef(ref.id(), normalized(levels), Names.normalize(name))
                .filter(entry -> entry.kind() == RefEntry.of(kind))
                .map(entry -> new Seen(store.readObject(entry.id()).orElseThrow(() -> new IllegalStateException(
                        "object " + entry.id() + " in the cut of ref " + ref.id() + " has no record")),
                        entry.pointer()));
    }

    /**
     * The pointer a read through {@code on} serves: main's; on a branch, its own where it has one, else the one its
     * cut took; on a tag, its cut's (D33). A cut's pointer carries no projection: its metadata is read by location
     * and checked against its SHA-256, as main's is.
     */
    Optional<Pointer> pointer(OnRef on, Seen seen) {
        if (on.ref().isEmpty()) {
            return store.readPointer(seen.object().mainKey());
        }
        Ref ref = on.ref().get();
        Optional<Pointer> own = ref.kind() == Ref.Kind.BRANCH
                ? store.readPointer(ref.key(seen.object().id())) : Optional.empty();
        return own.or(() -> seen.cut().map(at -> new Pointer(at.metadataLocation(), at.version(), at.sha256(),
                new byte[0])));
    }

    /** The display names of the namespaces directly under {@code parentId}, on main or in the ref's cut. */
    Page<String> namespaceNames(OnRef on, UUID parentId, PageRequest request) {
        if (on.ref().isEmpty()) {
            return names(store.listNamespaces(parentId, request), ns -> ns.name().display());
        }
        return names(store.listRefNamespaces(on.ref().get().id(), parentId, request), e -> e.name().display());
    }

    /** The display names of the active tables or views of {@code kind} in a namespace, on main or in the ref's cut. */
    Page<String> objectNames(OnRef on, UUID namespaceId, CatalogObject.Kind kind, PageRequest request) {
        if (on.ref().isEmpty()) {
            return names(store.listObjects(namespaceId, kind, CatalogObject.State.ACTIVE, request),
                    o -> o.name().display());
        }
        return names(store.listRefObjects(on.ref().get().id(), namespaceId, kind, request), e -> e.name().display());
    }

    private static <T> Page<String> names(Page<T> page, Function<T, String> name) {
        return new Page<>(page.items().stream().map(name).toList(), page.nextToken());
    }

    private static List<String> normalized(List<String> levels) {
        return levels.stream().map(Names::normalize).toList();
    }

    /**
     * The levels of a namespace by id, outermost first, as an audit record names it; empty when it or a
     * namespace above it is gone.
     */
    List<String> levelsOf(CatalogRecord catalog, UUID namespaceId) {
        List<String> levels = new ArrayList<>();
        UUID next = namespaceId;
        while (!next.equals(catalog.id())) {
            Optional<NamespaceRecord> namespace = store.readNamespace(next);
            if (namespace.isEmpty()) {
                return List.of();
            }
            levels.addFirst(namespace.get().name().display());
            next = namespace.get().parentId();
        }
        return levels;
    }

    /**
     * A listing's answer. A client that sends a {@code pageToken} (an empty one asks for the first page) gets one
     * page of {@code pageSize}, or the default size. A client that sends none gets every item and no next token,
     * as the spec's PageToken requires of a server that pages; its {@code pageSize} is then ignored. A page token
     * that is not one of ours is the client's error.
     */
    <T> Page<T> listing(String pageToken, String pageSize, Function<PageRequest, Page<T>> list) {
        if (pageToken == null) {
            PageRequest request = PageRequest.first(settings.maxPageSize());
            Page<T> page = list.apply(request);
            List<T> items = new ArrayList<>(page.items());
            while (page.nextToken().isPresent()) {
                request = request.after(page);
                page = list.apply(request);
                items.addAll(page.items());
            }
            return new Page<>(items, Optional.empty());
        }
        PageRequest request = new PageRequest(pageToken.isEmpty() ? Optional.empty() : Optional.of(pageToken),
                Math.min(size(pageSize), settings.maxPageSize()));
        try {
            return list.apply(request);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
    }

    private int size(String pageSize) {
        if (pageSize == null || pageSize.isEmpty()) {
            return settings.defaultPageSize();
        }
        int size;
        try {
            size = Integer.parseInt(pageSize);
        } catch (NumberFormatException e) {
            throw CatalogError.badRequest("Invalid page size: " + pageSize);
        }
        if (size < 1) {
            throw CatalogError.badRequest("Invalid page size: " + pageSize);
        }
        return size;
    }
}
