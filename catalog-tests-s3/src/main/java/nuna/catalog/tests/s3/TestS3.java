package nuna.catalog.tests.s3;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
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
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
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
 *       {@code nuna.aws.profile} (short-lived credentials; nothing here reads, prints or stores them).
 *       Creates two roles: one allowing every S3 action on this run's buckets, which vended credentials
 *       assume, and the maintenance worker's (D30), which may list and delete there and remove a bucket
 *       policy. On exit it removes each bucket's policy as the worker, since a delete policy refuses
 *       everyone else that, then deletes every bucket it made and both roles. Synthetic data only.</li>
 * </ul>
 *
 * <p>A test of a store behind a private CA starts one more, of its own: Silo serving TLS ({@link #siloBehindTls}).
 */
public final class TestS3 implements AutoCloseable {

    /** The stores a run can use. */
    public enum Kind {
        SILO,
        CEPH,
        AWS
    }

    public static final String REGION = "us-east-1";
    /**
     * The keys of Silo's root user and Ceph's S3 user. The secret keys here are made for each test JVM, so none is
     * written in the source (backlog 281): a store this class starts takes them as it starts, and the engine matrix
     * fills them into the stack's manifests ({@code ${S3_SECRET_KEY}} in {@code demo/k8s}).
     */
    public static final String ACCESS_KEY = "nunatest";
    public static final String SECRET_KEY = "s3-" + UUID.randomUUID();
    /** The role vended credentials assume on Ceph. */
    public static final String CEPH_ROLE_ARN = "arn:aws:iam:::role/nuna-vending";
    private static final String CEPH_ROLE = "nuna-vending";
    /** RGW's key for signing session tokens: 16 characters. */
    public static final String CEPH_STS_KEY = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    /** The maintenance worker's own storage identity (D30): a user on Ceph, whose keys these are. */
    public static final String MAINTENANCE_USER = "nuna-maintenance";
    public static final String MAINTENANCE_ACCESS_KEY = "nunamaintenance";
    public static final String MAINTENANCE_SECRET_KEY = "s3-" + UUID.randomUUID();

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
    /** The maintenance worker's storage identity as a storage profile names it, and its credentials (none on Silo). */
    private final String maintenancePrincipal;
    private final Optional<AwsCredentialsProvider> maintenanceCredentials;
    /** Silo's container, where {@link #siloUser} runs {@code mc}; none elsewhere. */
    private final Optional<GenericContainer<?>> silo;
    /** The CA that signed the store's certificate, when it serves TLS ({@link #siloBehindTls}); none elsewhere. */
    private final Optional<PrivateCa> ca;
    /** On AWS, this run's bucket-name prefix; empty elsewhere. */
    private final String bucketPrefix;
    private final Optional<String> awsProfile;
    private final AutoCloseable stop;
    private final Set<String> buckets = new LinkedHashSet<>();
    /** Those of {@link #buckets} made with Object Lock. */
    private final Set<String> lockedBuckets = new LinkedHashSet<>();
    private boolean closed;

    private TestS3(Kind kind, URI endpoint, AwsCredentialsProvider credentials, Optional<AwsBasicCredentials> keys,
                   Optional<String> roleArn, String maintenancePrincipal,
                   Optional<AwsCredentialsProvider> maintenanceCredentials, Optional<GenericContainer<?>> silo,
                   Optional<PrivateCa> ca, String bucketPrefix, Optional<String> awsProfile, AutoCloseable stop) {
        this.kind = kind;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.keys = keys;
        this.roleArn = roleArn;
        this.maintenancePrincipal = maintenancePrincipal;
        this.maintenanceCredentials = maintenanceCredentials;
        this.silo = silo;
        this.ca = ca;
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
     * it with {@code nuna-test-<run id>-}, since bucket names are global.
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
            if (objectLock) {
                lockedBuckets.add(bucket);
            }
        }
        return bucket;
    }

    /** A client with full access, addressing buckets by path (AWS still accepts that in us-east-1). */
    public S3Client client() {
        return client(credentials);
    }

    /**
     * A client signing in with {@code credentials}, addressing buckets by path; when the store serves TLS
     * ({@link #siloBehindTls}), trusting its CA alone.
     */
    public S3Client client(AwsCredentialsProvider credentials) {
        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(credentials);
        ca.ifPresent(authority -> builder.httpClientBuilder(Apache5HttpClient.builder()
                .tlsTrustManagersProvider(() -> trusting(authority))));
        return builder.build();
    }

    /**
     * The PEM file of the CA that signed the store's certificate, when it serves TLS ({@link #siloBehindTls}), as a
     * storage profile's {@code ca-bundle} names one; none elsewhere.
     */
    public Optional<Path> caBundle() {
        return ca.map(authority -> authority.bundle);
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
     * on Silo and Ceph, this run's role {@code nuna-test-<run id>-maintenance} on AWS. Silo's user exists only once a
     * test makes it ({@link #siloUser}): Silo's tests run as its root user, whom no policy binds.
     */
    public String maintenancePrincipal() {
        return maintenancePrincipal;
    }

    /**
     * The maintenance worker's own credentials: its user's keys on Ceph, sessions of its role on AWS.
     *
     * @throws IllegalStateException on Silo, which has no such user until a test makes it
     */
    public AwsCredentialsProvider maintenanceCredentials() {
        return maintenanceCredentials.orElseThrow(() -> new IllegalStateException(
                "the " + kind + " store has no maintenance user of its own"));
    }

    /**
     * On Silo, makes user {@code user} with secret key {@code secret} if it is new, and attaches to it user policy
     * {@code name}, created (or replaced) from {@code policy}: as Silo's root user, with the image's {@code mc}.
     */
    public void siloUser(String user, String secret, String name, String policy) {
        GenericContainer<?> container = silo.orElseThrow(() -> new IllegalStateException(
                "the " + kind + " store is not Silo"));
        container.copyFileToContainer(Transferable.of(policy), "/tmp/" + name + ".json");
        run(container, "mc", "alias", "set", "local", "http://localhost:9000", ACCESS_KEY, SECRET_KEY);
        run(container, "mc", "admin", "user", "add", "local", user, secret);
        run(container, "mc", "admin", "policy", "create", "local", name, "/tmp/" + name + ".json");
        run(container, "mc", "admin", "policy", "attach", "local", name, "--user", user);
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
     * Plain http is allowed, since the containers serve no TLS. A store that does ({@link #siloBehindTls}) is held to
     * it, as a deployment's are, and the profile names its CA's bundle ({@code ca-bundle}).
     */
    public Map<String, String> serverProperties(String profile, String bucket) {
        String p = "nuna.storage.profiles." + profile + ".";
        Map<String, String> properties = new LinkedHashMap<>();
        if (ca.isEmpty()) {
            properties.put("nuna.storage.require-tls", "false");
        }
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
        caBundle().ifPresent(bundle -> properties.put(p + "ca-bundle", bundle.toString()));
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
        GenericContainer<?> container = siloContainer()
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        AwsBasicCredentials keys = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        return new TestS3(Kind.SILO, endpoint(container, 9000), StaticCredentialsProvider.create(keys),
                Optional.of(keys), Optional.empty(), MAINTENANCE_USER, Optional.empty(), Optional.of(container),
                Optional.empty(), "", Optional.empty(), container::stop);
    }

    /**
     * Silo of its own, apart from the run's ({@link #get}) and whatever {@code -Ps3} names, as the run's is, its root
     * user's keys {@link #ACCESS_KEY} and {@link #SECRET_KEY}: another region's store, the DR drill's (backlog 92).
     * Started on each call and stopped by {@link #close} or when the JVM exits.
     */
    public static TestS3 siloOfItsOwn() {
        TestS3 store = silo();
        Runtime.getRuntime().addShutdownHook(new Thread(store::close, "test-s3-own-stop"));
        return store;
    }

    /**
     * Silo serving TLS, as an on-prem store whose certificate a private CA signed does (backlog 89): a store of its
     * own, apart from the run's ({@link #get}) and whatever {@code -Ps3} names, started on each call and stopped when
     * the JVM exits. Its certificate, which {@code ca} signs ({@link PrivateCa#issue}), names this machine as tests
     * reach it ({@code localhost}, {@code 127.0.0.1}, the Docker host's address) and as pods of the kind cluster
     * ({@link KindCluster}) do, {@code host.docker.internal}: a catalog serving those pods names the store by that name (the
     * maintenance worker's {@code TestCatalog}), and a pod's TLS client checks that name against the certificate.
     * Silo reads the certificate and its key from {@code --certs-dir}, as {@code public.crt} and {@code private.key}.
     * Its clients trust {@code ca} ({@link #client}); a server's storage profile for it requires TLS and names
     * {@code ca}'s bundle ({@link #serverProperties}). It has no user but its root user ({@link #siloUser} is for the
     * run's Silo).
     */
    public static TestS3 siloBehindTls(PrivateCa ca) {
        PrivateCa.Issued certificate = ca.issue(List.of("localhost", "127.0.0.1",
                DockerClientFactory.instance().dockerHostIpAddress(), "host.docker.internal"));
        GenericContainer<?> container = siloContainer()
                .withCopyFileToContainer(MountableFile.forHostPath(certificate.certificate()), "/certs/public.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(certificate.key()), "/certs/private.key")
                .withCommand("server", "--certs-dir", "/certs", "/data")
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).usingTls().allowInsecure()
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        AwsBasicCredentials keys = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        TestS3 store = new TestS3(Kind.SILO, URI.create("https://" + container.getHost() + ":"
                + container.getMappedPort(9000)), StaticCredentialsProvider.create(keys), Optional.of(keys),
                Optional.empty(), MAINTENANCE_USER, Optional.empty(), Optional.empty(), Optional.of(ca), "",
                Optional.empty(), container::stop);
        Runtime.getRuntime().addShutdownHook(new Thread(store::close, "test-s3-tls-stop"));
        return store;
    }

    /** Silo, its root user's keys {@link #ACCESS_KEY} and {@link #SECRET_KEY}, serving on port 9000. */
    private static GenericContainer<?> siloContainer() {
        return new GenericContainer<>(DockerImageName.parse(image("silo.image")))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000);
    }

    /** Trust managers trusting {@code ca} alone: for a client of the store it signed for, and of nothing else. */
    private static TrustManager[] trusting(PrivateCa ca) {
        try {
            KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
            anchors.load(null, null);
            anchors.setCertificateEntry("ca", ca.certificate);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(anchors);
            return trust.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("cannot trust the private CA", e);
        }
    }

    /**
     * Ceph RGW in one container (mon, mgr, osd, rgw), with an S3 user created at startup. The image sets
     * {@code rgw dns name = demo}; RGW then reads any other host name as a bucket (CNAME hosting), so the
     * DNS names are set to the host the tests connect through and {@code host.docker.internal}, the name a
     * catalog serving the kind cluster's pods gives the store (the maintenance worker's TestCatalog.forPods).
     */
    private static TestS3 ceph() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image("ceph.image")))
                .withEnv("CEPH_DEMO_ACCESS_KEY", ACCESS_KEY)
                .withEnv("CEPH_DEMO_SECRET_KEY", SECRET_KEY)
                .withEnv("CEPH_EXTRA_CONF", String.join("\n",
                        "rgw dns name = " + DockerClientFactory.instance().dockerHostIpAddress()
                                + ",host.docker.internal",
                        "rgw sts key = " + CEPH_STS_KEY,
                        "rgw s3 auth use sts = true"))
                .withExposedPorts(8080)
                .waitingFor(Wait.forLogMessage(".*=== ceph-test setup complete ===.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(6)));
        container.start();
        for (List<String> command : cephRoleCommands()) {
            run(container, command.toArray(String[]::new));
        }
        run(container, "radosgw-admin", "user", "create", "--uid=" + MAINTENANCE_USER,
                "--display-name=nuna maintenance worker", "--access-key=" + MAINTENANCE_ACCESS_KEY,
                "--secret-key=" + MAINTENANCE_SECRET_KEY);
        AwsBasicCredentials keys = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        return new TestS3(Kind.CEPH, endpoint(container, 8080), StaticCredentialsProvider.create(keys),
                Optional.of(keys), Optional.of(CEPH_ROLE_ARN), "arn:aws:iam:::user/" + MAINTENANCE_USER,
                Optional.of(StaticCredentialsProvider.create(AwsBasicCredentials.create(MAINTENANCE_ACCESS_KEY,
                        MAINTENANCE_SECRET_KEY))), Optional.empty(), Optional.empty(), "", Optional.empty(),
                container::stop);
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
     * Real AWS, signed in through {@code nuna.aws.profile}. Creates a role whose own policy allows every
     * S3 action on {@code nuna-test-<run id>-*} and trusts the account to assume and tag it, and the
     * maintenance worker's role {@code nuna-test-<run id>-maintenance}, whose own policy allows listing,
     * deleting and removing a bucket policy there, and waits until STS accepts both. In-process servers
     * find the profile through {@code aws.profile}.
     *
     * <p>A delete policy (D30) refuses every principal but the worker a change to the bucket policy, the
     * profile's own user included, so on exit the worker removes each bucket's policy before the buckets
     * are emptied, and the worker's role is deleted only once every removal succeeded. Otherwise the role is
     * kept and the buckets and role are named on stderr: only that role can then free them.
     */
    private static TestS3 aws() {
        String profile = System.getProperty("nuna.aws.profile", "").trim();
        if (profile.isEmpty()) {
            throw new IllegalStateException("-Ps3=aws needs -Pnuna.aws.profile=<an AWS CLI profile signed in with"
                    + " short-lived credentials>");
        }
        System.setProperty("aws.profile", profile);
        AwsCredentialsProvider credentials = ProfileCredentialsProvider.builder().profileName(profile).build();
        String account;
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            account = sts.getCallerIdentity().account();
        }
        String run = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "nuna-test-" + run + "-";
        String role = "nuna-test-" + run;
        IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(credentials).build();
        String roleArn = iam.createRole(r -> r.roleName(role).maxSessionDuration(3600)
                .description("nuna test run " + run + "; deleted when the run ends")
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                        + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\",\"sts:SetSourceIdentity\"]}]}")).role().arn();
        String buckets = "[\"arn:aws:s3:::" + prefix + "*\",\"arn:aws:s3:::" + prefix + "*/*\"]";
        iam.putRolePolicy(r -> r.roleName(role).policyName("everything-in-this-run")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:*\",\"Resource\":" + buckets + "}]}"));
        String worker = prefix + "maintenance";
        String workerArn = iam.createRole(r -> r.roleName(worker).maxSessionDuration(3600)
                .description("nuna test run " + run + ": the maintenance worker; deleted when the run ends")
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                        + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\",\"sts:SetSourceIdentity\"]}]}")).role().arn();
        iam.putRolePolicy(r -> r.roleName(worker).policyName("maintenance-in-this-run")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                        + "[\"s3:ListBucket\",\"s3:DeleteObject\",\"s3:DeleteObjectVersion\",\"s3:GetBucketPolicy\","
                        + "\"s3:DeleteBucketPolicy\"],\"Resource\":" + buckets + "}]}"));
        StsClient workerSts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build();
        StsAssumeRoleCredentialsProvider workerCredentials = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(workerSts).refreshRequest(r -> r.roleArn(workerArn).roleSessionName("nuna-test-maintenance")
                        .durationSeconds(900)).build();
        TestS3[] self = new TestS3[1];
        AutoCloseable cleanup = () -> {
            List<String> kept = new ArrayList<>();
            try {
                try (S3Client asWorker = self[0].client(workerCredentials)) {
                    for (String bucket : self[0].buckets) {
                        try {
                            asWorker.deleteBucketPolicy(r -> r.bucket(bucket));
                        } catch (RuntimeException e) {
                            kept.add(bucket);
                            System.err.println("TestS3: the worker's role " + workerArn + " could not remove the"
                                    + " policy of bucket " + bucket + ": " + e);
                        }
                    }
                }
                List<String> left = new ArrayList<>();
                try (S3Client s3 = self[0].client()) {
                    for (String bucket : self[0].buckets) {
                        if (kept.contains(bucket)) {
                            continue;
                        }
                        try {
                            empty(s3, bucket, self[0].lockedBuckets.contains(bucket));
                        } catch (RuntimeException e) {
                            left.add(bucket);
                            System.err.println("TestS3: bucket " + bucket + " was not deleted: " + e);
                        }
                    }
                }
                if (!left.isEmpty()) {
                    throw new IllegalStateException("buckets " + left + " were not deleted");
                }
            } finally {
                iam.deleteRolePolicy(r -> r.roleName(role).policyName("everything-in-this-run"));
                iam.deleteRole(r -> r.roleName(role));
                if (kept.isEmpty()) {
                    iam.deleteRolePolicy(r -> r.roleName(worker).policyName("maintenance-in-this-run"));
                    iam.deleteRole(r -> r.roleName(worker));
                } else {
                    System.err.println("TestS3: kept role " + workerArn + ", the only principal that can remove the"
                            + " delete policy of " + kept + "; remove each bucket's policy as it, then the buckets"
                            + " and the role");
                }
                workerCredentials.close();
                workerSts.close();
                iam.close();
            }
        };
        self[0] = new TestS3(Kind.AWS, URI.create("https://s3.us-east-1.amazonaws.com"), credentials,
                Optional.empty(), Optional.of(roleArn), workerArn, Optional.of(workerCredentials), Optional.empty(),
                Optional.empty(), prefix, Optional.of(profile), cleanup);
        try {
            awaitAssumable(credentials, roleArn);
            awaitAssumable(credentials, workerArn);
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
                    sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("nuna-test-probe").durationSeconds(900));
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
     * Deletes every object version and delete marker in the bucket, bypassing GOVERNANCE retention where the
     * bucket has Object Lock ({@code locked}; AWS refuses the bypass on any other bucket), aborts open uploads,
     * then deletes the bucket; a bucket that was never created is skipped. A bucket policy removed just before
     * may still refuse deletes for some seconds, so a refusal (403, or 409 for a bucket left not empty) is tried
     * again for a minute.
     */
    private static void empty(S3Client s3, String bucket, boolean locked) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(1));
        while (true) {
            try {
                emptyOnce(s3, bucket, locked);
                return;
            } catch (S3Exception e) {
                if ((e.statusCode() != 403 && e.statusCode() != 409) || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                sleep(Duration.ofSeconds(5));
            }
        }
    }

    private static void emptyOnce(S3Client s3, String bucket, boolean locked) {
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
            DeleteObjectsResponse deleted = s3.deleteObjects(r -> r.bucket(bucket)
                    .bypassGovernanceRetention(locked ? true : null).delete(d -> d.objects(batch)));
            if (deleted.hasErrors() && !deleted.errors().isEmpty()) {
                throw S3Exception.builder().statusCode(403).message("bucket " + bucket + ": "
                        + deleted.errors().getFirst().key() + " was not deleted: " + deleted.errors().getFirst().code())
                        .build();
            }
        }
        s3.listMultipartUploadsPaginator(r -> r.bucket(bucket)).uploads().forEach(upload ->
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(upload.key()).uploadId(upload.uploadId())));
        s3.deleteBucket(r -> r.bucket(bucket));
    }

    /** Runs {@code command} in the container and fails on a non-zero exit. */
    private static void run(GenericContainer<?> container, String... command) {
        try {
            var result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr()
                        + result.getStdout());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
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
