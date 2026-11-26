package nuna.catalog.objectstore;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The STS session policy that scopes vended credentials to one table (D21). The role's own policy
 * may allow more; STS grants only what both allow.
 *
 * <p>Objects are allowed under {@code <key>/*} and listing under the {@code <key>/} prefix, both with
 * the trailing slash, so a table at {@code orders} gets nothing in {@code orders2}. The location is a
 * canonical {@link ObjectLocation}, so its bucket and key hold no character a policy reads specially:
 * no wildcard, no policy variable, nothing JSON must escape; nor does a profile's KMS key, a checked key ARN.
 */
public final class SessionPolicy {

    /** STS refuses a longer inline session policy (D21). */
    public static final int MAX_CHARS = 2048;

    private static final String PUT = "s3:PutObject";
    /** A place beneath a table's prefix that a session may be confined to write in: plain segments only. */
    private static final Pattern UNDER = Pattern.compile("[a-z0-9-]+(/[a-z0-9-]+)*");

    private SessionPolicy() {
    }

    /**
     * The policy for {@code table}, with ARNs in the partition of {@code region}. With the profile's KMS key (D26,
     * amended 2026-11-19) a write must name exactly that key: PutObject, which covers CreateMultipartUpload, is allowed
     * only when the request asks for SSE-KMS with that key ARN, so one with no encryption header, with SSE-S3 or with
     * any other key is refused before S3 calls KMS. The session may also use the key as S3 does on its behalf: decrypt,
     * and for a writer, generate data keys. Without a key the policy is as it was before keys.
     *
     * @param kmsKey the profile's KMS key ARN, if it names one ({@link StorageProfile#requireKmsKey})
     * @throws IllegalArgumentException when it would not fit in {@link #MAX_CHARS}, or the location is a whole bucket
     */
    public static String forTable(ObjectLocation table, Access access, String region, Optional<String> kmsKey) {
        return forTable(table, access, region, kmsKey, Optional.empty());
    }

    /**
     * {@link #forTable(ObjectLocation, Access, String, Optional)}, writing only beneath {@code writeUnder}: a
     * read-write session confined so reads the whole table but writes, and with a key encrypts, only beneath
     * {@code <key>/<writeUnder>/}, as a writer on one branch only does (backlog 113). Without it the session writes
     * the whole prefix, as before.
     *
     * @param writeUnder plain segments beneath the table's prefix, such as {@code branches/<ref id>}; only for
     *                   {@link Access#READ_WRITE}
     */
    public static String forTable(ObjectLocation table, Access access, String region, Optional<String> kmsKey,
                                  Optional<String> writeUnder) {
        if (table.segments().isEmpty()) {
            throw new IllegalArgumentException("credentials are vended for a table's prefix, not a whole bucket: " + table);
        }
        if (writeUnder.isPresent() && (access != Access.READ_WRITE || !UNDER.matcher(writeUnder.get()).matches())) {
            throw new IllegalArgumentException("a session confined to write beneath " + writeUnder.get()
                    + " must be read-write, beneath plain segments");
        }
        String bucketArn = "arn:" + partition(region) + ":s3:::" + table.bucket();
        String prefix = table.key() + "/";
        String objects = bucketArn + "/" + prefix + "*";
        String statements;
        if (writeUnder.isEmpty()) {
            statements = "{\"Effect\":\"Allow\",\"Action\":[" + unconditioned(access, kmsKey) + "],"
                    + "\"Resource\":\"" + objects + "\"},"
                    + kmsKey.map(key -> withKey(access, objects, key)).orElse("");
        } else {
            String written = bucketArn + "/" + prefix + writeUnder.get() + "/*";
            List<String> writes = access.objectActions().stream()
                    .filter(action -> !Access.READ.objectActions().contains(action))
                    .filter(action -> kmsKey.isEmpty() || !action.equals(PUT)).toList();
            statements = "{\"Effect\":\"Allow\",\"Action\":[" + unconditioned(Access.READ, kmsKey) + "],"
                    + "\"Resource\":\"" + objects + "\"},"
                    + "{\"Effect\":\"Allow\",\"Action\":[" + quoted(writes) + "],\"Resource\":\"" + written + "\"},"
                    + kmsKey.map(key -> withKey(access, written, key)).orElse("");
        }
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements
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

    /** The object actions allowed with no condition: with a KMS key, PutObject has a statement of its own. */
    private static String unconditioned(Access access, Optional<String> kmsKey) {
        return quoted(access.objectActions().stream().filter(action -> kmsKey.isEmpty() || !action.equals(PUT)).toList());
    }

    private static String quoted(List<String> actions) {
        return actions.stream().map(action -> "\"" + action + "\"").collect(Collectors.joining(","));
    }

    /** A writer's PutObject, only with exactly the key, then the key's use: each statement ends with a comma. */
    private static String withKey(Access access, String objects, String key) {
        boolean writes = access.objectActions().contains(PUT);
        return (writes ? "{\"Effect\":\"Allow\",\"Action\":\"" + PUT + "\",\"Resource\":\"" + objects + "\","
                + "\"Condition\":{\"StringEquals\":{\"s3:x-amz-server-side-encryption\":\"aws:kms\","
                + "\"s3:x-amz-server-side-encryption-aws-kms-key-id\":\"" + key + "\"}}}," : "")
                + "{\"Effect\":\"Allow\",\"Action\":[" + (writes ? "\"kms:GenerateDataKey\"," : "")
                + "\"kms:Decrypt\"],\"Resource\":\"" + key + "\"},";
    }
}
