package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.FailedJob;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.WorkerStorage;
import nuna.catalog.store.WorkerStorage.Listed;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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

    @Test
    @DisplayName("Requests: a request is kept, asking again while it waits changes nothing, the worker's start report"
            + " clears it, and a request after that replaces it; only an active table may be asked for")
    void aRequestIsKeptDeduplicatedAndClearedOnStart() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Instant base = now();
        MaintenanceRequest compaction = new MaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, "ana",
                base, Optional.empty());
        MaintenanceRequest orphans = new MaintenanceRequest(table.id(), MaintenanceRequest.Job.ORPHAN_SCAN, "ben",
                base.plusSeconds(1), Optional.empty());
        assertThat(store.listMaintenanceRequests(table.id())).as("nothing asked yet").isEmpty();

        assertApplied(store.requestMaintenance(compaction, change()));
        assertApplied(store.requestMaintenance(orphans, change()));
        assertThat(store.requestMaintenance(new MaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION,
                "carl", base.plusSeconds(5), Optional.empty()), change())).as("one waits already")
                .isEqualTo(WriteResult.UNCHANGED);

        assertThat(store.listMaintenanceRequests(table.id())).containsExactly(compaction, orphans);
        assertThat(waiting(namespace)).containsExactly(compaction, orphans);
        assertThat(store.startMaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION,
                base.minusSeconds(1), base.plusSeconds(60))).as("not the request waiting")
                .isEqualTo(WriteResult.UNCHANGED);

        assertApplied(store.startMaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, base,
                base.plusSeconds(60)));

        MaintenanceRequest started = new MaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, "ana", base,
                Optional.of(base.plusSeconds(60)));
        assertThat(store.listMaintenanceRequests(table.id())).containsExactly(started, orphans);
        assertThat(waiting(namespace)).as("started: off the work list").containsExactly(orphans);
        assertThat(store.startMaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, base,
                base.plusSeconds(90))).as("its start, reported again").isEqualTo(WriteResult.UNCHANGED);
        MaintenanceRequest again = new MaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, "dee",
                base.plusSeconds(120), Optional.empty());
        assertApplied(store.requestMaintenance(again, change()));
        assertThat(store.listMaintenanceRequests(table.id())).containsExactly(again, orphans);

        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        CatalogObject dropped = newTable(namespace, "dropped");
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));
        for (UUID id : List.of(view.id(), dropped.id(), UUID.randomUUID())) {
            assertThat(store.requestMaintenance(new MaintenanceRequest(id, MaintenanceRequest.Job.COMPACTION, "ana",
                    base, Optional.empty()), change())).as("a request for %s", id).isEqualTo(MISSING);
            assertThat(store.listMaintenanceRequests(id)).isEmpty();
        }
    }

    @Test
    @DisplayName("Requests: the waiting are listed by the time asked, then table, then job, a page at a time; one"
            + " started or of a dropped table is left out")
    void theWaitingAreListedInTheOrderAsked() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject a = newTable(namespace, "a");
        CatalogObject b = newTable(namespace, "b");
        CatalogObject c = newTable(namespace, "c");
        CatalogObject dropped = newTable(namespace, "dropped");
        Instant base = now();
        MaintenanceRequest late = request(a, MaintenanceRequest.Job.COMPACTION, base.plusSeconds(30));
        MaintenanceRequest first = request(b, MaintenanceRequest.Job.ORPHAN_SCAN, base);
        List<MaintenanceRequest> tied = List.of(request(a, MaintenanceRequest.Job.ORPHAN_SCAN, base.plusSeconds(10)),
                request(c, MaintenanceRequest.Job.COMPACTION, base.plusSeconds(10)),
                request(c, MaintenanceRequest.Job.ORPHAN_SCAN, base.plusSeconds(10)));
        MaintenanceRequest started = request(b, MaintenanceRequest.Job.COMPACTION, base.plusSeconds(5));
        MaintenanceRequest gone = request(dropped, MaintenanceRequest.Job.COMPACTION, base.plusSeconds(1));
        for (MaintenanceRequest request : List.of(late, first, tied.get(2), tied.get(0), tied.get(1), started, gone)) {
            assertApplied(store.requestMaintenance(request, change()));
        }
        assertApplied(store.startMaintenanceRequest(b.id(), MaintenanceRequest.Job.COMPACTION, base.plusSeconds(5),
                base.plusSeconds(6)));
        assertApplied(store.dropObject(dropped.id(), now().plusSeconds(3600), change()));
        List<MaintenanceRequest> byTableThenJob = tied.stream().sorted(Comparator
                .comparing(MaintenanceRequest::objectId, UNSIGNED).thenComparing(MaintenanceRequest::job)).toList();

        List<MaintenanceRequest> listed = waiting(namespace);

        assertThat(listed).containsExactly(first, byTableThenJob.get(0), byTableThenJob.get(1), byTableThenJob.get(2),
                late);
    }

    @Test
    @DisplayName("Requests: a purge's completion deletes the table's requests")
    void aPurgeDeletesTheRequests() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        assertApplied(store.requestMaintenance(request(table, MaintenanceRequest.Job.COMPACTION, now()), change()));
        assertApplied(store.requestMaintenance(request(table, MaintenanceRequest.Job.ORPHAN_SCAN, now()), change()));
        assertApplied(store.dropObject(table.id(), now(), change()));
        assertApplied(store.claimPurge(table.id(), "worker", change()));
        assertThat(store.listMaintenanceRequests(table.id())).as("kept while the table is purged").hasSize(2);

        assertApplied(store.completePurge(table.id(), "worker", change()));

        assertThat(store.listMaintenanceRequests(table.id())).isEmpty();
    }

    private static MaintenanceRequest request(CatalogObject table, MaintenanceRequest.Job job, Instant at) {
        return new MaintenanceRequest(table.id(), job, "ana", at, Optional.empty());
    }

    /** The waiting requests of the tables in {@code namespace}, read in pages of 2. */
    private List<MaintenanceRequest> waiting(NamespaceRecord namespace) {
        return allPages(2, store::listWaitingRequests).stream().filter(request -> store.readObject(request.objectId())
                .filter(table -> table.namespaceId().equals(namespace.id())).isPresent()).toList();
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
                Optional.of(outcome), failures, error, scan.groups(), scan.storage());
    }

    @Test
    @DisplayName("Health: a scan keeps the worker's groups and what its storage identities reach, a refused listing as"
            + " refused, the end's deletes in place of the start's, and nothing for a worker that reports none")
    void aScanKeepsWhatTheWorkerReaches() {
        // An hour back, so this test's scans are never the last started that another test's assertions look for.
        Instant base = now().minusSeconds(3600);
        UUID id = UUID.randomUUID();
        SortedSet<String> groups = new TreeSet<>(Set.of("maintainers", "platform-workers"));
        WorkerStorage.Prefix lake = prefix("s3://lake/tables", Listed.OK, null, 0, 0, 0, null);
        WorkerStorage.Prefix ledger = prefix("s3://ledger-warehouse/tables", Listed.REFUSED, "AccessDenied", 0, 0, 0,
                null);
        WorkerStorage.Prefix gone = prefix("s3://gone/tables", Listed.FAILED, "SdkClientException", 0, 0, 0, null);
        MaintenanceScan started = new MaintenanceScan(id, "svc-maintenance", base, Optional.empty(), Optional.empty(),
                Map.of(), Optional.empty(), Optional.of(groups), Optional.of(List.of(
                        new WorkerStorage("s3", Optional.of("https://silo.example:9000"), List.of(lake, ledger)),
                        new WorkerStorage("s3", Optional.empty(), List.of(gone)))));

        store.recordScan(started, 50);

        assertThat(kept(id)).isEqualTo(started);
        assertThat(kept(id).covering("s3a://ledger-warehouse/tables/ns/t/data/f.parquet")).get()
                .satisfies(covering -> {
                    assertThat(covering.store().endpoint()).contains("https://silo.example:9000");
                    assertThat(covering.prefix().listed()).as("a refused listing is kept refused")
                            .isEqualTo(Listed.REFUSED);
                    assertThat(covering.prefix().listCode()).contains("AccessDenied");
                });
        assertThat(kept(id).covering("s3://ledger-warehouse/other")).isEmpty();

        MaintenanceScan ended = new MaintenanceScan(id, "svc-maintenance", base, Optional.of(base.plusSeconds(1)),
                Optional.of(MaintenanceScan.Outcome.SUCCEEDED), Map.of("deletion", 1), Optional.empty(),
                Optional.of(groups), Optional.of(List.of(
                        new WorkerStorage("s3", Optional.of("https://silo.example:9000"), List.of(
                                prefix("s3://lake/tables", Listed.OK, null, 12, 0, 0, null),
                                prefix("s3://ledger-warehouse/tables", Listed.REFUSED, "AccessDenied", 0, 3, 1,
                                        "AccessDenied"))),
                        new WorkerStorage("s3", Optional.empty(), List.of(gone)))));
        store.recordScan(ended, 50);
        assertThat(kept(id)).as("the end's report in place of the start's").isEqualTo(ended);
        store.recordScan(started, 50);
        assertThat(kept(id)).as("an ended scan keeps its end").isEqualTo(ended);

        UUID earlier = UUID.randomUUID();
        store.recordScan(MaintenanceScan.started(earlier, "svc-maintenance", base.plusSeconds(2)), 50);
        assertThat(kept(earlier).groups()).as("kept before the catalog recorded groups").isEmpty();
        assertThat(kept(earlier).storage()).as("reported by a worker that reports no storage").isEmpty();
        UUID none = UUID.randomUUID();
        store.recordScan(new MaintenanceScan(none, "svc-maintenance", base.plusSeconds(3), Optional.empty(),
                Optional.empty(), Map.of(), Optional.empty(), Optional.of(new TreeSet<>()), Optional.of(List.of())), 50);
        assertThat(kept(none).groups()).contains(new TreeSet<>());
        assertThat(kept(none).storage()).contains(List.of());
    }

    @Test
    @DisplayName("Health: a scan keeps the failed jobs it names, each with its table and why, and one that names none"
            + " keeps none")
    void aScanKeepsItsFailedJobs() {
        Instant base = now().minusSeconds(3500);
        UUID id = UUID.randomUUID();
        List<FailedJob> jobs = List.of(
                new FailedJob("table-load", "brokerage", List.of("party"), "beneficial_owners", UUID.randomUUID(),
                        "closed", "Forbidden: table beneficial_owners is in a regulated area and has no"
                        + " classification; it is closed until it is classified"),
                new FailedJob("compaction", "lake", List.of("a", "b.c"), "t", UUID.randomUUID(), "error",
                        "the cluster refused the Job"));
        store.recordScan(MaintenanceScan.started(id, "svc-maintenance", base), 50);
        assertThat(kept(id).failedJobs()).as("a scan just started names none").isEmpty();

        MaintenanceScan ended = new MaintenanceScan(id, "svc-maintenance", base, Optional.of(base.plusSeconds(1)),
                Optional.of(MaintenanceScan.Outcome.SUCCEEDED), Map.of("table-load", 1, "compaction", 1),
                Optional.empty(), Optional.of(new TreeSet<>()), Optional.of(List.of()), jobs);
        store.recordScan(ended, 50);

        assertThat(kept(id)).isEqualTo(ended);
        assertThat(kept(id).failedJobs().get(1).namespace()).containsExactly("a", "b.c");
    }

    private MaintenanceScan kept(UUID id) {
        return store.listScans().stream().filter(scan -> scan.id().equals(id)).findFirst().orElseThrow();
    }

    private static WorkerStorage.Prefix prefix(String location, Listed listed, String listCode, long deleted,
                                               long refused, long failed, String deleteCode) {
        return new WorkerStorage.Prefix(location, listed, Optional.ofNullable(listCode), deleted, refused, failed,
                Optional.ofNullable(deleteCode));
    }
}
