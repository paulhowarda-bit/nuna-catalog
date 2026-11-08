package fidice.catalog.core.commit;

import java.util.UUID;

/**
 * What a create or commit request gets back. {@code type} is the Iceberg error class name clients map
 * to an exception.
 */
public sealed interface CommitReply {

    enum How {
        /** This request's commit swapped the pointer. */
        COMMITTED,
        /** The request changed nothing; nothing was written (D9). */
        UNCHANGED,
        /** An earlier request with the same idempotency key finished; this is its answer (D10). */
        REPLAYED
    }

    /** The metadata the response carries, and the id the catalog knows the object by. */
    record Success(UUID objectId, String metadataLocation, String metadataSha256, String metadataJson, How how)
            implements CommitReply {
    }

    /** A definite 4xx: 400 BadRequest, 403 Forbidden, 404 NoSuch*, 409 CommitFailed or AlreadyExists. */
    record Failure(int status, String type, String message) implements CommitReply {
    }

    /**
     * 500. {@code CommitStateUnknownException} when the commit may have happened; otherwise the server
     * failed with nothing written. Never replayed (D10).
     */
    record ServerError(String type, String message) implements CommitReply {
    }
}
