package fidice.catalog.objectstore;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 */
public record StorageProfile(
        StoreKind kind, URI endpoint, String region, ObjectLocation tableRoot, List<ObjectLocation> allowedPrefixes) {

    public StorageProfile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(tableRoot, "tableRoot");
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
    }

    /** Builds a profile from plain configuration values. */
    public static StorageProfile of(
            StoreKind kind, String endpoint, String region, String tableRoot, List<String> allowedPrefixes) {
        return new StorageProfile(kind, URI.create(endpoint), region, ObjectLocation.parse(tableRoot),
                allowedPrefixes.stream().map(ObjectLocation::parse).toList());
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
