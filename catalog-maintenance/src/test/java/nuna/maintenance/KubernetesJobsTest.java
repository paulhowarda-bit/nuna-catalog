package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * was cut off by the window's end. The worker's own namespace is refused for the Jobs.
 */
class KubernetesJobsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer api;

    @TempDir
    Path serviceAccount;

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

    @Test
    void oneJobPerTableSignedInThroughASecretItOwns() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");
        WorkerSettings.Compaction settings = settings("lakehouse-compaction");
        KubernetesJobs compactor = new KubernetesJobs(settings, URI.create("http://ignored/"), TOKENS,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
        KubernetesJobs tomorrow = new KubernetesJobs(settings, URI.create("http://ignored/"), TOKENS,
                Clock.fixed(next.opens().plusSeconds(60), ZoneOffset.UTC));
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
    void aJobsStateComesFromItsConditions() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        KubernetesJobs compactor = new KubernetesJobs(settings("lakehouse-compaction"), URI.create("http://ignored/"),
                TOKENS, Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Test
    void theWorkersOwnNamespaceIsRefusedForTheJobs() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");

        assertThatThrownBy(() -> new KubernetesJobs(settings("lakehouse"), URI.create("http://ignored/"), TOKENS,
                Clock.systemUTC())).hasMessageContaining("the worker's own namespace");
    }

    private WorkerSettings.Compaction settings(String namespace) {
        return new WorkerSettings.Compaction("nuna/spark:test", "Never", namespace, "nuna-compaction",
                URI.create("http://127.0.0.1:" + api.getAddress().getPort()), serviceAccount,
                URI.create("http://nuna-catalog:8181/"), "3Gi", "2g", 2, 185,
                Map.of("spark.sql.shuffle.partitions", "4"));
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
