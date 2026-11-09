package fidice.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The SLO runs of M8, through two servers on one PostgreSQL cluster. The targets (load p99 under 50 ms, 304
 * p99 under 10 ms, commit p99 under 300 ms, 500 commits a second) are for production hardware; on a laptop,
 * where clients, servers, database and object store share one machine, the numbers are recorded in
 * {@code target/m8/results/slo.txt} as relative behaviour and the run checks only that nothing failed.
 */
@Tag("heavy")
class SloTest {

    private static final int TABLES = 32;
    private static final int READERS = 16;
    private static final int READS_PER_READER = 300;
    private static final Duration COMMIT_RUN = Duration.ofSeconds(30);
    private static final List<String> NAMESPACE = List.of("slo", "tables");

    private static Stack stack;
    private static Catalog catalog;
    private static List<Catalog> perServer;

    @BeforeAll
    static void start() {
        stack = new Stack(false);
        ServerProcess first = stack.server("slo-a");
        stack.grantWriters();
        ServerProcess second = stack.server("slo-b");
        String token = stack.token("m8-writer", Stack.WRITERS);
        catalog = new Catalog(token, first.uri(), second.uri());
        perServer = List.of(new Catalog(token, first.uri()), new Catalog(token, second.uri()));
        catalog.createNamespace(NAMESPACE);
        for (int t = 0; t < TABLES; t++) {
            catalog.createTable(NAMESPACE, "t" + t, 2);
            Appender appender = new Appender(catalog, NAMESPACE, "t" + t, new Appender.Ledger());
            for (int s = 0; s < 5; s++) {
                appender.append("seed-" + t + "-" + s, Duration.ofMinutes(1));
            }
        }
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void sloRun() throws Exception {
        warmUp();
        Latencies loads = read(READS_PER_READER);
        List<Map<String, String>> etags = List.of(new java.util.HashMap<>(), new java.util.HashMap<>());
        for (int s = 0; s < perServer.size(); s++) {
            for (int t = 0; t < TABLES; t++) {
                etags.get(s).put("t" + t, perServer.get(s).load(NAMESPACE, "t" + t, Map.of()).etag());
            }
        }
        Latencies notModified = readConditional(etags);
        int crossServer304 = 0;
        for (int t = 0; t < TABLES; t++) {
            String table = "t" + t;
            if (perServer.get(1).load(NAMESPACE, table, Map.of("If-None-Match", etags.get(0).get(table))).status() == 304) {
                crossServer304++;
            }
        }

        Appender.Ledger ledger = new Appender.Ledger();
        long started = System.nanoTime();
        long deadline = started + COMMIT_RUN.toNanos();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < TABLES; t++) {
                String table = "t" + t;
                done.add(pool.submit(() -> {
                    Appender appender = new Appender(catalog, NAMESPACE, table, ledger);
                    for (int i = 0; System.nanoTime() < deadline; i++) {
                        appender.append(table + "-run-" + i, Duration.ofMinutes(1));
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        double rate = ledger.acknowledged.size() / seconds;

        String file = "slo.txt";
        Latencies.report(file, "load (%d readers, %d tables):  %s   target p99 < 50 ms".formatted(READERS, TABLES,
                loads.summary()));
        Latencies.report(file, "304 (%d readers, ETag sent back to the server that gave it): %s   target p99 < 10 ms"
                .formatted(READERS, notModified.summary()));
        Latencies.report(file, "304 across servers: %d of %d conditional loads with the other server's ETag"
                .formatted(crossServer304, TABLES));
        Latencies.report(file, "commit (%d writers, own table): %s   target p99 < 300 ms".formatted(TABLES,
                ledger.commitLatency.summary()));
        Latencies.report(file, "commit rate: %d commits in %.1fs = %.0f/s   target >= 500/s; conflicts=%d"
                .formatted(ledger.acknowledged.size(), seconds, rate, ledger.conflicts.get()));
        Latencies.report(file, "statuses " + catalog.statusSummary());

        assertThat(catalog.serverErrors).as("5xx answers").isEmpty();
        assertThat(perServer).allSatisfy(c -> assertThat(c.serverErrors).as("5xx answers").isEmpty());
        assertThat(ledger.unknown).as("commits never acknowledged").isEmpty();
        assertThat(ledger.conflicts.get()).as("conflicts between writers of different tables").isZero();
    }

    private void warmUp() throws Exception {
        read(50);
    }

    private Latencies read(int perReader) throws Exception {
        Latencies latencies = new Latencies();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < perReader; i++) {
                        Catalog.Answer answer = catalog.load(NAMESPACE,
                                "t" + ThreadLocalRandom.current().nextInt(TABLES), Map.of());
                        assertThat(answer.status()).isEqualTo(200);
                        latencies.record(answer.nanos());
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }
        return latencies;
    }

    /** Conditional loads, each reader keeping to one server as a client behind a sticky balancer does. */
    private Latencies readConditional(List<Map<String, String>> etags) throws Exception {
        Latencies latencies = new Latencies();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                int server = r % perServer.size();
                done.add(pool.submit(() -> {
                    for (int i = 0; i < READS_PER_READER; i++) {
                        String table = "t" + ThreadLocalRandom.current().nextInt(TABLES);
                        Catalog.Answer answer = perServer.get(server).load(NAMESPACE, table,
                                Map.of("If-None-Match", etags.get(server).get(table)));
                        assertThat(answer.status()).isEqualTo(304);
                        latencies.record(answer.nanos());
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }
        return latencies;
    }
}
