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
import java.util.function.Predicate;
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
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Ref;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The grant preview (backlog 246): it answers what the grant route would do with the same body, {@code admitted},
 * {@code unchanged} or {@code refused} with the grant route's reason, writing nothing and auditing none of its
 * answers; a caller that may not manage grants on the securable is refused as the grant route refuses it, audited.
 * {@code create} on a table or view, which means nothing there, is refused by both. The routes and the authorizer
 * are the real ones over a store double that writes a grant as the stores do (the separation check first, inside
 * the write, then unchanged for a grant already held), so this needs only Java.
 */
class GrantPreviewTest {

    private static final String CATALOG = "gate";
    private static final Instant GRANTED_AT = Instant.parse("2026-11-12T09:30:00Z");

    /** Manage-grants on the namespace sales; also in sam-team, which holds nothing. */
    private static final Caller SAM = person("sam", "stewards", "sam-team");
    /** Select on sales and describe on lobby: something there, but not manage-grants. */
    private static final Caller ANN = person("ann", "analysts");
    /** Describe on the namespace lobby only: something in the catalog, nothing on sales. */
    private static final Caller OTTO = person("otto", "outsiders");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    /** Manage-grants on sales as a steward, and in taggers; nuna.security.own-group-principals names her. */
    private static final Caller PIA = person("pia", "stewards", "taggers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), CATALOG, CATALOG);
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private int writes;

    private final NamespaceRecord sales = namespace("sales");
    private final NamespaceRecord lobby = namespace("lobby");
    private final CatalogObject orders = object(sales, "orders", Kind.TABLE);
    private final CatalogObject weekly = object(sales, "weekly", Kind.VIEW);
    /** A live branch, a tag, and a branch that expired before the clock's now. */
    private final Ref dev = ref("dev", Ref.Kind.BRANCH, Optional.of(Instant.parse("2026-12-24T00:00:00Z")));
    private final Ref eod = ref("eod", Ref.Kind.TAG, Optional.empty());
    private final Ref stale = ref("stale", Ref.Kind.BRANCH, Optional.of(Instant.parse("2026-11-23T00:00:00Z")));
    /** The changes the store was handed, with their outbox events. */
    private final List<Change> changes = new ArrayList<>();

    private final GrantRoutes routes;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = SAM;

    GrantPreviewTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        Securable onSales = new Securable(Securable.Kind.NAMESPACE, sales.id());
        grant(onSales, "stewards", Privilege.MANAGE_GRANTS);
        grant(onSales, "analysts", Privilege.SELECT);
        grant(onSales, "taggers", Privilege.MANAGE_TAGS);
        grant(new Securable(Securable.Kind.NAMESPACE, lobby.id()), "outsiders", Privilege.DESCRIBE);
        grant(new Securable(Securable.Kind.NAMESPACE, lobby.id()), "analysts", Privilege.DESCRIBE);

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = new Denials(audit::add, settings, clock);
        routes = new GrantRoutes(store, authorizer, separation, request -> caller, denials,
                new Holdings(authorizer, store, settings, separation, denials), settings, new OwnGroups(Set.of("pia")), clock);
    }

    // ---- the three answers ----

    @Test
    void aGrantNoOneHoldsIsAdmitted() {
        Map<String, Object> answer = preview(body(namespaceSpec("sales"), "auditors", "select"));
        assertThat(answer).containsOnlyKeys("securable", "group", "privilege", "answer");
        assertThat(answer).containsEntry("answer", "admitted").containsEntry("group", "auditors")
                .containsEntry("privilege", "select");
        assertThat(answer.get("securable")).isEqualTo(Map.of("type", "namespace", "id", sales.id().toString(),
                "namespace", List.of("sales")));
    }

    @Test
    void aGrantAlreadyHeldIsUnchangedWithWhoGrantedItAndWhen() {
        Map<String, Object> answer = preview(body(namespaceSpec("sales"), "analysts", "select"));
        assertThat(answer).containsOnlyKeys("securable", "group", "privilege", "answer", "granted-by", "granted-at");
        assertThat(answer).containsEntry("answer", "unchanged").containsEntry("granted-by", "gary")
                .containsEntry("granted-at", "2026-11-12T09:30:00Z");
    }

