package fidice.catalog.objectstore;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * An S3-compatible store in a container, the credentials of a user with full access to it, and the role
 * that user may assume through STS, if the store needs one.
 */
record RunningStore(StoreKind kind, URI endpoint, AwsCredentialsProvider credentials, Optional<String> roleArn,
                    GenericContainer<?> container) implements AutoCloseable {

    private static final String ACCESS_KEY = "fidicetest";
    private static final String SECRET_KEY = "fidicetest-secret-key";
    /** RGW's key for signing session tokens: 16 characters. */
    private static final String CEPH_STS_KEY = "fidicestskey0123";
    static final String CEPH_ROLE = "fidice-vending";
    private static final AwsCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));

    /**
     * Ceph RGW in one container (mon, mgr, osd, rgw), with an S3 user created at startup. The image
     * sets {@code rgw dns name = demo}; RGW then reads any other host name as a bucket (CNAME
     * hosting), so the DNS name is set to the host the tests connect through. STS is on, and the
     * user may assume {@link #CEPH_ROLE}, whose own policy allows every S3 action on every bucket:
     * whatever confines vended credentials is their session policy.
     */
    static RunningStore ceph() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(System.getProperty("ceph.image")))
                .withEnv("CEPH_DEMO_ACCESS_KEY", ACCESS_KEY)
                .withEnv("CEPH_DEMO_SECRET_KEY", SECRET_KEY)
                .withEnv("CEPH_EXTRA_CONF", String.join("\n",
                        "rgw dns name = " + DockerClientFactory.instance().dockerHostIpAddress(),
                        "rgw sts key = " + CEPH_STS_KEY,
                        "rgw s3 auth use sts = true"))
                .withExposedPorts(8080)
                .waitingFor(Wait.forLogMessage(".*=== ceph-test setup complete ===.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(6)));
        container.start();
        admin(container, "role", "create", "--role-name=" + CEPH_ROLE, "--path=/", "--assume-role-policy-doc="
                + "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":[\"arn:aws:iam:::user/demo\"]},"
                + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}");
        admin(container, "role-policy", "put", "--role-name=" + CEPH_ROLE, "--policy-name=everything",
                "--policy-doc={\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":[\"s3:*\"],\"Resource\":\"arn:aws:s3:::*\"}]}");
        return new RunningStore(StoreKind.CEPH, endpoint(container, 8080), CREDENTIALS,
                Optional.of("arn:aws:iam:::role/" + CEPH_ROLE), container);
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
        return new RunningStore(StoreKind.MINIO, endpoint(container, 9000), CREDENTIALS, Optional.empty(), container);
    }

    @Override
    public void close() {
        container.stop();
    }

    /** Runs {@code radosgw-admin} in the container and fails on a non-zero exit. */
    private static void admin(GenericContainer<?> container, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "radosgw-admin";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            var result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", args) + " failed: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI endpoint(GenericContainer<?> container, int port) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port));
    }
}
