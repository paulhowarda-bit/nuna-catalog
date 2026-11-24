package nuna.catalog.server.governance;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The governance of a whole catalog, read in one walk (D45, item 128): every active namespace, table and view, then
 * their attributes and holds in batches, rather than one governance call per table. It counts what the console's
 * governance summary shows and lists the holds and DR freezes standing now. A table is closed until classified as
 * the authorizer closes it (D27): a level on or above it is regulated, and it has no classification of its own; an
 * inherited classification labels it but does not open it.
 */
final class GovernanceSummary {

    private static final int PAGE = 500;
    private static final int BATCH = 500;

    /** A catalog, namespace, table or view found by the walk: its namespace levels and name, and the level above. */
    private record Level(Securable securable, List<String> path, Securable parent) {
    }

    private final CatalogRecord catalog;
    /** Every level, the catalog first, each namespace before what is in it. */
    private final Map<Securable, Level> levels;
    private final List<GovernanceAttribute> attributes;
    private final List<LegalHold> holds;
    /** Each level's own attributes, by kind. */
    private final Map<Securable, Map<GovernanceAttribute.Kind, GovernanceAttribute>> own = new HashMap<>();
    private final Set<Securable> heldLevels = new HashSet<>();

    private GovernanceSummary(CatalogRecord catalog, Map<Securable, Level> levels,
                              List<GovernanceAttribute> attributes, List<LegalHold> holds) {
        this.catalog = catalog;
        this.levels = levels;
        this.attributes = attributes;
        this.holds = holds;
        attributes.forEach(a -> own.computeIfAbsent(a.target(), t -> new EnumMap<>(GovernanceAttribute.Kind.class))
                .put(a.kind(), a));
        holds.forEach(hold -> heldLevels.add(hold.target()));
    }

    static GovernanceSummary read(CatalogStore store, CatalogRecord catalog) {
        Map<Securable, Level> levels = new LinkedHashMap<>();
        Securable root = new Securable(Securable.Kind.CATALOG, catalog.id());
        levels.put(root, new Level(root, List.of(), null));
        walk(store, root, List.of(), levels);
        List<Securable> all = List.copyOf(levels.keySet());
        List<GovernanceAttribute> attributes = new ArrayList<>();
        List<LegalHold> holds = new ArrayList<>();
        for (int from = 0; from < all.size(); from += BATCH) {
            Set<Securable> batch = Set.copyOf(all.subList(from, Math.min(all.size(), from + BATCH)));
            attributes.addAll(store.attributesOf(batch));
            holds.addAll(store.holdsOn(batch));
        }
        return new GovernanceSummary(catalog, levels, attributes, holds);
    }

