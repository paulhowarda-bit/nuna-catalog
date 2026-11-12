package keel.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        long deadlocksBefore = stack.postgres.deadlocks();

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
        assertThat(stack.postgres.deadlocks()).as("deadlocks").isEqualTo(deadlocksBefore);
        assertThat(ledger.unknown).as("commits never acknowledged").isEmpty();
        assertThat(ledger.acknowledged).hasSize(writers * COMMITS_PER_WRITER);
        assertThat(history).as("snapshots in main's history").isEqualTo(writers * COMMITS_PER_WRITER);
    }
}
