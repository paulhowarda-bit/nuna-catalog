package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Separation of duties holds when two grants race (D18, backlog 13). Two grants to one group, each harmless alone
 * but together giving it two separated duties in one catalog, are made at once through the grant route. The store
 * double enforces {@code putGrantIf}'s contract: conditional puts in one catalog run one at a time, each asking the
 * route's check about the grants the one before it wrote. It also makes the race certain: both requests reach the
 * store before either writes, and a route that read the group's grants itself waits there until the other request
 * has read them too. So exactly one grant applies, and the other is refused and audited. Needs only Java;
 * {@code GrantSuite} races conditional puts on each real database, and {@code SecurityGateTest} checks the
 * separation refusals on the wire.
 */
class GrantRaceTest {

    private static final String CATALOG = "gate";
    private static final Caller GINA = new Caller(new Principal("gina", Set.of("grant-admins")), "console", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), CATALOG, CATALOG);
    private final List<NamespaceRecord> namespaces = List.of(namespace("sales"), namespace("hr"));
    /** The double's grants; every access holds the list's lock. */
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new CopyOnWriteArrayList<>();
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    /** Where the two racing requests wait for each other; null when a test does not race. */
    private volatile CyclicBarrier together;
    private final GrantRoutes routes;

    GrantRaceTest() {
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        routes = new GrantRoutes(store, authorizer, separation, request -> GINA, new Denials(audit::add, settings, clock),
                new Holdings(authorizer, store, settings), settings, clock);
    }

    @AfterEach
    void stop() {
        requests.shutdownNow();
    }

    @Test
    @DisplayName("Two grants raced that together break separation of duties: exactly one applies, the other is"
            + " refused and audited")
    void racedGrantsThatConflictCannotBothApply() throws Exception {
        List<List<String>> pairs = List.of(
                List.of("write", "manage-tags"),
                List.of("manage-tags", "create"),
                List.of("select", "manage-grants"),
                List.of("manage-grants", "modify"));
        for (int i = 0; i < pairs.size(); i++) {
            String group = "racers-" + i;
            List<String> privileges = pairs.get(i);
            int deniedBefore = denials().size();

            List<String> outcomes = race(group, privileges.get(0), privileges.get(1));

            assertThat(outcomes).as("%s and %s to %s", privileges.get(0), privileges.get(1), group)
                    .containsOnlyOnce("applied")
                    .filteredOn(outcome -> !outcome.equals("applied")).singleElement().asString()
                    .startsWith("refused: group " + group + " holds ");
            assertThat(held(group)).as("what %s holds", group).hasSize(1).isSubsetOf(privileges);
            List<Denial> denied = denials().subList(deniedBefore, denials().size());
            assertThat(denied).singleElement().satisfies(denial -> {
                assertThat(denial.operation()).isEqualTo("grant");
                assertThat(denial.reason()).startsWith("separation-of-duties: group " + group + " holds ");
            });
        }
    }

    @Test
    @DisplayName("Two grants raced that keep the duties separate both apply")
    void racedGrantsThatAgreeBothApply() throws Exception {
        assertThat(race("readers-writers", "select", "write")).containsExactly("applied", "applied");
        assertThat(held("readers-writers")).containsExactlyInAnyOrder("select", "write");
        assertThat(denials()).isEmpty();
    }

    @Test
    @DisplayName("A grant that conflicts with one the group holds is refused, audited, and writes nothing")
    void aGrantConflictingWithOneHeldIsRefused() {
        synchronized (grants) {
            grants.add(new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE,
                    namespaces.getFirst().id()), "loaders", "write"), "test", Instant.EPOCH));
        }

        assertThatThrownBy(() -> routes.grant(CATALOG, body("hr", "loaders", "manage-tags"), http()))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("group loaders holds write in this catalog, a data duty that manage-tags (tags) must stay"
                        + " separate from");
        assertThat(held("loaders")).containsExactly("write");
        assertThat(denials()).singleElement().extracting(Denial::reason).asString()
                .startsWith("separation-of-duties: ");
    }

    /** Grants the two privileges to the group at once, one on each namespace; "applied" or "refused: why" each. */
    private List<String> race(String group, String first, String second) throws Exception {
        together = new CyclicBarrier(2);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String body = body(namespaces.get(i).name().display(), group, i == 0 ? first : second);
            outcomes.add(requests.submit(() -> {
                try {
                    ResponseEntity<String> answer = routes.grant(CATALOG, body, http());
                    assertThat(answer.getStatusCode().value()).isEqualTo(204);
                    return "applied";
                } catch (AccessDenied e) {
                    return "refused: " + e.getMessage();
                }
            }));
        }
        List<String> answers = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            answers.add(outcome.get(30, TimeUnit.SECONDS));
        }
        together = null;
        return answers;
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private List<String> held(String group) {
        synchronized (grants) {
            return grants.stream().filter(g -> g.grant().group().equals(group)).map(g -> g.grant().privilege())
                    .toList();
        }
    }

    private static String body(String namespace, String group, String privilege) {
        return "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + namespace + "\"]},\"group\":\"" + group
                + "\",\"privilege\":\"" + privilege + "\"}";
    }

    private static MockHttpServletRequest http() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-" + UUID.randomUUID());
        return http;
    }

    private NamespaceRecord namespace(String name) {
        return new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(), new Name(Names.normalize(name), name),
                Map.of(), 1);
    }

    /** Waits, when racing, until the other request has come as far. */
    private void meet() throws Exception {
        CyclicBarrier barrier = together;
        if (barrier != null) {
            barrier.await(10, TimeUnit.SECONDS);
        }
    }

    /** The grant's catalog, its two namespaces and the groups' grants; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> namespaces.stream()
                            .filter(ns -> List.of(ns.name().normalized()).equals(args[1])).findFirst();
                    case "readNamespace" -> namespaces.stream().filter(ns -> ns.id().equals(args[0])).findFirst();
                    case "grantsFor" -> {
                        synchronized (grants) {
                            yield grants.stream()
                                    .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                            && ((Set<String>) args[1]).contains(g.grant().group()))
                                    .toList();
                        }
                    }
                    case "listGrantsOfGroups" -> {
                        List<GrantRecord> found;
                        synchronized (grants) {
                            found = grants.stream().filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group())).toList();
                        }
                        meet();
                        yield new Page<>(found, Optional.empty());
                    }
                    case "putGrantIf" -> {
                        meet();
                        yield putGrantIf((Grant) args[0], (String) args[1], (Predicate<List<String>>) args[3]);
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The contract: the group's grants in the catalog are read and the grant written under one lock. */
    private WriteResult putGrantIf(Grant grant, String grantedBy, Predicate<List<String>> admits) {
        synchronized (grants) {
            List<String> held = grants.stream().filter(g -> g.grant().catalogId().equals(grant.catalogId())
                    && g.grant().group().equals(grant.group())).map(g -> g.grant().privilege()).toList();
            if (!admits.test(held)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_ADMITTED);
            }
            if (grants.stream().anyMatch(g -> g.grant().equals(grant))) {
                return WriteResult.UNCHANGED;
            }
            grants.add(new GrantRecord(grant, grantedBy, Instant.EPOCH));
            return WriteResult.APPLIED;
        }
    }
}