    private static void walk(CatalogStore store, Securable parent, List<String> path, Map<Securable, Level> levels) {
        for (NamespaceRecord namespace : all(page -> store.listNamespaces(parent.id(), page))) {
            Securable securable = new Securable(Securable.Kind.NAMESPACE, namespace.id());
            List<String> levelPath = append(path, namespace.name().display());
            levels.put(securable, new Level(securable, levelPath, parent));
            for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
                for (CatalogObject object : all(page -> store.listObjects(namespace.id(), kind,
                        CatalogObject.State.ACTIVE, page))) {
                    levels.put(object.securable(), new Level(object.securable(),
                            append(levelPath, object.name().display()), securable));
                }
            }
            walk(store, securable, levelPath, levels);
        }
    }

    /**
     * The counts: tables, those with a classification (their own or inherited, as their labels carry it), those
     * closed until classified, held (a hold on them or above) and frozen (a DR freeze on them or above); the
     * standing holds and freezes; each regulated area, a level whose own regulated flag is true, with the tables
     * it covers, how many of those are closed, and the flag's provenance, setter and time; and every closed table.
     */
    ObjectNode summary() {
        int tables = 0;
        int classified = 0;
        int held = 0;
        int frozen = 0;
        List<Level> closed = new ArrayList<>();
        Map<Securable, int[]> regulated = new LinkedHashMap<>();
        for (Level level : levels.values()) {
            if (own(level.securable(), GovernanceAttribute.Kind.REGULATED).filter(GovernanceSummary::isTrue)
                    .isPresent()) {
                regulated.put(level.securable(), new int[2]);
            }
        }
        for (Level level : levels.values()) {
            if (level.securable().kind() != Securable.Kind.TABLE) {
                continue;
            }
            tables++;
            List<Securable> chain = chain(level);
            if (chain.stream().anyMatch(on -> own(on, GovernanceAttribute.Kind.CLASSIFICATION).isPresent())) {
                classified++;
            }
            if (chain.stream().anyMatch(heldLevels::contains)) {
                held++;
            }
            if (chain.stream().anyMatch(on -> own(on, GovernanceAttribute.Kind.DR_FREEZE).isPresent())) {
                frozen++;
            }
            boolean isClosed = chain.stream().anyMatch(regulated::containsKey)
                    && own(level.securable(), GovernanceAttribute.Kind.CLASSIFICATION).isEmpty();
            if (isClosed) {
                closed.add(level);
            }
            for (Securable on : chain) {
                int[] counts = regulated.get(on);
                if (counts != null) {
                    counts[0]++;
                    counts[1] += isClosed ? 1 : 0;
                }
            }
        }
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", catalog.name());
        ObjectNode counts = body.putObject("tables");
        counts.put("total", tables);
        counts.put("classified", classified);
        counts.put("closed", closed.size());
        counts.put("held", held);
        counts.put("frozen", frozen);
        body.put("holds", holds.size());
        body.put("freezes", freezes().size());
        ArrayNode areas = body.putArray("regulated");
        regulated.forEach((on, area) -> {
            ObjectNode node = areas.addObject();
            node.set("on", on(levels.get(on)));
            node.put("tables", area[0]);
            node.put("closed", area[1]);
            // Why the area is regulated and who said so, as its flag records them (Atelier's re-walk, backlog 213).
            GovernanceAttribute flag = own(on, GovernanceAttribute.Kind.REGULATED).orElseThrow();
            node.put("provenance", flag.provenance());
            node.put("set-by", flag.setBy());
            node.put("set-at", flag.setAt().toString());
        });
        ArrayNode closedNode = body.putArray("closed");
        closed.forEach(level -> closedNode.add(on(level)));
        return body;
    }

    /** The holds and DR freezes standing now, the newest placed first. */
    ArrayNode standing() {
        record Standing(Instant at, ObjectNode node) {
        }
        List<Standing> standing = new ArrayList<>();
        for (LegalHold hold : holds) {
            ObjectNode node = Management.JSON.createObjectNode();
            node.put("kind", "hold");
            node.put("id", hold.id().toString());
            node.set("on", on(levels.get(hold.target())));
            node.put("reason", hold.reason());
            node.put("placed-by", hold.placedBy());
            node.put("placed-at", hold.placedAt().toString());
            standing.add(new Standing(hold.placedAt(), node));
        }
        for (GovernanceAttribute freeze : freezes()) {
            ObjectNode node = Management.JSON.createObjectNode();
            node.put("kind", "freeze");
            node.set("on", on(levels.get(freeze.target())));
            node.put("reason", freeze.value());
            node.put("placed-by", freeze.setBy());
            node.put("placed-at", freeze.setAt().toString());
            standing.add(new Standing(freeze.setAt(), node));
        }
        ArrayNode list = Management.JSON.createArrayNode();
        standing.stream().sorted(Comparator.comparing(Standing::at).reversed()).forEach(s -> list.add(s.node()));
        return list;
    }

    private List<GovernanceAttribute> freezes() {
        return attributes.stream().filter(a -> a.kind() == GovernanceAttribute.Kind.DR_FREEZE).toList();
    }

    /** The level, then every level above it up to the catalog. */
    private List<Securable> chain(Level level) {
        List<Securable> chain = new ArrayList<>();
        for (Level at = level; at != null; at = at.parent() == null ? null : levels.get(at.parent())) {
            chain.add(at.securable());
        }
        return chain;
    }

    private Optional<GovernanceAttribute> own(Securable on, GovernanceAttribute.Kind kind) {
        return Optional.ofNullable(this.own.getOrDefault(on, Map.of()).get(kind));
    }

    private static boolean isTrue(GovernanceAttribute attribute) {
        return "true".equalsIgnoreCase(attribute.value());
    }

    /** A level as the console names it: its kind, id, and namespace levels and name, empty for the catalog. */
    private static ObjectNode on(Level level) {
        ObjectNode node = GovernanceRoutes.securable(level.securable());
        ArrayNode path = node.putArray("path");
        level.path().forEach(path::add);
        return node;
    }

    /**
     * {@link #on} for a level the walk found; one no longer in the catalog, such as a namespace dropped after a hold
     * on it was released, by its kind and id with a null path (item 133).
     */
    ObjectNode on(Securable securable) {
        Level level = levels.get(securable);
        if (level != null) {
            return on(level);
        }
        ObjectNode node = GovernanceRoutes.securable(securable);
        node.putNull("path");
        return node;
    }

    private static List<String> append(List<String> path, String name) {
        List<String> appended = new ArrayList<>(path);
        appended.add(name);
        return List.copyOf(appended);
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
