package keel.catalog.store;

import java.util.UUID;

/**
 * Test seam: a backend calls it immediately before sending the COMMIT of every write that carries
 * an id (commits, changes, idempotency claims, completions and releases), with that id, so a test
 * can cut the link at the moment that makes the outcome ambiguous. Production passes {@link #NONE}.
 */
@FunctionalInterface
public interface CommitHook {

    CommitHook NONE = commitId -> { };

    void beforeCommit(UUID commitId);
}
