package fidice.catalog.load;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import fidice.catalog.security.TestIdp;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Everything a catalog server needs besides its database: Kafka for audit (a load that vends credentials
 * waits for Kafka's ack), Silo for metadata files, and a token issuer. Servers run as separate processes
 * ({@link ServerProcess}) from the runnable jar, as they are deployed.
 */
final class Stack implements AutoCloseable {

    static final String CATALOG = "m8";
    static final String WRITERS = "m8-writers";
    static final String OPERATORS = "platform-ops";
    static final String BUCKET = "warehouse";
    static final String ACCESS_KEY = "fidicetest";
    static final String SECRET_KEY = "fidicetest-secret-key";
    static final String REGION = "us-east-1";
    private static final String ISSUER = "https://idp.test/m8";
    private static final String AUDIENCE = "fidice-catalog";

    final PostgresCluster postgres;
    private final KafkaContainer kafka;
    private final GenericContainer<?> silo;
    private final TestIdp idp;
    private final RSAKey key;
    private final List<ServerProcess> servers = new ArrayList<>();
    final URI siloEndpoint;

    Stack(boolean withDr) {
        postgres = new PostgresCluster(withDr);
        kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m");
        kafka.start();
        silo = new GenericContainer<>(DockerImageName.parse(System.getProperty("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
        silo.start();
        siloEndpoint = URI.create("http://" + silo.getHost() + ":" + silo.getMappedPort(9000));
        try (S3Client s3 = s3()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        try {
            idp = new TestIdp(ISSUER, AUDIENCE);
            key = TestIdp.rsaKey("m8");
            idp.publish(key);
        } catch (Exception e) {
            throw new IllegalStateException("the token issuer did not start", e);
        }
    }

    S3Client s3() {
        return S3Client.builder()
                .endpointOverride(siloEndpoint)
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
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

    /** A server from {@code jar} on {@code jdbcUrl}; the first one to start creates the catalog. */
    ServerProcess server(String name, Path jar, String jdbcUrl) {
        ServerProcess server = ServerProcess.start(name, jar, List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + PostgresCluster.USER,
                "--spring.datasource.password=" + PostgresCluster.USER,
                "--spring.datasource.hikari.maximum-pool-size=40",
                "--fidice.storage.require-tls=false",
                "--fidice.storage.profiles.silo.kind=MINIO",
                "--fidice.storage.profiles.silo.endpoint=" + siloEndpoint,
                "--fidice.storage.profiles.silo.region=" + REGION,
                "--fidice.storage.profiles.silo.table-root=s3://" + BUCKET + "/tables",
                "--fidice.storage.profiles.silo.allowed-prefixes=s3://" + BUCKET + "/tables",
                "--fidice.storage.profiles.silo.access-key-id=" + ACCESS_KEY,
                "--fidice.storage.profiles.silo.secret-access-key=" + SECRET_KEY,
                "--fidice.storage.catalogs." + CATALOG + ".profile=silo",
                "--fidice.storage.catalogs." + CATALOG + ".kms-key-set=test-keys",
                "--fidice.storage.catalogs." + CATALOG + ".legal-entity=Test Entity",
                "--fidice.security.issuers[0].issuer=" + ISSUER,
                "--fidice.security.issuers[0].jwks-uri=" + idp.jwksUri(),
                "--fidice.security.issuers[0].audiences=" + AUDIENCE,
                "--fidice.security.issuers[0].algorithms=RS256",
                "--fidice.security.issuers[0].user-id-claim=sub",
                "--fidice.security.issuers[0].groups-claim=groups",
                "--fidice.security.issuers[0].client-id-claim=azp",
                "--fidice.security.platform-operator-groups=" + OPERATORS,
                "--fidice.audit.kafka-bootstrap-servers=" + kafka.getBootstrapServers(),
                "--logging.level.root=WARN",
                "--logging.level.fidice.audit.fallback=ERROR"));
        servers.add(server);
        server.awaitReady(token("m8-writer", WRITERS), CATALOG);
        return server;
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
        idp.close();
        silo.stop();
        kafka.stop();
        postgres.close();
    }
}
