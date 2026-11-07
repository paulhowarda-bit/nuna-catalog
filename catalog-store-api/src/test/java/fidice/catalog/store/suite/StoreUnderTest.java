package fidice.catalog.store.suite;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.PointerKey;
import java.util.List;
import java.util.Set;

/**
 * What a backend hands the shared suite: stores over its database, the faults it can inject, and
 * direct reads of what a commit left behind. Every store reaches the database through the link
 * {@link Faults} breaks; the direct reads go around it, to the current primary.
 */
public interface StoreUnderTest extends AutoCloseable {

    /** A store over the backend's database whose commits call {@code hook} just before COMMIT is sent. */
    CatalogStore store(CommitHook hook);

    Faults faults();

    /** The rows {@code batch} left in the database. */
    WrittenRows writtenRows(CommitBatch batch);

    /** The new version of every journal entry for {@code key}, in journal order. */
    List<Long> journalVersions(PointerKey key);

    /** Capability for suite test 4: the backend runs with a replica it can promote. */
    boolean supportsFailover();

    /** Capability for suite test 5: the environment is durable enough for an empty self-check. */
    boolean hasSafeEnvironment();

    /** Every finding code the backend's self-check can report (docs/storage-design.md section 7). */
    Set<String> findingCodes();

    /** One entry per unsafe setting the backend can stage. */
    List<UnsafeSetting> unsafeSettings();

    @Override
    void close();
}
