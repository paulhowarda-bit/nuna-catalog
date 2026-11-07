package fidice.catalog.store.sql;

import static fidice.catalog.store.suite.Commits.batch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.suite.StoreSuite;
import fidice.catalog.store.suite.StoreUnderTest;
import java.sql.SQLWarning;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared suite against PostgreSQL 18 with a synchronous standby, plus PostgreSQL-only hazards. */
class PostgresStoreSuiteTest extends StoreSuite {

    private ReplicatedPostgres postgres;

    @Override
    protected StoreUnderTest storeUnderTest() {
        postgres = new ReplicatedPostgres();
        return postgres;
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
