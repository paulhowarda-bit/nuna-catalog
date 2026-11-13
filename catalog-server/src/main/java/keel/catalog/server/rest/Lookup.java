package keel.catalog.server.rest;

import keel.catalog.core.CatalogError;
import keel.catalog.core.Names;
import keel.catalog.iceberg.RestRequest;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    CatalogRecord catalog(String prefix) {
        return store.findCatalog(Names.normalize(prefix)).orElseThrow(
                () -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
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

    /** A page request from the client's {@code pageToken} and {@code pageSize}; an empty token is the first page. */
    PageRequest page(String pageToken, String pageSize) {
        int size = settings.defaultPageSize();
        if (pageSize != null && !pageSize.isEmpty()) {
            try {
                size = Integer.parseInt(pageSize);
            } catch (NumberFormatException e) {
                throw CatalogError.badRequest("Invalid page size: " + pageSize);
            }
            if (size < 1) {
                throw CatalogError.badRequest("Invalid page size: " + pageSize);
            }
        }
        Optional<String> token = pageToken == null || pageToken.isEmpty() ? Optional.empty() : Optional.of(pageToken);
        return new PageRequest(token, Math.min(size, settings.maxPageSize()));
    }

    /** Runs a listing; a page token that is not one of ours is the client's error. */
    static <T> T listing(java.util.function.Supplier<T> list) {
        try {
            return list.get();
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
    }
}
