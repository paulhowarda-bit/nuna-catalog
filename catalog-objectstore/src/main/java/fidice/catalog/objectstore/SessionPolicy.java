package fidice.catalog.objectstore;

import java.util.stream.Collectors;

/**
 * The STS session policy that scopes vended credentials to one table (D21). The role's own policy
 * may allow more; STS grants only what both allow.
 *
 * <p>Objects are allowed under {@code <key>/*} and listing under the {@code <key>/} prefix, both with
 * the trailing slash, so a table at {@code orders} gets nothing in {@code orders2}. The location is a
 * canonical {@link ObjectLocation}, so its bucket and key hold no character a policy reads specially:
 * no wildcard, no policy variable, nothing JSON must escape.
 */
public final class SessionPolicy {

    /** STS refuses a longer inline session policy (D21). */
    public static final int MAX_CHARS = 2048;

    private SessionPolicy() {
    }

    /**
     * The policy for {@code table}, with ARNs in the partition of {@code region}.
     *
     * @throws IllegalArgumentException when it would not fit in {@link #MAX_CHARS}, or the location is a whole bucket
     */
    public static String forTable(ObjectLocation table, Access access, String region) {
        if (table.segments().isEmpty()) {
            throw new IllegalArgumentException("credentials are vended for a table's prefix, not a whole bucket: " + table);
        }
        String bucketArn = "arn:" + partition(region) + ":s3:::" + table.bucket();
        String prefix = table.key() + "/";
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[" + quoted(access) + "],"
                + "\"Resource\":\"" + bucketArn + "/" + prefix + "*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"" + bucketArn + "\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"" + prefix + "*\"}}}]}";
        if (policy.length() > MAX_CHARS) {
            throw new IllegalArgumentException("the session policy for " + table + " is " + policy.length()
                    + " characters, over the STS limit of " + MAX_CHARS);
        }
        return policy;
    }

    /** The AWS partition a region belongs to; on-prem stores accept {@code aws}. */
    static String partition(String region) {
        if (region.startsWith("cn-")) {
            return "aws-cn";
        }
        if (region.startsWith("us-gov-")) {
            return "aws-us-gov";
        }
        return "aws";
    }

    private static String quoted(Access access) {
        return access.objectActions().stream().map(action -> "\"" + action + "\"").collect(Collectors.joining(","));
    }
}
