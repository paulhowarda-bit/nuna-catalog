package fidice.catalog.objectstore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The M6 gate (D21): credentials vended for one table reach that table's prefix and nothing else, on a
 * real S3-compatible store whose role would allow everything. Attacks: a sibling sharing the prefix's
 * leading characters ({@code orders} vs {@code orders2}), the bare prefix object, listing wider, copying
 * from a sibling, other buckets, bucket-level actions, deletes without the maintenance level, writes
 * with read access, and raw requests whose paths use dot segments, percent-encoded dots, slashes and
 * backslashes, double encoding, overlong UTF-8 and NUL, each signed exactly as sent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class VendingContract {

    private static final String REGION = "us-east-1";
    private static final String SECRET = "orders2-secret-" + UUID.randomUUID();
    /** Objects the tests seed outside {@code t/orders/}; nothing else may ever appear there. */
    private static final Set<String> OUTSIDE = Set.of("t/orders2/secret.json", "t/orders", "t/ordersX/secret.json",
            "outside.json");
    private static final String SEED = "t/orders/seed.json";

    private RunningStore store;
    private String bucket;
    private String otherBucket;
    /** The table credentials are vended for. */
    private String table;
    private S3Client direct;
    private CredentialVendor vendor;
    private final HttpClient http = HttpClient.newHttpClient();

    protected abstract RunningStore start();

    @BeforeAll
    void startStore() {
        store = start();
        bucket = store.bucket();
        otherBucket = store.otherBucket();
        table = "s3://" + bucket + "/t/orders";
        direct = client(store.credentials());
        direct.createBucket(request -> request.bucket(bucket));
        direct.createBucket(request -> request.bucket(otherBucket));
        for (String key : OUTSIDE) {
            directPut(bucket, key, SECRET);
        }
        directPut(otherBucket, "t/orders/secret.json", SECRET);
        directPut(bucket, SEED, "seed");
        StorageProfile profile = StorageProfile.of(store.kind(), store.endpoint().toString(), REGION, "s3://" + bucket + "/t",
                List.of("s3://" + bucket + "/t"));
        vendor = new CredentialVendor(profile, store.roleArn(), store.credentials(), CredentialVendor.MIN_LIFETIME);
    }

    /** Runs after a failed start too, so whatever the store created (a real AWS role, say) is removed. */
    @AfterAll
    void stopStore() {
        if (vendor != null) {
            vendor.close();
        }
        if (direct != null) {
            direct.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("vended credentials read, write, list and upload in parts inside their table's prefix")
    void ownPrefix() {
        try (S3Client vended = vended(Access.READ_WRITE)) {
            vended.putObject(r -> r.bucket(bucket).key("t/orders/data/a.parquet"), RequestBody.fromString("a"));
            assertThat(vended.getObjectAsBytes(r -> r.bucket(bucket).key("t/orders/data/a.parquet")).asUtf8String())
                    .isEqualTo("a");
            vended.headObject(r -> r.bucket(bucket).key(SEED));
            // Silo (as MinIO) lists nothing under t/orders/ while an object named t/orders exists, even for
            // its root user; the tests seed one, so the listing goes a level down.
            assertThat(vended.listObjectsV2(r -> r.bucket(bucket).prefix("t/orders/data/")).contents())
                    .extracting(S3Object::key).contains("t/orders/data/a.parquet");

            String upload = vended.createMultipartUpload(r -> r.bucket(bucket).key("t/orders/data/parts")).uploadId();
            String etag = vended.uploadPart(r -> r.bucket(bucket).key("t/orders/data/parts").uploadId(upload)
                    .partNumber(1), RequestBody.fromString("parts")).eTag();
            vended.completeMultipartUpload(r -> r.bucket(bucket).key("t/orders/data/parts").uploadId(upload)
                    .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(etag).build())));
            assertThat(directGet(bucket, "t/orders/data/parts")).isEqualTo("parts");

            String abandoned = vended.createMultipartUpload(r -> r.bucket(bucket).key("t/orders/data/x")).uploadId();
            vended.abortMultipartUpload(r -> r.bucket(bucket).key("t/orders/data/x").uploadId(abandoned));
        }
        assertNothingNewOutside();
    }

    @Test
    @DisplayName("orders gets nothing in orders2, ordersX or the bare object named orders")
    void siblingWithACommonStem() {
        try (S3Client vended = vended(Access.READ_WRITE)) {
            for (String key : List.of("t/orders2/secret.json", "t/ordersX/secret.json", "t/orders")) {
                assertDenied(() -> vended.getObjectAsBytes(r -> r.bucket(bucket).key(key)));
                assertDenied(() -> vended.headObject(r -> r.bucket(bucket).key(key)));
                assertDenied(() -> vended.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString("pwned")));
            }
            assertDenied(() -> vended.putObject(r -> r.bucket(bucket).key("t/orders2/new.json"),
                    RequestBody.fromString("pwned")));
        }
        assertNothingNewOutside();
    }

    @Test
    @DisplayName("listing is confined to the prefix with its trailing slash")
    void listing() {
        try (S3Client vended = vended(Access.READ_WRITE)) {
            for (String prefix : List.of("t/orders2/", "t/orders", "t/", "", "t/ordersX")) {
                assertDenied(() -> vended.listObjectsV2(r -> r.bucket(bucket).prefix(prefix)));
            }
            try {
                List<String> keys = vended.listObjectsV2(r -> r.bucket(bucket).prefix("t/orders/../orders2/"))
                        .contents().stream().map(S3Object::key).toList();
                assertThat(keys).allMatch(key -> key.startsWith("t/orders/"));
            } catch (S3Exception e) {
                assertThat(e.statusCode()).as("refused as forbidden or as an invalid name").isIn(400, 403);
            }
        }
    }

    @Test
    @DisplayName("a copy cannot pull a sibling's object into the table's prefix")
    void copyFromASibling() {
        try (S3Client vended = vended(Access.READ_WRITE)) {
            assertDenied(() -> vended.copyObject(r -> r.sourceBucket(bucket).sourceKey("t/orders2/secret.json")
                    .destinationBucket(bucket).destinationKey("t/orders/stolen.json")));
            assertDenied(() -> vended.copyObject(r -> r.sourceBucket(otherBucket).sourceKey("t/orders/secret.json")
                    .destinationBucket(bucket).destinationKey("t/orders/stolen2.json")));
        }
        assertThatThrownBy(() -> directGet(bucket, "t/orders/stolen.json")).isInstanceOf(NoSuchKeyException.class);
        assertThatThrownBy(() -> directGet(bucket, "t/orders/stolen2.json")).isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    @DisplayName("the same prefix in another bucket, and bucket-level actions, are refused")
    void otherBucketsAndBucketActions() {
        try (S3Client vended = vended(Access.READ_WRITE_DELETE)) {
            assertDenied(() -> vended.getObjectAsBytes(r -> r.bucket(otherBucket).key("t/orders/secret.json")));
            assertDenied(() -> vended.putObject(r -> r.bucket(otherBucket).key("t/orders/x"),
                    RequestBody.fromString("pwned")));
            assertDenied(() -> vended.listObjectsV2(r -> r.bucket(otherBucket).prefix("t/orders/")));
            assertDenied(() -> vended.createBucket(r -> r.bucket(bucket + "3")));
            assertDenied(() -> vended.putBucketPolicy(r -> r.bucket(bucket).policy("{\"Version\":\"2012-10-17\","
                    + "\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:*\","
                    + "\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}]}")));
            assertDenied(() -> vended.deleteBucket(r -> r.bucket(otherBucket)));
            assertDenied(() -> vended.listBuckets());
        }
        assertThat(directGet(otherBucket, "t/orders/secret.json")).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("without the maintenance level nothing can be deleted, singly or in a batch (D25)")
    void noDeleteWithoutMaintenance() {
        directPut(bucket, "t/orders/keep.json", "keep");
        try (S3Client vended = vended(Access.READ_WRITE)) {
            assertDenied(() -> vended.deleteObject(r -> r.bucket(bucket).key("t/orders/keep.json")));
            try {
                DeleteObjectsResponse batch = vended.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                        ObjectIdentifier.builder().key("t/orders/keep.json").build())));
                assertThat(batch.errors()).as("per-key errors of a batch delete").isNotEmpty();
            } catch (S3Exception e) {
                assertThat(e.statusCode()).isEqualTo(403);
            }
        }
        assertThat(directGet(bucket, "t/orders/keep.json")).isEqualTo("keep");
    }

    @Test
    @DisplayName("the maintenance level deletes inside the prefix and still nowhere else")
    void maintenanceDeletesOnlyInside() {
        directPut(bucket, "t/orders/old.json", "old");
        try (S3Client vended = vended(Access.READ_WRITE_DELETE)) {
            vended.deleteObject(r -> r.bucket(bucket).key("t/orders/old.json"));
            assertDenied(() -> vended.deleteObject(r -> r.bucket(bucket).key("t/orders2/secret.json")));
            assertDenied(() -> vended.deleteObject(r -> r.bucket(bucket).key("t/orders")));
        }
        assertThatThrownBy(() -> directGet(bucket, "t/orders/old.json")).isInstanceOf(NoSuchKeyException.class);
        assertThat(directGet(bucket, "t/orders2/secret.json")).isEqualTo(SECRET);
        assertThat(directGet(bucket, "t/orders")).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("read access reads and lists, and cannot write")
    void readCannotWrite() {
        try (S3Client vended = vended(Access.READ)) {
            assertThat(vended.getObjectAsBytes(r -> r.bucket(bucket).key(SEED)).asUtf8String()).isEqualTo("seed");
            vended.listObjectsV2(r -> r.bucket(bucket).prefix("t/orders/data/"));
            assertDenied(() -> vended.putObject(r -> r.bucket(bucket).key("t/orders/data/w"),
                    RequestBody.fromString("w")));
            assertDenied(() -> vended.createMultipartUpload(r -> r.bucket(bucket).key("t/orders/data/w")));
        }
    }

    @Test
    @DisplayName("raw requests with dot segments and encoded paths never reach outside the prefix")
    void rawPathAttacks() throws Exception {
        VendedCredentials credentials = vend(Access.READ_WRITE);
        RawResponse control = raw(credentials, "GET", "/" + bucket + "/" + SEED, null);
        assertThat(control.status()).as("a raw request the policy allows succeeds, so the signing is right")
                .isEqualTo(200);
        assertThat(control.body()).isEqualTo("seed");

        List<String> attacks = List.of(
                "/" + bucket + "/t/orders/../orders2/secret.json",
                "/" + bucket + "/t/orders/./../orders2/secret.json",
                "/" + bucket + "/t/orders//../../t/orders2/secret.json",
                "/" + bucket + "/t/orders/%2E%2E/orders2/secret.json",
                "/" + bucket + "/t/orders/%2e%2e%2forders2%2fsecret.json",
                "/" + bucket + "/t/orders%2F..%2Forders2%2Fsecret.json",
                "/" + bucket + "/t/orders/..%2Forders2/secret.json",
                "/" + bucket + "/t/orders/%252E%252E/orders2/secret.json",
                "/" + bucket + "/t/orders/..%5Corders2%5Csecret.json",
                "/" + bucket + "/t/orders/%C0%AE%C0%AE/orders2/secret.json",
                "/" + bucket + "/t/orders/%EF%BC%8E%EF%BC%8E/orders2/secret.json",
                "/" + bucket + "/t/orders/%00/../../orders2/secret.json",
                "/" + bucket + "/t/orders/../../" + otherBucket + "/t/orders/secret.json",
                "/" + bucket + "/t/orders/..");
        StringBuilder report = new StringBuilder("raw path attacks on " + store.kind()
                + " (* = signed for the path as the store decodes it):\n");
        for (String path : attacks) {
            RawResponse get = rawPastTheSignature(credentials, "GET", path, null);
            RawResponse put = rawPastTheSignature(credentials, "PUT", path, "pwned".getBytes(UTF_8));
            report.append(String.format("  GET %d %-26s PUT %d %-26s %s%n", get.status(), get.label(), put.status(),
                    put.label(), path));
            assertThat(get.body()).as("GET " + path).doesNotContain(SECRET);
        }
        System.out.print(report);
        assertNothingNewOutside();
        assertThat(directGet(bucket, "t/orders2/secret.json")).isEqualTo(SECRET);
        assertThat(directGet(otherBucket, "t/orders/secret.json")).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("the vendor refuses the prefix itself, other prefixes and other buckets")
    void vendorRefusesLocationsOutsideTheProfile() {
        for (String location : List.of("s3://" + bucket + "/t", "s3://" + bucket + "/other/x", "s3://" + otherBucket + "/t/x", "s3://" + bucket + "/t/../x",
                "s3://" + bucket + "/t/orders/", "s3://" + bucket + "/t/%2e%2e/x")) {
            assertThatThrownBy(() -> vendor.vend(location, Access.READ, identity()))
                    .as(location).isInstanceOf(LocationRejectedException.class);
        }
    }

    @Test
    @DisplayName("credentials are temporary, expire when asked, and are not the catalog's own")
    void temporary() {
        Instant before = Instant.now();
        VendedCredentials credentials = vend(Access.READ);

        assertThat(credentials.accessKeyId()).isNotEqualTo(store.credentials().resolveCredentials().accessKeyId());
        assertThat(credentials.sessionToken()).isNotBlank();
        assertThat(credentials.expiresAt()).isBetween(before.plus(Duration.ofMinutes(14)),
                Instant.now().plus(Duration.ofMinutes(16)));
        assertThat(credentials.location()).isEqualTo(table);
        assertThat(credentials.sessionName()).isEqualTo(identity().sessionName());
        assertThat(credentials.toString()).doesNotContain(credentials.secretAccessKey(), credentials.sessionToken());
    }

    private static SessionIdentity identity() {
        return new SessionIdentity("alice@corp.example", Optional.empty(), "spark", "req-1", "trading",
                UUID.fromString("0d1c5b0a-6f3e-4c2a-9d7e-3b8f1a2c4e5f"));
    }

    private VendedCredentials vend(Access access) {
        return vendor.vend(table, access, identity());
    }

    private S3Client vended(Access access) {
        VendedCredentials credentials = vend(access);
        return client(StaticCredentialsProvider.create(AwsSessionCredentials.create(credentials.accessKeyId(),
                credentials.secretAccessKey(), credentials.sessionToken())));
    }

    private S3Client client(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials) {
        return S3Client.builder()
                .endpointOverride(store.endpoint())
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(credentials)
                .build();
    }

    private static void assertDenied(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).as(e.getMessage()).isEqualTo(403));
    }

    private void directPut(String bucket, String key, String body) {
        direct.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(body));
    }

    private String directGet(String bucket, String key) {
        return direct.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asUtf8String();
    }

    /** Every key in the bucket outside {@code t/orders/} is one the tests seeded, and each still holds its bytes. */
    private void assertNothingNewOutside() {
        Set<String> outside = direct.listObjectsV2Paginator(r -> r.bucket(bucket)).contents().stream()
                .map(S3Object::key)
                .filter(key -> !key.startsWith("t/orders/"))
                .collect(Collectors.toSet());
        assertThat(outside).containsExactlyInAnyOrderElementsOf(OUTSIDE);
        for (String key : OUTSIDE) {
            assertThat(directGet(bucket, key)).as(key).isEqualTo(SECRET);
        }
        Set<String> other = direct.listObjectsV2Paginator(r -> r.bucket(otherBucket)).contents().stream()
                .map(S3Object::key).collect(Collectors.toSet());
        assertThat(other).containsExactly("t/orders/secret.json");
    }

    /** @param redecoded whether the request was signed for the path as the store decodes it */
    private record RawResponse(int status, String code, String body, boolean redecoded) {

        String label() {
            return redecoded ? code + "*" : code;
        }
    }

    /**
     * Sends the request signed for the path as sent and, if the store refuses that signature because it
     * decodes the path before signing, again signed for the decoded path, so the request reaches the policy.
     */
    private RawResponse rawPastTheSignature(VendedCredentials credentials, String method, String rawPath, byte[] body)
            throws IOException, InterruptedException {
        RawResponse asSent = raw(credentials, method, rawPath, rawPath, body);
        if (!asSent.code().equals("SignatureDoesNotMatch")) {
            return asSent;
        }
        return raw(credentials, method, rawPath, decodedOnce(rawPath), body);
    }

    /** Sends the request returning only the response, mainly for paths signed exactly as sent. */
    private RawResponse raw(VendedCredentials credentials, String method, String rawPath, byte[] body)
            throws IOException, InterruptedException {
        return raw(credentials, method, rawPath, rawPath, body);
    }

    /**
     * Percent-decodes the path once and encodes it again the way SigV4 canonicalizes S3 paths: unreserved
     * characters and {@code /} as they are, every other byte as {@code %XX}. Works on bytes, so invalid
     * UTF-8 and NUL survive.
     */
    static String decodedOnce(String path) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                bytes.write(Integer.parseInt(path.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        StringBuilder encoded = new StringBuilder();
        for (byte b : bytes.toByteArray()) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~/".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append(String.format("%%%02X", c));
            }
        }
        return encoded.toString();
    }

    /** Sends a request whose path goes on the wire exactly as written, signed for {@code signedPath}. */
    private RawResponse raw(VendedCredentials credentials, String method, String rawPath, String signedPath,
                            byte[] body) throws IOException, InterruptedException {
        URI endpoint = store.endpoint();
        byte[] payload = body == null ? new byte[0] : body;
        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .protocol(endpoint.getScheme())
                .host(endpoint.getHost())
                .port(endpoint.getPort())
                .encodedPath(signedPath)
                .putHeader("x-amz-content-sha256", HexFormat.of().formatHex(sha256(payload)))
                .build();
        SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r
                .identity(AwsSessionCredentialsIdentity.create(credentials.accessKeyId(),
                        credentials.secretAccessKey(), credentials.sessionToken()))
                .request(request)
                .payload(ContentStreamProvider.fromByteArray(payload))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, REGION)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + rawPath))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(payload));
        signed.request().headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String code = response.body().replaceAll("(?s).*<Code>([^<]*)</Code>.*", "$1");
        return new RawResponse(response.statusCode(), code.equals(response.body()) ? "-" : code, response.body(),
                !signedPath.equals(rawPath));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
