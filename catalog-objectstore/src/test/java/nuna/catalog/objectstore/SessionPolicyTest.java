package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionPolicyTest {

    private static final ObjectLocation ORDERS = ObjectLocation.parse("s3://lake/t/orders");
    /** A synthetic key ARN, in AWS's documentation account. */
    private static final String KEY = "arn:aws:kms:us-east-1:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final Optional<String> NO_KEY = Optional.empty();

    @Test
    @DisplayName("objects and listing are allowed only beneath the table's prefix with its trailing slash")
    void scopedToTheTablePrefix() {
        String policy = SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1", NO_KEY);

        assertThat(policy).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:AbortMultipartUpload\","
                + "\"s3:ListMultipartUploadParts\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
    }

    @Test
    @DisplayName("with the profile's KMS key, a write must ask for SSE-KMS with exactly that key, and the session may"
            + " use the key (D26)")
    void writesOnlyWithTheProfileKey() {
        String policy = SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1", Optional.of(KEY));

        assertThat(policy).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:AbortMultipartUpload\","
                + "\"s3:ListMultipartUploadParts\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::lake/t/orders/*\","
                + "\"Condition\":{\"StringEquals\":{\"s3:x-amz-server-side-encryption\":\"aws:kms\","
                + "\"s3:x-amz-server-side-encryption-aws-kms-key-id\":\"" + KEY + "\"}}},"
                + "{\"Effect\":\"Allow\",\"Action\":[\"kms:GenerateDataKey\",\"kms:Decrypt\"],\"Resource\":\"" + KEY
                + "\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
    }

    @Test
    @DisplayName("a writer on one branch only reads the whole table but writes only beneath the branch's place,"
            + " with the profile's key there (backlog 113)")
    void aBranchWriterWritesOnlyBeneathItsPlace() {
        String branch = "branches/0b4f6c1e-6a3e-4b8e-9d2a-5f1c7e2a9b30";

        assertThat(SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1", NO_KEY, Optional.of(branch)))
                .isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:AbortMultipartUpload\","
                + "\"s3:ListMultipartUploadParts\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/" + branch + "/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1", Optional.of(KEY),
                Optional.of(branch))).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:AbortMultipartUpload\",\"s3:ListMultipartUploadParts\"],"
                + "\"Resource\":\"arn:aws:s3:::lake/t/orders/" + branch + "/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::lake/t/orders/" + branch
                + "/*\",\"Condition\":{\"StringEquals\":{\"s3:x-amz-server-side-encryption\":\"aws:kms\","
                + "\"s3:x-amz-server-side-encryption-aws-kms-key-id\":\"" + KEY + "\"}}},"
                + "{\"Effect\":\"Allow\",\"Action\":[\"kms:GenerateDataKey\",\"kms:Decrypt\"],\"Resource\":\"" + KEY
                + "\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
        assertThatThrownBy(() -> SessionPolicy.forTable(ORDERS, Access.READ, "us-east-1", NO_KEY, Optional.of(branch)))
                .as("a reader writes nowhere").isInstanceOf(IllegalArgumentException.class);
        for (String bad : List.of("branches/*", "../main", "branches/", "", "a\"b", "Branches/x")) {
            assertThatThrownBy(() -> SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1", NO_KEY,
                    Optional.of(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("with a KMS key, read access may decrypt but neither write nor generate data keys")
    void readersOnlyDecrypt() {
        String policy = SessionPolicy.forTable(ORDERS, Access.READ, "us-east-1", Optional.of(KEY));

        assertThat(policy).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":[\"kms:Decrypt\"],\"Resource\":\"" + KEY + "\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
    }

    @Test
    @DisplayName("read access cannot write, and no access level deletes (D30)")
    void accessLevels() {
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "us-east-1", NO_KEY))
                .contains("\"Action\":[\"s3:GetObject\"]")
                .doesNotContain("s3:PutObject", "s3:DeleteObject");
        for (Access access : Access.values()) {
            for (Optional<String> key : List.of(NO_KEY, Optional.of(KEY))) {
                assertThat(SessionPolicy.forTable(ORDERS, access, "us-east-1", key)).as(access.name() + " " + key)
                        .doesNotContain("Delete");
            }
        }
    }

    @Test
    @DisplayName("ARNs use the region's partition")
    void partitions() {
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "cn-north-1", NO_KEY)).contains("arn:aws-cn:s3:::lake/");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "us-gov-west-1", NO_KEY))
                .contains("arn:aws-us-gov:s3:::lake/");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "eu-west-2", NO_KEY)).contains("arn:aws:s3:::lake/");
    }

    @Test
    @DisplayName("a whole bucket is never vended")
    void noWholeBucket() {
        assertThatThrownBy(() -> SessionPolicy.forTable(ObjectLocation.parse("s3://lake"), Access.READ, "us-east-1",
                NO_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a whole bucket");
    }

    @Test
    @DisplayName("a catalog-assigned location fits far inside 2,048 characters, with a KMS key too; a policy over the"
            + " limit is refused")
    void sizeLimit() {
        ObjectLocation assigned = ObjectLocation.parse("s3://lake/tables").child(UUID.randomUUID().toString());
        assertThat(SessionPolicy.forTable(assigned, Access.READ_WRITE, "us-east-1", NO_KEY).length()).isLessThan(500);
        assertThat(SessionPolicy.forTable(assigned, Access.READ_WRITE, "us-east-1", Optional.of(KEY)).length())
                .isLessThan(1000);
        assertThat(SessionPolicy.forTable(assigned, Access.READ_WRITE, "us-east-1", Optional.of(KEY),
                Optional.of("branches/" + UUID.randomUUID())).length()).as("a writer on one branch only").isLessThan(1300);

        String longSegment = "x".repeat(1000);
        ObjectLocation tooLong = ObjectLocation.parse("s3://lake/" + longSegment);
        assertThatThrownBy(() -> SessionPolicy.forTable(tooLong, Access.READ, "us-east-1", NO_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over the STS limit of 2048");
    }
}
