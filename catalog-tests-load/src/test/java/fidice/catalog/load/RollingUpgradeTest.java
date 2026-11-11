package fidice.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A rolling upgrade across a schema change (M8). Two servers of the previous release, whose schema stops at V3,
 * take traffic; servers of this release replace them one at a time, the first of them migrating PostgreSQL
 * to V4 (the journal becomes partitioned) while previous-release servers still commit. Each server leaves
 * only after the balancer has drained it. No request fails and no commit is lost or doubled.
 */
@Tag("overnight")
class RollingUpgradeTest {

    private static final int WRITERS = 8;
    private static final List<String> NAMESPACE = List.of("resilience", "upgrade", "rolling");
    private static final List<String> TABLES = List.of("v2", "v3");

    private static Stack stack;
    private static Path previous;

    @BeforeAll
    static void start() {
        previous = PreviousRelease.jar();
        stack = new Stack(false);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void upgradingOneServerAtATimeAcrossV4LosesNothing() throws Exception {
        String url = stack.postgres.serverUrl();
        ServerProcess old1 = stack.server("upgrade-old-1", previous, url);
        stack.grantWriters();
        ServerProcess old2 = stack.server("upgrade-old-2", previous, url);
        assertThat(schemaVersion()).as("the previous release's schema").isEqualTo("3");

        Catalog catalog = new Catalog(stack.token("m8-writer", Stack.WRITERS), old1.uri(), old2.uri());
        catalog.createNamespace(NAMESPACE);
        catalog.createTable(NAMESPACE, "v2", 2);
        catalog.createTable(NAMESPACE, "v3", 3);

        Appender.Ledger ledger = new Appender.Ledger();
        Set<String> ops = ConcurrentHashMap.newKeySet();
        AtomicBoolean running = new AtomicBoolean(true);
        ServerProcess new1;
        ServerProcess new2;
        int beforeUpgrade;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                int writer = w;
                String table = TABLES.get(w % TABLES.size());
                done.add(pool.submit(() -> {
                    Appender appender = new Appender(catalog, NAMESPACE, table, ledger);
                    for (int i = 0; running.get(); i++) {
                        String op = table + "-w" + writer + "-" + i;
                        ops.add(op);
                        appender.append(op, Duration.ofMinutes(3));
                    }
                }));
            }
            ServerProcess.pause(Duration.ofSeconds(5));
            beforeUpgrade = ledger.acknowledged.size();

            new1 = stack.server("upgrade-new-1");
            assertThat(schemaVersion()).as("the schema once a server of this release started").isEqualTo("4");
            catalog.add(new1.uri());
            ServerProcess.pause(Duration.ofSeconds(5));
            catalog.drain(old1.uri());
            old1.close();

            new2 = stack.server("upgrade-new-2");
            catalog.add(new2.uri());
            ServerProcess.pause(Duration.ofSeconds(5));
            catalog.drain(old2.uri());
            old2.close();

            ServerProcess.pause(Duration.ofSeconds(5));
            running.set(false);
            for (Future<?> f : done) {
                f.get();
            }
        }

        int history = 0;
        for (String table : TABLES) {
            Appender.Ledger mine = new Appender.Ledger();
            ledger.acknowledged.forEach((op, id) -> {
                if (op.startsWith(table + "-")) {
                    mine.acknowledged.put(op, id);
                }
            });
            ledger.unknown.stream().filter(op -> op.startsWith(table + "-")).forEach(mine.unknown::add);
            history += Appender.verify(catalog, NAMESPACE, table, mine, ops);
        }
        long journal = Long.parseLong(stack.postgres.value("SELECT count(*) FROM journal"));
        Latencies.report("rolling-upgrade.txt", ("acknowledged before the first new server=%d, in all=%d, history=%d,"
                + " journal rows=%d; conflicts=%d resent=%d; statuses %s; commit %s").formatted(beforeUpgrade,
                ledger.acknowledged.size(), history, journal, ledger.conflicts.get(), ledger.resent.get(),
                catalog.statusSummary(), ledger.commitLatency.summary()));

        assertThat(catalog.serverErrors).as("5xx answers").isEmpty();
        assertThat(catalog.unreachable.get()).as("requests that reached no server").isZero();
        assertThat(ledger.unknown).as("commits never acknowledged").isEmpty();
        assertThat(history).isEqualTo(ledger.acknowledged.size());
        assertThat(journal).as("journal rows: one per commit and create, across the partitioning")
                .isGreaterThanOrEqualTo(ledger.acknowledged.size());
        assertThat(stack.postgres.value("SELECT relkind FROM pg_class WHERE relname = 'journal'")).isEqualTo("p");
        assertThat(new1.alive() && new2.alive()).isTrue();
    }

    private static String schemaVersion() {
        return stack.postgres.value("SELECT max(version::int)::text FROM flyway_schema_history WHERE success");
    }
}
