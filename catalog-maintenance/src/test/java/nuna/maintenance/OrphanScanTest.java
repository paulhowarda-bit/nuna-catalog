package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Orphan removal (D30 (8)): a file beneath the table's location that no metadata reaches goes to the pending-deletion
 * list once it is older than the policy's orphan age, and not before; a reachable file never does.
 */
@Tag("heavy")
class OrphanScanTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void youngOrphansAreKept() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            TableIdentifier id = TestCatalog.table(writer, "scanned");
            Table table = writer.loadTable(id);
            DataFile live = TestCatalog.dataFile(table, "live");
            table.newAppend().appendFile(live).commit();
            String orphan = table.location() + "/data/orphan.parquet";
            String sibling = table.location() + "-twin/data/elsewhere.parquet";
            catalog.put(orphan, "written, never committed");
            catalog.put(sibling, "another table's location shares this one's name as a prefix");
            catalog.policy(id, Map.of("orphan-removal.enabled", "true", "orphan-removal.older-than", "P8D"));
            String object = table.uuid().toString();

            worker.runOnce();
            assertThat(catalog.pending(object)).as("a moment old").isEmpty();
            catalog.advance(Duration.ofDays(7), clock);
            worker.runOnce();
            assertThat(catalog.pending(object)).as("7 days old, under the orphan age of 8").isEmpty();

            catalog.advance(Duration.ofDays(2), clock);
            worker.runOnce();

            assertThat(catalog.pending(object)).containsExactly(orphan);
            assertThat(catalog.exists(orphan)).as("pending, not deleted").isTrue();
            assertThat(catalog.exists(sibling)).isTrue();
            assertThat(TestCatalog.metadataFiles(writer.loadTable(id))).allMatch(catalog::exists);
            assertThat(catalog.exists(live.location())).isTrue();
        }
    }
}
