package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
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
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Authorizer;
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
import nuna.catalog.store.CatalogStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The storage prefixes the maintenance worker reads at each scan (item 256; D30, amended 2026-11-24): only a
 * maintenance principal acting as itself reads them, every other caller is refused with an audited denial (D18), and
 * they hold the prefixes of the profiles that name a {@code maintenance-principal} and nothing else of a profile, no
 * credential above all. The route and guard are the real ones over the profiles as configured, so this needs only Java.
 */
class StoragePrefixRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of()), "worker", false,
            Optional.empty());
    private static final Caller WORKER_FOR_ANN = new Caller(new Principal("nuna-maintenance", Set.of()), "worker",
            true, Optional.of(new Principal("ann", Set.of("analysts"))));
    private static final Caller OLGA = person("olga", "platform-ops");
    private static final Caller ANN = person("ann", "analysts");

    /** Every secret a profile can hold, none of which may reach the worker. */
    private static final List<String> SECRETS = List.of("catalog-key-id", "catalog-secret-key",
            "arn:aws:iam::123456789012:role/nuna-vending", "/etc/nuna/storage-ca/ceph.crt",
            "arn:aws:kms:us-east-1:123456789012:key/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");

    private final List<CatalogEvent> audit = new ArrayList<>();
    private final StoragePrefixRoutes routes;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = WORKER;

    StoragePrefixRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        Map<String, StorageSettings.Profile> profiles = new LinkedHashMap<>();
        profiles.put("lake", new StorageSettings.Profile(StoreKind.S3, "https://s3.us-east-1.amazonaws.com",
                "us-east-1", "s3://lake/tables", List.of("s3://lake/tables", "s3://lake-archive/tables"),
                "arn:aws:iam::123456789012:role/nuna-vending", null, null,
                "arn:aws:iam::123456789012:role/nuna-maintenance", null,
                "arn:aws:kms:us-east-1:123456789012:key/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00"));
        profiles.put("ceph", new StorageSettings.Profile(StoreKind.CEPH, "https://rgw.internal:7480", "us-east-1",
                "s3://ledger/tables", List.of("s3://ledger/tables"), "arn:aws:iam:::role/nuna-vending",
                "catalog-key-id", "catalog-secret-key", "arn:aws:iam:::user/nuna-maintenance",
                Path.of("/etc/nuna/storage-ca/ceph.crt"), null));
        profiles.put("scratch", new StorageSettings.Profile(StoreKind.MINIO, "http://silo:9000", "us-east-1",
                "s3://scratch/tables", List.of("s3://scratch/tables"), null, "catalog-key-id", "catalog-secret-key",
                null, null, null));
        StorageSettings storage = new StorageSettings(profiles, Map.of(), Duration.ofMinutes(30), 10_000, true);
        CatalogStore store = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "the test's store";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> caller, settings, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        routes = new StoragePrefixRoutes(storage, management, guard);
    }

    @Test
    void onlyTheWorkerActingAsItselfReadsThePrefixes() {
        for (Caller other : List.of(ANN, OLGA, WORKER_FOR_ANN)) {
            caller = other;
            assertThatThrownBy(() -> routes.prefixes(http)).as("the prefixes for %s", other)
                    .isInstanceOf(AccessDenied.class);
        }

        assertThat(denials()).extracting(Denial::operation).containsExactly("listMaintenanceStoragePrefixes",
                "listMaintenanceStoragePrefixes", "listMaintenanceStoragePrefixes");
        assertThat(denials()).extracting(Denial::reason).allMatch(reason -> reason.startsWith("not_maintenance:"));

        caller = WORKER;

        assertThat(routes.prefixes(http).getStatusCode().value()).isEqualTo(200);
        assertThat(denials()).hasSize(3);
    }

    @Test
    void onlyProfilesNamingAMaintenancePrincipalAreListedWithNoCredential() {
        ResponseEntity<String> response = routes.prefixes(http);
        JsonNode profiles = Management.JSON.readTree(response.getBody()).get("profiles");

        assertThat(profiles.valueStream().map(profile -> profile.get("name").asString()).toList())
                .as("by name, and not scratch, which names no maintenance principal").containsExactly("ceph", "lake");
        JsonNode lake = profiles.get(1);
        assertThat(lake.propertyNames()).containsExactlyInAnyOrder("name", "kind", "endpoint", "prefixes");
        assertThat(lake.get("kind").asString()).isEqualTo("s3");
        assertThat(lake.get("endpoint").asString()).isEqualTo("https://s3.us-east-1.amazonaws.com");
        assertThat(lake.get("prefixes").valueStream().map(JsonNode::asString).toList())
                .containsExactly("s3://lake/tables", "s3://lake-archive/tables");
        JsonNode ceph = profiles.get(0);
        assertThat(ceph.get("kind").asString()).isEqualTo("ceph");
        assertThat(ceph.get("endpoint").asString()).isEqualTo("https://rgw.internal:7480");
        assertThat(ceph.get("prefixes").valueStream().map(JsonNode::asString).toList())
                .containsExactly("s3://ledger/tables");
        assertThat(response.getBody()).as("no key, role, KMS key, CA bundle or maintenance principal")
                .doesNotContain(SECRETS.toArray(String[]::new)).doesNotContain("nuna-maintenance");
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }
}
