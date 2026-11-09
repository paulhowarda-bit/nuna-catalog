package fidice.catalog.store.sql;

import fidice.catalog.store.suite.CatalogObjectSuite;
import fidice.catalog.store.suite.CatalogStoreUnderTest;
import fidice.catalog.store.suite.CommitSuite;
import fidice.catalog.store.suite.GovernanceSuite;
import fidice.catalog.store.suite.GrantSuite;
import fidice.catalog.store.suite.IdempotencySuite;
import fidice.catalog.store.suite.JournalSuite;
import fidice.catalog.store.suite.MutationSuite;
import fidice.catalog.store.suite.NamespaceSuite;
import fidice.catalog.store.suite.OutboxSuite;
import fidice.catalog.store.suite.SizeSuite;
import fidice.catalog.store.suite.StoreUnderTest;
import fidice.catalog.store.suite.UpgradeSuite;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared suite against a three-node YugabyteDB cluster: the commit suite, every group suite as a
 * nested class on the same cluster, and YugabyteDB-only checks (docs/storage-design.md 5.5).
 */
@Tag("heavy")
class YugabyteStoreSuiteTest extends CommitSuite {

    private YugabyteCluster yugabyte;

    @Override
    protected StoreUnderTest storeUnderTest() {
        yugabyte = new YugabyteCluster();
        return yugabyte;
    }

    @Nested
    class Namespaces extends NamespaceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class CatalogObjects extends CatalogObjectSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Grants extends GrantSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Idempotency extends IdempotencySuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Outbox extends OutboxSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Governance extends GovernanceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Journal extends JournalSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Size extends SizeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Upgrade extends UpgradeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Nested
    class Mutations extends MutationSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return yugabyte;
        }
    }

    @Test
    @DisplayName("YugabyteDB 7 Size: the unpartitioned journal splits into tablets as it grows, and every commit"
            + " stays readable and findable")
    void journalSplitsIntoTablets() throws InterruptedException {
        JournalTabletSplits.run(yugabyte.singleNodeFreshDatabase());
    }

    @Test
    @DisplayName("YugabyteDB: a write aborted as a deadlock before COMMIT (40P01) is rolled back and run again")
    void deadlockVictimRunsAgain() throws Exception {
        DeadlockRetry.run(yugabyte.primaryDirect());
    }

    @Test
    @DisplayName("YugabyteDB 8 Names: names compare and sort as bytes, like COLLATE \"C\"")
    void namesCompareAsBytes() {
        DataSource database = yugabyte.freshDatabaseDirect();
        SqlCatalogStore store = new SqlCatalogStore(database);
        store.migrate();
        ByteOrderNames.run(store);
    }

    @Test
    @DisplayName("YugabyteDB: schema steps create tablets and outlast a commit's socket timeout")
    void schemaStepsOutlastACommitTimeout() {
        new SqlCatalogStore(yugabyte.freshDatabaseDirect(1)).migrate();
    }
}
