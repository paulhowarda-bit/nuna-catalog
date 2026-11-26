package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.GrantStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.NamespaceStore;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The effective grants on an object (D18, D45): every grant on it, on each namespace above it and on its catalog,
 * read with the authorizer's own chain, so the read agrees with every access decision. The grants live in memory,
 * so these tests need no Docker and run in the fast build.
 */
class EffectiveGrantsTest {

    private final Memory store = new Memory();
    private final UUID catalogId = UUID.randomUUID();
    private final Authorizer authorizer = new Authorizer(store, noAttributes(), store,
            new Separation(Set.of("grant-admins"), Set.of("platform-ops")), false, Set.of());

    @Test
    void aGrantOnTheCatalogOrOnANamespaceAboveReachesATableFromTheCatalogDown() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        NamespaceRecord desk = namespace(trading.id(), "desk");
        CatalogObject fills = object(desk, "fills", CatalogObject.Kind.TABLE);
        grant(catalog(), "auditors", Privilege.DESCRIBE);
        grant(securable(trading), "traders", Privilege.SELECT);
        grant(securable(desk), "desk-leads", Privilege.MODIFY);
        grant(fills.securable(), "loaders", Privilege.WRITE);

        assertThat(effective(Target.of(fills))).containsExactly(
                "catalog auditors describe", "namespace(trading) traders select",
                "namespace(desk) desk-leads modify", "table(fills) loaders write");
    }

    @Test
    void aGrantBelowOrBesideTheObjectDoesNotReachIt() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        NamespaceRecord desk = namespace(trading.id(), "desk");
        NamespaceRecord other = namespace(catalogId, "other");
        CatalogObject fills = object(desk, "fills", CatalogObject.Kind.TABLE);
        CatalogObject sibling = object(desk, "orders", CatalogObject.Kind.TABLE);
        grant(securable(trading), "traders", Privilege.SELECT);
        grant(sibling.securable(), "sibling-readers", Privilege.SELECT);
        grant(securable(other), "others", Privilege.SELECT);
        grant(fills.securable(), "loaders", Privilege.WRITE);

        assertThat(effective(Target.of(fills))).containsExactly("namespace(trading) traders select",
                "table(fills) loaders write");
        assertThat(effective(Target.of(trading))).as("a grant on a table never reaches up to its namespace")
                .containsExactly("namespace(trading) traders select");
        assertThat(effective(new Target.Catalog(catalogId))).isEmpty();
    }

    @Test
    void aViewAndANamespaceAreReadTheSameWay() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        NamespaceRecord desk = namespace(trading.id(), "desk");
        CatalogObject daily = object(desk, "daily", CatalogObject.Kind.VIEW);
        grant(catalog(), "auditors", Privilege.DESCRIBE);
        grant(securable(trading), "traders", Privilege.SELECT);
        grant(daily.securable(), "report-readers", Privilege.SELECT);

        assertThat(effective(Target.of(daily))).containsExactly("catalog auditors describe",
                "namespace(trading) traders select", "view(daily) report-readers select");
        assertThat(effective(Target.of(desk))).containsExactly("catalog auditors describe",
                "namespace(trading) traders select");
        assertThat(effective(new Target.Catalog(catalogId))).containsExactly("catalog auditors describe");
    }

    @Test
    void aRevokedGrantNoLongerReaches() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        CatalogObject fills = object(trading, "fills", CatalogObject.Kind.TABLE);
        grant(catalog(), "auditors", Privilege.DESCRIBE);
        grant(securable(trading), "traders", Privilege.SELECT);

        store.revokeGrant(new Grant(catalogId, securable(trading), "traders", "select"), null);
        assertThat(effective(Target.of(fills))).containsExactly("catalog auditors describe");
        store.revokeGrant(new Grant(catalogId, catalog(), "auditors", "describe"), null);
        assertThat(effective(Target.of(fills))).isEmpty();
    }

    /**
     * Backlog 113: a grant that names a branch counts only for requests through that branch, on the table and,
     * inherited, below a namespace; one on every ref counts through main, every branch and every tag. The effective
     * grants show both, each with its branch.
     */
    @Test
    void aGrantThatNamesABranchHoldsThroughThatBranchOnly() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        CatalogObject fills = object(trading, "fills", CatalogObject.Kind.TABLE);
        UUID dev = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        store.putGrant(new Grant(catalogId, fills.securable(), "dev-writers", "write", Optional.of(dev)), "setup", null);
        store.putGrant(new Grant(catalogId, securable(trading), "dev-owners", "modify", Optional.of(dev)), "setup",
                null);
        grant(fills.securable(), "loaders", Privilege.WRITE);
        Caller devWriter = new Caller(new Principal("w", Set.of("dev-writers")), "tests", false, Optional.empty());
        Caller devOwner = new Caller(new Principal("o", Set.of("dev-owners")), "tests", false, Optional.empty());
        Caller loader = new Caller(new Principal("l", Set.of("loaders")), "tests", false, Optional.empty());
        Operation append = new Operation("updateTable", Privilege.WRITE, true);
        Operation alter = new Operation("updateTable", Privilege.MODIFY, true);

        assertThat(authorizer.decide(devWriter, Target.of(fills), append, Optional.of(dev)).allowed()).isTrue();
        assertThat(authorizer.decide(devWriter, Target.of(fills), append).allowed()).as("main").isFalse();
        assertThat(authorizer.decide(devWriter, Target.of(fills), append, Optional.of(test)).allowed())
                .as("another branch").isFalse();
        assertThat(authorizer.decide(devOwner, Target.of(fills), alter, Optional.of(dev)).allowed())
                .as("inherited from the namespace, through dev").isTrue();
        assertThat(authorizer.decide(devOwner, Target.of(fills), alter).allowed()).as("main").isFalse();
        for (Optional<UUID> ref : List.of(Optional.<UUID>empty(), Optional.of(dev), Optional.of(test))) {
            assertThat(authorizer.decide(loader, Target.of(fills), append, ref).allowed()).as("every ref: " + ref)
                    .isTrue();
        }
        assertThat(authorizer.privileges(Set.of("dev-writers"), Target.of(fills))).isEmpty();
        assertThat(authorizer.privileges(Set.of("dev-writers"), Target.of(fills), Optional.of(dev)))
                .containsExactly(Privilege.WRITE);
        assertThat(authorizer.effectiveGrants(Target.of(fills))).extracting(g -> g.grant().group() + " "
                + g.grant().branch().map(id -> id.equals(dev) ? "dev" : "?").orElse("every ref"))
                .containsExactlyInAnyOrder("dev-owners dev", "dev-writers dev", "loaders every ref");
    }

    @Test
    void eachGrantKeepsWhoGrantedItAndWhen() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        grant(securable(trading), "traders", Privilege.SELECT);

        GrantRecord only = authorizer.effectiveGrants(Target.of(trading)).getFirst();
        assertThat(only.grantedBy()).isEqualTo("setup");
        assertThat(only.grantedAt()).isEqualTo(Memory.AT);
        assertThat(only.grant().securable()).isEqualTo(securable(trading));
    }

    @Test
    void aPrivilegeThisVersionDoesNotKnowGivesNothingSoItIsLeftOut() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        store.putGrant(new Grant(catalogId, securable(trading), "future", "time-travel"), "setup", null);
        grant(securable(trading), "traders", Privilege.SELECT);

        assertThat(effective(Target.of(trading))).containsExactly("namespace(trading) traders select");
    }

    @Test
    void everyPageOfASecurablesGrantsIsRead() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        for (int i = 0; i < 1_201; i++) {
            grant(catalog(), "group-" + i, Privilege.DESCRIBE);
        }
        grant(securable(trading), "traders", Privilege.SELECT);

        List<GrantRecord> reaching = authorizer.effectiveGrants(Target.of(trading));
        assertThat(reaching).hasSize(1_202);
        assertThat(reaching.getLast().grant().group()).isEqualTo("traders");
    }

    /**
     * The read never disagrees with a decision: for every group, every privilege and every securable in a small
     * tree, a member of the group is allowed exactly when an effective grant of that group implies the privilege.
     */
    @Test
    void theReadAgreesWithEveryAccessDecision() {
        NamespaceRecord trading = namespace(catalogId, "trading");
        NamespaceRecord desk = namespace(trading.id(), "desk");
        NamespaceRecord other = namespace(catalogId, "other");
        CatalogObject fills = object(desk, "fills", CatalogObject.Kind.TABLE);
        CatalogObject daily = object(desk, "daily", CatalogObject.Kind.VIEW);
        CatalogObject elsewhere = object(other, "elsewhere", CatalogObject.Kind.TABLE);
        grant(catalog(), "auditors", Privilege.DESCRIBE);
        grant(securable(trading), "traders", Privilege.SELECT);
        grant(securable(trading), "builders", Privilege.CREATE);
        grant(securable(desk), "desk-leads", Privilege.MODIFY);
        grant(securable(desk), "stewards", Privilege.MANAGE_TAGS);
        grant(fills.securable(), "loaders", Privilege.WRITE);
        grant(daily.securable(), "report-readers", Privilege.SELECT);
        grant(securable(other), "others", Privilege.MANAGE_GRANTS);
        Set<String> groups = Set.of("auditors", "traders", "builders", "desk-leads", "stewards", "loaders",
                "report-readers", "others", "nobody");

        int decisions = 0;
        for (Target target : List.of(new Target.Catalog(catalogId), Target.of(trading), Target.of(desk),
                Target.of(other), Target.of(fills), Target.of(daily), Target.of(elsewhere))) {
            List<GrantRecord> reaching = authorizer.effectiveGrants(target);
            for (String group : groups) {
                Caller member = new Caller(new Principal("p-" + group, Set.of(group)), "tests", false,
                        Optional.empty());
                for (Privilege privilege : Privilege.values()) {
                    boolean shown = reaching.stream().filter(g -> g.grant().group().equals(group))
                            .anyMatch(g -> Privilege.fromWireName(g.grant().privilege()).implies(privilege));
                    boolean allowed = authorizer.decide(member, target,
                            new Operation("check", privilege, false)).allowed();
                    assertThat(shown).as(group + " " + privilege.wireName() + " on " + target).isEqualTo(allowed);
                    decisions++;
                }
            }
        }
        assertThat(decisions).isEqualTo(7 * 9 * Privilege.values().length);
    }

    // ---- the estate ----

    private Securable catalog() {
        return new Securable(Securable.Kind.CATALOG, catalogId);
    }

    private static Securable securable(NamespaceRecord namespace) {
        return new Securable(Securable.Kind.NAMESPACE, namespace.id());
    }

    private NamespaceRecord namespace(UUID parentId, String name) {
        NamespaceRecord record = new NamespaceRecord(catalogId, UUID.randomUUID(), parentId, new Name(name, name),
                Map.of(), 1);
        store.namespaces.put(record.id(), record);
        store.names.put(record.id(), name);
        return record;
    }

    private CatalogObject object(NamespaceRecord namespace, String name, CatalogObject.Kind kind) {
        UUID id = UUID.randomUUID();
        store.names.put(id, name);
        return CatalogObject.active(catalogId, id, namespace.id(), new Name(name, name), kind, "s3://t/" + id);
    }

    private void grant(Securable on, String group, Privilege privilege) {
        assertThat(store.putGrant(new Grant(catalogId, on, group, privilege.wireName()), "setup", null))
                .isEqualTo(WriteResult.APPLIED);
    }

    /** The effective grants as "where group privilege", where is the securable's kind and name. */
    private List<String> effective(Target target) {
        return authorizer.effectiveGrants(target).stream().map(g -> {
            Securable on = g.grant().securable();
            String where = on.kind() == Securable.Kind.CATALOG ? "catalog"
                    : on.kind().name().toLowerCase(java.util.Locale.ROOT) + "(" + store.names.get(on.id()) + ")";
            return where + " " + g.grant().group() + " " + g.grant().privilege();
        }).toList();
    }

    /** No governance attributes anywhere, so only grants decide. */
    private static GovernanceStore noAttributes() {
        return (GovernanceStore) Proxy.newProxyInstance(GovernanceStore.class.getClassLoader(),
                new Class<?>[] {GovernanceStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("attributesOf")) {
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Grants and namespaces in memory, listed and paged as the store contract says. */
    private static final class Memory implements GrantStore, NamespaceStore {
        static final Instant AT = Instant.parse("2026-11-22T09:00:00Z");

        final Set<Grant> grants = new LinkedHashSet<>();
        final Map<UUID, NamespaceRecord> namespaces = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();

        @Override
        public WriteResult putGrant(Grant grant, String grantedBy, Change change) {
            return grants.add(grant) ? WriteResult.APPLIED : WriteResult.UNCHANGED;
        }

        @Override
        public WriteResult putGrantIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult revokeGrant(Grant grant, Change change) {
            return grants.remove(grant) ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.GRANT);
        }

        @Override
        public List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
            return grants.stream().filter(g -> securables.contains(g.securable()) && groups.contains(g.group()))
                    .map(Memory::record).toList();
        }

        @Override
        public Page<GrantRecord> listGrantsOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<nuna.catalog.store.GrantGroup> listGrantGroups(UUID catalogId, PageRequest page) {
            throw new UnsupportedOperationException();
        }

        /** By group, then privilege; the token is the index of the next grant. */
        @Override
        public Page<GrantRecord> listGrantsOn(Securable securable, PageRequest page) {
            List<GrantRecord> on = grants.stream().filter(g -> g.securable().equals(securable))
                    .sorted(Comparator.comparing(Grant::group).thenComparing(Grant::privilege))
                    .map(Memory::record).toList();
            int from = page.token().map(Integer::parseInt).orElse(0);
            int to = Math.min(on.size(), from + page.size());
            return new Page<>(new ArrayList<>(on.subList(from, to)),
                    to < on.size() ? Optional.of(Integer.toString(to)) : Optional.empty());
        }

        @Override
        public WriteResult createNamespace(NamespaceRecord namespace, Change change) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<NamespaceRecord> findNamespace(UUID catalogId, List<String> normalizedPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<NamespaceRecord> readNamespace(UUID namespaceId) {
            return Optional.ofNullable(namespaces.get(namespaceId));
        }

        @Override
        public Page<NamespaceRecord> listNamespaces(UUID parentId, PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult updateNamespaceProperties(UUID namespaceId, long expectedVersion,
                                                     Map<String, String> properties, Change change) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult dropNamespace(UUID namespaceId, Change change) {
            throw new UnsupportedOperationException();
        }

        private static GrantRecord record(Grant grant) {
            return new GrantRecord(grant, "setup", AT);
        }
    }
}
