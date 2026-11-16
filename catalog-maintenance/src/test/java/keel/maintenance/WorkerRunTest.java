package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's scans against a clock the test moves (D30 (2), amended 2026-11-15): one table's whole maintenance over
 * sixteen days, in a cleanup window from an hour before the test's start to three hours after, every day. Expiry runs
 * every scan and finds nothing until a snapshot passes its age; compaction and the orphan scan run in the window, once
 * a night; what expiry and the orphan scan leave unreferenced is deleted seven days after it was recorded, and not
 * before, whether the window is open or not.
 */
class WorkerRunTest {

    private final TestCatalog catalog = TestCatalog.get();
    private final FakeClock clock = new FakeClock();
    private final Instant start = clock.instant();
    private Duration elapsed = Duration.ZERO;

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void theWorkerRunsOnItsClock() throws Exception {
        StubCompactor compactor = new StubCompactor();
        CleanupWindow window = new CleanupWindow(time(start.minus(Duration.ofHours(1))),
                time(start.plus(Duration.ofHours(3))), ZoneOffset.UTC);
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, compactor,
                Storage.of(catalog.storageProfiles()), catalog.workerTokens(), new SimpleMeterRegistry(), window, 2)) {
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
            // One small file is enough to need compaction.
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1", "orphan-removal.enabled", "true",
                    "orphan-removal.older-than", "P7DT1H", "compaction.enabled", "true",
                    "compaction.min-input-files", "1"));
            UUID uuid = UUID.fromString(table.uuid().toString());
            String object = uuid.toString();
            Set<String> expired = new HashSet<>(TestCatalog.reachable(table, first));
            expired.removeAll(TestCatalog.reachable(table, second));
            assertThat(expired).contains(a.location(), first.manifestListLocation());
            // The second snapshot's manifest still names a, as deleted: Iceberg's expiry leaves a until that snapshot
            // expires too, and the orphan scan takes it once it is older than the orphan age.
            expired.remove(a.location());

            worker.runOnce();
            assertThat(compactor.launches(uuid)).as("day 0: compaction launched").isEqualTo(1);
            assertThat(writer.loadTable(id).snapshots()).hasSize(2);
            assertThat(catalog.pending(object)).isEmpty();

            at(Duration.ofHours(1), worker);
            assertThat(compactor.launches(uuid)).as("an hour on: assessed once a night").isEqualTo(1);
            at(Duration.ofHours(6), worker);
            assertThat(compactor.launches(uuid)).as("the window closed").isEqualTo(1);

            at(Duration.ofDays(6), worker);
            assertThat(compactor.launches(uuid)).as("day 6, the next night it ran").isEqualTo(2);
            assertThat(writer.loadTable(id).snapshots()).extracting(Snapshot::snapshotId)
                    .containsExactly(second.snapshotId());
            assertThat(catalog.pending(object)).as("day 6: expired, the orphan only 6 days old")
                    .containsExactlyInAnyOrderElementsOf(expired);

            at(Duration.ofDays(7), worker);
            assertThat(catalog.pending(object)).as("day 7: scanned, the orphan 7 days old").doesNotContain(orphan);
            at(Duration.ofDays(7).plusHours(2), worker);
            assertThat(catalog.pending(object)).as("day 7, 2 hours on: old enough, but scanned tonight")
                    .doesNotContain(orphan);
            at(Duration.ofDays(7).plusHours(6), worker);
            assertThat(catalog.pending(object)).as("day 7, 6 hours on: the window closed").doesNotContain(orphan);
            at(Duration.ofDays(8), worker);
            assertThat(catalog.pending(object)).as("day 8").contains(orphan, a.location());
            assertThat(expired).as("pending, not deleted").allMatch(catalog::exists);

            at(Duration.ofDays(12).plusHours(22), worker);
            assertThat(expired).as("day 12, 22 hours on: recorded on day 6, due on day 13").allMatch(catalog::exists);
            at(Duration.ofDays(13).plusHours(2), worker);
            assertThat(expired).as("day 13, 2 hours on").noneMatch(catalog::exists);
            assertThat(catalog.pending(object)).containsExactlyInAnyOrder(orphan, a.location());
            assertThat(catalog.exists(b.location())).isTrue();

            at(Duration.ofDays(16), worker);
            assertThat(List.of(orphan, a.location())).as("day 16: recorded on day 8").noneMatch(catalog::exists);
            assertThat(catalog.pending(object)).isEmpty();
            assertThat(catalog.exists(b.location())).isTrue();
            assertThat(TestCatalog.metadataFiles(writer.loadTable(id))).allMatch(catalog::exists);
        }
    }

    /** Moves both clocks on to {@code sinceStart} after the test's start and runs one scan. */
    private void at(Duration sinceStart, Worker worker) {
        catalog.advance(sinceStart.minus(elapsed), clock);
        elapsed = sinceStart;
        worker.runOnce();
    }

    private static LocalTime time(Instant instant) {
        return LocalTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
