package fidice.catalog.server.security;

import fidice.catalog.core.CatalogError;
import fidice.catalog.core.Names;
import fidice.catalog.security.Authorizer.Target;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Securable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Securables as the management API names them, {@code {"type":"table","namespace":["a","b"],"name":"t"}},
 * resolved to their stored ids, and stored ids rendered back as names.
 */
final class Securables {

    /** A securable as a request names it; {@code namespace} and {@code name} as the type needs. */
    record Spec(String type, List<String> namespace, String name) {
    }

    /** A named securable: its stored form, its authorization target and its audit form. */
    record Resolved(Securable stored, Target target, fidice.catalog.audit.Securable named) {
    }

    private final CatalogStore store;
    private final CatalogRecord catalog;
    private final Map<UUID, List<String>> namespacePaths = new HashMap<>();

    Securables(CatalogStore store, CatalogRecord catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    static CatalogRecord catalog(CatalogStore store, String name) {
        return store.findCatalog(Names.normalize(name)).orElseThrow(
                () -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + name));
    }

    Resolved catalogItself() {
        return new Resolved(new Securable(Securable.Kind.CATALOG, catalog.id()), new Target.Catalog(catalog.id()),
                new fidice.catalog.audit.Securable(fidice.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                        catalog.name(), List.of()));
    }

    Resolved resolve(Spec spec) {
        if (spec == null || spec.type() == null) {
            throw CatalogError.badRequest("A securable needs a type: catalog, namespace, table or view");
        }
        List<String> levels = spec.namespace() == null ? List.of() : spec.namespace();
        return switch (spec.type().toLowerCase(Locale.ROOT)) {
            case "catalog" -> catalogItself();
            case "namespace" -> {
                NamespaceRecord namespace = namespace(levels);
                yield new Resolved(new Securable(Securable.Kind.NAMESPACE, namespace.id()), Target.of(namespace),
                        new fidice.catalog.audit.Securable(fidice.catalog.audit.Securable.Kind.NAMESPACE,
                                namespace.id(), catalog.name(), levels));
            }
            case "table", "view" -> {
                CatalogObject.Kind kind = spec.type().equalsIgnoreCase("table")
                        ? CatalogObject.Kind.TABLE : CatalogObject.Kind.VIEW;
                if (spec.name() == null || spec.name().isEmpty()) {
                    throw CatalogError.badRequest("A " + spec.type() + " needs a name");
                }
                CatalogObject object = store.findObject(namespace(levels).id(), Names.normalize(spec.name()))
                        .filter(o -> o.kind() == kind)
                        .orElseThrow(() -> CatalogError.of(404, kind == CatalogObject.Kind.TABLE
                                ? "NoSuchTableException" : "NoSuchViewException",
                                spec.type() + " does not exist: " + dotted(levels) + "." + spec.name()));
                List<String> path = new ArrayList<>(levels);
                path.add(spec.name());
                yield new Resolved(object.securable(), Target.of(object),
                        new fidice.catalog.audit.Securable(kind == CatalogObject.Kind.TABLE
                                ? fidice.catalog.audit.Securable.Kind.TABLE : fidice.catalog.audit.Securable.Kind.VIEW,
                                object.id(), catalog.name(), path));
            }
            default -> throw CatalogError.badRequest("Unknown securable type '" + spec.type()
                    + "'; expected catalog, namespace, table or view");
        };
    }

    /** A stored securable as the API names it, with its id; a name that can no longer be read is left out. */
    Map<String, Object> render(Securable securable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", securable.kind().name().toLowerCase(Locale.ROOT));
        out.put("id", securable.id().toString());
        switch (securable.kind()) {
            case CATALOG -> {
            }
            case NAMESPACE -> path(securable.id()).ifPresent(path -> out.put("namespace", path));
            case TABLE, VIEW -> store.readObject(securable.id()).ifPresent(object -> {
                path(object.namespaceId()).ifPresent(path -> out.put("namespace", path));
                out.put("name", object.name().display());
                out.put("state", object.state().name().toLowerCase(Locale.ROOT));
            });
        }
        return out;
    }

    private NamespaceRecord namespace(List<String> levels) {
        if (levels.isEmpty()) {
            throw CatalogError.badRequest("A namespace, table or view needs its namespace levels");
        }
        return store.findNamespace(catalog.id(), levels.stream().map(Names::normalize).toList()).orElseThrow(
                () -> CatalogError.of(404, "NoSuchNamespaceException", "Namespace does not exist: " + dotted(levels)));
    }

    private Optional<List<String>> path(UUID namespaceId) {
        if (namespacePaths.containsKey(namespaceId)) {
            return Optional.of(namespacePaths.get(namespaceId));
        }
        List<String> levels = new ArrayList<>();
        UUID next = namespaceId;
        while (!next.equals(catalog.id())) {
            Optional<NamespaceRecord> record = store.readNamespace(next);
            if (record.isEmpty()) {
                return Optional.empty();
            }
            levels.add(record.get().name().display());
            next = record.get().parentId();
        }
        Collections.reverse(levels);
        namespacePaths.put(namespaceId, List.copyOf(levels));
        return Optional.of(List.copyOf(levels));
    }

    private static String dotted(List<String> levels) {
        return String.join(".", levels);
    }
}
