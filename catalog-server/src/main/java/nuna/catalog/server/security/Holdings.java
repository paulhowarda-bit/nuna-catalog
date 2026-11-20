package nuna.catalog.server.security;

import nuna.catalog.audit.Actor;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether a caller holds something on a catalog, namespace, table or view, which decides how a refusal there is
 * answered (D18, amended 2026-11-19 and 2026-11-20): a caller that holds something may learn the name exists and is
 * refused with 403; any other is answered as if the name did not exist. It is the Iceberg routes' rule (item 14)
 * and, for a namespace and a catalog, the catalog list's (item 20); the management routes ask it here (item 73), and
 * every route that names a catalog finds it through {@link #catalog} (item 68).
 */
@Component
public class Holdings {

    private final Authorizer authorizer;
    private final CatalogStore store;
    private final RestSettings settings;
    private final Separation separation;
    private final Denials denials;

    public Holdings(Authorizer authorizer, CatalogStore store, RestSettings settings, Separation separation,
                    Denials denials) {
        this.authorizer = authorizer;
        this.store = store;
        this.settings = settings;
        this.separation = separation;
        this.denials = denials;
    }

    /**
     * The catalog {@code name} names, for a caller that holds something on it ({@link #inCatalog}). Any other caller
     * gets what a name no catalog has gets, 404 NoSuchWarehouseException echoing the name as given, so it cannot
     * tell whether the catalog exists (D18, amended 2026-11-20, item 68); that refusal is audited first, as every
     * refusal is, naming the catalog.
     *
     * @param operation what the caller asked, as its refusal is audited
     * @param privilege what the operation needs, or null when it needs no one privilege
     */
    public CatalogRecord catalog(String name, Caller caller, Actor actor, String operation, Privilege privilege) {
        CatalogError missing = CatalogError.noSuchWarehouse("The given warehouse does not exist: " + name);
        CatalogRecord catalog = store.findCatalog(Names.normalize(name)).orElseThrow(() -> missing);
        if (!inCatalog(caller, catalog.id())) {
            denials.refuse(actor, operation, new nuna.catalog.audit.Securable(
                            nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(), catalog.name(), List.of()),
                    privilege == null ? null : privilege.wireName(), "no_privilege",
                    caller.subject().id() + " holds nothing in catalog " + catalog.name());
            throw missing;
        }
        return catalog;
    }

    /**
     * Whether the caller holds something on the catalog, as the catalog list counts it (item 20): one of the
     * subject's groups (the end user's, when a trusted engine acts for one) holds a grant, any privilege, on the
     * catalog or on anything in it, the grants its own list by principal there shows. A grant admin holds
     * {@code manage-grants} on every catalog; a platform operator holds no grant but runs the deployment, and sees
     * every catalog ("Catalogs, not objects"). So does a registered trusted engine acting as itself, which reads a
     * catalog's config with its own token and reaches what is in it with its users' (item 84); acting for an end
     * user, it sees what that user holds.
     */
    public boolean inCatalog(Caller caller, UUID catalogId) {
        if (caller.trustedEngine() && caller.endUser().isEmpty()) {
            return true;
        }
        Set<String> groups = caller.subject().groups();
        if (groups.stream().anyMatch(group -> separation.grantAdminGroups().contains(group)
                || separation.platformOperatorGroups().contains(group))) {
            return true;
        }
        return !groups.isEmpty()
                && !store.listGrantsOfGroups(catalogId, groups, PageRequest.first(1)).items().isEmpty();
    }

    /**
     * What decides the answer to a refusal on the target: on a table or view, {@link #onOrAbove}; on a namespace,
     * {@link #onAboveOrIn}. On the catalog itself always true, so a refusal there is 403: a caller asked about a
     * catalog found it through {@link #catalog}, so it holds something there.
     */
    public boolean something(Caller caller, Target target) {
        return switch (target) {
            case Target.Catalog catalog -> true;
            case Target.Namespace namespace -> onAboveOrIn(caller, namespace.catalogId(), namespace.namespaceId());
            case Target.TableOrView object -> onOrAbove(caller, object);
        };
    }

    /**
     * Whether one of the subject's groups (the end user's, when a trusted engine acts for one) holds a privilege
     * on the target through a grant on it or above it, as the authorizer reads grants. A grant admin holds
     * {@code manage-grants} on every catalog; a platform operator holds no grant.
     */
    public boolean onOrAbove(Caller caller, Target target) {
        return !authorizer.privileges(caller.subject().groups(), target).isEmpty();
    }

    /**
     * {@link #onOrAbove} for a namespace, or a grant to one of the subject's groups on anything in it: a namespace
     * below it, or a table or view in it or below. The catalog list counts a catalog the same way (item 20). Read
     * from the groups' own grants in the catalog.
     */
    public boolean onAboveOrIn(Caller caller, UUID catalogId, UUID namespaceId) {
        if (onOrAbove(caller, new Target.Namespace(catalogId, namespaceId))) {
            return true;
        }
        Set<String> groups = caller.subject().groups();
        if (groups.isEmpty()) {
            return false;
        }
        Map<UUID, UUID> parents = new HashMap<>();
        PageRequest page = PageRequest.first(settings.maxPageSize());
        while (true) {
            Page<GrantRecord> grants = store.listGrantsOfGroups(catalogId, groups, page);
            for (GrantRecord grant : grants.items()) {
                if (below(grant.grant().securable(), catalogId, namespaceId, parents)) {
                    return true;
                }
            }
            if (grants.nextToken().isEmpty()) {
                return false;
            }
            page = page.after(grants);
        }
    }

    /** Whether the securable lies below the namespace: in it, or in a namespace below it. */
    private boolean below(Securable securable, UUID catalogId, UUID namespaceId, Map<UUID, UUID> parents) {
        UUID next = switch (securable.kind()) {
            case CATALOG -> catalogId;
            case NAMESPACE -> parent(securable.id(), catalogId, parents);
            case TABLE, VIEW -> store.readObject(securable.id()).map(CatalogObject::namespaceId).orElse(catalogId);
        };
        while (!next.equals(catalogId)) {
            if (next.equals(namespaceId)) {
                return true;
            }
            next = parent(next, catalogId, parents);
        }
        return false;
    }

    /** A namespace's parent; the catalog for one that is gone, whose chain stops there as the authorizer's does. */
    private UUID parent(UUID namespaceId, UUID catalogId, Map<UUID, UUID> parents) {
        return parents.computeIfAbsent(namespaceId,
                id -> store.readNamespace(id).map(NamespaceRecord::parentId).orElse(catalogId));
    }
}
