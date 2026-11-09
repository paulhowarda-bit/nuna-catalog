package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import fidice.catalog.audit.CredentialIssue;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.server.rest.Callers;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.server.storage.CatalogStorage;
import fidice.catalog.server.storage.CredentialAudit;
import fidice.catalog.server.storage.StorageSettings;
import fidice.catalog.objectstore.StoreKind;
import fidice.catalog.store.CatalogStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Real STS credentials through the REST routes (D21, D23, D14), on Silo: what a load vends, that the
 * credentials reach the table's prefix and nothing else, that every new set is audited before it is handed
 * out and nothing is handed out when the audit fails, the maintenance and delegation rules, and the 304 rule
 * with real expiry. Callers come from test headers until M5 validates tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fidice.storage.maintenance-principals=svc-maintenance")
@Import({PostgresForTests.class, SiloForTests.class, VendingRouteTest.TestCallersAndAudit.class})
class VendingRouteTest {

    static final List<CredentialIssue> ISSUED = new CopyOnWriteArrayList<>();
    static final AtomicBoolean AUDIT_DOWN = new AtomicBoolean();

    /** Callers named by headers, and an audit sink that records, or fails when told to. */
    @TestConfiguration
    static class TestCallersAndAudit {

        @Bean
        @Primary
        Callers headerCallers() {
            return request -> {
                String principal = request.getHeader("X-Test-Principal");
                if (principal == null) {
                    return Callers.ANONYMOUS_CALLER;
                }
                return new Caller(new Principal(principal, Set.of()), "spark",
                        "true".equals(request.getHeader("X-Test-Trusted")), Optional.empty());
            };
        }

        @Bean
        @Primary
        CredentialAudit recordingAudit() {
            return issue -> {
                if (AUDIT_DOWN.get()) {
                    throw new IllegalStateException("audit broker unreachable");
                }
                ISSUED.add(issue);
            };
        }
    }

