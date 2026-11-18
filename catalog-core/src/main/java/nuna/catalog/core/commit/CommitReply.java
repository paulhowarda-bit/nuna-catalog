package nuna.catalog.core.commit;

import java.util.Optional;
import java.util.UUID;

/**
 * What a create, commit or transaction request gets back. {@code type} is the Iceberg error class name clients map
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

    /** A multi-table transaction went through (D15): every table's change, or none was needed. Answered 204. */
    record Applied(How how) implements CommitReply {
    }

    /**
     * A definite 4xx: 400 BadRequest, 403 Forbidden, 404 NoSuch*, 409 CommitFailed or AlreadyExists.
     *
     * @param requirement for a 409 a requirement refused, its type as the spec names it ({@code assert-ref-snapshot-id}
     *                    and so on); empty for any other failure, and for a failure replayed by its idempotency key
     */
    record Failure(int status, String type, String message, Optional<String> requirement) implements CommitReply {

        public Failure(int status, String type, String message) {
            this(status, type, message, Optional.empty());
        }
    }

    /**
     * 500. {@code CommitStateUnknownException} when the commit may have happened; otherwise the server
     * failed with nothing written. Never replayed (D10).
     */
    record ServerError(String type, String message) implements CommitReply {
    }

    /**
     * 503 with Retry-After: the catalog store could not be reached and nothing was written, so the client
     * may send the request again. Never replayed (D10).
     */
    record Unavailable(String message) implements CommitReply {
    }
}
