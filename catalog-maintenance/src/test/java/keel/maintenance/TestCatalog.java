package keel.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import keel.catalog.objectstore.DeletePolicy;
import keel.catalog.objectstore.ObjectLocation;
import keel.catalog.objectstore.StoreKind;
import keel.catalog.security.TestIdp;
import keel.catalog.server.CatalogServer;
import keel.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * One catalog server for the worker's tests, for the whole test JVM: PostgreSQL, Kafka (audit is on), the run's S3
 * store ({@link TestS3}, chosen by {@code -Ps3}), a local token issuer, and a clock the tests move forward
 * ({@link #serverClock}), which decides when pending deletions and purges are due. Catalog {@value #CATALOG} lives
 * in the store's bucket {@code warehouse} under {@code tables/}.
 *
 * <p>Callers, each a group with its grants: writers (create, modify), the worker {@value #WORKER} and a second
 * worker {@value #OTHER_WORKER}, both maintenance principals (write), tag stewards (manage-tags, for legal holds) and
 * platform operators (DR freezes). Every token is signed at the server's clock.
 *
 * <p>The worker decides ages by its own clock: a test gives it one ({@link FakeClock}, starting at the wall clock)
 * and moves it with {@link #advance}, which moves the server's clock as far, so the server never finds a snapshot
 * younger than the worker does. Snapshot times and S3 modification times are wall-clock times.
 */
final class TestCatalog {

    static final String CATALOG = "lake";
    static final String WORKER = "keel-maintenance";
    static final String OTHER_WORKER = "keel-maintenance-2";
    static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));

    private static final String ISSUER = "https://idp.test/maintenance";
    private static final String AUDIENCE = "keel-catalog";
    private static final String WRITERS = "writers";
    private static final String WORKERS = "maintainers";
    private static final String STEWARDS = "stewards";
    private static final String OPERATORS = "platform-ops";
    private static final String PROFILE = "s3";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The clock of every server here; only ever moved forward. */
    static final FakeClock SERVER_CLOCK = new FakeClock();

    private static Backing backing;
    private static TestCatalog local;
    private static TestCatalog forPods;

    final URI uri;
    final FakeClock serverClock = SERVER_CLOCK;
    final TestS3 s3 = TestS3.get();
    final String bucket = s3.bucket("warehouse");
    final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** The containers and the issuer every server here shares. */
    private record Backing(PostgreSQLContainer postgres, KafkaContainer kafka, TestIdp idp, RSAKey key) {
    }

    /** The server's clock, as its bean. */
    @Configuration(proxyBeanMethods = false)
    static class ServerClock {

        @Bean
        @Primary
        Clock testClock() {
            return SERVER_CLOCK;
        }
    }

    private TestCatalog(String host) {
        Backing shared = backing();
        Map<String, Object> properties = new LinkedHashMap<>(s3.serverProperties(PROFILE, bucket));
        if (!host.equals("localhost") && s3.kind != TestS3.Kind.AWS) {
            URI endpoint = s3.endpoint;
            properties.put("keel.storage.profiles." + PROFILE + ".endpoint", URI.create(endpoint.getScheme() + "://"
                    + host + ":" + endpoint.getPort()).toString());
        }
        properties.put("spring.datasource.url", shared.postgres().getJdbcUrl());
        properties.put("spring.datasource.username", shared.postgres().getUsername());
        properties.put("spring.datasource.password", shared.postgres().getPassword());
        properties.put("keel.storage.catalogs." + CATALOG + ".profile", PROFILE);
        properties.put("keel.storage.catalogs." + CATALOG + ".kms-key-set", "test-keys");
        properties.put("keel.storage.catalogs." + CATALOG + ".legal-entity", "Test Entity");
        properties.put("keel.security.issuers[0].issuer", ISSUER);
        properties.put("keel.security.issuers[0].jwks-uri", shared.idp().jwksUri().toString());
        properties.put("keel.security.issuers[0].audiences", AUDIENCE);
        properties.put("keel.security.issuers[0].algorithms", "RS256");
        properties.put("keel.security.issuers[0].user-id-claim", "sub");
        properties.put("keel.security.issuers[0].groups-claim", "groups");
        properties.put("keel.security.issuers[0].client-id-claim", "azp");
        properties.put("keel.security.maintenance-principals", WORKER + "," + OTHER_WORKER);
        properties.put("keel.security.platform-operator-groups", OPERATORS);
        properties.put("keel.governance.purges-per-minute", "1000");
        properties.put("keel.audit.kafka-bootstrap-servers", shared.kafka().getBootstrapServers());
        properties.put("keel.audit.delivery-timeout", "3s");
        properties.put("keel.audit.request-timeout", "1s");
        properties.put("keel.audit.probe-interval", "1s");
        properties.put("keel.audit.relay-interval", "100ms");
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class, ServerClock.class)
                .properties(properties)
                // A free port, as an argument: builder properties are only defaults, and application.yaml's 8181
                // would win over them, clashing with any catalog already running here (the demo).
                .run("--server.port=0");
        jdbc = server.getBean(JdbcTemplate.class);
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        uri = URI.create("http://" + host + ":" + port + "/");
    }

    /** The server for tests in this JVM. */
    static synchronized TestCatalog get() {
        if (local == null) {
            local = new TestCatalog("localhost");
            local.grant();
            local.protectDeletes();
        }
        return local;
    }

    /**
     * The catalog's address for pods of Docker Desktop's Kubernetes: a second server on the same database, which it
     * and its storage profile's S3 endpoint name by {@code host.docker.internal}, as those pods resolve this machine.
     * The test and the worker reach the same catalog and store through {@link #get()}; this server's own calls to
     * the store resolve the name to this machine through {@link HostDockerInternal}, not the Windows hosts-file
     * entry, which goes stale when the machine's address changes.
     */
    static synchronized URI forPods() {
        get();
        if (forPods == null) {
            forPods = new TestCatalog("host.docker.internal");
        }
        return forPods.uri;
    }

    private static synchronized Backing backing() {
        if (backing == null) {
            PostgreSQLContainer postgres =
                    new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
            postgres.start();
            KafkaContainer kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            kafka.start();
            try {
                TestIdp idp = new TestIdp(ISSUER, AUDIENCE);
                RSAKey key = TestIdp.rsaKey("maintenance-tests");
                idp.publish(key);
                backing = new Backing(postgres, kafka, idp, key);
            } catch (Exception e) {
                throw new IllegalStateException("the test token issuer did not start", e);
            }
        }
        return backing;
    }

    /** The Kafka broker audit goes to. */
    static KafkaContainer kafka() {
        return backing().kafka();
    }

    private void grant() {
        UUID catalog = jdbc.queryForObject("SELECT id FROM catalog WHERE name_norm = ?", UUID.class, CATALOG);
        Map<String, List<String>> grants = Map.of(WRITERS, List.of("create", "modify"), WORKERS, List.of("write"),
                STEWARDS, List.of("manage-tags"));
        grants.forEach((group, privileges) -> privileges.forEach(privilege -> jdbc.update("INSERT INTO grant_record"
                + " (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)"
                + " VALUES (?, 'CATALOG', ?, ?, ?, 'maintenance-tests', now())", catalog, catalog, group, privilege)));
    }

    /**
     * On Ceph, the delete policy an operator applies (D30), as it is: beneath {@code tables/} only the worker's own
     * user deletes, and it lists there too; it reads files through vended credentials. Silo enforces no bucket
     * policy, and the AWS run's identity is the worker's.
     */
    private void protectDeletes() {
        if (s3.kind != TestS3.Kind.CEPH) {
            return;
        }
        String policy = DeletePolicy.bucketPolicy(StoreKind.CEPH, TestS3.REGION, bucket, List.of(new DeletePolicy.Worker(
                s3.maintenancePrincipal(), List.of(ObjectLocation.parse("s3://" + bucket + "/tables")))));
        try (S3Client client = s3.client()) {
            client.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
        }
    }

    // ---- callers ----

    /** Tokens for a caller in a group, signed at the server's clock and valid for an hour of it. */
    TokenSource tokens(String subject, String group) {
        RSAKey key = backing().key();
        return new TokenSource() {
            @Override
            public String token() {
                Instant now = serverClock.instant();
                try {
                    return TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder().issuer(ISSUER)
                            .audience(AUDIENCE).subject(subject).claim("groups", List.of(group)).claim("azp", subject)
                            .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(3600)))
                            .build());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of("token", token());
            }
        };
    }

    TokenSource workerTokens() {
        return tokens(WORKER, WORKERS);
    }

    TokenSource otherWorkerTokens() {
        return tokens(OTHER_WORKER, WORKERS);
    }

    TokenSource writerTokens() {
        return tokens("writer", WRITERS);
    }

    TokenSource steward() {
        return tokens("steward", STEWARDS);
    }

    TokenSource operator() {
        return tokens("operator", OPERATORS);
    }

    /** The worker's own storage identity on the run's store (D30): the only one a delete policy lets delete. */
    Map<String, WorkerSettings.StorageProfile> storageProfiles() {
        URI endpoint = s3.kind == TestS3.Kind.AWS ? null
                : URI.create(s3.endpoint.getScheme() + "://" + uri.getHost() + ":" + s3.endpoint.getPort());
        String key = null;
        String secret = null;
        if (s3.kind == TestS3.Kind.CEPH) {
            key = TestS3.MAINTENANCE_ACCESS_KEY;
            secret = TestS3.MAINTENANCE_SECRET_KEY;
        } else if (s3.kind == TestS3.Kind.SILO) {
            key = TestS3.ACCESS_KEY;
            secret = TestS3.SECRET_KEY;
        }
        return Map.of("test", new WorkerSettings.StorageProfile(List.of("s3://" + bucket + "/tables"), endpoint,
                TestS3.REGION, s3.kind != TestS3.Kind.AWS, key, secret));
    }

    /** The worker, with its own clock, deciding nothing by the server's. */
    Worker worker(Clock clock, Compactor compactor) {
        return worker(clock, compactor, Storage.of(storageProfiles()));
    }

    Worker worker(Clock clock, Compactor compactor, Storage storage) {
        TokenSource tokens = workerTokens();
        return new Worker(new Management(uri, tokens, http, 100), new Catalogs(uri, tokens, Map.of()), storage,
                compactor, clock, Duration.ofDays(1), Duration.ofDays(1));
    }

    /** The worker's management client. */
    Management management() {
        return new Management(uri, workerTokens(), http, 100);
    }

    /** Moves the server's clock and the worker's forward together. */
    void advance(Duration duration, FakeClock worker) {
        serverClock.advance(duration);
        worker.advance(duration);
    }

    /** An Iceberg client as the writers' group, on vended credentials. */
    RESTCatalog writer() {
        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.URI, uri.toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, CATALOG);
        properties.put("rest.auth.type", BearerAuthManager.class.getName());
        properties.put(BearerAuthManager.SOURCE, BearerAuthManager.register(writerTokens()));
        properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("http-client.type", "apache");
        properties.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
        RESTCatalog client = new RESTCatalog();
        client.initialize("writer", properties);
        return client;
    }

    /** A new namespace with a new unpartitioned v2 table in it. */
    static TableIdentifier table(RESTCatalog writer, String name) {
        Namespace namespace = Namespace.of("ns_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        writer.createNamespace(namespace);
        TableIdentifier table = TableIdentifier.of(namespace, name);
        writer.buildTable(table, SCHEMA).withPartitionSpec(PartitionSpec.unpartitioned())
                .withProperty("format-version", "2").create();
        CREATED.add(table);
        return table;
    }

    private static final List<TableIdentifier> CREATED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Turns every job off on the tables tests made so far, so the next test's scans do not maintain them again.
     * Tables since dropped or unregistered refuse, and are left as they are.
     */
    void quiet() {
        for (TableIdentifier table : CREATED) {
            manage(writerTokens(), "PUT", path(table) + "/maintenance-policy", "{\"snapshot-expiry.enabled\":\"false\","
                    + "\"orphan-removal.enabled\":\"false\",\"compaction.enabled\":\"false\","
                    + "\"manifest-rewrite.enabled\":\"false\"}");
        }
        CREATED.clear();
    }

    /** Every file a snapshot reaches: its manifest list, its manifests, and their live data and delete files. */
    static java.util.Set<String> reachable(Table table, org.apache.iceberg.Snapshot snapshot) {
        java.util.Set<String> files = new java.util.HashSet<>();
        files.add(snapshot.manifestListLocation());
        for (org.apache.iceberg.ManifestFile manifest : snapshot.allManifests(table.io())) {
            files.add(manifest.path());
            if (manifest.content() == org.apache.iceberg.ManifestContent.DATA) {
                try (org.apache.iceberg.ManifestReader<DataFile> reader =
                             org.apache.iceberg.ManifestFiles.read(manifest, table.io(), table.specs())) {
                    reader.forEach(file -> files.add(file.location()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return files;
    }

    /** The table's current metadata file and those in its metadata log. */
    static java.util.Set<String> metadataFiles(Table table) {
        org.apache.iceberg.TableMetadata metadata = ((org.apache.iceberg.HasTableOperations) table).operations()
                .current();
        java.util.Set<String> files = new java.util.HashSet<>();
        files.add(metadata.metadataFileLocation());
        metadata.previousFiles().forEach(entry -> files.add(entry.file()));
        return files;
    }

    /** A request to the catalog's Iceberg REST API, under the catalog's prefix. */
    HttpResponse<String> rest(TokenSource caller, String method, String path) {
        HttpRequest request = HttpRequest.newBuilder(uri.resolve("v1/" + CATALOG + path))
                .header("Authorization", "Bearer " + caller.token())
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A small file written beneath the table's data location, as a data file of it (not yet committed). */
    static DataFile dataFile(Table table, String name) {
        String location = table.locationProvider().newDataLocation(name + ".parquet");
        try (PositionOutputStream out = table.io().newOutputFile(location).createOrOverwrite()) {
            out.write(name.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return DataFiles.builder(table.spec()).withPath(location).withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(name.length()).withRecordCount(1).build();
    }

    // ---- management API ----

    HttpResponse<String> manage(TokenSource caller, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri.resolve("management/v1/catalogs/" + CATALOG + path))
                .header("Authorization", "Bearer " + caller.token())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> expect(HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new AssertionError(response.request().method() + " " + response.request().uri() + " answered "
                    + response.statusCode() + ", not " + status + ": " + response.body());
        }
        return response;
    }

    private static String path(TableIdentifier table) {
        return "/namespaces/" + table.namespace().level(0) + "/tables/" + table.name();
    }

    /** Sets the table's own maintenance policy, as a writer. */
    void policy(TableIdentifier table, Map<String, String> policy) {
        try {
            expect(manage(writerTokens(), "PUT", path(table) + "/maintenance-policy", JSON.writeValueAsString(policy)),
                    200);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Places a legal hold on the table, as a tag steward; returns its id. */
    String hold(TableIdentifier table) {
        return read(expect(manage(steward(), "POST", path(table) + "/holds", "{\"reason\":\"matter 12\"}"), 201)
                .body()).path("id").asText();
    }

    void release(TableIdentifier table, String hold) {
        expect(manage(steward(), "DELETE", path(table) + "/holds/" + hold, null), 204);
    }

    /** Places a DR freeze on the namespace, as a platform operator. */
    void freeze(Namespace namespace) {
        expect(manage(operator(), "PUT", "/namespaces/" + namespace.level(0) + "/dr-freeze", "{\"reason\":\"drill\"}"),
                200);
    }

    void thaw(Namespace namespace) {
        expect(manage(operator(), "DELETE", "/namespaces/" + namespace.level(0) + "/dr-freeze", null), 204);
    }

    // ---- what the catalog and the store hold ----

    /** Every file pending deletion for the object, due or not, as the catalog's database holds them. */
    List<String> pending(String objectId) {
        return jdbc.queryForList("SELECT location FROM pending_deletion WHERE object_id = ?", String.class,
                UUID.fromString(objectId));
    }

    /** The object's lifecycle state in the catalog's database, e.g. {@code PURGED}. */
    String state(String objectId) {
        return jdbc.queryForObject("SELECT state FROM catalog_object WHERE id = ?", String.class,
                UUID.fromString(objectId));
    }

    boolean exists(String location) {
        String[] parsed = split(location);
        try (S3Client client = s3.client()) {
            client.headObject(r -> r.bucket(parsed[0]).key(parsed[1]));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Writes an object straight to the store, as nothing the catalog knows of. */
    void put(String location, String content) {
        String[] parsed = split(location);
        try (S3Client client = s3.client()) {
            client.putObject(r -> r.bucket(parsed[0]).key(parsed[1]), RequestBody.fromString(content));
        }
    }

    /** Every object under a prefix. */
    List<String> list(String prefix) {
        String[] parsed = split(prefix);
        List<String> keys = new ArrayList<>();
        try (S3Client client = s3.client()) {
            client.listObjectsV2Paginator(r -> r.bucket(parsed[0]).prefix(parsed[1])).contents()
                    .forEach(object -> keys.add("s3://" + parsed[0] + "/" + object.key()));
        }
        return keys;
    }

    /** An {@code s3://} location's bucket and key. */
    private static String[] split(String location) {
        String path = location.substring("s3://".length());
        int slash = path.indexOf('/');
        return new String[] {path.substring(0, slash), path.substring(slash + 1)};
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
