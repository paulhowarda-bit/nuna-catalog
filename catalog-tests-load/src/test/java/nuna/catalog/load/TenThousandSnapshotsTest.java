package nuna.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A table with more than ten thousand snapshots (M8): it commits, loads in full and by refs, answers 304 to a
 * client that has it, and its history is whole. The latencies are recorded as the table grows.
 */
@Tag("overnight")
class TenThousandSnapshotsTest {

    private static final int COMMITS = 101;
    private static final int SNAPSHOTS_PER_COMMIT = 100;

    private static Stack stack;
    private static Catalog catalog;

    @BeforeAll
    static void start() {
        stack = new Stack(false);
        ServerProcess server = stack.server("snapshots");
        stack.grantWriters();
        catalog = new Catalog(stack.token("m8-writer", Stack.WRITERS), server.uri());
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @ParameterizedTest(name = "format v{0}")
    @ValueSource(ints = {2, 3})
    void aTableOfTenThousandSnapshotsStaysWhole(int formatVersion) {
        List<String> namespace = List.of("load", "history", "deep", "v" + formatVersion);
        catalog.createNamespace(namespace);
        catalog.createTable(namespace, "long", formatVersion);

        Appender.Ledger ledger = new Appender.Ledger();
        Appender appender = new Appender(catalog, namespace, "long", ledger);
        Set<String> ops = new HashSet<>();
        Latencies early = new Latencies();
        Latencies late = new Latencies();
        for (int c = 0; c < COMMITS; c++) {
            List<String> batch = new ArrayList<>();
            for (int s = 0; s < SNAPSHOTS_PER_COMMIT; s++) {
                batch.add("c" + c + "-" + s);
            }
            ops.addAll(batch);
            long started = System.nanoTime();
            assertThat(appender.append(batch, Duration.ofMinutes(2))).as("commit " + c).isTrue();
            (c < 10 ? early : late).record(System.nanoTime() - started);
        }
        int history = Appender.verify(catalog, namespace, "long", ledger, ops);
        assertThat(history).isEqualTo(COMMITS * SNAPSHOTS_PER_COMMIT);

        Latencies full = new Latencies();
        Latencies refs = new Latencies();
        Latencies notModified = new Latencies();
        Latencies single = new Latencies();
        Catalog.Answer loaded = null;
        for (int i = 0; i < 20; i++) {
            loaded = catalog.load(namespace, "long", Map.of());
            assertThat(loaded.status()).isEqualTo(200);
            full.record(loaded.nanos());
        }
        int bytes = loaded.body().length();
        Catalog.Answer byRefs = null;
        for (int i = 0; i < 50; i++) {
            byRefs = catalog.load(namespace, "long", "?snapshots=refs", Map.of());
            assertThat(byRefs.status()).isEqualTo(200);
            refs.record(byRefs.nanos());
        }
        assertThat(byRefs.json().get("metadata").get("snapshots").size()).as("snapshots loaded by refs").isLessThan(10);
        for (int i = 0; i < 200; i++) {
            Catalog.Answer answer = catalog.load(namespace, "long", Map.of("If-None-Match", loaded.etag()));
            assertThat(answer.status()).isEqualTo(304);
            notModified.record(answer.nanos());
        }
        for (int i = 0; i < 20; i++) {
            long started = System.nanoTime();
            assertThat(appender.append("top-" + i, Duration.ofMinutes(1))).isTrue();
            single.record(System.nanoTime() - started);
        }

        String file = "ten-thousand-snapshots.txt";
        Latencies.report(file, "v%d snapshots=%d metadata=%.1fMB".formatted(formatVersion, history + 20, bytes / 1e6));
        Latencies.report(file, "v%d commit of 100 snapshots, first 10:  %s".formatted(formatVersion, early.summary()));
        Latencies.report(file, "v%d commit of 100 snapshots, last 91:   %s".formatted(formatVersion, late.summary()));
        Latencies.report(file, "v%d load in full:                       %s".formatted(formatVersion, full.summary()));
        Latencies.report(file, "v%d load ?snapshots=refs:               %s".formatted(formatVersion, refs.summary()));
        Latencies.report(file, "v%d load answered 304:                  %s".formatted(formatVersion, notModified.summary()));
        Latencies.report(file, "v%d commit of 1 snapshot on top:        %s".formatted(formatVersion, single.summary()));
        assertThat(catalog.serverErrors).as("5xx answers").isEmpty();
    }
}
