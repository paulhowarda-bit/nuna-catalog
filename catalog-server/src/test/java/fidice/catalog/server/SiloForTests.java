package fidice.catalog.server;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Silo (the maintained MinIO fork) for server tests, with the bucket tables live in; the image is pinned in the parent POM. */
@TestConfiguration(proxyBeanMethods = false)
public class SiloForTests {

    public static final String BUCKET = "warehouse";
    public static final String ACCESS_KEY = "fidicetest";
    public static final String SECRET_KEY = "fidicetest-secret-key";

    /** The running store and its endpoint. */
    public record Silo(GenericContainer<?> container, URI endpoint) implements AutoCloseable {

        @Override
        public void close() {
            container.stop();
        }
    }

    @Bean
    Silo silo() {
        return start();
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(Silo silo) {
        return registry -> {
            registry.add("fidice.storage.kind", () -> "MINIO");
            registry.add("fidice.storage.endpoint", () -> silo.endpoint().toString());
            registry.add("fidice.storage.region", () -> "us-east-1");
            registry.add("fidice.storage.table-root", () -> "s3://" + BUCKET + "/tables");
            registry.add("fidice.storage.allowed-prefixes", () -> "s3://" + BUCKET + "/tables");
            registry.add("fidice.storage.access-key-id", () -> ACCESS_KEY);
            registry.add("fidice.storage.secret-access-key", () -> SECRET_KEY);
        };
    }

    /** Starts Silo with {@link #BUCKET} created. */
    public static Silo start() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(System.getProperty("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        URI endpoint = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9000));
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        return new Silo(container, endpoint);
    }
}
