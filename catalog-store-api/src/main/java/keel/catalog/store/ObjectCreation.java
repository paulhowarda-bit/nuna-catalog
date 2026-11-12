package keel.catalog.store;

import java.util.Objects;

/**
 * A new active object and its first commit: one swap that creates the pointer (object id, main),
 * with that commit's journal, outbox and idempotency records. Registering a table is a creation
 * whose first pointer names an existing metadata file.
 */
public record ObjectCreation(CatalogObject object, CommitBatch firstCommit) {

    public ObjectCreation {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(firstCommit, "firstCommit");
        if (object.state() != CatalogObject.State.ACTIVE) {
            throw new IllegalArgumentException("a new object is active");
        }
        if (firstCommit.swaps().size() != 1
                || !firstCommit.swaps().getFirst().key().equals(object.mainKey())
                || firstCommit.swaps().getFirst().expected().isPresent()) {
            throw new IllegalArgumentException("the first commit creates exactly the pointer " + object.mainKey());
        }
    }
}
