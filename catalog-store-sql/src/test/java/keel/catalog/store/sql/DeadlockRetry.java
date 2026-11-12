package keel.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CommitHook;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Two writes lock two rows in opposite orders, so the database aborts one of them as a deadlock
 * (CockroachDB: 40001; PostgreSQL: 40P01) before its COMMIT is sent. The store rolls
 * it back and runs it again, and both commit (docs/storage-design.md section 5.4).
 */
final class DeadlockRetry {

    private DeadlockRetry() {
    }

    static void run(DataSource database) throws Exception {
        Sql.execute(database, "CREATE TABLE IF NOT EXISTS deadlock_probe (k INT PRIMARY KEY, v INT NOT NULL)");
        Sql.execute(database, "INSERT INTO deadlock_probe (k, v) VALUES (1, 0), (2, 0) ON CONFLICT (k) DO UPDATE SET v = 0");
        Transactions transactions = new Transactions(database, CommitHook.NONE);
        CyclicBarrier bothLocked = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                () -> lockBoth(transactions, 1, 2, bothLocked, attempts));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                () -> lockBoth(transactions, 2, 1, bothLocked, attempts));

        assertThat(first.get(120, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(second.get(120, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(attempts.get()).as("the deadlock victim ran again").isGreaterThanOrEqualTo(3);
        assertThat(Sql.column(database, "SELECT v FROM deadlock_probe ORDER BY k")).containsExactly("2", "2");
    }

    /** Locks {@code a}, waits on its first attempt until the other write locked its first row too, then locks {@code b}. */
    private static int lockBoth(Transactions transactions, int a, int b, CyclicBarrier bothLocked, AtomicInteger attempts) {
        boolean[] firstAttempt = {true};
        return transactions.write(UUID.randomUUID(), "deadlock probe " + a + " then " + b, connection -> {
            attempts.incrementAndGet();
            Jdbc.update(connection, "UPDATE deadlock_probe SET v = v + 1 WHERE k = ?", a);
            if (firstAttempt[0]) {
                firstAttempt[0] = false;
                try {
                    bothLocked.await(Duration.ofSeconds(60).toSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            Jdbc.update(connection, "UPDATE deadlock_probe SET v = v + 1 WHERE k = ?", b);
            return 1;
        }, done -> true);
    }
}
