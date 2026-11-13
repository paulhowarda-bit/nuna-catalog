package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The policies that leave delete to the maintenance worker alone (D30), as text; DeletePolicyCephTest applies one. */
class DeletePolicyTest {

    private static final ObjectLocation TABLES = ObjectLocation.parse("s3://lake/tables");
    private static final ObjectLocation EU = ObjectLocation.parse("s3://lake/eu");
    private static final String ROLE = "arn:aws:iam::123456789012:role/keel-maintenance";

    @Test
    @DisplayName("on AWS every principal but the worker's role is denied delete beneath the prefixes, and lifecycle rules"
            + " and bucket policies on the bucket, and nothing else")
    void aws() {
        assertThat(DeletePolicy.bucketPolicy(StoreKind.S3, "us-east-1", "lake",
                List.of(new DeletePolicy.Worker(ROLE, List.of(TABLES, EU))))).isEqualTo(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"KeelOnlyTheWorkerDeletes0\",\"Effect\":\"Deny\","
                        + "\"Principal\":\"*\",\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake/tables/*\",\"arn:aws:s3:::lake/eu/*\"],"
                        + "\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":\"" + ROLE + "\"}}},"
                        + "{\"Sid\":\"KeelOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\",\"Principal\":\"*\","
                        + "\"Action\":[\"s3:PutLifecycleConfiguration\",\"s3:PutBucketPolicy\",\"s3:DeleteBucketPolicy\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake\"],"
                        + "\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":[\"" + ROLE + "\"]}}}]}");
        assertThat(DeletePolicy.bucketPolicy(StoreKind.S3, "cn-north-1", "lake",
                List.of(new DeletePolicy.Worker("arn:aws-cn:iam::1:role/w", List.of(TABLES)))))
                .contains("arn:aws-cn:s3:::lake/tables/*");
        assertThat(DeletePolicy.bucketPolicy(StoreKind.S3, "us-east-1", "lake",
                List.of(new DeletePolicy.Worker(ROLE, List.of(ObjectLocation.parse("s3://lake"))))))
                .contains("\"Resource\":[\"arn:aws:s3:::lake/*\"]");
    }

    @Test
    @DisplayName("on Ceph the worker is named in NotPrincipal and allowed to delete there, since nothing else grants it;"
            + " everyone else is denied lifecycle rules on the bucket")
    void ceph() {
        String worker = "arn:aws:iam:::user/keel-maintenance";
        assertThat(DeletePolicy.bucketPolicy(StoreKind.CEPH, "us-east-1", "lake",
                List.of(new DeletePolicy.Worker(worker, List.of(TABLES))))).isEqualTo(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"KeelOnlyTheWorkerDeletes0\",\"Effect\":\"Deny\","
                        + "\"NotPrincipal\":{\"AWS\":[\"" + worker + "\"]},"
                        + "\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake/tables/*\"]},"
                        + "{\"Sid\":\"KeelTheWorkerDeletes0\",\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"" + worker + "\"]},"
                        + "\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake/tables/*\"]},"
                        + "{\"Sid\":\"KeelOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\","
                        + "\"NotPrincipal\":{\"AWS\":[\"" + worker + "\"]},\"Action\":[\"s3:PutLifecycleConfiguration\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake\"]}]}");
    }

    @Test
    @DisplayName("profiles sharing a bucket each keep their own worker in the one policy the bucket holds")
    void sharedBucket() {
        String policy = DeletePolicy.bucketPolicy(StoreKind.S3, "us-east-1", "lake", List.of(
                new DeletePolicy.Worker(ROLE, List.of(TABLES)),
                new DeletePolicy.Worker("arn:aws:iam::123456789012:role/eu-maintenance", List.of(EU))));

        assertThat(policy).contains("KeelOnlyTheWorkerDeletes0", "KeelOnlyTheWorkerDeletes1",
                "\"Resource\":[\"arn:aws:s3:::lake/eu/*\"],\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":"
                        + "\"arn:aws:iam::123456789012:role/eu-maintenance\"}}");
        assertThat(policy).as("one bucket-settings deny, which neither worker falls under")
                .containsOnlyOnce("KeelOnlyTheWorkerSetsTheBucket")
                .contains("\"Resource\":[\"arn:aws:s3:::lake\"],\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":"
                        + "[\"" + ROLE + "\",\"arn:aws:iam::123456789012:role/eu-maintenance\"]}}");
    }

    @Test
    @DisplayName("MinIO gets a user policy, since it enforces no bucket policy on signed requests; it denies lifecycle"
            + " rules and bucket policies on the bucket too")
    void minio() {
        assertThat(DeletePolicy.userPolicy(List.of(TABLES, EU))).isEqualTo(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"KeelOnlyTheWorkerDeletes\",\"Effect\":\"Deny\","
                        + "\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake/tables/*\",\"arn:aws:s3:::lake/eu/*\"]},"
                        + "{\"Sid\":\"KeelOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\","
                        + "\"Action\":[\"s3:PutLifecycleConfiguration\",\"s3:PutBucketPolicy\",\"s3:DeleteBucketPolicy\"],"
                        + "\"Resource\":[\"arn:aws:s3:::lake\"]}]}");
        assertThatThrownBy(() -> DeletePolicy.bucketPolicy(StoreKind.MINIO, "us-east-1", "lake",
                List.of(new DeletePolicy.Worker("keel-maintenance", List.of(TABLES)))))
                .hasMessageContaining("user policy");
    }

    @Test
    @DisplayName("a principal must suit the store and need no escaping, and every prefix must be in the bucket")
    void refusals() {
        DeletePolicy.requirePrincipal(StoreKind.MINIO, "keel-maintenance");
        assertThatThrownBy(() -> DeletePolicy.requirePrincipal(StoreKind.S3, "keel-maintenance"))
                .hasMessageContaining("must be an ARN");
        assertThatThrownBy(() -> DeletePolicy.requirePrincipal(StoreKind.MINIO, ROLE))
                .hasMessageContaining("must be a MinIO user name");
        assertThatThrownBy(() -> DeletePolicy.requirePrincipal(StoreKind.CEPH, "arn:aws:iam:::user/x\",\"*"))
                .hasMessageContaining("character outside");
        assertThatThrownBy(() -> DeletePolicy.requirePrincipal(StoreKind.S3, null))
                .hasMessageContaining("character outside");
        assertThatThrownBy(() -> DeletePolicy.bucketPolicy(StoreKind.S3, "us-east-1", "other",
                List.of(new DeletePolicy.Worker(ROLE, List.of(TABLES))))).hasMessageContaining("not in bucket other");
    }
}
