package keel.catalog.store;

import java.util.UUID;

/**
 * The backend cannot tell whether a commit's transaction committed: the connection was lost
 * after COMMIT was sent, or failover happened mid-commit. The caller must call
 * {@link CatalogStore#findCommit}; it must not retry the commit or report a conflict (D9).
 *
 * <p>Deliberately not a {@link StoreException}, so a handler for clean failures cannot swallow it.
 */
public class CommitOutcomeUnknownException extends RuntimeException {

    private final UUID commitId;

    public CommitOutcomeUnknownException(UUID commitId, Throwable cause) {
        super("outcome of commit " + commitId + " is unknown", cause);
        this.commitId = commitId;
    }

    public UUID commitId() {
        return commitId;
    }
}
