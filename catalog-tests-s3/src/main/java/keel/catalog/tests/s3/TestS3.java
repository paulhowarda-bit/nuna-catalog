package keel.catalog.tests.s3;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

/**
 * The one S3 store every test uses, chosen by the system property {@code s3}: {@code silo} (the default),
 * {@code ceph} or {@code aws}. One store per test JVM, started on first use and removed when the JVM exits.
 *
 * <ul>
 *   <li><b>silo</b>: Silo, the maintained MinIO fork (research F5), as its root user. MinIO's STS model:
 *       sessions derive from the calling user, so there is no role.</li>
 *   <li><b>ceph</b>: Ceph RGW in one container (research F7), with STS on and a role
 *       {@value #CEPH_ROLE_ARN} that allows every S3 action: whatever confines vended credentials is their
 *       session policy. A second user, {@value #MAINTENANCE_USER}, is the maintenance worker's own identity
 *       (D30).</li>
 *   <li><b>aws</b>: real AWS S3 and STS in us-east-1, signed in through the CLI profile named by
 *       {@code keel.aws.profile} (short-lived credentials; nothing here reads, prints or stores them).
 *       Creates one role allowing every S3 action on this run's buckets, and on exit deletes every
 *       bucket it made and the role. Synthetic data only.</li>
 * </ul>
 */
public final class TestS3 implements AutoCloseable {

    /** The stores a run can use. */
    public enum Kind {
        SILO,
        CEPH,
        AWS
    }

    public static final String REGION = "us-east-1";
    /** The keys of Silo's root user and Ceph's S3 user. */
    public static final String ACCESS_KEY = "keeltest";
    public static final String SECRET_KEY = "keeltest-secret-key";
    /** The role vended credentials assume on Ceph. */
    public static final String CEPH_ROLE_ARN = "arn:aws:iam:::role/keel-vending";
    private static final String CEPH_ROLE = "keel-vending";
    /** RGW's key for signing session tokens: 16 characters. */
    private static final String CEPH_STS_KEY = "keelstskey012345";
    /** The maintenance worker's own storage identity (D30): a user on Ceph, whose keys these are. */
    public static final String MAINTENANCE_USER = "keel-maintenance";
    public static final String MAINTENANCE_ACCESS_KEY = "keelmaintenance";
    public static final String MAINTENANCE_SECRET_KEY = "keelmaintenance-secret-key";

    private static TestS3 shared;

    public final Kind kind;
    /** The S3 (and STS) endpoint. */
    public final URI endpoint;
    /** Full access to the store. */
    public final AwsCredentialsProvider credentials;
    /** The role vended credentials assume: {@value #CEPH_ROLE_ARN} on Ceph, this run's role on AWS; none on Silo. */
    public final Optional<String> roleArn;
    /** The static keys behind {@link #credentials} on Silo and Ceph; none on AWS. */
    private final Optional<AwsBasicCredentials> keys;
    /** On AWS, this run's bucket-name prefix; empty elsewhere. */
    private final String bucketPrefix;
    private final Optional<String> awsProfile;
    private final AutoCloseable stop;
    private final Set<String> buckets = new LinkedHashSet<>();
    private boolean closed;

    private TestS3(Kind kind, URI endpoint, AwsCredentialsProvider credentials, Optional<AwsBasicCredentials> keys,
                   Optional<String> roleArn, String bucketPrefix, Optional<String> awsProfile, AutoCloseable stop) {
        this.kind = kind;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.keys = keys;
        this.roleArn = roleArn;
        this.bucketPrefix = bucketPrefix;
        this.awsProfile = awsProfile;
        this.stop = stop;
    }

    /** The store for this run, started on first call. */
    public static synchronized TestS3 get() {
        if (shared == null) {
            shared = start(kindFromSystemProperty());
            Runtime.getRuntime().addShutdownHook(new Thread(shared::close, "test-s3-stop"));
        }
        return shared;
    }

    /** The kind {@code -Ps3} names, without starting anything. */
    public static Kind kindFromSystemProperty() {
        String name = System.getProperty("s3", "silo").trim();
        try {
            return Kind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("-Ps3=" + name + " is not one of silo, ceph, aws", e);
        }
    }

    private static TestS3 start(Kind kind) {
        return switch (kind) {
            case SILO -> silo();
            case CEPH -> ceph();
            case AWS -> aws();
        };
    }

    /**
     * A bucket for this run, created on first request. Silo and Ceph use the name as given; AWS prefixes
     * it with {@code keel-test-<run id>-}, since bucket names are global.
     */
    public String bucket(String name) {
        return bucket(name, false);
    }

