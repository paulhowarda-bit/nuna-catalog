package nuna.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import nuna.catalog.objectstore.DeletePolicy;
import nuna.catalog.objectstore.ObjectLocation;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.TestIdp;
import nuna.catalog.server.CatalogServer;
import nuna.catalog.tests.kafka.TestKafka;
import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.aws.AssumeRoleAwsClientFactory;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.FileInfo;
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
 * younger than the worker does. Snapshot times and S3 modification times are wall-clock times. Its cleanup window is
 * open all day unless a test gives it another ({@link #allDay}).
 *
 * <p>A test that needs a store of its own (backlog 89: Silo behind TLS) gets a catalog of its own on it
 * ({@link #onStore}), on a database of its own in the same PostgreSQL.
 */
final class TestCatalog {

    static final String CATALOG = "lake";
    static final String WORKER = "nuna-maintenance";
    static final String OTHER_WORKER = "nuna-maintenance-2";
    static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));

    private static final String ISSUER = "https://idp.test/maintenance";
    private static final String AUDIENCE = "nuna-catalog";
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
    final TestS3 s3;
    final String bucket;
    final JdbcTemplate jdbc;
    /** The server's database, as its JDBC URL. */
    private final String database;
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

    /** A server on the run's store ({@link TestS3#get}) and the shared PostgreSQL's own database. */
    private TestCatalog(String host, String... arguments) {
        this(TestS3.get(), backing().postgres().getJdbcUrl(), host, arguments);
    }

    /**
     * @param database  the server's database, as its JDBC URL
     * @param arguments more of the server's settings, as command-line arguments, which win over its application.yaml
     */
    private TestCatalog(TestS3 s3, String database, String host, String... arguments) {
        this.s3 = s3;
        this.bucket = s3.bucket("warehouse");
        this.database = database;
        Backing shared = backing();
        Map<String, Object> properties = new LinkedHashMap<>(s3.serverProperties(PROFILE, bucket));
        if (!host.equals("localhost") && s3.kind != TestS3.Kind.AWS) {
            URI endpoint = s3.endpoint;
            properties.put("nuna.storage.profiles." + PROFILE + ".endpoint", URI.create(endpoint.getScheme() + "://"
                    + host + ":" + endpoint.getPort()).toString());
        }
        properties.put("spring.datasource.url", database);
        properties.put("spring.datasource.username", shared.postgres().getUsername());
        properties.put("spring.datasource.password", shared.postgres().getPassword());
        properties.put("nuna.storage.catalogs." + CATALOG + ".profile", PROFILE);
        properties.put("nuna.storage.catalogs." + CATALOG + ".kms-key-set", "test-keys");
        properties.put("nuna.storage.catalogs." + CATALOG + ".legal-entity", "Test Entity");
        properties.put("nuna.security.issuers[0].issuer", ISSUER);
        properties.put("nuna.security.issuers[0].jwks-uri", shared.idp().jwksUri().toString());
        properties.put("nuna.security.issuers[0].audiences", AUDIENCE);
        properties.put("nuna.security.issuers[0].algorithms", "RS256");
        properties.put("nuna.security.issuers[0].user-id-claim", "sub");
        properties.put("nuna.security.issuers[0].groups-claim", "groups");
        properties.put("nuna.security.issuers[0].client-id-claim", "azp");
        properties.put("nuna.security.maintenance-principals", WORKER + "," + OTHER_WORKER);
        properties.put("nuna.security.platform-operator-groups", OPERATORS);
        properties.put("nuna.governance.purges-per-minute", "1000");
        properties.put("nuna.audit.kafka-bootstrap-servers", shared.kafka().getBootstrapServers());
        properties.put("nuna.audit.delivery-timeout", "3s");
        properties.put("nuna.audit.request-timeout", "1s");
        properties.put("nuna.audit.probe-interval", "1s");
        properties.put("nuna.audit.relay-interval", "100ms");
        List<String> args = new ArrayList<>(List.of(arguments));
        // A free port, as an argument: builder properties are only defaults, and application.yaml's 8181 would win
        // over them, clashing with any catalog already running here (the demo).
        args.add("--server.port=0");
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class, ServerClock.class)
                .properties(properties)
                .run(args.toArray(String[]::new));
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

    /**
     * A catalog of its own on {@code s3}, a store apart from the run's (backlog 89: Silo behind TLS,
     * {@link TestS3#siloBehindTls}), on a new database in the shared PostgreSQL, so the work list its worker reads
     * holds its own tables alone. Where the store serves TLS, the server's storage profile names its CA's bundle, and
     * so do the worker's ({@link #storageProfiles}) and the writer's S3 clients ({@link #writer}). Pods reach it
     * through {@link #twinForPods}.
     */
    static TestCatalog onStore(TestS3 s3) {
        String name = "catalog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PostgreSQLContainer postgres = backing().postgres();
        try (Connection connection = postgres.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the database " + name, e);
        }
        TestCatalog catalog = new TestCatalog(s3, "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name, "localhost");
        catalog.grant();
        catalog.protectDeletes();
        return catalog;
    }

    /**
     * This catalog's address for pods of Docker Desktop's Kubernetes, as {@link #forPods()} is the JVM's catalog's: a
     * second server, started on each call, on this one's database and store, which it and its storage profile's S3
     * endpoint name by {@code host.docker.internal}.
     */
    URI twinForPods() {
        return new TestCatalog(s3, database, "host.docker.internal").uri;
    }

    /**
     * A catalog that exports its spans over OTLP/HTTP to {@code traces}: a second server on the same database and
     * store, for a test of the worker's trace. Its sampler keeps every request whose caller's trace was kept, and 10%
     * of the rest, as a deployment's does. Its spans come from {@code nuna-catalog}: on this module's class path the
     * worker's application.yaml comes first, which names every server here {@code nuna-maintenance}.
     */
    static synchronized TestCatalog traced(String traces) {
        get();
        return new TestCatalog("localhost", "--management.opentelemetry.tracing.export.otlp.endpoint=" + traces,
                "--management.opentelemetry.tracing.export.schedule-delay=100ms",
                "--spring.application.name=nuna-catalog");
    }

    private static synchronized Backing backing() {
        if (backing == null) {
            PostgreSQLContainer postgres =
                    new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
            postgres.start();
            KafkaContainer kafka = TestKafka.container("256m");
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
     * On Ceph and AWS, the delete policy an operator applies (D30), as it is: beneath {@code tables/} only the
     * worker's own identity deletes (Ceph's maintenance user, the AWS run's maintenance role), and it lists there too;
     * it reads files through vended credentials. Silo enforces no bucket policy. On AWS the run's sign-in, which is
     * also the catalog's own identity here, may then no longer delete beneath {@code tables/}; {@link TestS3} removes
     * the policy as the worker when the run ends.
     */
    private void protectDeletes() {
        if (s3.kind == TestS3.Kind.SILO) {
            return;
        }
        String policy = DeletePolicy.bucketPolicy(StoreKind.valueOf(s3.profileKind()), TestS3.REGION, bucket,
                List.of(new DeletePolicy.Worker(s3.maintenancePrincipal(),
                        List.of(ObjectLocation.parse("s3://" + bucket + "/tables")))));
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

    /**
     * The worker's own storage identity on the run's store (D30) as its settings give it: the only one a delete policy
     * lets delete. On AWS it names no keys, so it is the SDK's default chain, in a test JVM the run's sign-in: storage
     * as the worker is {@link #workerStorage}. On a store serving TLS ({@link #onStore}) it names the store's CA
     * bundle.
     */
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
                TestS3.REGION, s3.kind != TestS3.Kind.AWS, key, secret, s3.caBundle().orElse(null)));
    }

    /** The worker, with its own clock, deciding nothing by the server's. */
    Worker worker(Clock clock, Compactor compactor) {
        return worker(clock, compactor, workerStorage());
    }

    /**
     * The worker's storage as its own identity (D30), the only one the delete policy lets delete beneath
     * {@code tables/}: {@link Storage#of} on {@link #storageProfiles} on Silo and Ceph, the run's maintenance role on
     * AWS ({@link #asRole}). Every test that lists or deletes as the worker uses this, never {@link Storage#of} itself.
     */
    Storage workerStorage() {
        return s3.kind == TestS3.Kind.AWS ? asRole(s3.maintenancePrincipal()) : Storage.of(storageProfiles());
    }

    /**
     * On AWS, the worker's storage as its own role {@code role}. A deployed worker reaches AWS as its role through the
     * SDK's default chain (its pod's identity), which is all {@link Storage#of} uses there; in a test JVM that chain is
     * the run's sign-in, the catalog's own identity, which the delete policy refuses. So the same S3FileIO is opened
     * here with Iceberg's AssumeRoleAwsClientFactory, which assumes the role from that sign-in, and the worker lists
     * and deletes through it as {@link Storage#of}'s does: listPrefix and deleteFiles. A child JVM's worker
     * ({@link ChildJvm#storage}) opens it too.
     */
    static Storage asRole(String role) {
        S3FileIO io = new S3FileIO();
        io.initialize(Map.of("client.region", TestS3.REGION, "http-client.type", "apache",
                "client.factory", AssumeRoleAwsClientFactory.class.getName(),
                "client.assume-role.arn", role, "client.assume-role.region", TestS3.REGION));
        return new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                return io.listPrefix(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                io.deleteFiles(locations);
            }

            @Override
            public void close() {
                io.close();
            }
        };
    }

    Worker worker(Clock clock, Compactor compactor, Storage storage) {
        return worker(clock, compactor, storage, workerTokens(), new SimpleMeterRegistry());
    }

    /** The worker, signing in with {@code tokens} and recording its meters in {@code meters}. */
    Worker worker(Clock clock, Compactor compactor, Storage storage, TokenSource tokens, MeterRegistry meters) {
        return worker(clock, compactor, storage, tokens, meters, allDay(clock), 2);
    }

    /** The worker, its cleanup window {@code window}, running at most {@code maxJobs} compaction Jobs at once. */
    Worker worker(Clock clock, Compactor compactor, Storage storage, TokenSource tokens, MeterRegistry meters,
                  CleanupWindow window, int maxJobs) {
        return worker(clock, compactor, storage, tokens, meters, window, Duration.ofDays(1), maxJobs);
    }

    /**
     * The same, scanning a table for orphans at most once per {@code orphanInterval}. It reads tables with S3 clients
     * trusting what {@code storage}'s do, as a deployed worker's do ({@link Storage#clientProperties}).
     */
    Worker worker(Clock clock, Compactor compactor, Storage storage, TokenSource tokens, MeterRegistry meters,
                  CleanupWindow window, Duration orphanInterval, int maxJobs) {
        return new Worker(new Management(uri, tokens, http, 100), new Catalogs(uri, tokens,
                storage.clientProperties(), TraceHeaders.NONE), storage, compactor, clock, window, orphanInterval,
                Duration.ofHours(2), maxJobs, meters, ObservationRegistry.NOOP);
    }

    /**
     * A cleanup window open all day, each night ending as the next begins twelve hours from {@code clock}'s time of
     * day now: a test's scans, whole days apart or a few hours, fall in one night a day.
     */
    static CleanupWindow allDay(Clock clock) {
        LocalTime turn = LocalTime.ofInstant(clock.instant().minus(Duration.ofHours(12)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        return new CleanupWindow(turn, turn, ZoneOffset.UTC);
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

    /**
     * An Iceberg client as the writers' group, on vended credentials; its S3 clients trust the store's CA when it has
     * one, as the worker's do.
     */
    RESTCatalog writer() {
        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.URI, uri.toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, CATALOG);
        properties.put("rest.auth.type", BearerAuthManager.class.getName());
        properties.put(BearerAuthManager.SOURCE, BearerAuthManager.register(writerTokens(), TraceHeaders.NONE));
        properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("http-client.type", "apache");
        properties.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
        properties.putAll(TrustedS3Clients.register(storageProfiles()));
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

    /**
     * Ten rows in an Avro data file of the table, ids {@code index * 10} on, written with the table's vended
     * credentials (not yet committed): a file Spark can read and compact.
     */
    static DataFile avro(Table table, int index) throws IOException {
        DataWriter<Record> writer = Avro.writeData(table.io().newOutputFile(
                        table.locationProvider().newDataLocation("part-" + index + ".avro")))
                .schema(table.schema()).withSpec(table.spec())
                .createWriterFunc(org.apache.iceberg.data.avro.DataWriter::create).overwrite().build();
        try (writer) {
            for (long row = 0; row < 10; row++) {
                writer.write(GenericRecord.create(table.schema()).copy(Map.of("id", index * 10L + row,
                        "name", "row " + row)));
            }
        }
        return writer.toDataFile();
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

    /** The table's health as the catalog keeps it from the worker's reports (D38), read as a writer. */
    JsonNode health(TableIdentifier table) {
        return read(expect(manage(writerTokens(), "GET", path(table) + "/health", null), 200).body());
    }

    /** The worker's last scans as the catalog keeps them (D38), read as a platform operator. */
    JsonNode scans() {
        HttpRequest request = HttpRequest.newBuilder(uri.resolve("management/v1/maintenance/scans"))
                .header("Authorization", "Bearer " + operator().token()).GET().build();
        try {
            return read(expect(http.send(request, HttpResponse.BodyHandlers.ofString()), 200).body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The scan as the catalog keeps it from the worker's reports, or a missing node when it keeps none. */
    JsonNode scan(UUID id) {
        for (JsonNode scan : scans().path("scans")) {
            if (scan.path("id").asText().equals(id.toString())) {
                return scan;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    // ---- what the catalog and the store hold ----

    /**
     * The object's jobs of the night as the catalog's database holds them (backlog 126), by start: each as its kind,
     * slot and outcome, e.g. {@code COMPACTION 1 CUT_OFF}, a job with no slot showing none.
     */
    List<String> jobs(java.time.LocalDate night, UUID object) {
        return jdbc.query("SELECT kind, slot, outcome FROM maintenance_job WHERE night = ? AND object_id = ?"
                + " ORDER BY started_at, id", (row, n) -> row.getString("kind") + " "
                + (row.getObject("slot") == null ? "none" : row.getInt("slot")) + " " + row.getString("outcome"),
                night, object);
    }

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
