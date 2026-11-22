package nuna.maintenance;

import static nuna.maintenance.CompactionCluster.await;
import static nuna.maintenance.CompactionCluster.kubectl;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nuna.catalog.tests.s3.PrivateCa;
import nuna.catalog.tests.s3.TestS3;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compaction Job against a store behind a private CA, end to end (backlog 89; D21, amended 2026-11-20 for backlog
 * 88), in Docker Desktop's cluster as {@link KubernetesCompactionTest} has it ({@link CompactionCluster}). The store is
 * Silo serving TLS with a certificate a private CA signed ({@link TestS3#siloBehindTls}, {@link PrivateCa}); the
 * catalog's storage profile names that CA's bundle, and so does the worker's ({@link TestCatalog#onStore}), and the
 * chart's Role is the one it renders for a profile with a {@code ca-bundle}, which lets the worker create ConfigMaps
 * in the Jobs' namespace. The worker finds a table of six small Avro files needs compaction and launches the repo's
 * Spark image as a Job; the Job gets the bundle in a ConfigMap of its own, builds a trust store of the image's JDK CAs
 * and the bundle, and its Spark compacts the table into one data file in a {@code replace} snapshot, committed through
 * the catalog.
 *
 * <p>First, as a control, the same Job without the bundle (the worker's Jobs as a worker whose storage profiles name
 * none launches them) fails to reach the store and leaves the table as it was: the store is behind a CA that neither
 * the image nor its JDK trusts, so the second Job, the same but for the bundle, succeeding against the same address
 * is the bundle's doing. The control's log cannot show the certificate refused: Docker Desktop gives pods an IPv4 and
 * an IPv6 address for {@code host.docker.internal}, and pods have no route for IPv6 (checked 2026-11-21 with
 * {@code nslookup} and {@code ip -6 route} in a pod). The S3 client's HttpClient (5.6.4 in iceberg-aws-bundle 1.12.0)
 * handshakes TLS inside its loop over a host's addresses, so a refused certificate on the IPv4 address sends it on to
 * the IPv6 one, and it reports only that last error: {@code Network is unreachable}, never the PKIX error. So the
 * control asserts that every attempt at the S3 request failed, which with the IPv4 address reachable (the second Job
 * reaches it) is the handshake failing. The bite
 * {@code bites/M6/job-storage-ca-left-out} leaves the bundle out of every Job, and the worker's Job then fails as the
 * control does, on this test's assertion of its outcome.
 *
 * <p>The Jobs' pods reach the catalog and the store as {@code host.docker.internal} ({@link TestCatalog#twinForPods}),
 * and the store's certificate names that host; the worker and the test, on this machine, reach both as
 * {@code localhost}. Overnight: it builds an image and runs two Spark Jobs, and the cluster has no room for it beside
 * the nuna-demo stack, which the overnight run takes down first.
 */
@Tag("overnight")
class PrivateCaCompactionTest {

    /** Where the JDK says it cannot trust a certificate; a pod reports it only when the store has no other address. */
    private static final String UNTRUSTED = "PKIX path building failed";
    /** How the AWS SDK reports an S3 request whose every attempt failed, whatever the last address's error. */
    private static final String UNREACHED = "Unable to execute HTTP request";

    @TempDir
    Path serviceAccount;

    @Test
    void aJobTrustsItsStoresPrivateCaAndCompacts() throws Exception {
        PrivateCa ca = PrivateCa.shared();
        TestS3 store = TestS3.siloBehindTls(ca);
        TestCatalog catalog = TestCatalog.onStore(store);
        URI forPods = catalog.twinForPods();
        // A profile with a ca-bundle, so the chart's Role grants create on ConfigMaps; the path is where a deployed
        // worker finds the bundle (storageCaConfigMap), which this one, in the test JVM, reads from the CA's folder.
        try (CompactionCluster cluster = CompactionCluster.start(serviceAccount, forPods, catalog.bucket,
                "--set", "config.nuna.maintenance.storage.test.ca-bundle=/etc/nuna/storage-ca/ca.pem")) {
            String jobs = cluster.jobs;
            Map<String, WorkerSettings.StorageProfile> storage = catalog.storageProfiles();
            assertThat(storage.get("test").caBundle()).as("the worker's profile names the bundle").isEqualTo(ca.bundle);
            FakeClock clock = new FakeClock();
            KubernetesJobs compactor = new KubernetesJobs(cluster.settings, catalog.uri, catalog.workerTokens(),
                    storage, clock);
            CleanupWindow window = TestCatalog.allDay(clock);
            CleanupWindow.Night night = window.night(clock.instant()).orElseThrow();

            try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, compactor,
                    catalog.workerStorage(), catalog.workerTokens(), new SimpleMeterRegistry(), window, 2)) {
                TableIdentifier id = TestCatalog.table(writer, "behind_private_ca");
                Table table = writer.loadTable(id);
                for (int i = 0; i < 6; i++) {
                    table.newAppend().appendFile(TestCatalog.avro(table, i)).commit();
                }
                long written = writer.loadTable(id).currentSnapshot().snapshotId();
                catalog.policy(id, Map.of("compaction.enabled", "true"));
                Management.TableWork work = new Management.TableWork(TestCatalog.CATALOG,
                        UUID.fromString(table.uuid().toString()), List.of(id.namespace().levels()), id.name(),
                        table.location(), Map.of("compaction.enabled", "true"));

                // The control: the Job without the bundle, on another night, so under another name.
                KubernetesJobs untrusting = new KubernetesJobs(cluster.settings, catalog.uri, catalog.workerTokens(),
                        Map.of(), clock);
                CleanupWindow.Night lastNight = new CleanupWindow.Night(night.date().minusDays(1), night.opens(),
                        night.closes());
                CompactionNeed need = new CompactionNeed(6, 6, 0, 0, 5, 0.3, 536870912);
                assertThat(untrusting.launch(work, need, lastNight)).isEqualTo(Compactor.Launch.STARTED);
                String control = KubernetesJobs.jobName(work, lastNight);
                String controlStatus = await(jobs, control);
                String controlLog = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + control);
                assertThat(controlStatus).as("the Job without the bundle; its log:%n%s", controlLog).isEqualTo(",1");
                assertThat(controlLog).as("the S3 request failed: the store's certificate refused on its IPv4"
                        + " address, the IPv6 one unroutable").contains(UNREACHED).doesNotContain("COMPACTED");
                assertThat(writer.loadTable(id).currentSnapshot().snapshotId()).as("not compacted")
                        .isEqualTo(written);

                // The worker's own Job, with the bundle.
                assertThat(worker.runOnce().compactionsStarted).isEqualTo(1);
                String job = KubernetesJobs.jobName(work, night);
                String status = await(jobs, job);
                String log = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + job);
                assertThat(status).as("the Job with the store's CA bundle; its log:%n%s", log).isEqualTo("1,");
                assertThat(log).contains("COMPACTED").doesNotContain(UNTRUSTED);
                assertThat(compactor.state(work, night)).isEqualTo(Compactor.State.SUCCEEDED);
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "configmap", "-n", jobs, "-l",
                        "app.kubernetes.io/name=nuna-compaction", "-o",
                        "jsonpath={.items[*].metadata.ownerReferences[0].kind}/"
                                + "{.items[*].metadata.ownerReferences[0].name}"))
                        .as("the bundle's copy, the worker's Job's own").isEqualTo("Job/" + job);

                Table compacted = writer.loadTable(id);
                assertThat(compacted.currentSnapshot().operation()).isEqualTo("replace");
                assertThat(compacted.currentSnapshot().summary()).containsEntry("total-data-files", "1")
                        .containsEntry("total-records", "60");
            }
        }
    }
}
