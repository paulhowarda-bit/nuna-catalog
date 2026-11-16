package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A writer committing while the orphan scan runs (D30 (8)): the scan lists the table's prefix first and reads the
 * metadata it checks the listing against again afterwards, so a file written before the listing and committed
 * during it is reachable, not an orphan. Heavy: it races a writer against the scan.
 */
@Tag("heavy")
class OrphanRaceTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void aFileCommittedBetweenTheListingAndTheSecondReadIsReachable() throws Exception {
        try (RESTCatalog writer = catalog.writer(); Catalogs worker = new Catalogs(catalog.uri, catalog.workerTokens(),
                Map.of()); Storage storage = Storage.of(catalog.storageProfiles())) {
            TableIdentifier id = TestCatalog.table(writer, "raced");
            Table table = writer.loadTable(id);
            table.newAppend().appendFile(TestCatalog.dataFile(table, "first")).commit();
            DataFile late = TestCatalog.dataFile(table, "late");
            String orphan = table.location() + "/data/orphan.parquet";
            catalog.put(orphan, "never committed");
            Table scanned = worker.load(TestCatalog.CATALOG, List.of(id.namespace().levels()), id.name());

            // Every file looks old enough: only reachability decides.
            List<String> found = OrphanScan.find(scanned, storage, Instant.now().plus(Duration.ofDays(1)), () -> true,
                    () -> table.newAppend().appendFile(late).commit());

            assertThat(found).contains(orphan).doesNotContain(late.location());
        }
    }

    @Test
    void aWriterCommittingThroughoutTheScansLosesNothing() throws Exception {
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (RESTCatalog writer = catalog.writer(); Catalogs worker = new Catalogs(catalog.uri, catalog.workerTokens(),
                Map.of()); Storage storage = Storage.of(catalog.storageProfiles())) {
            TableIdentifier id = TestCatalog.table(writer, "busy");
            Table table = writer.loadTable(id);
            String orphan = table.location() + "/data/orphan.parquet";
            catalog.put(orphan, "never committed");
            Set<String> committed = ConcurrentHashMap.newKeySet();
            AtomicBoolean stop = new AtomicBoolean();
            Future<?> writing = threads.submit(() -> {
                for (int i = 0; !stop.get(); i++) {
                    DataFile file = TestCatalog.dataFile(table, "w" + i);
                    table.newAppend().appendFile(file).commit();
                    committed.add(file.location());
                }
                return null;
            });
            Set<String> found = ConcurrentHashMap.newKeySet();
            Instant end = Instant.now().plusSeconds(20);
            int scans = 0;
            Thread.sleep(500);
            while (Instant.now().isBefore(end)) {
                Table scanned = worker.load(TestCatalog.CATALOG, List.of(id.namespace().levels()), id.name());
                // An orphan age of 10 seconds: far longer than the writer takes from writing a file to committing it.
                found.addAll(OrphanScan.find(scanned, storage, Instant.now().minusSeconds(10), () -> true, () -> { }));
                scans++;
            }
            stop.set(true);
            writing.get();

            assertThat(scans).isGreaterThan(3);
            assertThat(committed).hasSizeGreaterThan(10);
            assertThat(found).as("scanned %d times while %d files were committed", scans, committed.size())
                    .contains(orphan).doesNotContainAnyElementsOf(committed);
        } finally {
            threads.shutdownNow();
        }
    }
}
