package nuna.catalog.objectstore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Who may delete a table's files (D30): the maintenance worker alone, with its own storage identity. Vended
 * credentials never carry delete ({@link Access}); these policies take it from every other principal the store
 * knows as well, the catalog's own role or user included, so neither a vended session nor the catalog's own key
 * can delete, and with it the bucket settings that would delete for them or drop the deny ({@link #LIFECYCLE},
 * {@link #BUCKET_POLICY}). They also give the worker what it needs beneath its prefixes, delete and the listing
 * its orphan scans and purges make ({@link #LISTING}), so they are all an operator applies. The catalog never
 * applies them, since its own credentials would then need the right to change them back: an operator applies
 * them once, from {@code GET /management/v1/maintenance/delete-policies}.
 *
 * <p>Locations come in as canonical {@link ObjectLocation}s and principals pass {@link #requirePrincipal}, so
 * nothing in a policy needs JSON escaping or reads as a wildcard.
 */
public final class DeletePolicy {

    /** What is denied: deleting an object, or one version of it in a versioned bucket. */
    static final List<String> DELETES = List.of("s3:DeleteObject", "s3:DeleteObjectVersion");
    /**
     * Also denied, on the bucket: a lifecycle rule, whose expiration deletes objects without anyone calling
     * DeleteObject (removing one needs the same right), on every store.
     */
    static final List<String> LIFECYCLE = List.of("s3:PutLifecycleConfiguration");
    /**
     * And where the store does not stop the bucket's own account changing the policy itself: putting another bucket
     * policy or deleting this one, which would take the deny away. On AWS S3 the account's root user can still do
     * both; on Ceph the bucket's owner may, so the catalog's user must not own the bucket. On MinIO a bucket policy
     * opens a bucket to anonymous requests, deletes included.
     */
    static final List<String> BUCKET_POLICY = List.of("s3:PutBucketPolicy", "s3:DeleteBucketPolicy");
    /**
     * Allowed to the worker, on the bucket: listing it, which the worker does with its own identity to find the
     * files beneath a table's location. A condition on {@code s3:prefix} keeps it to the worker's prefixes.
     */
    static final List<String> LISTING = List.of("s3:ListBucket");
    private static final String DELETES_JSON = json(DELETES);
    /** IAM's characters for user and role names and ARNs; MinIO's user names fit in them too. */
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9+=,.@_:/-]+");

    private DeletePolicy() {
    }

    /** One profile's maintenance worker and the prefixes in one bucket that it alone may delete in. */
    public record Worker(String principal, List<ObjectLocation> prefixes) {

        public Worker {
            Objects.requireNonNull(principal, "principal");
            prefixes = List.copyOf(prefixes);
            if (prefixes.isEmpty()) {
                throw new IllegalArgumentException("a worker deletes beneath at least one prefix");
            }
        }
    }

    /**
     * Checks that {@code principal} can name the maintenance worker on {@code kind}: an IAM role or user ARN on
     * AWS S3 ({@code arn:aws:iam::<account>:role/<name>}), a user ARN on Ceph ({@code arn:aws:iam::<tenant>:user/<uid>},
     * the tenant empty for none), a user name on MinIO.
     *
     * @throws IllegalArgumentException when it cannot
     */
    public static void requirePrincipal(StoreKind kind, String principal) {
        if (principal == null || !PRINCIPAL.matcher(principal).matches()) {
            throw new IllegalArgumentException("maintenance principal " + principal
                    + " has a character outside [A-Za-z0-9+=,.@_:/-]");
        }
        boolean arn = principal.startsWith("arn:");
        if (kind == StoreKind.MINIO ? arn : !arn) {
            throw new IllegalArgumentException("maintenance principal " + principal + " must be "
                    + (kind == StoreKind.MINIO ? "a MinIO user name" : "an ARN") + " on " + kind);
        }
    }

    /**
     * The bucket policy for {@code bucket} on AWS S3 or Ceph RGW: beneath each worker's prefixes, every principal
     * but that worker is denied delete. AWS matches {@code aws:PrincipalArn}, which for a role session is the role,
     * so the worker may be a role. RGW names the worker in {@code NotPrincipal} instead. Each worker is allowed to
     * delete beneath its prefixes and to list the bucket there, since a Ceph user has no rights in another user's
     * bucket but what the bucket grants, and on AWS a principal of the bucket's account needs nothing more. On the
     * bucket itself every principal but the workers is denied a lifecycle rule, and on AWS also a new or deleted
     * bucket policy (on Ceph the catalog's user must not own the bucket instead). Nothing else is allowed or denied:
     * every other right still comes from where it came from before.
     *
     * @param region the region of the bucket, which sets the partition of its ARNs
     */
    public static String bucketPolicy(StoreKind kind, String region, String bucket, List<Worker> workers) {
        if (kind == StoreKind.MINIO) {
            throw new IllegalArgumentException("MinIO does not enforce a bucket policy on signed requests;"
                    + " use a user policy");
        }
        String bucketArn = "[\"arn:" + SessionPolicy.partition(region) + ":s3:::" + bucket + "\"]";
        List<String> statements = new ArrayList<>();
        for (int i = 0; i < workers.size(); i++) {
            Worker worker = workers.get(i);
            requirePrincipal(kind, worker.principal());
            String resources = resources(SessionPolicy.partition(region), bucket, worker.prefixes());
            String principal = "{\"AWS\":[\"" + worker.principal() + "\"]}";
            if (kind == StoreKind.S3) {
                statements.add("{\"Sid\":\"NunaOnlyTheWorkerDeletes" + i + "\",\"Effect\":\"Deny\",\"Principal\":\"*\","
                        + "\"Action\":" + DELETES_JSON + ",\"Resource\":" + resources + ","
                        + "\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":\"" + worker.principal() + "\"}}}");
            } else {
                statements.add("{\"Sid\":\"NunaOnlyTheWorkerDeletes" + i + "\",\"Effect\":\"Deny\",\"NotPrincipal\":"
                        + principal + ",\"Action\":" + DELETES_JSON + ",\"Resource\":" + resources + "}");
            }
            statements.add("{\"Sid\":\"NunaTheWorkerDeletes" + i + "\",\"Effect\":\"Allow\",\"Principal\":"
                    + principal + ",\"Action\":" + DELETES_JSON + ",\"Resource\":" + resources + "}");
            statements.add(listing("NunaTheWorkerLists" + i, "\"Principal\":" + principal + ",", bucketArn,
                    worker.prefixes()));
        }
        String principals = json(workers.stream().map(Worker::principal).distinct().toList());
        if (kind == StoreKind.S3) {
            List<String> settings = new ArrayList<>(LIFECYCLE);
            settings.addAll(BUCKET_POLICY);
            statements.add("{\"Sid\":\"NunaOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\",\"Principal\":\"*\","
                    + "\"Action\":" + json(settings) + ",\"Resource\":" + bucketArn + ","
                    + "\"Condition\":{\"ArnNotEquals\":{\"aws:PrincipalArn\":" + principals + "}}}");
        } else {
            statements.add("{\"Sid\":\"NunaOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\",\"NotPrincipal\":"
                    + "{\"AWS\":" + principals + "},\"Action\":" + json(LIFECYCLE) + ",\"Resource\":" + bucketArn + "}");
        }
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    /**
     * On MinIO, which does not enforce a bucket policy on signed requests (Silo, its maintained fork, was checked
     * on 2026-11-12), the deny goes into a user policy instead: attached to every user but the worker, the
     * catalog's own included, with a deny of lifecycle rules and bucket policies on each prefix's bucket. The
     * sessions the catalog vends derive from its user and keep the deny. The root user ignores every policy, so the
     * catalog must not run as root. The worker gets {@link #workerPolicy} instead.
     */
    public static String userPolicy(List<ObjectLocation> prefixes) {
        List<String> settings = new ArrayList<>(LIFECYCLE);
        settings.addAll(BUCKET_POLICY);
        String buckets = json(prefixes.stream().map(prefix -> "arn:aws:s3:::" + prefix.bucket()).distinct().toList());
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"NunaOnlyTheWorkerDeletes\",\"Effect\":\"Deny\","
                + "\"Action\":" + DELETES_JSON + ",\"Resource\":" + resources("aws", null, prefixes) + "},"
                + "{\"Sid\":\"NunaOnlyTheWorkerSetsTheBucket\",\"Effect\":\"Deny\",\"Action\":" + json(settings)
                + ",\"Resource\":" + buckets + "}]}";
    }

    /**
     * On MinIO, the user policy to attach to the worker, whose user has no rights but what its policies give: delete
     * beneath the prefixes, and listing each prefix's bucket there.
     */
    public static String workerPolicy(List<ObjectLocation> prefixes) {
        List<String> statements = new ArrayList<>();
        statements.add("{\"Sid\":\"NunaTheWorkerDeletes\",\"Effect\":\"Allow\",\"Action\":" + DELETES_JSON
                + ",\"Resource\":" + resources("aws", null, prefixes) + "}");
        Map<String, List<ObjectLocation>> byBucket = prefixes.stream()
                .collect(Collectors.groupingBy(ObjectLocation::bucket, LinkedHashMap::new, Collectors.toList()));
        int i = 0;
        for (Map.Entry<String, List<ObjectLocation>> bucket : byBucket.entrySet()) {
            statements.add(listing("NunaTheWorkerLists" + i++, "", "[\"arn:aws:s3:::" + bucket.getKey() + "\"]",
                    bucket.getValue()));
        }
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    /**
     * Allows listing {@code bucketArn} beneath {@code prefixes} only ({@code s3:prefix}), or all of it when one of
     * them is the whole bucket. {@code principal} is the statement's Principal element and a comma, or empty.
     */
    private static String listing(String sid, String principal, String bucketArn, List<ObjectLocation> prefixes) {
        String statement = "{\"Sid\":\"" + sid + "\",\"Effect\":\"Allow\"," + principal + "\"Action\":" + json(LISTING)
                + ",\"Resource\":" + bucketArn;
        if (prefixes.stream().anyMatch(prefix -> prefix.segments().isEmpty())) {
            return statement + "}";
        }
        return statement + ",\"Condition\":{\"StringLike\":{\"s3:prefix\":"
                + json(prefixes.stream().map(prefix -> prefix.key() + "/*").distinct().toList()) + "}}}";
    }

    /** A JSON array of strings that need no escaping. */
    private static String json(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    /** Every object beneath each prefix; {@code bucket}, when given, is the only bucket allowed. */
    private static String resources(String partition, String bucket, List<ObjectLocation> prefixes) {
        String arn = "arn:" + partition + ":s3:::";
        return prefixes.stream().map(prefix -> {
            if (bucket != null && !prefix.bucket().equals(bucket)) {
                throw new IllegalArgumentException(prefix + " is not in bucket " + bucket);
            }
            return "\"" + arn + prefix.bucket() + "/" + (prefix.segments().isEmpty() ? "" : prefix.key() + "/") + "*\"";
        }).collect(Collectors.joining(",", "[", "]"));
    }
}
