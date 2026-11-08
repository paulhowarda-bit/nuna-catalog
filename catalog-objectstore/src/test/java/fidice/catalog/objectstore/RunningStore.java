package fidice.catalog.objectstore;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** An S3-compatible store in a container, and the credentials of a user with full access to it. */
record RunningStore(StoreKind kind, URI endpoint, AwsCredentialsProvider credentials, GenericContainer<?> container)
        implements AutoCloseable {

    private static final String ACCESS_KEY = "fidicetest";
    private static final String SECRET_KEY = "fidicetest-secret-key";
    private static final AwsCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));

    /**
     * Ceph RGW in one container (mon, mgr, osd, rgw), with an S3 user created at startup. The image
     * sets {@code rgw dns name = demo}; RGW then reads any other host name as a bucket (CNAME
     * hosting), so the DNS name is set to the host the tests connect through.
     */
    static RunningStore ceph() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(System.getProperty("ceph.image")))
                .withEnv("CEPH_DEMO_ACCESS_KEY", ACCESS_KEY)
                .withEnv("CEPH_DEMO_SECRET_KEY", SECRET_KEY)
                .withEnv("CEPH_EXTRA_CONF", "rgw dns name = " + DockerClientFactory.instance().dockerHostIpAddress())
                .withExposedPorts(8080)
                .waitingFor(Wait.forLogMessage(".*=== ceph-test setup complete ===.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(6)));
        container.start();
        return new RunningStore(StoreKind.CEPH, endpoint(container, 8080), CREDENTIALS, container);
    }

    /** Silo, the maintained MinIO fork, run as its root user. */
    static RunningStore silo() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(System.getProperty("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        return new RunningStore(StoreKind.MINIO, endpoint(container, 9000), CREDENTIALS, container);
    }

    @Override
    public void close() {
        container.stop();
    }

    private static URI endpoint(GenericContainer<?> container, int port) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port));
    }
}
