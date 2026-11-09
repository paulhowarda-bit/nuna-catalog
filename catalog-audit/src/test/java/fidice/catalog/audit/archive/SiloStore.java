package fidice.catalog.audit.archive;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Silo, the maintained MinIO fork the repo tests against (R4), in a container; the image is pinned in the parent POM. */
final class SiloStore implements AutoCloseable {

    private final GenericContainer<?> container;
    final S3Client s3;

    SiloStore() {
        container = new GenericContainer<>(DockerImageName.parse(System.getProperty("silo.image")))
                .withEnv("MINIO_ROOT_USER", "fidicetest")
                .withEnv("MINIO_ROOT_PASSWORD", "fidicetest-secret-key")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fidicetest", "fidicetest-secret-key")))
                .build();
    }

    /** A new bucket; Object Lock can only be turned on when a bucket is created. */
    String newBucket(boolean objectLock) {
        String bucket = "archive-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(objectLock));
        return bucket;
    }

    @Override
    public void close() {
        s3.close();
        container.stop();
    }
}
