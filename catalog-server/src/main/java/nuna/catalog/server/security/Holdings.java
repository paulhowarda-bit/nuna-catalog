package nuna.catalog.server.security;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether a caller holds something on a namespace, table or view, which decides how a refusal there is answered
 * (D18, amended 2026-11-19): a caller that holds something may learn the name exists and is refused with 403; any
 * other is answered as if the name did not exist. It is the Iceberg routes' rule (item 14) and, for a namespace,
 * the catalog list's (item 20); the management routes ask it here (item 73). Read only for a refusal.
 */
@Component
public class Holdings {

    private final Authorizer authorizer;
    private final CatalogStore store;
    private final RestSettings settings;

    public Holdings(Authorizer authorizer, CatalogStore store, RestSettings settings) {
        this.authorizer = authorizer;
        this.store = store;
        this.settings = settings;
    }

    /**
     * What decides the answer to a refusal on the target: on a table or view, {@link #onOrAbove}; on a namespace,
     * {@link #onAboveOrIn}. On the catalog itself always true, so a refusal there stays 403 (item 68).
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
