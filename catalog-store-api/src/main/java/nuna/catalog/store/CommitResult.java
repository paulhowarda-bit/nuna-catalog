package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The definite outcome of a commit. An ambiguous outcome is {@link CommitOutcomeUnknownException}. */
public sealed interface CommitResult {

    /** Every swap and record in the batch was written. */
    record Committed(UUID commitId) implements CommitResult {
    }

    /**
     * The pointer at {@code key} did not equal the swap's or the hold's expected value, or is an own pointer of the
     * branch that a merge ({@link RefLock.End}) does not hold; nothing was written.
     */
    record Conflict(PointerKey key, Optional<Pointer> actual) implements CommitResult {
    }

    /**
     * Something the batch swaps on is gone, so nothing was written, and no retry can succeed.
     * {@link Subject#OBJECT}: the object whose pointer {@code key} swaps is no longer active (it was dropped,
     * unregistered or purged after the commit read it), or, on a branch, is not in the branch's cut.
     * {@link Subject#REF}: the branch the batch lands on or ends ({@link CommitBatch#ref}) is gone (deleted, merged or
     * past its expiry) or is a tag; {@code key} is then {@link CommitBatch#refKey}.
     */
    record Inactive(PointerKey key, Subject subject) implements CommitResult {

        public Inactive {
            Objects.requireNonNull(subject, "subject");
        }

        /** The object of {@code key} is no longer active. */
        public Inactive(PointerKey key) {
            this(key, Subject.OBJECT);
        }
    }

    /** What an {@link Inactive} commit found gone. */
    enum Subject { OBJECT, REF }
}
