package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import nuna.catalog.tests.s3.TestS3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * A storage profile's KMS key on a real AWS S3 bucket (D26, amended 2026-11-19; backlog 23). The key is the account's
 * AWS-managed {@code aws/s3} key: no key is created for the test. Its ARN is what S3 names when the run's sign-in
 * writes with SSE-KMS and no key id, which also makes S3 create the key in an account that has none yet.
 *
 * <p>Credentials vended for a table of a profile that names the key write with it, in one request or in parts, and
 * read what they wrote; the session policy refuses them (AccessDenied) a write with no encryption header and with
 * SSE-S3, and read access any write. The vending role allows every S3 action on the run's buckets, so each of those
 * refusals is the session policy's. What the first runs of 2026-11-20 showed of S3's order:
 * <ul>
 *   <li>A write with SSE-KMS naming no key gets through: S3 checks the condition against the key it will use, and
 *       with no key named that is {@code aws/s3}, this profile's key, which it is then encrypted with.</li>
 *   <li>The parts of a multipart upload carry no encryption header, yet are let through after a
 *       CreateMultipartUpload naming the key.</li>
 *   <li>S3 looks the named key up before it checks the policy: a key ARN that does not exist is refused by S3 itself,
 *       400 "does not exist", not by the policy. The account holds no key but {@code aws/s3}, and Paul allowed no
 *       other, so a write naming another existing key is not run here; {@code SessionPolicyTest} shows the condition
 *       names exactly the profile's key.</li>
 * </ul>
 * The server's metadata write ({@link MetadataFiles}, the run's sign-in) is encrypted with the key.
 *
 * <p>The {@code aws/s3} key's own policy lets every principal in the account use it through S3, so this run does not
 * show that the session policy's KMS statement is enough for a customer-managed key whose policy defers to IAM.
 *
 * <p>AWS only, with {@code -Ps3=aws}: heavy, and skipped on Silo and Ceph, whose profiles name no key.
 */
@Tag("heavy")
class KmsKeyAwsTest {

