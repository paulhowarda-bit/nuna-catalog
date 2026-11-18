package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A file of a table the maintenance worker will delete once {@code notBefore} has passed (D30): a file
 * that snapshot expiry or orphan removal left unreferenced, kept until the database's PITR window is over,
 * so a database restored to any point it still holds never names a deleted file.
 *
 * @param id         the deletion's own id, which the worker hands back once the file is gone
 * @param objectId   the table the file belongs to
 * @param catalogId  that table's catalog
 * @param location   the file, beneath the table's location
 * @param notBefore  the earliest time the file may be deleted
 * @param recordedAt when it was recorded
 */
public record PendingDeletion(UUID id, UUID objectId, UUID catalogId, String location, Instant notBefore,
                              Instant recordedAt) {

    public PendingDeletion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