    @Test
    void aGrantToAGroupTheCallerBelongsToIsRefused() {
        String body = body(namespaceSpec("sales"), "sam-team", "select");
        Map<String, Object> answer = preview(body);
        assertThat(answer).containsOnlyKeys("securable", "group", "privilege", "answer", "refusal", "reason");
        assertThat(answer).containsEntry("answer", "refused").containsEntry("refusal", "self-grant")
                .containsEntry("reason", "no one grants to a group they belong to: sam-team");
        assertThat(answer(() -> routes.grant(CATALOG, body, http))).as("the grant route, through the same check")
                .isEqualTo(new Answer(403, "ForbiddenException", "no one grants to a group they belong to: sam-team"));
        assertThat(writes).isZero();
    }

    @Test
    void someoneTheSettingNamesGrantsToTheirOwnGroupButNotAcrossTheSeparationOfDuties() {
        caller = PIA;
        String select = body(namespaceSpec("sales"), "taggers", "select");
        assertThat(preview(select)).containsEntry("answer", "admitted");
        assertThat(answer(() -> routes.grant(CATALOG, select, http)).status()).isEqualTo(204);
        assertThat(writes).isOne();

        Map<String, Object> modify = preview(body(namespaceSpec("sales"), "taggers", "modify"));
        assertThat(modify).containsEntry("answer", "refused").containsEntry("refusal", "separation-of-duties");
    }

    @Test
    void aGrantThatBreaksTheSeparationOfDutiesIsRefusedWithTheSeparationsReason() {
        assertThat(preview(body(namespaceSpec("sales"), "taggers", "write")))
                .containsEntry("answer", "refused").containsEntry("refusal", "separation-of-duties")
                .containsEntry("reason", "group taggers holds manage-tags in this catalog, a tags duty that write"
                        + " (data) must stay separate from");
        assertThat(preview(body(namespaceSpec("sales"), "grant-admins", "select")))
                .containsEntry("answer", "refused").containsEntry("refusal", "separation-of-duties")
                .containsEntry("reason", "group grant-admins administers grants on every catalog and may hold no"
                        + " other grant");
        assertThat(preview(body(namespaceSpec("sales"), "analysts", "manage-grants")))
                .as("of two conflicts, the first in the privileges' order, as the grant route names it")
                .containsEntry("refusal", "separation-of-duties")
                .containsEntry("reason", "group analysts holds describe in this catalog, a read duty that"
                        + " manage-grants (grants) must stay separate from");
    }

    @Test
    void createIsRefusedOnATableOrViewAndAdmittedOnANamespace() {
        assertThat(preview(body(tableSpec("sales", "orders"), "builders", "create")))
                .containsEntry("answer", "refused").containsEntry("refusal", "privilege-not-on-object")
                .containsEntry("reason", "create means nothing on a table: it creates namespaces, tables and views"
                        + " in a catalog or namespace");
        assertThat(preview(body(viewSpec("sales", "weekly"), "builders", "create")))
                .containsEntry("refusal", "privilege-not-on-object")
                .containsEntry("reason", "create means nothing on a view: it creates namespaces, tables and views"
                        + " in a catalog or namespace");
        assertThat(preview(body(namespaceSpec("sales"), "builders", "create"))).containsEntry("answer", "admitted");
        assertThat(preview(body(tableSpec("sales", "orders"), "builders", "modify")))
                .containsEntry("answer", "admitted");
    }

    @Test
    void writeIsRefusedOnAViewAndAdmittedOnATable() {
        assertThat(preview(body(viewSpec("sales", "weekly"), "builders", "write")))
                .containsEntry("answer", "refused").containsEntry("refusal", "privilege-not-on-object")
                .containsEntry("reason", "write means nothing on a view: it has no rows; select reads it, and modify"
                        + " changes its definition");
        assertThat(preview(body(tableSpec("sales", "orders"), "builders", "write"))).containsEntry("answer", "admitted");
    }

    // ---- the preview and the grant agree ----

    @Test
    void theGrantRouteDoesWhatThePreviewSaid() {
        for (String body : cases()) {
            Map<String, Object> said = preview(body);
            int before = grants.size();
            Answer granted = answer(() -> routes.grant(CATALOG, body, http));
            switch ((String) said.get("answer")) {
                case "admitted" -> {
                    assertThat(granted.status()).as(body).isEqualTo(204);
                    assertThat(grants).as(body).hasSize(before + 1);
                }
                case "unchanged" -> {
                    assertThat(granted.status()).as(body).isEqualTo(204);
                    assertThat(grants).as(body).hasSize(before);
                }
                case "refused" -> {
                    assertThat(granted).as(body).isEqualTo(said.get("refusal").equals("privilege-not-on-object")
                            ? new Answer(400, "BadRequestException", (String) said.get("reason"))
                            : new Answer(403, "ForbiddenException", (String) said.get("reason")));
                    assertThat(grants).as(body).hasSize(before);
                }
                default -> throw new AssertionError("unknown answer " + said);
            }
        }
        assertThat(denials()).as("the grant route audits its self-grant and separation refusals")
                .extracting(Denial::operation).containsExactly("grant", "grant", "grant", "grant");
    }

