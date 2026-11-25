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
import java.util.TreeSet;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.DeletePolicy;
import nuna.catalog.objectstore.ObjectLocation;
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
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.WorkerStorage;
import nuna.catalog.store.WorkerStorage.Listed;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * What an operator sets up outside the catalog so the maintenance worker can maintain each storage profile's catalogs
 * (backlog 253): only platform operators read it, every other caller refused with an audited denial (D18); each profile
 * gives its catalogs and prefixes, the worker's reach on its store from its reports, the worker's settings only for a
 * store the worker's report shows no identity on, and the delete policies with their commands for MinIO, AWS S3 and
 * Ceph, or the setting an unprotected profile lacks; no key or secret anywhere. The route and guard are the real ones
 * over profiles as configured and a stand-in store holding the worker's scans, so this needs only Java.
 */
class StorageSetupRoutesTest {

    private static final Instant EARLIER = Instant.parse("2026-11-24T13:55:00Z");
    private static final Instant LATER = Instant.parse("2026-11-24T14:05:00Z");
    private static final String US = "https://s3.us-east-1.amazonaws.com";
    private static final String LAKE_ROLE = "arn:aws:iam::123456789012:role/nuna-maintenance";
    private static final String AUDIT_ROLE = "arn:aws:iam::123456789012:role/nuna-maintenance-audit";
    private static final Caller OLGA = person("olga", "platform-ops");
    private static final Caller ANN = person("ann", "analysts");
    private static final Caller GRACE = person("grace", "grant-admins");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of()), "worker", false,
            Optional.empty());

    /** Every secret a profile can hold: none may reach the answer. */
    private static final List<String> SECRETS = List.of("catalog-key-id", "catalog-secret-key",
            "arn:aws:iam::123456789012:role/nuna-vending", "/etc/nuna/storage-ca/ceph.crt",
            "arn:aws:kms:us-east-1:123456789012:key/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");

    private final List<CatalogEvent> audit = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final StorageSetupRoutes routes;
    private List<MaintenanceScan> scans = new ArrayList<>();
    private Caller caller = OLGA;

    StorageSetupRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        Map<String, StorageSettings.Profile> profiles = new LinkedHashMap<>();
        // MinIO: the store the worker reaches, and on it a new bucket whose prefix the worker has not read yet.
        profiles.put("s3", minio("http://s3:9000", "s3://warehouse/tables", "nuna-maintenance"));
        profiles.put("ledger", minio("http://s3:9000", "s3://ledger-warehouse/tables", "nuna-maintenance"));
        // MinIO: a store the worker does not reach, whose profile names no maintenance principal.
        profiles.put("scratch", minio("http://silo:9000", "s3://scratch/tables", null));
        // AWS S3: a store the worker reaches, with a bucket two profiles share; and a region it does not reach.
        profiles.put("lake", new StorageSettings.Profile(StoreKind.S3, US, "us-east-1", "s3://lake/tables",
                List.of("s3://lake/tables", "s3://lake-archive/tables"), "arn:aws:iam::123456789012:role/nuna-vending",
                null, null, LAKE_ROLE, null,
                "arn:aws:kms:us-east-1:123456789012:key/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00"));
        profiles.put("audit", new StorageSettings.Profile(StoreKind.S3, US, "us-east-1", "s3://lake/audit",
                List.of("s3://lake/audit"), "arn:aws:iam::123456789012:role/nuna-vending", null, null, AUDIT_ROLE,
                null, null));
        profiles.put("eu", new StorageSettings.Profile(StoreKind.S3, "https://s3.eu-west-1.amazonaws.com",
                "eu-west-1", "s3://lake-eu/tables", List.of("s3://lake-eu/tables"),
                "arn:aws:iam::123456789012:role/nuna-vending", null, null, LAKE_ROLE, null, null));
        // Ceph: a store the worker does not reach, behind a private CA.
        profiles.put("ceph", new StorageSettings.Profile(StoreKind.CEPH, "https://rgw.internal:7480", "us-east-1",
                "s3://ledger/tables", List.of("s3://ledger/tables"), "arn:aws:iam:::role/nuna-vending",
                "catalog-key-id", "catalog-secret-key", "arn:aws:iam:::user/nuna-maintenance",
                Path.of("/etc/nuna/storage-ca/ceph.crt"), null));
        Map<String, StorageSettings.Catalog> catalogs = new LinkedHashMap<>();
        catalogs.put("warehouse", catalog("s3"));
        catalogs.put("ledger", catalog("ledger"));
        catalogs.put("sales", catalog("lake"));
        catalogs.put("Finance", catalog("lake"));
        catalogs.put("ops", catalog("ceph"));
        StorageSettings storage = new StorageSettings(profiles, catalogs, Duration.ofMinutes(30), 10_000, false);
        CatalogStore store = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "listScans" -> scans;
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(LATER, ZoneOffset.UTC);
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
        routes = new StorageSetupRoutes(storage, store, management, guard);

        // The worker's last two scans, the newest first, as the store lists them: the newest still running. Its MinIO
        // store's endpoint is written as its settings write it, which the worker matches to the profiles' however
        // either is spelled.
        scans = List.of(
                scan(LATER, Optional.empty(),
                        new WorkerStorage("minio", Optional.of("HTTP://S3:9000"), List.of(
                                listed("s3://warehouse/tables", 0, 0, null))),
                        new WorkerStorage("s3", Optional.of(US), List.of(
                                listed("s3://lake/tables", 0, 0, null),
                                refused("s3://lake-archive/tables"),
                                listed("s3://lake/audit", 0, 0, null)))),
                scan(EARLIER, Optional.of(LATER.minusSeconds(60)),
                        new WorkerStorage("minio", Optional.of("http://s3:9000"), List.of(
                                listed("s3://warehouse/tables", 12, 0, null))),
                        new WorkerStorage("s3", Optional.of(US), List.of(
                                listed("s3://lake/tables", 3, 1, "AccessDenied"),
                                refused("s3://lake-archive/tables")))));
    }

    @Test
    void onlyPlatformOperatorsReadTheSetupAndEachRefusalIsAudited() {
        for (Caller other : List.of(ANN, GRACE, WORKER)) {
            caller = other;
            assertThatThrownBy(() -> routes.storageSetup(http)).as("the setup for %s", other)
                    .isInstanceOf(AccessDenied.class);
        }

        assertThat(denials()).extracting(Denial::operation).containsOnly("getMaintenanceStorageSetup").hasSize(3);
        assertThat(denials()).extracting(Denial::reason).allMatch(reason -> reason.startsWith("no_privilege"));

        caller = OLGA;

        assertThat(routes.storageSetup(http).getStatusCode().value()).isEqualTo(200);
        assertThat(audit).as("the read is not audited").hasSize(3);
    }

    @Test
    void eachProfileGivesItsCatalogsPrefixesAndWorkerByNameAndNoNamespaceOrTable() {
        JsonNode body = setup();

        assertThat(body.get("reported-at").asString()).as("the newest report, still running")
                .isEqualTo("2026-11-24T14:05:00Z");
        assertThat(names(body.get("profiles"), "name"))
                .containsExactly("audit", "ceph", "eu", "lake", "ledger", "s3", "scratch");
        JsonNode lake = profile(body, "lake");
        assertThat(lake.propertyNames()).containsExactly("name", "catalogs", "kind", "endpoint", "prefixes",
                "maintenance-principal", "worker", "store");
        assertThat(strings(lake.get("catalogs"))).containsExactly("Finance", "sales");
        assertThat(lake.get("kind").asString()).isEqualTo("s3");
        assertThat(lake.get("endpoint").asString()).isEqualTo(US);
        assertThat(strings(lake.get("prefixes"))).containsExactly("s3://lake/tables", "s3://lake-archive/tables");
        assertThat(lake.get("maintenance-principal").asString()).isEqualTo(LAKE_ROLE);
        assertThat(profile(body, "ceph").get("kind").asString()).isEqualTo("ceph");
        assertThat(strings(profile(body, "ceph").get("catalogs"))).containsExactly("ops");
        JsonNode scratch = profile(body, "scratch");
        assertThat(scratch.get("kind").asString()).isEqualTo("minio");
        assertThat(scratch.get("catalogs").isEmpty()).isTrue();
        assertThat(scratch.has("maintenance-principal")).isFalse();
    }

    @Test
    void aBucketOnAStoreTheWorkerReachesNeedsNoWorkerSettings() {
        JsonNode body = setup();

        JsonNode ledger = profile(body, "ledger");
        assertThat(ledger.has("worker-settings")).as("a new bucket on the worker's MinIO store").isFalse();
        assertThat(ledger.get("worker").get("state").asString()).isEqualTo("reported");
        assertThat(ledger.get("worker").get("prefixes").get(0).propertyNames())
                .as("the worker has not read its prefix yet: it lists it from its next scan").containsExactly("prefix");
        JsonNode s3 = profile(body, "s3");
        assertThat(s3.has("worker-settings")).isFalse();
        assertThat(s3.get("worker").get("prefixes").get(0).get("listed").asString()).isEqualTo("ok");
        assertThat(profile(body, "lake").has("worker-settings")).isFalse();
        assertThat(profile(body, "audit").has("worker-settings")).isFalse();
    }

    @Test
    void theWorkersReachIsTheListingOfItsNewestReportAndTheDeletesOfItsNewestEndedOne() {
        JsonNode prefixes = profile(setup(), "lake").get("worker").get("prefixes");

        assertThat(Management.JSON.writeValueAsString(prefixes)).isEqualTo("[{\"prefix\":\"s3://lake/tables\","
                + "\"listed\":\"ok\",\"deleted\":3,\"delete-refused\":1,\"delete-failed\":0,\"delete-code\":"
                + "\"AccessDenied\"},{\"prefix\":\"s3://lake-archive/tables\",\"listed\":\"refused\",\"list-code\":"
                + "\"AccessDenied\",\"deleted\":0,\"delete-refused\":0,\"delete-failed\":0}]");
    }

    @Test
    void aStoreTheWorkerDoesNotReachGetsItsSettingsWithTheKeysPlaceMarked() {
        JsonNode body = setup();

        JsonNode ceph = profile(body, "ceph");
        assertThat(ceph.get("worker").propertyNames()).containsExactly("state");
        assertThat(ceph.get("worker").get("state").asString()).isEqualTo("not-reported");
        JsonNode settings = ceph.get("worker-settings");
        assertThat(settings.propertyNames()).containsExactly("store", "yaml", "secret");
        assertThat(settings.get("store").asString()).isEqualTo("ceph");
        assertThat(settings.get("yaml").asString()).isEqualTo("""
                ceph:
                  kind: ceph
                  endpoint: https://rgw.internal:7480
                  region: us-east-1
                  access-key-id: <the worker's access key on this store>
                  ca-bundle: <the store's CA bundle in the worker's pod, from storageCaConfigMap>
                """);
        assertThat(settings.get("secret").asString()).isEqualTo("nuna.maintenance.storage.ceph.secret-access-key");

        JsonNode eu = profile(body, "eu").get("worker-settings");
        assertThat(eu.get("yaml").asString()).as("another AWS region is another store").isEqualTo("""
                eu:
                  kind: s3
                  endpoint: https://s3.eu-west-1.amazonaws.com
                  region: eu-west-1
                  access-key-id: <the worker's access key on this store>
                """);
        assertThat(eu.get("note").asString()).contains("IRSA or Pod Identity", "leave access-key-id out");

        JsonNode scratch = profile(body, "scratch").get("worker-settings");
        assertThat(scratch.get("yaml").asString())
                .startsWith("scratch:\n  kind: minio\n  endpoint: http://silo:9000\n");
        assertThat(scratch.has("note")).isFalse();
    }

    @Test
    void beforeAnyReportOrWithAnotherKindAtTheEndpointEveryStoreNeedsItsSettings() {
        scans = List.of();
        JsonNode body = setup();

        assertThat(body.has("reported-at")).isFalse();
        for (JsonNode profile : body.get("profiles")) {
            assertThat(profile.get("worker").get("state").asString()).isEqualTo("not-reported");
            assertThat(profile.has("worker-settings")).as(profile.get("name").asString()).isTrue();
        }

        scans = List.of(scan(LATER, Optional.empty(), new WorkerStorage("ceph", Optional.of("http://s3:9000"),
                List.of(listed("s3://warehouse/tables", 0, 0, null)))));

        assertThat(profile(setup(), "s3").has("worker-settings")).as("a Ceph identity serves no MinIO profile")
                .isTrue();
    }

    @Test
    void minioPoliciesComeWithMcCommandsNamedForTheProfile() {
        JsonNode store = profile(setup(), "ledger").get("store");

        assertThat(store.get("state").asString()).isEqualTo("generated");
        JsonNode except = store.get("policies").get(0);
        assertThat(except.propertyNames()).containsExactly("apply-as", "except", "name", "file", "policy", "commands");
        assertThat(except.get("apply-as").asString()).isEqualTo("user-policy");
        assertThat(except.get("except").asString()).isEqualTo("nuna-maintenance");
        assertThat(except.get("policy").asString())
                .isEqualTo(DeletePolicy.userPolicy(List.of(ObjectLocation.parse("s3://ledger-warehouse/tables"))));
        assertThat(strings(except.get("commands"))).containsExactly(
                "mc admin policy create <alias> nuna-no-delete-ledger nuna-no-delete-ledger.json",
                "mc admin policy attach <alias> nuna-no-delete-ledger --user <user>");
        JsonNode to = store.get("policies").get(1);
        assertThat(to.get("to").asString()).isEqualTo("nuna-maintenance");
        assertThat(to.get("file").asString()).isEqualTo("nuna-worker-ledger.json");
        assertThat(to.get("policy").asString())
                .isEqualTo(DeletePolicy.workerPolicy(List.of(ObjectLocation.parse("s3://ledger-warehouse/tables"))));
        assertThat(strings(to.get("commands"))).containsExactly(
                "mc admin policy create <alias> nuna-worker-ledger nuna-worker-ledger.json",
                "mc admin policy attach <alias> nuna-worker-ledger --user nuna-maintenance");
        assertThat(store.get("policies")).hasSize(2);
        assertThat(names(profile(setup(), "s3").get("store").get("policies"), "name"))
                .as("another profile on the store keeps its own policies").containsExactly("nuna-no-delete-s3",
                        "nuna-worker-s3");
    }

    @Test
    void awsAndCephPoliciesComeWithPutBucketPolicyOneABucket() {
        JsonNode body = setup();

        JsonNode lake = profile(body, "lake").get("store").get("policies");
        assertThat(names(lake, "bucket")).containsExactly("lake", "lake-archive");
        JsonNode shared = lake.get(0);
        assertThat(shared.propertyNames()).containsExactly("apply-as", "bucket", "profiles", "policy", "commands");
        assertThat(shared.get("apply-as").asString()).isEqualTo("bucket-policy");
        assertThat(strings(shared.get("profiles"))).as("a bucket holds one policy").containsExactly("lake", "audit");
        String text = DeletePolicy.bucketPolicy(StoreKind.S3, "us-east-1", "lake", List.of(
                new DeletePolicy.Worker(LAKE_ROLE, List.of(ObjectLocation.parse("s3://lake/tables"))),
                new DeletePolicy.Worker(AUDIT_ROLE, List.of(ObjectLocation.parse("s3://lake/audit")))));
        assertThat(shared.get("policy").asString()).isEqualTo(text).doesNotContain("'");
        assertThat(strings(shared.get("commands")))
                .containsExactly("aws s3api put-bucket-policy --bucket lake --policy '" + text + "'");
        assertThat(profile(body, "audit").get("store").get("policies")).as("the same policy, for audit too")
                .containsExactly(shared);

        JsonNode ceph = profile(body, "ceph").get("store").get("policies");
        assertThat(ceph).hasSize(1);
        String cephText = DeletePolicy.bucketPolicy(StoreKind.CEPH, "us-east-1", "ledger", List.of(
                new DeletePolicy.Worker("arn:aws:iam:::user/nuna-maintenance",
                        List.of(ObjectLocation.parse("s3://ledger/tables")))));
        assertThat(strings(ceph.get(0).get("commands"))).containsExactly("aws s3api put-bucket-policy --bucket ledger"
                + " --endpoint-url https://rgw.internal:7480 --policy '" + cephText + "'");
    }

    @Test
    void anUnprotectedProfileGetsTheSettingToAdd() {
        JsonNode store = profile(setup(), "scratch").get("store");

        assertThat(Management.JSON.writeValueAsString(store)).isEqualTo("{\"state\":\"unprotected\","
                + "\"setting\":\"nuna.storage.profiles.scratch.maintenance-principal\"}");
    }

    @Test
    void noKeyOrSecretAnywhere() {
        String body = routes.storageSetup(http).getBody();

        assertThat(body).as("no key, vending role, KMS key or CA bundle of the catalog's")
                .doesNotContain(SECRETS.toArray(String[]::new));
        assertThat(body).as("no secret key in any settings block").doesNotContain("secret-access-key:");
    }

    private JsonNode setup() {
        return Management.JSON.readTree(routes.storageSetup(http).getBody());
    }

    private static JsonNode profile(JsonNode body, String name) {
        return body.get("profiles").valueStream().filter(profile -> profile.get("name").asString().equals(name))
                .findFirst().orElseThrow();
    }

    private static List<String> names(JsonNode array, String field) {
        return array.valueStream().map(node -> node.get(field).asString()).toList();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static StorageSettings.Profile minio(String endpoint, String prefix, String principal) {
        return new StorageSettings.Profile(StoreKind.MINIO, endpoint, "us-east-1", prefix, List.of(prefix), null,
                "catalog-key-id", "catalog-secret-key", principal, null, null);
    }

    private static StorageSettings.Catalog catalog(String profile) {
        return new StorageSettings.Catalog(profile, "default", "nuna");
    }

    private static MaintenanceScan scan(Instant started, Optional<Instant> ended, WorkerStorage... storage) {
        return new MaintenanceScan(UUID.randomUUID(), "nuna-maintenance", started, ended,
                ended.map(at -> MaintenanceScan.Outcome.SUCCEEDED), Map.of(), Optional.empty(),
                Optional.of(new TreeSet<>(Set.of("nuna-maintenance"))), Optional.of(List.of(storage)));
    }

    private static WorkerStorage.Prefix listed(String prefix, long deleted, long refused, String code) {
        return new WorkerStorage.Prefix(prefix, Listed.OK, Optional.empty(), deleted, refused, 0,
                Optional.ofNullable(code));
    }

    private static WorkerStorage.Prefix refused(String prefix) {
        return new WorkerStorage.Prefix(prefix, Listed.REFUSED, Optional.of("AccessDenied"), 0, 0, 0,
                Optional.empty());
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }
}
