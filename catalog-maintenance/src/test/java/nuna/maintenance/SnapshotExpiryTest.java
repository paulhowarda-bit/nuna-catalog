package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Snapshot expiry (D30 (2)): Iceberg's RemoveSnapshots commits through the catalog, and every file it leaves
 * unreferenced goes to the catalog's pending-deletion list instead of being deleted. A file any branch or tag still
 * reaches stays, in expiry and in the orphan scan that follows it.
 */
@Tag("heavy")
class SnapshotExpiryTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }
    private final FakeClock clock = new FakeClock();
    private RESTCatalog writer;
    private Worker worker;

    @BeforeEach
    void start() {
        writer = catalog.writer();
        worker = catalog.worker(clock, Compactor.NONE);
    }

    @AfterEach
    void stop() throws Exception {
        worker.close();
        writer.close();
    }

    @Test
    void expiryQueuesOnlyUnreachableFiles() {
        TableIdentifier id = TestCatalog.table(writer, "expiring");
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        Snapshot first = table.currentSnapshot();
        DataFile b = TestCatalog.dataFile(table, "b");
        table.newOverwrite().deleteFile(a).addFile(b).commit();
        Snapshot second = table.currentSnapshot();
        table.newAppend().appendFile(TestCatalog.dataFile(table, "c")).commit();
        Snapshot third = table.currentSnapshot();
        catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                "snapshot-expiry.min-snapshots-to-keep", "1"));
        String object = table.uuid().toString();

        worker.runOnce();
        assertThat(writer.loadTable(id).snapshots()).as("every snapshot is younger than 5 days").hasSize(3);
        assertThat(catalog.pending(object)).isEmpty();

        catalog.advance(Duration.ofDays(6), clock);
        worker.runOnce();

        Table expired = writer.loadTable(id);
        assertThat(expired.snapshots()).extracting(Snapshot::snapshotId).containsExactly(third.snapshotId());
        Set<String> unreachable = new HashSet<>(TestCatalog.reachable(table, first));
        unreachable.addAll(TestCatalog.reachable(table, second));
        unreachable.removeAll(TestCatalog.reachable(table, third));
        assertThat(unreachable).contains(a.location(), first.manifestListLocation(), second.manifestListLocation())
                .doesNotContain(b.location());
        List<String> pending = catalog.pending(object);
        assertThat(pending).containsExactlyInAnyOrderElementsOf(unreachable);
        assertThat(pending).as("pending, not deleted").allMatch(catalog::exists);
    }

    @Test
    void branchAndTagFilesSurvive() {
        TableIdentifier id = TestCatalog.table(writer, "branched");
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        Snapshot branched = table.currentSnapshot();
        table.manageSnapshots().createBranch("audit", branched.snapshotId()).commit();
        DataFile b = TestCatalog.dataFile(table, "b");
        table.newOverwrite().deleteFile(a).addFile(b).commit();
        Snapshot tagged = table.currentSnapshot();
        table.manageSnapshots().createTag("v1", tagged.snapshotId()).commit();
        DataFile c = TestCatalog.dataFile(table, "c");
        table.newOverwrite().deleteFile(b).addFile(c).commit();
        Snapshot expiring = table.currentSnapshot();
        DataFile d = TestCatalog.dataFile(table, "d");
        table.newOverwrite().deleteFile(c).addFile(d).commit();
        Snapshot main = table.currentSnapshot();
        String orphan = table.location() + "/data/orphan.parquet";
        catalog.put(orphan, "no snapshot names this file");
        catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                "snapshot-expiry.min-snapshots-to-keep", "1", "orphan-removal.enabled", "true",
                "orphan-removal.older-than", "P8D"));

        catalog.advance(Duration.ofDays(9), clock);
        worker.runOnce();

        Table expired = writer.loadTable(id);
        assertThat(expired.snapshots()).extracting(Snapshot::snapshotId)
                .containsExactlyInAnyOrder(branched.snapshotId(), tagged.snapshotId(), main.snapshotId());
        Set<String> kept = new HashSet<>(TestCatalog.metadataFiles(expired));
        for (Snapshot snapshot : List.of(branched, tagged, main)) {
            kept.addAll(TestCatalog.reachable(table, snapshot));
        }
        assertThat(kept).contains(a.location(), b.location(), d.location());
        List<String> pending = catalog.pending(table.uuid().toString());
        assertThat(pending).as("expired, then found again by the orphan scan, and pending once")
                .contains(c.location(), expiring.manifestListLocation(), orphan).doesNotHaveDuplicates();
        assertThat(pending).as("reachable from the branch, the tag or main").doesNotContainAnyElementsOf(kept);
        assertThat(kept).allMatch(catalog::exists);
    }
}
