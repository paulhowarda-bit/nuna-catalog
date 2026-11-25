package nuna.catalog.server.governance;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.GovernanceChange;
import nuna.catalog.core.Names;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes the row/column-policy flags set above a table (D20, backlog 239). Only a table's own flag is enforced, and
 * the set-flag route now refuses one anywhere else, but before it did a flag could be set on a catalog, namespace or
 * view, where it read as protection that was not there. At startup, once the declared catalogs exist, the server walks
 * each one's catalog, namespaces and views (never its tables), removes every such flag with its audit record in the
 * same write, as the remove route does, and logs a warning naming it. A second server starting at once finds it gone;
 * a removal that fails is logged and tried again at the next start, and does not stop this one: the flag it leaves is
 * enforced nowhere either way.
 */
@Component
class FlagsAboveTables {

    private static final Logger LOG = LoggerFactory.getLogger(FlagsAboveTables.class);
    /** Who removes them: the server itself, at startup. */
    static final String BY = "nuna-server";
    static final Actor SERVER = new Actor(BY, null, "catalog-server", "startup");
    private static final String ATTRIBUTE = "row-column-policy";
    private static final int PAGE = 500;
    private static final int BATCH = 500;

    /** Takes the catalogs' storage, which declares the catalogs, so they exist first. */
    FlagsAboveTables(StorageSettings settings, CatalogStorage storage, CatalogStore store, Management management,
                     Clock clock) {
        for (String name : settings.catalogs().keySet()) {
            try {
                store.findCatalog(Names.normalize(name)).ifPresent(catalog -> remove(store, management, clock,
                        catalog));
            } catch (RuntimeException e) {
                LOG.warn("Could not look for row-column-policy flags above the tables of catalog {}: {}; the next"
                        + " start looks again", name, e.toString());
            }
        }
    }

    /** Removes every row/column-policy flag in {@code catalog} that is not on a table; returns where each was. */
    static List<nuna.catalog.audit.Securable> remove(CatalogStore store, Management management, Clock clock,
                                                      CatalogRecord catalog) {
        Map<Securable, nuna.catalog.audit.Securable> levels = levels(store, catalog);
        List<Securable> all = List.copyOf(levels.keySet());
        List<GovernanceAttribute> flags = new ArrayList<>();
        for (int from = 0; from < all.size(); from += BATCH) {
            store.attributesOf(Set.copyOf(all.subList(from, Math.min(all.size(), from + BATCH)))).stream()
                    .filter(a -> a.kind() == GovernanceAttribute.Kind.ROW_COLUMN_POLICY)
                    .filter(a -> a.target().kind() != Securable.Kind.TABLE)
                    .forEach(flags::add);
        }
        List<nuna.catalog.audit.Securable> removed = new ArrayList<>();
        for (GovernanceAttribute flag : flags) {
            nuna.catalog.audit.Securable on = levels.get(flag.target());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            WriteResult result = store.removeAttribute(flag.target(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, BY,
                    management.change(now, new GovernanceChange(GovernanceChange.Action.REMOVED, on, ATTRIBUTE, null,
                            null, SERVER)));
            String where = on.kind().name().toLowerCase(Locale.ROOT) + " " + (on.path().isEmpty()
                    ? catalog.name() : catalog.name() + "." + String.join(".", on.path()));
            switch (result) {
                case WriteResult.Applied applied -> {
                    LOG.warn("Removed the row-column-policy flag ({}, set by {} at {}, provenance {}) from {}: the flag"
                            + " goes on a table, and only a table's own is enforced (backlog 239)", flag.value(),
                            flag.setBy(), flag.setAt(), flag.provenance(), where);
                    removed.add(on);
                }
                case WriteResult.Missing missing -> {
                    // Another server starting at the same time removed it first.
                }
                default -> LOG.warn("Could not remove the row-column-policy flag from {}: the store answered {}",
                        where, result);
            }
        }
        return removed;
    }

    /** The catalog, each namespace and each view in it, as audit records name them. */
    private static Map<Securable, nuna.catalog.audit.Securable> levels(CatalogStore store, CatalogRecord catalog) {
        Map<Securable, nuna.catalog.audit.Securable> levels = new LinkedHashMap<>();
        levels.put(new Securable(Securable.Kind.CATALOG, catalog.id()), new nuna.catalog.audit.Securable(
                nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(), catalog.name(), List.of()));
        walk(store, catalog, catalog.id(), List.of(), levels);
        return levels;
    }

    private static void walk(CatalogStore store, CatalogRecord catalog, UUID parent, List<String> path,
                             Map<Securable, nuna.catalog.audit.Securable> levels) {
        for (NamespaceRecord namespace : all(page -> store.listNamespaces(parent, page))) {
            List<String> levelPath = new ArrayList<>(path);
            levelPath.add(namespace.name().display());
            levels.put(new Securable(Securable.Kind.NAMESPACE, namespace.id()), new nuna.catalog.audit.Securable(
                    nuna.catalog.audit.Securable.Kind.NAMESPACE, namespace.id(), catalog.name(),
                    List.copyOf(levelPath)));
            for (CatalogObject view : all(page -> store.listObjects(namespace.id(), CatalogObject.Kind.VIEW,
                    CatalogObject.State.ACTIVE, page))) {
                levels.put(view.securable(), Management.audit(catalog, levelPath, view.name().display(), view));
            }
            walk(store, catalog, namespace.id(), levelPath, levels);
        }
    }

    private static <T> List<T> all(Function<PageRequest, Page<T>> list) {
        List<T> items = new ArrayList<>();
        PageRequest request = PageRequest.first(PAGE);
        while (true) {
            Page<T> page = list.apply(request);
            items.addAll(page.items());
            if (page.nextToken().isEmpty()) {
                return items;
            }
            request = request.after(page);
        }
    }
}
