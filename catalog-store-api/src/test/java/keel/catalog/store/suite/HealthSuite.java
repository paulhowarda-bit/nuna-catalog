package keel.catalog.store.suite;

import static keel.catalog.store.suite.Changes.change;
import static keel.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CatalogObject;
import keel.catalog.store.CompactionRun;
import keel.catalog.store.MaintenanceScan;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.TableAssessment;
import keel.catalog.store.TableHealth;
import keel.catalog.store.WriteResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the maintenance worker reports of its own work (D38): each table's latest assessment and last compaction, and
 * its recent scans. The listing of the neediest is shared by every test on the backend, so each test looks only at
 * its own tables.
 */
public abstract class HealthSuite extends GroupSuite {

    private static final WriteResult.Missing MISSING = new WriteResult.Missing(WriteResult.Subject.OBJECT);
    /** Ids as the stores order them: an unsigned 128-bit number. */
    private static final Comparator<UUID> UNSIGNED = Comparator
            .<UUID>comparingLong(id -> id.getMostSignificantBits() ^ Long.MIN_VALUE)
            .thenComparingLong(id -> id.getLeastSignificantBits() ^ Long.MIN_VALUE);

    @Test
    @DisplayName("Health: an active table's assessment is kept, the latest wins, and one reported late changes nothing")
    void theLatestAssessmentIsKept() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Instant base = now();
        TableAssessment first = new TableAssessment(base, 120, 90, 3, 2, 0, Set.of("small-files", "deletes"), 2.5);
        TableAssessment second = new TableAssessment(base.plusSeconds(60), 30, 0, 0, 0, 0, Set.of(), 0);
        assertThat(store.readTableHealth(table.id())).as("nothing reported yet").isEmpty();

        assertApplied(store.recordAssessment(table.id(), first));

