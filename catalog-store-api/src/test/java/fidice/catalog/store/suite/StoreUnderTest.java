package fidice.catalog.store.suite;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitStore;
import fidice.catalog.store.PointerKey;
import java.util.List;
import java.util.Set;

/**
 * What a backend hands the commit suite: stores over its database, the faults it can inject, and
 * direct reads of what a commit left behind. Every store reaches the database through the link
 * {@link Faults} breaks; the direct reads go around it, to the current primary. A backend of the
 * whole interface hands the suites a {@link CatalogStoreUnderTest}.
 */
public interface StoreUnderTest extends AutoCloseable {

    /** A store over the backend's database whose writes call {@code hook} just before COMMIT is sent. */
    CommitStore store(CommitHook hook);

    /**
     * A store of another process: it shares nothing in memory with the stores {@link #store}
     * hands out (for example Oracle's transaction ids of undecided commits).
     */
    CommitStore storeOfAnotherProcess();

    Faults faults();

    /** The rows {@code batch} left in the database. */
    WrittenRows writtenRows(CommitBatch batch);

    /** The new version of every journal entry for {@code key}, in journal order. */
    List<Long> journalVersions(PointerKey key);

    /** Capability for suite test 4: the backend runs with a replica it can promote. */
    boolean supportsFailover();

    /** Capability for suite test 6: the backend runs with a replica whose apply can lag. */
    boolean hasReplica();

    /** Capability for suite test 5: the environment is durable enough for an empty self-check. */
    boolean hasSafeEnvironment();

    /**
     * Capability for suite test 3: a commit whose COMMIT was in flight while replication stalled
     * may still be lost once replication resumes. On a Raft backend the lookup that finds the
     * commit's transaction abandoned by its client may abort it (docs/storage-design.md sections 5.4
     * and 5.5). findCommit's answer must still be final and agree with what the commit left behind.
     */
    default boolean stalledCommitCanBeLost() {
        return false;
    }

    /** Every finding code the backend's self-check can report (docs/storage-design.md section 7). */
    Set<String> findingCodes();

    /** One entry per unsafe setting the backend can stage. */
    List<UnsafeSetting> unsafeSettings();

    @Override
    void close();
}
