package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import keel.catalog.tests.s3.TestS3;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * The delete policy applied to a real Ceph bucket (D30): beneath the profile's prefix the maintenance worker's own
 * user deletes, and nobody else does, the catalog's own user (the bucket's owner) and a session of the role it
 * vends from included, though that role allows every S3 action; neither can set a lifecycle rule, whose expiration
 * would delete for them. The policy alone lets the worker list the bucket beneath the prefix, and nowhere else.
 * Ceph only: Silo, the default store, enforces no
 * bucket policy on signed requests, so this runs in the heavy pass with {@code -Ps3=ceph} and is skipped otherwise.
 */
@Tag("heavy")
class DeletePolicyCephTest {

    @Test
    @DisplayName("only the worker deletes beneath the prefix, and it lists only there; outside it the policy changes"
            + " nothing")
    void onlyTheWorkerDeletes() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.CEPH, "bucket policies are checked on Ceph");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("delete-policy");
        ObjectLocation prefix = ObjectLocation.parse("s3://" + bucket + "/tables");
        try (S3Client owner = store.client();
             S3Client worker = client(store, AwsBasicCredentials.create(TestS3.MAINTENANCE_ACCESS_KEY,
                     TestS3.MAINTENANCE_SECRET_KEY));
             S3Client role = client(store, roleSession(store))) {
            for (String key : List.of("tables/t/a", "tables/t/b", "tables/t/c", "other/x", "other/y")) {
                owner.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(key));
            }
            // Without the policy the owner may set and remove a lifecycle rule: the refusals below are the policy's.
            owner.putBucketLifecycleConfiguration(r -> r.bucket(bucket).lifecycleConfiguration(expireTables()));
            owner.deleteBucketLifecycle(r -> r.bucket(bucket));
            owner.putBucketPolicy(r -> r.bucket(bucket).policy(DeletePolicy.bucketPolicy(StoreKind.CEPH,
                    TestS3.REGION, bucket, List.of(new DeletePolicy.Worker(store.maintenancePrincipal(),
                            List.of(prefix))))));

            assertDenied(() -> owner.deleteObject(r -> r.bucket(bucket).key("tables/t/a")));
            assertDenied(() -> role.deleteObject(r -> r.bucket(bucket).key("tables/t/a")));
            worker.deleteObject(r -> r.bucket(bucket).key("tables/t/b"));
            assertDenied(() -> worker.deleteObject(r -> r.bucket(bucket).key("other/x")));
            assertThat(worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/t/")).contents())
                    .extracting(S3Object::key).containsExactly("tables/t/a", "tables/t/c");
            assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket).prefix("other/")));
            assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket)));
            assertDenied(() -> owner.putBucketLifecycleConfiguration(r -> r.bucket(bucket)
                    .lifecycleConfiguration(expireTables())));
            assertDenied(() -> role.putBucketLifecycleConfiguration(r -> r.bucket(bucket)
                    .lifecycleConfiguration(expireTables())));
            owner.deleteObject(r -> r.bucket(bucket).key("other/y"));

            assertThat(owner.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/a")).asUtf8String())
                    .isEqualTo("tables/t/a");
            assertThatThrownBy(() -> owner.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/b")))
                    .isInstanceOf(NoSuchKeyException.class);
            assertThat(owner.getObjectAsBytes(r -> r.bucket(bucket).key("other/x")).asUtf8String()).isEqualTo("other/x");
            owner.putObject(r -> r.bucket(bucket).key("tables/t/d"), RequestBody.fromString("still writable"));
        }
    }

    /** A rule that would delete every table file a day after it was written, with no DeleteObject call. */
    private static BucketLifecycleConfiguration expireTables() {
        return BucketLifecycleConfiguration.builder().rules(LifecycleRule.builder().id("expire-tables")
                .filter(LifecycleRuleFilter.builder().prefix("tables/").build()).status(ExpirationStatus.ENABLED)
                .expiration(LifecycleExpiration.builder().days(1).build()).build()).build();
    }

    /** A session of the role the catalog vends from, with no session policy: the role alone allows every S3 action. */
    private static AwsCredentials roleSession(TestS3 store) {
        try (StsClient sts = StsClient.builder().endpointOverride(store.endpoint).region(Region.of(TestS3.REGION))
                .credentialsProvider(store.credentials).build()) {
            Credentials session = sts.assumeRole(r -> r.roleArn(store.roleArn.orElseThrow())
                    .roleSessionName("delete-policy-test").durationSeconds(900)).credentials();
            return AwsSessionCredentials.create(session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
        }
    }

    private static S3Client client(TestS3 store, AwsCredentials credentials) {
        return S3Client.builder().endpointOverride(store.endpoint).region(Region.of(TestS3.REGION)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(S3Exception.class,
                e -> assertThat(e.statusCode()).isEqualTo(403));
    }
}
