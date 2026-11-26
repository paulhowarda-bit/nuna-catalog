package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import nuna.catalog.tests.s3.TestS3;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * The MinIO delete policies applied to Silo (D30, backlog 4) as the catalog gives them: the user policy for every
 * user but the worker ({@code except}, {@link DeletePolicy#userPolicy}) and the worker's own ({@code to},
 * {@link DeletePolicy#workerPolicy}), each for the profile's allowed prefixes {@code s3://<bucket>/tables}, the calls
 * {@code GET /management/v1/maintenance/delete-policies} makes ({@code DeletePolicyRoutes}) for such a profile.
 *
 * <p>Silo's root user ignores every policy, so the catalog here is a user of its own, {@code nuna-catalog}, whose
 * other policy allows every S3 action on the bucket, deletes and bucket settings included, as another grant might at
 * work: what refuses it is the {@code except} policy. Neither it nor a session it takes from STS, which carries its
 * policies, may delete beneath the prefix, set a lifecycle rule or a bucket policy, or remove one; outside the
 * prefix it deletes as before. The worker, {@value TestS3#MAINTENANCE_USER}, has no right but its own policy's: it
 * deletes and lists beneath the prefix and nowhere else.
 *
 * <p>Silo only, the default store: heavy, as it starts the container.
 */
@Tag("heavy")
class DeletePolicyMinioTest {

    private static final String CATALOG_USER = "nuna-catalog";
    /** The users' secret keys, made per run (MinIO takes 8 to 40 characters). */
    private static final String CATALOG_SECRET = "sk-" + UUID.randomUUID();
    private static final String WORKER_SECRET = "sk-" + UUID.randomUUID();
    private static final String ONLY_TO_USER = "nuna-worker-only-to";
    private static final String ONLY_TO_SECRET = "sk-" + UUID.randomUUID();

    @Test
    @DisplayName("only the worker deletes and lists beneath the prefix; the catalog's user and its sessions set no"
            + " lifecycle rule or bucket policy; outside the prefix nothing changes")
    void onlyTheWorkerDeletes() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.SILO, "MinIO's user policies are checked on Silo");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("delete-policy");
        List<ObjectLocation> prefixes = List.of(ObjectLocation.parse("s3://" + bucket + "/tables"));
        String bucketArn = "arn:aws:s3:::" + bucket;
        String openToAll = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
                + "[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"" + bucketArn + "/tables/*\"]}]}";
        store.siloUser(CATALOG_USER, CATALOG_SECRET, "nuna-catalog-rights", "{\"Version\":\"2012-10-17\",\"Statement\":"
                + "[{\"Effect\":\"Allow\",\"Action\":[\"s3:*\"],\"Resource\":[\"" + bucketArn + "\",\"" + bucketArn
                + "/*\"]}]}");
        try (S3Client root = store.client();
             S3Client catalog = store.client(keys(CATALOG_USER, CATALOG_SECRET))) {
            for (String key : List.of("tables/t/a", "tables/t/b", "tables/t/c", "tables/t/catalog", "other/x",
                    "other/y")) {
                root.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(key));
            }
            // Before the except policy the catalog's user deletes there and sets and removes a lifecycle rule and a
            // bucket policy: the refusals below are the policy's.
            catalog.deleteObject(r -> r.bucket(bucket).key("tables/t/catalog"));
            catalog.putBucketLifecycleConfiguration(r -> r.bucket(bucket).lifecycleConfiguration(expireTables()));
            catalog.deleteBucketLifecycle(r -> r.bucket(bucket));
            catalog.putBucketPolicy(r -> r.bucket(bucket).policy(openToAll));
            catalog.deleteBucketPolicy(r -> r.bucket(bucket));

            store.siloUser(CATALOG_USER, CATALOG_SECRET, "nuna-no-delete", DeletePolicy.userPolicy(prefixes));
            store.siloUser(store.maintenancePrincipal(), WORKER_SECRET, "nuna-maintenance",
                    DeletePolicy.workerPolicy(prefixes));

            try (S3Client session = store.client(session(store));
                 S3Client worker = store.client(keys(store.maintenancePrincipal(), WORKER_SECRET))) {
                for (S3Client other : List.of(catalog, session)) {
                    assertDenied(() -> other.deleteObject(r -> r.bucket(bucket).key("tables/t/a")));
                    assertNotDeleted(other.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                            ObjectIdentifier.builder().key("tables/t/a").build()))), "tables/t/a");
                    assertDenied(() -> other.putBucketLifecycleConfiguration(r -> r.bucket(bucket)
                            .lifecycleConfiguration(expireTables())));
                    assertDenied(() -> other.putBucketPolicy(r -> r.bucket(bucket).policy(openToAll)));
                    assertDenied(() -> other.deleteBucketPolicy(r -> r.bucket(bucket)));
                }
                worker.deleteObject(r -> r.bucket(bucket).key("tables/t/b"));
                assertThat(worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/t/")).contents())
                        .extracting(S3Object::key).containsExactly("tables/t/a", "tables/t/c");
                assertDenied(() -> worker.deleteObject(r -> r.bucket(bucket).key("other/x")));
                assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket).prefix("other/")));
                assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket)));
                // Outside the prefix the policy changes nothing, and beneath it only deletes are refused.
                catalog.deleteObject(r -> r.bucket(bucket).key("other/y"));
                catalog.putObject(r -> r.bucket(bucket).key("tables/t/d"), RequestBody.fromString("still writable"));
            }

            assertThat(root.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/a")).asUtf8String())
                    .isEqualTo("tables/t/a");
            assertThatThrownBy(() -> root.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/b")))
                    .isInstanceOf(NoSuchKeyException.class);
            assertThat(root.getObjectAsBytes(r -> r.bucket(bucket).key("other/x")).asUtf8String()).isEqualTo("other/x");
            assertThatThrownBy(() -> root.getObjectAsBytes(r -> r.bucket(bucket).key("other/y")))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    /**
     * The worker's rights on MinIO come from its {@code to} policy alone (item 256; deploy/README.md, "Delete rights"):
     * a user holding only that policy, in no group and with keys of its own, lists and deletes beneath the prefix and
     * is refused everywhere else, outside the prefix in its bucket and in a second bucket, whatever prefixes the
     * catalog gives the worker. A user of its own, so the other test's worker policy cannot change what this one holds.
     */
    @Test
    @DisplayName("a worker user holding only its own policy lists and deletes beneath the prefix, and nowhere else")
    void aWorkerHoldingOnlyItsOwnPolicyIsRefusedOutsideIt() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.SILO, "MinIO's user policies are checked on Silo");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("worker-only-to");
        String elsewhere = store.bucket("worker-only-to-elsewhere");
        store.siloUser(ONLY_TO_USER, ONLY_TO_SECRET, "nuna-worker-only-to",
                DeletePolicy.workerPolicy(List.of(ObjectLocation.parse("s3://" + bucket + "/tables"))));
        try (S3Client root = store.client();
             S3Client worker = store.client(keys(ONLY_TO_USER, ONLY_TO_SECRET))) {
            for (String key : List.of("tables/t/a", "tables/t/b", "tablespace/t/a", "other/x")) {
                root.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(key));
            }
            root.putObject(r -> r.bucket(elsewhere).key("tables/t/a"), RequestBody.fromString("elsewhere"));

            assertThat(worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/t/")).contents())
                    .extracting(S3Object::key).containsExactly("tables/t/a", "tables/t/b");
            worker.deleteObject(r -> r.bucket(bucket).key("tables/t/a"));
            assertThat(worker.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                    ObjectIdentifier.builder().key("tables/t/b").build()))).errors()).isEmpty();

            for (String key : List.of("tablespace/t/a", "other/x")) {
                assertDenied(() -> worker.deleteObject(r -> r.bucket(bucket).key(key)));
                assertNotDeleted(worker.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                        ObjectIdentifier.builder().key(key).build()))), key);
            }
            assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket).prefix("other/")));
            assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket)));
            assertDenied(() -> worker.deleteObject(r -> r.bucket(elsewhere).key("tables/t/a")));
            assertNotDeleted(worker.deleteObjects(r -> r.bucket(elsewhere).delete(d -> d.objects(
                    ObjectIdentifier.builder().key("tables/t/a").build()))), "tables/t/a");
            assertDenied(() -> worker.listObjectsV2(r -> r.bucket(elsewhere).prefix("tables/")));

            assertThat(root.listObjectsV2(r -> r.bucket(bucket)).contents()).extracting(S3Object::key)
                    .containsExactly("other/x", "tablespace/t/a");
            assertThat(root.getObjectAsBytes(r -> r.bucket(elsewhere).key("tables/t/a")).asUtf8String())
                    .isEqualTo("elsewhere");
        }
    }

    private static AwsCredentialsProvider keys(String user, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(user, secret));
    }

    /**
     * A session the catalog's user takes from MinIO's STS with no session policy, as vended credentials are taken
     * (MinIO derives them from the calling user and needs no role): it carries the user's policies, the deny included.
     */
    private static AwsCredentialsProvider session(TestS3 store) {
        try (StsClient sts = StsClient.builder().endpointOverride(store.endpoint).region(Region.of(TestS3.REGION))
                .credentialsProvider(keys(CATALOG_USER, CATALOG_SECRET)).build()) {
            Credentials session = sts.assumeRole(r -> r.roleSessionName("delete-policy-test").durationSeconds(900))
                    .credentials();
            return StaticCredentialsProvider.create(AwsSessionCredentials.create(session.accessKeyId(),
                    session.secretAccessKey(), session.sessionToken()));
        }
    }

    /** A rule that would delete every table file a day after it was written, with no DeleteObject call. */
    private static BucketLifecycleConfiguration expireTables() {
        return BucketLifecycleConfiguration.builder().rules(LifecycleRule.builder().id("expire-tables")
                .filter(LifecycleRuleFilter.builder().prefix("tables/").build()).status(ExpirationStatus.ENABLED)
                .expiration(LifecycleExpiration.builder().days(1).build()).build()).build();
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(S3Exception.class,
                e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    /** A multi-object delete answers 200 and refuses each key in its body, as S3FileIO's deletes would see. */
    private static void assertNotDeleted(DeleteObjectsResponse response, String key) {
        assertThat(response.deleted()).isEmpty();
        assertThat(response.errors()).extracting(S3Error::key, S3Error::code)
                .containsExactly(tuple(key, "AccessDenied"));
    }
}
