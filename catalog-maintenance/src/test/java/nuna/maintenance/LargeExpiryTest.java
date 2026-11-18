package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Snapshot expiry on a table of {@value #SNAPSHOTS} snapshots (D30 (2)): one scan expires all but the last, commits
 * through the catalog, and queues every manifest list it leaves unreferenced, in batches, once each. The appends are
 * metadata only (no data file is written) and committed {@value #PER_COMMIT} to a transaction. Prints how long the
 * scan took. Overnight: building the table alone takes minutes.
 */
@Tag("overnight")
class LargeExpiryTest {

    private static final int SNAPSHOTS = 10_000;
    private static final int PER_COMMIT = 500;

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void expiryOnATableOfTenThousandSnapshots() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            TableIdentifier id = TestCatalog.table(writer, "long_history");
            Table table = writer.loadTable(id);
            long building = System.nanoTime();
            for (int done = 0; done < SNAPSHOTS; done += PER_COMMIT) {
                Transaction transaction = table.newTransaction();
                for (int i = done; i < done + PER_COMMIT; i++) {
                    transaction.newFastAppend().appendFile(DataFiles.builder(table.spec())
                            .withPath(table.location() + "/data/append-" + i + ".parquet")
                            .withFormat(FileFormat.PARQUET).withFileSizeInBytes(100).withRecordCount(1).build())
                            .commit();
                }
                transaction.commitTransaction();
            }
            System.out.printf("LargeExpiryTest: %,d snapshots committed in %d s%n", SNAPSHOTS,
                    (System.nanoTime() - building) / 1_000_000_000);
            Table built = writer.loadTable(id);
            assertThat(built.snapshots()).hasSize(SNAPSHOTS);
            Set<String> expiring = new HashSet<>();
            List<Snapshot> history = new ArrayList<>();
            built.snapshots().forEach(history::add);
            Snapshot last = built.currentSnapshot();
            history.stream().filter(snapshot -> snapshot.snapshotId() != last.snapshotId())
                    .forEach(snapshot -> expiring.add(snapshot.manifestListLocation()));
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1"));

            catalog.advance(Duration.ofDays(6), clock);
            long start = System.nanoTime();
            ScanReport report = worker.runOnce();
            long seconds = (System.nanoTime() - start) / 1_000_000_000;
            System.out.printf("LargeExpiryTest: one scan expired %,d snapshots and queued %,d files in %d s%n",
                    report.snapshotsExpired, report.filesRecorded, seconds);

            assertThat(report.failures).isEmpty();
            assertThat(report.snapshotsExpired).isEqualTo(SNAPSHOTS - 1);
            assertThat(writer.loadTable(id).snapshots()).extracting(Snapshot::snapshotId)
                    .containsExactly(last.snapshotId());
            List<String> pending = catalog.pending(table.uuid().toString());
            assertThat(pending).doesNotHaveDuplicates().containsAll(expiring);
            assertThat(pending).as("every data file is still in the last snapshot")
                    .noneMatch(location -> location.contains("/data/append-"));
        }
    }
}
