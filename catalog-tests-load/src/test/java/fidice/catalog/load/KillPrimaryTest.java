package fidice.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The PostgreSQL primary dies while writers are committing, and the synchronous standby is promoted (M8):
 * servers carry on against the new primary without a restart, writers that resend under their idempotency
 * keys finish every commit, and main's history holds every acknowledged commit exactly once. While the
 * database is away the servers answer 503 with Retry-After in the Iceberg error shape; the only 500 allowed
 * is CommitStateUnknownException, for a commit whose COMMIT was sent and whose outcome the store could not
 * then tell.
 */
@Tag("heavy")
class KillPrimaryTest {

    private static final int WRITERS = 8;
    private static final List<String> NAMESPACE = List.of("resilience", "failover");

    private static Stack stack;
    private static List<ServerProcess> servers;

    @BeforeAll
    static void start() {
        stack = new Stack(false);
        ServerProcess first = stack.server("failover-a");
        stack.grantWriters();
        servers = List.of(first, stack.server("failover-b"));
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void killingThePrimaryMidCommitLosesNoAcknowledgedCommit() throws Exception {
        Catalog catalog = new Catalog(stack.token("m8-writer", Stack.WRITERS),
                servers.stream().map(ServerProcess::uri).toArray(java.net.URI[]::new));
        catalog.createNamespace(NAMESPACE);
        catalog.createTable(NAMESPACE, "busy", 2);

        Appender.Ledger ledger = new Appender.Ledger();
        Set<String> ops = ConcurrentHashMap.newKeySet();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong killedAt = new AtomicLong();
        AtomicLong firstAckAfterKill = new AtomicLong();
        long ackedBeforeKill;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                int writer = w;
                done.add(pool.submit(() -> {
                    Appender appender = new Appender(catalog, NAMESPACE, "busy", ledger);
                    for (int i = 0; running.get(); i++) {
                        String op = "w" + writer + "-" + i;
                        ops.add(op);
                        if (appender.append(op, Duration.ofMinutes(3)) && killedAt.get() != 0) {
                            firstAckAfterKill.compareAndSet(0, System.nanoTime());
                        }
                    }
                }));
            }
            ServerProcess.pause(Duration.ofSeconds(10));
            ackedBeforeKill = ledger.acknowledged.size();
            stack.postgres.killPrimary();
            killedAt.set(System.nanoTime());
            ServerProcess.pause(Duration.ofSeconds(3));
            stack.postgres.promoteStandby();
            ServerProcess.pause(Duration.ofSeconds(20));
            running.set(false);
            for (Future<?> f : done) {
                f.get();
            }
        }

        int history = Appender.verify(catalog, NAMESPACE, "busy", ledger, ops);
        double recovery = (firstAckAfterKill.get() - killedAt.get()) / 1e9;
        Latencies.report("kill-primary.txt", ("acknowledged before kill=%d, in all=%d, history=%d; first commit %.1fs after"
                + " the kill (promotion at 3s); resent=%d conflicts=%d; 5xx=%d statuses %s").formatted(ackedBeforeKill,
                ledger.acknowledged.size(), history, recovery, ledger.resent.get(), ledger.conflicts.get(),
                catalog.fiveHundreds(), catalog.statusSummary()));
        catalog.serverErrors.stream().filter(e -> !e.startsWith("503")).limit(5)
                .forEach(e -> Latencies.report("kill-primary.txt", "  " + e));

        assertThat(servers).allMatch(ServerProcess::alive);
        assertThat(catalog.serverErrors).as("5xx answers in the Iceberg error shape, not a framework error page")
                .allSatisfy(e -> assertThat(e).contains("{\"error\":{").doesNotContain("\"timestamp\""));
        assertThat(catalog.serverErrors).as("5xx answers other than 503 or an unknown commit outcome")
                .allSatisfy(e -> assertThat(e.startsWith("503 ") || e.contains("CommitStateUnknownException"))
                        .as(e).isTrue());
        assertThat(catalog.withoutRetryAfter).as("503 answers without Retry-After").isEmpty();
        assertThat(ledger.unknown).as("commits never acknowledged").isEmpty();
        assertThat(ledger.acknowledged.size()).isGreaterThan((int) ackedBeforeKill);
        assertThat(history).as("snapshots in main's history").isEqualTo(ledger.acknowledged.size());
    }
}
