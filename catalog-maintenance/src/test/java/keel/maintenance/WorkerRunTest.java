package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's scans against a clock the test moves (D30 (2)): one table's whole maintenance over sixteen days.
 * Expiry runs every scan and finds nothing until a snapshot passes its age; the orphan scan and compaction run at most
 * once per interval (a day); what expiry and the orphan scan leave unreferenced is deleted seven days after it was
 * recorded, and not before.
 */
class WorkerRunTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }
    private final FakeClock clock = new FakeClock();
    private final Map<UUID, AtomicInteger> launches = new ConcurrentHashMap<>();

    @Test
    void theWorkerRunsOnItsClock() throws Exception {
        Compactor compactor = table -> {
            launches.computeIfAbsent(table.id(), id -> new AtomicInteger()).incrementAndGet();
            return Compactor.Launch.STARTED;
        };
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, compactor)) {
            TableIdentifier id = TestCatalog.table(writer, "maintained");
            Table table = writer.loadTable(id);
            DataFile a = TestCatalog.dataFile(table, "a");
            table.newAppend().appendFile(a).commit();
            Snapshot first = table.currentSnapshot();
            DataFile b = TestCatalog.dataFile(table, "b");
            table.newOverwrite().deleteFile(a).addFile(b).commit();
            Snapshot second = table.currentSnapshot();
            String orphan = table.location() + "/data/orphan.parquet";
            catalog.put(orphan, "never committed");
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1", "orphan-removal.enabled", "true",
                    "orphan-removal.older-than", "P8D", "compaction.enabled", "true"));
            UUID uuid = UUID.fromString(table.uuid().toString());
            String object = uuid.toString();
            Set<String> expired = new HashSet<>(TestCatalog.reachable(table, first));
            expired.removeAll(TestCatalog.reachable(table, second));
            assertThat(expired).contains(a.location(), first.manifestListLocation());
            // The second snapshot's manifest still names a, as deleted: Iceberg's expiry leaves a until that snapshot
            // expires too, and the orphan scan takes it once it is older than the orphan age.
            expired.remove(a.location());

            worker.runOnce();
            assertThat(launches(uuid)).as("day 0: compaction launched").isEqualTo(1);
            assertThat(writer.loadTable(id).snapshots()).hasSize(2);
            assertThat(catalog.pending(object)).isEmpty();

            at(Duration.ofHours(1), worker);
            assertThat(launches(uuid)).as("an hour on: within the compaction interval").isEqualTo(1);

            at(Duration.ofDays(6), worker);
            assertThat(launches(uuid)).as("day 6").isEqualTo(2);
            assertThat(writer.loadTable(id).snapshots()).extracting(Snapshot::snapshotId)
                    .containsExactly(second.snapshotId());
            assertThat(catalog.pending(object)).as("day 6: expired, the orphan only 6 days old")
                    .containsExactlyInAnyOrderElementsOf(expired);

            at(Duration.ofHours(36), worker);
            assertThat(catalog.pending(object)).as("day 7.5: scanned, the orphan 7.5 days old").doesNotContain(orphan);
            at(Duration.ofHours(18), worker);
            assertThat(catalog.pending(object)).as("day 8.25: old enough, but scanned less than a day ago")
                    .doesNotContain(orphan);
            at(Duration.ofHours(12), worker);
            assertThat(catalog.pending(object)).as("day 8.75").contains(orphan, a.location());
            assertThat(expired).as("pending, not deleted").allMatch(catalog::exists);

            at(Duration.ofHours(99), worker);
            assertThat(expired).as("day 12.875: recorded on day 6, due on day 13").allMatch(catalog::exists);
            at(Duration.ofHours(6), worker);
            assertThat(expired).as("day 13.125").noneMatch(catalog::exists);
            assertThat(catalog.pending(object)).containsExactlyInAnyOrder(orphan, a.location());
            assertThat(catalog.exists(b.location())).isTrue();

            at(Duration.ofHours(69), worker);
            assertThat(List.of(orphan, a.location())).as("day 16: recorded on day 8.75").noneMatch(catalog::exists);
            assertThat(catalog.pending(object)).isEmpty();
            assertThat(catalog.exists(b.location())).isTrue();
            assertThat(TestCatalog.metadataFiles(writer.loadTable(id))).allMatch(catalog::exists);
        }
    }

    /** Moves both clocks on and runs one scan. */
    private void at(Duration later, Worker worker) {
        catalog.advance(later, clock);
        worker.runOnce();
    }

    private int launches(UUID table) {
        AtomicInteger count = launches.get(table);
        return count == null ? 0 : count.get();
    }
}
