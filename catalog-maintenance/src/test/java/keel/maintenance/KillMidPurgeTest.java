package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker killed part-way through a purge (D30 (6)): the object stays claimed (PURGING) with some of its files gone,
 * and the next worker of the same identity gets the claim back, deletes the rest and completes the purge. Heavy: it
 * starts a worker in its own JVM and kills it.
 */
@Tag("heavy")
class KillMidPurgeTest {

    private static final int FILES = 2_500;

    private final TestCatalog catalog = TestCatalog.get();

    @TempDir
    Path folder;

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void aKilledPurgeIsResumedFromItsClaim() throws Exception {
        FakeClock clock = new FakeClock();
        String id;
        String location;
        try (RESTCatalog writer = catalog.writer()) {
            TableIdentifier table = TestCatalog.table(writer, "killed");
            Table loaded = writer.loadTable(table);
            loaded.newAppend().appendFile(TestCatalog.dataFile(loaded, "a")).commit();
            id = loaded.uuid().toString();
            location = loaded.location();
            ExecutorService puts = Executors.newFixedThreadPool(8);
            List<Future<?>> written = new ArrayList<>();
            for (int i = 0; i < FILES; i++) {
                String file = location + "/data/stray-" + i + ".parquet";
                written.add(puts.submit(() -> catalog.put(file, "x")));
            }
            for (Future<?> put : written) {
                put.get();
            }
            puts.shutdown();
            assertThat(writer.dropTable(table, false)).isTrue();
        }
        catalog.advance(Duration.ofDays(31), clock);
        int before = catalog.list(location + "/").size();
        assertThat(before).isGreaterThan(FILES);

        Process worker = start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(worker.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null && !line.startsWith("DELETED")) {
                // Spring and Iceberg logging, until the first batch is gone.
            }
            assertThat(line).as("the worker deleted a batch before it ended").isNotNull();
            worker.destroyForcibly();
            assertThat(worker.waitFor(1, TimeUnit.MINUTES)).isTrue();
        } finally {
            worker.destroyForcibly();
        }
        int left = catalog.list(location + "/").size();
        assertThat(left).as("killed part-way").isPositive().isLessThan(before);
        assertThat(catalog.state(id)).isEqualTo("PURGING");

        try (Worker restarted = catalog.worker(clock, Compactor.NONE)) {
            ScanReport report = restarted.runOnce();
            assertThat(report.purged).isGreaterThanOrEqualTo(1);
        }

        assertThat(catalog.list(location + "/")).isEmpty();
        assertThat(catalog.state(id)).isEqualTo("PURGED");
    }

    private Process start() throws Exception {
        return ChildJvm.start(folder, List.of(), PurgeProcess.class, ChildJvm.workerArguments(catalog, folder));
    }
}
