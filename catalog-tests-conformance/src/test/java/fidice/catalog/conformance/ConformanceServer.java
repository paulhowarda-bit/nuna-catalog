package fidice.catalog.conformance;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import fidice.catalog.security.TestIdp;
import fidice.catalog.server.CatalogServer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One catalog server on PostgreSQL and Silo (the maintained MinIO fork) for the whole test JVM, with
 * the RCK's warehouse seeded. Clients reach the same Silo through {@link #clientStorage()}, and authenticate with
 * {@link #token()}: a local test issuer's token for a group that holds create and modify on the warehouse (M5).
 */
final class ConformanceServer {

    /** The warehouse name the RCK client asks for by default. */
    static final String WAREHOUSE = "rck_warehouse";

    private static final String BUCKET = "warehouse";
    private static final String ACCESS_KEY = "fidicetest";
    private static final String SECRET_KEY = "fidicetest-secret-key";
    private static final String REGION = "us-east-1";
    private static final String ISSUER = "https://idp.test/conformance";
    private static final String AUDIENCE = "fidice-catalog";
    private static final String GROUP = "conformance-clients";

    private static URI uri;
    private static URI storage;
    private static String token;

    private ConformanceServer() {
    }

    static synchronized URI start() {
        if (uri != null) {
            return uri;
        }
        PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
        postgres.start();
        storage = startSilo();
        // Audit is on (M7): every load hands out credentials, and a credential issue needs Kafka's ack.
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
        kafka.start();
        TestIdp idp;
        RSAKey key;
        try {
            idp = new TestIdp(ISSUER, AUDIENCE);
            key = TestIdp.rsaKey("conformance");
            idp.publish(key);
            Instant now = Instant.now();
            token = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject("conformance").claim("groups", List.of(GROUP))
                    .claim("azp", "conformance-clients")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("the conformance token issuer did not start", e);
        }
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "fidice.storage.require-tls=false",
                        "fidice.storage.profiles.silo.kind=MINIO",
                        "fidice.storage.profiles.silo.endpoint=" + storage,
                        "fidice.storage.profiles.silo.region=" + REGION,
                        "fidice.storage.profiles.silo.table-root=s3://" + BUCKET + "/tables",
                        "fidice.storage.profiles.silo.allowed-prefixes=s3://" + BUCKET + "/tables",
                        "fidice.storage.profiles.silo.access-key-id=" + ACCESS_KEY,
                        "fidice.storage.profiles.silo.secret-access-key=" + SECRET_KEY,
                        "fidice.storage.catalogs." + WAREHOUSE + ".profile=silo",
                        "fidice.storage.catalogs." + WAREHOUSE + ".kms-key-set=test-keys",
                        "fidice.storage.catalogs." + WAREHOUSE + ".legal-entity=Test Entity",
                        "fidice.security.issuers[0].issuer=" + ISSUER,
                        "fidice.security.issuers[0].jwks-uri=" + idp.jwksUri(),
                        "fidice.security.issuers[0].audiences=" + AUDIENCE,
                        "fidice.security.issuers[0].algorithms=RS256",
                        "fidice.security.issuers[0].user-id-claim=sub",
                        "fidice.security.issuers[0].groups-claim=groups",
                        "fidice.security.issuers[0].client-id-claim=azp",
                        "fidice.audit.kafka-bootstrap-servers=" + kafka.getBootstrapServers())
                // A free port, as an argument: builder properties are only defaults, and application.yaml's 8181
                // would win over them, clashing with any catalog already running here (the demo).
                .run("--server.port=0");
        JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
        UUID catalog = jdbc.queryForObject("SELECT id FROM catalog WHERE name_norm = ?", UUID.class, WAREHOUSE);
        for (String privilege : List.of("create", "modify")) {
            jdbc.update("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) VALUES (?, 'CATALOG', ?, ?, ?, 'conformance', now())",
                    catalog, catalog, GROUP, privilege);
        }
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        uri = URI.create("http://localhost:" + port + "/");
        return uri;
    }

    /** The bearer token every conformance client sends (the Iceberg client's {@code token} property). */
    static String token() {
        start();
        return token;
    }

    /**
     * Catalog properties that give a client's S3FileIO access to the server's Silo. M4 vends no
     * credentials (that is M6), so clients bring their own.
     */
    static Map<String, String> clientStorage() {
        start();
        return Map.of(
                "io-impl", "org.apache.iceberg.aws.s3.S3FileIO",
                "s3.endpoint", storage.toString(),
                "s3.path-style-access", "true",
                "s3.access-key-id", ACCESS_KEY,
                "s3.secret-access-key", SECRET_KEY,
                "client.region", REGION,
                "http-client.type", "apache");
    }

    private static URI startSilo() {
        GenericContainer<?> silo = new GenericContainer<>(DockerImageName.parse(System.getProperty("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        silo.start();
        URI endpoint = URI.create("http://" + silo.getHost() + ":" + silo.getMappedPort(9000));
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        return endpoint;
    }
}
