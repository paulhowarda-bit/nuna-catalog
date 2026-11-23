package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.RefChange;
import nuna.catalog.audit.publish.AuditUnavailableException;
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
import nuna.catalog.server.security.SecuritySettings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A catalog's ref routes (D33, item 106): making, renewing and deleting a ref needs {@code create} on the catalog and
 * listing and reading any holding in it (choice 12); P11's limits refuse a create, naming the setting; a renewal moves
 * a branch's expiry 30 days ahead, never past 180 days from its creation (choice 11); a branch past its expiry is gone
 * for every route; an Idempotency-Key replays the first answer; each change writes its {@code ref} record with it;
 * and the maintenance worker, alone, warns of a branch once for each expiry and ends it once expired, from the work
 * list's refs section. The routes, guard and idempotency are the real ones over a store double that keeps refs, their
 * cuts and idempotency keys as every store does (RefSuite and IdempotencySuite check the stores), so this needs only
 * Java.
 */
class RefRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);
    /** Two open branches and three tags a catalog, so a test reaches each limit. */
    private static final RefSettings SETTINGS = new RefSettings(2, 3, Duration.ofDays(30), Duration.ofDays(180),
            Duration.ofDays(7));

    /** Create on the catalog. */
    private static final Caller BEA = person("bea", "builders");
    /** Describe on the namespace hr only: something in the catalog, nothing on sales. */
    private static final Caller HAL = person("hal", "hr-readers");
    /** Describe on the whole catalog, and nothing more. */
    private static final Caller VIC = person("vic", "viewers");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of()), "worker", false,
            Optional.empty());
    private static final Caller WORKER_FOR_BEA = new Caller(new Principal("nuna-maintenance", Set.of()), "worker",
            true, Optional.of(new Principal("bea", Set.of("builders"))));

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final NamespaceRecord hr = namespace("hr");
    private final NamespaceRecord sales = namespace("sales");
    private final CatalogObject staff = object(hr, "Staff");
    private final CatalogObject salaries = object(hr, "salaries");
    private final CatalogObject orders = object(sales, "orders");

    private final Map<UUID, Ref> refs = new LinkedHashMap<>();
    private final Map<UUID, List<RefEntry>> cuts = new HashMap<>();
    private final Map<PointerKey, Pointer> pointers = new HashMap<>();
    private final Map<String, Key> keys = new HashMap<>();
    private final List<CatalogEvent> outbox = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final List<CatalogEvent> notices = new ArrayList<>();
    private RuntimeException noticesFail;

    private final MutableClock clock = new MutableClock(NOW);
    private final RefRoutes routes;
    private final MaintenanceRoutes maintenance;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = BEA;

    RefRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(new Securable(Securable.Kind.CATALOG, catalog.id()), "builders", Privilege.CREATE);
        grant(new Securable(Securable.Kind.NAMESPACE, hr.id()), "hr-readers", Privilege.DESCRIBE);
        grant(new Securable(Securable.Kind.CATALOG, catalog.id()), "viewers", Privilege.DESCRIBE);
        CatalogStore store = store();
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> caller, rest, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        CatalogNames names = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return id.equals(catalog.id()) ? "gate" : null;
            }
        };
        RefRoutes.Notices sent = event -> {
            if (noticesFail != null) {
                throw noticesFail;
            }
            notices.add(event);
        };
        routes = new RefRoutes(store, management, guard, new ManagementIdempotency(store, clock), authorizer, SETTINGS,
                names, sent, rest, clock);
        StorageSettings storage = new StorageSettings(Map.of(), Map.of("gate", new StorageSettings.Catalog(
                "warehouse", null, null)), Duration.ofHours(1), 100, false);
        // No governance settings: only the refs section is asked for.
        maintenance = new MaintenanceRoutes(store, management, guard, null, names, SETTINGS, storage, clock, authorizer);
    }

    // ---- rights ----

    @Test
    void makingRenewingAndDeletingNeedCreateOnTheCatalog() {
        create("dev", "branch", null, null);
        for (Caller other : List.of(HAL, VIC)) {
            caller = other;
            assertThat(answer(() -> routes.create("gate", null, body("qa", "branch", null), http)).status())
                    .as("create by %s", other).isEqualTo(403);
            assertThat(answer(() -> routes.renew("gate", "dev", null, http)).status()).as("renew by %s", other)
                    .isEqualTo(403);
            assertThat(answer(() -> routes.delete("gate", "dev", null, http)).status()).as("delete by %s", other)
                    .isEqualTo(403);
        }
        caller = NED;
        assertThat(answer(() -> routes.create("gate", null, body("qa", "branch", null), http)))
                .as("one who holds nothing in the catalog is told it does not exist")
                .isEqualTo(new Answer(404, "NoSuchWarehouseException", "The given warehouse does not exist: gate"));

        assertThat(denials()).extracting(Denial::operation).containsExactly("createRef", "renewRef", "deleteRef",
                "createRef", "renewRef", "deleteRef", "createRef");
        assertThat(denials().getFirst().privilege()).isEqualTo("create");
        assertThat(refs.values()).extracting(ref -> ref.name().display()).containsExactly("dev");
        assertThat(events(RefChange.Action.RENEWED)).isEmpty();
    }

    @Test
    void listingAndReadingNeedAnyHoldingInTheCatalog() {
        create("dev", "branch", null, null);
        caller = HAL;

        assertThat(json(routes.list("gate", null, null, http)).get("refs")).hasSize(1);
        assertThat(json(routes.read("gate", "dev", http)).get("name").asString()).isEqualTo("dev");
        caller = NED;
        assertThat(answer(() -> routes.list("gate", null, null, http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.read("gate", "dev", http)).status()).isEqualTo(404);

        assertThat(denials()).extracting(Denial::operation).containsExactly("listRefs", "loadRef");
    }

    // ---- create ----

    @Test
    void aCreateMakesTheRefWithItsExpiryAndRecordsIt() {
        JsonNode dev = create("dev", "branch", null, null);
        JsonNode release = create("Release-1", "TAG", null, null);
        JsonNode fix = create("fix", "branch", "DEV", null);

        assertThat(dev.get("kind").asString()).isEqualTo("branch");
        assertThat(dev.get("from").asString()).isEqualTo("main");
        assertThat(dev.get("created-at").asString()).isEqualTo(NOW.toString());
        assertThat(dev.get("created-by").asString()).isEqualTo("bea");
        assertThat(dev.get("expires-at").asString()).isEqualTo(NOW.plus(DAY.multipliedBy(30)).toString());
        assertThat(dev.get("max-expires-at").asString()).isEqualTo(NOW.plus(DAY.multipliedBy(180)).toString());
        assertThat(dev.get("expiring").asBoolean()).isFalse();
        assertThat(release.get("name").asString()).isEqualTo("Release-1");
        assertThat(release.get("kind").asString()).isEqualTo("tag");
        assertThat(release.has("expires-at")).as("a tag never expires").isFalse();
        assertThat(fix.get("from").asString()).isEqualTo("dev");
        assertThat(refs.get(id(fix)).fromRef()).contains(id(dev));
        assertThat(cuts.get(id(dev))).extracting(entry -> entry.name().display())
                .containsExactlyInAnyOrder("hr", "sales", "Staff", "salaries", "orders");

        List<RefChange> created = events(RefChange.Action.CREATED);
        assertThat(created).extracting(RefChange::name).containsExactly("dev", "Release-1", "fix");
        assertThat(created.getFirst().ref()).isEqualTo(id(dev));
        assertThat(created.getFirst().kind()).isEqualTo(RefChange.Kind.BRANCH);
        assertThat(created.getFirst().catalog().catalog()).isEqualTo("gate");
        assertThat(created.getFirst().catalog().id()).isEqualTo(catalog.id());
        assertThat(created.getFirst().from()).isEqualTo("main");
        assertThat(created.getFirst().expiresAt()).isEqualTo(NOW.plus(DAY.multipliedBy(30)));
        assertThat(created.getFirst().actor().principal()).isEqualTo("bea");
        assertThat(created.get(1).kind()).isEqualTo(RefChange.Kind.TAG);
        assertThat(created.get(1).expiresAt()).isNull();
        assertThat(created.get(2).from()).isEqualTo("dev");
    }

    @Test
    void aCreatePastALimitIsRefusedNamingIt() {
        create("dev", "branch", null, null);
        create("qa", "branch", null, null);
        for (String tag : List.of("v1", "v2", "v3")) {
            create(tag, "tag", null, null);
        }

        Answer branch = answer(() -> routes.create("gate", null, body("third", "branch", null), http));
        Answer tag = answer(() -> routes.create("gate", null, body("v4", "tag", null), http));

        assertThat(branch.status()).isEqualTo(409);
        assertThat(branch.type()).isEqualTo("RefLimitException");
        assertThat(branch.message()).contains("nuna.refs.max-branches").contains("2");
        assertThat(tag.status()).isEqualTo(409);
        assertThat(tag.message()).contains("nuna.refs.max-tags").contains("3");
        assertThat(refs).hasSize(5);

        clock.advance(DAY.multipliedBy(30));
        assertThat(answer(() -> routes.create("gate", null, body("third", "branch", null), http)).status())
                .as("a branch past its expiry no longer counts").isEqualTo(201);
    }

    @Test
    void aCreateIsRefusedForABadNameKindOrSource() {
        create("dev", "branch", null, null);

        assertThat(answer(() -> routes.create("gate", null, body("a@b", "branch", null), http)).status())
                .isEqualTo(400);
        assertThat(answer(() -> routes.create("gate", null, body("x/y", "branch", null), http)).status())
                .isEqualTo(400);
        assertThat(answer(() -> routes.create("gate", null, body("Main", "branch", null), http)).status())
                .isEqualTo(400);
        assertThat(answer(() -> routes.create("gate", null, body("qa", "trunk", null), http)).status())
                .isEqualTo(400);
        assertThat(answer(() -> routes.create("gate", null, "{\"kind\":\"branch\"}", http)).status())
                .isEqualTo(400);
        assertThat(answer(() -> routes.create("gate", null, body("qa", "branch", "nowhere"), http)))
                .isEqualTo(new Answer(404, "NoSuchRefException", "Ref does not exist: nowhere"));
        assertThat(answer(() -> routes.create("gate", null, body("DEV", "tag", null), http)))
                .isEqualTo(new Answer(409, "AlreadyExistsException", "Ref already exists: DEV"));

        assertThat(refs).hasSize(1);
        assertThat(events(RefChange.Action.CREATED)).hasSize(1);
    }

    // ---- renew ----

    @Test
    void aRenewalMovesTheExpiryAheadNeverPastTheBranchsWholeLife() {
        UUID dev = id(create("dev", "branch", null, null));

        clock.advance(DAY.multipliedBy(20));
        JsonNode renewed = json(routes.renew("gate", "dev", null, http));

        assertThat(renewed.get("expires-at").asString()).isEqualTo(NOW.plus(DAY.multipliedBy(50)).toString());
        assertThat(events(RefChange.Action.RENEWED)).singleElement().satisfies(event -> {
            assertThat(event.ref()).isEqualTo(dev);
            assertThat(event.expiresAt()).isEqualTo(NOW.plus(DAY.multipliedBy(50)));
        });
        for (int day = 45; day <= 170; day += 25) {
            clock.advance(DAY.multipliedBy(25));
            assertThat(routes.renew("gate", "dev", null, http).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(refs.get(dev).expiresAt()).as("180 days from its creation, not 30 from the last renewal")
                .contains(NOW.plus(DAY.multipliedBy(180)));
        int renewals = events(RefChange.Action.RENEWED).size();

        clock.advance(DAY.multipliedBy(5));
        assertThat(json(routes.renew("gate", "dev", null, http)).get("expires-at").asString())
                .isEqualTo(NOW.plus(DAY.multipliedBy(180)).toString());
        assertThat(events(RefChange.Action.RENEWED)).as("a renewal at the end changes nothing").hasSize(renewals);
    }

    @Test
    void aTagIsNotRenewed() {
        create("v1", "tag", null, null);

        assertThat(answer(() -> routes.renew("gate", "v1", null, http)).status()).isEqualTo(400);
        assertThat(events(RefChange.Action.RENEWED)).isEmpty();
    }

    // ---- delete ----

    @Test
    void aDeleteFreesTheName() {
        UUID first = id(create("dev", "branch", null, null));

        assertThat(routes.delete("gate", "DEV", null, http).getStatusCode().value()).isEqualTo(204);

        assertThat(refs).isEmpty();
        assertThat(events(RefChange.Action.DELETED)).singleElement().satisfies(event -> {
            assertThat(event.ref()).isEqualTo(first);
            assertThat(event.actor().principal()).isEqualTo("bea");
        });
        assertThat(id(create("dev", "branch", null, null))).isNotEqualTo(first);
        assertThat(answer(() -> routes.delete("gate", "qa", null, http)).status()).isEqualTo(404);
    }

    // ---- expiry ----

    @Test
    void aBranchPastItsExpiryIsGoneForEveryRouteButKeepsItsName() {
        create("dev", "branch", null, null);
        create("v1", "tag", null, null);

        clock.advance(DAY.multipliedBy(30));

        assertThat(json(routes.list("gate", null, null, http)).get("refs").valueStream()
                .map(ref -> ref.get("name").asString()).toList()).containsExactly("v1");
        assertThat(answer(() -> routes.read("gate", "dev", http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.renew("gate", "dev", null, http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.delete("gate", "dev", null, http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.create("gate", null, body("fix", "branch", "dev"), http)).status())
                .isEqualTo(404);
        Answer taken = answer(() -> routes.create("gate", null, body("dev", "branch", null), http));
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.message()).contains("past its expiry");
        assertThat(json(routes.read("gate", "v1", http)).get("name").asString()).as("a tag never expires")
                .isEqualTo("v1");
    }

    // ---- idempotency ----

    @Test
    void aKeyReplaysTheFirstAnswer() {
        String body = body("dev", "branch", null);
        ResponseEntity<String> first = routes.create("gate", "key-1", body, http);
        ResponseEntity<String> again = routes.create("gate", "key-1", body, http);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(refs).hasSize(1);
        assertThat(events(RefChange.Action.CREATED)).hasSize(1);
        assertThat(answer(() -> routes.create("gate", "key-1", body("qa", "branch", null), http)).status())
                .as("the key on another request").isEqualTo(400);

        assertThat(routes.delete("gate", "dev", "key-2", http).getStatusCode().value()).isEqualTo(204);
        assertThat(routes.delete("gate", "dev", "key-2", http).getStatusCode().value()).isEqualTo(204);
        assertThat(events(RefChange.Action.DELETED)).hasSize(1);

        create("a", "branch", null, null);
        create("b", "branch", null, null);
        assertThat(answer(() -> routes.create("gate", "key-3", body("c", "branch", null), http)).status())
                .isEqualTo(409);
        routes.delete("gate", "a", null, http);
        assertThat(answer(() -> routes.create("gate", "key-3", body("c", "branch", null), http)).status())
                .as("a refusal is recorded and replayed too").isEqualTo(409);
        assertThat(routes.create("gate", "key-4", body("c", "branch", null), http).getStatusCode().value())
                .isEqualTo(201);
    }

    // ---- read ----

    @Test
    void readingABranchListsWhatItChangedThatTheCallerCanDescribe() {
        UUID dev = id(create("dev", "branch", null, null));
        pointers.put(new PointerKey(staff.id(), dev.toString()), pointer(staff, 2));
        pointers.put(new PointerKey(orders.id(), dev.toString()), pointer(orders, 4));
        create("v1", "tag", null, null);

        JsonNode changed = json(routes.read("gate", "dev", http)).get("changed");

        assertThat(changed).hasSize(2);
        JsonNode table = changed.valueStream().filter(item -> item.get("name").asString().equals("Staff"))
                .findFirst().orElseThrow();
        assertThat(table.get("namespace").valueStream().map(JsonNode::asString).toList()).containsExactly("hr");
        assertThat(table.get("kind").asString()).isEqualTo("table");
        assertThat(table.get("cut-version").asLong()).isEqualTo(1);
        assertThat(table.get("version").asLong()).isEqualTo(2);
        caller = HAL;
        assertThat(json(routes.read("gate", "dev", http)).get("changed").valueStream()
                .map(item -> item.get("name").asString()).toList()).as("hal describes hr only")
                .containsExactly("Staff");
        assertThat(json(routes.read("gate", "v1", http)).has("changed")).isFalse();
        assertThat(denials()).as("a table left out is not a refusal").isEmpty();
    }

    // ---- the maintenance worker ----

    @Test
    void onlyTheWorkerActingAsItselfWarnsAndEnds() {
        String dev = id(create("dev", "branch", null, null)).toString();
        for (Caller other : List.of(BEA, HAL, WORKER_FOR_BEA)) {
            caller = other;
            assertThat(answer(() -> routes.warn(dev, http)).status()).as("warn by %s", other).isEqualTo(403);
            assertThat(answer(() -> routes.end(dev, http)).status()).as("end by %s", other).isEqualTo(403);
            assertThat(answer(() -> maintenance.work("refs", null, null, http)).status()).isEqualTo(403);
        }

        assertThat(denials()).extracting(Denial::reason).allMatch(reason -> reason.startsWith("not_maintenance:"));
        assertThat(denials()).filteredOn(denial -> denial.operation().equals("warnRefExpiry"))
                .extracting(denial -> denial.securable().catalog()).containsOnly("gate");
        assertThat(notices).isEmpty();
        assertThat(refs).hasSize(1);
    }

    @Test
    void aBranchIsWarnedOfOnceForEachExpiry() {
        String dev = id(create("dev", "branch", null, null)).toString();
        caller = WORKER;
        assertThat(answer(() -> routes.warn(dev, http)).type()).isEqualTo("RefNotExpiringException");

        clock.advance(DAY.multipliedBy(23));
        JsonNode sent = json(routes.warn(dev, http));
        JsonNode again = json(routes.warn(dev, http));

        assertThat(sent.get("sent").asBoolean()).isTrue();
        assertThat(sent.get("expires-at").asString()).isEqualTo(NOW.plus(DAY.multipliedBy(30)).toString());
        assertThat(again.get("sent").asBoolean()).isFalse();
        assertThat(notices).singleElement().satisfies(notice -> {
            RefChange event = (RefChange) notice.data();
            assertThat(event.action()).isEqualTo(RefChange.Action.EXPIRING);
            assertThat(event.name()).isEqualTo("dev");
            assertThat(event.expiresAt()).isEqualTo(NOW.plus(DAY.multipliedBy(30)));
            assertThat(event.catalog().catalog()).isEqualTo("gate");
            assertThat(event.actor().principal()).isEqualTo("nuna-maintenance");
        });

        caller = BEA;
        routes.renew("gate", "dev", null, http);
        caller = WORKER;
        assertThat(answer(() -> routes.warn(dev, http)).status()).as("renewed out of reach").isEqualTo(409);
        clock.advance(DAY.multipliedBy(24));
        assertThat(json(routes.warn(dev, http)).get("sent").asBoolean()).as("its new expiry is warned of")
                .isTrue();
        assertThat(notices).hasSize(2);
    }

    @Test
    void aWarningThatCouldNotBeSentIsSentNextTime() {
        String dev = id(create("dev", "branch", null, null)).toString();
        caller = WORKER;
        clock.advance(DAY.multipliedBy(25));
        noticesFail = new AuditUnavailableException(CatalogEvent.create(URI.create("urn:nuna:test"), NOW,
                events(RefChange.Action.CREATED).getFirst()), new IllegalStateException("Kafka is down"));

        assertThat(answer(() -> routes.warn(dev, http)).status()).isEqualTo(503);
        noticesFail = null;

        assertThat(json(routes.warn(dev, http)).get("sent").asBoolean()).isTrue();
        assertThat(notices).hasSize(1);
    }

    @Test
    void theWorkerEndsOnlyABranchPastItsExpiry() {
        UUID dev = id(create("dev", "branch", null, null));
        String tag = id(create("v1", "tag", null, null)).toString();
        caller = WORKER;

        assertThat(answer(() -> routes.end(tag, http)).type()).isEqualTo("RefNotExpiredException");
        assertThat(answer(() -> routes.end(dev.toString(), http)).type()).isEqualTo("RefNotExpiredException");
        clock.advance(DAY.multipliedBy(30));

        assertThat(routes.end(dev.toString(), http).getStatusCode().value()).isEqualTo(204);

        assertThat(refs).doesNotContainKey(dev);
        assertThat(events(RefChange.Action.EXPIRED)).singleElement().satisfies(event -> {
            assertThat(event.ref()).isEqualTo(dev);
            assertThat(event.actor().principal()).isEqualTo("nuna-maintenance");
        });
        assertThat(answer(() -> routes.end(dev.toString(), http)).status()).isEqualTo(404);
        assertThat(answer(() -> routes.end("not-an-id", http)).status()).isEqualTo(400);
    }

    @Test
    void theWorkListNamesTheBranchesToWarnOfAndToEnd() {
        UUID old = id(create("old", "branch", null, null));
        clock.advance(DAY.multipliedBy(8));
        UUID dev = id(create("dev", "branch", null, null));
        create("v1", "tag", null, null);
        caller = WORKER;
        clock.advance(DAY.multipliedBy(23));

        JsonNode work = json(maintenance.work("refs", null, null, http));

        assertThat(work.get("refs").valueStream().map(item -> item.get("id").asString() + " "
                + item.get("action").asString())).containsExactly(dev + " warn", old + " end");
        JsonNode item = work.get("refs").get(0);
        assertThat(item.get("catalog").asString()).isEqualTo("gate");
        assertThat(item.get("name").asString()).isEqualTo("dev");
        assertThat(item.get("expires-at").asString()).isEqualTo(NOW.plus(DAY.multipliedBy(38)).toString());
        assertThat(work.has("next-page-token")).isFalse();

        routes.warn(dev.toString(), http);
        routes.end(old.toString(), http);

        assertThat(json(maintenance.work("refs", null, null, http)).get("refs")).as("each done once").isEmpty();
    }

    @Test
    void theWorkListWalksTheRefsAPageAtATime() {
        create("a", "branch", null, null);
        create("b", "branch", null, null);
        caller = WORKER;
        clock.advance(DAY.multipliedBy(31));

        JsonNode first = json(maintenance.work("refs", null, 1, http));
        JsonNode second = json(maintenance.work("refs", first.get("next-page-token").asString(), 1, http));

        assertThat(first.get("refs").get(0).get("name").asString()).isEqualTo("a");
        assertThat(second.get("refs").get(0).get("name").asString()).isEqualTo("b");
        assertThat(second.has("next-page-token")).isFalse();
        JsonNode all = json(maintenance.work(null, PageToken.encode("REFS", ""), null, http));
        assertThat(all.get("refs")).as("the section walked to from deletions").hasSize(2);
        assertThat(answer(() -> maintenance.work("refs", PageToken.encode("REFS", PageToken.encode("other", "")),
                null, http)).status()).isEqualTo(400);
    }

    // ---- the estate ----

    private NamespaceRecord namespace(String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
                new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private CatalogObject object(NamespaceRecord namespace, String name) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), CatalogObject.Kind.TABLE, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    private static Pointer pointer(CatalogObject object, long version) {
        return new Pointer(object.locationRoot() + "/metadata/v" + version + ".metadata.json", version, "sha-" + version,
                new byte[0]);
    }

    // ---- the store: refs and their cuts as RefSuite has them, keys as IdempotencySuite has them ----

    private record Key(String principal, String hash, UUID changeId, Instant leaseUntil, Instant expiresAt,
                       IdempotencyResult result) {
    }

    private void write(Change change) {
        change.idempotency().ifPresent(result -> {
            Key key = keys.get(result.key());
            assertThat(key).as("a change completes only its own claim").isNotNull();
            assertThat(key.changeId()).isEqualTo(change.id());
            keys.put(result.key(), new Key(key.principal(), key.hash(), key.changeId(), key.leaseUntil(),
                    key.expiresAt(), result));
        });
        for (OutboxEvent event : change.outbox()) {
            outbox.add(EventCodec.decode(event.payloadJson().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private WriteResult createRef(Ref ref, RefLimits limits, Change change) {
        if (!ref.catalogId().equals(catalog.id())) {
            return new WriteResult.Missing(WriteResult.Subject.CATALOG);
        }
        Optional<Ref> holder = refs.values().stream()
                .filter(other -> other.name().normalized().equals(ref.name().normalized())).findFirst();
        if (holder.isPresent()) {
            return new WriteResult.NameTaken(holder.get().id());
        }
        Optional<Ref> from = ref.fromRef().map(refs::get);
        if (ref.fromRef().isPresent() && (from.isEmpty() || from.get().expiredAt(change.at()))) {
            return new WriteResult.Missing(WriteResult.Subject.REF);
        }
        long held = refs.values().stream().filter(other -> other.kind() == ref.kind() && !other.expiredAt(change.at()))
                .count();
        if (!limits.admitsAnother(ref.kind(), held)) {
            return new WriteResult.Refused(WriteResult.Reason.REF_LIMIT);
        }
        List<RefEntry> cut = new ArrayList<>();
        if (from.isPresent()) {
            for (RefEntry entry : cuts.get(from.get().id())) {
                Optional<RefEntry.At> at = entry.pointer().map(kept -> Optional.ofNullable(
                        pointers.get(from.get().key(entry.id()))).map(own -> new RefEntry.At(own.version(),
                        own.metadataLocation(), own.sha256())).orElse(kept));
                cut.add(new RefEntry(ref.id(), entry.id(), entry.kind(), entry.parentId(), entry.name(), at));
            }
        } else {
            namespaces.values().forEach(ns -> cut.add(new RefEntry(ref.id(), ns.id(), RefEntry.Kind.NAMESPACE,
                    ns.parentId(), ns.name(), Optional.empty())));
            objects.values().forEach(o -> cut.add(new RefEntry(ref.id(), o.id(), RefEntry.of(o.kind()),
                    o.namespaceId(), o.name(), Optional.of(new RefEntry.At(1, pointer(o, 1).metadataLocation(),
                    "sha-1")))));
        }
        refs.put(ref.id(), ref);
        cuts.put(ref.id(), cut);
        write(change);
        return WriteResult.APPLIED;
    }

    private WriteResult renewRef(UUID id, Instant expiresAt, Change change) {
        Ref ref = refs.get(id);
        if (ref == null || ref.expiredAt(change.at())) {
            return new WriteResult.Missing(WriteResult.Subject.REF);
        }
        if (ref.kind() == Ref.Kind.TAG) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_A_BRANCH);
        }
        if (ref.expiresAt().get().equals(expiresAt)) {
            return WriteResult.UNCHANGED;
        }
        refs.put(id, new Ref(ref.catalogId(), id, ref.name(), ref.kind(), ref.createdAt(), ref.createdBy(),
                ref.fromRef(), ref.fromName(), Optional.of(expiresAt)));
        write(change);
        return WriteResult.APPLIED;
    }

    private WriteResult deleteRef(UUID id, Change change) {
        if (refs.remove(id) == null) {
            return new WriteResult.Missing(WriteResult.Subject.REF);
        }
        cuts.remove(id);
        pointers.keySet().removeIf(key -> key.branch().equals(id.toString()));
        write(change);
        return WriteResult.APPLIED;
    }

    private Page<Ref> listRefs(UUID catalogId, PageRequest page) {
        String after = page.token().map(token -> PageToken.decode(token, 1).getFirst()).orElse("");
        List<Ref> all = refs.values().stream().filter(ref -> ref.catalogId().equals(catalogId))
                .filter(ref -> ref.name().normalized().compareTo(after) > 0)
                .sorted(Comparator.comparing(ref -> ref.name().normalized())).toList();
        List<Ref> items = all.stream().limit(page.size()).toList();
        return new Page<>(items, all.size() > page.size()
                ? Optional.of(PageToken.encode(items.getLast().name().normalized())) : Optional.empty());
    }

    private Page<RefEntry> entries(UUID refId, UUID parentId, RefEntry.Kind kind) {
        return new Page<>(cuts.getOrDefault(refId, List.of()).stream()
                .filter(entry -> entry.parentId().equals(parentId) && entry.kind() == kind).toList(), Optional.empty());
    }

    private KeyState claimKey(IdempotencyClaim claim) {
        Key key = keys.get(claim.key());
        if (key != null && key.expiresAt().isAfter(claim.now())) {
            if (!key.principal().equals(claim.principal()) || !key.hash().equals(claim.requestHash())) {
                return new KeyState.Mismatch();
            }
            if (key.result() != null) {
                return new KeyState.Done(key.result());
            }
            if (key.leaseUntil().isAfter(claim.now())) {
                return new KeyState.InFlight(key.changeId(), key.leaseUntil());
            }
        }
        keys.put(claim.key(), new Key(claim.principal(), claim.requestHash(), claim.changeId(), claim.leaseUntil(),
                claim.expiresAt(), null));
        return new KeyState.Claimed();
    }

    private KeyState readKey(String name, Instant now) {
        Key key = keys.get(name);
        if (key == null || !key.expiresAt().isAfter(now)) {
            return new KeyState.Absent();
        }
        return key.result() != null ? new KeyState.Done(key.result())
                : key.leaseUntil().isAfter(now) ? new KeyState.InFlight(key.changeId(), key.leaseUntil())
                : new KeyState.Absent();
    }

    private WriteResult completeKey(IdempotencyResult result, UUID changeId) {
        Key key = keys.get(result.key());
        if (key == null || !key.changeId().equals(changeId) || key.result() != null) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        keys.put(result.key(), new Key(key.principal(), key.hash(), changeId, key.leaseUntil(), key.expiresAt(),
                result));
        return WriteResult.APPLIED;
    }

    private WriteResult releaseKey(String name, UUID changeId) {
        Key key = keys.get(name);
        if (key == null || !key.changeId().equals(changeId) || key.result() != null) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        keys.remove(name);
        return WriteResult.APPLIED;
    }

    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
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
                    case "createRef" -> createRef((Ref) args[0], (RefLimits) args[1], (Change) args[2]);
                    case "readRef" -> Optional.ofNullable(refs.get((UUID) args[0]));
                    case "findRef" -> refs.values().stream().filter(ref -> ref.catalogId().equals(args[0])
                            && ref.name().normalized().equals(args[1])).findFirst();
                    case "listRefs" -> listRefs((UUID) args[0], (PageRequest) args[1]);
                    case "renewRef" -> renewRef((UUID) args[0], (Instant) args[1], (Change) args[2]);
                    case "deleteRef" -> deleteRef((UUID) args[0], (Change) args[1]);
                    case "listRefNamespaces" -> entries((UUID) args[0], (UUID) args[1], RefEntry.Kind.NAMESPACE);
                    case "listRefObjects" -> entries((UUID) args[0], (UUID) args[1],
                            RefEntry.of((CatalogObject.Kind) args[2]));
                    case "readPointer" -> Optional.ofNullable(pointers.get((PointerKey) args[0]));
                    case "claimKey" -> claimKey((IdempotencyClaim) args[0]);
                    case "readKey" -> readKey((String) args[0], (Instant) args[1]);
                    case "awaitKey" -> readKey((String) args[0], clock.instant());
                    case "completeKey" -> completeKey((IdempotencyResult) args[0], (UUID) args[1]);
                    case "releaseKey" -> releaseKey((String) args[0], (UUID) args[1]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // ---- helpers ----

    private JsonNode create(String name, String kind, String from, String key) {
        ResponseEntity<String> response = routes.create("gate", key, body(name, kind, from), http);
        assertThat(response.getStatusCode().value()).as("%s: %s", name, response.getBody()).isEqualTo(201);
        return Management.JSON.readTree(response.getBody());
    }

    private static String body(String name, String kind, String from) {
        return "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"" + (from == null ? "" : ",\"from\":\"" + from
                + "\"") + "}";
    }

    private static UUID id(JsonNode ref) {
        return UUID.fromString(ref.get("id").asString());
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    /** What a call answered: a response, a refusal thrown before the route ran, or an error the handler answers. */
    private record Answer(int status, String type, String message) {
    }

    private static Answer answer(Callable<ResponseEntity<String>> call) {
        try {
            ResponseEntity<String> response = call.call();
            JsonNode error = response.getBody() == null || response.getBody().isEmpty() ? null
                    : Management.JSON.readTree(response.getBody()).get("error");
            return new Answer(response.getStatusCode().value(), error == null ? null : error.get("type").asString(),
                    error == null ? null : error.get("message").asString());
        } catch (CatalogError e) {
            return new Answer(e.status(), e.type(), e.getMessage());
        } catch (AccessDenied e) {
            return new Answer(403, "ForbiddenException", e.getMessage());
        } catch (AuditUnavailableException e) {
            // AuditUnavailableResponses answers it.
            return new Answer(503, "ServiceUnavailableException", e.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private List<RefChange> events(RefChange.Action action) {
        return outbox.stream().map(CatalogEvent::data).filter(RefChange.class::isInstance).map(RefChange.class::cast)
                .filter(event -> event.action() == action).toList();
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
