package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * Go to a path's search (Atelier's review, S1; backlog 198): every namespace, table and view whose name holds a
 * fragment, in every declared catalog, listed only where the caller may describe it, by the rules each listing uses,
 * for each role; a caller who holds nothing finds nothing, at once, and searching writes no denial; a hidden match costs
 * what no match costs; paged, namespaces first. The route and authorizer are the real ones over a store double, which
 * fails any call it does not expect, so this needs only Java.
 */
class NameSearchRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-23T07:00:00Z");

    /** Describe on the namespace desk. */
    private static final Caller RITA = person("rita", "desk-readers");
    /** Select on the table desk.trades only. */
    private static final Caller TOM = person("tom", "trades-readers");
    /** Describe on the namespace lobby only: he holds something in the catalog, nothing on desk. */
    private static final Caller OTTO = person("otto", "outsiders");
    /** Nothing in any catalog. */
    private static final Caller NED = person("ned", "newcomers");
    /** Describe on the catalog archive only. */
    private static final Caller ADA = person("ada", "archivists");
    /** A grant admin: manage-grants on every catalog, which describes everything. */
    private static final Caller GRETA = person("greta", "grant-admins");
    /** A platform operator: every catalog, no objects. */
    private static final Caller OPAL = person("opal", "platform-ops");
    /** A trusted engine acting as itself, its own groups holding nothing. */
    private static final Caller ENGINE = new Caller(new Principal("spark", Set.of("engines")), "spark", true,
            Optional.empty());
    /** The engine acting for tom: tom's groups decide. */
    private static final Caller ENGINE_FOR_TOM = new Caller(new Principal("spark", Set.of("desk-readers")), "spark",
            true, Optional.of(TOM.principal()));

    private final CatalogRecord gate = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final CatalogRecord archive = new CatalogRecord(UUID.randomUUID(), "archive", "Archive");
    /** In the store, not declared: never searched, whatever the caller holds there. */
    private final CatalogRecord attic = new CatalogRecord(UUID.randomUUID(), "attic", "attic");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    /** The store calls made, by method, in order. */
    private final List<String> calls = new ArrayList<>();

    private final NamespaceRecord desk = namespace(gate, null, "desk");
    private final NamespaceRecord lobby = namespace(gate, null, "lobby");
    private final NamespaceRecord eu = namespace(gate, desk, "eu");
    private final NamespaceRecord xetra = namespace(gate, eu, "xetra");
    private final CatalogObject trades = object(desk, "trades", Kind.TABLE);
    private final CatalogObject positions = object(desk, "positions", Kind.TABLE);
    private final CatalogObject daily = object(desk, "daily_trades", Kind.VIEW);
    private final CatalogObject visitors = object(lobby, "visitors", Kind.TABLE);
    private final CatalogObject quotes = object(xetra, "quotes", Kind.TABLE);
    private final NamespaceRecord old = namespace(archive, null, "old_desk");
    private final CatalogObject oldTrades = object(old, "trades_2019", Kind.TABLE);
    private final NamespaceRecord dusty = namespace(attic, null, "dusty_desk");
    private final CatalogObject atticTrades = object(dusty, "trades_attic", Kind.TABLE);

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final NameSearchRoutes routes;
    private Caller caller = RITA;

    NameSearchRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(gate, new Securable(Securable.Kind.NAMESPACE, desk.id()), "desk-readers", Privilege.DESCRIBE);
        grant(gate, trades.securable(), "trades-readers", Privilege.SELECT);
        grant(gate, new Securable(Securable.Kind.NAMESPACE, lobby.id()), "outsiders", Privilege.DESCRIBE);
        grant(archive, new Securable(Securable.Kind.CATALOG, archive.id()), "archivists", Privilege.DESCRIBE);
        grant(attic, new Securable(Securable.Kind.CATALOG, attic.id()), "desk-readers", Privilege.DESCRIBE);

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> caller, settings, holdings);
        Map<String, StorageSettings.Catalog> declared = new LinkedHashMap<>();
        declared.put("gate", new StorageSettings.Catalog("store", "keys", "Test Entity"));
        declared.put("Archive", new StorageSettings.Catalog("store", "keys", "Test Entity"));
        StorageSettings storage = new StorageSettings(Map.of(), declared, Duration.ofMinutes(30), 10, true);
        routes = new NameSearchRoutes(store, management, holdings, authorizer, storage);
    }

    @Test
    void aNameTypedAloneIsFoundWithItsCatalogPathAndKind() {
        assertThat(found("quot")).containsExactly("gate table desk.eu.xetra.quotes");
        assertThat(found("TRADES")).as("any case, a table and a view, namespaces first then tables then views")
                .containsExactly("gate table desk.trades", "gate view desk.daily_trades");
        assertThat(found("eu")).containsExactly("gate namespace desk.eu");
    }

    @Test
    void onlyWhatTheCallerMayDescribeIsListed() {
        caller = TOM;
        assertThat(found("trades")).as("select on one table describes it, and no other there")
                .containsExactly("gate table desk.trades");
        assertThat(found("desk")).as("a grant on a table in desk shows desk, as Iceberg's listing does")
                .containsExactly("gate namespace desk");
        assertThat(found("o")).doesNotContain("gate table desk.positions", "gate table lobby.visitors",
                "gate namespace lobby");

        caller = OTTO;
        assertThat(found("trades")).isEmpty();
        assertThat(found("o")).containsExactly("gate namespace lobby", "gate table lobby.visitors");

        caller = ADA;
        assertThat(found("trades")).containsExactly("Archive table old_desk.trades_2019");

        caller = NED;
        for (String fragment : List.of("a", "e", "s", "trades", "desk")) {
            assertThat(found(fragment)).as(fragment).isEmpty();
        }
        assertThat(audit).as("searching refuses nothing, so writes no denial").isEmpty();
    }

    @Test
    void eachRoleFindsWhatItMayDescribe() {
        caller = GRETA;
        assertThat(found("trades")).as("a grant admin describes everything in every declared catalog")
                .containsExactlyInAnyOrder("gate table desk.trades", "Archive table old_desk.trades_2019",
                        "gate view desk.daily_trades");
        assertThat(found("lobby")).containsExactly("gate namespace lobby");

        for (Caller none : List.of(OPAL, ENGINE)) {
            caller = none;
            for (String fragment : List.of("a", "e", "s", "trades", "desk")) {
                assertThat(found(fragment)).as(none.principal().id() + " " + fragment)
                        .as("catalogs, not objects: every catalog, no name in it").isEmpty();
            }
        }

        caller = ENGINE_FOR_TOM;
        assertThat(found("s")).as("an engine acting for tom finds what tom may describe, not what its own groups hold")
                .containsExactly("gate namespace desk", "gate table desk.trades");
        assertThat(audit).isEmpty();
    }

    @Test
    void oneCatalogMayBeAskedForAndOneTheCallerHoldsNothingInIsAnsweredAsNoneAndAudited() {
        caller = ADA;
        assertThat(names(ask("trades", "archive", null, null))).containsExactly("Archive table old_desk.trades_2019");
        assertThat(audit).isEmpty();
        CatalogError held = (CatalogError) catchThrowable(() -> ask("trades", "gate", null, null));
        CatalogError none = (CatalogError) catchThrowable(() -> ask("trades", "nowhere", null, null));
        CatalogError attic = (CatalogError) catchThrowable(() -> ask("trades", "attic", null, null));
        assertThat(List.of(held, none, attic)).as("ada holds nothing in gate: answered as no catalog")
                .extracting(CatalogError::status).containsOnly(404);
        assertThat(held.getMessage()).isEqualTo(none.getMessage().replace("nowhere", "gate"));
        assertThat(audit).as("the refusal in gate, audited (item 68); none for a catalog no route serves").hasSize(1);
        caller = RITA;
        assertThat(names(ask("trades", "gate", null, null)))
                .containsExactly("gate table desk.trades", "gate view desk.daily_trades");
    }

    @Test
    void anUnclassifiedTableInARegulatedAreaIsStillFound() {
        attributes.add(new GovernanceAttribute(new Securable(Securable.Kind.CATALOG, gate.id()),
                GovernanceAttribute.Kind.REGULATED, "true", "test", "test", NOW));
        assertThat(found("trades")).as("closed until classified, but describe is still allowed (D27)")
                .containsExactly("gate table desk.trades", "gate view desk.daily_trades");
        caller = TOM;
        assertThat(found("trades")).containsExactly("gate table desk.trades");
    }

    @Test
    void aCatalogNotDeclaredIsNeverSearched() {
        assertThat(found("attic")).isEmpty();
        assertThat(found("dusty")).isEmpty();
        assertThat(found("trades")).doesNotContain("attic table dusty_desk.trades_attic");
    }

    @Test
    void oneWhoHoldsNothingIsAnsweredAtOnce() {
        caller = NED;
        calls.clear();
        JsonNode body = ask("s", null, null, null);
        assertThat(body.path("names").isEmpty()).isTrue();
        assertThat(body.has("next-page-token")).as("no pages to count the deployment by").isFalse();
        assertThat(calls).as("no walk").doesNotContain("listActiveObjects", "listNamespaces");
    }

    @Test
    void aHiddenMatchCostsWhatNoMatchCosts() {
        caller = TOM;
        found("zzqq");
        calls.clear();
        assertThat(found("positions")).isEmpty();
        List<String> hidden = List.copyOf(calls);
        calls.clear();
        assertThat(found("zzqq")).isEmpty();
        assertThat(hidden).as("the store calls of a search whose only matches tom may not see (D18)")
                .isEqualTo(List.copyOf(calls));
    }

    @Test
    void pagesHoldWhatTheyAskAndTogetherEverything() {
        List<String> all = found("s");
        assertThat(all).containsExactlyInAnyOrder("gate namespace desk", "gate table desk.positions",
                "gate table desk.eu.xetra.quotes", "gate table desk.trades",
                "gate view desk.daily_trades");
        for (int size = 1; size <= 3; size++) {
            List<String> paged = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                JsonNode body = ask("s", null, token, size);
                assertThat(body.path("names").size()).isLessThanOrEqualTo(size);
                paged.addAll(names(body));
                token = body.path("next-page-token").isMissingNode() ? null : body.path("next-page-token").asString();
                assertThat(++pages).isLessThan(50);
            } while (token != null);
            assertThat(paged).as("page size " + size).containsExactlyInAnyOrderElementsOf(all);
        }
    }

    @Test
    void aBlankFragmentOrAForeignTokenIsRefused() {
        assertThat(catchThrowable(() -> ask(" ", null, null, null))).isInstanceOf(CatalogError.class);
        assertThat(catchThrowable(() -> ask("s", null, "not-ours", null))).isInstanceOf(CatalogError.class);
        for (String token : List.of(PageToken.encode("x", "", "0"), PageToken.encode("t", "", "-1"),
                PageToken.encode("t", "", "x"), PageToken.encode("t", "", Integer.toString(NameSearchRoutes.LOOK)))) {
            assertThat(catchThrowable(() -> ask("s", null, token, null))).as(token).isInstanceOf(CatalogError.class);
        }
    }

    // ---- helpers ----

    private List<String> found(String fragment) {
        return names(ask(fragment, null, null, NameSearchRoutes.MAX_PAGE));
    }

    private JsonNode ask(String fragment, String catalog, String token, Integer size) {
        ResponseEntity<String> response = routes.names(fragment, catalog, token, size, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static List<String> names(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : body.path("names")) {
            List<String> path = new ArrayList<>();
            row.path("namespace").forEach(level -> path.add(level.asString()));
            path.add(row.path("name").asString());
            out.add(row.path("catalog").asString() + " " + row.path("kind").asString() + " " + String.join(".", path));
        }
        return out;
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(CatalogRecord catalog, NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(),
                parent == null ? catalog.id() : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private CatalogObject object(NamespaceRecord namespace, String name, Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(namespace.catalogId(), id, namespace.id(),
                new Name(Names.normalize(name), name), kind, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(CatalogRecord catalog, Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    /** One page of {@code items}, {@code page.size()} at a time, as a store pages them. */
    private static <T> Page<T> page(List<T> items, PageRequest page) {
        int from = page.token().map(token -> Integer.parseInt(PageToken.decode(token, 1).getFirst())).orElse(0);
        int to = Math.min(items.size(), from + page.size());
        return new Page<>(List.copyOf(items.subList(from, to)),
                to < items.size() ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
    }

    /** The two catalogs, their namespaces, objects and grants. Any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> calls.add(method.getName()) ? switch (method.getName()) {
                    case "findCatalog" -> List.of(gate, archive, attic).stream()
                            .filter(c -> c.normalizedName().equals(args[0])).findFirst();
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "listNamespaces" -> page(namespaces.values().stream()
                            .filter(ns -> ns.parentId().equals(args[0]))
                            .sorted(Comparator.comparing((NamespaceRecord ns) -> ns.name().normalized())
                                    .thenComparing(NamespaceRecord::id))
                            .toList(), (PageRequest) args[1]);
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "listActiveObjects" -> page(objects.values().stream()
                            .filter(o -> o.kind() == args[0] && o.state() == CatalogObject.State.ACTIVE)
                            .sorted(Comparator.comparing(CatalogObject::id))
                            .toList(), (PageRequest) args[1]);
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((java.util.Collection<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                } : null);
    }
}
