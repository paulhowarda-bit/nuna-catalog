package fidice.catalog.store.sql;

import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.batch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.IdempotencyClaim;
import fidice.catalog.store.KeyState;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.suite.CatalogObjectSuite;
import fidice.catalog.store.suite.CatalogStoreUnderTest;
import fidice.catalog.store.suite.Changes;
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
import java.sql.SQLWarning;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared suite against PostgreSQL 18 with a synchronous standby: the commit suite, every group
 * suite as a nested class on the same backend, and PostgreSQL-only hazards.
 */
class PostgresStoreSuiteTest extends CommitSuite {

    private ReplicatedPostgres postgres;

    @Override
    protected StoreUnderTest storeUnderTest() {
        postgres = new ReplicatedPostgres();
        return postgres;
    }

    @Nested
    class Namespaces extends NamespaceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class CatalogObjects extends CatalogObjectSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Grants extends GrantSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Idempotency extends IdempotencySuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Outbox extends OutboxSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Governance extends GovernanceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Journal extends JournalSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Size extends SizeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Upgrade extends UpgradeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Nested
    class Mutations extends MutationSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return postgres;
        }
    }

    @Test
    @DisplayName("PostgreSQL: a COMMIT whose wait for the standby is cancelled is unknown, and findCommit answers"
            + " COMMITTED only once the standby has it")
    void cancelledReplicationWaitIsUnknownUntilTheStandbyHasTheCommit() throws Exception {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = batch(swap(key, Optional.of(base), pointer("cancelled-wait", 2)));
        postgres.stallReplication();

        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> store().commit(batch));
        String waiting = awaitBackendWaitingForTheStandby();
        Sql.execute(postgres.primaryDirect(), "SELECT pg_cancel_backend(CAST(? AS INT))", waiting);

        assertThatThrownBy(() -> commit.get(60, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(CommitOutcomeUnknownException.class)
                .cause().isInstanceOf(SQLWarning.class).hasMessageContaining("synchronous replication");
        // The hazard is real: the primary shows the commit though the standby never received it.
        assertThat(store().readPointer(key)).contains(next(batch));

        CompletableFuture<CommitLookup> lookup =
                CompletableFuture.supplyAsync(() -> store().findCommit(batch.commitId(), key));
        Thread.sleep(1_500);
        assertThat(lookup).as("COMMITTED must wait until the standby has the commit").isNotDone();
        postgres.resumeReplication();

        assertThat(lookup.get(60, TimeUnit.SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("PostgreSQL: a key's answer whose COMMIT lost its wait for the standby is replayed only once the"
            + " standby has it")
    void idempotentReplayWaitsForTheStandby() throws Exception {
        CatalogStore store = postgres.store(CommitHook.NONE);
        String key = UUID.randomUUID().toString();
        Instant now = Changes.now();
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), now, List.of(advance(newKey(), Optional.empty(), "keyed")),
                List.of(), Optional.of(Changes.result(key, 200, now)));
        postgres.stallReplication();

        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> store.commit(batch));
        Sql.execute(postgres.primaryDirect(), "SELECT pg_cancel_backend(CAST(? AS INT))", awaitBackendWaitingForTheStandby());
        assertThatThrownBy(() -> commit.get(60, TimeUnit.SECONDS)).cause().isInstanceOf(CommitOutcomeUnknownException.class);

        CompletableFuture<KeyState> claim = CompletableFuture.supplyAsync(() -> store.claimKey(new IdempotencyClaim(key,
                "svc-suite", "hash-of-" + key, UUID.randomUUID(), now, now.plusSeconds(30), now.plusSeconds(3600))));
        CompletableFuture<KeyState> read = CompletableFuture.supplyAsync(() -> store.readKey(key, Changes.now()));
        Thread.sleep(1_500);
        assertThat(claim).as("Done must wait until the standby has the answer").isNotDone();
        assertThat(read).as("Done must wait until the standby has the answer").isNotDone();
        postgres.resumeReplication();

        KeyState done = new KeyState.Done(batch.idempotency().orElseThrow());
        assertThat(claim.get(60, TimeUnit.SECONDS)).isEqualTo(done);
        assertThat(read.get(60, TimeUnit.SECONDS)).isEqualTo(done);
    }

    @Test
    @DisplayName("PostgreSQL 7 Size: the journal rolls over into monthly partitions, and a commit beyond the horizon"
            + " moves into its month when the horizon is extended")
    void journalRollsOver() {
        DataSource database = postgres.freshDatabase();
        new JournalRollOver(database, new SqlCatalogStore(database)).run();
    }

    @Test
    @DisplayName("PostgreSQL: a write aborted as a deadlock before COMMIT (40P01) is rolled back and run again")
    void deadlockVictimRunsAgain() throws Exception {
        DeadlockRetry.run(postgres.freshDatabase());
    }

    @Test
    @DisplayName("PostgreSQL 8 Names: names compare and sort as bytes, like COLLATE \"C\"")
    void namesCompareAsBytes() {
        DataSource database = postgres.freshDatabase();
        SqlCatalogStore store = new SqlCatalogStore(database);
        store.migrate();
        ByteOrderNames.run(store);
    }

    private String awaitBackendWaitingForTheStandby() {
        String[] pid = new String[1];
        Sql.await(Duration.ofSeconds(ReplicatedPostgres.SOCKET_TIMEOUT_SECONDS - 1), () -> {
            List<String> waiting = Sql.column(postgres.primaryDirect(),
                    "SELECT pid FROM pg_stat_activity WHERE wait_event = 'SyncRep'");
            pid[0] = waiting.isEmpty() ? null : waiting.getFirst();
            return pid[0] != null;
        }, "a COMMIT waiting for the standby");
        return pid[0];
    }
}
