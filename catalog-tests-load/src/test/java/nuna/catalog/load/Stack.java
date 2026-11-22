package nuna.catalog.load;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.security.TestIdp;
import nuna.catalog.tests.kafka.TestKafka;
import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Everything a catalog server needs besides its database: Kafka for audit (a load that vends credentials
 * waits for Kafka's ack), the run's S3 store ({@link TestS3}) for metadata files, and a token issuer. Servers run as separate processes
 * ({@link ServerProcess}) from the runnable jar, as they are deployed. A stack with a DR standby also archives audit
 * (D23) in an Object Lock bucket of that store, which its servers lock in COMPLIANCE mode, as they always do: the DR
 * runbook's reconcile reads it (backlog 10). A stack with a DR store ({@link #withDrStore}) replicates its bucket there
 * instead, and runs the maintenance worker's DR verifier ({@link #worker}).
 */
final class Stack implements AutoCloseable {

    static final String CATALOG = "m8";
    static final String WRITERS = "m8-writers";
    static final String OPERATORS = "platform-ops";
    /** The maintenance worker's principal (D30), which every server of a stack with a DR store names as one. */
    static final String WORKER = "nuna-maintenance";
    /** Numbers each stack's bucket: the run's S3 store is shared, and a DR drill must see only its own tables. */
    private static final AtomicInteger STACKS = new AtomicInteger();
    private static final String PROFILE = "s3";
    private static final String ISSUER = "https://idp.test/m8";
    private static final String AUDIENCE = "nuna-catalog";
    /** The DR store's read-only user, as whom the verifier reads it (D37: a replica's keys may only read there). */
    private static final String DR_READER = "nuna-dr-reader";
    private static final String DR_READER_SECRET = "nuna-dr-reader-secret";

    final PostgresCluster postgres;
    private final KafkaContainer kafka;
    private final TestS3 s3;
    private final String bucket;
    /** The audit archive's Object Lock bucket, in a stack with a DR standby; null otherwise. */
    private final String archiveBucket;
    /** The DR region's store, holding what the {@link #replicator} copied of the bucket; null in other stacks. */
    private final TestS3 drStore;
    private final Replicator replicator;
    /** Clients of the run's store and of the DR store, for writers' files. */
    private final S3Client files;
    private final S3Client drFiles;
    private final TestIdp idp;
    private final RSAKey key;
    private final List<ServerProcess> servers = new ArrayList<>();

    /** @param withDr a DR standby, and the audit archive the DR runbook's reconcile reads */
    Stack(boolean withDr) {
        this(withDr, withDr, null);
    }

    /**
     * A stack whose storage replicates to the DR region's own store, Silo beside the run's, through a replicator the
     * test pauses (backlog 92): its database has a DR standby, its servers archive no audit and take the maintenance
     * worker as a maintenance principal, and the DR store has the stack's bucket and a user that may only read it.
     */
    static Stack withDrStore() {
        return new Stack(true, false, TestS3.siloOfItsOwn());
    }

    private Stack(boolean withDr, boolean archive, TestS3 drStore) {
        postgres = new PostgresCluster(withDr);
        kafka = TestKafka.container("384m");
        kafka.start();
        s3 = TestS3.get();
        int stack = STACKS.incrementAndGet();
        bucket = s3.bucket("warehouse-" + stack);
        archiveBucket = archive ? s3.bucket("audit-archive-" + stack, true) : null;
        files = s3.client();
        this.drStore = drStore;
        if (drStore != null) {
            drStore.bucket(bucket);
            drStore.siloUser(DR_READER, DR_READER_SECRET, "dr-read", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject","s3:ListBucket"],
                     "Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}]}""".formatted(bucket, bucket));
            drFiles = drStore.client();
            replicator = new Replicator(s3.client(), drStore.client(), bucket);
        } else {
            drFiles = null;
            replicator = null;
        }
        try {
            idp = new TestIdp(ISSUER, AUDIENCE);
            key = TestIdp.rsaKey("m8");
            idp.publish(key);
        } catch (Exception e) {
            throw new IllegalStateException("the token issuer did not start", e);
        }
    }

    /** A client of the run's store, where the servers of the primary region keep the tables. */
    S3Client files() {
        return files;
    }

    /** A client of the DR store, where the servers of the DR region keep the tables. */
    S3Client drFiles() {
        return drFiles;
    }

    /** Copies the bucket to the DR store until paused. */
    Replicator replicator() {
        return replicator;
    }

    /** A bearer token valid for six hours. */
    String token(String subject, String group) {
        Instant now = Instant.now();
        try {
            return TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject(subject).claim("groups", List.of(group))
                    .claim("azp", "m8-load")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A server of this release on the cluster's primary. */
    ServerProcess server(String name) {
        return server(name, Path.of(System.getProperty("m8.server-jar")), postgres.serverUrl());
    }

    /**
     * A server from {@code jar} on {@code jdbcUrl}; the first one to start creates the catalog. {@code more} are more
     * of its settings, as {@code --name=value}.
     */
    ServerProcess server(String name, Path jar, String jdbcUrl, String... more) {
        return start(name, jar, jdbcUrl, s3, more);
    }

    /**
     * A server of this release in the DR region: on the promoted DR database and the DR store. The promoted database
     * has no synchronous standby, so its self-check reports pg.no-sync-standby, and a server refuses to start there
     * unless that finding is allowed (D8.2).
     */
    ServerProcess drServer(String name) {
        return start(name, Path.of(System.getProperty("m8.server-jar")), postgres.drServerUrl(), drStore,
                "--nuna.database.allow-unsafe=pg.no-sync-standby");
    }

    private ServerProcess start(String name, Path jar, String jdbcUrl, TestS3 store, String... more) {
        List<String> properties = new ArrayList<>();
        store.serverProperties(PROFILE, bucket).forEach((key, value) -> properties.add("--" + key + "=" + value));
        properties.addAll(List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + PostgresCluster.USER,
                "--spring.datasource.password=" + PostgresCluster.USER,
                "--spring.datasource.hikari.maximum-pool-size=40",
                "--nuna.storage.catalogs." + CATALOG + ".profile=" + PROFILE,
                "--nuna.storage.catalogs." + CATALOG + ".kms-key-set=test-keys",
                "--nuna.storage.catalogs." + CATALOG + ".legal-entity=Test Entity",
                "--nuna.security.issuers[0].issuer=" + ISSUER,
                "--nuna.security.issuers[0].jwks-uri=" + idp.jwksUri(),
                "--nuna.security.issuers[0].audiences=" + AUDIENCE,
                "--nuna.security.issuers[0].algorithms=RS256",
                "--nuna.security.issuers[0].user-id-claim=sub",
                "--nuna.security.issuers[0].groups-claim=groups",
                "--nuna.security.issuers[0].client-id-claim=azp",
                "--nuna.security.platform-operator-groups=" + OPERATORS,
                "--nuna.audit.kafka-bootstrap-servers=" + kafka.getBootstrapServers(),
                "--logging.level.root=WARN",
                "--logging.level.nuna.audit.fallback=ERROR"));
        if (archiveBucket != null) {
            // Segments written within a second of their first record, so the archive is current when the drill reads it.
            properties.addAll(List.of("--nuna.audit.archive.bucket=" + archiveBucket,
                    "--nuna.audit.archive.profile=" + PROFILE, "--nuna.audit.archive.segment-age=1s"));
        }
        if (drStore != null) {
            properties.add("--nuna.security.maintenance-principals=" + WORKER);
        }
        properties.addAll(List.of(more));
        ServerProcess server = ServerProcess.start(name, jar, store.jvmOptions(), properties);
        servers.add(server);
        server.awaitReady(token("m8-writer", WRITERS), CATALOG);
        return server;
    }

    /**
     * The maintenance worker (D30) of a stack with a DR store, from its runnable jar, signed in to {@code catalog} as
     * {@value #WORKER} with a token file: its own storage the run's store ({@code inDr} false) or the DR store, and the
     * DR store, read as the user that may only read it, the replica its DR verifier checks
     * ({@code nuna.maintenance.dr.replicas}), every second (D37). Its scans find nothing to do, since the catalog's
     * maintenance policy runs nothing by default, and its cleanup window is twelve hours away. It logs each DR pass
     * ({@code DR pass done}, at INFO) and, at DEBUG, what each check found missing to {@link ServerProcess#log}.
     */
    ServerProcess worker(String name, URI catalog, boolean inDr) {
        TestS3 storage = inDr ? drStore : s3;
        Path tokenFile = Path.of(System.getProperty("m8.work"), name + ".token");
        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, token(WORKER, "m8-maintenance"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LocalTime night = LocalTime.now(ZoneOffset.UTC).plusHours(12).truncatedTo(ChronoUnit.MINUTES);
        String own = "--nuna.maintenance.storage." + PROFILE + ".";
        String replica = "--nuna.maintenance.dr.replicas." + PROFILE + ".";
        List<String> properties = new ArrayList<>(List.of(
                "--nuna.maintenance.catalog-uri=" + catalog.resolve("/"),
                "--nuna.maintenance.token.file=" + tokenFile,
                "--nuna.maintenance.cleanup-window.start=" + night,
                "--nuna.maintenance.cleanup-window.end=" + night.plusMinutes(1),
                "--nuna.maintenance.cleanup-window.zone=UTC",
                "--nuna.maintenance.scan-interval=PT1M",
                own + "prefixes[0]=s3://" + bucket + "/tables",
                own + "endpoint=" + storage.endpoint,
                own + "region=" + TestS3.REGION,
                own + "path-style-access=true",
                "--nuna.maintenance.dr.verify-interval=PT1S",
                replica + "endpoint=" + drStore.endpoint,
                replica + "region=" + TestS3.REGION,
                replica + "path-style-access=true",
                replica + "access-key-id=" + DR_READER,
                replica + "secret-access-key=" + DR_READER_SECRET,
                "--logging.level.nuna.maintenance=DEBUG"));
        storage.keys().ifPresent(keys -> properties.addAll(List.of(own + "access-key-id=" + keys.accessKeyId(),
                own + "secret-access-key=" + keys.secretAccessKey())));
        ServerProcess worker = ServerProcess.start(name, Path.of(System.getProperty("m8.worker-jar")),
                storage.jvmOptions(), properties);
        servers.add(worker);
        return worker;
    }

    /** Lets the writers' group create and commit anywhere in the catalog. */
    void grantWriters() {
        String catalog = "(SELECT id FROM catalog WHERE name_norm = '" + CATALOG + "')";
        for (String privilege : List.of("create", "modify", "write", "select")) {
            postgres.execute("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) SELECT " + catalog + ", 'CATALOG', " + catalog + ", '" + WRITERS + "', '"
                    + privilege + "', 'm8', now() WHERE NOT EXISTS (SELECT 1 FROM grant_record WHERE group_name = '"
                    + WRITERS + "' AND privilege = '" + privilege + "')");
        }
    }

    @Override
    public void close() {
        servers.forEach(ServerProcess::close);
        files.close();
        if (drStore != null) {
            try {
                replicator.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drFiles.close();
            drStore.close();
        }
        idp.close();
        kafka.stop();
        postgres.close();
    }
}
