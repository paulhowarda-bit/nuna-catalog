package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.security.SecuritySettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WorkerStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A scan that could not read its storage prefixes (item 275), as when the worker could not get a token just after a
 * restart, reports no storage: readiness then gives the storage and store steps {@code unknown}, as before any scan,
 * not {@code missing}, and storage setup calls the worker's identity {@code unknown} and gives no worker settings.
 * A scan that read its prefixes and reached none is still {@code missing}. The routes and guard are the real ones over
 * a stand-in store, so this needs only Java.
 */
class ReadinessUnreadPrefixesTest {

    private static final Instant EARLIER = Instant.parse("2026-11-25T18:55:00Z");
    private static final Instant LATER = Instant.parse("2026-11-25T19:05:49Z");
    private static final String PREFIX = "s3://warehouse/tables";
    private static final String ENDPOINT = "http://127.0.0.1:9";
    private static final Caller OLGA = new Caller(new Principal("olga", Set.of("platform-ops")), "console", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "lakehouse", "lakehouse");
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final CatalogStorage storage;
    private final ReadinessRoutes readiness;
    private final StorageSetupRoutes setup;
    private List<MaintenanceScan> scans = new ArrayList<>();

    ReadinessUnreadPrefixesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        AtomicReference<UUID> profile = new AtomicReference<>();
        List<GrantRecord> grants = List.of(new GrantRecord(new Grant(catalog.id(),
                new Securable(Securable.Kind.CATALOG, catalog.id()), "nuna-maintenance", Privilege.WRITE.wireName()),
                "test", Instant.EPOCH));
        CatalogStore store = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "listScans" -> scans;
                    case "readStorageProfile" -> Optional.empty();
                    case "putStorageProfile" -> {
                        profile.set(((StorageProfile) args[0]).id());
                        yield null;
                    }
                    case "catalogSettings" -> Optional.of(new CatalogSettings(profile.get(), "default", "nuna"));
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<?>) args[0]).contains(g.grant().securable())
                                    && ((Set<?>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(LATER, ZoneOffset.UTC);
        AuditPublisher publisher = event -> { };
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> OLGA, settings, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        // One MinIO profile, protected for the worker, whose endpoint nothing contacts.
        StorageSettings storageSettings = new StorageSettings(Map.of("lake", new StorageSettings.Profile(
                StoreKind.MINIO, ENDPOINT, "us-east-1", PREFIX, List.of(PREFIX), null, "catalog", "catalog-secret",
                "nuna-maintenance", null, null)), Map.of("lakehouse", new StorageSettings.Catalog("lake", "default",
                "nuna")), Duration.ofMinutes(30), 100, false);
        storage = new CatalogStorage(storageSettings, store, settings, clock, ObservationRegistry.NOOP);
        GovernanceSettings governance = new GovernanceSettings(10, 60, Map.of(), Duration.ofDays(7),
                Duration.ofHours(1), Map.of());
        readiness = new ReadinessRoutes(store, management, guard, storage, governance);
        setup = new StorageSetupRoutes(storageSettings, store, management, guard);
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    @Test
    void aScanThatCouldNotReadItsPrefixesLeavesTheStorageAndStoreStepsUnknown() {
        scans = List.of(unread(LATER), reached(EARLIER));

        JsonNode body = readiness();

        assertThat(body.get("storage").get("state").asString()).as("not missing").isEqualTo("unknown");
        assertThat(body.get("store").get("state").asString()).isEqualTo("unknown");
        assertThat(body.has("reported-at")).as("as before any scan: the console says the worker has not reported")
                .isFalse();
        assertThat(body.get("store").has("code")).isFalse();
        assertThat(body.get("store").has("deletes-since")).as("nothing of the scan before").isFalse();
        assertThat(body.get("store").get("prefixes").get(0).propertyNames()).containsExactly("prefix");
        assertThat(body.get("storage").get("prefixes").get(0).has("covered-by")).isFalse();
    }

    @Test
    void aScanThatReadItsPrefixesAndReachedNoneIsMissingAndTheNextThatReachesThemDone() {
        scans = List.of(scan(LATER, Optional.of(List.of())), reached(EARLIER));

        JsonNode none = readiness();

        assertThat(none.get("storage").get("state").asString()).isEqualTo("missing");
        assertThat(none.get("store").get("state").asString()).isEqualTo("unknown");
        assertThat(none.get("reported-at").asString()).isEqualTo(LATER.plusSeconds(30).toString());

        scans = List.of(reached(LATER), unread(EARLIER));

        JsonNode reached = readiness();

        assertThat(List.of(reached.get("grant").get("state").asString(), reached.get("storage").get("state")
                .asString(), reached.get("store").get("state").asString())).containsExactly("done", "done", "done");
    }

    @Test
    void storageSetupGivesNoWorkerSettingsAfterAScanThatCouldNotReadItsPrefixes() {
        scans = List.of(unread(LATER), reached(EARLIER));

        JsonNode lake = setup().get("profiles").get(0);

        assertThat(lake.get("worker").propertyNames()).containsExactly("state");
        assertThat(lake.get("worker").get("state").asString()).as("not not-reported").isEqualTo("unknown");
        assertThat(lake.has("worker-settings")).isFalse();

        scans = List.of(scan(LATER, Optional.of(List.of())), reached(EARLIER));

        JsonNode none = setup().get("profiles").get(0);

        assertThat(none.get("worker").get("state").asString()).as("read, and no identity on the store")
                .isEqualTo("not-reported");
        assertThat(none.has("worker-settings")).isTrue();
    }

    private JsonNode readiness() {
        return Management.JSON.readTree(readiness.readiness("lakehouse", http).getBody());
    }

    private JsonNode setup() {
        return Management.JSON.readTree(setup.storageSetup(http).getBody());
    }

    /** A scan whose read of the storage prefixes failed, as the worker reports it: no storage at all. */
    private static MaintenanceScan unread(Instant started) {
        MaintenanceScan scan = scan(started, Optional.empty());
        return new MaintenanceScan(scan.id(), scan.worker(), scan.startedAt(), scan.endedAt(), scan.outcome(),
                Map.of("storage-prefixes", 1), scan.error(), scan.groups(), scan.storage());
    }

    /** A scan whose worker listed and deleted beneath the catalog's prefix on its store. */
    private static MaintenanceScan reached(Instant started) {
        return scan(started, Optional.of(List.of(new WorkerStorage("minio", Optional.of(ENDPOINT), List.of(
                new WorkerStorage.Prefix(PREFIX, WorkerStorage.Listed.OK, Optional.empty(), 2, 0, 0,
                        Optional.empty()))))));
    }

    private static MaintenanceScan scan(Instant started, Optional<List<WorkerStorage>> storage) {
        return new MaintenanceScan(UUID.randomUUID(), "nuna-maintenance", started,
                Optional.of(started.plusSeconds(30)), Optional.of(MaintenanceScan.Outcome.SUCCEEDED), Map.of(),
                Optional.empty(), Optional.of(new TreeSet<>(Set.of("nuna-maintenance"))), storage);
    }
}
