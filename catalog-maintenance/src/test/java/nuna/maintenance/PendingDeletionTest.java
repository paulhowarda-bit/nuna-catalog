package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A file on the pending-deletion list is deleted, with the worker's own storage identity, only once the catalog lists
 * it as due: after the database's PITR window (7 days) has passed since it was recorded (D30 (5)).
 */
@Tag("heavy")
class PendingDeletionTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void pendingDeletionsWaitForTheirTime() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            TableIdentifier id = TestCatalog.table(writer, "pending");
            Table table = writer.loadTable(id);
            List<String> files = List.of(table.location() + "/data/x.parquet", table.location() + "/metadata/y.avro");
            files.forEach(file -> catalog.put(file, "unreferenced"));
            catalog.management().recordDeletions(TestCatalog.CATALOG, java.util.UUID.fromString(table.uuid().toString()),
                    files, List.of());

            worker.runOnce();
            assertThat(files).as("just recorded").allMatch(catalog::exists);
            catalog.advance(Duration.ofDays(6), clock);
            worker.runOnce();
            assertThat(files).as("6 days on").allMatch(catalog::exists);
            catalog.advance(Duration.ofDays(1).minusMinutes(1), clock);
            worker.runOnce();
            assertThat(files).as("a minute short of 7 days").allMatch(catalog::exists);

            catalog.advance(Duration.ofMinutes(2), clock);
            worker.runOnce();

            assertThat(files).noneMatch(catalog::exists);
            assertThat(catalog.pending(table.uuid().toString())).as("reported done").isEmpty();
        }
    }
}
