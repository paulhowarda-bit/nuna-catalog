package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.storage.StsCredentialVending;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.tests.s3.TestS3;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Real STS credentials through the REST routes (D21, D23, D14), on the run's S3 store: what a load vends, that the
 * credentials reach the table's prefix and nothing else, that a tag's only read (D33), that every new set is audited
 * before it is handed out and nothing is handed out when the audit fails, that no credentials delete (D30), the
 * delegation rules, and the 304 rule with real expiry. Callers sign in with tokens from the local test issuer;
 * {@code trino-prod} is registered as a trusted engine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nuna.security.maintenance-principals=svc-maintenance",
        "nuna.security.platform-operator-groups=platform-ops"})
@Tag("heavy")
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, VendingRouteTest.RecordingAudit.class})
class VendingRouteTest {

    static final List<CredentialIssue> ISSUED = new CopyOnWriteArrayList<>();
    static final AtomicBoolean AUDIT_DOWN = new AtomicBoolean();

    /** An audit publisher that records credential issues, or fails when told to. */
    @TestConfiguration
    static class RecordingAudit {

        @Bean
        @Primary
        AuditPublisher recordingAudit() {
            return event -> {
                if (AUDIT_DOWN.get()) {
                    throw new IllegalStateException("audit broker unreachable");
                }
                if (event.data() instanceof CredentialIssue issue) {
                    ISSUED.add(issue);
                }
            };
        }
    }

    private static final String TRUSTED_ENGINE = "trino-prod";
    /** Holds only {@code select} on the catalog. */
    private static final String READERS = "catalog-readers";
    /** Holds only {@code create} on the catalog. */
    private static final String BUILDERS = "catalog-builders";

