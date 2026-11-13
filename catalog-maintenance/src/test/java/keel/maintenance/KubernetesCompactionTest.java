package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compaction as a Kubernetes Job (D30 (3)), in Docker Desktop's cluster: the worker, signed in to the API server as
 * the chart's service account with only the chart's Role in the Jobs' namespace, launches the repo's Spark image as
 * a Job there, running as the chart's Job service account, that runs rewrite_data_files on a table of six small Avro
 * files, signed in to the catalog as the worker. The table ends with one data file in a {@code replace} snapshot; the
 * six old files are left for expiry. A second scan launches nothing while the Job is kept. A Job naming the table
 * with an id it no longer has (another table took the name after the scan) stops without compacting it.
 *
 * <p>Runs in two namespaces of its own, the worker's and the Jobs', created and deleted here; it never touches
 * another (keel-demo least of all). The catalog and the S3 store are reached from the pods as
 * {@code host.docker.internal} ({@link TestCatalog#forPods}).
 * Needs Docker, kubectl and Helm on the PATH and the kubectl context {@value #CONTEXT}. Heavy: it builds an image and
 * waits for a Spark job.
 */
@Tag("heavy")
class KubernetesCompactionTest {

    private static final String CONTEXT = "docker-desktop";
    private static final String IMAGE = "keel-engines/spark:4.1.4-iceberg-1.12.0";
    private static final String RELEASE = "keel-maintenance";

    private final TestCatalog catalog = TestCatalog.forPods();

    @TempDir
    Path serviceAccount;

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void compactionRunsAsAJobPerTable() throws Exception {
        Path repo = Path.of(System.getProperty("repo.root"));
        run(Duration.ofMinutes(20), null, "docker", "build", "-t", IMAGE,
                repo.resolve("catalog-tests-engines/images/spark").toString());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "keel-maintenance-test-" + suffix;
        String jobs = "keel-compaction-test-" + suffix;
        kubectl(Duration.ofMinutes(1), null, "create", "namespace", namespace);
        kubectl(Duration.ofMinutes(1), null, "create", "namespace", jobs);
        try {
            kubectl(Duration.ofMinutes(1), chart(repo, namespace, jobs, "templates/serviceaccount.yaml"), "apply",
                    "-n", namespace, "-f", "-");
            kubectl(Duration.ofMinutes(1), chart(repo, namespace, jobs, "templates/rbac.yaml"), "apply", "-f", "-");
            Files.writeString(serviceAccount.resolve("token"), kubectl(Duration.ofMinutes(1), null, "create", "token",
                    RELEASE, "-n", namespace, "--duration=1h"));
            Files.writeString(serviceAccount.resolve("namespace"), namespace);
            Files.write(serviceAccount.resolve("ca.crt"), Base64.getDecoder().decode(kubectl(Duration.ofMinutes(1),
                    null, "config", "view", "--raw", "--minify", "-o",
                    "jsonpath={.clusters[0].cluster.certificate-authority-data}").strip()));
            URI apiServer = URI.create(kubectl(Duration.ofMinutes(1), null, "config", "view", "--minify", "-o",
                    "jsonpath={.clusters[0].cluster.server}").strip());
            WorkerSettings.Compaction settings = new WorkerSettings.Compaction(IMAGE, "Never", jobs,
                    "keel-compaction", apiServer, serviceAccount, null, "2Gi", "1g", Duration.ofMinutes(20), 185,
                    Map.of());
            KubernetesJobs compactor = new KubernetesJobs(settings, Duration.ofHours(1), catalog.uri,
                    catalog.workerTokens());

            FakeClock clock = new FakeClock();
            try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, compactor)) {
                TableIdentifier id = TestCatalog.table(writer, "compacted");
                Table table = writer.loadTable(id);
                List<DataFile> small = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    DataFile file = avro(table, i);
                    table.newAppend().appendFile(file).commit();
                    small.add(file);
                }
                catalog.policy(id, Map.of("compaction.enabled", "true"));

                assertThat(worker.runOnce().compactionsStarted).isEqualTo(1);
                String job = "keel-compact-" + table.uuid();
                String status = await(jobs, job);
                String log = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + job);
                assertThat(status).as("the Job's status; its log:%n%s", log).isEqualTo("1,");
                assertThat(log).contains("COMPACTED");
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "job", job, "-n", jobs, "-o",
                        "jsonpath={.spec.template.spec.serviceAccountName}")).isEqualTo("keel-compaction");
                assertThat(kubectl(Duration.ofMinutes(1), null, "get", "secret", "-n", jobs, "-l",
                        "app.kubernetes.io/name=keel-compaction", "-o", "jsonpath={.items[*].metadata.ownerReferences[0]"
                                + ".kind}/{.items[*].metadata.ownerReferences[0].name}")).isEqualTo("Job/" + job);

                Table compacted = writer.loadTable(id);
                assertThat(compacted.currentSnapshot().operation()).isEqualTo("replace");
                assertThat(compacted.currentSnapshot().summary()).containsEntry("total-data-files", "1")
                        .containsEntry("total-records", "60");
                assertThat(small).as("left for expiry").allMatch(file -> catalog.exists(file.location()));

                // The scan saw the table under another id: a table since dropped, the name now this one's.
                UUID stale = UUID.randomUUID();
                assertThat(compactor.launch(new Management.TableWork(TestCatalog.CATALOG, stale,
                        List.of(id.namespace().levels()), id.name(), table.location(),
                        Map.of("compaction.enabled", "true")))).isEqualTo(Compactor.Launch.STARTED);
                String staleJob = "keel-compact-" + stale;
                String staleStatus = await(jobs, staleJob);
                String staleLog = kubectl(Duration.ofMinutes(1), null, "logs", "-n", jobs, "job/" + staleJob);
                assertThat(staleStatus).as("the Job's status; its log:%n%s", staleLog).isEqualTo(",1");
                assertThat(staleLog).contains("TABLE CHANGED").doesNotContain("COMPACTED");
                assertThat(writer.loadTable(id).currentSnapshot().snapshotId()).as("not compacted again")
                        .isEqualTo(compacted.currentSnapshot().snapshotId());

                assertThat(worker.runOnce().compactionsStarted).as("a day has not passed").isZero();
                clock.advance(Duration.ofDays(2));
                assertThat(worker.runOnce().compactionsStarted).as("the finished Job is kept for an hour")
                        .isZero();
            }
        } finally {
            kubectl(Duration.ofMinutes(5), null, "delete", "namespace", jobs, namespace, "--wait=true",
                    "--timeout=4m");
        }
    }

    /** One template of the chart as this test installs it: the worker in {@code namespace}, its Jobs in {@code jobs}. */
    private String chart(Path repo, String namespace, String jobs, String template) throws Exception {
        return run(Duration.ofMinutes(1), null, "helm", "template", RELEASE,
                repo.resolve("deploy/helm/keel-maintenance").toString(), "--namespace", namespace,
                "--show-only", template,
                "--set", "config.keel.maintenance.catalog-uri=" + catalog.uri,
                "--set", "config.keel.maintenance.storage.test.prefixes[0]=s3://" + catalog.bucket + "/tables",
                "--set", "config.keel.maintenance.compaction.image=" + IMAGE,
                "--set", "config.keel.maintenance.compaction.namespace=" + jobs);
    }

    /** Ten rows in an Avro data file of the table, written with the table's vended credentials. */
    private static DataFile avro(Table table, int index) throws IOException {
        DataWriter<Record> writer = Avro.writeData(table.io().newOutputFile(
                        table.locationProvider().newDataLocation("part-" + index + ".avro")))
                .schema(table.schema()).withSpec(table.spec())
                .createWriterFunc(org.apache.iceberg.data.avro.DataWriter::create).overwrite().build();
        try (writer) {
            for (long row = 0; row < 10; row++) {
                writer.write(GenericRecord.create(table.schema()).copy(Map.of("id", index * 10L + row,
                        "name", "row " + row)));
            }
        }
        return writer.toDataFile();
    }

    /** Waits for the Job to succeed or fail; returns {@code succeeded,failed}. */
    private String await(String namespace, String job) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(15));
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = kubectl(Duration.ofMinutes(1), null, "get", "job", job, "-n", namespace, "-o",
                    "jsonpath={.status.succeeded},{.status.failed}").strip();
            if (!status.equals(",")) {
                return status;
            }
            Thread.sleep(5_000);
        }
        return status;
    }

    private static String kubectl(Duration timeout, String stdin, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("kubectl", "--context", CONTEXT));
        command.addAll(List.of(arguments));
        return run(timeout, stdin, command.toArray(String[]::new));
    }

    /** Runs a command; returns its output, or fails with it when the command fails or runs too long. */
    private static String run(Duration timeout, String stdin, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(String.join(" ", command) + " ran past " + timeout);
        }
        String text = output.get(1, TimeUnit.MINUTES);
        if (process.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " exited " + process.exitValue() + ":\n" + text);
        }
        return text;
    }

    private static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
