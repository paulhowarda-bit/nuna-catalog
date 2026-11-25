package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
import nuna.catalog.server.rest.RestSettings;
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
 * Names are hidden on the grant routes as on the Iceberg routes (D18, amended again 2026-11-19; backlog 73). A
 * grant, a revoke, a grant's preview (backlog 246) or a listing of grants on a namespace, table or view the caller
 * holds nothing on answers exactly as for one that does not exist, and the batch check answers for it as for a
 * missing one; a caller that holds
 * something there is refused with 403, or told its own answer. A caller that holds nothing in the catalog at all
 * gets, on every grant route, what a catalog that does not exist gets (D18, amended 2026-11-20; backlog 68). Every
 * refusal is audited; a check is not. The routes and authorizer are the real ones over a store double, so this
 * needs only Java; {@code SecurityGateTest} checks the grant routes on the wire.
 */
class GrantHiddenNamesTest {

    private static final String CATALOG = "gate";

    /** Manage-grants on the namespace sales. */
    private static final Caller SAM = person("sam", "stewards");
    /** Select on the namespace sales. */
    private static final Caller ANN = person("ann", "analysts");
    /** Select on the table hr.staff only. */
    private static final Caller TOM = person("tom", "staff-readers");
    /** Describe on the namespace lobby only, so he holds something in the catalog but nothing on hr or sales. */
    private static final Caller OTTO = person("otto", "outsiders");
    /** Nothing anywhere in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    private static final Caller OLGA = person("olga", "platform-ops");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), CATALOG, CATALOG);
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private boolean catalogGone;

    private final NamespaceRecord sales = namespace("sales");
    private final NamespaceRecord hr = namespace("hr");
    private final CatalogObject staff = object(hr, "staff", Kind.TABLE);
    private final CatalogObject roster = object(hr, "roster", Kind.VIEW);
    private final NamespaceRecord lobby = namespace("lobby");

    private final GrantRoutes routes;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = SAM;

    GrantHiddenNamesTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(new Securable(Securable.Kind.NAMESPACE, sales.id()), "stewards", Privilege.MANAGE_GRANTS);
        grant(new Securable(Securable.Kind.NAMESPACE, sales.id()), "analysts", Privilege.SELECT);
        grant(staff.securable(), "staff-readers", Privilege.SELECT);
        grant(new Securable(Securable.Kind.NAMESPACE, lobby.id()), "outsiders", Privilege.DESCRIBE);

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = new Denials(audit::add, settings, clock);
        routes = new GrantRoutes(store, authorizer, separation, request -> caller, denials,
                new Holdings(authorizer, store, settings, separation, denials), settings, clock);
    }

    // ---- grant, revoke and the listing by object ----

    @Test
    void aGrantOnANamespaceTheGranterHoldsNothingOnAnswersAsForOneThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr");
        assertThat(alike(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http), hr))
                .isEqualTo(missing);
        assertThat(alike(() -> routes.revoke(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http), hr))
                .isEqualTo(missing);
        assertThat(alike(() -> routes.preview(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http), hr))
                .isEqualTo(missing);
        assertThat(alike(() -> routes.listOn(CATALOG, "namespace", List.of("hr"), null, null, null, http), hr))
                .isEqualTo(missing);
    }

    @Test
    void aGrantOnATableOrViewTheGranterHoldsNothingOnAnswersAsForOneThatDoesNotExist() {
        Answer noTable = new Answer(404, "NoSuchTableException", "table does not exist: hr.staff");
        assertThat(alike(() -> routes.grant(CATALOG, body(tableSpec("hr", "staff"), "auditors", "select"), http),
                staff, hr)).isEqualTo(noTable);
        assertThat(alike(() -> routes.revoke(CATALOG, body(tableSpec("hr", "staff"), "auditors", "select"), http),
                staff, hr)).isEqualTo(noTable);
        assertThat(alike(() -> routes.preview(CATALOG, body(tableSpec("hr", "staff"), "auditors", "select"), http),
                staff, hr)).isEqualTo(noTable);
        assertThat(alike(() -> routes.listOn(CATALOG, "table", List.of("hr"), "staff", null, null, http), staff, hr))
                .isEqualTo(noTable);
        assertThat(alike(() -> routes.listOn(CATALOG, "view", List.of("hr"), "roster", null, null, http), roster, hr))
                .isEqualTo(new Answer(404, "NoSuchViewException", "view does not exist: hr.roster"));
    }

    // ---- who may see a name ----

    @Test
    void aCallerThatHoldsSomethingThereIsRefusedWith403() {
        caller = ANN;
        assertThat(answer(() -> routes.revoke(CATALOG, body(namespaceSpec("sales"), "analysts", "select"), http))
                .status()).as("select on sales, not manage-grants").isEqualTo(403);
        caller = TOM;
        assertThat(answer(() -> routes.grant(CATALOG, body(tableSpec("hr", "staff"), "auditors", "select"), http))
                .status()).isEqualTo(403);
        assertThat(answer(() -> routes.listOn(CATALOG, "namespace", List.of("hr"), null, null, null, http)).status())
                .as("a grant on a table in hr").isEqualTo(403);
    }

    @Test
    void aGrantOnOneObjectRevealsNoOtherObjectBesideIt() {
        caller = TOM;
        assertThat(alike(() -> routes.grant(CATALOG, body(viewSpec("hr", "roster"), "auditors", "select"), http),
                roster)).isEqualTo(new Answer(404, "NoSuchViewException", "view does not exist: hr.roster"));
    }

    @Test
    void aPlatformOperatorSeesNoNameAndARefusalOnTheCatalogIsStill403() {
        caller = OLGA;
        assertThat(alike(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
        caller = OTTO;
        assertThat(answer(() -> routes.grant(CATALOG, body("{\"type\":\"catalog\"}", "auditors", "select"), http))
                .status()).isEqualTo(403);
        assertThat(answer(() -> routes.listOn(CATALOG, "catalog", null, null, null, null, http)).status())
                .isEqualTo(403);
    }

    // ---- the batch check ----

    @Test
    void theCheckAnswersForAHiddenObjectAsForAMissingOne() {
        caller = OTTO;
        String checks = checks(tableSpec("hr", "staff"), viewSpec("hr", "roster"), namespaceSpec("hr"));
        String hidden = routes.check(CATALOG, checks, http).getBody();
        assertThat(JsonPath.<List<Boolean>>read(hidden, "$.results[*].allowed")).containsExactly(false, false, false);
        assertThat(JsonPath.<List<String>>read(hidden, "$.results[*].reason")).containsExactly(
                "table does not exist: hr.staff", "view does not exist: hr.roster", "Namespace does not exist: hr");
        assertThat(JsonPath.<List<Object>>read(hidden, "$.results[*].securable")).isEmpty();

        forget(staff);
        forget(roster);
        assertThat(routes.check(CATALOG, checks, http).getBody()).isEqualTo(hidden);
        forget(hr);
        assertThat(routes.check(CATALOG, checks, http).getBody()).isEqualTo(hidden);
        assertThat(denials()).as("a check is a question, not a refused request").isEmpty();
    }

    @Test
    void theCheckTellsACallerThatHoldsSomethingWhatItIsAsked() {
        caller = TOM;
        String answer = routes.check(CATALOG, checks(tableSpec("hr", "staff"), tableSpec("hr", "staff")), http)
                .getBody();
        assertThat(JsonPath.<List<String>>read(answer, "$.results[*].securable.id"))
                .containsExactly(staff.id().toString(), staff.id().toString());
        assertThat(JsonPath.<List<Boolean>>read(answer, "$.results[*].allowed")).containsExactly(true, false);
        assertThat(JsonPath.<String>read(answer, "$.results[1].reason"))
                .isEqualTo("tom holds no privilege that gives modify for check");
    }

    // ---- a catalog the caller holds nothing in (backlog 68) ----

    @Test
    void everyGrantRouteOnACatalogTheCallerHoldsNothingInAnswersAsForOneThatDoesNotExist() {
        caller = NED;
        Answer missing = new Answer(404, "NoSuchWarehouseException", "The given warehouse does not exist: gate");
        List<Callable<ResponseEntity<?>>> calls = List.of(
                () -> routes.grant(CATALOG, body("{\"type\":\"catalog\"}", "auditors", "select"), http),
                () -> routes.revoke(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http),
                () -> routes.preview(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http),
                () -> routes.listByGroups(CATALOG, null, null, null, http),
                () -> routes.listByGroups(CATALOG, List.of("auditors"), null, null, http),
                () -> routes.listOn(CATALOG, "catalog", null, null, null, null, http),
                () -> routes.listOn(CATALOG, "table", List.of("hr"), "staff", null, null, http),
                () -> routes.check(CATALOG, checks(tableSpec("hr", "staff")), http),
                () -> routes.export(CATALOG, http));
        for (Callable<ResponseEntity<?>> call : calls) {
            assertThat(alike(call, catalog)).isEqualTo(missing);
        }
    }

    @Test
    void aGrantAdminAndAPlatformOperatorSeeEveryCatalog() {
        caller = OLGA;
        assertThat(answer(() -> routes.listOn(CATALOG, "catalog", null, null, null, null, http)).status())
                .as("refused on the catalog it sees").isEqualTo(403);
        assertThat(answer(() -> routes.listByGroups(CATALOG, null, null, null, http)).status()).isEqualTo(200);
        caller = person("gina", "grant-admins");
        assertThat(answer(() -> routes.listByGroups(CATALOG, List.of("auditors"), null, null, http)).status())
                .as("manage-grants on every catalog").isEqualTo(200);
    }

    @Test
    void aHiddenCatalogRefusalIsAuditedButAMissingOneIsNot() {
        caller = NED;
        answer(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http));
        answer(() -> routes.check(CATALOG, checks(tableSpec("hr", "staff")), http));

        assertThat(denials()).extracting(Denial::operation).containsExactly("grant", "check");
        Denial grant = denials().getFirst();
        assertThat(grant.privilege()).isEqualTo("manage-grants");
        assertThat(grant.reason()).startsWith("no_privilege");
        assertThat(grant.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.CATALOG);
        assertThat(grant.securable().id()).isEqualTo(catalog.id());
        assertThat(grant.securable().path()).isEmpty();
        assertThat(grant.actor().principal()).isEqualTo("ned");

        forget(catalog);
        answer(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http));
        assertThat(denials()).as("nothing was there to refuse").hasSize(2);
    }

    // ---- the record ----

    @Test
    void aHiddenRefusalIsAuditedAsEveryRefusalIs() {
        answer(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http));
        answer(() -> routes.listOn(CATALOG, "table", List.of("hr"), "staff", null, null, http));

        assertThat(denials()).extracting(Denial::operation).containsExactly("grant", "listGrants");
        Denial grant = denials().getFirst();
        assertThat(grant.privilege()).isEqualTo("manage-grants");
        assertThat(grant.reason()).startsWith("no_privilege");
        assertThat(grant.securable().id()).isEqualTo(hr.id());
        assertThat(grant.securable().path()).containsExactly("hr");
        assertThat(grant.actor().principal()).isEqualTo("sam");
        assertThat(denials().get(1).securable().path()).containsExactly("hr", "staff");

        forget(hr);
        answer(() -> routes.grant(CATALOG, body(namespaceSpec("hr"), "auditors", "select"), http));
        assertThat(denials()).as("nothing was there to refuse").hasSize(2);
    }

    // ---- helpers ----

    /** What a client is told: the status, and an error's type and message. */
    private record Answer(int status, String type, String message) {
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

