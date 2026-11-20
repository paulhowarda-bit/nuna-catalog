package nuna.catalog.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a catalog's tables live and how credentials for them are vended (D21, D36a): the store
 * kind, its endpoint (none for AWS S3), the role STS assumes, the location prefixes tables may
 * use, kept in the order given, and the KMS key every write to its tables uses (D26, amended
 * 2026-11-19), none when the profile names no key.
 */
public record StorageProfile(UUID id, String name, Kind kind, Optional<String> endpoint, String stsRole,
                             List<String> allowedLocations, Optional<String> kmsKey) {

    public enum Kind { S3, MINIO, CEPH }

    public StorageProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(stsRole, "stsRole");
        allowedLocations = List.copyOf(allowedLocations);
        Objects.requireNonNull(kmsKey, "kmsKey");
    }
}