    @Test
    void aPreviewWritesNothingAndAuditsNoneOfItsAnswers() {
        List<GrantRecord> before = List.copyOf(grants);
        for (String body : cases()) {
            preview(body);
        }
        assertThat(grants).isEqualTo(before);
        assertThat(writes).as("no write asked of the store").isZero();
        assertThat(audit).as("a preview's answers are a question, not a refused request").isEmpty();
    }

    // ---- callers who may not manage grants there ----

    @Test
    void aCallerThatMayNotManageGrantsThereIsRefusedAsTheGrantRouteRefusesIt() {
        String body = body(namespaceSpec("sales"), "auditors", "select");
        caller = ANN;
        assertThat(answer(() -> routes.preview(CATALOG, body, http)))
                .isEqualTo(new Answer(403, "ForbiddenException", "ann holds no privilege that gives manage-grants"
                        + " for previewGrant"));
        assertThat(answer(() -> routes.grant(CATALOG, body, http)).status()).isEqualTo(403);
        caller = OTTO;
        assertThat(answer(() -> routes.preview(CATALOG, body, http))).isEqualTo(answer(() -> routes.grant(CATALOG,
                body, http))).isEqualTo(new Answer(404, "NoSuchNamespaceException", "Namespace does not exist: sales"));
        caller = NED;
        assertThat(answer(() -> routes.preview(CATALOG, body, http))).isEqualTo(answer(() -> routes.grant(CATALOG,
                body, http))).isEqualTo(new Answer(404, "NoSuchWarehouseException",
                "The given warehouse does not exist: gate"));
        assertThat(writes).isZero();
    }

    @Test
    void aPreviewsRefusalOfTheCallerIsAuditedAsTheGrantRoutesIs() {
        String body = body(namespaceSpec("sales"), "auditors", "select");
        for (Caller refused : List.of(ANN, OTTO, NED)) {
            caller = refused;
            answer(() -> routes.preview(CATALOG, body, http));
        }
        assertThat(denials()).extracting(Denial::operation).containsExactly("previewGrant", "previewGrant",
                "previewGrant");
        assertThat(denials()).extracting(Denial::privilege).containsOnly("manage-grants");
        assertThat(denials()).extracting(d -> d.actor().principal()).containsExactly("ann", "otto", "ned");
        assertThat(denials().get(0).securable().id()).isEqualTo(sales.id());
        assertThat(denials().get(1).securable().id()).as("hidden from otto, still audited").isEqualTo(sales.id());
        assertThat(denials().get(2).securable().id()).as("the catalog ned holds nothing in").isEqualTo(catalog.id());
    }

    @Test
    void aMalformedPreviewIsAnsweredAsTheGrantIs() {
        for (String body : List.of(
                "{\"securable\":" + namespaceSpec("sales") + ",\"privilege\":\"select\"}",
                body(namespaceSpec("sales"), " ", "select"),
                body(namespaceSpec("sales"), "auditors", "own"),
                body("{\"namespace\":[\"sales\"]}", "auditors", "select"),
                body("{\"type\":\"table\",\"namespace\":[\"sales\"]}", "auditors", "select"),
                body(tableSpec("sales", "returns"), "auditors", "select"),
                "not json")) {
            Answer previewed = answer(() -> routes.preview(CATALOG, body, http));
            assertThat(previewed.status()).as(body).isIn(400, 404);
            assertThat(previewed).as(body).isEqualTo(answer(() -> routes.grant(CATALOG, body, http)));
        }
        assertThat(writes).isZero();
    }

    // ---- create on a table or view, at the grant route ----

    @Test
    void theGrantRouteRefusesCreateOnATableOrViewUnauditedAndARevokeStillRemovesOne() {
        assertThat(answer(() -> routes.grant(CATALOG, body(tableSpec("sales", "orders"), "builders", "create"),
                http))).isEqualTo(new Answer(400, "BadRequestException", "create means nothing on a table: it"
                + " creates namespaces, tables and views in a catalog or namespace"));
        assertThat(answer(() -> routes.grant(CATALOG, body(viewSpec("sales", "weekly"), "builders", "create"),
                http)).status()).isEqualTo(400);
        assertThat(writes).isZero();
        assertThat(audit).as("a mistake in the request, not a refused one").isEmpty();

        grant(orders.securable(), "builders", Privilege.CREATE);
        assertThat(answer(() -> routes.revoke(CATALOG, body(tableSpec("sales", "orders"), "builders", "create"),
                http)).status()).as("one granted before backlog 246").isEqualTo(204);
        assertThat(grants).noneMatch(g -> g.grant().securable().equals(orders.securable()));
    }

