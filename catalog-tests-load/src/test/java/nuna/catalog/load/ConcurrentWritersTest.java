package nuna.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Many writers appending to one table through two servers, as engines committing to a hot table do (M8): no
 * 5xx, no deadlock in PostgreSQL, and main's history holds every acknowledged commit exactly once.
 */
@Tag("overnight")
class ConcurrentWritersTest {

    private static final int COMMITS_PER_WRITER = 25;

    private static Stack stack;
    private static List<ServerProcess> servers;

    @BeforeAll
    static void start() {
        stack = new Stack(false);
        ServerProcess first = stack.server("writers-a");
        stack.grantWriters();
        servers = List.of(first, stack.server("writers-b"));
        warmUp();
    }

    /**
     * Has each server vend credentials once before any writer starts. A vend waits for Kafka's ack of its audit
     * record (D23), and a server's first one also waits for the audit topic's metadata: in the overnight run of
     * 2026-11-16 the server started last answered the first two loads of the run with 503, Kafka having taken more
     * than the 5 s delivery timeout. The run measures contention, so it starts once each server has vended.
     */
    private static void warmUp() {
        String token = stack.token("m8-writer", Stack.WRITERS);
        List<String> namespace = List.of("load", "warm-up");
        Catalog any = new Catalog(token, servers.getFirst().uri());
        any.createNamespace(namespace);
        any.createTable(namespace, "t", 2);
        for (ServerProcess server : servers) {
            Catalog one = new Catalog(token, server.uri());
            long started = System.nanoTime();
            long deadline = started + Duration.ofMinutes(2).toNanos();
            while (one.load(namespace, "t", Map.of()).status() != 200) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException(server.name() + " vended no credentials in 2 minutes: "
                            + one.serverErrors + "; see " + server.log());
                }
                ServerProcess.pause(Duration.ofSeconds(1));
            }
            System.out.printf("[m8] %s vended credentials after %.1fs, statuses %s%n", server.name(),
                    (System.nanoTime() - started) / 1e9, one.statusSummary());
        }
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @ParameterizedTest(name = "{0} writers, format v{1}")
    @CsvSource({"8, 2", "16, 2", "32, 2", "8, 3", "32, 3"})
    void writersOnOneTableLoseAndDuplicateNothing(int writers, int formatVersion) throws Exception {
        Catalog catalog = new Catalog(stack.token("m8-writer", Stack.WRITERS),
                servers.stream().map(ServerProcess::uri).toArray(java.net.URI[]::new));
        List<String> namespace = List.of("load", "contention", "w" + writers, "v" + formatVersion);
        catalog.createNamespace(namespace);
        catalog.createTable(namespace, "hot", formatVersion);
        long deadlocksBefore = deadlocks();

        Appender.Ledger ledger = new Appender.Ledger();
        Set<String> ops = new HashSet<>();
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                List<String> mine = new ArrayList<>();
                for (int i = 0; i < COMMITS_PER_WRITER; i++) {
                    mine.add("w" + w + "-" + i);
                }
                ops.addAll(mine);
                done.add(pool.submit(() -> {
                    Appender appender = new Appender(catalog, namespace, "hot", ledger);
                    mine.forEach(op -> appender.append(op, Duration.ofMinutes(15)));
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        int history = Appender.verify(catalog, namespace, "hot", ledger, ops);
        Latencies.report("concurrent-writers.txt", "writers=%d v%d commits=%d in %.1fs (%.0f/s) conflicts=%d resent=%d"
                .formatted(writers, formatVersion, ledger.acknowledged.size(), seconds,
                        ledger.acknowledged.size() / seconds, ledger.conflicts.get(), ledger.resent.get())
                + " commit(incl. retries) " + ledger.commitLatency.summary() + " statuses " + catalog.statusSummary());

        assertThat(catalog.serverErrors).as("5xx answers").isEmpty();
        assertThat(deadlocks()).as("deadlocks").isEqualTo(deadlocksBefore);
        assertThat(ledger.unknown).as("commits never acknowledged").isEmpty();
        assertThat(ledger.acknowledged).hasSize(writers * COMMITS_PER_WRITER);
        assertThat(history).as("snapshots in main's history").isEqualTo(writers * COMMITS_PER_WRITER);
    }

    /**
     * The deadlocks PostgreSQL has counted in the catalog database, read again after 1 s when the read fails, three
     * tries at most. The read is this test's own instrument, not the catalog under test, and no writer runs while it
     * reads, so a read a second later reads the same count. In the overnight run of 2026-11-18 the read after "32
     * writers, format v2" failed on one connection attempt that timed out reaching the container (backlog 55); a third
     * failure in a row still fails the case. Each repeat is printed, so the overnight log shows any.
     */
    private static long deadlocks() {
        for (int attempt = 1; ; attempt++) {
            try {
                long deadlocks = stack.postgres.deadlocks();
                if (attempt > 1) {
                    System.out.printf("[m8] deadlock count read after %d repeat(s)%n", attempt - 1);
                }
                return deadlocks;
            } catch (RuntimeException e) {
                if (attempt == 3) {
                    throw e;
                }
                System.out.printf("[m8] deadlock count not read, repeating in 1 s: %s, caused by %s%n", e, e.getCause());
                ServerProcess.pause(Duration.ofSeconds(1));
            }
        }
    }
}
