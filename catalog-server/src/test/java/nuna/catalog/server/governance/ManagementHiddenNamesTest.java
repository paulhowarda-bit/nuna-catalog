package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Names are hidden on the management API as on the Iceberg routes (D18, amended again 2026-11-19; backlog 73). A
 * caller refused on a namespace, table or view it holds nothing on gets exactly what the route answers when the name
 * does not exist: each case asks once with the name there, then again once the object is gone, then once its
 * namespace is gone too, and expects one answer. A caller that holds something on the name (any grant on it or
 * above it, a grant admin's {@code manage-grants} included, or for a namespace a grant on anything in it) is still
 * refused with 403; a platform operator, who holds no grant, sees no name. A caller that holds nothing in the
 * catalog at all gets what a catalog that does not exist gets, on every route under it (D18, amended 2026-11-20;
 * backlog 68); a platform operator sees every catalog. Every refusal is audited. The routes,
 * guard and authorizer are the real ones over a store double, so this needs only Java; the grant routes are
 * {@code GrantHiddenNamesTest}'s, and {@code ManagementAuthorizationTest} checks the routes on the wire.
 */
class ManagementHiddenNamesTest {

    private static final String ATTRIBUTE = "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/1\"}";
    private static final String REASON = "{\"reason\":\"case 9\"}";
    private static final String POLICY = "{\"compaction.enabled\":true}";

    /** Describe on the namespace lobby only, so he holds something in the catalog but nothing on hr or sales. */
    private static final Caller OTTO = person("otto", "outsiders");
    /** Nothing anywhere in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    /** Select on the table hr.staff only. */
    private static final Caller TOM = person("tom", "staff-readers");
    /** Select on the table hr.payroll.slips only. */
    private static final Caller PAM = person("pam", "payroll");
    /** Manage-tags on the namespace sales. */
    private static final Caller STU = person("stu", "stewards");
    private static final Caller GINA = person("gina", "grant-admins");
    private static final Caller OLGA = person("olga", "platform-ops");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private boolean catalogGone;

    private final NamespaceRecord sales = namespace(null, "sales");
    private final NamespaceRecord hr = namespace(null, "hr");
    private final NamespaceRecord payroll = namespace(hr, "payroll");
    private final CatalogObject staff = object(hr, "staff", Kind.TABLE);
    private final CatalogObject salaries = object(hr, "salaries", Kind.TABLE);
    private final CatalogObject roster = object(hr, "roster", Kind.VIEW);
    private final CatalogObject slips = object(payroll, "slips", Kind.TABLE);
    private final NamespaceRecord lobby = namespace(null, "lobby");

    private final GovernanceRoutes governance;
    private final AuditReadRoutes trail;
    private final HealthRoutes health;
    private final LifecycleRoutes lifecycle;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = OTTO;

    ManagementHiddenNamesTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(staff.securable(), "staff-readers", Privilege.SELECT);
        grant(slips.securable(), "payroll", Privilege.SELECT);
        grant(new Securable(Securable.Kind.NAMESPACE, sales.id()), "stewards", Privilege.MANAGE_TAGS);
        grant(new Securable(Securable.Kind.NAMESPACE, lobby.id()), "outsiders", Privilege.DESCRIBE);

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> caller, settings, holdings);
        // No security settings: they name only the maintenance principals, whose routes are not asked here.
        ManagementGuard guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        // No governance settings, audit topic, storage or catalog names: every case is answered before them.
        governance = new GovernanceRoutes(store, management, guard, null, null, clock);
        trail = new AuditReadRoutes(management, guard, null, null, null, clock);
        health = new HealthRoutes(store, management, guard, authorizer, null);
        lifecycle = new LifecycleRoutes(store, management, guard, null, null, null, null, clock);
    }

    // ---- every route that names a namespace, table or view ----

    @Test
    void everyRouteOnATableAnswersAsForATableThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff");
        for (Callable<ResponseEntity<?>> call : routes("hr", "staff", null)) {
            assertThat(alike(call, staff, hr)).isEqualTo(missing);
        }
        assertThat(alike(() -> health.table("gate", "hr", "staff", http), staff, hr)).isEqualTo(missing);
    }

    @Test
    void everyRouteOnAViewAnswersAsForAViewThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchViewException", "View does not exist: hr.roster");
        for (Callable<ResponseEntity<?>> call : routes("hr", null, "roster")) {
            assertThat(alike(call, roster, hr)).isEqualTo(missing);
        }
    }

    @Test
    void everyRouteOnANamespaceAnswersAsForANamespaceThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr");
        for (Callable<ResponseEntity<?>> call : routes("hr", null, null)) {
            assertThat(alike(call, hr)).isEqualTo(missing);
        }
        assertThat(alike(() -> lifecycle.dropped("gate", "hr", http), hr)).isEqualTo(missing);
    }

    @Test
    void aNamespaceBelowAnotherAnswersAsOneThatDoesNotExist() {
        caller = TOM;
        assertThat(alike(() -> governance.governance("gate", "hr\u001fpayroll", null, null, http), payroll))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr.payroll"));
    }

    // ---- who may see a name ----

    @Test
    void aCallerThatHoldsSomethingOnTheObjectIsRefusedWith403() {
        caller = TOM;
        assertThat(answer(() -> governance.setAttribute("gate", "hr", "staff", null, "classification", ATTRIBUTE,
                http)).status()).isEqualTo(403);
        assertThat(answer(() -> governance.setPolicy("gate", "hr", "staff", null, POLICY, http)).status())
                .isEqualTo(403);
        assertThat(answer(() -> trail.recent("gate", "hr", "staff", null, null, http)).status()).isEqualTo(403);
        assertThat(answer(() -> governance.freeze("gate", "hr", "staff", null, REASON, http)))
                .as("only an operator freezes, and Tom may know staff exists")
                .isEqualTo(new Answer(403, "ForbiddenException", "only platform operators may call setDrFreeze"));
    }

    @Test
    void aCallerThatHoldsSomethingOnTheObjectGetsItsAnswer() {
        caller = TOM;
        assertThat(answer(() -> governance.governance("gate", "hr", "staff", null, http)).status()).isEqualTo(200);
    }

    @Test
    void aGrantOnAnythingInANamespaceLetsTheCallerSeeTheNamespace() {
        caller = TOM;
        assertThat(answer(() -> governance.governance("gate", "hr", null, null, http)).status()).isEqualTo(403);
        assertThat(answer(() -> lifecycle.dropped("gate", "hr", http)).status()).isEqualTo(403);
        caller = PAM;
        assertThat(answer(() -> governance.policy("gate", "hr", null, null, http)).status())
                .as("a grant on a table in a namespace below").isEqualTo(403);
        assertThat(answer(() -> governance.governance("gate", "sales", null, null, http)))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: sales"));
    }

    @Test
    void aGrantOnOneObjectRevealsNoOtherObjectBesideIt() {
        caller = TOM;
        assertThat(alike(() -> governance.governance("gate", "hr", "salaries", null, http), salaries))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.salaries"));
        assertThat(alike(() -> governance.freeze("gate", "hr", null, "roster", REASON, http), roster))
                .isEqualTo(new Answer(404, "NoSuchViewException", "View does not exist: hr.roster"));
    }

    @Test
    void aGrantAdminSeesEveryNameAndAPlatformOperatorNone() {
        caller = GINA;
        assertThat(answer(() -> governance.setAttribute("gate", "hr", "staff", null, "owner", ATTRIBUTE, http))
                .status()).isEqualTo(403);
        caller = OLGA;
        assertThat(alike(() -> governance.governance("gate", "hr", "staff", null, http), staff, hr))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
        assertThat(alike(() -> governance.policy("gate", "hr", null, null, http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
    }

    @Test
    void aStewardOfOneNamespaceSeesNoOther() {
        caller = STU;
        assertThat(answer(() -> governance.setPolicy("gate", "sales", null, null, POLICY, http)).status())
                .isEqualTo(403);
        assertThat(alike(() -> governance.setAttribute("gate", "hr", null, null, "owner", ATTRIBUTE, http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
    }

    @Test
    void aRefusalOnTheCatalogItselfIsStill403() {
        assertThat(answer(() -> governance.governance("gate", null, null, null, http)).status()).isEqualTo(403);
        assertThat(answer(() -> governance.freeze("gate", null, null, null, REASON, http)).status()).isEqualTo(403);
    }

    // ---- a catalog the caller holds nothing in (backlog 68) ----

    @Test
    void everyRouteOnACatalogTheCallerHoldsNothingInAnswersAsForOneThatDoesNotExist() {
        caller = NED;
        Answer missing = new Answer(404, "NoSuchWarehouseException", "The given warehouse does not exist: gate");
        List<Callable<ResponseEntity<?>>> calls = new ArrayList<>();
        calls.addAll(routes(null, null, null));
        calls.addAll(routes("hr", null, null));
        calls.addAll(routes("hr", "staff", null));
        calls.addAll(routes("hr", null, "roster"));
        calls.add(() -> health.table("gate", "hr", "staff", http));
        calls.add(() -> lifecycle.dropped("gate", "hr", http));
        for (Callable<ResponseEntity<?>> call : calls) {
            assertThat(alike(call, catalog)).isEqualTo(missing);
        }
    }

    @Test
    void aPlatformOperatorSeesEveryCatalog() {
        caller = OLGA;
        assertThat(answer(() -> governance.governance("gate", null, null, null, http)).status())
                .as("refused on the catalog it sees").isEqualTo(403);
        assertThat(answer(() -> governance.setPolicy("gate", null, null, null, POLICY, http)).status())
                .isEqualTo(403);
    }

    @Test
    void aCallerThatHoldsAnythingInTheCatalogIsRefusedOnItWith403() {
        for (Caller holder : List.of(OTTO, TOM, STU)) {
            caller = holder;
            assertThat(answer(() -> governance.setPolicy("gate", null, null, null, POLICY, http)).status())
                    .as("%s lacks modify on the catalog", holder.principal().id()).isEqualTo(403);
        }
    }

    @Test
    void aHiddenCatalogRefusalIsAudited() {
        caller = NED;
        answer(() -> governance.setPolicy("gate", "hr", "staff", null, POLICY, http));
        answer(() -> governance.freeze("gate", null, null, null, REASON, http));

        assertThat(denials()).extracting(Denial::operation).containsExactly("setMaintenancePolicy", "setDrFreeze");
        Denial set = denials().getFirst();
        assertThat(set.privilege()).isEqualTo("modify");
        assertThat(set.reason()).startsWith("no_privilege");
        assertThat(set.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.CATALOG);
        assertThat(set.securable().id()).isEqualTo(catalog.id());
        assertThat(set.securable().path()).isEmpty();
        assertThat(set.actor().principal()).isEqualTo("ned");
        assertThat(set.actor().requestId()).isEqualTo("request-1");
        assertThat(denials().get(1).privilege()).isNull();

        forget(catalog);
        answer(() -> governance.setPolicy("gate", "hr", "staff", null, POLICY, http));
        assertThat(denials()).as("nothing was there to refuse").hasSize(2);
    }

    // ---- the record ----

    @Test
    void aHiddenRefusalIsAuditedAsEveryRefusalIs() {
        answer(() -> governance.governance("gate", "hr", "staff", null, http));
        answer(() -> governance.freeze("gate", "hr", null, null, REASON, http));
        answer(() -> lifecycle.dropped("gate", "hr", http));
        answer(() -> trail.recent("gate", "hr", null, "roster", null, http));

        assertThat(denials()).extracting(Denial::operation)
                .containsExactly("loadGovernance", "setDrFreeze", "listDropped", "readAudit");
        Denial load = denials().getFirst();
        assertThat(load.privilege()).isEqualTo("describe");
        assertThat(load.reason()).startsWith("no_privilege");
        assertThat(load.securable().id()).isEqualTo(staff.id());
        assertThat(load.securable().path()).containsExactly("hr", "staff");
        assertThat(load.actor().principal()).isEqualTo("otto");
        assertThat(load.actor().requestId()).isEqualTo("request-1");
        assertThat(denials().get(1).securable().path()).containsExactly("hr");
        assertThat(denials().get(1).reason()).startsWith("no_privilege: only platform operators");
        assertThat(denials().get(3).privilege()).isEqualTo("manage-grants");
        assertThat(denials().get(3).securable().path()).containsExactly("hr", "roster");

        forget(staff);
        answer(() -> governance.governance("gate", "hr", "staff", null, http));
        assertThat(denials()).as("nothing was there to refuse").hasSize(4);
    }

    // ---- helpers ----

    /** What a client is told: the status, and an error's type and message. */
    private record Answer(int status, String type, String message) {
    }

    /**
     * Every governance route on the target {@code namespace}, {@code table} and {@code view} name, as the path
     * would: the governance read, attributes, holds, the maintenance policy, the DR freeze, and the audit trail.
     */
    private List<Callable<ResponseEntity<?>>> routes(String namespace, String table, String view) {
        String hold = UUID.randomUUID().toString();
        return List.of(
                () -> governance.governance("gate", namespace, table, view, http),
                () -> governance.setAttribute("gate", namespace, table, view, "classification", ATTRIBUTE, http),
                () -> governance.removeAttribute("gate", namespace, table, view, "owner", http),
                () -> governance.placeHold("gate", namespace, table, view, REASON, http),
                () -> governance.releaseHold("gate", namespace, table, view, hold, http),
                () -> governance.policy("gate", namespace, table, view, http),
                () -> governance.setPolicy("gate", namespace, table, view, POLICY, http),
                () -> governance.removePolicy("gate", namespace, table, view, http),
                () -> governance.freeze("gate", namespace, table, view, REASON, http),
                () -> governance.thaw("gate", namespace, table, view, http),
                () -> trail.recent("gate", namespace, table, view, null, http));
    }

    /** The answer, the same once each of {@code gone} is forgotten in turn; each is put back afterwards. */
    private Answer alike(Callable<? extends ResponseEntity<?>> call, Object... gone) {
        Answer hidden = answer(call);
        for (Object record : gone) {
            forget(record);
            assertThat(answer(call)).as("once %s is gone", record).isEqualTo(hidden);
        }
        for (Object record : gone) {
            restore(record);
        }
        return hidden;
    }

    /** A route's answer as ErrorResponses renders a thrown error. */
    private static Answer answer(Callable<? extends ResponseEntity<?>> call) {
        try {
            return new Answer(call.call().getStatusCode().value(), null, null);
        } catch (CatalogError e) {
            return new Answer(e.status(), e.type(), e.getMessage());
        } catch (AccessDenied e) {
            return new Answer(403, "ForbiddenException", e.getMessage());
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(),
                parent == null ? catalog.id() : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private CatalogObject object(NamespaceRecord namespace, String name, Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), kind, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    private void forget(Object record) {
        switch (record) {
            case NamespaceRecord namespace -> namespaces.remove(namespace.id());
            case CatalogObject object -> objects.remove(object.id());
            case CatalogRecord ignored -> catalogGone = true;
            default -> throw new IllegalArgumentException(String.valueOf(record));
        }
    }

    private void restore(Object record) {
        switch (record) {
            case NamespaceRecord namespace -> namespaces.put(namespace.id(), namespace);
            case CatalogObject object -> objects.put(object.id(), object);
            case CatalogRecord ignored -> catalogGone = false;
            default -> throw new IllegalArgumentException(String.valueOf(record));
        }
    }

    private Optional<NamespaceRecord> findNamespace(List<String> path) {
        UUID parent = catalog.id();
        NamespaceRecord found = null;
        for (String level : path) {
            UUID above = parent;
            found = namespaces.values().stream()
                    .filter(ns -> ns.parentId().equals(above) && ns.name().normalized().equals(level))
                    .findFirst().orElse(null);
            if (found == null) {
                return Optional.empty();
            }
            parent = found.id();
        }
        return Optional.ofNullable(found);
    }

    /** The estate above and the groups' grants on it, with no attributes or holds; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog)
                            .filter(c -> !catalogGone && c.normalizedName().equals(args[0]));
                    case "findNamespace" -> findNamespace((List<String>) args[1]);
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "findObject" -> objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]))
                            .findFirst();
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