    private static String body(String securable, String group, String privilege) {
        return "{\"securable\":" + securable + ",\"group\":\"" + group + "\",\"privilege\":\"" + privilege + "\"}";
    }

    /** A check of select on the first securable, then modify on each other one. */
    private static String checks(String... securables) {
        StringBuilder body = new StringBuilder("{\"checks\":[");
        for (int i = 0; i < securables.length; i++) {
            body.append(i == 0 ? "" : ",").append("{\"securable\":").append(securables[i])
                    .append(",\"privilege\":\"").append(i == 0 ? "select" : "modify").append("\"}");
        }
        return body.append("]}").toString();
    }

    private static String namespaceSpec(String level) {
        return "{\"type\":\"namespace\",\"namespace\":[\"" + level + "\"]}";
    }

    private static String tableSpec(String level, String name) {
        return "{\"type\":\"table\",\"namespace\":[\"" + level + "\"],\"name\":\"" + name + "\"}";
    }

    private static String viewSpec(String level, String name) {
        return "{\"type\":\"view\",\"namespace\":[\"" + level + "\"],\"name\":\"" + name + "\"}";
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
                new Name(Names.normalize(name), name), Map.of(), 1);
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

    /** The estate above and the groups' grants on it; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog)
                            .filter(c -> !catalogGone && c.normalizedName().equals(args[0]));
                    case "findNamespace" -> namespaces.values().stream()
                            .filter(ns -> ((List<String>) args[1]).equals(List.of(ns.name().normalized())))
                            .findFirst();
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "findObject" -> objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]))
                            .findFirst();
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
