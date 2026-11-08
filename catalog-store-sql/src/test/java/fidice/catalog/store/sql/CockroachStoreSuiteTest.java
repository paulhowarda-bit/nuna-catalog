package fidice.catalog.store.sql;

import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.batch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.PointerKey;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared suite against a three-node CockroachDB cluster: the commit suite, every group suite as a
 * nested class on the same cluster, and CockroachDB-only checks (docs/storage-design.md 5.4).
 */
class CockroachStoreSuiteTest extends CommitSuite {

    private CockroachCluster cockroach;

    @Override
    protected StoreUnderTest storeUnderTest() {
        cockroach = new CockroachCluster();
        return cockroach;
    }

    @Nested
    class Namespaces extends NamespaceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class CatalogObjects extends CatalogObjectSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Grants extends GrantSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Idempotency extends IdempotencySuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Outbox extends OutboxSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Governance extends GovernanceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Journal extends JournalSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Size extends SizeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Upgrade extends UpgradeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Nested
    class Mutations extends MutationSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return cockroach;
        }
    }

    @Test
    @DisplayName("CockroachDB 7 Size: the journal needs no partitions; over three years of commit times it splits"
            + " into ranges by itself, and every commit stays readable and findable")
    void journalSplitsIntoRanges() throws Exception {
        JournalRangeSplits.run(cockroach.freshDatabaseDirect());
    }

    @Test
    @DisplayName("CockroachDB: a write aborted as a deadlock before COMMIT (40001) is rolled back and run again")
    void deadlockVictimRunsAgain() throws Exception {
        DeadlockRetry.run(cockroach.primaryDirect());
    }

    @Test
    @DisplayName("CockroachDB 8 Names: names compare and sort as bytes, like COLLATE \"C\"")
    void namesCompareAsBytes() {
        DataSource database = cockroach.freshDatabaseDirect();
        SqlCatalogStore store = new SqlCatalogStore(database);
        store.migrate();
        ByteOrderNames.run(store);
    }

    @Test
    @DisplayName("CockroachDB: a COMMIT that carries the missing-license notice is acknowledged as committed")
    void licenseNoticeOnCommitIsIgnored() throws SQLException {
        // A cluster without a license, in its first seven days, attaches the notice to every
        // statement that runs while more than five transactions are open on its gateway.
        List<Connection> open = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                Connection connection = cockroach.primaryDirect().getConnection();
                open.add(connection);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
            }
            try (Statement statement = open.getFirst().createStatement()) {
                statement.execute("SELECT 1");
                assertThat((Throwable) statement.getWarnings()).as("the gateway's license notice").isNotNull()
                        .hasMessageContaining("No license is installed");
            }

            PointerKey key = newKey();
            CommitBatch batch = batch(advance(key, Optional.empty(), "license-notice"));
            assertThat(store().commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            assertThat(store().readPointer(key)).contains(next(batch));
        } finally {
            for (Connection connection : open) {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("CockroachDB: schema steps run as jobs and outlast a commit's socket timeout")
    void schemaStepsOutlastACommitTimeout() {
        new SqlCatalogStore(cockroach.freshDatabaseDirect(1)).migrate();
    }
}
