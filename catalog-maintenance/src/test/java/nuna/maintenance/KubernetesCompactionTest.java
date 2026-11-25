package nuna.maintenance;

import static nuna.maintenance.CompactionCluster.await;
import static nuna.maintenance.CompactionCluster.kubectl;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compaction as a Kubernetes Job (D30 (3), amended 2026-11-15), in Docker Desktop's cluster: the worker, signed in to
 * the API server as the chart's service account with only the chart's Role in the Jobs' namespace, finds a table of
 * six small Avro files needs compaction in its cleanup window and launches the repo's Spark image as a Job there,
 * running as the chart's Job service account, that runs rewrite_data_files on it, signed in to the catalog as the
 * worker, with a deadline no later than the window's end. The table ends with one data file in a {@code replace}
 * snapshot; the six old files are left for expiry. Another scan that night launches nothing, and the next night finds
 * the table needs none. A Job naming the table with an id it no longer has (another table took the name after the
 * scan) stops without compacting it. A Job whose window closes before it can finish is stopped by Kubernetes at the
 * window's end, and the worker reads it as cut off.
 *
 * <p>Runs in two namespaces of its own, the worker's and the Jobs', created and deleted here
 * ({@link CompactionCluster}); it never touches another (nuna-demo least of all). The Jobs reach the catalog and the S3
 * store as {@code host.docker.internal} ({@link TestCatalog#forPods}, the settings' {@code catalogUri}); the worker and
 * the test, on this machine, as {@code localhost}.
 * Needs Docker, kubectl and Helm on the PATH and the kubectl context {@value CompactionCluster#CONTEXT}. Heavy: it
 * builds an image and waits for a Spark job.
 */
@Tag("heavy")
class KubernetesCompactionTest {

    private final TestCatalog catalog = TestCatalog.get();
    private final URI forPods = TestCatalog.forPods();

    @TempDir
    Path serviceAccount;

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void compactionRunsAsAJobPerTable() throws Exception {
        try (CompactionCluster cluster = CompactionCluster.start(serviceAccount, forPods)) {
            String jobs = cluster.jobs;
            WorkerSettings.Compaction settings = cluster.settings;
            FakeClock clock = new FakeClock();
            KubernetesJobs compactor = new KubernetesJobs(settings, catalog.uri, catalog.workerTokens(), Map.of(),
                    location -> java.util.Optional.empty(), clock);
            CleanupWindow window = TestCatalog.allDay(clock);
            CleanupWindow.Night night = window.night(clock.instant()).orElseThrow();

            try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, compactor,
                    catalog.workerStorage(), catalog.workerTokens(), new SimpleMeterRegistry(), window,
                    2)) {
                TableIdentifier id = TestCatalog.table(writer, "compacted");
                Table table = writer.loadTable(id);
                List<DataFile> small = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    DataFile file = TestCatalog.avro(table, i);
                    table.newAppend().appendFile(file).commit();
                    small.add(file);
                }
                catalog.policy(id, Map.of("compaction.enabled", "true"));
                Management.TableWork work = new Management.TableWork(TestCatalog.CATALOG,
                        UUID.fromString(table.uuid().toString()), List.of(id.namespace().levels()), id.name(),
                        table.location(), Map.of("compaction.enabled", "true"));

                Instant launched = clock.instant();
                assertThat(worker.runOnce().compactionsStarted).isEqualTo(1);
                String job = KubernetesJobs.jobName(work, night);
                String status = await(jobs, job);
                String log = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + job);
                assertThat(status).as("the Job's status; its log:%n%s", log).isEqualTo("1,");
                assertThat(log).contains("COMPACTED");
                assertThat(compactor.state(work, night)).isEqualTo(Compactor.State.SUCCEEDED);
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "job", job, "-n", jobs, "-o",
                        "jsonpath={.spec.template.spec.serviceAccountName}")).isEqualTo("nuna-compaction");
                assertThat(Long.parseLong(kubectl(Duration.ofMinutes(1), null, "get", "job", job, "-n", jobs, "-o",
                        "jsonpath={.spec.activeDeadlineSeconds}").strip())).as("stopped at the window's end at the latest")
                        .isPositive().isLessThanOrEqualTo(Duration.between(launched, night.closes()).toSeconds() + 1);
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "secret", "-n", jobs, "-l",
                        "app.kubernetes.io/name=nuna-compaction", "-o", "jsonpath={.items[*].metadata.ownerReferences[0]"
                                + ".kind}/{.items[*].metadata.ownerReferences[0].name}")).isEqualTo("Job/" + job);

                Table compacted = writer.loadTable(id);
                assertThat(compacted.currentSnapshot().operation()).isEqualTo("replace");
                assertThat(compacted.currentSnapshot().summary()).containsEntry("total-data-files", "1")
                        .containsEntry("total-records", "60");
                assertThat(small).as("left for expiry").allMatch(file -> catalog.exists(file.location()));

                // The scan saw the table under another id: a table since dropped, the name now this one's.
                CompactionNeed need = new CompactionNeed(6, 6, 0, 0, 5, 0.3, 536870912);
                Management.TableWork staleWork = new Management.TableWork(TestCatalog.CATALOG, UUID.randomUUID(),
                        List.of(id.namespace().levels()), id.name(), table.location(),
                        Map.of("compaction.enabled", "true"));
                assertThat(compactor.launch(staleWork, need, night)).isEqualTo(Compactor.Launch.STARTED);
                String staleJob = KubernetesJobs.jobName(staleWork, night);
                String staleStatus = await(jobs, staleJob);
                String staleLog = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + staleJob);
                assertThat(staleStatus).as("the Job's status; its log:%n%s", staleLog).isEqualTo(",1");
                assertThat(staleLog).contains("TABLE CHANGED").doesNotContain("COMPACTED");
                assertThat(compactor.state(staleWork, night)).isEqualTo(Compactor.State.FAILED);
                assertThat(writer.loadTable(id).currentSnapshot().snapshotId()).as("not compacted again")
                        .isEqualTo(compacted.currentSnapshot().snapshotId());

                ScanReport again = worker.runOnce();
                assertThat(again.compactionsStarted).as("tonight's Job ran").isZero();
                assertThat(again.tablesAssessed).as("assessed once a night").isZero();
                catalog.advance(Duration.ofDays(1), clock);
                ScanReport tomorrow = worker.runOnce();
                assertThat(tomorrow.tablesAssessed).as("the next night").isPositive();
                assertThat(tomorrow.compactionsStarted).as("one data file: no need").isZero();

                // A window that closes before Spark can start: Kubernetes stops the Job at its end.
                Instant now = clock.instant();
                CleanupWindow.Night closing = new CleanupWindow.Night(night.date().plusDays(1), now,
                        now.plusSeconds(2));
                Management.TableWork cutWork = new Management.TableWork(TestCatalog.CATALOG,
                        UUID.fromString(table.uuid().toString()), List.of(id.namespace().levels()), id.name(),
                        table.location(), Map.of("compaction.enabled", "true"));
                assertThat(compactor.launch(cutWork, need, closing)).isEqualTo(Compactor.Launch.STARTED);
                String cutJob = KubernetesJobs.jobName(cutWork, closing);
                assertThat(await(jobs, cutJob)).as("failed").isEqualTo(",1");
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "job", cutJob, "-n", jobs, "-o",
                        // Single quotes: Java on Windows passes double quotes inside an argument unescaped, and
                        // the process receives them stripped.
                        "jsonpath={.status.conditions[?(@.type=='Failed')].reason}").strip())
                        .isEqualTo("DeadlineExceeded");
                assertThat(compactor.state(cutWork, closing)).isEqualTo(Compactor.State.CUT_OFF);
            }
        }
    }
}
