package fidice.catalog.objectstore;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

/**
 * An S3-compatible store, the credentials of a user with full access to it, the role that user may assume
 * through STS if the store needs one, the two buckets the gate uses, and how to clean up afterwards.
 */
record RunningStore(StoreKind kind, URI endpoint, AwsCredentialsProvider credentials, Optional<String> roleArn,
                    String bucket, String otherBucket, AutoCloseable cleanup) implements AutoCloseable {

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
                Optional.of("arn:aws:iam:::role/" + CEPH_ROLE), "lake", "lake2", container::stop);
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
        return new RunningStore(StoreKind.MINIO, endpoint(container, 9000), CREDENTIALS, Optional.empty(), "lake",
                "lake2", container::stop);
    }

    /**
     * A real AWS account, signed in through the named CLI profile (short-lived credentials from an
     * {@code aws login} session, which the SDK gets through the profile's {@code credential_process}; nothing
     * here reads, prints or stores them). Creates a role
     * whose own policy allows every S3 action on the gate's two buckets and trusts the account to assume
     * and tag it, waits until STS accepts it, and on close empties and deletes the buckets and the role.
     * Synthetic data only.
     */
    static RunningStore aws(String profile) {
        AwsCredentialsProvider credentials = ProfileCredentialsProvider.builder().profileName(profile).build();
        String account;
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            account = sts.getCallerIdentity().account();
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "fidice-m6-gate-" + suffix;
        String otherBucket = bucket + "-other";
        String role = "fidice-m6-gate-" + suffix;
        IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(credentials).build();
        String roleArn = iam.createRole(r -> r.roleName(role).maxSessionDuration(3600)
                .description("fidice M6 vending gate; deleted when the test ends")
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                        + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}")).role().arn();
        iam.putRolePolicy(r -> r.roleName(role).policyName("everything-in-the-gate-buckets")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:*\",\"Resource\":[\"arn:aws:s3:::" + bucket + "\",\"arn:aws:s3:::" + bucket
                        + "/*\",\"arn:aws:s3:::" + otherBucket + "\",\"arn:aws:s3:::" + otherBucket + "/*\"]}]}"));
        awaitAssumable(credentials, roleArn);
        URI endpoint = URI.create("https://s3.us-east-1.amazonaws.com");
        AutoCloseable cleanup = () -> {
            try (S3Client s3 = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
                for (String name : List.of(bucket, otherBucket)) {
                    empty(s3, name);
                }
            } finally {
                iam.deleteRolePolicy(r -> r.roleName(role).policyName("everything-in-the-gate-buckets"));
                iam.deleteRole(r -> r.roleName(role));
                iam.close();
            }
        };
        return new RunningStore(StoreKind.S3, endpoint, credentials, Optional.of(roleArn), bucket, otherBucket,
                cleanup);
    }

    @Override
    public void close() {
        try {
            cleanup.close();
        } catch (Exception e) {
            throw new IllegalStateException("cleanup failed", e);
        }
    }

    /** IAM is eventually consistent: a new role can be refused for some seconds. */
    private static void awaitAssumable(AwsCredentialsProvider credentials, String roleArn) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            while (true) {
                try {
                    sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("fidice-gate-probe").durationSeconds(900));
                    return;
                } catch (StsException e) {
                    if (Instant.now().isAfter(deadline)) {
                        throw e;
                    }
                    Thread.sleep(5_000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Deletes every object in the bucket, then the bucket; a bucket that was never created is skipped. */
    private static void empty(S3Client s3, String bucket) {
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
            return;
        }
        List<ObjectIdentifier> keys = s3.listObjectsV2Paginator(r -> r.bucket(bucket)).contents().stream()
                .map(S3Object::key).map(key -> ObjectIdentifier.builder().key(key).build()).toList();
        for (int i = 0; i < keys.size(); i += 1000) {
            List<ObjectIdentifier> batch = keys.subList(i, Math.min(keys.size(), i + 1000));
            s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(batch)));
        }
        s3.listMultipartUploadsPaginator(r -> r.bucket(bucket)).uploads().forEach(upload ->
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(upload.key()).uploadId(upload.uploadId())));
        s3.deleteBucket(r -> r.bucket(bucket));
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
