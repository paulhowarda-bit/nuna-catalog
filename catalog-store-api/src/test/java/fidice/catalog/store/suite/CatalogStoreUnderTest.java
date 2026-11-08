package fidice.catalog.store.suite;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitStore;
import fidice.catalog.store.PointerKey;

/**
 * What a backend of the whole {@link CatalogStore} hands the group suites, beyond what the commit
 * suite needs. The direct reads go around the link, to the current primary.
 */
public interface CatalogStoreUnderTest extends StoreUnderTest {

    @Override
    CatalogStore store(CommitHook hook);

    @Override
    CatalogStore storeOfAnotherProcess();

    /** The outbox rows and done idempotency record {@code change} left in the database. */
    WrittenRows changeRows(Change change);

    /** The size budget of one stored projection piece (suite test 7). */
    int projectionPieceBytes();

    /** How many pieces the stored projections of {@code key} occupy, over every version still stored. */
    int projectionPieces(PointerKey key);

    /** A fresh database at the previous release's schema version (suite test 10). */
    UpgradeTarget upgradeTarget();

    /**
     * Stores of two releases over one fresh database, which is at {@code previousVersion}: the
     * previous release's store, as it shipped, and this release's, whose migrate() upgrades it.
     */
    record UpgradeTarget(int previousVersion, CommitStore previousRelease, CatalogStore currentRelease) {
    }
}
