package nuna.catalog.store;

import java.util.Objects;
import java.util.UUID;

/**
 * The branch a commit lands on, or ends (D33, backlog 103). The store locks the ref record before any pointer, so a
 * merge and a branch commit cannot deadlock, and checks it as it locks it: the branch must exist, be a branch and not
 * be past its expiry at the batch's {@code committedAt}. Otherwise the batch writes nothing and answers
 * {@link CommitResult.Inactive} with {@link CommitResult.Subject#REF}.
 */
public sealed interface RefLock {

    UUID refId();

    /**
     * A commit on the branch: it swaps and holds only the branch's own pointers ({@link Ref#key}). The ref is locked
     * for share where the backend can (PostgreSQL and CockroachDB; Oracle locks it for update and MongoDB marks it,
     * as their object checks do), so a merge or a deletion of the branch waits for the commit or refuses it, and the
     * commit lands before them or not at all. A swap of an object outside the branch's cut answers
     * {@link CommitResult.Inactive} with {@link CommitResult.Subject#OBJECT}: a branch's objects are those of its cut.
     */
    record Hold(UUID refId) implements RefLock {

        public Hold {
            Objects.requireNonNull(refId, "refId");
        }
    }

    /**
     * A merge: it swaps main's pointers and holds every own pointer of the branch as the merge read them, and ends
     * the branch in the same transaction. The ref is locked for update, then the pointers in key order; once every
     * swap and hold matched, the branch is deleted with its cut and its own pointers, as {@link RefStore#deleteRef}
     * deletes them. Its journal rows stay. An own pointer the batch does not hold was written by a commit on the
     * branch after the merge read it: the merge answers {@link CommitResult.Conflict} on it rather than lose it.
     */
    record End(UUID refId) implements RefLock {

        public End {
            Objects.requireNonNull(refId, "refId");
        }
    }

    /** Whether {@code key} is one of this branch's own pointers. */
    default boolean owns(PointerKey key) {
        return key.branch().equals(refId().toString());
    }
}
