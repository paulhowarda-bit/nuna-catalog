package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
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
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /management/v1/catalogs/{catalog}/properties} over a store double (item 131): the catalog's boundary and
 * where its tables live, with nothing secret and no identity in it, to anyone who holds something in the catalog; any
 * other caller is answered as for a catalog that does not exist, and the refusal is audited (D18). Needs only Java;
 * {@code ManagementAuthorizationTest#readingACatalogsPropertiesNeedsSomethingInIt} checks the route on the wire.
 */
class CatalogPropertiesTest {

    private static final String ACCESS_KEY = "AKIATESTACCESSKEY0001";
    private static final String SECRET_KEY = "test-secret-access-key-never-answered";
    private static final String STS_ROLE = "arn:aws:iam::123456789012:role/nuna-vending";
    private static final String MAINTENANCE = "arn:aws:iam::123456789012:role/nuna-maintenance";
    private static final String KMS_KEY = "arn:aws:kms:eu-west-2:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final Path CA_BUNDLE = Path.of("/etc/nuna/private-ca.pem");

    /** Select on one table in the catalog: something in it. */
    private static final Caller TIA = person("tia", "traders");
    /** Nothing anywhere in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    private static final Caller GINA = person("gina", "grant-admins");
    private static final Caller OLGA = person("olga", "platform-ops");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "trading", "Trading");
    private final UUID profileId = UUID.randomUUID();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final CatalogProperties routes;
    private Caller caller = TIA;

    CatalogPropertiesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grants.add(new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.TABLE, UUID.randomUUID()),
                "traders", "select"), "test", Instant.EPOCH));
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Holdings holdings = new Holdings(null, store, settings, separation,
                DenialsForTests.denials(audit::add, settings, clock));
        Management management = new Management(store, request -> caller, settings, holdings);
        StorageSettings.Profile profile = new StorageSettings.Profile(StoreKind.S3,
                "https://s3.eu-west-2.amazonaws.com", "eu-west-2", "s3://warehouse/trading/tables",
                List.of("s3://warehouse/trading"), STS_ROLE, ACCESS_KEY, SECRET_KEY, MAINTENANCE, CA_BUNDLE, KMS_KEY);
        StorageSettings storage = new StorageSettings(Map.of("warehouse", profile),
                Map.of("Trading", new StorageSettings.Catalog("warehouse", "trading-keys", "Nuna Markets Ltd")),
                Duration.ofMinutes(30), 10, true);
        routes = new CatalogProperties(store, management, storage);
    }

    @Test
    void aCallerWhoHoldsSomethingInTheCatalogReadsItsProperties() {
        JsonNode body = read("trading");
        assertThat(body).isEqualTo(JsonMapper.builder().build().readTree("""
                {"name": "Trading", "kms-key-set": "trading-keys", "legal-entity": "Nuna Markets Ltd",
                 "storage-profile": {"name": "warehouse", "kind": "S3", "endpoint": "https://s3.eu-west-2.amazonaws.com",
                   "region": "eu-west-2", "table-root": "s3://warehouse/trading/tables",
                   "allowed-prefixes": ["s3://warehouse/trading"], "encryption": "sse-kms"}}
                """));
        assertThat(audit).isEmpty();
    }

    @Test
    void nothingSecretAndNoIdentityIsAnswered() {
        String body = read("trading").toString();
        for (String kept : List.of(ACCESS_KEY, SECRET_KEY, STS_ROLE, MAINTENANCE, KMS_KEY, CA_BUNDLE.toString(),
                "123456789012")) {
            assertThat(body).doesNotContain(kept);
        }
    }

    @Test
    void grantAdminsAndPlatformOperatorsReadEveryCatalogsProperties() {
        for (Caller each : List.of(GINA, OLGA)) {
            caller = each;
            assertThat(read("TRADING").get("name").asString()).isEqualTo("Trading");
        }
    }

    @Test
    void aCallerWhoHoldsNothingInTheCatalogIsAnsweredAsForOneThatDoesNotExist() {
        caller = NED;
        CatalogError hidden = refusal("trading");
        CatalogError missing = refusal("nowhere");
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(List.of(hidden.type(), hidden.getMessage().replace("trading", "nowhere")))
                .isEqualTo(List.of(missing.type(), missing.getMessage()));
        assertThat(hidden.type()).isEqualTo("NoSuchWarehouseException");

        List<Denial> denials = audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance)
                .map(Denial.class::cast).toList();
        assertThat(denials).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("readCatalogProperties");
            assertThat(denial.securable().id()).isEqualTo(catalog.id());
            assertThat(denial.actor().principal()).isEqualTo("ned");
        });
    }

    // ---- helpers ----

    private JsonNode read(String name) {
        var response = routes.read(name, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.getBody());
    }

    private CatalogError refusal(String name) {
        Throwable thrown = catchThrowable(() -> routes.read(name, http));
        assertThat(thrown).as("the answer to %s for %s", caller.principal().id(), name)
                .isInstanceOf(CatalogError.class);
        return (CatalogError) thrown;
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "nuna-console", false, Optional.empty());
    }

    /** The catalog, its boundary and its profile as the store keeps them, and its grants; any other call fails. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "catalogSettings" -> Optional.of(new CatalogSettings(profileId, "trading-keys",
                            "Nuna Markets Ltd")).filter(s -> catalog.id().equals(args[0]));
                    case "readStorageProfile" -> Optional.of(new StorageProfile(profileId, "warehouse",
                            StorageProfile.Kind.S3, Optional.empty(), STS_ROLE, List.of("s3://warehouse/trading"),
                            Optional.of(KMS_KEY))).filter(p -> profileId.equals(args[0]));
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