    @Test
    void theGrantRouteRefusesWriteOnAViewUnauditedAndARevokeStillRemovesOne() {
        assertThat(answer(() -> routes.grant(CATALOG, body(viewSpec("sales", "weekly"), "builders", "write"),
                http))).isEqualTo(new Answer(400, "BadRequestException", "write means nothing on a view: it has no"
                + " rows; select reads it, and modify changes its definition"));
        assertThat(writes).isZero();
        assertThat(audit).as("a mistake in the request, not a refused one").isEmpty();

        grant(weekly.securable(), "builders", Privilege.WRITE);
        assertThat(answer(() -> routes.revoke(CATALOG, body(viewSpec("sales", "weekly"), "builders", "write"),
                http)).status()).as("one granted before backlog 258").isEqualTo(204);
        assertThat(grants).noneMatch(g -> g.grant().securable().equals(weekly.securable()));
    }

    // ---- grants that name a branch (backlog 113) ----

    @Test
    @SuppressWarnings("unchecked")
    void aGrantMayNameABranchAndIsListedPreviewedCheckedAndRevokedWithIt() throws Exception {
        String body = branchBody(tableSpec("sales", "orders"), "dev-loaders", "write", "dev");
        assertThat(preview(body)).containsEntry("answer", "admitted").containsEntry("branch", "dev");

        assertThat(answer(() -> routes.grant(CATALOG, body, http)).status()).isEqualTo(204);
        assertThat(grants).anySatisfy(g -> {
            assertThat(g.grant().group()).isEqualTo("dev-loaders");
            assertThat(g.grant().branch()).contains(dev.id());
        });
        assertThat(changes.getLast().outbox().getFirst().payloadJson()).contains("\"branch\":\"dev\"")
                .contains("\"branchId\":\"" + dev.id() + "\"");
        assertThat(preview(body)).containsEntry("answer", "unchanged");
        assertThat(preview(body(tableSpec("sales", "orders"), "dev-loaders", "write")))
                .as("the same grant on every ref is another grant").containsEntry("answer", "admitted");

        Map<String, Object> listed = json(routes.listOn(CATALOG, "table", List.of("sales"), "orders", null, null,
                http));
        assertThat((List<Map<String, Object>>) listed.get("grants")).singleElement()
                .satisfies(g -> assertThat(g).containsEntry("group", "dev-loaders").containsEntry("branch", "dev"));

        caller = person("dl", "dev-loaders", "analysts");
        Map<String, Object> checked = json(routes.check(CATALOG, "{\"checks\":["
                + "{\"securable\":" + tableSpec("sales", "orders") + ",\"privilege\":\"write\",\"branch\":\"dev\"},"
                + "{\"securable\":" + tableSpec("sales", "orders") + ",\"privilege\":\"write\"}]}", http));
        assertThat((List<Map<String, Object>>) checked.get("results")).extracting(r -> r.get("allowed"))
                .containsExactly(true, false);

        caller = SAM;
        assertThat(answer(() -> routes.revoke(CATALOG, body(tableSpec("sales", "orders"), "dev-loaders", "write"),
                http)).status()).as("the grant on every ref is not held").isEqualTo(404);
        assertThat(answer(() -> routes.revoke(CATALOG, body, http)).status()).isEqualTo(204);
        assertThat(grants).noneMatch(g -> g.grant().group().equals("dev-loaders"));
    }

