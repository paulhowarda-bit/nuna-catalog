package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionPolicyTest {

    private static final ObjectLocation ORDERS = ObjectLocation.parse("s3://lake/t/orders");

    @Test
    @DisplayName("objects and listing are allowed only beneath the table's prefix with its trailing slash")
    void scopedToTheTablePrefix() {
        String policy = SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1");

        assertThat(policy).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:AbortMultipartUpload\","
                + "\"s3:ListMultipartUploadParts\"],\"Resource\":\"arn:aws:s3:::lake/t/orders/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lake\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"t/orders/*\"}}}]}");
    }

    @Test
    @DisplayName("read access cannot write and only the maintenance level can delete")
    void accessLevels() {
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "us-east-1"))
                .contains("\"Action\":[\"s3:GetObject\"]")
                .doesNotContain("s3:PutObject", "s3:DeleteObject");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ_WRITE, "us-east-1")).doesNotContain("Delete");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ_WRITE_DELETE, "us-east-1")).contains("\"s3:DeleteObject\"");
    }

    @Test
    @DisplayName("ARNs use the region's partition")
    void partitions() {
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "cn-north-1")).contains("arn:aws-cn:s3:::lake/");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "us-gov-west-1")).contains("arn:aws-us-gov:s3:::lake/");
        assertThat(SessionPolicy.forTable(ORDERS, Access.READ, "eu-west-2")).contains("arn:aws:s3:::lake/");
    }

    @Test
    @DisplayName("a whole bucket is never vended")
    void noWholeBucket() {
        assertThatThrownBy(() -> SessionPolicy.forTable(ObjectLocation.parse("s3://lake"), Access.READ, "us-east-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a whole bucket");
    }

    @Test
    @DisplayName("a catalog-assigned location fits far inside 2,048 characters; a policy over the limit is refused")
    void sizeLimit() {
        ObjectLocation assigned = ObjectLocation.parse("s3://lake/tables").child(UUID.randomUUID().toString());
        assertThat(SessionPolicy.forTable(assigned, Access.READ_WRITE_DELETE, "us-east-1").length()).isLessThan(500);

        String longSegment = "x".repeat(1000);
        ObjectLocation tooLong = ObjectLocation.parse("s3://lake/" + longSegment);
        assertThatThrownBy(() -> SessionPolicy.forTable(tooLong, Access.READ, "us-east-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over the STS limit of 2048");
    }
}
