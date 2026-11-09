package fidice.catalog.conformance;

import fidice.catalog.server.CatalogServer;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One catalog server on PostgreSQL and Silo (the maintained MinIO fork) for the whole test JVM, with
 * the RCK's warehouse seeded. Clients reach the same Silo through {@link #clientStorage()}.
 */
final class ConformanceServer {

    /** The warehouse name the RCK client asks for by default. */
    static final String WAREHOUSE = "rck_warehouse";

    private static final String BUCKET = "warehouse";
    private static final String ACCESS_KEY = "fidicetest";
    private static final String SECRET_KEY = "fidicetest-secret-key";
    private static final String REGION = "us-east-1";

    private static URI uri;
    private static URI storage;

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
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "fidice.storage.kind=MINIO",
                        "fidice.storage.endpoint=" + storage,
                        "fidice.storage.region=" + REGION,
                        "fidice.storage.table-root=s3://" + BUCKET + "/tables",
                        "fidice.storage.allowed-prefixes=s3://" + BUCKET + "/tables",
                        "fidice.storage.access-key-id=" + ACCESS_KEY,
                        "fidice.storage.secret-access-key=" + SECRET_KEY)
                .run();
        server.getBean(JdbcTemplate.class).update(
                "INSERT INTO catalog (id, name_norm, name) VALUES (?, ?, ?)", UUID.randomUUID(), WAREHOUSE, WAREHOUSE);
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        uri = URI.create("http://localhost:" + port + "/");
        return uri;
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
