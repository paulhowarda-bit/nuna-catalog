package nuna.catalog.core.commit;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Securable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Holds and attributes for one securable and everything above it: a table or view, every namespace
 * above it and its catalog, since a hold or flag on any of them covers everything beneath (D24,
 * D26), and the nearest level that sets an attribute wins. Each is read from the store the first
 * time it is asked for, in one read for every level. Not safe for concurrent use.
 */
public final class GovernanceChain implements CommitRule.Governance {

    /** The attributes sent as labels, by their wire names. */
    public static final Map<GovernanceAttribute.Kind, String> LABELS = Map.of(
            GovernanceAttribute.Kind.CLASSIFICATION, "classification",
            GovernanceAttribute.Kind.OWNER, "owner",
            GovernanceAttribute.Kind.RETENTION_CLASS, "retention-class");

    private final CatalogStore store;
    private final List<Securable> levels;
    private List<LegalHold> holds;
    private List<GovernanceAttribute> attributes;

    private GovernanceChain(CatalogStore store, List<Securable> levels) {
        this.store = store;
        this.levels = List.copyOf(levels);
    }

    /** The object, its namespaces from the innermost out, then its catalog. */
    public static GovernanceChain of(CatalogStore store, CatalogObject object) {
        List<Securable> levels = new ArrayList<>();
        levels.add(object.securable());
        levels.addAll(namespaces(store, object.catalogId(), object.namespaceId()));
        levels.add(new Securable(Securable.Kind.CATALOG, object.catalogId()));
        return new GovernanceChain(store, levels);
    }

    /** The namespace, the namespaces above it, then its catalog. */
    public static GovernanceChain of(CatalogStore store, NamespaceRecord namespace) {
        List<Securable> levels = new ArrayList<>(namespaces(store, namespace.catalogId(), namespace.id()));
        levels.add(new Securable(Securable.Kind.CATALOG, namespace.catalogId()));
        return new GovernanceChain(store, levels);
    }

    /** The catalog alone. */
    public static GovernanceChain ofCatalog(CatalogStore store, UUID catalogId) {
        return new GovernanceChain(store, List.of(new Securable(Securable.Kind.CATALOG, catalogId)));
    }

    /** The levels, nearest first. */
    public List<Securable> levels() {
        return levels;
    }

    @Override
    public boolean held() {
        return !holds().isEmpty();
    }

    /** Every hold on any level. */
    public List<LegalHold> holds() {
        if (holds == null) {
            holds = List.copyOf(store.holdsOn(Set.copyOf(levels)));
        }
        return holds;
    }

    @Override
    public boolean regulated() {
        return attributes().stream()
                .anyMatch(a -> a.kind() == GovernanceAttribute.Kind.REGULATED && "true".equalsIgnoreCase(a.value()));
    }

    @Override
    public Optional<GovernanceAttribute> nearest(GovernanceAttribute.Kind kind) {
        return attributes().stream().filter(a -> a.kind() == kind).findFirst();
    }

    /** Every attribute on any level, nearest level first. */
    public List<GovernanceAttribute> attributes() {
        if (attributes == null) {
            List<GovernanceAttribute> all = store.attributesOf(Set.copyOf(levels));
            List<GovernanceAttribute> ordered = new ArrayList<>();
            for (Securable level : levels) {
                all.stream().filter(a -> a.target().equals(level)).forEach(ordered::add);
            }
            attributes = List.copyOf(ordered);
        }
        return attributes;
    }

    /**
     * The object labels a load sends (D27, amended 2026-11-12): the effective classification, owner and
     * retention class, each the nearest level's value, by its wire name. Provenance, the regulated and
     * row/column flags and every other attribute stay in the catalog.
     */
    public Map<String, String> labels() {
        Map<String, String> labels = new TreeMap<>();
        for (GovernanceAttribute attribute : attributes()) {
            String label = LABELS.get(attribute.kind());
            if (label != null) {
                labels.putIfAbsent(label, attribute.value());
            }
        }
        return labels;
    }

    /** The namespace and those above it, innermost first, stopping at the catalog or a namespace that is gone. */
    private static List<Securable> namespaces(CatalogStore store, UUID catalogId, UUID namespaceId) {
        Set<Securable> found = new LinkedHashSet<>();
        Set<UUID> seen = new HashSet<>();
        UUID next = namespaceId;
        while (!next.equals(catalogId) && seen.add(next)) {
            found.add(new Securable(Securable.Kind.NAMESPACE, next));
            NamespaceRecord namespace = store.readNamespace(next).orElse(null);
            if (namespace == null) {
                break;
            }
            next = namespace.parentId();
        }
        return new ArrayList<>(found);
    }
}
