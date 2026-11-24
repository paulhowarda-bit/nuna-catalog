package nuna.maintenance;

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

/**
 * Docker Desktop's Kubernetes as the maintenance chart sets it up for one test's compaction Jobs (D30 (3)), shared by
 * {@link KubernetesCompactionTest} and {@link PrivateCaCompactionTest}: the repo's Spark image built, and two
 * namespaces of the test's own, the worker's ({@link #namespace}) and the Jobs' ({@link #jobs}), created here and
 * deleted on {@link #close}; it never touches another (nuna-demo least of all). The chart's service account goes in the
 * worker's namespace and its Role in the Jobs', and a token of that account, the cluster's CA and the namespace go in a
 * folder as a pod has them mounted, so the worker, through {@link #settings}, talks to the API server as the chart's
 * service account with only the chart's Role, and its Jobs run as the chart's Job service account.
 * Needs Docker, kubectl and Helm on the PATH and the kubectl context {@value #CONTEXT}.
 */
final class CompactionCluster implements AutoCloseable {

    static final String CONTEXT = "docker-desktop";
    static final String IMAGE = "nuna-engines/spark:4.1.4-iceberg-1.12.0";
    private static final String RELEASE = "nuna-maintenance";

    /** The worker's namespace. */
    final String namespace;
    /** The Jobs' namespace. */
    final String jobs;
    /** The worker's compaction settings: the Spark image, the Jobs' namespace, the API server, the service account. */
    final WorkerSettings.Compaction settings;

    private CompactionCluster(String namespace, String jobs, WorkerSettings.Compaction settings) {
        this.namespace = namespace;
        this.jobs = jobs;
        this.settings = settings;
    }

    /**
     * Builds the image, creates the two namespaces and installs the chart's service account and Role there.
     *
     * @param serviceAccount an empty folder, where the worker's service account is written as a pod has it mounted
     * @param catalogForPods the catalog as the Jobs' pods reach it ({@link TestCatalog#forPods}): the chart's and the
     *                       settings' catalog URI
     * @param bucket         the store's bucket, under whose {@code tables/} the chart's storage profile lies
     * @param values         more of the chart's values, as {@code helm template} arguments
     */
    static CompactionCluster start(Path serviceAccount, URI catalogForPods, String bucket, String... values)
            throws Exception {
        Path repo = Path.of(System.getProperty("repo.root"));
        run(Duration.ofMinutes(20), null, "docker", "build", "-t", IMAGE,
                repo.resolve("catalog-tests-engines/images/spark").toString());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "nuna-maintenance-test-" + suffix;
        String jobs = "nuna-compaction-test-" + suffix;
        kubectl(Duration.ofMinutes(1), null, "create", "namespace", namespace);
        kubectl(Duration.ofMinutes(1), null, "create", "namespace", jobs);
        try {
            kubectl(Duration.ofMinutes(1), chart(repo, namespace, jobs, "templates/serviceaccount.yaml", catalogForPods,
                    bucket, values), "apply", "-n", namespace, "-f", "-");
            kubectl(Duration.ofMinutes(1), chart(repo, namespace, jobs, "templates/rbac.yaml", catalogForPods, bucket,
                    values), "apply", "-f", "-");
            Files.writeString(serviceAccount.resolve("token"), kubectl(Duration.ofMinutes(1), null, "create", "token",
                    RELEASE, "-n", namespace, "--duration=1h"));
            Files.writeString(serviceAccount.resolve("namespace"), namespace);
            Files.write(serviceAccount.resolve("ca.crt"), Base64.getDecoder().decode(kubectl(Duration.ofMinutes(1),
                    null, "config", "view", "--raw", "--minify", "-o",
                    "jsonpath={.clusters[0].cluster.certificate-authority-data}").strip()));
            URI apiServer = URI.create(kubectl(Duration.ofMinutes(1), null, "config", "view", "--minify", "-o",
                    "jsonpath={.clusters[0].cluster.server}").strip());
            return new CompactionCluster(namespace, jobs, new WorkerSettings.Compaction(IMAGE, "Never", jobs,
                    "nuna-compaction", apiServer, serviceAccount, catalogForPods, "2Gi", null, "1g", 2, 185, Map.of()));
        } catch (Exception | Error failure) {
            try {
                delete(namespace, jobs);
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Deletes both namespaces, and with them every Job, Secret and ConfigMap the test made there. */
    @Override
    public void close() throws Exception {
        delete(namespace, jobs);
    }

    private static void delete(String namespace, String jobs) throws Exception {
        kubectl(Duration.ofMinutes(5), null, "delete", "namespace", jobs, namespace, "--wait=true", "--timeout=4m");
    }

    /** One template of the chart as a test installs it: the worker in {@code namespace}, its Jobs in {@code jobs}. */
    private static String chart(Path repo, String namespace, String jobs, String template, URI catalogForPods,
                                String bucket, String... values) throws Exception {
        List<String> command = new ArrayList<>(List.of("helm", "template", RELEASE,
                repo.resolve("deploy/helm/nuna-maintenance").toString(), "--namespace", namespace,
                "--show-only", template,
                "--set", "config.nuna.maintenance.catalog-uri=" + catalogForPods,
                "--set", "config.nuna.maintenance.storage.test.prefixes[0]=s3://" + bucket + "/tables",
                "--set-string", "config.nuna.maintenance.cleanup-window.start=01:00",
                "--set-string", "config.nuna.maintenance.cleanup-window.end=05:00",
                "--set", "config.nuna.maintenance.cleanup-window.zone=UTC",
                "--set", "config.nuna.maintenance.compaction.image=" + IMAGE,
                "--set", "config.nuna.maintenance.compaction.namespace=" + jobs));
        command.addAll(List.of(values));
        return run(Duration.ofMinutes(1), null, command.toArray(String[]::new));
    }

    /** Waits for the Job to succeed or fail; returns {@code succeeded,failed}. */
    static String await(String namespace, String job) throws Exception {
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

    static String kubectl(Duration timeout, String stdin, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("kubectl", "--context", CONTEXT));
        command.addAll(List.of(arguments));
        return run(timeout, stdin, command.toArray(String[]::new));
    }

    /** Runs a command; returns its output, or fails with it when the command fails or runs too long. */
    static String run(Duration timeout, String stdin, String... command) throws Exception {
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
