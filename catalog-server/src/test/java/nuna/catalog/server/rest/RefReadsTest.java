package nuna.catalog.server.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.MaintenanceExemption;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.MetadataViews;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.server.ConfigRoute;
import nuna.catalog.server.ErrorResponsesForTests;
import nuna.catalog.server.governance.AuditTrail;
import nuna.catalog.server.governance.ReadAudit;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.server.rest.CredentialVending.Vended;
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
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Reads through a branch or tag, the prefix {@code catalog@ref} (D33, as amended 2026-11-20; item 104), behind the
 * real routes, lookup, authorization, commit engine (its metadata reads and hash checks) and error mapping, served by
 * Spring MVC. Java only: the catalog store is a double holding main, a tag's cut and three branches' cuts, and the
 * object store an HTTP server in the test JVM that serves the metadata files.
 *
 * <p>Catalog {@code trading} holds namespace {@code desk}, with table {@code orders}, table {@code fills} and view
 * {@code daily}. Tag {@code eod} was cut before {@code fills} was renamed from {@code trades}, before namespace
 * {@code old} was dropped and before {@code orders} and {@code daily} moved on main. Branch {@code dev} has no commits
 * yet; branch {@code wip} has its own pointer for {@code orders}; branch {@code stale} is past its expiry.
 */
class RefReadsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final String ROOT = "s3://warehouse/tables";
    /** Write on namespace desk, and describe on the catalog. */
    private static final Caller MO = new Caller(new Principal("mo", Set.of("desk-writers", "catalog-describers")),
            "spark", false, Optional.empty());
    /** The maintenance principal, acting as itself through an engine that is not trusted: write on desk. */
    private static final Caller MAINTENANCE = new Caller(new Principal("maint", Set.of("desk-writers")), "spark",
            false, Optional.empty());
    /** A trusted engine acting as itself, naming no end user: write on desk. */
    private static final Caller ETL = new Caller(new Principal("etl", Set.of("desk-writers")), "trino", true,
            Optional.empty());
    /** Modify on namespace desk. */
    private static final Caller OWNER = new Caller(new Principal("ow", Set.of("desk-owners")), "spark", false,
            Optional.empty());
    /** Describe on the catalog, and nothing else. */
    private static final Caller DESCRIBER = new Caller(new Principal("di", Set.of("catalog-describers")), "spark",
            false, Optional.empty());
    /** Select on namespace desk through every ref, and write on desk through branch dev only (backlog 113). */
    private static final Caller DEV_WRITER = new Caller(new Principal("dw", Set.of("desk-readers", "dev-writers")),
            "spark", false, Optional.empty());
    /** Select on a namespace of the catalog other than desk: nothing on desk, its tables or the catalog. */
    private static final Caller STRANGER = new Caller(new Principal("st", Set.of("elsewhere-readers")), "spark",
            false, Optional.empty());
    /** An append of snapshot 7 made as main moved to it, which needs write. */
    private static final String APPEND = """
            {"requirements":[],"updates":[{"action":"add-snapshot","snapshot":{"snapshot-id":7,"sequence-number":1,\
            "timestamp-ms":%d,"manifest-list":"s3://warehouse/tables/snap-7.avro","summary":{"operation":"append"},\
            "schema-id":0,"first-row-id":0,"added-rows":10}},\
            {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":7}]}"""
            .formatted(NOW.toEpochMilli());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "trading", "trading");
    private final NamespaceRecord desk = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            name("desk"), Map.of("owner", "rates"), 3);
    /** A namespace the tag's cut holds and main has dropped since. */
    private final UUID old = UUID.randomUUID();
    private final CatalogObject orders = object("orders", Kind.TABLE);
    /** Named trades in the tag's cut; renamed fills on main since. */
    private final CatalogObject fills = object("fills", Kind.TABLE);
    private final CatalogObject daily = object("daily", Kind.VIEW);
    /** Main's objects, active or not: only an active one holds its name. */
    private final List<CatalogObject> onMain = new CopyOnWriteArrayList<>(List.of(orders, fills, daily));

    private final Map<String, Ref> refs = new HashMap<>();
    private final Map<UUID, List<RefEntry>> cuts = new HashMap<>();
    private final Map<PointerKey, Pointer> pointers = new HashMap<>();
    private final List<GrantRecord> grants = new CopyOnWriteArrayList<>(List.of(
            grant(new Securable(Securable.Kind.NAMESPACE, desk.id()), "desk-writers", Privilege.WRITE),
            grant(new Securable(Securable.Kind.NAMESPACE, desk.id()), "desk-readers", Privilege.SELECT),
            grant(new Securable(Securable.Kind.CATALOG, catalog.id()), "catalog-describers", Privilege.DESCRIBE),
            grant(new Securable(Securable.Kind.NAMESPACE, UUID.randomUUID()), "elsewhere-readers",
                    Privilege.SELECT),
            grant(new Securable(Securable.Kind.NAMESPACE, desk.id()), "desk-owners", Privilege.MODIFY)));
    private final List<GovernanceAttribute> attributes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Caller> caller = new AtomicReference<>(MO);
    /** The batches the store committed, and the branches it renewed. */
    private final List<CommitBatch> batches = new CopyOnWriteArrayList<>();
    private final List<Renewal> renewals = new CopyOnWriteArrayList<>();

    /** The metadata files the object store serves, by path ({@code /bucket/key}). */
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<CredentialVending.Request> vended = new CopyOnWriteArrayList<>();
    private final List<CatalogEvent> audited = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private HttpServer objectStore;
    private CatalogStorage storage;
    private MockMvc mvc;

    private Ref eod;
    private Ref dev;
    private Ref wip;
    /** The metadata of orders and daily as the cuts took them, and as main and wip have them now. */
    private String ordersCut;
    private String ordersMain;
    private String ordersWip;
    private String tradesCut;
    private String dailyCut;
    private String fillsMain;

    @BeforeEach
    void serve() throws IOException, ReflectiveOperationException {
        objectStore = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        objectStore.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                byte[] sent = exchange.getRequestBody().readAllBytes();
                String signed = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
                byte[] written = signed != null && signed.startsWith("STREAMING-") ? unchunked(sent) : sent;
                files.put(exchange.getRequestURI().getPath(), written);
                exchange.getResponseHeaders().add("ETag", "\"" + digest("MD5", written) + "\"");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            byte[] body = files.get(exchange.getRequestURI().getPath());
            int status = body == null ? 404 : 200;
            if (body == null) {
                body = "<Error><Code>NoSuchKey</Code><Message>no such key</Message></Error>".getBytes(UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/xml");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        objectStore.start();

        ordersCut = tableFile(orders, "orders", 1, "cut");
        ordersMain = tableFile(orders, "orders", 2, "main");
        ordersWip = tableFile(orders, "orders", 3, "wip");
        tradesCut = tableFile(fills, "trades", 1, "trades-cut");
        fillsMain = tableFile(fills, "fills", 2, "fills-main");
        dailyCut = viewFile(daily, 1, "cut");
        String dailyMain = viewFile(daily, 2, "main");
        pointers.put(orders.mainKey(), pointer(ordersMain, 2));
        pointers.put(fills.mainKey(), pointer(fillsMain, 2));
        pointers.put(daily.mainKey(), pointer(dailyMain, 2));

        eod = ref("eod", Ref.Kind.TAG, Optional.empty());
        cuts.put(eod.id(), List.of(
                namespaceEntry(eod, desk.id(), "desk"),
                namespaceEntry(eod, old, "old"),
                entry(eod, orders.id(), RefEntry.Kind.TABLE, "orders", ordersCut, 1),
                entry(eod, fills.id(), RefEntry.Kind.TABLE, "trades", tradesCut, 1),
                entry(eod, daily.id(), RefEntry.Kind.VIEW, "daily", dailyCut, 1)));
        dev = ref("dev", Ref.Kind.BRANCH, Optional.of(NOW.plus(Duration.ofDays(30))));
        wip = ref("wip", Ref.Kind.BRANCH, Optional.of(NOW.plus(Duration.ofDays(30))));
        Ref stale = ref("stale", Ref.Kind.BRANCH, Optional.of(NOW.minus(Duration.ofDays(1))));
        for (Ref branch : List.of(dev, wip, stale)) {
            cut(branch);
        }
        pointers.put(wip.key(orders.id()), pointer(ordersWip, 2));

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audited::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        // D17's switch on, and maint the maintenance principal.
        Authorizer authorizer = new Authorizer(store, store, store, separation, true, Set.of("maint"));
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Guard guard = new Guard(authorizer, denials, publisher, settings, clock, holdings);
        Prefixes prefixes = new Prefixes(holdings, store, clock);
        Lookup lookup = new Lookup(store, settings, prefixes);
        AuditEvents events = new AuditEvents(settings);
        Callers callers = request -> caller.get();
        storage = new CatalogStorage(new StorageSettings(Map.of("warehouse", new StorageSettings.Profile(
                StoreKind.MINIO, "http://127.0.0.1:" + objectStore.getAddress().getPort(), "us-east-1", ROOT,
                List.of(ROOT), null, "catalog", "catalog-secret", null, null, null)), Map.of(),
                Duration.ofMinutes(30), 100, false), store, settings, clock, ObservationRegistry.NOOP);
        ServerAlerts alerts = new ServerAlerts(1_000_000, 100, registry);
        Engines engines = new Engines(store, storage, new MetadataCache(1_000_000), alerts, events, clock,
                new DefaultListableBeanFactory().getBeanProvider(CommitRule.class),
                new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7)));
        Idempotency idempotency = new Idempotency(store, clock);
        ObjectRoutes objects = new ObjectRoutes(store, lookup, idempotency, events, settings, guard, clock);
        MetadataViews views = new MetadataViews(settings.metadataCacheChars());
        ReadAudit reads = readAudit(store, callers, settings, holdings, clock);
        CredentialVending vending = request -> {
            vended.add(request);
            return new Vended(Map.of(), List.of(), "epoch-1", Optional.empty(), false);
        };
        CatalogMetrics meters = new CatalogMetrics(registry);
        mvc = MockMvcBuilders.standaloneSetup(
                        new TableRoutes(store, lookup, objects, engines, views, vending,
                                (name, levels, table, summary) -> { }, alerts, settings, callers, guard, clock, reads,
                                meters),
                        new ViewRoutes(store, lookup, objects, engines, views, callers, guard, reads, meters),
                        new NamespaceRoutes(store, lookup, idempotency, events, callers, guard, clock),
                        new TransactionRoutes(lookup, engines, settings, callers, guard, meters),
                        new ConfigRoute(settings, callers, prefixes))
                .setControllerAdvice(ErrorResponsesForTests.advice())
                .build();
        calls.clear();
    }

    @AfterEach
    void stop() throws Exception {
        storage.close();
        objectStore.stop(0);
    }

    // ---- the prefix ----

    @Test
    @DisplayName("catalog@tag loads a table as the tag's cut took it, whether the @ comes as is or as %40; catalog and"
            + " catalog@main load main")
    void aTagLoadsItsCut() throws Exception {
        String table = "/namespaces/desk/tables/orders";

        assertLoaded(perform(get("/v1/trading@eod" + table)), ordersCut, "cut");
        assertLoaded(perform(get(URI.create("/v1/trading%40eod" + table))), ordersCut, "cut");
        assertLoaded(perform(get("/v1/trading@EOD" + table)), ordersCut, "cut");
        assertLoaded(perform(get("/v1/trading" + table)), ordersMain, "main");
        assertLoaded(perform(get("/v1/trading@main" + table)), ordersMain, "main");
    }

    @Test
    @DisplayName("a branch with no commits loads its cut's version; a branch reads its own pointer where it has one")
    void aBranchLoadsItsOwnPointerElseItsCut() throws Exception {
        assertLoaded(perform(get("/v1/trading@dev/namespaces/desk/tables/orders")), ordersCut, "cut");
        assertLoaded(perform(get("/v1/trading@wip/namespaces/desk/tables/orders")), ordersWip, "wip");
        assertThat(calls).as("the tag has no pointer of its own to read; each branch's own was read")
                .containsSubsequence("findRef", "resolveOnRef", "readPointer");
    }

    @Test
    @DisplayName("a ref the catalog does not have, and a branch past its expiry, answer 404 NoSuchWarehouseException"
            + " on every route, /v1/config included")
    void aMissingOrExpiredRefIsGone() throws Exception {
        for (String ref : List.of("stale", "nope", "")) {
            String prefix = "trading@" + ref;
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/v1/" + prefix + "/namespaces/desk/tables/orders"),
                    get("/v1/" + prefix + "/namespaces"),
                    post("/v1/" + prefix + "/namespaces/desk/tables/orders").contentType("application/json")
                            .content("{}"),
                    get("/v1/config").param("warehouse", prefix))) {
                MockHttpServletResponse response = perform(request);
                String body = response.getContentAsString();
                assertThat(response.getStatus()).as(prefix + ": " + body).isEqualTo(404);
                assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("NoSuchWarehouseException");
                assertThat(JsonPath.<String>read(body, "$.error.message"))
                        .startsWith("The given warehouse does not exist: " + prefix);
            }
        }
    }

    // ---- names and listings as of the cut ----

    @Test
    @DisplayName("names resolve as of the ref's cut: the tag loads trades, which main renamed fills, and not fills")
    void namesResolveAsOfTheCut() throws Exception {
        assertLoaded(perform(get("/v1/trading@eod/namespaces/desk/tables/trades")), tradesCut, "trades-cut");
        assertThat(perform(get("/v1/trading@eod/namespaces/desk/tables/fills")).getStatus()).isEqualTo(404);
        assertThat(perform(get("/v1/trading/namespaces/desk/tables/trades")).getStatus()).isEqualTo(404);

        assertThat(perform(head("/v1/trading@eod/namespaces/desk/tables/trades")).getStatus()).isEqualTo(204);
        assertThat(perform(head("/v1/trading@eod/namespaces/desk/tables/fills")).getStatus()).isEqualTo(404);
        assertThat(perform(head("/v1/trading@dev/namespaces/desk/tables/orders")).getStatus()).isEqualTo(204);
        assertThat(perform(head("/v1/trading@eod/namespaces/desk/views/daily")).getStatus()).isEqualTo(204);
        assertThat(perform(head("/v1/trading@eod/namespaces/old")).getStatus()).isEqualTo(204);
        assertThat(perform(head("/v1/trading/namespaces/old")).getStatus()).isEqualTo(404);
        assertThat(perform(head("/v1/trading@dev/namespaces/old")).getStatus()).isEqualTo(404);

        assertThat(perform(post("/v1/trading@eod/namespaces/desk/tables/trades/metrics")
                .contentType("application/json").content(METRICS_REPORT)).getStatus()).isEqualTo(204);
        assertThat(perform(post("/v1/trading/namespaces/desk/tables/trades/metrics")
                .contentType("application/json").content(METRICS_REPORT)).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("listings come from the ref's cut: its namespaces, tables and views, by name")
    void listingsComeFromTheCut() throws Exception {
        assertThat(namespaces(perform(get("/v1/trading@eod/namespaces")))).containsExactly("desk", "old");
        assertThat(namespaces(perform(get("/v1/trading/namespaces")))).containsExactly("desk");
        assertThat(names(perform(get("/v1/trading@eod/namespaces/desk/tables")))).containsExactly("orders", "trades");
        assertThat(names(perform(get("/v1/trading@dev/namespaces/desk/tables")))).containsExactly("fills", "orders");
        assertThat(names(perform(get("/v1/trading/namespaces/desk/tables")))).containsExactly("fills", "orders");
        assertThat(names(perform(get("/v1/trading@eod/namespaces/desk/views")))).containsExactly("daily");
        assertThat(names(perform(get("/v1/trading@eod/namespaces/desk/tables").param("pageToken", ""))))
                .containsExactly("orders", "trades");
    }

    @Test
    @DisplayName("a namespace through a ref has the cut's name and live properties: main's, or none once main dropped"
            + " it")
    void namespacePropertiesAreLive() throws Exception {
        MockHttpServletResponse loaded = perform(get("/v1/trading@eod/namespaces/desk"));
        assertThat(loaded.getStatus()).as(loaded.getContentAsString()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, String>>read(loaded.getContentAsString(), "$.properties"))
                .isEqualTo(Map.of("owner", "rates"));
        MockHttpServletResponse dropped = perform(get("/v1/trading@eod/namespaces/old"));
        assertThat(dropped.getStatus()).as(dropped.getContentAsString()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, String>>read(dropped.getContentAsString(), "$.properties")).isEmpty();
    }

    @Test
    @DisplayName("a view loads as the ref's cut took it")
    void aViewLoadsItsCut() throws Exception {
        MockHttpServletResponse response = perform(get("/v1/trading@eod/namespaces/desk/views/daily"));
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertThat(JsonPath.<String>read(body, "$['metadata-location']")).isEqualTo(dailyCut);
        assertThat(JsonPath.<String>read(body, "$.metadata.properties.marker")).isEqualTo("cut");
        assertThat(reads()).singleElement().satisfies(read -> assertThat(read.branch()).isEqualTo("eod"));
    }

    // ---- credentials, ETags, audit and the hash check ----

    @Test
    @DisplayName("a tag vends read-only credentials to a caller who may write; a branch and main vend read-write")
    void aTagVendsReadOnlyCredentials() throws Exception {
        for (String prefix : List.of("trading@eod", "trading@dev", "trading")) {
            assertThat(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders")).getStatus()).isEqualTo(200);
            assertThat(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders/credentials")).getStatus())
                    .isEqualTo(200);
        }
        assertThat(vended).extracting(CredentialVending.Request::write)
                .containsExactly(false, false, true, true, true, true);
        assertThat(vended).allSatisfy(request -> assertThat(request.tableId()).isEqualTo(orders.id()));
    }

    @Test
    @DisplayName("the ETag carries the ref: the same file through a tag and a branch has two tags, and only the tag's"
            + " own answers 304, audited as a read on the tag")
    void theETagCarriesTheRef() throws Exception {
        String table = "/namespaces/desk/tables/orders";
        String onTag = perform(get("/v1/trading@eod" + table)).getHeader(HttpHeaders.ETAG);
        String onBranch = perform(get("/v1/trading@dev" + table)).getHeader(HttpHeaders.ETAG);
        assertThat(onTag).isNotNull();
        assertThat(onBranch).isNotNull().isNotEqualTo(onTag);

        MockHttpServletResponse again = perform(get("/v1/trading@eod" + table).header(HttpHeaders.IF_NONE_MATCH, onTag));
        assertThat(again.getStatus()).isEqualTo(304);
        assertThat(perform(get("/v1/trading@dev" + table).header(HttpHeaders.IF_NONE_MATCH, onTag)).getStatus())
                .isEqualTo(200);
        assertThat(reads()).extracting(ObjectRead::branch).containsExactly("eod", "dev", "eod", "dev");
        assertThat(reads().get(2).served().metadataLocation()).isEqualTo(ordersCut);
    }

    @Test
    @DisplayName("a read through a ref is audited naming the branch or tag, and the version it served")
    void readsNameTheRef() throws Exception {
        perform(get("/v1/trading@eod/namespaces/desk/tables/orders"));
        perform(get("/v1/trading@wip/namespaces/desk/tables/orders"));
        perform(get("/v1/trading/namespaces/desk/tables/orders"));

        assertThat(reads()).extracting(ObjectRead::branch).containsExactly("eod", "wip", PointerKey.MAIN);
        assertThat(reads()).extracting(read -> read.served().metadataLocation())
                .containsExactly(ordersCut, ordersWip, ordersMain);
        assertThat(reads()).allSatisfy(read -> assertThat(read.object().id()).isEqualTo(orders.id()));
    }

    @Test
    @DisplayName("the file a ref's cut names is checked against the SHA-256 the cut took: one that no longer matches"
            + " is alerted, as on main")
    void aCutsFileIsHashChecked() throws Exception {
        Ref tampered = ref("tampered", Ref.Kind.TAG, Optional.empty());
        cuts.put(tampered.id(), List.of(namespaceEntry(tampered, desk.id(), "desk"),
                new RefEntry(tampered.id(), orders.id(), RefEntry.Kind.TABLE, desk.id(), name("orders"),
                        Optional.of(new RefEntry.At(1, ordersCut, "0".repeat(64))))));

        assertLoaded(perform(get("/v1/trading@eod/namespaces/desk/tables/fills".replace("fills", "trades"))),
                tradesCut, "trades-cut");
        assertThat(integrityAlerts()).as("an intact file").isZero();
        assertLoaded(perform(get("/v1/trading@tampered/namespaces/desk/tables/orders")), ordersCut, "cut");
        assertThat(integrityAlerts()).as("a file that does not match the cut's hash").isEqualTo(1);
    }

    // ---- writes ----

    @Test
    @DisplayName("every write on a tag is refused with 400 naming the tag, before anything is read past the ref")
    void writesOnATagAre400() throws Exception {
        for (Write write : WRITES) {
            calls.clear();
            assertRefused(write, "trading@eod", write.operation() + " is refused on tag eod of catalog trading: a tag"
                    + " is read-only");
            assertThat(calls).as(write.operation()).containsOnly("findCatalog", "listGrantsOfGroups", "findRef");
        }
    }

    @Test
    @DisplayName("creates, drops, renames, registrations and namespace changes on a branch are refused with 400, before"
            + " anything is read past the ref; commits are served (item 105)")
    void writesOnABranchAre400() throws Exception {
        for (Write write : WRITES.stream().filter(write -> !write.commit()).toList()) {
            calls.clear();
            assertRefused(write, "trading@dev", write.operation() + " is refused on branch dev of catalog trading: "
                    + "creates, drops, renames, registrations and namespace changes happen on main only");
            assertThat(calls).as(write.operation()).containsOnly("findCatalog", "listGrantsOfGroups", "findRef");
        }
    }

    // ---- commits on a branch (item 105) ----

    @Test
    @DisplayName("a commit on a branch writes a file marked with the branch and creates the branch's own pointer under"
            + " a hold on the branch; main's does not move, the branch then loads it, and the commit is metered as on a"
            + " branch, never by its name")
    void aCommitOnABranchSwapsItsOwnPointer() throws Exception {
        String location = committed(perform(commit("trading@dev", "orders", APPEND)));

        assertThat(location).startsWith(orders.locationRoot() + "/metadata/00001-")
                .endsWith(".branch-" + dev.id() + ".metadata.json");
        assertThat(pointers.get(dev.key(orders.id())).metadataLocation()).isEqualTo(location);
        assertThat(pointers.get(orders.mainKey()).metadataLocation()).isEqualTo(ordersMain);
        assertThat(batches).singleElement().satisfies(batch -> {
            assertThat(batch.ref()).contains(new RefLock.Hold(dev.id()));
            assertThat(batch.outbox()).anySatisfy(event -> assertThat(event.payloadJson())
                    .contains("\"branch\":\"dev\""));
        });
        assertLoaded(perform(get("/v1/trading@dev/namespaces/desk/tables/orders")), location, "cut");
        assertLoaded(perform(get("/v1/trading/namespaces/desk/tables/orders")), ordersMain, "main");
        assertThat(registry.get(CatalogMetrics.COMMITS).tag(CatalogMetrics.REF_KIND, CatalogMetrics.BRANCH)
                .tag(CatalogMetrics.OUTCOME, "committed").counter().count()).isEqualTo(1);
        assertThat(registry.find(CatalogMetrics.COMMITS).tag(CatalogMetrics.REF_KIND, CatalogMetrics.MAIN).counter())
                .isNull();
        assertThat(renewals).as("dev's expiry is 30 days ahead already").isEmpty();
    }

    @Test
    @DisplayName("a view's replace on a branch goes to the branch's own pointer; main's does not move")
    void aViewReplaceOnABranch() throws Exception {
        caller.set(OWNER);

        String location = committed(perform(post("/v1/trading@dev/namespaces/desk/views/daily")
                .contentType("application/json").content("""
                        {"requirements":[],"updates":[{"action":"set-properties","updates":{"comment":"weekly"}}]}\
                        """)));

        assertThat(location).startsWith(daily.locationRoot() + "/metadata/00001-")
                .endsWith(".branch-" + dev.id() + ".metadata.json");
        assertThat(pointers.get(dev.key(daily.id())).metadataLocation()).isEqualTo(location);
        assertThat(batches).singleElement().satisfies(batch -> assertThat(batch.ref())
                .contains(new RefLock.Hold(dev.id())));
        assertThat(registry.get(CatalogMetrics.COMMITS).tag(CatalogMetrics.REF_KIND, CatalogMetrics.BRANCH)
                .tag(CatalogMetrics.OPERATION_ID, "replaceView").counter().count()).isEqualTo(1);
        MockHttpServletResponse onMain = perform(get("/v1/trading/namespaces/desk/views/daily"));
        assertThat(JsonPath.<String>read(onMain.getContentAsString(), "$.metadata.properties.marker"))
                .isEqualTo("main");
    }

    @Test
    @DisplayName("a transaction on a branch swaps each table's own pointer in one batch; main's do not move")
    void aTransactionOnABranch() throws Exception {
        MockHttpServletResponse response = perform(post("/v1/trading@dev/transactions/commit")
                .contentType("application/json").content("""
                        {"table-changes":[{"identifier":{"namespace":["desk"],"name":"orders"},%s},\
                        {"identifier":{"namespace":["desk"],"name":"fills"},%s}]}""".formatted(
                        APPEND.substring(1, APPEND.length() - 1), APPEND.substring(1, APPEND.length() - 1))));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(204);
        assertThat(batches).singleElement().satisfies(batch -> {
            assertThat(batch.ref()).contains(new RefLock.Hold(dev.id()));
            assertThat(batch.swaps()).extracting(PointerSwap::key)
                    .containsExactlyInAnyOrder(dev.key(orders.id()), dev.key(fills.id()));
        });
        assertThat(pointers.get(orders.mainKey()).metadataLocation()).isEqualTo(ordersMain);
        assertThat(pointers.get(fills.mainKey()).metadataLocation()).isEqualTo(fillsMain);
        assertThat(registry.get(CatalogMetrics.COMMITS).tag(CatalogMetrics.REF_KIND, CatalogMetrics.BRANCH)
                .tag(CatalogMetrics.OPERATION_ID, "commitTransaction").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a commit that lands on a branch moves its expiry 30 days ahead with a renewed event naming who"
            + " committed (choice 11); a refused one does not")
    void aCommitRenewsTheBranch() throws Exception {
        Ref hot = ref("hot", Ref.Kind.BRANCH, Optional.of(NOW.plus(Duration.ofDays(5))));
        cut(hot);
        attributes.add(attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true"));
        assertThat(perform(commit("trading@hot", "orders", APPEND)).getStatus()).isEqualTo(403);
        assertThat(renewals).isEmpty();

        committed(perform(commit("trading@hot", "fills", APPEND)));

        assertThat(renewals).singleElement().satisfies(renewal -> {
            assertThat(renewal.refId()).isEqualTo(hot.id());
            assertThat(renewal.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
            assertThat(renewal.change().outbox()).singleElement().satisfies(event -> assertThat(event.payloadJson())
                    .contains("RENEWED").contains("\"principal\":\"mo\""));
        });
    }

    @Test
    @DisplayName("through a branch, a table main has dropped since is served read-only: its load and credentials vend"
            + " no write, and a commit to it is answered 404 (security finding 1)")
    void aTableMainDroppedIsReadOnlyOnABranch() throws Exception {
        onMain.replaceAll(o -> o.id().equals(orders.id()) ? retired(o, CatalogObject.State.DROPPED) : o);

        assertLoaded(perform(get("/v1/trading@dev/namespaces/desk/tables/orders")), ordersCut, "cut");
        assertThat(perform(get("/v1/trading@dev/namespaces/desk/tables/orders/credentials")).getStatus())
                .isEqualTo(200);
        assertThat(vended).extracting(CredentialVending.Request::write).containsExactly(false, false);
        assertThat(perform(get("/v1/trading/namespaces/desk/tables/orders")).getStatus()).isEqualTo(404);

        MockHttpServletResponse refused = perform(commit("trading@dev", "orders", APPEND));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(404);
        assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.error.message"))
                .endsWith("(main has dropped or unregistered it since branch dev was made)");
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("a table main has unregistered is served through no ref: the tag and the branch answer 404 for its"
            + " load, existence, credentials and commits (security finding 1)")
    void aTableMainUnregisteredIsGoneFromEveryRef() throws Exception {
        onMain.replaceAll(o -> o.id().equals(fills.id()) ? retired(o, CatalogObject.State.UNREGISTERED) : o);

        for (String table : List.of("/v1/trading@eod/namespaces/desk/tables/trades",
                "/v1/trading@dev/namespaces/desk/tables/fills")) {
            assertThat(perform(get(table)).getStatus()).as(table).isEqualTo(404);
            assertThat(perform(head(table)).getStatus()).as(table).isEqualTo(404);
            assertThat(perform(get(table + "/credentials")).getStatus()).as(table).isEqualTo(404);
        }
        assertThat(perform(commit("trading@dev", "fills", APPEND)).getStatus()).isEqualTo(404);
        assertThat(vended).isEmpty();
        assertThat(batches).isEmpty();
    }

    // ---- authorization through a ref (security finding 5, for item 104) ----

    @Test
    @DisplayName("through a tag or a branch, a caller holding nothing on a table is told it does not exist, and the"
            + " refusal is audited")
    void aCallerHoldingNothingIsTold404() throws Exception {
        caller.set(STRANGER);
        for (String prefix : List.of("trading@eod", "trading@dev")) {
            MockHttpServletResponse response = perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders"));
            assertThat(response.getStatus()).as(prefix + ": " + response.getContentAsString()).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.getContentAsString(), "$.error.type"))
                    .isEqualTo("NoSuchTableException");
        }
        assertThat(denials()).hasSize(2).allSatisfy(denial -> {
            assertThat(denial.operation()).isEqualTo("loadTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.actor().principal()).isEqualTo("st");
        });
    }

    @Test
    @DisplayName("through a tag or a branch, a caller who may only describe a table is refused its load with 403")
    void aDescriberIsRefusedTheLoad() throws Exception {
        caller.set(DESCRIBER);
        for (String prefix : List.of("trading@eod", "trading@dev")) {
            assertForbidden(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders")));
        }
        assertThat(denials()).hasSize(2).allSatisfy(denial -> assertThat(denial.privilege()).isEqualTo("select"));
    }

    @Test
    @DisplayName("through a tag or a branch, a table with a row/column policy gives an engine that is not trusted no"
            + " storage access: its load and credentials are refused (D20)")
    void aFlaggedTableRefusesAnUntrustedEngineThroughARef() throws Exception {
        attributes.add(attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true"));
        for (String prefix : List.of("trading@eod", "trading@dev")) {
            assertForbidden(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders")));
            assertForbidden(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders/credentials")));
        }
        assertThat(denials()).hasSize(4).allSatisfy(denial -> assertThat(denial.reason()).startsWith("flagged_table"));
        assertThat(vended).isEmpty();
    }

    @Test
    @DisplayName("through a tag or a branch, an unclassified table in a regulated area is closed (D27)")
    void anUnclassifiedTableIsClosedThroughARef() throws Exception {
        attributes.add(attribute(new Securable(Securable.Kind.NAMESPACE, desk.id()), GovernanceAttribute.Kind.REGULATED,
                "true"));
        for (String prefix : List.of("trading@eod", "trading@dev")) {
            assertForbidden(perform(get("/v1/" + prefix + "/namespaces/desk/tables/orders")));
        }
        assertThat(denials()).hasSize(2).allSatisfy(denial -> assertThat(denial.reason()).startsWith("unclassified"));
    }

    // ---- grants that name a branch (backlog 113, security finding 11) ----

    @Test
    @DisplayName("a grant that names a branch lets its group commit through that branch and no other ref; its loads"
            + " and credentials through the branch write only beneath the branch's place, and say where")
    void aBranchOnlyWriterCommitsThroughItsBranchAndWritesOnlyBeneathItsPlace() throws Exception {
        grants.add(new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE, desk.id()),
                "dev-writers", Privilege.WRITE.wireName(), Optional.of(dev.id())), "test", Instant.EPOCH));
        caller.set(DEV_WRITER);
        String place = orders.locationRoot() + "/branches/" + dev.id();

        assertForbidden(perform(commit("trading", "orders", APPEND)));
        assertForbidden(perform(commit("trading@wip", "orders", APPEND)));
        assertThat(batches).isEmpty();
        committed(perform(commit("trading@dev", "orders", APPEND)));
        assertThat(pointers).containsKey(dev.key(orders.id()));

        vended.clear();
        MockHttpServletResponse onDev = perform(get("/v1/trading@dev/namespaces/desk/tables/orders"));
        assertThat(onDev.getStatus()).isEqualTo(200);
        assertThat(perform(get("/v1/trading@dev/namespaces/desk/tables/orders/credentials")).getStatus())
                .isEqualTo(200);
        MockHttpServletResponse onMain = perform(get("/v1/trading/namespaces/desk/tables/orders"));
        assertThat(vended).extracting(CredentialVending.Request::write).containsExactly(true, true, false);
        assertThat(vended).extracting(CredentialVending.Request::writeUnder).containsExactly(
                Optional.of("branches/" + dev.id()), Optional.of("branches/" + dev.id()), Optional.empty());
        assertThat(JsonPath.<String>read(onDev.getContentAsString(), "$.metadata.properties['write.data.path']"))
                .isEqualTo(place + "/data");
        assertThat(JsonPath.<String>read(onDev.getContentAsString(),
                "$.metadata.properties['write.metadata.path']")).isEqualTo(place + "/metadata");
        assertThat(onMain.getContentAsString()).doesNotContain("write.data.path");
        assertThat(onDev.getHeader(HttpHeaders.ETAG)).isNotNull();

        caller.set(MO);
        vended.clear();
        assertThat(perform(get("/v1/trading@dev/namespaces/desk/tables/orders")).getContentAsString())
                .as("a writer on every ref writes all of the table").doesNotContain("write.data.path");
        assertThat(vended).singleElement().satisfies(request -> {
            assertThat(request.write()).isTrue();
            assertThat(request.writeUnder()).isEmpty();
        });
    }

    // ---- governance on a branch's commits (security finding 4) ----

    @Test
    @DisplayName("on a branch, an engine that is not trusted may not commit to a table with a row/column policy (D20):"
            + " 403, audited, nothing written")
    void aFlaggedTableRefusesAnUntrustedCommitOnABranch() throws Exception {
        attributes.add(attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true"));
        int files = this.files.size();

        assertForbidden(perform(commit("trading@dev", "orders", APPEND)));

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("updateTable");
            assertThat(denial.reason()).startsWith("flagged_table");
        });
        assertThat(this.files).hasSize(files);
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("the maintenance exemption holds on main only (choice 14): the maintenance principal's commit to a"
            + " flagged table lands on main, exempted and audited, and is refused on a branch")
    void theMaintenanceExemptionHoldsOnMainOnly() throws Exception {
        caller.set(MAINTENANCE);
        attributes.add(attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true"));

        String message = assertForbidden(perform(commit("trading@dev", "orders", APPEND)));
        assertThat(message).contains("the maintenance exemption holds on main only");
        assertThat(denials()).singleElement().satisfies(denial -> assertThat(denial.reason())
                .startsWith("flagged_table"));

        committed(perform(commit("trading", "orders", APPEND)));
        assertThat(audited).extracting(CatalogEvent::data).hasAtLeastOneElementOfType(MaintenanceExemption.class);
        assertThat(pointers).doesNotContainKey(dev.key(orders.id()));
    }

    @Test
    @DisplayName("on a branch, removing unused schemas and partition specs needs modify, even from the maintenance"
            + " principal: snapshot expiry's allowance holds on main only (choice 14)")
    void theExpiryAllowanceHoldsOnMainOnly() throws Exception {
        caller.set(MAINTENANCE);

        assertForbidden(perform(commit("trading@dev", "orders", """
                {"requirements":[],"updates":[{"action":"remove-schemas","schema-ids":[5]}]}""")));

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.privilege()).isEqualTo("modify");
            assertThat(denial.reason()).startsWith("no_privilege");
        });
    }

    @Test
    @DisplayName("on a branch, an unclassified table in a regulated area takes no commit (D27)")
    void anUnclassifiedTableTakesNoCommitOnABranch() throws Exception {
        attributes.add(attribute(new Securable(Securable.Kind.NAMESPACE, desk.id()), GovernanceAttribute.Kind.REGULATED,
                "true"));

        assertForbidden(perform(commit("trading@dev", "orders", APPEND)));

        assertThat(denials()).singleElement().satisfies(denial -> assertThat(denial.reason())
                .startsWith("unclassified"));
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("on a branch, a trusted engine that names no end user may not write to a regulated area with D17's"
            + " switch on")
    void anIdentityLessWriteIsRefusedOnABranch() throws Exception {
        caller.set(ETL);
        attributes.add(attribute(new Securable(Securable.Kind.NAMESPACE, desk.id()), GovernanceAttribute.Kind.REGULATED,
                "true"));
        attributes.add(attribute(orders.securable(), GovernanceAttribute.Kind.CLASSIFICATION, "internal"));

        assertForbidden(perform(commit("trading@dev", "orders", APPEND)));

        assertThat(denials()).singleElement().satisfies(denial -> assertThat(denial.reason())
                .startsWith("identity_less"));
        assertThat(batches).isEmpty();
    }

    // ---- /v1/config ----

    @Test
    @DisplayName("/v1/config answers the prefix catalog@ref; a tag lists only the read endpoints, a branch the reads"
            + " and the commits, main every endpoint")
    void configNamesTheRefAndWhatItServes() throws Exception {
        String tag = config("trading@EOD");
        assertThat(JsonPath.<String>read(tag, "$.overrides.prefix")).isEqualTo("trading@eod");
        assertThat(JsonPath.<List<String>>read(tag, "$.endpoints")).containsExactly(
                "GET /v1/{prefix}/namespaces",
                "GET /v1/{prefix}/namespaces/{namespace}",
                "HEAD /v1/{prefix}/namespaces/{namespace}",
                "GET /v1/{prefix}/namespaces/{namespace}/tables",
                "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
                "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
                "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
                "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
                "GET /v1/{prefix}/namespaces/{namespace}/views",
                "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
                "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}");

        String branch = config("trading%40dev");
        assertThat(JsonPath.<String>read(branch, "$.overrides.prefix")).isEqualTo("trading@dev");
        List<String> branchEndpoints = JsonPath.read(branch, "$.endpoints");
        assertThat(branchEndpoints).containsAll(JsonPath.<List<String>>read(tag, "$.endpoints"))
                .contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
                        "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
                        "POST /v1/{prefix}/transactions/commit")
                .hasSize(14);

        for (String main : List.of("trading", "trading@main")) {
            String answer = config(main);
            assertThat(JsonPath.<String>read(answer, "$.overrides.prefix")).isEqualTo("trading");
            assertThat(JsonPath.<List<String>>read(answer, "$.endpoints")).containsAll(branchEndpoints)
                    .contains("POST /v1/{prefix}/namespaces", "POST /v1/{prefix}/tables/rename").hasSize(26);
        }
    }

    // ---- helpers ----

    private static final String METRICS_REPORT = """
            {"report-type":"scan-report","table-name":"desk.trades","snapshot-id":1,"filter":true,"schema-id":0,\
            "projected-field-ids":[1],"projected-field-names":["id"],"metrics":{}}""";

    /** A write route, its operation as the REST spec names it, and whether it is a commit. */
    private record Write(String method, String path, String operation, boolean commit) {
    }

    private static final List<Write> WRITES = List.of(
            new Write("POST", "/namespaces", "createNamespace", false),
            new Write("DELETE", "/namespaces/desk", "dropNamespace", false),
            new Write("POST", "/namespaces/desk/properties", "updateProperties", false),
            new Write("POST", "/namespaces/desk/tables", "createTable", false),
            new Write("DELETE", "/namespaces/desk/tables/orders", "dropTable", false),
            new Write("POST", "/tables/rename", "renameTable", false),
            new Write("POST", "/namespaces/desk/register", "registerTable", false),
            new Write("POST", "/namespaces/desk/tables/orders/unregister", "unregisterTable", false),
            new Write("POST", "/namespaces/desk/views", "createView", false),
            new Write("DELETE", "/namespaces/desk/views/daily", "dropView", false),
            new Write("POST", "/views/rename", "renameView", false),
            new Write("POST", "/namespaces/desk/register-view", "registerView", false),
            new Write("POST", "/namespaces/desk/tables/orders", "updateTable", true),
            new Write("POST", "/namespaces/desk/views/daily", "replaceView", true),
            new Write("POST", "/transactions/commit", "commitTransaction", true));

    private void assertRefused(Write write, String prefix, String message) throws Exception {
        String path = "/v1/" + prefix + write.path();
        MockHttpServletRequestBuilder request = write.method().equals("DELETE") ? delete(path)
                : post(path).contentType("application/json").content("{}");
        MockHttpServletResponse response = perform(request);
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(write.operation() + ": " + body).isEqualTo(400);
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("BadRequestException");
        assertThat(JsonPath.<String>read(body, "$.error.message")).isEqualTo(message);
    }

    /** A commit of {@code body} to table {@code table} of namespace desk, through {@code prefix}. */
    private static MockHttpServletRequestBuilder commit(String prefix, String table, String body) {
        return post("/v1/" + prefix + "/namespaces/desk/tables/" + table).contentType("application/json").content(body);
    }

    /** The metadata location a commit answered 200 with. */
    private static String committed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return JsonPath.read(response.getContentAsString(), "$['metadata-location']");
    }

    /** The message of a 403. */
    private static String assertForbidden(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(403);
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("ForbiddenException");
        return JsonPath.read(body, "$.error.message");
    }

    private List<Denial> denials() {
        return audited.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private GovernanceAttribute attribute(Securable target, GovernanceAttribute.Kind kind, String value) {
        return new GovernanceAttribute(target, kind, value, "test", "admin", NOW);
    }

    private static CatalogObject retired(CatalogObject object, CatalogObject.State state) {
        return new CatalogObject(object.catalogId(), object.id(), object.namespaceId(), object.name(), object.kind(),
                state, Optional.of(NOW), state == CatalogObject.State.DROPPED ? Optional.of(NOW.plus(Duration.ofDays(7)))
                        : Optional.empty(), object.locationRoot(), Optional.empty());
    }

    private void assertLoaded(MockHttpServletResponse response, String location, String marker) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertThat(JsonPath.<String>read(body, "$['metadata-location']")).isEqualTo(location);
        assertThat(JsonPath.<String>read(body, "$.metadata.properties.marker")).isEqualTo(marker);
    }

    private String config(String warehouse) throws Exception {
        MockHttpServletResponse response = perform(get(URI.create("/v1/config?warehouse=" + warehouse)));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    private static List<String> namespaces(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        List<List<String>> levels = JsonPath.read(response.getContentAsString(), "$.namespaces");
        return levels.stream().map(List::getLast).toList();
    }

    private static List<String> names(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return JsonPath.read(response.getContentAsString(), "$.identifiers[*].name");
    }

    private List<ObjectRead> reads() {
        return audited.stream().map(CatalogEvent::data).filter(ObjectRead.class::isInstance)
                .map(ObjectRead.class::cast).toList();
    }

    private double integrityAlerts() {
        return registry.counter(ServerAlerts.INTEGRITY).count();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        // What RequestIds would have set before the route ran.
        return mvc.perform(request.requestAttr(RequestIds.class.getName(), "request-1")).andReturn().getResponse();
    }

    private CatalogObject object(String name, Kind kind) {
        UUID id = UUID.randomUUID();
        return CatalogObject.active(catalog.id(), id, desk.id(), name(name), kind, ROOT + "/" + id);
    }

    private Ref ref(String name, Ref.Kind kind, Optional<Instant> expiresAt) {
        Ref ref = new Ref(catalog.id(), UUID.randomUUID(), name(name), kind, NOW.minus(Duration.ofDays(2)), "mo",
                Optional.empty(), Ref.MAIN, expiresAt, Optional.empty());
        refs.put(ref.name().normalized(), ref);
        return ref;
    }

    private RefEntry namespaceEntry(Ref ref, UUID id, String name) {
        return new RefEntry(ref.id(), id, RefEntry.Kind.NAMESPACE, catalog.id(), name(name), Optional.empty());
    }

    private RefEntry entry(Ref ref, UUID id, RefEntry.Kind kind, String name, String location, long version) {
        return new RefEntry(ref.id(), id, kind, desk.id(), name(name),
                Optional.of(new RefEntry.At(version, location, sha256(files.get(path(location))))));
    }

    private Pointer pointer(String location, long version) {
        return new Pointer(location, version, sha256(files.get(path(location))), new byte[0]);
    }

    /** A table's metadata file, its property {@code marker} telling the versions apart; its location. */
    private String tableFile(CatalogObject table, String name, int version, String marker) {
        String request = """
                {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,\
                "type":"long"}]},"properties":{"marker":"%s"}}""".formatted(name, marker);
        return file(table, version, marker, ((Outcome.NewMetadata) Metadata.createTable(request, table.id().toString(),
                table.locationRoot())).json());
    }

    private String viewFile(CatalogObject view, int version, String marker) {
        String request = """
                {"name":"daily","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id",\
                "required":true,"type":"long"}]},"view-version":{"version-id":1,"timestamp-ms":1,"schema-id":0,\
                "summary":{},"representations":[{"type":"sql","sql":"select 1 as id","dialect":"spark"}],\
                "default-namespace":["desk"]},"properties":{"marker":"%s"}}""".formatted(marker);
        return file(view, version, marker, ((Outcome.NewMetadata) Metadata.createView(request, view.id().toString(),
                view.locationRoot(), 1)).json());
    }

    private String file(CatalogObject object, int version, String marker, String json) {
        String location = object.locationRoot() + "/metadata/0000" + version + "-" + marker + ".metadata.json";
        files.put(path(location), json.getBytes(UTF_8));
        return location;
    }

    /** Where a path-style request finds a location: {@code /bucket/key}. */
    private static String path(String location) {
        return location.substring("s3:/".length());
    }

    private static String sha256(byte[] bytes) {
        return digest("SHA-256", bytes);
    }

    private static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bytes of an {@code aws-chunked} body, as S3 stores them: each chunk's data, without its header. */
    private static byte[] unchunked(byte[] sent) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int at = 0;
        while (true) {
            int end = at;
            while (sent[end] != '\r' || sent[end + 1] != '\n') {
                end++;
            }
            int size = Integer.parseInt(new String(sent, at, end - at, UTF_8).split(";")[0].trim(), 16);
            if (size == 0) {
                return bytes.toByteArray();
            }
            bytes.write(sent, end + 2, size);
            at = end + 2 + size + 2;
        }
    }

    private static Name name(String display) {
        return new Name(Names.normalize(display), display);
    }

    private GrantRecord grant(Securable securable, String group, Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), securable, group, privilege.wireName()), "test", Instant.EPOCH);
    }

    /** The read audit the routes call, built as Spring builds it, recording what it publishes. */
    private ReadAudit readAudit(CatalogStore store, Callers callers, RestSettings settings, Holdings holdings,
                                Clock clock) throws ReflectiveOperationException {
        Class<?> management = Class.forName("nuna.catalog.server.governance.Management");
        Constructor<?> newManagement = management.getDeclaredConstructor(CatalogStore.class, Callers.class,
                RestSettings.class, Holdings.class);
        newManagement.setAccessible(true);
        Constructor<ReadAudit> newAudit = ReadAudit.class.getDeclaredConstructor(AuditTrail.class, management);
        newAudit.setAccessible(true);
        AuditTrail trail = new AuditTrail(Optional.<nuna.catalog.audit.publish.EventPublisher>of(
                stamped -> audited.add(stamped.event())), URI.create("urn:nuna:test"), clock, Duration.ofMinutes(1));
        return newAudit.newInstance(trail, newManagement.newInstance(store, callers, settings, holdings));
    }

    /**
     * Main's namespace desk, its objects and pointers, the refs and their cuts, the grants and the storage profile;
     * any call not answered here, a write among them, fails the request with 500.
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
                    return switch (call) {
                        case "readStorageProfile" -> Optional.empty();
                        case "putStorageProfile" -> {
                            profile.set(((StorageProfile) args[0]).id());
                            yield WriteResult.APPLIED;
                        }
                        case "catalogSettings" -> Optional.of(new CatalogSettings(profile.get(), "test-keys",
                                "test-entity"));
                        case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                        case "findNamespace" -> Optional.of(desk).filter(ns -> args[1].equals(List.of("desk")));
                        case "readNamespace" -> Optional.of(desk).filter(ns -> ns.id().equals(args[0]));
                        case "listNamespaces" -> new Page<>(args[0].equals(catalog.id()) ? List.of(desk) : List.of(),
                                Optional.empty());
                        case "findObject" -> onMain.stream().filter(o -> o.state() == CatalogObject.State.ACTIVE
                                && o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]))
                                .findFirst();
                        case "readObject" -> onMain.stream().filter(o -> o.id().equals(args[0])).findFirst();
                        case "listObjects" -> new Page<>(onMain.stream().filter(o -> o.state()
                                == CatalogObject.State.ACTIVE && o.namespaceId().equals(args[0]) && o.kind() == args[1])
                                .sorted(Comparator.comparing(o -> o.name().normalized())).toList(), Optional.empty());
                        case "readPointer" -> Optional.ofNullable(pointers.get((PointerKey) args[0]));
                        case "findRef" -> Optional.ofNullable(refs.get((String) args[1]))
                                .filter(r -> r.catalogId().equals(args[0]));
                        case "findRefNamespace" -> refNamespace((UUID) args[0], (List<String>) args[1]);
                        case "resolveOnRef" -> refNamespace((UUID) args[0], (List<String>) args[1])
                                .flatMap(ns -> cuts.get((UUID) args[0]).stream().filter(e -> e.kind()
                                        != RefEntry.Kind.NAMESPACE && e.parentId().equals(ns.id())
                                        && e.name().normalized().equals(args[2])).findFirst());
                        case "listRefNamespaces" -> listed((UUID) args[0], (UUID) args[1], RefEntry.Kind.NAMESPACE);
                        case "listRefObjects" -> listed((UUID) args[0], (UUID) args[1],
                                RefEntry.of((Kind) args[2]));
                        case "grantsFor" -> grants.stream()
                                .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                        && ((Set<String>) args[1]).contains(g.grant().group()))
                                .toList();
                        case "listGrantsOfGroups" -> new Page<>(grants.stream()
                                .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                                Optional.empty());
                        case "attributesOf" -> attributes.stream()
                                .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                        case "holdsOn" -> List.of();
                        case "countOwnPointers" -> pointers.keySet().stream()
                                .filter(key -> key.branch().equals(args[0].toString())).count();
                        case "commit" -> commit((CommitBatch) args[0]);
                        case "renewRef" -> {
                            renewals.add(new Renewal((UUID) args[0], (Instant) args[1], (Change) args[2]));
                            yield WriteResult.APPLIED;
                        }
                        default -> throw new UnsupportedOperationException(call);
                    };
                });
    }

    /** Swaps each pointer that is as the batch expects, as the stores do; a branch's batch swaps its own only. */
    private synchronized CommitResult commit(CommitBatch batch) {
        for (PointerSwap swap : batch.swaps()) {
            assertThat(batch.ref().map(lock -> lock.owns(swap.key())).orElse(swap.key().branch()
                    .equals(PointerKey.MAIN))).as("a batch swaps its branch's own pointers, or main's").isTrue();
            Optional<Pointer> actual = Optional.ofNullable(pointers.get(swap.key()));
            if (!actual.map(Pointer::metadataLocation).equals(swap.expected().map(Pointer::metadataLocation))) {
                return new CommitResult.Conflict(swap.key(), actual);
            }
        }
        batch.swaps().forEach(swap -> pointers.put(swap.key(), swap.next()));
        batches.add(batch);
        return new CommitResult.Committed(batch.commitId());
    }

    /** A branch's renewal: its new expiry, and the change carrying its event. */
    private record Renewal(UUID refId, Instant expiresAt, Change change) {
    }

    /** The branch's cut, as every branch's here: desk, orders and daily as the tag took them, fills as main has it. */
    private void cut(Ref branch) {
        cuts.put(branch.id(), List.of(
                namespaceEntry(branch, desk.id(), "desk"),
                entry(branch, orders.id(), RefEntry.Kind.TABLE, "orders", ordersCut, 1),
                entry(branch, fills.id(), RefEntry.Kind.TABLE, "fills", fillsMain, 2),
                entry(branch, daily.id(), RefEntry.Kind.VIEW, "daily", dailyCut, 1)));
    }

    /** The namespace at a normalized path in a ref's cut. */
    private Optional<RefEntry> refNamespace(UUID refId, List<String> path) {
        UUID parent = catalog.id();
        Optional<RefEntry> found = Optional.empty();
        for (String level : path) {
            UUID above = parent;
            found = cuts.get(refId).stream().filter(e -> e.kind() == RefEntry.Kind.NAMESPACE
                    && e.parentId().equals(above) && e.name().normalized().equals(level)).findFirst();
            if (found.isEmpty()) {
                return found;
            }
            parent = found.get().id();
        }
        return found;
    }

    private Page<RefEntry> listed(UUID refId, UUID parentId, RefEntry.Kind kind) {
        List<RefEntry> entries = new ArrayList<>(cuts.get(refId).stream()
                .filter(e -> e.kind() == kind && e.parentId().equals(parentId)).toList());
        entries.sort(Comparator.comparing(e -> e.name().normalized()));
        return new Page<>(entries, Optional.empty());
    }
}
