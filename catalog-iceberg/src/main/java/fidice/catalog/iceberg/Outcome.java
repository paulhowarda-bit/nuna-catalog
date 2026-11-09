package fidice.catalog.iceberg;

/** What applying a create or commit request to the current metadata produced. */
public sealed interface Outcome {

    /**
     * The request changed the metadata: write {@code json} to a new metadata file, then swap the pointer.
     * {@code before} is null for a create.
     */
    record NewMetadata(String json, Facts before, Facts after) implements Outcome {
    }

    /** The request changes nothing: answer with the current metadata and write nothing (D9). */
    record NoChange() implements Outcome {
    }

    /**
     * A requirement does not hold against the current metadata, or the request carries a value the table
     * has moved past (a stale sequence number or first-row-id): 409, and a server retry cannot help.
     */
    record RequirementFailed(String message) implements Outcome {
    }

    /**
     * The request is malformed or not allowed: 400. The type is the error type the client sees: the
     * class name of iceberg-core's exception, as the reference sends it (the Iceberg client turns
     * IllegalArgumentException back into that exception), or BadRequestException for our own refusals.
     */
    record BadRequest(String type, String message) implements Outcome {

        public BadRequest(String message) {
            this("BadRequestException", message);
        }
    }

    /** A table commit that is not a create, against a table with no current metadata: 404. */
    record NotFound() implements Outcome {
    }
}
