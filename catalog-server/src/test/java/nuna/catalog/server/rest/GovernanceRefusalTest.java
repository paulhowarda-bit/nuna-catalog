package nuna.catalog.server.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
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
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.ErrorResponsesForTests;
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
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A commit, transaction, create, unregister or drop that a legal hold or DR freeze refuses is audited as a denial, as
 * every refusal is (D18; backlog 134): the Denial names the operation and the table, its reason is
 * {@code legal_hold} or {@code dr_freeze} followed by the message the caller gets, and the answer is the 403
 * ForbiddenException it always was. The commit engine answers its refusals as a reply, not a throw, so the routes
 * learn which 403s are these from the reply. The table and transaction routes, guard, authorizer, commit engine and
 * its rules are the real ones, served by Spring MVC, over a store double that refuses an unregister or drop as a
 * store would and fails any other write, so this needs only Java.
 */
class GovernanceRefusalTest {

    private static final String TABLE = "/v1/desks/namespaces/sales/tables/orders";
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    /** Modify and create on sales: every refusal here is a hold's or a freeze's. */
    private static final Caller MO = new Caller(new Principal("mo", Set.of("owners")), "spark", false,
            Optional.empty());
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"desk":"rates"}}]}""";
    private static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}""";

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "desks", "desks");
    private final NamespaceRecord sales = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("sales", "sales"), Map.of(), 1);
    private final CatalogObject orders = CatalogObject.active(catalog.id(), UUID.randomUUID(), sales.id(),
            new Name(Names.normalize("orders"), "orders"), Kind.TABLE, "s3://warehouse/tables/orders");
    private final List<GrantRecord> grants = List.of(grant(Privilege.MODIFY), grant(Privilege.CREATE));
    private final List<CatalogEvent> audit = new CopyOnWriteArrayList<>();

    /** A DR freeze on sales. */
    private boolean frozen;
    /** A legal hold on orders. */
    private boolean held;
    /** What the store answers an unregister or a drop: a hold or a freeze it found itself. */
    private WriteResult.Reason storeRefuses;

    /** orders at its third version: snapshots 1 and 2, main at 2, both a day old. */
    private final Pointer pointer = new Pointer(orders.locationRoot() + "/metadata/00003.metadata.json", 3,
            "0".repeat(64), new byte[0]);
    private final CatalogStorage storage;
    private final MockMvc mvc;

    GovernanceRefusalTest() {
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Guard guard = new Guard(authorizer, denials, publisher, settings, clock, holdings);
        Lookup lookup = new Lookup(store, settings, new Prefixes(holdings, store, clock));
        AuditEvents events = new AuditEvents(settings);
        Callers callers = request -> MO;
        // One MinIO profile whose endpoint nothing contacts: every refusal comes before a metadata file is written,
        // and orders' current file is in the cache.
        storage = new CatalogStorage(new StorageSettings(Map.of("warehouse", new StorageSettings.Profile(
                StoreKind.MINIO, "http://127.0.0.1:9", "us-east-1", "s3://warehouse/tables",
                List.of("s3://warehouse/tables"), null, "catalog", "catalog-secret", null, null, null)), Map.of(),
                Duration.ofMinutes(30), 100, false), store, settings, clock, ObservationRegistry.NOOP);
        MetadataCache cache = new MetadataCache(1_000_000);
        cache.put(pointer.metadataLocation(), ordersMetadata());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ServerAlerts alerts = new ServerAlerts(1_000_000, 100, registry);
        Engines engines = new Engines(store, storage, cache, alerts, events, clock,
                new DefaultListableBeanFactory().getBeanProvider(CommitRule.class));
        ObjectRoutes objects = new ObjectRoutes(store, lookup, new Idempotency(store, clock), events, settings, guard,
                clock);
        CatalogMetrics meters = new CatalogMetrics(registry);
        // No metadata views, metrics sink or read audit: every case here is refused before any of them is reached.
        TableRoutes tables = new TableRoutes(store, lookup, objects, engines, null, CredentialVending.NONE, null,
                alerts, settings, callers, guard, clock, null, meters);
        TransactionRoutes transactions = new TransactionRoutes(lookup, engines, settings, callers, guard, meters);
        mvc = MockMvcBuilders.standaloneSetup(tables, transactions)
                .setControllerAdvice(ErrorResponsesForTests.advice()).build();
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    @Test
    void aCommitUnderAFreezeIsAuditedAsADenial() throws Exception {
        frozen = true;

        String message = forbidden(perform(post(TABLE).contentType("application/json").content(SET_PROPERTY)));

        assertThat(message).isEqualTo("Writes to " + orders.id() + " are frozen for DR reconcile: drill");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("updateTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("dr_freeze: " + message);
            assertThat(denial.actor().principal()).isEqualTo("mo");
        });
    }

    @Test
    void aSnapshotRemovalFromAHeldTableIsAuditedAsADenial() throws Exception {
        held = true;

        String message = forbidden(perform(post(TABLE).contentType("application/json").content("""
                {"requirements":[],"updates":[{"action":"remove-snapshots","snapshot-ids":[1]}]}""")));

        assertThat(message).isEqualTo("Table " + orders.id() + " is under legal hold: its snapshots cannot be removed");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("updateTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("legal_hold: " + message);
        });
    }

    @Test
    void aTransactionUnderAFreezeIsAuditedNamingTheTableRefused() throws Exception {
        frozen = true;

        String message = forbidden(perform(post("/v1/desks/transactions/commit").contentType("application/json")
                .content("""
                        {"table-changes":[{"identifier":{"namespace":["sales"],"name":"orders"},"requirements":[],
                         "updates":[{"action":"set-properties","updates":{"desk":"rates"}}]}]}""")));

        assertThat(message).isEqualTo("sales.orders: Writes to " + orders.id() + " are frozen for DR reconcile: drill");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("commitTransaction");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("dr_freeze: " + message);
        });
    }

    @Test
    void aCreateInAFrozenNamespaceIsAuditedNamingTheTableItWouldHaveMade() throws Exception {
        frozen = true;

        String message = forbidden(perform(post("/v1/desks/namespaces/sales/tables").contentType("application/json")
                .content("{\"name\":\"trades\",\"schema\":" + SCHEMA + "}")));

        assertThat(message).startsWith("Writes to ").endsWith(" are frozen for DR reconcile: drill");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("createTable");
            assertThat(message).contains(denial.securable().id().toString());
            assertThat(denial.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.TABLE);
            assertThat(denial.securable().path()).containsExactly("sales", "trades");
            assertThat(denial.reason()).isEqualTo("dr_freeze: " + message);
        });
    }

    @Test
    void anUnregisterTheStoreFindsFrozenIsAuditedAsADenial() throws Exception {
        storeRefuses = WriteResult.Reason.FROZEN;

        String message = forbidden(perform(post(TABLE + "/unregister")));

        assertThat(message).isEqualTo("Table sales.orders is frozen for DR reconcile and cannot be unregistered");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("unregisterTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("dr_freeze: " + message);
        });
    }

    @Test
    void anUnregisterTheStoreFindsHeldIsAuditedAsADenial() throws Exception {
        storeRefuses = WriteResult.Reason.HELD;

        String message = forbidden(perform(post(TABLE + "/unregister")));

        assertThat(message).isEqualTo("Table sales.orders is under a legal hold and cannot be unregistered");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("unregisterTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.reason()).isEqualTo("legal_hold: " + message);
        });
    }

    @Test
    void aDropUnderALegalHoldIsAuditedAsADenial() throws Exception {
        storeRefuses = WriteResult.Reason.HELD;

        String message = forbidden(perform(delete(TABLE)));

        assertThat(message).isEqualTo("Table sales.orders is under a legal hold and cannot be dropped");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("dropTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("legal_hold: " + message);
            assertThat(denial.actor().principal()).isEqualTo("mo");
        });
    }

    // ---- helpers ----

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        // What RequestIds would have set before the route ran.
        return mvc.perform(request.requestAttr(RequestIds.class.getName(), "request-1")).andReturn().getResponse();
    }

    /** 403 ForbiddenException in the Iceberg error shape, as before backlog 134; its message. */
    private static String forbidden(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(JsonPath.<Integer>read(body, "$.error.code")).isEqualTo(403);
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("ForbiddenException");
        return JsonPath.read(body, "$.error.message");
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private GrantRecord grant(Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE, sales.id()),
                "owners", privilege.wireName()), "test", Instant.EPOCH);
    }

    /** orders' metadata as the pointer names it: a v2 table at its own location, snapshots 1 and 2, main at 2. */
    private String ordersMetadata() {
        String created = ((Outcome.NewMetadata) Metadata.createTable("{\"name\":\"orders\",\"schema\":" + SCHEMA
                + ",\"properties\":{\"format-version\":\"2\"}}", orders.id().toString(), orders.locationRoot()))
                .json();
        long dayOld = NOW.minus(Duration.ofDays(1)).toEpochMilli();
        return ((Outcome.NewMetadata) Metadata.commitTable("{\"requirements\":[],\"updates\":["
                + snapshot(1, null, dayOld) + "," + snapshot(2, 1L, dayOld) + ","
                + "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"type\":\"branch\",\"snapshot-id\":2}]}",
                created, orders.locationRoot() + "/metadata/00001.metadata.json")).json();
    }

    private static String snapshot(long id, Long parent, long timestampMs) {
        return "{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":" + id
                + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent)
                + ",\"sequence-number\":" + id + ",\"timestamp-ms\":" + timestampMs
                + ",\"manifest-list\":\"s3://warehouse/tables/orders/metadata/snap-" + id + ".avro\""
                + ",\"summary\":{\"operation\":\"append\"},\"schema-id\":0}}";
    }

    /**
     * The catalog desks, its namespace sales and table orders with its pointer, the grants on sales, the freeze and
     * hold a test places, and the storage profile; an unregister or drop is refused for {@link #storeRefuses}. Any
     * call not answered here fails the request with 500, so nothing else was asked of the store, writes included.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        AtomicReference<UUID> profile = new AtomicReference<>();
        Securable namespace = new Securable(Securable.Kind.NAMESPACE, sales.id());
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
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
                    case "readPointer" -> Optional.of(pointer).filter(p -> orders.mainKey().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                            Optional.empty());
                    case "attributesOf" -> frozen && ((Set<Securable>) args[0]).contains(namespace)
                            ? List.of(new GovernanceAttribute(namespace, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                                    "api", "olga", NOW))
                            : List.of();
                    case "holdsOn" -> held && ((Set<Securable>) args[0]).contains(orders.securable())
                            ? List.of(new LegalHold(UUID.randomUUID(), orders.securable(), "litigation", "lee", NOW))
                            : List.of();
                    case "unregisterObject", "dropObject" -> new WriteResult.Refused(storeRefuses);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
