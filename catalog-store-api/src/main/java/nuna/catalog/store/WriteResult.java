package nuna.catalog.store;

import java.util.UUID;

/**
 * The definite outcome of a non-commit mutation. Only {@link Applied} wrote anything; every other
 * outcome wrote nothing, not even the change's outbox events or idempotency result. Each method
 * lists the outcomes it can return. An unknown outcome is {@link CommitOutcomeUnknownException}.
 */
public sealed interface WriteResult {

    WriteResult APPLIED = new Applied();
    WriteResult UNCHANGED = new Unchanged();

    /** The mutation and its change records were written. */
    record Applied() implements WriteResult {
    }

    /** The record already held exactly this; nothing was written. */
    record Unchanged() implements WriteResult {
    }

    /** The record, or one the mutation needs, does not exist. */
    record Missing(Subject subject) implements WriteResult {
    }

    /** Another live record holds the normalized name. */
    record NameTaken(UUID holder) implements WriteResult {
    }

    /** The record's version is not the expected one. */
    record VersionMismatch(long actual) implements WriteResult {
    }

    /** A rule the store enforces refused the mutation. */
    record Refused(Reason reason) implements WriteResult {
    }

    enum Subject { CATALOG, NAMESPACE, OBJECT, GRANT, STORAGE_PROFILE, ATTRIBUTE, HOLD, ENGINE, KEY }

    enum Reason {
        /** The namespace still has child namespaces or active objects. */
        NOT_EMPTY,
        /** A legal hold on the record or one of its ancestors forbids it (D24). */
        HELD,
        /** The object's purge-after time has not come (D25). */
        NOT_DUE,
        /** Another claimant holds the idempotency key or the outbox relay lease. */
        NOT_HOLDER,
        /** A DR freeze on the record or one of its ancestors forbids it. */
        FROZEN,
        /**
         * A regulated flag on the record or one of its ancestors (D27), or a row/column-policy flag on the
         * record itself (D20), forbids it until a steward removes the flag.
         */
        FLAGGED,
        /** The condition the caller set, asked inside the write, refused it ({@link GrantStore#putGrantIf}). */
        NOT_ADMITTED,
        /** A higher DR watermark is kept, and a watermark never moves back ({@link DrStore#recordDrWatermark}). */
        WATERMARK_BEHIND,
        /** The DR watermark is above the version of the object's main pointer ({@link DrStore#recordDrWatermark}). */
        WATERMARK_AHEAD
    }
}
