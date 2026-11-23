package nuna.catalog.server.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.server.ErrorResponsesForTests;
import nuna.catalog.server.StoreOutage;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.StoreUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A catalog store that raises {@link StoreUnavailableException}, as item 7 made every store do for a database that
 * cannot be reached, behind the real table routes, commit engine and error mapping, served by Spring MVC: a load and
 * a commit answer 503 ServiceUnavailableException with Retry-After in the Iceberg error shape, whether the outage
 * meets the route's own first read or, mid-request, the commit engine's (backlog 70). Java only; the store is a double
 * that answers the lookups and the authorization, so the database's own failure is not here: {@code OracleBackendTest}
 * and {@code MongodbBackendTest} cut a real link, and see the same requests succeed once it is back.
 */
class StoreOutageRoutesTest {

    private static final String TABLE = "/v1/desks/namespaces/sales/tables/orders";
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"desk":"rates"}}]}""";
    /** Select and modify on the namespace sales. */
    private static final Caller MO = new Caller(new Principal("mo", Set.of("sales-owners")), "spark", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "desks", "desks");
    private final NamespaceRecord sales = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("sales", "sales"), Map.of(), 1);
    private final CatalogObject orders = CatalogObject.active(catalog.id(), UUID.randomUUID(), sales.id(),
            new Name(Names.normalize("orders"), "orders"), Kind.TABLE, "s3://warehouse/tables/orders");
    private final List<GrantRecord> grants = List.of(grant(Privilege.SELECT), grant(Privilege.MODIFY));

    /** The store calls the database is away for. */
    private Predicate<String> unreachable = call -> false;
    /** Every store call, in order. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final CatalogStorage storage;
    private final MockMvc mvc;

    StoreOutageRoutesTest() {
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
        AuditPublisher publisher = event -> { };
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Guard guard = new Guard(authorizer, denials, publisher, settings, clock, holdings);
        Lookup lookup = new Lookup(store, settings, new Prefixes(holdings, store, clock));
        AuditEvents events = new AuditEvents(settings);
        Callers callers = request -> MO;
        // One MinIO profile whose endpoint nothing contacts: every case is answered before a metadata file is read.
        storage = new CatalogStorage(new StorageSettings(Map.of("warehouse", new StorageSettings.Profile(
                StoreKind.MINIO, "http://127.0.0.1:9", "us-east-1", "s3://warehouse/tables",
                List.of("s3://warehouse/tables"), null, "catalog", "catalog-secret", null, null, null)), Map.of(),
                Duration.ofMinutes(30), 100, false), store, settings, clock, ObservationRegistry.NOOP);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ServerAlerts alerts = new ServerAlerts(1_000_000, 100, registry);
        Engines engines = new Engines(store, storage, new MetadataCache(1_000_000), alerts, events, clock,
                new DefaultListableBeanFactory().getBeanProvider(CommitRule.class),
                new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7)));
        ObjectRoutes objects = new ObjectRoutes(store, lookup, new Idempotency(store, clock), events, settings, guard,
                clock);
        // No metadata views, metrics sink or read audit: an outage is answered before any of them is reached.
        TableRoutes tables = new TableRoutes(store, lookup, objects, engines, null, CredentialVending.NONE, null,
                alerts, settings, callers, guard, clock, null, new CatalogMetrics(registry));
        mvc = MockMvcBuilders.standaloneSetup(tables).setControllerAdvice(ErrorResponsesForTests.advice()).build();
        // The storage profile recorded while starting; each test counts its requests' calls only.
        calls.clear();
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    @Test
    @DisplayName("a database away answers a load and a commit 503 with Retry-After, as the route's first read meets it")
    void aDatabaseAwayAnswersLoadsAndCommits503() throws Exception {
        unreachable = call -> true;

        unavailable(perform(get(TABLE)));
        unavailable(perform(post(TABLE).contentType("application/json").content(SET_PROPERTY)));
        assertThat(calls).as("each request stopped at its first read").containsExactly("findCatalog", "findCatalog");
    }

    @Test
    @DisplayName("a database lost while the commit engine reads the pointer answers the commit 503 with Retry-After")
    void aDatabaseLostMidCommitAnswers503() throws Exception {
        unreachable = "readPointer"::equals;

        String body = unavailable(perform(post(TABLE).contentType("application/json").content(SET_PROPERTY)));
        assertThat(JsonPath.<String>read(body, "$.error.message")).contains("nothing was applied");
        assertThat(calls).as("the route's lookups and authorization passed; the engine met the outage")
                .contains("findObject").last().isEqualTo("readPointer");
    }

    @Test
    @DisplayName("a database lost while a load reads the pointer answers the load 503 with Retry-After")
    void aDatabaseLostMidLoadAnswers503() throws Exception {
        unreachable = "readPointer"::equals;

        unavailable(perform(get(TABLE)));
        assertThat(calls).last().isEqualTo("readPointer");
    }

    // ---- helpers ----

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        // What RequestIds would have set before the route ran.
        return mvc.perform(request.requestAttr(RequestIds.class.getName(), "request-1")).andReturn().getResponse();
    }

    /** 503 ServiceUnavailableException with Retry-After, in the Iceberg error shape; the body. */
    private static String unavailable(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(StoreOutage.STATUS);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(StoreOutage.RETRY_AFTER_SECONDS);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(JsonPath.<Integer>read(body, "$.error.code")).isEqualTo(StoreOutage.STATUS);
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("ServiceUnavailableException");
        assertThat(JsonPath.<String>read(body, "$.error.message")).startsWith("Catalog store unavailable");
        return body;
    }

    private GrantRecord grant(Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE, sales.id()),
                "sales-owners", privilege.wireName()), "test", Instant.EPOCH);
    }

    /**
     * The catalog desks, its namespace sales and table orders, the grants on sales, and the storage profile; a call
     * {@link #unreachable} names raises {@link StoreUnavailableException}, and any call not answered here fails the
     * request with 500, so nothing else was asked of the store, writes included.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        AtomicReference<UUID> profile = new AtomicReference<>();
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    String call = method.getName();
                    if (call.equals("toString")) {
                        return "the test's store";
                    }
                    calls.add(call);
                    if (unreachable.test(call)) {
                        throw new StoreUnavailableException("the database cannot be reached",
                                new ConnectException("Connection refused"));
                    }
                    return switch (call) {
                        case "readStorageProfile" -> Optional.empty();
                        case "putStorageProfile" -> {
                            profile.set(((StorageProfile) args[0]).id());
                            yield null;
                        }
                        case "catalogSettings" -> Optional.of(new CatalogSettings(profile.get(), "test-keys",
                                "test-entity"));
                        case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                        case "findNamespace" -> Optional.of(sales).filter(ns -> args[1].equals(List.of("sales")));
                        case "readNamespace" -> Optional.of(sales).filter(ns -> ns.id().equals(args[0]));
                        case "findObject" -> Optional.of(orders)
                                .filter(o -> o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]));
                        case "readObject" -> Optional.of(orders).filter(o -> o.id().equals(args[0]));
                        case "grantsFor" -> grants.stream()
                                .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                        && ((Set<String>) args[1]).contains(g.grant().group()))
                                .toList();
                        case "attributesOf" -> List.of();
                        case "listGrantsOfGroups" -> new Page<>(grants.stream()
                                .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                                Optional.empty());
                        default -> throw new UnsupportedOperationException(call);
                    };
                });
    }
}
