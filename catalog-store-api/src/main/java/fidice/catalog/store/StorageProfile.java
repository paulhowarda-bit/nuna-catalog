package fidice.catalog.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a catalog's tables live and how credentials for them are vended (D21, D36a): the store
 * kind, its endpoint (none for AWS S3), the role STS assumes, and the location prefixes tables may
 * use, kept in the order given.
 */
public record StorageProfile(UUID id, String name, Kind kind, Optional<String> endpoint, String stsRole,
                             List<String> allowedLocations) {

    public enum Kind { S3, MINIO, CEPH }

    public StorageProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(stsRole, "stsRole");
        allowedLocations = List.copyOf(allowedLocations);
    }
}
