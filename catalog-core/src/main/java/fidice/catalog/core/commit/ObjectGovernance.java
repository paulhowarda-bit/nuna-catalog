package fidice.catalog.core.commit;

import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.GovernanceAttribute;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Securable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Holds and the regulated flag for one object, read from the store the first time a rule asks: on
 * the object, every namespace above it and its catalog, since a hold or flag on any of them covers
 * everything beneath (D24, D26).
 */
final class ObjectGovernance implements CommitRule.Governance {

    private final CatalogStore store;
    private final CatalogObject object;
    private Set<Securable> chain;
    private Boolean held;
    private Boolean regulated;

    ObjectGovernance(CatalogStore store, CatalogObject object) {
        this.store = store;
        this.object = object;
    }

    @Override
    public boolean held() {
        if (held == null) {
            held = !store.holdsOn(chain()).isEmpty();
        }
        return held;
    }

    @Override
    public boolean regulated() {
        if (regulated == null) {
            regulated = store.attributesOf(chain()).stream()
                    .anyMatch(a -> a.kind() == GovernanceAttribute.Kind.REGULATED && "true".equalsIgnoreCase(a.value()));
        }
        return regulated;
    }

    private Set<Securable> chain() {
        if (chain == null) {
            Set<Securable> securables = new HashSet<>();
            securables.add(object.securable());
            securables.add(new Securable(Securable.Kind.CATALOG, object.catalogId()));
            UUID next = object.namespaceId();
            while (!next.equals(object.catalogId()) && securables.add(new Securable(Securable.Kind.NAMESPACE, next))) {
                NamespaceRecord namespace = store.readNamespace(next).orElse(null);
                if (namespace == null) {
                    break;
                }
                next = namespace.parentId();
            }
            chain = Set.copyOf(securables);
        }
        return chain;
    }
}