    private static final String TABLE = """
            {"name":"t","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}%s}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestS3 objectStore;

    @Autowired
    private CatalogStore store;

    @Autowired
    private RestSettings rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private String namespace;

    @BeforeEach
    void freshNamespace() throws Exception {
        AUDIT_DOWN.set(false);
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        HttpResponse<String> engine = request("PUT", "/management/v1/engines", """
                {"issuer":"%s","client-id":"%s","end-user-claim":"oid","flags":[]}"""
                .formatted(IssuerForTests.ISSUER, TRUSTED_ENGINE), IssuerForTests.bearer("ops", "platform-ops"));
        assertThat(engine.statusCode()).as(engine.body()).isBetween(200, 204);
        namespace = "vend_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("POST", "/namespaces", "{\"namespace\":[\"" + namespace + "\"]}", "alice").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a load vends credentials, a refresh route and the store's endpoint, and they reach the table only")
    void loadVendsCredentialsConfinedToTheTable() throws Exception {
        String location = createTable("t", "alice");
        String other = createTable("u", "alice");
        HttpResponse<String> load = send("GET", table("t"), null, "alice");

        assertThat(load.statusCode()).isEqualTo(200);
        Map<String, String> config = JsonPath.read(load.body(), "$.config");
        assertThat(config).containsKeys("s3.access-key-id", "s3.secret-access-key", "s3.session-token",
                "s3.session-token-expires-at-ms");
        if (objectStore.kind == TestS3.Kind.AWS) {
            assertThat(config).doesNotContainKey("s3.endpoint").containsEntry("s3.path-style-access", "false");
        } else {
            assertThat(config).containsEntry("s3.endpoint", objectStore.endpoint.toString())
                    .containsEntry("s3.path-style-access", "true");
        }
        assertThat(config)
                .containsEntry("client.region", "us-east-1")
                .containsEntry("client.refresh-credentials-enabled", "true")
                .containsEntry("client.refresh-credentials-endpoint",
                        "v1/trading/namespaces/" + namespace + "/tables/t/credentials");
        assertThat(JsonPath.<List<String>>read(load.body(), "$.storage-credentials[*].prefix")).containsExactly(location);

        try (S3Client s3 = client(config)) {
            String key = key(location) + "/data/00000-0.parquet";
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key), RequestBody.fromString("rows"));
            assertThat(s3.getObjectAsBytes(b -> b.bucket(S3ForTests.bucket()).key(key)).asUtf8String())
                    .isEqualTo("rows");
            assertDenied(() -> s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key(other) + "/data/x.parquet"),
                    RequestBody.fromString("x")));
            assertDenied(() -> s3.listObjectsV2(b -> b.bucket(S3ForTests.bucket()).prefix("tables/")));
            assertDenied(() -> s3.deleteObject(b -> b.bucket(S3ForTests.bucket()).key(key)));
        }
    }

    @Test
    @DisplayName("a caller with only select gets credentials that read the table but write nothing; a writer's write")
    void selectOnlyCallersGetReadOnlyCredentials() throws Exception {
        ISSUED.clear();
        String location = createTable("t", "alice");
        String rows = key(location) + "/data/00000-0.parquet";
        try (S3Client root = objectStore.client()) {
            root.putObject(b -> b.bucket(S3ForTests.bucket()).key(rows), RequestBody.fromString("rows"));
        }
        IssuerForTests.grant(jdbc, "trading", READERS, "select");

        String reader = IssuerForTests.bearer("rita", READERS);
        for (String path : List.of(table("t"), table("t") + "/credentials")) {
            HttpResponse<String> load = request("GET", "/v1/trading" + path, null, reader);
            assertThat(load.statusCode()).as(load.body()).isEqualTo(200);
            try (S3Client s3 = client(JsonPath.read(load.body(), "$.storage-credentials[0].config"))) {
                assertThat(s3.getObjectAsBytes(b -> b.bucket(S3ForTests.bucket()).key(rows)).asUtf8String())
                        .isEqualTo("rows");
                assertThat(s3.listObjectsV2(b -> b.bucket(S3ForTests.bucket()).prefix(key(location) + "/")).contents())
                        .extracting(o -> o.key()).contains(rows);
                assertDenied(() -> s3.putObject(b -> b.bucket(S3ForTests.bucket())
                        .key(key(location) + "/data/01a1d172-orphan.parquet"), RequestBody.fromString("x")));
                assertDenied(() -> s3.putObject(b -> b.bucket(S3ForTests.bucket())
                        .key(key(location) + "/metadata/snap-1-orphan.avro"), RequestBody.fromString("x")));
                assertDenied(() -> s3.createMultipartUpload(b -> b.bucket(S3ForTests.bucket())
                        .key(key(location) + "/data/big.parquet")));
                assertDenied(() -> s3.deleteObject(b -> b.bucket(S3ForTests.bucket()).key(rows)));
            }
        }
        HttpResponse<String> commit = request("POST", "/v1/trading" + table("t"), """
                {"updates":[{"action":"set-snapshot-ref","ref-name":"main","snapshot-id":1,"type":"branch"}]}""",
                reader);
        assertThat(commit.statusCode()).as("the commit is refused as before").isEqualTo(403);
        assertThat(ISSUED).filteredOn(issue -> issue.actor().principal().equals("rita"))
                .as("one set, cached for the credentials route").singleElement()
                .extracting(CredentialIssue::access).isEqualTo(CredentialIssue.Access.READ);

        HttpResponse<String> load = send("GET", table("t"), null, "alice");
        try (S3Client s3 = client(JsonPath.read(load.body(), "$.config"))) {
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key(location) + "/data/00001-0.parquet"),
                    RequestBody.fromString("more"));
        }
        assertThat(ISSUED).filteredOn(issue -> issue.actor().principal().equals("alice"))
                .as("the create's set, which the load reuses").singleElement()
                .extracting(CredentialIssue::access).isEqualTo(CredentialIssue.Access.READ_WRITE);
    }

    /**
     * A tag is read-only (D33, item 26's choice 5), so its credentials refuse PutObject on the store itself, to a caller
     * who may write the table, while the same caller's credentials on main write (backlog 112).
     */
    @Test
    @DisplayName("a tag's credentials read the table but refuse PutObject, to a caller who may write it on main")
    void aTagsCredentialsRefusePutObject() throws Exception {
        String location = createTable("t", "alice");
        String rows = key(location) + "/data/00000-0.parquet";
        try (S3Client root = objectStore.client()) {
            root.putObject(b -> b.bucket(S3ForTests.bucket()).key(rows), RequestBody.fromString("rows"));
        }
        String alice = IssuerForTests.bearer("alice", IssuerForTests.GROUP);
        String tag = namespace + "-eod";
        HttpResponse<String> made = request("POST", "/management/v1/catalogs/trading/refs",
                "{\"name\":\"" + tag + "\",\"kind\":\"tag\"}", alice);
        assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        try {
            int before = ISSUED.size();
            for (String path : List.of(table("t"), table("t") + "/credentials")) {
                HttpResponse<String> load = request("GET", "/v1/trading@" + tag + path, null, alice);
                assertThat(load.statusCode()).as(load.body()).isEqualTo(200);
                try (S3Client s3 = client(JsonPath.read(load.body(), "$.storage-credentials[0].config"))) {
                    assertThat(s3.getObjectAsBytes(b -> b.bucket(S3ForTests.bucket()).key(rows)).asUtf8String())
                            .isEqualTo("rows");
                    assertDenied(() -> s3.putObject(b -> b.bucket(S3ForTests.bucket())
                            .key(key(location) + "/data/00001-tag.parquet"), RequestBody.fromString("x")));
                    assertDenied(() -> s3.putObject(b -> b.bucket(S3ForTests.bucket())
                            .key(key(location) + "/metadata/snap-1-tag.avro"), RequestBody.fromString("x")));
                    assertDenied(() -> s3.createMultipartUpload(b -> b.bucket(S3ForTests.bucket())
                            .key(key(location) + "/data/big.parquet")));
                    assertDenied(() -> s3.deleteObject(b -> b.bucket(S3ForTests.bucket()).key(rows)));
                }
            }
            assertThat(ISSUED.subList(before, ISSUED.size())).as("the tag's sets").isNotEmpty()
                    .allSatisfy(issue -> assertThat(issue.access()).isEqualTo(CredentialIssue.Access.READ));

            HttpResponse<String> main = send("GET", table("t"), null, "alice");
            try (S3Client s3 = client(JsonPath.read(main.body(), "$.config"))) {
                s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key(location) + "/data/00001-main.parquet"),
                        RequestBody.fromString("more"));
            }
        } finally {
            HttpResponse<String> deleted = request("DELETE", "/management/v1/catalogs/trading/refs/" + tag, null,
                    alice);
            assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        }
    }

    @Test
    @DisplayName("a staged create vends read-write to a caller who may create the table, even without write")
    void stagedCreateVendsReadWrite() throws Exception {
        IssuerForTests.grant(jdbc, "trading", BUILDERS, "create");
        ISSUED.clear();

        HttpResponse<String> staged = request("POST", "/v1/trading/namespaces/" + namespace + "/tables",
                TABLE.formatted(",\"stage-create\":true").replace("\"t\"", "\"ctas\""),
                IssuerForTests.bearer("bert", BUILDERS));
        assertThat(staged.statusCode()).as(staged.body()).isEqualTo(200);
        String location = JsonPath.read(staged.body(), "$.metadata.location");
        try (S3Client s3 = client(JsonPath.read(staged.body(), "$.storage-credentials[0].config"))) {
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key(location) + "/data/00000-0.parquet"),
                    RequestBody.fromString("rows"));
        }
        assertThat(ISSUED).singleElement().extracting(CredentialIssue::access)
                .isEqualTo(CredentialIssue.Access.READ_WRITE);
    }

    @Test
    @DisplayName("each new set is audited before hand-out; while the audit is down nothing is handed out")
    void auditedBeforeHandOutAndFailsClosed() throws Exception {
        String location = createTable("t", "alice");
        ISSUED.clear();

        HttpResponse<String> load = send("GET", table("t"), null, "bob", "X-Request-ID", "req-bob-1");
        assertThat(load.statusCode()).isEqualTo(200);
        assertThat(ISSUED).singleElement().satisfies(issue -> {
            assertThat(issue.actor().principal()).isEqualTo("bob");
            assertThat(issue.actor().requestId()).isEqualTo("req-bob-1");
            assertThat(issue.locationPrefix()).isEqualTo(location);
            assertThat(issue.storageProfile()).isEqualTo(S3ForTests.PROFILE);
            assertThat(issue.access()).isEqualTo(CredentialIssue.Access.READ_WRITE);
            assertThat(issue.sessionName()).isEqualTo("bob_req-bob-1");
            assertThat(issue.object().path()).containsExactly(namespace, "t");
        });
        assertThat(send("GET", table("t"), null, "bob").statusCode()).as("cached: no new issue").isEqualTo(200);
        assertThat(ISSUED).hasSize(1);

        AUDIT_DOWN.set(true);
        for (String path : List.of(table("t"), table("t") + "/credentials")) {
            HttpResponse<String> refused = send("GET", path, null, "carol");
            assertThat(refused.statusCode()).as(path).isEqualTo(503);
            assertThat(refused.body()).doesNotContain("s3.session-token").doesNotContain("secret");
        }
        AUDIT_DOWN.set(false);
        assertThat(send("GET", table("t") + "/credentials", null, "carol").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("no vended credentials delete, a maintenance principal's included: the worker uses its own identity")
    void noCredentialsDelete() throws Exception {
        String location = createTable("t", "alice");
        HttpResponse<String> load = send("GET", table("t") + "/credentials", null, "svc-maintenance");
        Map<String, String> config = JsonPath.read(load.body(), "$.storage-credentials[0].config");
        String key = key(location) + "/data/old.parquet";

        try (S3Client s3 = client(config)) {
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(key), RequestBody.fromString("old"));
            assertDenied(() -> s3.deleteObject(b -> b.bucket(S3ForTests.bucket()).key(key)));
        }
        try (S3Client root = objectStore.client()) {
            assertThat(root.getObjectAsBytes(b -> b.bucket(S3ForTests.bucket()).key(key)).asUtf8String())
                    .isEqualTo("old");
        }
        assertThat(ISSUED).anySatisfy(issue -> {
            assertThat(issue.actor().principal()).isEqualTo("svc-maintenance");
            assertThat(issue.access()).isEqualTo(CredentialIssue.Access.READ_WRITE);
        });
    }

    @Test
    @DisplayName("any caller that declines delegation gets no credentials, no AssumeRole and no audit record; without"
            + " the header, or naming another mechanism too, it is vended to (D21, amended 2026-11-19)")
    void anyCallerMayDecline() throws Exception {
        createTable("t", "alice");
        ISSUED.clear();
        double assumed = assumeRoles();

        // dave is a person signed in directly, as the console's user is; the trusted engine declines as before.
        for (String caller : List.of("dave", TRUSTED)) {
            for (String path : List.of(table("t"), table("t") + "/credentials")) {
                HttpResponse<String> declined = send("GET", path, null, caller, "X-Iceberg-Access-Delegation", "none");
                assertThat(declined.statusCode()).as(caller + " " + path).isEqualTo(200);
                assertNoStorageAccess(declined.body());
            }
        }
        assertThat(assumeRoles()).as("no AssumeRole for a declined vend").isEqualTo(assumed);
        assertThat(ISSUED).as("no credential issue to audit").isEmpty();

        HttpResponse<String> vended = send("GET", table("t"), null, "dave");
        assertThat(JsonPath.<Map<String, String>>read(vended.body(), "$.config")).containsKey("s3.access-key-id");
        assertThat(assumeRoles()).as("a load without the header mints, nothing was cached").isEqualTo(assumed + 1);
        assertThat(ISSUED).singleElement().extracting(issue -> issue.actor().principal()).isEqualTo("dave");

        HttpResponse<String> both = send("GET", table("t"), null, TRUSTED,
                "X-Iceberg-Access-Delegation", "vended-credentials,none");
        assertThat(JsonPath.<Map<String, String>>read(both.body(), "$.config")).containsKey("s3.access-key-id");
    }

    /** A load or credentials answer with no storage credentials and no storage settings in its config. */
    private static void assertNoStorageAccess(String body) {
        Map<String, Object> response = JsonPath.read(body, "$");
        assertThat((List<?>) response.getOrDefault("storage-credentials", List.of())).as(body).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, String> config = (Map<String, String>) response.getOrDefault("config", Map.of());
        assertThat(config.keySet()).as(body).noneMatch(key -> key.startsWith("s3.") || key.startsWith("client."));
        assertThat(body).doesNotContain("s3.access-key-id", "s3.session-token");
    }

    /** STS AssumeRole calls, each one observation (spans.md). */
    private double assumeRoles() {
        return meters.find(StsCredentialVending.ASSUME_ROLE).timers().stream().mapToDouble(Timer::count).sum();
    }

    @Test
    @DisplayName("a load answers 304 while the vended credentials have time left, and staged creates vend too")
    void notModifiedWithRealCredentials() throws Exception {
        createTable("t", "alice");
        HttpResponse<String> first = send("GET", table("t"), null, "erin");
        String etag = first.headers().firstValue("ETag").orElseThrow();

        assertThat(send("GET", table("t"), null, "erin", "If-None-Match", etag).statusCode()).isEqualTo(304);

        HttpResponse<String> staged = send("POST", "/namespaces/" + namespace + "/tables",
                TABLE.formatted(",\"stage-create\":true").replace("\"t\"", "\"s\""), "erin");
        assertThat(staged.statusCode()).isEqualTo(200);
        String location = JsonPath.read(staged.body(), "$.metadata.location");
        assertThat(JsonPath.<List<String>>read(staged.body(), "$.storage-credentials[*].prefix"))
                .containsExactly(location);
    }

    @Test
    @DisplayName("clients may not set file IO properties that would redirect or re-credential storage")
    void fileIoPropertiesAreRefused() throws Exception {
        HttpResponse<String> response = send("POST", "/namespaces/" + namespace + "/tables",
                TABLE.formatted(",\"properties\":{\"s3.endpoint\":\"https://elsewhere.example\"}"), "alice");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("configures storage access");
    }

    @Test
    @DisplayName("a declared catalog's boundary never changes, profiles never overlap, endpoints use TLS,"
            + " and a maintenance principal needs no escaping")
    void startupGuardrails() {
        StoreKind kind = StoreKind.valueOf(objectStore.profileKind());
        String root = "s3://" + S3ForTests.bucket() + "/tables";
        String role = objectStore.roleArn.orElse(null);
        StorageSettings.Profile profile = new StorageSettings.Profile(kind, objectStore.endpoint.toString(),
                "us-east-1", root, List.of(root), role, "a", "b", null, null, null);
        StorageSettings.Profile nested = new StorageSettings.Profile(kind, objectStore.endpoint.toString(),
                "us-east-1", root + "/eu", List.of(root + "/eu"), role, "a", "b", null, null, null);
        StorageSettings.Profile injected = new StorageSettings.Profile(kind, objectStore.endpoint.toString(),
                "us-east-1", root, List.of(root), role, "a", "b", "arn:aws:iam:::user/w\"}],\"Statement\":[{\"x", null,
                null);
        Clock clock = Clock.systemUTC();
        ObservationRegistry none = ObservationRegistry.NOOP;

        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of(S3ForTests.PROFILE, profile,
                "eu", nested), Map.of(), false), store, rest, clock, none)).hasMessageContaining("overlap");
        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of(S3ForTests.PROFILE, profile), Map.of(), true), store, rest,
                clock, none)).hasMessageContaining("must use https");
        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of(S3ForTests.PROFILE, injected), Map.of(), false), store,
                rest, clock, none)).hasMessageContaining("maintenance-principal").hasMessageContaining("character outside");
        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of(S3ForTests.PROFILE, profile),
                Map.of("trading", new StorageSettings.Catalog(S3ForTests.PROFILE, "other-keys", "Test Entity")), false), store,
                rest, clock, none)).hasMessageContaining("a catalog's boundary never changes");
    }

    private static StorageSettings settings(Map<String, StorageSettings.Profile> profiles,
                                            Map<String, StorageSettings.Catalog> catalogs, boolean tls) {
        return new StorageSettings(profiles, catalogs, Duration.ofMinutes(30), 100, tls);
    }

    private String createTable(String name, String principal) throws Exception {
        HttpResponse<String> created = send("POST", "/namespaces/" + namespace + "/tables",
                TABLE.formatted("").replace("\"t\"", "\"" + name + "\""), principal);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        return JsonPath.read(created.body(), "$.metadata.location");
    }

    private String table(String name) {
        return "/namespaces/" + namespace + "/tables/" + name;
    }

    private static String key(String location) {
        return location.substring(("s3://" + S3ForTests.bucket() + "/").length());
    }

    private S3Client client(Map<String, String> config) {
        return S3Client.builder()
                .endpointOverride(objectStore.endpoint)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        config.get("s3.access-key-id"), config.get("s3.secret-access-key"),
                        config.get("s3.session-token"))))
                .build();
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(S3Exception.class,
                e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    /** The trusted engine's service principal, signing in through its registered client. */
    private static final String TRUSTED = "svc-trino@" + TRUSTED_ENGINE;

    /**
     * A request to the {@code trading} catalog as {@code principal}, a user in the test group; a principal
     * written {@code user@client} signs in through that client.
     */
    private HttpResponse<String> send(String method, String path, String body, String principal, String... headers)
            throws Exception {
        String[] parts = principal.split("@", 2);
        String token = parts.length == 2
                ? IssuerForTests.sign(IssuerForTests.claims(parts[0], parts[1], IssuerForTests.GROUP).build())
                : IssuerForTests.bearer(principal, IssuerForTests.GROUP);
        return request(method, "/v1/trading" + path, body, token, headers);
    }

    private HttpResponse<String> request(String method, String path, String body, String token, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", token);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
