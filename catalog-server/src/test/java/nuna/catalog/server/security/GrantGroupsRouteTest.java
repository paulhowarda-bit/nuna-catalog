package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantGroup;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The groups that hold a grant in a catalog, for the console's group picker (backlog 247):
 * {@code GET .../grants/groups} answers each with its duties and privileges, and the deployment's grant-admin and
 * platform-operator groups, to those who manage the catalog's grants, and refuses everyone else as the export does,
 * audited, without reading the groups. The route and authorizer are the real ones over a store double, so this
 * needs only Java; {@code GrantSuite} checks the store's listing on every database.
 */
class GrantGroupsRouteTest {

    private static final String CATALOG = "gate";

    /** Manage-grants on the catalog. */
    private static final Caller GIL = person("gil", "keepers");
    /** In a deployment grant-admin group: manage-grants on every catalog by configuration. */
    private static final Caller GINA = person("gina", "grant-admins");
    /** Manage-grants on the namespace sales only. */
    private static final Caller SAM = person("sam", "stewards");
    /** Select and describe on the catalog. */
    private static final Caller ANN = person("ann", "analysts");
    /** A platform operator: sees every catalog, holds no grant. */
    private static final Caller OLGA = person("olga", "platform-ops");
    /** Nothing anywhere in the catalog. */
    private static final Caller NED = person("ned", "newcomers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), CATALOG, CATALOG);
    private final NamespaceRecord sales = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name(Names.normalize("sales"), "sales"), Map.of(), 1);
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final List<PageRequest> groupListings = new ArrayList<>();

    private final GrantRoutes routes;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = GIL;

    GrantGroupsRouteTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        Securable onCatalog = new Securable(Securable.Kind.CATALOG, catalog.id());
        Securable onSales = new Securable(Securable.Kind.NAMESPACE, sales.id());
        grant(onCatalog, "keepers", Privilege.MANAGE_GRANTS.wireName());
        grant(onSales, "stewards", Privilege.MANAGE_GRANTS.wireName());
        grant(onCatalog, "analysts", Privilege.DESCRIBE.wireName());
        grant(onSales, "analysts", Privilege.SELECT.wireName());
        grant(onCatalog, "analysts", Privilege.SELECT.wireName());
        grant(onSales, "loaders", Privilege.WRITE.wireName());
        grant(onSales, "loaders", Privilege.CREATE.wireName());
        grant(onSales, "taggers", Privilege.MANAGE_TAGS.wireName());
        grant(onSales, "taggers", Privilege.DESCRIBE.wireName());
        grant(onSales, "futurists", "foresee");
        grant(onSales, "futurists", Privilege.SELECT.wireName());

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins", "auditor-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = new Denials(audit::add, settings, clock);
        routes = new GrantRoutes(store, authorizer, separation, request -> caller, denials,
                new Holdings(authorizer, store, settings, separation, denials), settings, OwnGroups.NONE, clock);
    }

    @Test
    void aGrantManagerOfTheCatalogGetsEachGroupWithItsDutiesAndTheDeploymentsGroups() {
        String answer = routes.listGroups(CATALOG, null, null, http).getBody();

        assertThat(JsonPath.<List<String>>read(answer, "$.groups[*].group"))
                .containsExactly("analysts", "futurists", "keepers", "loaders", "stewards", "taggers");
        assertThat(JsonPath.<Map<String, Object>>read(answer, "$.groups[0]")).isEqualTo(Map.of(
                "group", "analysts", "duties", List.of("read"), "privileges", List.of("describe", "select")));
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'loaders')].duties[*]"))
                .containsExactly("data");
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'loaders')].privileges[*]"))
                .containsExactly("create", "write");
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'keepers')].duties[*]"))
                .containsExactly("grants");
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'stewards')].duties[*]"))
                .as("manage-grants on a namespace is the grants duty too").containsExactly("grants");
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'taggers')].duties[*]"))
                .containsExactly("read", "tags");
        assertThat(JsonPath.<List<String>>read(answer, "$.grant-admin-groups"))
                .containsExactly("auditor-admins", "grant-admins");
        assertThat(JsonPath.<List<String>>read(answer, "$.platform-operator-groups")).containsExactly("platform-ops");
        assertThat(JsonPath.<String>read(answer, "$.note")).contains("does not read the directory");
        assertThat(JsonPath.<Map<String, Object>>read(answer, "$")).doesNotContainKey("next-page-token");
        assertThat(denials()).isEmpty();
    }

    @Test
    void aPrivilegeThisVersionDoesNotKnowIsListedButGivesNoDuty() {
        String answer = routes.listGroups(CATALOG, null, null, http).getBody();

        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'futurists')].privileges[*]"))
                .containsExactly("foresee", "select");
        assertThat(JsonPath.<List<String>>read(answer, "$.groups[?(@.group == 'futurists')].duties[*]"))
                .containsExactly("read");
    }

    @Test
    void aDeploymentGrantAdminManagesEveryCatalogsGrantsAndGetsTheGroups() {
        caller = GINA;
        assertThat(JsonPath.<List<String>>read(routes.listGroups(CATALOG, null, null, http).getBody(),
                "$.groups[*].group")).hasSize(6);
    }

    @Test
    void theGroupsComeAPageAtATimeWithTheDeploymentsGroupsOnEveryPage() {
        String first = routes.listGroups(CATALOG, null, "4", http).getBody();
        String token = JsonPath.read(first, "$.next-page-token");
        String second = routes.listGroups(CATALOG, token, "4", http).getBody();

        assertThat(JsonPath.<List<String>>read(first, "$.groups[*].group"))
                .containsExactly("analysts", "futurists", "keepers", "loaders");
        assertThat(JsonPath.<List<String>>read(second, "$.groups[*].group")).containsExactly("stewards", "taggers");
        assertThat(JsonPath.<Map<String, Object>>read(second, "$")).doesNotContainKey("next-page-token");
        for (String page : List.of(first, second)) {
            assertThat(JsonPath.<List<String>>read(page, "$.grant-admin-groups"))
                    .containsExactly("auditor-admins", "grant-admins");
            assertThat(JsonPath.<List<String>>read(page, "$.platform-operator-groups")).containsExactly("platform-ops");
        }
        assertThatThrownBy(() -> routes.listGroups(CATALOG, "not-a-token", null, http))
                .isInstanceOfSatisfying(CatalogError.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void anyoneWithoutManageGrantsOnTheCatalogIsRefusedAuditedAndReadsNoGroup() {
        for (Caller refused : List.of(SAM, ANN, OLGA)) {
            caller = refused;
            assertThatThrownBy(() -> routes.listGroups(CATALOG, null, null, http)).as(refused.subject().id())
                    .isInstanceOf(AccessDenied.class);
        }

        assertThat(denials()).extracting(Denial::operation).containsOnly("listGrantGroups").hasSize(3);
        assertThat(denials()).extracting(denial -> denial.actor().principal()).containsExactly("sam", "ann", "olga");
        Denial sam = denials().getFirst();
        assertThat(sam.privilege()).isEqualTo("manage-grants");
        assertThat(sam.reason()).startsWith("no_privilege");
        assertThat(sam.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.CATALOG);
        assertThat(sam.securable().id()).isEqualTo(catalog.id());
        assertThat(groupListings).as("no refused caller reads the groups").isEmpty();
    }

    @Test
    void aCallerThatHoldsNothingInTheCatalogIsAnsweredAsForOneThatDoesNotExist() {
        caller = NED;
        assertThatThrownBy(() -> routes.listGroups(CATALOG, null, null, http))
                .isInstanceOfSatisfying(CatalogError.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.type()).isEqualTo("NoSuchWarehouseException");
                });
        assertThat(denials()).extracting(Denial::operation).containsExactly("listGrantGroups");
        assertThat(groupListings).isEmpty();
    }

    // ---- helpers ----

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private void grant(Securable on, String group, String privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege), "test", Instant.EPOCH));
    }

    /** The store's group listing over {@link #grants}, keyset-paged by group as the stores page it. */
    private Page<GrantGroup> listGrantGroups(UUID catalogId, PageRequest page) {
        groupListings.add(page);
        Optional<String> after = page.token().map(token -> PageToken.decode(token, 1).getFirst());
        List<GrantGroup> groups = grants.stream().map(GrantRecord::grant)
                .filter(g -> g.catalogId().equals(catalogId) && after.map(a -> g.group().compareTo(a) > 0).orElse(true))
                .collect(Collectors.groupingBy(Grant::group, Collectors.mapping(Grant::privilege, Collectors.toList())))
                .entrySet().stream().map(e -> new GrantGroup(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(GrantGroup::group)).toList();
        if (groups.size() <= page.size()) {
            return new Page<>(groups, Optional.empty());
        }
        List<GrantGroup> items = groups.subList(0, page.size());
        return new Page<>(items, Optional.of(PageToken.encode(items.getLast().group())));
    }

    /** The catalog, the namespace sales and the groups' grants; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.of(sales).filter(ns -> ns.id().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "listGrantGroups" -> listGrantGroups((UUID) args[0], (PageRequest) args[1]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
