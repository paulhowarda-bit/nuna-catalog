package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Resolves what a route names: the catalog its prefix names, namespaces, tables and views (D8.6, D28). */
@Component
class Lookup {

    private final CatalogStore store;
    private final RestSettings settings;

    Lookup(CatalogStore store, RestSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** The catalog the prefix names, which also labels the request's meters. */
    CatalogRecord catalog(String prefix) {
        CatalogRecord catalog = store.findCatalog(Names.normalize(prefix)).orElseThrow(
                () -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
        RequestMetrics.catalog(catalog.name());
        return catalog;
    }

    /** The namespace path segment: levels joined by 0x1F. */
    static List<String> levels(String encoded) {
        return RestRequest.namespaceLevels(encoded);
    }

    Optional<NamespaceRecord> namespace(CatalogRecord catalog, List<String> levels) {
        return store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList());
    }

    NamespaceRecord existingNamespace(CatalogRecord catalog, List<String> levels) {
        return namespace(catalog, levels).orElseThrow(() -> Http.noSuchNamespace(levels));
    }

    /** The active table or view of {@code kind} holding the name; an object of the other kind does not count. */
    Optional<CatalogObject> object(NamespaceRecord namespace, String name, CatalogObject.Kind kind) {
        return store.findObject(namespace.id(), Names.normalize(name)).filter(o -> o.kind() == kind);
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