    @Test
    @DisplayName("vended credentials write only with the profile's key, and the server's metadata files use it too")
    void writesOnlyWithTheProfileKey() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.AWS, "the KMS key is checked on AWS");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("kms");
        try (S3Client admin = store.client()) {
            admin.putObject(r -> r.bucket(bucket).key("key-probe").serverSideEncryption(ServerSideEncryption.AWS_KMS),
                    RequestBody.fromString("which key"));
            String key = admin.headObject(r -> r.bucket(bucket).key("key-probe")).ssekmsKeyId();
            assertThat(key).as("the aws/s3 key's ARN").startsWith("arn:aws:kms:" + TestS3.REGION + ":");
            String otherKey = key.substring(0, key.indexOf(":key/") + 5) + UUID.randomUUID();

            StorageProfile profile = StorageProfile.of(StoreKind.S3, store.endpoint.toString(), TestS3.REGION,
                    "s3://" + bucket + "/t", List.of("s3://" + bucket + "/t"), Optional.of(key));
            String table = "s3://" + bucket + "/t/orders";
            try (CredentialVendor vendor = new CredentialVendor(profile, store.roleArn, store.credentials,
                    CredentialVendor.MIN_LIFETIME, Optional.empty());
                 S3Client writer = client(store, vendor.vend(table, Access.READ_WRITE, identity()));
                 S3Client reader = client(store, vendor.vend(table, Access.READ, identity()))) {
                writer.putObject(withKey(bucket, key, "t/orders/data/a.parquet"), RequestBody.fromString("a"));
                assertEncryptedWith(admin.headObject(r -> r.bucket(bucket).key("t/orders/data/a.parquet")), key);
                assertThat(writer.getObjectAsBytes(r -> r.bucket(bucket).key("t/orders/data/a.parquet"))
                        .asUtf8String()).isEqualTo("a");
                assertThat(reader.getObjectAsBytes(r -> r.bucket(bucket).key("t/orders/data/a.parquet"))
                        .asUtf8String()).isEqualTo("a");

                // S3FileIO names the key on CreateMultipartUpload only; its parts and completion carry no header.
                String parts = "t/orders/data/parts.parquet";
                String upload = writer.createMultipartUpload(multipartWithKey(bucket, key, parts)).uploadId();
                String etag = writer.uploadPart(r -> r.bucket(bucket).key(parts).uploadId(upload).partNumber(1),
                        RequestBody.fromString("parts")).eTag();
                writer.completeMultipartUpload(r -> r.bucket(bucket).key(parts).uploadId(upload)
                        .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(etag).build())));
                assertEncryptedWith(admin.headObject(r -> r.bucket(bucket).key(parts)), key);
                assertThat(reader.getObjectAsBytes(r -> r.bucket(bucket).key(parts)).asUtf8String())
                        .isEqualTo("parts");

                String refused = "t/orders/data/refused.parquet";
                assertAccessDenied(() -> writer.putObject(r -> r.bucket(bucket).key(refused),
                        RequestBody.fromString("no encryption")));
                assertAccessDenied(() -> writer.putObject(r -> r.bucket(bucket).key(refused)
                        .serverSideEncryption(ServerSideEncryption.AES256), RequestBody.fromString("SSE-S3")));
                assertAccessDenied(() -> writer.createMultipartUpload(r -> r.bucket(bucket).key(refused)));
                assertAccessDenied(() -> writer.createMultipartUpload(r -> r.bucket(bucket).key(refused)
                        .serverSideEncryption(ServerSideEncryption.AES256)));
                assertAccessDenied(() -> reader.putObject(withKey(bucket, key, refused),
                        RequestBody.fromString("reader")));
                // SSE-KMS naming no key: S3 checks the condition against the key it will use, here aws/s3, which is
                // this profile's key, so the write is let through and encrypted with it. With a customer-managed key
                // as the profile's, the same write would come to aws/s3 and be refused; that is not run here.
                String defaultKey = "t/orders/data/default-key.parquet";
                writer.putObject(r -> r.bucket(bucket).key(defaultKey)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS), RequestBody.fromString("no key named"));
                assertEncryptedWith(admin.headObject(r -> r.bucket(bucket).key(defaultKey)), key);
                // A key that does not exist: S3 refuses it before the policy is asked.
                assertNoSuchKey(() -> writer.putObject(withKey(bucket, otherKey, refused),
                        RequestBody.fromString("another key")));
                assertNoSuchKey(() -> writer.createMultipartUpload(multipartWithKey(bucket, otherKey, refused)));
                assertThat(admin.listObjectsV2(r -> r.bucket(bucket).prefix(refused)).contents()).isEmpty();
            }

            try (MetadataFiles files = new MetadataFiles(profile, store.credentials, Optional.empty())) {
                StoredMetadata stored = files.write("s3://" + bucket + "/t/orders", 1, "{\"format-version\":2}");
                ObjectLocation written = ObjectLocation.parse(stored.location());
                assertEncryptedWith(admin.headObject(r -> r.bucket(bucket).key(written.key())), key);
                assertThat(files.read(stored.location(), stored.sha256(), OnMismatch.FAIL).intact()).isTrue();
            }
        }
    }

    /** A PutObject as S3FileIO sends it with {@code s3.sse.type} {@code kms} and {@code s3.sse.key} {@code key}. */
    private static Consumer<PutObjectRequest.Builder> withKey(String bucket, String key, String objectKey) {
        return r -> r.bucket(bucket).key(objectKey).serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(key);
    }

    private static Consumer<CreateMultipartUploadRequest.Builder> multipartWithKey(String bucket, String key,
                                                                                 String objectKey) {
        return r -> r.bucket(bucket).key(objectKey).serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(key);
    }

    private static void assertEncryptedWith(HeadObjectResponse head, String key) {
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(head.ssekmsKeyId()).isEqualTo(key);
    }

    /** Refused by the session policy: 403 AccessDenied, not an error from KMS. */
    private static void assertAccessDenied(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(S3Exception.class, e -> {
                    assertThat(e.statusCode()).as(e.getMessage()).isEqualTo(403);
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
                });
    }

    /** Refused by S3 itself because the key named does not exist, as the runs of 2026-11-20 saw: 400. */
    private static void assertNoSuchKey(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(S3Exception.class, e -> {
                    assertThat(e.statusCode()).as(e.getMessage()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("does not exist");
                });
    }

    private static S3Client client(TestS3 store, VendedCredentials credentials) {
        return store.client(StaticCredentialsProvider.create(AwsSessionCredentials.create(credentials.accessKeyId(),
                credentials.secretAccessKey(), credentials.sessionToken())));
    }

    private static SessionIdentity identity() {
        return new SessionIdentity("alice@corp.example", Optional.empty(), "spark", "req-1", "trading",
                UUID.fromString("0d1c5b0a-6f3e-4c2a-9d7e-3b8f1a2c4e5f"));
    }
}