        assertThat(store.readTableHealth(table.id())).contains(new TableHealth(table, Optional.of(first), Optional.empty()));
        assertApplied(store.recordAssessment(table.id(), second));
        assertThat(store.readTableHealth(table.id()).orElseThrow().assessment()).contains(second);
        assertThat(store.recordAssessment(table.id(), first)).as("an older assessment, reported late")
                .isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.readTableHealth(table.id()).orElseThrow().assessment()).contains(second);
        assertApplied(store.recordAssessment(table.id(), second));
        assertThat(store.readTableHealth(table.id()).orElseThrow().assessment()).as("the same one again")
                .contains(second);
    }

    @Test
    @DisplayName("Health: a report of a view, a dropped table or an unknown id is Missing and keeps nothing")
    void onlyActiveTablesAreReported() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        CatalogObject dropped = newTable(namespace, "dropped");
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));
        TableAssessment assessment = new TableAssessment(now(), 10, 10, 0, 0, 0, Set.of("small-files"), 1);
        CompactionRun compaction = new CompactionRun(now(), Optional.empty(), CompactionRun.Outcome.RUNNING);

        for (UUID id : List.of(view.id(), dropped.id(), UUID.randomUUID())) {
            assertThat(store.recordAssessment(id, assessment)).as("assessment of %s", id).isEqualTo(MISSING);
            assertThat(store.recordCompaction(id, compaction)).as("compaction of %s", id).isEqualTo(MISSING);
            assertThat(store.readTableHealth(id)).as("health of %s", id).isEmpty();
        }
    }

    @Test
    @DisplayName("Health: the last compaction is kept beside the assessment; an earlier one changes nothing, nor does"
            + " a running report once it has ended")
    void theLastCompactionIsKept() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject unassessed = newTable(namespace, "u");
        Instant base = now();
        TableAssessment assessment = new TableAssessment(base, 50, 40, 0, 0, 4, Set.of("equality-deletes"), 4);
        CompactionRun running = new CompactionRun(base.plusSeconds(10), Optional.empty(), CompactionRun.Outcome.RUNNING);
        CompactionRun succeeded = new CompactionRun(base.plusSeconds(10), Optional.of(base.plusSeconds(70)),
                CompactionRun.Outcome.SUCCEEDED);
        CompactionRun earlier = new CompactionRun(base.minusSeconds(600), Optional.of(base.minusSeconds(500)),
                CompactionRun.Outcome.FAILED);
        CompactionRun cutOff = new CompactionRun(base.plusSeconds(100), Optional.of(base.plusSeconds(200)),
                CompactionRun.Outcome.CUT_OFF);
        assertApplied(store.recordAssessment(table.id(), assessment));

        assertApplied(store.recordCompaction(table.id(), running));

        assertThat(store.readTableHealth(table.id()))
                .contains(new TableHealth(table, Optional.of(assessment), Optional.of(running)));
        assertApplied(store.recordCompaction(table.id(), succeeded));
        assertThat(store.recordCompaction(table.id(), running)).as("its start, reported again once it ended")
                .isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.recordCompaction(table.id(), earlier)).as("an earlier compaction")
                .isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.readTableHealth(table.id()))
                .contains(new TableHealth(table, Optional.of(assessment), Optional.of(succeeded)));
        assertApplied(store.recordCompaction(table.id(), cutOff));
        TableAssessment later = new TableAssessment(base.plusSeconds(300), 12, 0, 0, 0, 0, Set.of(), 0);
        assertApplied(store.recordAssessment(table.id(), later));
        assertThat(store.readTableHealth(table.id()))
                .contains(new TableHealth(table, Optional.of(later), Optional.of(cutOff)));

        assertApplied(store.recordCompaction(unassessed.id(), running));
        assertThat(store.readTableHealth(unassessed.id()))
                .contains(new TableHealth(unassessed, Optional.empty(), Optional.of(running)));
        assertThat(neediest()).as("a table never assessed is not among the neediest").doesNotContain(unassessed.id());
    }

    @Test
    @DisplayName("Health: the neediest active tables come first: equality deletes, then the highest score, then by id;"
            + " a table needing nothing or dropped is left out, and pages resume after the last")
    void theNeediestComeFirst() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        Instant at = now();
        CatalogObject equality = assessed(namespace, "equality", new TableAssessment(at, 10, 0, 0, 0, 2,
                Set.of("equality-deletes"), 1.5));
        CatalogObject high = assessed(namespace, "high", new TableAssessment(at, 900, 800, 0, 0, 0,
                Set.of("small-files"), 9.25));
        CatalogObject middle = assessed(namespace, "middle", new TableAssessment(at, 300, 200, 40, 7, 0,
                Set.of("small-files", "deletes"), 3.0625));
        List<CatalogObject> tied = List.of(
                assessed(namespace, "tied-a", new TableAssessment(at, 100, 80, 0, 0, 0, Set.of("small-files"), 2)),
                assessed(namespace, "tied-b", new TableAssessment(at, 100, 80, 0, 0, 0, Set.of("small-files"), 2)));
        CatalogObject low = assessed(namespace, "low", new TableAssessment(at, 60, 50, 0, 0, 0,
                Set.of("small-files"), 1));
        CatalogObject fine = assessed(namespace, "fine", new TableAssessment(at, 5, 0, 0, 0, 0, Set.of(), 0.25));
        CatalogObject dropped = assessed(namespace, "dropped", new TableAssessment(at, 9000, 9000, 0, 0, 9,
                Set.of("small-files", "equality-deletes"), 500));
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));
        List<UUID> byId = tied.stream().map(CatalogObject::id).sorted(UNSIGNED).toList();

        assertThat(neediest(namespace)).as("in pages of 2").containsExactly(equality.id(), high.id(), middle.id(), byId.get(0),
                byId.get(1), low.id());

        assertThat(store.readTableHealth(dropped.id()).orElseThrow().table().state()).as("a dropped table's health")
                .isEqualTo(CatalogObject.State.DROPPED);
        assertThat(store.readTableHealth(fine.id()).orElseThrow().assessment().orElseThrow().needsCompaction()).isFalse();
        assertApplied(store.recordAssessment(high.id(), new TableAssessment(at.plusSeconds(60), 100, 0, 0, 0, 0,
                Set.of(), 0)));
        assertThat(neediest(namespace)).as("once compacted, a table needs nothing").containsExactly(equality.id(), middle.id(),
                byId.get(0), byId.get(1), low.id());
    }

    @Test
    @DisplayName("Health: completing a purge deletes the table's health")
    void aPurgeDeletesTheHealth() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = assessed(namespace, "t", new TableAssessment(now(), 10, 10, 0, 0, 0,
                Set.of("small-files"), 1));
        assertApplied(store.recordCompaction(table.id(), new CompactionRun(now(), Optional.empty(),
                CompactionRun.Outcome.RUNNING)));
        assertApplied(store.dropObject(table.id(), now(), change()));
        assertApplied(store.claimPurge(table.id(), "worker", change()));
        assertThat(store.readTableHealth(table.id())).as("kept while the table is purged").isPresent();

        assertApplied(store.completePurge(table.id(), "worker", change()));

        assertThat(store.readTableHealth(table.id())).isEmpty();
    }

    @Test
    @DisplayName("Health: a scan's start then its end are kept, an ended scan keeps its end, and every scan but the"
            + " last few and the last success is deleted")
    void recentScansAndTheLastSuccessAreKept() {
        Instant base = now();
        MaintenanceScan started = MaintenanceScan.started(UUID.randomUUID(), "svc-maintenance", base);
        MaintenanceScan succeeded = ended(started, base.plusSeconds(1), MaintenanceScan.Outcome.SUCCEEDED,
                Map.of("purge", 2, "work-list", 1), Optional.empty());

        store.recordScan(started, 3);

        assertThat(store.listScans()).first().isEqualTo(started);
        store.recordScan(succeeded, 3);
        store.recordScan(started, 3);
        assertThat(store.listScans()).as("its start, reported again once it ended").first().isEqualTo(succeeded);
        MaintenanceScan failed2 = failed(base.plusSeconds(2));
        MaintenanceScan failed3 = failed(base.plusSeconds(3));
        MaintenanceScan failed4 = failed(base.plusSeconds(4));
        MaintenanceScan running = MaintenanceScan.started(UUID.randomUUID(), "svc-maintenance", base.plusSeconds(5));
        for (MaintenanceScan scan : List.of(failed2, failed3, failed4, running)) {
            store.recordScan(scan, 3);
        }
        assertThat(store.listScans()).as("the last three, and the last success").containsExactly(running, failed4,
                failed3, succeeded);
        MaintenanceScan later = ended(MaintenanceScan.started(UUID.randomUUID(), "svc-maintenance", base.plusSeconds(6)),
                base.plusSeconds(7), MaintenanceScan.Outcome.SUCCEEDED, Map.of(), Optional.empty());
        store.recordScan(later, 3);
        assertThat(store.listScans()).containsExactly(later, running, failed4);
    }

    private CatalogObject assessed(NamespaceRecord namespace, String name, TableAssessment assessment) {
        CatalogObject table = newTable(namespace, name);
        assertApplied(store.recordAssessment(table.id(), assessment));
        return table;
    }

    /** Ids of every table listed among the neediest, in pages of 2. */
    private List<UUID> neediest() {
        return allPages(2, store::listNeediestTables).stream().map(health -> health.table().id()).toList();
    }

    /** Ids of the tables in {@code namespace} listed among the neediest, in pages of 2. */
    private List<UUID> neediest(NamespaceRecord namespace) {
        return allPages(2, store::listNeediestTables).stream().filter(health -> health.table().namespaceId()
                .equals(namespace.id())).map(health -> health.table().id()).toList();
    }

    private static MaintenanceScan failed(Instant startedAt) {
        return ended(MaintenanceScan.started(UUID.randomUUID(), "svc-maintenance", startedAt), startedAt.plusMillis(500),
                MaintenanceScan.Outcome.FAILED, Map.of("work-list", 1), Optional.of("the work list could not be read"));
    }

    private static MaintenanceScan ended(MaintenanceScan scan, Instant endedAt, MaintenanceScan.Outcome outcome,
                                         Map<String, Integer> failures, Optional<String> error) {
        return new MaintenanceScan(scan.id(), scan.worker(), scan.startedAt(), Optional.of(endedAt),
                Optional.of(outcome), failures, error);
    }
}