    private static final String TABLE = """
            {"name":"t","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}%s}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SiloForTests.Silo silo;

    @Autowired
    private CatalogStore store;

    @Autowired
    private RestSettings rest;

    private String namespace;

    @BeforeEach
    void freshNamespace() throws Exception {
        AUDIT_DOWN.set(false);
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
        assertThat(config).containsEntry("s3.endpoint", silo.endpoint().toString())
                .containsEntry("s3.path-style-access", "true")
                .containsEntry("client.region", "us-east-1")
                .containsEntry("client.refresh-credentials-enabled", "true")
                .containsEntry("client.refresh-credentials-endpoint",
                        "v1/trading/namespaces/" + namespace + "/tables/t/credentials");
        assertThat(JsonPath.<List<String>>read(load.body(), "$.storage-credentials[*].prefix")).containsExactly(location);

        try (S3Client s3 = client(config)) {
            String key = key(location) + "/data/00000-0.parquet";
            s3.putObject(b -> b.bucket(SiloForTests.BUCKET).key(key), RequestBody.fromString("rows"));
            assertThat(s3.getObjectAsBytes(b -> b.bucket(SiloForTests.BUCKET).key(key)).asUtf8String())
                    .isEqualTo("rows");
            assertDenied(() -> s3.putObject(b -> b.bucket(SiloForTests.BUCKET).key(key(other) + "/data/x.parquet"),
                    RequestBody.fromString("x")));
            assertDenied(() -> s3.listObjectsV2(b -> b.bucket(SiloForTests.BUCKET).prefix("tables/")));
            assertDenied(() -> s3.deleteObject(b -> b.bucket(SiloForTests.BUCKET).key(key)));
        }
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
            assertThat(issue.storageProfile()).isEqualTo("silo");
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
    @DisplayName("only a maintenance principal's credentials may delete, and only inside the table")
    void maintenanceMayDelete() throws Exception {
        String location = createTable("t", "alice");
        String other = createTable("u", "alice");
        try (S3Client root = SiloForTests.rootClient(silo)) {
            root.putObject(b -> b.bucket(SiloForTests.BUCKET).key(key(other) + "/data/keep.parquet"),
                    RequestBody.fromString("keep"));
        }
        HttpResponse<String> load = send("GET", table("t") + "/credentials", null, "svc-maintenance");
        Map<String, String> config = JsonPath.read(load.body(), "$.storage-credentials[0].config");

        try (S3Client s3 = client(config)) {
            String key = key(location) + "/data/old.parquet";
            s3.putObject(b -> b.bucket(SiloForTests.BUCKET).key(key), RequestBody.fromString("old"));
            s3.deleteObject(b -> b.bucket(SiloForTests.BUCKET).key(key));
            assertDenied(() -> s3.deleteObject(b -> b.bucket(SiloForTests.BUCKET).key(key(other) + "/data/keep.parquet")));
        }
        assertThat(ISSUED).anySatisfy(issue -> {
            assertThat(issue.actor().principal()).isEqualTo("svc-maintenance");
            assertThat(issue.access()).isEqualTo(CredentialIssue.Access.READ_WRITE_DELETE);
        });
    }

    @Test
    @DisplayName("a trusted engine that declines delegation gets none; any other caller still gets credentials")
    void onlyTrustedEnginesMayDecline() throws Exception {
        createTable("t", "alice");

        HttpResponse<String> trusted = send("GET", table("t"), null, "trino-svc", "X-Test-Trusted", "true",
                "X-Iceberg-Access-Delegation", "none");
        assertThat(trusted.statusCode()).isEqualTo(200);
        assertThat(trusted.body()).doesNotContain("s3.access-key-id").doesNotContain("storage-credentials\":[{");

        HttpResponse<String> untrusted = send("GET", table("t"), null, "dave", "X-Iceberg-Access-Delegation", "none");
        assertThat(JsonPath.<Map<String, String>>read(untrusted.body(), "$.config")).containsKey("s3.access-key-id");

        HttpResponse<String> both = send("GET", table("t"), null, "trino-svc", "X-Test-Trusted", "true",
                "X-Iceberg-Access-Delegation", "vended-credentials,none");
        assertThat(JsonPath.<Map<String, String>>read(both.body(), "$.config")).containsKey("s3.access-key-id");
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
    @DisplayName("a declared catalog's boundary never changes, profiles never overlap, and endpoints use TLS")
    void startupGuardrails() {
        StorageSettings.Profile silo = new StorageSettings.Profile(StoreKind.MINIO, this.silo.endpoint().toString(),
                "us-east-1", "s3://warehouse/tables", List.of("s3://warehouse/tables"), null, "a", "b");
        StorageSettings.Profile nested = new StorageSettings.Profile(StoreKind.MINIO, this.silo.endpoint().toString(),
                "us-east-1", "s3://warehouse/tables/eu", List.of("s3://warehouse/tables/eu"), null, "a", "b");
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of("silo", silo,
                "eu", nested), Map.of(), false), store, rest, clock)).hasMessageContaining("overlap");
        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of("silo", silo), Map.of(), true), store, rest,
                clock)).hasMessageContaining("must use https");
        assertThatThrownBy(() -> new CatalogStorage(settings(Map.of("silo", silo),
                Map.of("trading", new StorageSettings.Catalog("silo", "other-keys", "Test Entity")), false), store,
                rest, clock)).hasMessageContaining("a catalog's boundary never changes");
    }

    private static StorageSettings settings(Map<String, StorageSettings.Profile> profiles,
                                            Map<String, StorageSettings.Catalog> catalogs, boolean tls) {
        return new StorageSettings(profiles, catalogs, Duration.ofMinutes(30), 100, List.of(), tls);
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
        return location.substring(("s3://" + SiloForTests.BUCKET + "/").length());
    }

    private S3Client client(Map<String, String> config) {
        return S3Client.builder()
                .endpointOverride(silo.endpoint())
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

    private HttpResponse<String> send(String method, String path, String body, String principal, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/trading" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("X-Test-Principal", principal);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
