package nuna.catalog.server.rest;

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
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Names are hidden from callers without access (D18, amended 2026-11-19; backlog 14). A caller refused on a
 * namespace, table or view it holds nothing on gets exactly what the route answers when the name does not exist:
 * each case asks once with the name there, then again once the object is gone, then once its namespace is gone
 * too, and expects one answer. A caller that holds something on the name, any grant on it or above it (every
 * privilege implies describe) or, for a namespace, on anything in it, is still refused with 403. Every refusal is
 * audited as before. The routes run against a store double, so this needs only Java; {@code SecurityGateTest}
 * checks the same on the wire. Unregister needs the commit engine and is covered there only.
 */
class HiddenNamesTest {

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}}""";
    private static final String VIEW = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"x","required":false,"type":"int"}]},
             "view-version":{"version-id":1,"timestamp-ms":1,"schema-id":0,"summary":{"engine-name":"spark"},
              "representations":[{"type":"sql","sql":"select 1 as x","dialect":"spark"}],"default-namespace":[]},
             "properties":{}}""";
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"k":"v"}}]}""";
    private static final String REPORT = """
            {"report-type":"commit-report","table-name":"staff","snapshot-id":1,"sequence-number":1,
             "operation":"append","metrics":{}}""";
    private static final String REGISTER = """
            {"name":"back","metadata-location":"s3://gate/hr/back/metadata/v1.metadata.json"}""";

    private static final Caller OTTO = person("otto", "outsiders");
    /** Select on the namespace sales. */
    private static final Caller ANN = person("ann", "analysts");
    /** Modify on the namespace sales. */
    private static final Caller MO = person("mo", "sales-owners");
    /** Select on the table hr.staff only. */
    private static final Caller TOM = person("tom", "staff-readers");
    /** Describe on the table hr.salaries only. */
    private static final Caller DEE = person("dee", "describers");
    /** Select on the table hr.payroll.slips only. */
    private static final Caller PAM = person("pam", "payroll");
    private static final Caller GINA = person("gina", "grant-admins");
    private static final Caller OLGA = person("olga", "platform-ops");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<IdempotencyResult> kept = new ArrayList<>();
    private final List<String> released = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();

    private final NamespaceRecord sales = namespace(null, "sales");
    private final NamespaceRecord hr = namespace(null, "hr");
    private final NamespaceRecord payroll = namespace(hr, "payroll");
    private final CatalogObject orders = object(sales, "orders", Kind.TABLE);
    private final CatalogObject staff = object(hr, "staff", Kind.TABLE);
    private final CatalogObject salaries = object(hr, "salaries", Kind.TABLE);
    private final CatalogObject roster = object(hr, "roster", Kind.VIEW);
    private final CatalogObject slips = object(payroll, "slips", Kind.TABLE);

    private final NamespaceRoutes namespaceRoutes;
    private final TableRoutes tables;
    private final ViewRoutes views;
    private final TransactionRoutes transactions;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = OTTO;

    HiddenNamesTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(new Securable(Securable.Kind.NAMESPACE, sales.id()), "analysts", Privilege.SELECT);
        grant(new Securable(Securable.Kind.NAMESPACE, sales.id()), "sales-owners", Privilege.MODIFY);
        grant(staff.securable(), "staff-readers", Privilege.SELECT);
        grant(salaries.securable(), "describers", Privilege.DESCRIBE);
        grant(slips.securable(), "payroll", Privilege.SELECT);

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Authorizer authorizer = new Authorizer(store, store, store,
                new Separation(Set.of("grant-admins"), Set.of("platform-ops")), false, Set.of());
        Guard guard = new Guard(authorizer, DenialsForTests.denials(publisher, settings, clock), publisher, settings,
                clock, store);
        Lookup lookup = new Lookup(store, settings);
        Idempotency idempotency = new Idempotency(store, clock);
        AuditEvents events = new AuditEvents(settings);
        Callers callers = request -> caller;
        ObjectRoutes objectRoutes = new ObjectRoutes(store, lookup, idempotency, events, settings, guard, clock);
        // No engine, vending, metadata or meters: every case here is answered before any of them is reached.
        namespaceRoutes = new NamespaceRoutes(store, lookup, idempotency, events, callers, guard, clock);
        tables = new TableRoutes(store, lookup, objectRoutes, null, null, null,
                (name, levels, table, summary) -> reported.add(table), null, settings, callers, guard, clock, null,
                null);
        views = new ViewRoutes(store, lookup, objectRoutes, null, null, callers, guard, null, null);
        transactions = new TransactionRoutes(lookup, null, settings, callers, guard, null);
    }

    // ---- HEAD, loads and the metrics route: what the item and D18's amendment name ----

    @Test
    void headAnswers404AsForATableOrViewThatDoesNotExist() {
        ResponseEntity<Void> hidden = tables.exists("gate", "hr", "staff", http);
        assertThat(hidden).isEqualTo(ResponseEntity.notFound().build());
        forget(staff);
        assertThat(tables.exists("gate", "hr", "staff", http)).isEqualTo(hidden);
        forget(hr);
        assertThat(tables.exists("gate", "hr", "staff", http)).isEqualTo(hidden);
    }

    @Test
    void headOnAViewAnswers404AsForOneThatDoesNotExist() {
        ResponseEntity<Void> hidden = views.exists("gate", "hr", "roster", http);
        assertThat(hidden).isEqualTo(ResponseEntity.notFound().build());
        forget(roster);
        assertThat(views.exists("gate", "hr", "roster", http)).isEqualTo(hidden);
    }

    @Test
    void headOnANamespaceAnswers404AsForOneThatDoesNotExist() {
        ResponseEntity<Void> hidden = namespaceRoutes.exists("gate", "hr", http);
        assertThat(hidden).isEqualTo(ResponseEntity.notFound().build());
        forget(hr);
        assertThat(namespaceRoutes.exists("gate", "hr", http)).isEqualTo(hidden);
    }

    @Test
    void loadsAnswerAsForATableThatDoesNotExist() {
        assertThat(alike(() -> tables.load("gate", "hr", "staff", null, null, null, null, http), staff, hr))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
    }

    @Test
    void credentialsAnswerAsForATableThatDoesNotExist() {
        assertThat(alike(() -> tables.credentials("gate", "hr", "staff", null, http), staff, hr))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
    }

    @Test
    void viewLoadsAnswerAsForAViewThatDoesNotExist() {
        assertThat(alike(() -> views.load("gate", "hr", "roster", http), roster, hr))
                .isEqualTo(new Answer(404, "NoSuchViewException", "View does not exist: hr.roster"));
    }

    @Test
    void namespaceLoadsAnswerAsForANamespaceThatDoesNotExist() {
        assertThat(alike(() -> namespaceRoutes.load("gate", "hr", http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
    }

    @Test
    void theMetricsRouteAnswersAsForATableThatDoesNotExistAndReportsNothing() {
        assertThat(alike(() -> tables.metrics("gate", "hr", "staff", REPORT, http), staff, hr))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
        assertThat(reported).isEmpty();
    }

    // ---- every other route that names a namespace, table or view ----

    @Test
    void listingsInANamespaceAnswerAsForOneThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr");
        assertThat(answer(() -> tables.list("gate", "hr", null, null, http))).isEqualTo(missing);
        assertThat(answer(() -> views.list("gate", "hr", null, null, http))).isEqualTo(missing);
        assertThat(answer(() -> namespaceRoutes.list("gate", "hr", null, null, http))).isEqualTo(missing);
        forget(hr);
        assertThat(answer(() -> tables.list("gate", "hr", null, null, http))).isEqualTo(missing);
        assertThat(answer(() -> views.list("gate", "hr", null, null, http))).isEqualTo(missing);
        assertThat(answer(() -> namespaceRoutes.list("gate", "hr", null, null, http))).isEqualTo(missing);
    }

    @Test
    void namespaceChangesAnswerAsForANamespaceThatDoesNotExist() {
        Answer missing = new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr");
        List<Callable<ResponseEntity<?>>> calls = List.of(
                () -> namespaceRoutes.drop("gate", "hr", null, http),
                () -> namespaceRoutes.updateProperties("gate", "hr", null, "{\"removals\":[],\"updates\":{\"k\":\"v\"}}",
                        http),
                () -> namespaceRoutes.create("gate", null, "{\"namespace\":[\"hr\",\"new\"]}", http),
                () -> tables.create("gate", "hr", null, null, TABLE.formatted("fresh"), http),
                () -> tables.register("gate", "hr", null, null, REGISTER, http),
                () -> views.create("gate", "hr", null, VIEW.formatted("fresh"), http),
                () -> views.register("gate", "hr", null, REGISTER, http));
        for (Callable<ResponseEntity<?>> call : calls) {
            assertThat(answer(call)).isEqualTo(missing);
        }
        forget(hr);
        for (Callable<ResponseEntity<?>> call : calls) {
            assertThat(answer(call)).isEqualTo(missing);
        }
    }

    @Test
    void objectChangesAnswerAsForAnObjectThatDoesNotExist() {
        Answer noTable = new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff");
        assertThat(alike(() -> tables.drop("gate", "hr", "staff", null, http), staff, hr)).isEqualTo(noTable);
        objects.put(staff.id(), staff);
        namespaces.put(hr.id(), hr);
        assertThat(alike(() -> tables.commit("gate", "hr", "staff", null, null, SET_PROPERTY, http), staff, hr))
                .isEqualTo(noTable);
        objects.put(staff.id(), staff);
        namespaces.put(hr.id(), hr);
        assertThat(alike(() -> transactions.commit("gate", null, transaction("staff"), http), staff, hr))
                .isEqualTo(noTable);

        Answer noView = new Answer(404, "NoSuchViewException", "View does not exist: hr.roster");
        assertThat(alike(() -> views.drop("gate", "hr", "roster", null, http), roster)).isEqualTo(noView);
        objects.put(roster.id(), roster);
        assertThat(alike(() -> views.replace("gate", "hr", "roster", null, SET_PROPERTY, http), roster, hr))
                .isEqualTo(noView);
    }

    @Test
    void aRenameAsksAboutItsSourceBeforeLookingForItsDestination() {
        String toNowhere = rename("hr", "staff", "nowhere", "moved");
        assertThat(alike(() -> tables.rename("gate", null, toNowhere, http), staff, hr))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
    }

    @Test
    void aRenameIntoANamespaceTheCallerMayNotSeeAnswersAsIfItDidNotExist() {
        caller = MO;
        String intoHr = rename("sales", "orders", "hr", "orders");
        assertThat(alike(() -> tables.rename("gate", null, intoHr, http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
    }

    @Test
    void aTransactionAsksAboutEachTableBeforeLookingForRepeats() {
        assertThat(alike(() -> transactions.commit("gate", null, transaction("staff", "staff"), http), staff))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
    }

    @Test
    void aBadTableNameIsCheckedOnlyOnceTheCallerMaySeeTheNamespace() {
        assertThat(alike(() -> tables.create("gate", "hr", null, null, TABLE.formatted(""), http), hr))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
    }

    // ---- who may see a name ----

    @Test
    void aCallerThatHoldsAnythingOnTheObjectIsRefusedWith403() {
        caller = DEE;
        assertThat(tables.exists("gate", "hr", "salaries", http).getStatusCode().value()).isEqualTo(204);
        assertThat(answer(() -> tables.load("gate", "hr", "salaries", null, null, null, null, http)).status())
                .isEqualTo(403);
        assertThat(answer(() -> tables.metrics("gate", "hr", "salaries", REPORT, http)).status()).isEqualTo(403);

        caller = ANN;
        assertThat(answer(() -> tables.commit("gate", "sales", "orders", null, null, SET_PROPERTY, http)).status())
                .as("select on the namespace holds something on every table in it").isEqualTo(403);
        assertThat(answer(() -> tables.create("gate", "sales", null, null, TABLE.formatted("fresh"), http)).status())
                .isEqualTo(403);
    }

    @Test
    void aGrantOnAnythingInANamespaceLetsTheCallerSeeTheNamespace() {
        caller = TOM;
        assertThat(answer(() -> tables.list("gate", "hr", null, null, http)).status()).isEqualTo(403);
        assertThat(answer(() -> namespaceRoutes.load("gate", "hr", http)).status()).isEqualTo(403);
        caller = PAM;
        assertThat(answer(() -> namespaceRoutes.load("gate", "hr", http)).status())
                .as("a grant on a table in a namespace below").isEqualTo(403);
        assertThat(answer(() -> namespaceRoutes.list("gate", "hr", null, null, http)).status()).isEqualTo(403);
        assertThat(answer(() -> namespaceRoutes.load("gate", "sales", http)))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: sales"));
    }

    @Test
    void aGrantOnOneObjectRevealsNoOtherObjectBesideIt() {
        caller = TOM;
        assertThat(tables.exists("gate", "hr", "salaries", http)).isEqualTo(ResponseEntity.notFound().build());
        assertThat(alike(() -> tables.load("gate", "hr", "salaries", null, null, null, null, http), salaries))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.salaries"));
    }

    @Test
    void aCommitToANameNoObjectHoldsIsJudgedAsAnObjectThereWouldBe() {
        // Tom holds something in hr, on staff, but nothing on hr itself or on salaries: whether salaries exists
        // or the commit would create it, he is told it does not exist.
        caller = TOM;
        assertThat(alike(() -> tables.commit("gate", "hr", "salaries", null, null, SET_PROPERTY, http), salaries))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.salaries"));
        assertThat(alike(() -> views.replace("gate", "hr", "roster", null, SET_PROPERTY, http), roster))
                .isEqualTo(new Answer(404, "NoSuchViewException", "View does not exist: hr.roster"));
    }

    @Test
    void aGrantAdminSeesEveryNameAndAPlatformOperatorNone() {
        caller = GINA;
        assertThat(answer(() -> tables.load("gate", "hr", "staff", null, null, null, null, http)).status())
                .isEqualTo(403);
        caller = OLGA;
        assertThat(answer(() -> tables.load("gate", "hr", "staff", null, null, null, null, http)))
                .isEqualTo(new Answer(404, "NoSuchTableException", "Table does not exist: hr.staff"));
    }

    @Test
    void aTrustedEngineIsAnsweredAsForTheEndUserItActsFor() {
        Principal engine = new Principal("svc-trino", Set.of("staff-readers"));
        caller = new Caller(engine, "trino", true, Optional.of(OTTO.principal()));
        assertThat(answer(() -> tables.list("gate", "hr", null, null, http)))
                .isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: hr"));
        caller = new Caller(new Principal("svc-trino", Set.of("outsiders")), "trino", true,
                Optional.of(TOM.principal()));
        assertThat(answer(() -> tables.list("gate", "hr", null, null, http)).status()).isEqualTo(403);
    }

    // ---- the record and the key ----

    @Test
    void aHiddenRefusalIsAuditedAsEveryRefusalIs() {
        answer(() -> tables.load("gate", "hr", "staff", null, null, null, null, http));
        views.exists("gate", "hr", "roster", http);
        answer(() -> namespaceRoutes.load("gate", "hr", http));

        assertThat(denials()).extracting(Denial::operation)
                .containsExactly("loadTable", "viewExists", "loadNamespaceMetadata");
        Denial load = denials().getFirst();
        assertThat(load.privilege()).isEqualTo("select");
        assertThat(load.reason()).startsWith("no_privilege");
        assertThat(load.securable().id()).isEqualTo(staff.id());
        assertThat(load.securable().path()).containsExactly("hr", "staff");
        assertThat(load.actor().principal()).isEqualTo("otto");
        assertThat(denials().get(2).securable().path()).containsExactly("hr");

        forget(staff);
        answer(() -> tables.load("gate", "hr", "staff", null, null, null, null, http));
        assertThat(denials()).as("nothing was there to refuse").hasSize(3);
    }

    @Test
    void anIdempotencyKeyKeepsAHiddenAnswerAsItKeepsAMissingOne() {
        assertThat(answer(() -> namespaceRoutes.drop("gate", "hr", "k1", http)).status()).isEqualTo(404);
        forget(hr);
        assertThat(answer(() -> namespaceRoutes.drop("gate", "hr", "k2", http)).status()).isEqualTo(404);
        assertThat(kept).extracting(IdempotencyResult::key).containsExactly("k1", "k2");
        assertThat(kept.get(0).status()).isEqualTo(kept.get(1).status());
        assertThat(kept.get(0).body()).isEqualTo(kept.get(1).body());
        assertThat(released).isEmpty();

        namespaces.put(hr.id(), hr);
        caller = TOM;
        assertThat(answer(() -> namespaceRoutes.drop("gate", "hr", "k3", http)).status()).isEqualTo(403);
        assertThat(released).as("a 403 is not kept, so a retry after a grant runs afresh").containsExactly("k3");
        assertThat(kept).hasSize(2);
    }

    // ---- helpers ----

    /** What a client is told: the status, and an error's type and message. */
    private record Answer(int status, String type, String message) {
    }

    /** The answer, the same once each of {@code gone} is forgotten in turn. */
    private Answer alike(Callable<? extends ResponseEntity<?>> call, Object... gone) {
        Answer hidden = answer(call);
        for (Object record : gone) {
            forget(record);
            assertThat(answer(call)).as("once %s is gone", record).isEqualTo(hidden);
        }
        return hidden;
    }

    /** A route's answer as ErrorResponses renders a thrown error. */
    private static Answer answer(Callable<? extends ResponseEntity<?>> call) {
        try {
            ResponseEntity<?> response = call.call();
            int status = response.getStatusCode().value();
            if (response.getBody() instanceof String json && response.getStatusCode().isError()) {
                return new Answer(status, JsonPath.read(json, "$.error.type"), JsonPath.read(json, "$.error.message"));
            }
            return new Answer(status, null, null);
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
        return new Caller(new Principal(id, Set.of(groups)), "spark", false, Optional.empty());
    }

    private static String rename(String fromNamespace, String from, String toNamespace, String to) {
        return "{\"source\":{\"namespace\":[\"" + fromNamespace + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + toNamespace + "\"],\"name\":\"" + to + "\"}}";
    }

    private static String transaction(String... tables) {
        StringBuilder body = new StringBuilder("{\"table-changes\":[");
        for (int i = 0; i < tables.length; i++) {
            body.append(i == 0 ? "" : ",").append("{\"identifier\":{\"namespace\":[\"hr\"],\"name\":\"")
                    .append(tables[i]).append("\"},").append(SET_PROPERTY.strip().substring(1));
        }
        return body.append("]}").toString();
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

    /** The estate above, the groups' grants on it, and idempotency keys; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
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
                    case "attributesOf" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "claimKey" -> new KeyState.Claimed();
                    case "completeKey" -> {
                        kept.add((IdempotencyResult) args[0]);
                        yield WriteResult.APPLIED;
                    }
                    case "releaseKey" -> {
                        released.add((String) args[0]);
                        yield WriteResult.APPLIED;
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
