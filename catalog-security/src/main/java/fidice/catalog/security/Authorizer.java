package fidice.catalog.security;

import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.GovernanceAttribute;
import fidice.catalog.store.GovernanceStore;
import fidice.catalog.store.GrantRecord;
import fidice.catalog.store.GrantStore;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.NamespaceStore;
import fidice.catalog.store.Securable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a caller may perform an operation on a securable (plan 2.2 step 3; D17, D18, D20, D27).
 *
 * <p>Authorization is for {@link Caller#subject()}: the end user when a trusted engine acts for one.
 * A subject holds a privilege on a target when one of its groups was granted a privilege that implies
 * it on the target or on anything above it: the catalog, then each namespace down to the target
 * (inheritance covers objects created later). The deployment's grant-admin groups hold
 * {@code manage-grants} on every catalog. There is no DENY and no superuser.
 *
 * <p>Three governance rules then apply, in this order, to tables and views:
 * <ol>
 *   <li>D27: a table without its own classification, in a regulated area (the table, a namespace above
 *       it or its catalog is flagged regulated), is refused everything but {@code describe};</li>
 *   <li>D20: a table flagged with a row/column policy gives storage access (load, credentials, commit)
 *       only to trusted engines, which enforce the policy;</li>
 *   <li>D17: with the switch on, a trusted engine acting as itself, with no end user, may not write to
 *       anything in a regulated area.</li>
 * </ol>
 *
 * <p>Every request in a batch is decided with one grant read and one governance read for the whole
 * batch. Namespaces never move, so a namespace's parent is cached for good.
 */
public final class Authorizer {

    /** One operation as authorization sees it. {@code storageAccess} marks routes that hand out file access. */
    public record Operation(String name, Privilege privilege, boolean storageAccess) {

        public Operation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(privilege, "privilege");
        }
    }

    /** What an operation is on. The chain of securables above it is resolved by the authorizer. */
    public sealed interface Target {

        UUID catalogId();

        record Catalog(UUID catalogId) implements Target {
        }

        record Namespace(UUID catalogId, UUID namespaceId) implements Target {
        }

        record TableOrView(CatalogObject object) implements Target {
            @Override
            public UUID catalogId() {
                return object.catalogId();
            }
        }

        static Target of(NamespaceRecord namespace) {
            return new Namespace(namespace.catalogId(), namespace.id());
        }

        static Target of(CatalogObject object) {
            return new TableOrView(object);
        }
    }

    public record Request(Target target, Operation operation) {
    }

    public enum Reason {
        /** No group of the subject holds a privilege that implies the one needed (D18). */
        NO_PRIVILEGE,
        /** An unclassified table in a regulated area (D27). */
        UNCLASSIFIED,
        /** Storage access to a table with a row/column policy, asked by a client that is no trusted engine (D20). */
        FLAGGED_TABLE,
        /** A write to a regulated area by a trusted engine that named no end user, with the switch on (D17). */
        IDENTITY_LESS
    }

    public sealed interface Decision {

        boolean allowed();

        record Allowed() implements Decision {
            @Override
            public boolean allowed() {
                return true;
            }
        }

        /** @param privilege the privilege that was missing; null when the refusal is not about one */
        record Denied(Reason reason, Privilege privilege, String message) implements Decision {
            @Override
            public boolean allowed() {
                return false;
            }
        }
    }

    private static final Decision ALLOWED = new Decision.Allowed();
    private static final int PARENT_CACHE_LIMIT = 100_000;

    private final GrantStore grants;
    private final GovernanceStore governance;
    private final NamespaceStore namespaces;
    private final Separation separation;
    private final boolean refuseIdentityLessWrites;
    private final Map<UUID, UUID> parents = new ConcurrentHashMap<>();

    /**
     * @param refuseIdentityLessWrites D17's switch (CMP-7): refuse writes to regulated areas by a trusted
     *                                 engine that names no end user; off by default in configuration
     */
    public Authorizer(GrantStore grants, GovernanceStore governance, NamespaceStore namespaces, Separation separation,
                      boolean refuseIdentityLessWrites) {
        this.grants = grants;
        this.governance = governance;
        this.namespaces = namespaces;
        this.separation = separation;
        this.refuseIdentityLessWrites = refuseIdentityLessWrites;
    }

    public Decision decide(Caller caller, Target target, Operation operation) {
        return decide(caller, List.of(new Request(target, operation))).getFirst();
    }

    /** The batch check: one decision per request, in order (D18, D19). */
    public List<Decision> decide(Caller caller, List<Request> requests) {
        Principal subject = caller.subject();
        List<List<Securable>> chains = new ArrayList<>(requests.size());
        Set<Securable> all = new LinkedHashSet<>();
        Set<Securable> objectChains = new LinkedHashSet<>();
        for (Request request : requests) {
            List<Securable> chain = chain(request.target());
            chains.add(chain);
            all.addAll(chain);
            if (request.target() instanceof Target.TableOrView) {
                objectChains.addAll(chain);
            }
        }
        List<GrantRecord> held = subject.groups().isEmpty() ? List.of() : grants.grantsFor(all, subject.groups());
        List<GovernanceAttribute> attributes = objectChains.isEmpty() ? List.of() : governance.attributesOf(objectChains);
        boolean grantAdmin = subject.groups().stream().anyMatch(separation.grantAdminGroups()::contains);

        List<Decision> decisions = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            decisions.add(decide(caller, requests.get(i), chains.get(i), held, attributes, grantAdmin));
        }
        return decisions;
    }

    /** Every privilege {@code groups} hold on the target through grants on it or above it. */
    public Set<Privilege> privileges(Set<String> groups, Target target) {
        List<Securable> chain = chain(target);
        Set<Privilege> privileges = new HashSet<>();
        if (groups.stream().anyMatch(separation.grantAdminGroups()::contains)) {
            privileges.add(Privilege.MANAGE_GRANTS);
        }
        if (!groups.isEmpty()) {
            grants.grantsFor(Set.copyOf(chain), groups).forEach(g -> known(g).ifPresent(privileges::add));
        }
        return privileges;
    }

    private Decision decide(Caller caller, Request request, List<Securable> chain, List<GrantRecord> held,
                            List<GovernanceAttribute> attributes, boolean grantAdmin) {
        Operation operation = request.operation();
        Privilege needed = operation.privilege();
        boolean granted = (grantAdmin && Privilege.MANAGE_GRANTS.implies(needed))
                || held.stream().filter(g -> chain.contains(g.grant().securable()))
                        .flatMap(g -> known(g).stream())
                        .anyMatch(p -> p.implies(needed));
        if (!granted) {
            return new Decision.Denied(Reason.NO_PRIVILEGE, needed, caller.subject().id() + " holds no privilege that gives "
                    + needed.wireName() + " for " + operation.name());
        }
        if (!(request.target() instanceof Target.TableOrView(CatalogObject object))) {
            return ALLOWED;
        }
        boolean regulated = attributes.stream().anyMatch(a -> chain.contains(a.target())
                && a.kind() == GovernanceAttribute.Kind.REGULATED && "true".equalsIgnoreCase(a.value()));
        Securable self = object.securable();
        if (object.kind() == CatalogObject.Kind.TABLE && regulated && needed != Privilege.DESCRIBE
                && attributes.stream().noneMatch(a -> a.target().equals(self)
                        && a.kind() == GovernanceAttribute.Kind.CLASSIFICATION)) {
            return new Decision.Denied(Reason.UNCLASSIFIED, null, "table " + object.name().display()
                    + " is in a regulated area and has no classification; it is closed until it is classified");
        }
        if (operation.storageAccess() && !caller.trustedEngine() && attributes.stream().anyMatch(a ->
                a.target().equals(self) && a.kind() == GovernanceAttribute.Kind.ROW_COLUMN_POLICY
                        && "true".equalsIgnoreCase(a.value()))) {
            return new Decision.Denied(Reason.FLAGGED_TABLE, null, "table " + object.name().display()
                    + " has a row/column policy; only a trusted engine that enforces it gets storage access");
        }
        if (refuseIdentityLessWrites && regulated && caller.trustedEngine() && caller.endUser().isEmpty()
                && (needed == Privilege.WRITE || needed == Privilege.MODIFY)) {
            return new Decision.Denied(Reason.IDENTITY_LESS, null, "engine " + caller.engine()
                    + " named no end user, and writes to regulated areas must name one");
        }
        return ALLOWED;
    }

    /** The target, then every namespace above it, then its catalog. */
    private List<Securable> chain(Target target) {
        List<Securable> chain = new ArrayList<>();
        UUID next;
        switch (target) {
            case Target.Catalog catalog -> next = catalog.catalogId();
            case Target.Namespace namespace -> next = namespace.namespaceId();
            case Target.TableOrView(CatalogObject object) -> {
                chain.add(object.securable());
                next = object.namespaceId();
            }
        }
        UUID catalogId = target.catalogId();
        while (!next.equals(catalogId)) {
            chain.add(new Securable(Securable.Kind.NAMESPACE, next));
            UUID current = next;
            // A namespace that is gone holds nothing; its chain stops at the catalog, and the miss is not cached.
            next = parents.computeIfAbsent(current, id -> namespaces.readNamespace(id)
                    .map(NamespaceRecord::parentId).orElse(null));
            if (next == null) {
                next = catalogId;
            }
            if (parents.size() > PARENT_CACHE_LIMIT) {
                parents.clear();
            }
        }
        chain.add(new Securable(Securable.Kind.CATALOG, catalogId));
        return chain;
    }

    /** A stored privilege this version knows; a name from a newer version gives nothing. */
    private static Optional<Privilege> known(GrantRecord grant) {
        try {
            return Optional.of(Privilege.fromWireName(grant.grant().privilege()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
