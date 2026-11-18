package nuna.catalog.store;

import java.util.Optional;
import java.util.UUID;

/** The definite outcome of a commit. An ambiguous outcome is {@link CommitOutcomeUnknownException}. */
public sealed interface CommitResult {

    /** Every swap and record in the batch was written. */
    record Committed(UUID commitId) implements CommitResult {
    }

    /** The pointer at {@code key} did not equal the swap's expected value; nothing was written. */
    record Conflict(PointerKey key, Optional<Pointer> actual) implements CommitResult {
    }

    /**
     * The object whose pointer {@code key} swaps is no longer active: it was dropped, unregistered or
     * purged after the commit read it. Nothing was written, and no retry can succeed.
     */
    record Inactive(PointerKey key) implements CommitResult {
    }
}