    /** As {@link #bucket(String)}; Object Lock can be turned on only when a bucket is created. */
    public synchronized String bucket(String name, boolean objectLock) {
        String bucket = bucketPrefix + name;
        if (buckets.add(bucket)) {
            try (S3Client s3 = client()) {
                s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(objectLock ? true : null));
            }
        }
        return bucket;
    }

    /** A client with full access, addressing buckets by path (AWS still accepts that in us-east-1). */
    public S3Client client() {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(credentials)
                .build();
    }

    /** What a server's storage profile calls this store ({@code StoreKind}): MINIO, CEPH or S3. */
    public String profileKind() {
        return switch (kind) {
            case SILO -> "MINIO";
            case CEPH -> "CEPH";
            case AWS -> "S3";
        };
    }

    /**
     * The maintenance worker's storage identity as a storage profile names it (D30): user {@value #MAINTENANCE_USER}
     * on Silo and Ceph, a role of that name on AWS. Only Ceph's exists: Silo runs as its root user, whom no policy
     * binds, and the AWS run makes no second role.
     */
    public String maintenancePrincipal() {
        return switch (kind) {
            case SILO -> MAINTENANCE_USER;
            case CEPH -> "arn:aws:iam:::user/" + MAINTENANCE_USER;
            case AWS -> roleArn.orElseThrow().replaceFirst(":role/.*$", ":role/" + MAINTENANCE_USER);
        };
    }

    /** The static access key on Silo and Ceph, for clients that cannot take a credentials provider. */
    public Optional<AwsBasicCredentials> keys() {
        return keys;
    }

    /**
     * Settings that make a catalog server use this store as storage profile {@code profile}, with tables
     * under {@code s3://<bucket>/tables}: kind, endpoint, region, table root, allowed prefixes, and the
     * catalog's keys (Silo, Ceph) or the AWS SDK's default chain (AWS), plus the STS role where there is one
     * and the maintenance worker's {@link #maintenancePrincipal()}.
     * Plain http is allowed, since the containers serve no TLS.
     */
    public Map<String, String> serverProperties(String profile, String bucket) {
        String p = "keel.storage.profiles." + profile + ".";
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("keel.storage.require-tls", "false");
        properties.put(p + "kind", profileKind());
        properties.put(p + "endpoint", endpoint.toString());
        properties.put(p + "region", REGION);
        properties.put(p + "table-root", "s3://" + bucket + "/tables");
        properties.put(p + "allowed-prefixes", "s3://" + bucket + "/tables");
        keys.ifPresent(k -> {
            properties.put(p + "access-key-id", k.accessKeyId());
            properties.put(p + "secret-access-key", k.secretAccessKey());
        });
        roleArn.ifPresent(role -> properties.put(p + "sts-role", role));
        properties.put(p + "maintenance-principal", maintenancePrincipal());
        return properties;
    }

    /** JVM options a server in its own process needs to reach the store: on AWS, the CLI profile. */
    public List<String> jvmOptions() {
        return awsProfile.map(profile -> List.of("-Daws.profile=" + profile)).orElse(List.of());
    }

    /** Silo and Ceph: stops the container. AWS: empties and deletes every bucket this run made, then the role. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stop.close();
        } catch (Exception e) {
            throw new IllegalStateException("the " + kind + " store did not stop cleanly", e);
        }
    }

    private static TestS3 silo() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        AwsBasicCredentials keys = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        return new TestS3(Kind.SILO, endpoint(container, 9000), StaticCredentialsProvider.create(keys),
                Optional.of(keys), Optional.empty(), "", Optional.empty(), container::stop);
    }

    /**
     * Ceph RGW in one container (mon, mgr, osd, rgw), with an S3 user created at startup. The image sets
     * {@code rgw dns name = demo}; RGW then reads any other host name as a bucket (CNAME hosting), so the
     * DNS name is set to the host the tests connect through.
     */
    private static TestS3 ceph() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image("ceph.image")))
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
        for (List<String> command : cephRoleCommands()) {
            admin(container, command.subList(1, command.size()).toArray(String[]::new));
        }
        admin(container, "user", "create", "--uid=" + MAINTENANCE_USER, "--display-name=keel maintenance worker",
                "--access-key=" + MAINTENANCE_ACCESS_KEY, "--secret-key=" + MAINTENANCE_SECRET_KEY);
        AwsBasicCredentials keys = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        return new TestS3(Kind.CEPH, endpoint(container, 8080), StaticCredentialsProvider.create(keys),
                Optional.of(keys), Optional.of(CEPH_ROLE_ARN), "", Optional.empty(), container::stop);
    }

    /**
     * The {@code radosgw-admin} commands that create {@link #CEPH_ROLE_ARN} in a ceph-test container: user
     * {@code demo} (whose keys are {@link #ACCESS_KEY}) may assume and tag it, and its own policy allows every
     * S3 action, so whatever confines vended credentials is their session policy.
     */
    public static List<List<String>> cephRoleCommands() {
        return List.of(
                List.of("radosgw-admin", "role", "create", "--role-name=" + CEPH_ROLE, "--path=/",
                        "--assume-role-policy-doc={\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":{\"AWS\":[\"arn:aws:iam:::user/demo\"]},"
                                + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}"),
                List.of("radosgw-admin", "role-policy", "put", "--role-name=" + CEPH_ROLE, "--policy-name=everything",
                        "--policy-doc={\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Action\":[\"s3:*\"],\"Resource\":\"arn:aws:s3:::*\"}]}"));
    }

    /**
     * Real AWS, signed in through {@code keel.aws.profile}. Creates a role whose own policy allows every
     * S3 action on {@code keel-test-<run id>-*} and trusts the account to assume and tag it, and waits
     * until STS accepts it. In-process servers find the profile through {@code aws.profile}.
     */
    private static TestS3 aws() {
        String profile = System.getProperty("keel.aws.profile", "").trim();
        if (profile.isEmpty()) {
            throw new IllegalStateException("-Ps3=aws needs -Pkeel.aws.profile=<an AWS CLI profile signed in with"
                    + " short-lived credentials>");
        }
        System.setProperty("aws.profile", profile);
        AwsCredentialsProvider credentials = ProfileCredentialsProvider.builder().profileName(profile).build();
        String account;
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            account = sts.getCallerIdentity().account();
        }
        String run = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "keel-test-" + run + "-";
        String role = "keel-test-" + run;
        IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(credentials).build();
        String roleArn = iam.createRole(r -> r.roleName(role).maxSessionDuration(3600)
                .description("keel test run " + run + "; deleted when the run ends")
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                        + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}")).role().arn();
        iam.putRolePolicy(r -> r.roleName(role).policyName("everything-in-this-run")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:*\",\"Resource\":[\"arn:aws:s3:::" + prefix + "*\",\"arn:aws:s3:::"
                        + prefix + "*/*\"]}]}"));
        TestS3[] self = new TestS3[1];
        AutoCloseable cleanup = () -> {
            try (S3Client s3 = self[0].client()) {
                for (String bucket : self[0].buckets) {
                    empty(s3, bucket);
                }
            } finally {
                iam.deleteRolePolicy(r -> r.roleName(role).policyName("everything-in-this-run"));
                iam.deleteRole(r -> r.roleName(role));
                iam.close();
            }
        };
        self[0] = new TestS3(Kind.AWS, URI.create("https://s3.us-east-1.amazonaws.com"), credentials,
                Optional.empty(), Optional.of(roleArn), prefix, Optional.of(profile), cleanup);
        try {
            awaitAssumable(credentials, roleArn);
        } catch (RuntimeException e) {
            self[0].close();
            throw e;
        }
        return self[0];
    }

    /** IAM is eventually consistent: a new role can be refused for some seconds. */
    private static void awaitAssumable(AwsCredentialsProvider credentials, String roleArn) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            while (true) {
                try {
                    sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("keel-test-probe").durationSeconds(900));
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

    /**
     * Deletes every object version and delete marker in the bucket, bypassing GOVERNANCE retention, aborts
     * open uploads, then deletes the bucket; a bucket that was never created is skipped.
     */
    private static void empty(S3Client s3, String bucket) {
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
            return;
        }
        List<ObjectIdentifier> versions = new ArrayList<>();
        s3.listObjectVersionsPaginator(r -> r.bucket(bucket)).forEach(page -> {
            page.versions().forEach(v -> versions.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId())
                    .build()));
            page.deleteMarkers().forEach(m -> versions.add(ObjectIdentifier.builder().key(m.key())
                    .versionId(m.versionId()).build()));
        });
        for (int i = 0; i < versions.size(); i += 1000) {
            List<ObjectIdentifier> batch = versions.subList(i, Math.min(versions.size(), i + 1000));
            s3.deleteObjects(r -> r.bucket(bucket).bypassGovernanceRetention(true).delete(d -> d.objects(batch)));
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

    private static String image(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("system property " + property + " is not set; the parent POM pins it");
        }
        return image;
    }

    private static URI endpoint(GenericContainer<?> container, int port) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port));
    }
}
