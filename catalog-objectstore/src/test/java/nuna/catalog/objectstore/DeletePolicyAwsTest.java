package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import nuna.catalog.tests.s3.TestS3;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
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
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * The delete policy applied to a real AWS S3 bucket (D30, backlog 4), as the catalog gives it: the text is
 * {@link DeletePolicy#bucketPolicy} for kind S3, the run's region and bucket, and one worker, the maintenance principal
 * with the prefix {@code s3://<bucket>/tables}. That is the call {@code GET /management/v1/maintenance/delete-policies}
 * makes ({@code DeletePolicyRoutes}) for a profile whose allowed prefix that is and whose maintenance principal the
 * worker's role is, so an operator applies this text.
 *
 * <p>The worker is this run's role {@code nuna-test-<run id>-maintenance}, whose own policy lets it list and delete in
 * the run's buckets and remove a bucket policy. Beneath the prefix it deletes and lists; the account's own user
 * (the run's sign-in, an administrator) and a session of the role vended credentials come from, which allows every S3
 * action on the run's buckets, may neither delete there, nor set a lifecycle rule, nor change or remove the policy.
 * Outside the prefix they delete as before. Only the worker can then remove the policy, so the test first checks that
 * it can, and removes it as the worker at the end; {@link TestS3} does so again at exit before it empties the buckets.
 *
 * <p>AWS only, with {@code -Ps3=aws}: heavy, and skipped on Silo and Ceph ({@link DeletePolicyCephTest}).
 */
@Tag("heavy")
class DeletePolicyAwsTest {

    /** A key beneath the prefix that is never written: deleting it shows whether the policy is in force yet. */
    private static final String PROBE = "tables/policy-probe";
    /** How long a bucket policy may take to come into force, or out of it, after it is put or removed. */
    private static final Duration PROPAGATION = Duration.ofMinutes(2);

    @Test
    @DisplayName("only the worker's role deletes and lists beneath the prefix, nobody else sets a lifecycle rule or"
            + " changes the policy, and outside the prefix nothing changes")
    void onlyTheWorkerDeletes() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.AWS, "the bucket policy is checked on AWS here");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("delete-policy");
        String policy = DeletePolicy.bucketPolicy(StoreKind.S3, TestS3.REGION, bucket, List.of(new DeletePolicy.Worker(
                store.maintenancePrincipal(), List.of(ObjectLocation.parse("s3://" + bucket + "/tables")))));
        try (StsClient sts = StsClient.builder().region(Region.of(TestS3.REGION)).credentialsProvider(store.credentials)
                .build();
             StsAssumeRoleCredentialsProvider roleSessions = StsAssumeRoleCredentialsProvider.builder().stsClient(sts)
                     .refreshRequest(r -> r.roleArn(store.roleArn.orElseThrow()).roleSessionName("delete-policy-test")
                             .durationSeconds(900)).build();
             S3Client admin = store.client();
             S3Client role = store.client(roleSessions);
             S3Client worker = store.client(store.maintenanceCredentials())) {
            for (String key : List.of("tables/t/a", "tables/t/b", "tables/t/c", "tables/t/admin", "tables/t/role",
                    "other/x", "other/y")) {
                admin.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(key));
            }
            // Without the policy the admin and the role delete there and set and remove a lifecycle rule: the
            // refusals below are the policy's.
            admin.deleteObject(r -> r.bucket(bucket).key("tables/t/admin"));
            role.deleteObject(r -> r.bucket(bucket).key("tables/t/role"));
            for (S3Client client : List.of(admin, role)) {
                client.putBucketLifecycleConfiguration(r -> r.bucket(bucket).lifecycleConfiguration(expireTables()));
                client.deleteBucketLifecycle(r -> r.bucket(bucket));
            }
            try {
                admin.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
                awaitInForce(admin, bucket, true);
                // The way out works before anything relies on it: the admin cannot remove the policy, the worker can.
                assertDenied(() -> admin.deleteBucketPolicy(r -> r.bucket(bucket)));
                worker.deleteBucketPolicy(r -> r.bucket(bucket));
                awaitInForce(admin, bucket, false);
                admin.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
                awaitInForce(admin, bucket, true);

                for (S3Client other : List.of(admin, role)) {
                    assertDenied(() -> other.deleteObject(r -> r.bucket(bucket).key("tables/t/a")));
                    assertNotDeleted(other.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                            ObjectIdentifier.builder().key("tables/t/a").build()))), "tables/t/a");
                    assertDenied(() -> other.putBucketLifecycleConfiguration(r -> r.bucket(bucket)
                            .lifecycleConfiguration(expireTables())));
                    assertDenied(() -> other.putBucketPolicy(r -> r.bucket(bucket).policy(policy)));
                    assertDenied(() -> other.deleteBucketPolicy(r -> r.bucket(bucket)));
                }
                worker.deleteObject(r -> r.bucket(bucket).key("tables/t/b"));
                assertThat(worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/t/")).contents())
                        .extracting(S3Object::key).containsExactly("tables/t/a", "tables/t/c");
                // Outside the prefix the policy changes nothing, and beneath it only deletes are refused.
                admin.deleteObject(r -> r.bucket(bucket).key("other/y"));
                role.deleteObject(r -> r.bucket(bucket).key("other/x"));
                admin.putObject(r -> r.bucket(bucket).key("tables/t/d"), RequestBody.fromString("still writable"));
                role.putObject(r -> r.bucket(bucket).key("tables/t/e"), RequestBody.fromString("still writable"));

                assertThat(admin.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/a")).asUtf8String())
                        .isEqualTo("tables/t/a");
                assertThatThrownBy(() -> admin.getObjectAsBytes(r -> r.bucket(bucket).key("tables/t/b")))
                        .isInstanceOf(NoSuchKeyException.class);
                assertThat(admin.listObjectsV2(r -> r.bucket(bucket).prefix("other/")).contents()).isEmpty();
            } finally {
                // As the worker: the objects beneath the prefix, then the policy; then wait until the admin may delete
                // there again, so the run's cleanup can empty the bucket.
                List<ObjectIdentifier> left = worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/")).contents()
                        .stream().map(object -> ObjectIdentifier.builder().key(object.key()).build()).toList();
                if (!left.isEmpty()) {
                    worker.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(left)));
                }
                worker.deleteBucketPolicy(r -> r.bucket(bucket));
                awaitInForce(admin, bucket, false);
            }
        }
    }

    /**
     * The worker's rights on AWS come from the bucket policy alone (item 256; deploy/README.md, "Delete rights"): a
     * worker role of the test's own, with no policy of its own, lists and deletes beneath the prefix the delete policy
     * names it for, and is refused everywhere else, outside the prefix and in a second bucket with no policy. So the
     * worker deletes only where the stores' policies name it, whatever prefixes the catalog gives it.
     */
    @Test
    @DisplayName("a worker role with no policy of its own lists and deletes beneath the prefix, and nowhere else")
    void theBucketPolicyAloneLetsTheWorkerDelete() {
        assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.AWS, "the bucket policy is checked on AWS here");
        TestS3 store = TestS3.get();
        String bucket = store.bucket("bare-worker");
        String elsewhere = store.bucket("bare-worker-elsewhere");
        String account;
        try (StsClient sts = StsClient.builder().region(Region.of(TestS3.REGION)).credentialsProvider(store.credentials)
                .build()) {
            account = sts.getCallerIdentity().account();
        }
        String name = "nuna-test-bare-worker-" + UUID.randomUUID().toString().substring(0, 8);
        try (IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(store.credentials)
                .build()) {
            String role = iam.createRole(r -> r.roleName(name).maxSessionDuration(3600)
                    .description("a nuna test's worker role with no policy of its own; deleted when the test ends")
                    .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                            + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                            + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\",\"sts:SetSourceIdentity\"]}]}")).role().arn();
            boolean removed = true;
            try (StsClient sts = StsClient.builder().region(Region.of(TestS3.REGION))
                    .credentialsProvider(store.credentials).build();
                 StsAssumeRoleCredentialsProvider sessions = StsAssumeRoleCredentialsProvider.builder().stsClient(sts)
                         .refreshRequest(r -> r.roleArn(role).roleSessionName("bare-worker").durationSeconds(900))
                         .build();
                 S3Client admin = store.client();
                 S3Client worker = store.client(sessions)) {
                awaitAssumable(sessions);
                for (String key : List.of("tables/t/a", "tables/t/b", "other/x")) {
                    admin.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(key));
                }
                admin.putObject(r -> r.bucket(elsewhere).key("tables/t/a"), RequestBody.fromString("elsewhere"));
                // The catalog's text for this role, and one grant more, in the bucket policy too, so the role can
                // remove the policy at the end: nobody else may.
                String generated = DeletePolicy.bucketPolicy(StoreKind.S3, TestS3.REGION, bucket, List.of(
                        new DeletePolicy.Worker(role, List.of(ObjectLocation.parse("s3://" + bucket + "/tables")))));
                String policy = generated.substring(0, generated.length() - 2) + ",{\"Sid\":\"TestRemovesThePolicy\","
                        + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"" + role + "\"]},"
                        + "\"Action\":\"s3:DeleteBucketPolicy\",\"Resource\":\"arn:aws:s3:::" + bucket + "\"}]}";
                removed = false;
                admin.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
                try {
                    awaitInForce(admin, bucket, true);

                    assertThat(worker.listObjectsV2(r -> r.bucket(bucket).prefix("tables/t/")).contents())
                            .extracting(S3Object::key).containsExactly("tables/t/a", "tables/t/b");
                    worker.deleteObject(r -> r.bucket(bucket).key("tables/t/a"));
                    assertThat(worker.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                            ObjectIdentifier.builder().key("tables/t/b").build()))).errors()).isEmpty();
                    assertThat(admin.listObjectsV2(r -> r.bucket(bucket).prefix("tables/")).contents()).isEmpty();

                    assertDenied(() -> worker.deleteObject(r -> r.bucket(bucket).key("other/x")));
                    assertDenied(() -> worker.listObjectsV2(r -> r.bucket(bucket).prefix("other/")));
                    assertDenied(() -> worker.deleteObject(r -> r.bucket(elsewhere).key("tables/t/a")));
                    assertDenied(() -> worker.listObjectsV2(r -> r.bucket(elsewhere).prefix("tables/")));
                    assertThat(admin.getObjectAsBytes(r -> r.bucket(elsewhere).key("tables/t/a")).asUtf8String())
                            .isEqualTo("elsewhere");
                } finally {
                    worker.deleteBucketPolicy(r -> r.bucket(bucket));
                    awaitInForce(admin, bucket, false);
                    removed = true;
                }
            } finally {
                if (removed) {
                    iam.deleteRole(r -> r.roleName(name));
                } else {
                    System.err.println("DeletePolicyAwsTest: kept role " + role + ", the only principal that can"
                            + " remove the policy of bucket " + bucket + "; remove it as that role, then the role");
                }
            }
        }
    }

    /** Waits until STS lets the run's sign-in assume a role just made: IAM takes some seconds to say so. */
    private static void awaitAssumable(StsAssumeRoleCredentialsProvider sessions) {
        Instant deadline = Instant.now().plus(PROPAGATION);
        while (true) {
            try {
                sessions.resolveCredentials();
                return;
            } catch (RuntimeException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** A rule that would delete every table file a day after it was written, with no DeleteObject call. */
    private static BucketLifecycleConfiguration expireTables() {
        return BucketLifecycleConfiguration.builder().rules(LifecycleRule.builder().id("expire-tables")
                .filter(LifecycleRuleFilter.builder().prefix("tables/").build()).status(ExpirationStatus.ENABLED)
                .expiration(LifecycleExpiration.builder().days(1).build()).build()).build();
    }

    /**
     * Waits until the admin's delete beneath the prefix is refused ({@code inForce}) or allowed: S3 applies a new or
     * removed bucket policy within seconds, not at once. The key deleted is never written, so nothing is lost.
     */
    private static void awaitInForce(S3Client admin, String bucket, boolean inForce) {
        Instant deadline = Instant.now().plus(PROPAGATION);
        while (true) {
            boolean refused;
            try {
                admin.deleteObject(r -> r.bucket(bucket).key(PROBE));
                refused = false;
            } catch (S3Exception e) {
                if (e.statusCode() != 403) {
                    throw e;
                }
                refused = true;
            }
            if (refused == inForce) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the bucket policy of " + bucket + " was still " + (inForce ? "not " : "")
                        + "in force after " + PROPAGATION);
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
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