    @Test
    void aBranchGrantIsWriteOrModifyOnALiveBranchOnly() {
        assertThat(answer(() -> routes.grant(CATALOG, branchBody(tableSpec("sales", "orders"), "dev-loaders",
                "select", "dev"), http))).isEqualTo(new Answer(400, "BadRequestException", "select is not granted on"
                + " one branch: a grant that names a branch gives write or modify, what a commit on it needs; reads"
                + " and every other change ask through main"));
        assertThat(preview(branchBody(namespaceSpec("sales"), "dev-loaders", "describe", "dev")))
                .containsEntry("refusal", "privilege-not-on-a-branch");
        assertThat(answer(() -> routes.grant(CATALOG, branchBody(tableSpec("sales", "orders"), "dev-loaders",
                "write", "eod"), http)).status()).as("a tag").isEqualTo(400);
        assertThat(answer(() -> routes.grant(CATALOG, branchBody(tableSpec("sales", "orders"), "dev-loaders",
                "write", "stale"), http)).status()).as("an expired branch").isEqualTo(404);
        assertThat(answer(() -> routes.grant(CATALOG, branchBody(tableSpec("sales", "orders"), "dev-loaders",
                "write", "nowhere"), http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.grant(CATALOG, branchBody(namespaceSpec("sales"), "dev-owners", "modify",
                "dev"), http)).status()).isEqualTo(204);
        assertThat(writes).isEqualTo(1);
        assertThat(audit).as("mistakes in the request, not refused ones").isEmpty();

        grants.add(new GrantRecord(new Grant(catalog.id(), orders.securable(), "dev-loaders", "write",
                Optional.of(stale.id())), "gary", GRANTED_AT));
        assertThat(answer(() -> routes.revoke(CATALOG, branchBody(tableSpec("sales", "orders"), "dev-loaders",
                "write", "stale"), http)).status()).as("a revoke on an expired branch").isEqualTo(204);
    }

    // ---- helpers ----

    /** One body for each answer and each refusal. */
    private static List<String> cases() {
        return List.of(
                body(namespaceSpec("sales"), "auditors", "select"),
                body(namespaceSpec("sales"), "analysts", "select"),
                body(namespaceSpec("sales"), "sam-team", "select"),
                body(namespaceSpec("sales"), "taggers", "write"),
                body(namespaceSpec("sales"), "grant-admins", "select"),
                body(namespaceSpec("sales"), "analysts", "manage-grants"),
                body(tableSpec("sales", "orders"), "builders", "create"),
                body(viewSpec("sales", "weekly"), "builders", "create"),
                body(viewSpec("sales", "weekly"), "builders", "write"),
                body(namespaceSpec("sales"), "builders", "create"));
    }

    private Map<String, Object> preview(String body) {
        ResponseEntity<String> answer = routes.preview(CATALOG, body, http);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return JsonPath.read(answer.getBody(), "$");
    }

    /** What a client is told: the status, and an error's type and message. */
    private record Answer(int status, String type, String message) {
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

    private static String branchBody(String securable, String group, String privilege, String branch) {
        return "{\"securable\":" + securable + ",\"group\":\"" + group + "\",\"privilege\":\"" + privilege
                + "\",\"branch\":\"" + branch + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(ResponseEntity<String> response) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readValue(response.getBody(), Map.class);
    }

    private Ref ref(String name, Ref.Kind kind, Optional<Instant> expiresAt) {
        return new Ref(catalog.id(), UUID.randomUUID(), new Name(Names.normalize(name), name), kind,
                Instant.parse("2026-11-01T00:00:00Z"), "gary", Optional.empty(), Ref.MAIN, expiresAt);
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
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "gary", GRANTED_AT));
    }

    /**
     * The estate above and the groups' grants on it, written as the stores write a grant: {@code admits} asked of
     * the privileges the group holds in the catalog, read in another order than the listing's (the stores promise
     * none), then unchanged for a grant already held. Any other call fails.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
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
                    case "findRef" -> java.util.stream.Stream.of(dev, eod, stale)
                            .filter(r -> r.catalogId().equals(args[0]) && r.name().normalized().equals(args[1]))
                            .findFirst();
                    case "readRef" -> java.util.stream.Stream.of(dev, eod, stale).filter(r -> r.id().equals(args[0]))
                            .findFirst();
                    case "listGrantsOn" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().securable().equals(args[0])).toList(), Optional.empty());
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "putGrantIf" -> {
                        writes++;
                        changes.add((Change) args[2]);
                        Grant grant = (Grant) args[0];
                        List<String> held = grants.stream().filter(g -> g.grant().catalogId().equals(grant.catalogId())
                                && g.grant().group().equals(grant.group())).map(g -> g.grant().privilege()).toList()
                                .reversed();
                        if (!((Predicate<List<String>>) args[3]).test(held)) {
                            yield new WriteResult.Refused(WriteResult.Reason.NOT_ADMITTED);
                        }
                        if (grants.stream().anyMatch(g -> g.grant().equals(grant))) {
                            yield WriteResult.UNCHANGED;
                        }
                        grants.add(new GrantRecord(grant, (String) args[1], Instant.now()));
                        yield WriteResult.APPLIED;
                    }
                    case "revokeGrant" -> {
                        writes++;
                        yield grants.removeIf(g -> g.grant().equals(args[0])) ? WriteResult.APPLIED
                                : new WriteResult.Missing(WriteResult.Subject.GRANT);
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
