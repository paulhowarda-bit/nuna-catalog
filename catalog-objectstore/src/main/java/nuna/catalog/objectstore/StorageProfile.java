package nuna.catalog.objectstore;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where one catalog's files live, and the only places the catalog will read or write them
 * (D21, D28, D36a). The endpoint comes from here and never from a client.
 *
 * @param kind            which store the endpoint is
 * @param endpoint        the store's S3 endpoint: {@code http(s)://host[:port]}, nothing after it
 * @param region          the region requests are signed for
 * @param tableRoot       where the catalog assigns new table locations; within an allowed prefix
 * @param allowedPrefixes the approved prefixes; every location read or written lies beneath one,
 *                        so there is at least one (the one holding the table root)
 * @param kmsKey          the one SSE-KMS key every write must use (D26, amended 2026-11-19): the server's metadata
 *                        files name it, vended sessions may write with it alone, and engines are told to. AWS S3 only,
 *                        as a key ARN in the profile's region. Without it, writes are encrypted as the bucket's
 *                        default says, as before
 */
public record StorageProfile(
        StoreKind kind, URI endpoint, String region, ObjectLocation tableRoot, List<ObjectLocation> allowedPrefixes,
        Optional<String> kmsKey) {

    /**
     * A key ARN: {@code arn:<partition>:kms:<region>:<account>:key/<key id>}, the id a single-Region key's UUID or a
     * multi-Region key's {@code mrk-} id. Nothing else passes, so the key carries no character a policy reads specially.
     */
    private static final Pattern KEY_ARN = Pattern.compile("arn:(aws|aws-cn|aws-us-gov):kms:([a-z0-9-]+):[0-9]{12}"
            + ":key/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|mrk-[0-9a-f]{32})");

    public StorageProfile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(tableRoot, "tableRoot");
        Objects.requireNonNull(kmsKey, "kmsKey");
        allowedPrefixes = List.copyOf(allowedPrefixes);
        String scheme = endpoint.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || !endpoint.getRawPath().isEmpty()
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must be http(s)://host[:port], got " + endpoint);
        }
        if (region.isBlank()) {
            throw new IllegalArgumentException("region must not be blank");
        }
        if (allowedPrefixes.stream().noneMatch(prefix -> tableRoot.equals(prefix) || tableRoot.isBeneath(prefix))) {
            throw new IllegalArgumentException("table root " + tableRoot + " is not within an allowed prefix");
        }
        kmsKey.ifPresent(key -> requireKmsKey(kind, region, key));
    }

    /** Builds a profile that names no KMS key from plain configuration values. */
    public static StorageProfile of(
            StoreKind kind, String endpoint, String region, String tableRoot, List<String> allowedPrefixes) {
        return of(kind, endpoint, region, tableRoot, allowedPrefixes, Optional.empty());
    }

    /** Builds a profile from plain configuration values. */
    public static StorageProfile of(StoreKind kind, String endpoint, String region, String tableRoot,
                                    List<String> allowedPrefixes, Optional<String> kmsKey) {
        return new StorageProfile(kind, URI.create(endpoint), region, ObjectLocation.parse(tableRoot),
                allowedPrefixes.stream().map(ObjectLocation::parse).toList(), kmsKey);
    }

    /**
     * Checks that {@code key} can be a profile's KMS key: a key ARN (an alias or a bare key id never matches the
     * session policy's condition, which compares the key a write names), in the profile's region and partition, since
     * S3 encrypts only with a key in the bucket's region. Only AWS S3 profiles name one: the condition and the ARN
     * are AWS's.
     *
     * @throws IllegalArgumentException saying what is wrong
     */
    public static void requireKmsKey(StoreKind kind, String region, String key) {
        if (kind != StoreKind.S3) {
            throw new IllegalArgumentException("only an AWS S3 profile names a KMS key, not a " + kind + " profile");
        }
        Matcher arn = KEY_ARN.matcher(key);
        if (!arn.matches()) {
            throw new IllegalArgumentException("must be a KMS key ARN, arn:<partition>:kms:<region>:<account>:key/"
                    + "<key id>, not an alias or a bare key id; got " + key);
        }
        if (!arn.group(2).equals(region) || !arn.group(1).equals(SessionPolicy.partition(region))) {
            throw new IllegalArgumentException("the key " + key + " is not in the profile's region " + region
                    + ": S3 encrypts only with a key in the bucket's region");
        }
    }

    /**
     * Returns the parsed location if it lies strictly beneath one of the allowed prefixes, and
     * throws {@link LocationRejectedException} otherwise. A location equal to a prefix is rejected:
     * a table there would own every other table's files.
     */
    public ObjectLocation approve(String location) {
        ObjectLocation parsed = ObjectLocation.parse(location);
        for (ObjectLocation prefix : allowedPrefixes) {
            if (parsed.isBeneath(prefix)) {
                return parsed;
            }
        }
        throw new LocationRejectedException(location, "not beneath an allowed prefix of the storage profile");
    }

    /**
     * The location of a new table: the table root plus the table's ID (D28). It is short and its
     * length is fixed, whatever the namespace depth, which keeps vended session policies small (D21).
     */
    public String newTableLocation(UUID tableId) {
        return tableRoot.child(tableId.toString()).toString();
    }
}
