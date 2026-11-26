package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.tests.s3.PrivateCa;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compaction's Kubernetes Job (D30 (3), amended 2026-11-15) as the API server receives it, from a stand-in API server:
 * one Job per table and night, in the Jobs' own namespace, named for the table and the night, stopped by Kubernetes at
 * the cleanup window's end and kept a day after it finishes, running the Spark image as its user and as a service
 * account of its own, with the table's levels, id and rewrite_data_files' options, partial progress among them, and
 * signed in through a Secret of a fresh name that the Job owns, created after it. A Job already there for the night
 * means tonight's is running or ran. A Job's state comes from its conditions: one Kubernetes stopped at its deadline
 * was cut off by the window's end. The worker's own namespace is refused for the Jobs. A table on a storage profile
 * with a CA bundle gets it in a ConfigMap the Job owns, mounted, and Spark's driver trusts it beside the image's JDK
 * CAs (backlog 88); a table on a profile without one gets the Job as before.
 */
class KubernetesJobsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** No store for any table: the Job trusts no storage CA. */
    private static final Function<String, Optional<String>> NO_STORE = location -> Optional.empty();
    private static final TokenSource TOKENS = new TokenSource() {
        @Override
        public String token() {
            return "worker-token";
        }

        @Override
        public Map<String, String> clientProperties() {
            return Map.of("token", "worker-token");
        }
    };

    private final Map<String, String> jobs = new ConcurrentHashMap<>();
    private final List<JsonNode> secrets = new CopyOnWriteArrayList<>();
    private final List<JsonNode> configMaps = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer api;

    @TempDir
    Path serviceAccount;
    @TempDir
    Path bundles;

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", this::handle);
        api.start();
    }

    @AfterEach
    void stop() {
        api.stop(0);
    }

    /** 02:00 on 15 November 2026 in London, GMT: an hour into the night's window, 01:00 to 05:00. */
    private static final Instant NOW = Instant.parse("2026-11-15T02:00:00Z");
    private static final CleanupWindow WINDOW = new CleanupWindow(LocalTime.of(1, 0), LocalTime.of(5, 0),
            ZoneId.of("Europe/London"));
    private static final CompactionNeed SMALL_FILES = new CompactionNeed(10, 10, 0, 0, 5, 0.3, 134217728);
    /** The Job's command for a table whose storage profile names no CA bundle. */
    private static final String PLAIN_COMMAND = "printf '%s' \"$NUNA_SCRIPT\" > /tmp/compact.py && exec"
            + " /opt/spark/bin/spark-submit --master 'local[2]' --driver-memory \"$NUNA_DRIVER_MEMORY\" /tmp/compact.py";

    @Test
    void oneJobPerTableSignedInThroughASecretItOwns() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");
        WorkerSettings.Compaction settings = settings("lakehouse-compaction");
        KubernetesJobs compactor = new KubernetesJobs(settings, URI.create("http://ignored/"), TOKENS, Map.of(),
                NO_STORE, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        Management.TableWork table = new Management.TableWork("lake", id, List.of("sales", "eu"), "orders",
                "s3://warehouse/tables/orders", Map.of("compaction.enabled", "true",
                "compaction.target-file-size-bytes", "134217728"));
        CleanupWindow.Night night = WINDOW.night(NOW).orElseThrow();

        assertThat(compactor.launch(table, SMALL_FILES, night)).isEqualTo(Compactor.Launch.STARTED);
        assertThat(compactor.launch(table, SMALL_FILES, night)).as("tonight's Job is there")
                .isEqualTo(Compactor.Launch.EXISTS);

        String name = "nuna-compact-" + id + "-20261115";
        assertThat(KubernetesJobs.jobName(table, night)).isEqualTo(name);
        assertThat(name).as("short enough for the label Kubernetes puts on its pods").hasSizeLessThanOrEqualTo(63);
        assertThat(requests).as("the Job before its Secret, nothing replaced").containsExactly(
                "GET /apis/batch/v1/namespaces/lakehouse-compaction/jobs/" + name,
                "POST /apis/batch/v1/namespaces/lakehouse-compaction/jobs",
                "POST /api/v1/namespaces/lakehouse-compaction/secrets",
                "GET /apis/batch/v1/namespaces/lakehouse-compaction/jobs/" + name);
        JsonNode job = JSON.readTree(jobs.get(name));
        assertThat(job.at("/metadata/labels/nuna.catalog~1table-id").asText()).isEqualTo(id.toString());
        assertThat(job.at("/spec/activeDeadlineSeconds").asLong()).as("stopped at the window's end, 05:00")
                .isEqualTo(Duration.ofHours(3).toSeconds());
        assertThat(job.at("/spec/ttlSecondsAfterFinished").asLong()).as("kept a day, scheduling nothing")
                .isEqualTo(Duration.ofDays(1).toSeconds());
        assertThat(job.at("/spec/backoffLimit").asInt()).isZero();
        JsonNode pod = job.at("/spec/template/spec");
        assertThat(pod.at("/securityContext/runAsUser").asLong()).isEqualTo(185);
        assertThat(pod.at("/automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(pod.at("/serviceAccountName").asText()).isEqualTo("nuna-compaction");
        JsonNode container = pod.at("/containers/0");
        assertThat(container.at("/image").asText()).isEqualTo("nuna/spark:test");
        assertThat(container.at("/imagePullPolicy").asText()).isEqualTo("Never");
        assertThat(container.at("/resources/limits/memory").asText()).isEqualTo("3Gi");
        assertThat(container.at("/resources/requests/memory").asText()).as("by default the limit").isEqualTo("3Gi");
        assertThat(container.at("/command/2").asText()).as("no storage CA bundle: the command as it always was")
                .isEqualTo(PLAIN_COMMAND);
        assertThat(container.has("volumeMounts")).isFalse();
        assertThat(pod.has("volumes")).isFalse();
        Map<String, JsonNode> env = env(job);
        assertThat(JSON.readTree(env.get("NUNA_TABLE").path("value").asText()))
                .isEqualTo(JSON.readTree("[\"sales\",\"eu\",\"orders\"]"));
        assertThat(env.get("NUNA_TABLE_UUID").path("value").asText()).isEqualTo(id.toString());
        assertThat(JSON.readTree(env.get("NUNA_OPTIONS").path("value").asText())).as("small files alone")
                .isEqualTo(JSON.readTree("{\"target-file-size-bytes\":\"134217728\",\"min-input-files\":\"5\","
                        + "\"partial-progress.enabled\":\"true\"}"));
        JsonNode conf = JSON.readTree(env.get("NUNA_CONF").path("value").asText());
        assertThat(conf.path("spark.sql.catalog.nuna.uri").asText()).isEqualTo("http://nuna-catalog:8181/");
        assertThat(conf.path("spark.sql.catalog.nuna.warehouse").asText()).isEqualTo("lake");
        assertThat(conf.path("spark.sql.shuffle.partitions").asText()).isEqualTo("4");
        assertThat(env.get("NUNA_DRIVER_MEMORY").path("value").asText()).isEqualTo("2g");
        assertThat(env.get("NUNA_SCRIPT").path("value").asText()).contains("rewrite_data_files", "NUNA_TABLE_UUID");
        assertThat(job.toString()).as("no credential in the Job itself").doesNotContain("worker-token");

        assertThat(secrets).hasSize(1);
        JsonNode secret = secrets.getFirst();
        assertThat(secret.at("/metadata/name").asText()).as("a name of its own").startsWith(name + "-")
                .isNotEqualTo(name).isEqualTo(env.get("NUNA_AUTH").at("/valueFrom/secretKeyRef/name").asText());
        assertThat(secret.at("/metadata/ownerReferences/0/kind").asText()).isEqualTo("Job");
        assertThat(secret.at("/metadata/ownerReferences/0/name").asText()).isEqualTo(name);
        assertThat(secret.at("/metadata/ownerReferences/0/uid").asText()).isEqualTo("uid-" + name);
        assertThat(JSON.readTree(secret.at("/stringData/auth").asText()).path("token").asText())
                .isEqualTo("worker-token");
        assertThat(authorizations).isNotEmpty().containsOnly("Bearer sa-token");

        // The next night, a minute after it opens: a Job of its own, whatever is kept of tonight's, to the next end.
        CleanupWindow.Night next = WINDOW.on(night.date().plusDays(1));
        KubernetesJobs tomorrow = new KubernetesJobs(settings, URI.create("http://ignored/"), TOKENS, Map.of(),
                NO_STORE, Clock.fixed(next.opens().plusSeconds(60), ZoneOffset.UTC));
        CompactionNeed deletes = new CompactionNeed(10, 0, 4, 1, 5, 0.3, 536870912);
        assertThat(tomorrow.launch(table, deletes, next)).isEqualTo(Compactor.Launch.STARTED);
        JsonNode second = JSON.readTree(jobs.get("nuna-compact-" + id + "-20261116"));
        assertThat(second.at("/spec/activeDeadlineSeconds").asLong())
                .isEqualTo(Duration.ofHours(4).toSeconds() - 60);
        JsonNode options = JSON.readTree(env(second).get("NUNA_OPTIONS").path("value").asText());
        assertThat(options.path("delete-file-threshold").asText()).as("every file with a delete rewritten")
                .isEqualTo("1");
        assertThat(options.path("remove-dangling-deletes").asText()).isEqualTo("true");
        assertThat(options.path("partial-progress.enabled").asText()).isEqualTo("true");
    }

    @Test
    void aStorageCaBundleIsMountedIntoTheJobAndSparkTrustsIt() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        X509Certificate privateCa = PrivateCa.shared().certificate;
        X509Certificate jvmCa = jvmCa();
        Path bundle = Files.writeString(bundles.resolve("ceph.crt"), PrivateCa.pem(privateCa) + "a label between\n"
                + PrivateCa.pem(jvmCa), StandardCharsets.US_ASCII);
        Map<String, WorkerSettings.Store> stores = Map.of(
                "ceph", new WorkerSettings.Store(StoreKind.CEPH, URI.create("https://ceph.example.internal"),
                        "us-east-1", null, null, bundle),
                "aws", new WorkerSettings.Store(StoreKind.S3, URI.create("https://s3.eu-west-2.amazonaws.com"),
                        "eu-west-2", null, null, null));
        // The catalog gives each store's prefixes; the worker's storage finds a table's store by them.
        OwnStorage storage = Storage.of(stores);
        storage.reach(List.of(
                new Management.StorageProfile("onprem", "ceph", "https://ceph.example.internal",
                        List.of("s3://warehouse/tables")),
                new Management.StorageProfile("cloud", "s3", "https://s3.eu-west-2.amazonaws.com",
                        List.of("s3://aws-lake/tables"))));
        KubernetesJobs compactor = new KubernetesJobs(settings("lakehouse-compaction"), URI.create("http://ignored/"),
                TOKENS, stores, storage::store, Clock.fixed(NOW, ZoneOffset.UTC));
        CleanupWindow.Night night = WINDOW.night(NOW).orElseThrow();
        Management.TableWork onPrem = new Management.TableWork("lake", UUID.randomUUID(), List.of("sales"), "orders",
                "s3://warehouse/tables/orders", Map.of("compaction.enabled", "true"));
        Management.TableWork onAws = new Management.TableWork("lake", UUID.randomUUID(), List.of("sales"), "returns",
                "s3://aws-lake/tables/returns", Map.of("compaction.enabled", "true"));

        assertThat(compactor.launch(onPrem, SMALL_FILES, night)).isEqualTo(Compactor.Launch.STARTED);
        String name = KubernetesJobs.jobName(onPrem, night);
        assertThat(requests).as("the bundle's ConfigMap after the Job and its Secret").containsExactly(
                "GET /apis/batch/v1/namespaces/lakehouse-compaction/jobs/" + name,
                "POST /apis/batch/v1/namespaces/lakehouse-compaction/jobs",
                "POST /api/v1/namespaces/lakehouse-compaction/secrets",
                "POST /api/v1/namespaces/lakehouse-compaction/configmaps");
        String own = secrets.getFirst().at("/metadata/name").asText();
        JsonNode configMap = configMaps.getFirst();
        assertThat(configMap.at("/metadata/name").asText()).as("the Secret's fresh name").isEqualTo(own);
        assertThat(configMap.at("/metadata/ownerReferences/0/kind").asText()).isEqualTo("Job");
        assertThat(configMap.at("/metadata/ownerReferences/0/name").asText()).isEqualTo(name);
        assertThat(configMap.at("/metadata/ownerReferences/0/uid").asText()).isEqualTo("uid-" + name);
        List<String> keys = new ArrayList<>();
        configMap.path("data").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("one certificate per key, in the bundle's order").containsExactly("ca-0.pem", "ca-1.pem");
        assertThat(certificates(configMap.at("/data/ca-0.pem").asText())).containsExactly(privateCa);
        assertThat(certificates(configMap.at("/data/ca-1.pem").asText())).containsExactly(jvmCa);

        JsonNode pod = JSON.readTree(jobs.get(name)).at("/spec/template/spec");
        JsonNode container = pod.at("/containers/0");
        assertThat(pod.at("/volumes")).isEqualTo(JSON.readTree(
                "[{\"name\":\"storage-ca\",\"configMap\":{\"name\":\"" + own + "\"}}]"));
        assertThat(container.at("/volumeMounts")).isEqualTo(JSON.readTree(
                "[{\"name\":\"storage-ca\",\"mountPath\":\"/etc/nuna/storage-ca\",\"readOnly\":true}]"));
        assertThat(container.at("/command/0").asText()).isEqualTo("sh");
        assertThat(container.at("/command/2").asText())
                .as("a trust store of the image's JDK CAs and the bundle's, built before Spark's driver starts on it")
                .containsSubsequence(
                        "tp=$(cat /proc/sys/kernel/random/uuid);",
                        "-importkeystore -srckeystore \"$JAVA_HOME/lib/security/cacerts\"",
                        "-destkeystore /tmp/storage-trust.p12", "-deststorepass \"$tp\";",
                        "for ca in /etc/nuna/storage-ca/*.pem; do",
                        "-importcert", "-file \"$ca\" -keystore /tmp/storage-trust.p12 -storepass \"$tp\"",
                        "printf '%s' \"$NUNA_SCRIPT\" > /tmp/compact.py",
                        "exec /opt/spark/bin/spark-submit --master 'local[2]' --driver-memory \"$NUNA_DRIVER_MEMORY\"",
                        "--conf 'spark.driver.extraJavaOptions=-Djavax.net.ssl.trustStore=/tmp/storage-trust.p12"
                                + " -Djavax.net.ssl.trustStorePassword='\"$tp\"",
                        "/tmp/compact.py")
                .as("the trust store's password is made as the script starts, never a fixed value in the Job")
                .doesNotContainPattern("(?<!src)storepass [^\"]|trustStorePassword=[^']");

        // A table on a store with no bundle: its Job as it always was, and no ConfigMap.
        assertThat(compactor.launch(onAws, SMALL_FILES, night)).isEqualTo(Compactor.Launch.STARTED);
        JsonNode plain = JSON.readTree(jobs.get(KubernetesJobs.jobName(onAws, night))).at("/spec/template/spec");
        assertThat(plain.has("volumes")).isFalse();
        assertThat(plain.at("/containers/0").has("volumeMounts")).isFalse();
        assertThat(plain.at("/containers/0/command/2").asText()).isEqualTo(PLAIN_COMMAND);
        assertThat(configMaps).hasSize(1);
    }

    @Test
    void aJobsStateComesFromItsConditions() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        KubernetesJobs compactor = new KubernetesJobs(settings("lakehouse-compaction"), URI.create("http://ignored/"),
                TOKENS, Map.of(), NO_STORE, Clock.fixed(NOW, ZoneOffset.UTC));
        Management.TableWork table = new Management.TableWork("lake", UUID.randomUUID(), List.of("sales"), "orders",
                "s3://warehouse/tables/orders", Map.of("compaction.enabled", "true"));
        CleanupWindow.Night night = WINDOW.night(NOW).orElseThrow();
        String name = KubernetesJobs.jobName(table, night);

        assertThat(compactor.state(table, night)).as("no Job: nothing left to wait for")
                .isEqualTo(Compactor.State.SUCCEEDED);
        Map<String, Compactor.State> states = Map.of(
                "{}", Compactor.State.RUNNING,
                "{\"conditions\":[{\"type\":\"Complete\",\"status\":\"False\"}]}", Compactor.State.RUNNING,
                "{\"conditions\":[{\"type\":\"SuccessCriteriaMet\",\"status\":\"True\"},"
                        + "{\"type\":\"Complete\",\"status\":\"True\"}]}", Compactor.State.SUCCEEDED,
                "{\"conditions\":[{\"type\":\"FailureTarget\",\"status\":\"True\",\"reason\":\"DeadlineExceeded\"}]}",
                Compactor.State.CUT_OFF,
                "{\"conditions\":[{\"type\":\"Failed\",\"status\":\"True\",\"reason\":\"DeadlineExceeded\"}]}",
                Compactor.State.CUT_OFF,
                "{\"conditions\":[{\"type\":\"Failed\",\"status\":\"True\",\"reason\":\"BackoffLimitExceeded\"}]}",
                Compactor.State.FAILED);
        for (Map.Entry<String, Compactor.State> state : states.entrySet()) {
            jobs.put(name, "{\"metadata\":{\"name\":\"" + name + "\"},\"status\":" + state.getKey() + "}");
            assertThat(compactor.state(table, night)).as(state.getKey()).isEqualTo(state.getValue());
        }
    }

    /** A Job may ask for less memory than its limit, to schedule on a node mostly asked for (backlog 230). */
    @Test
    void aJobMayAskForLessMemoryThanItsLimit() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        WorkerSettings.Compaction base = settings("lakehouse-compaction");
        WorkerSettings.Compaction settings = new WorkerSettings.Compaction(base.image(), base.imagePullPolicy(),
                base.namespace(), base.jobServiceAccount(), base.apiServer(), base.serviceAccount(), base.catalogUri(),
                "1Gi", "128Mi", "512m", 1, 185, Map.of());
        KubernetesJobs compactor = new KubernetesJobs(settings, URI.create("http://ignored/"), TOKENS, Map.of(),
                NO_STORE, Clock.fixed(NOW, ZoneOffset.UTC));
        Management.TableWork table = new Management.TableWork("lake", UUID.randomUUID(), List.of("sales"), "orders",
                "s3://warehouse/tables/orders", Map.of("compaction.enabled", "true"));
        CleanupWindow.Night night = WINDOW.night(NOW).orElseThrow();

        assertThat(compactor.launch(table, SMALL_FILES, night)).isEqualTo(Compactor.Launch.STARTED);
        JsonNode container = JSON.readTree(jobs.get(KubernetesJobs.jobName(table, night)))
                .at("/spec/template/spec/containers/0");
        assertThat(container.at("/resources/limits/memory").asText()).isEqualTo("1Gi");
        assertThat(container.at("/resources/requests/memory").asText()).isEqualTo("128Mi");
    }

    @Test
    void theWorkersOwnNamespaceIsRefusedForTheJobs() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");

        assertThatThrownBy(() -> new KubernetesJobs(settings("lakehouse"), URI.create("http://ignored/"), TOKENS,
                Map.of(), NO_STORE, Clock.systemUTC())).hasMessageContaining("the worker's own namespace");
    }

    private WorkerSettings.Compaction settings(String namespace) {
        return new WorkerSettings.Compaction("nuna/spark:test", "Never", namespace, "nuna-compaction",
                URI.create("http://127.0.0.1:" + api.getAddress().getPort()), serviceAccount,
                URI.create("http://nuna-catalog:8181/"), "3Gi", null, "2g", 2, 185,
                Map.of("spark.sql.shuffle.partitions", "4"));
    }

    /** A CA the JVM trusts, for a bundle of more than one certificate. */
    private static X509Certificate jvmCa() throws GeneralSecurityException {
        TrustManagerFactory jvm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jvm.init((KeyStore) null);
        return Arrays.stream(jvm.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow().getAcceptedIssuers()[0];
    }

    private static List<Certificate> certificates(String pem) throws GeneralSecurityException {
        return List.copyOf(CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))));
    }

    /** The Job container's environment, by variable name. */
    private static Map<String, JsonNode> env(JsonNode job) {
        Map<String, JsonNode> env = new java.util.HashMap<>();
        job.at("/spec/template/spec/containers/0/env").forEach(variable ->
                env.put(variable.path("name").asText(), variable));
        return env;
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String jobs = "/apis/batch/v1/namespaces/lakehouse-compaction/jobs";
        if (exchange.getRequestMethod().equals("GET") && path.startsWith(jobs + "/")) {
            String job = this.jobs.get(path.substring(jobs.length() + 1));
            answer(exchange, job == null ? 404 : 200, job == null ? "{\"kind\":\"Status\",\"code\":404}" : job);
        } else if (exchange.getRequestMethod().equals("POST") && path.equals(jobs)) {
            String name = JSON.readTree(body).at("/metadata/name").asText();
            this.jobs.put(name, body);
            answer(exchange, 201, "{\"metadata\":{\"name\":\"" + name + "\",\"uid\":\"uid-" + name + "\"}}");
        } else if (exchange.getRequestMethod().equals("POST") && path.equals("/api/v1/namespaces/lakehouse-compaction/secrets")) {
            secrets.add(JSON.readTree(body));
            answer(exchange, 201, body);
        } else if (exchange.getRequestMethod().equals("POST")
                && path.equals("/api/v1/namespaces/lakehouse-compaction/configmaps")) {
            configMaps.add(JSON.readTree(body));
            answer(exchange, 201, body);
        } else {
            answer(exchange, 404, "{}");
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
