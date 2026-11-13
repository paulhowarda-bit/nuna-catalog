package keel.maintenance;

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
import java.time.Duration;
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
 * Compaction's Kubernetes Job (D30 (3)) as the API server receives it, from a stand-in API server: one Job per table,
 * in the Jobs' own namespace, named for the table, kept for the compaction interval, running the Spark image as its
 * user and as a service account of its own, with the table's levels, id and options, and signed in through a Secret of
 * a fresh name that the Job owns, created after it. A Job already there means one is running or ran within the
 * interval. The worker's own namespace is refused for the Jobs.
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

    @Test
    void oneJobPerTableSignedInThroughASecretItOwns() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");
        WorkerSettings.Compaction settings = settings("lakehouse-compaction");
        KubernetesJobs compactor = new KubernetesJobs(settings, Duration.ofDays(1), URI.create("http://ignored/"),
                TOKENS);
        UUID id = UUID.randomUUID();
        Management.TableWork table = new Management.TableWork("lake", id, List.of("sales", "eu"), "orders",
                "s3://warehouse/tables/orders", Map.of("compaction.enabled", "true",
                "compaction.target-file-size-bytes", "134217728"));

        assertThat(compactor.launch(table)).isEqualTo(Compactor.Launch.STARTED);
        assertThat(compactor.launch(table)).as("the Job is there").isEqualTo(Compactor.Launch.RUNNING);

        String name = "keel-compact-" + id;
        assertThat(requests).as("the Job before its Secret, nothing replaced").containsExactly(
                "GET /apis/batch/v1/namespaces/lakehouse-compaction/jobs/" + name,
                "POST /apis/batch/v1/namespaces/lakehouse-compaction/jobs",
                "POST /api/v1/namespaces/lakehouse-compaction/secrets",
                "GET /apis/batch/v1/namespaces/lakehouse-compaction/jobs/" + name);
        JsonNode job = JSON.readTree(jobs.get(name));
        assertThat(job.at("/metadata/labels/keel.catalog~1table-id").asText()).isEqualTo(id.toString());
        assertThat(job.at("/spec/ttlSecondsAfterFinished").asLong()).isEqualTo(Duration.ofDays(1).toSeconds());
        assertThat(job.at("/spec/activeDeadlineSeconds").asLong()).isEqualTo(3600);
        assertThat(job.at("/spec/backoffLimit").asInt()).isZero();
        JsonNode pod = job.at("/spec/template/spec");
        assertThat(pod.at("/securityContext/runAsUser").asLong()).isEqualTo(185);
        assertThat(pod.at("/automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(pod.at("/serviceAccountName").asText()).isEqualTo("keel-compaction");
        JsonNode container = pod.at("/containers/0");
        assertThat(container.at("/image").asText()).isEqualTo("keel/spark:test");
        assertThat(container.at("/imagePullPolicy").asText()).isEqualTo("Never");
        assertThat(container.at("/resources/limits/memory").asText()).isEqualTo("3Gi");
        Map<String, JsonNode> env = new java.util.HashMap<>();
        container.at("/env").forEach(variable -> env.put(variable.path("name").asText(), variable));
        assertThat(JSON.readTree(env.get("KEEL_TABLE").path("value").asText()))
                .isEqualTo(JSON.readTree("[\"sales\",\"eu\",\"orders\"]"));
        assertThat(env.get("KEEL_TABLE_UUID").path("value").asText()).isEqualTo(id.toString());
        assertThat(JSON.readTree(env.get("KEEL_OPTIONS").path("value").asText()).path("target-file-size-bytes").asText())
                .isEqualTo("134217728");
        JsonNode conf = JSON.readTree(env.get("KEEL_CONF").path("value").asText());
        assertThat(conf.path("spark.sql.catalog.keel.uri").asText()).isEqualTo("http://keel-catalog:8181/");
        assertThat(conf.path("spark.sql.catalog.keel.warehouse").asText()).isEqualTo("lake");
        assertThat(conf.path("spark.sql.shuffle.partitions").asText()).isEqualTo("4");
        assertThat(env.get("KEEL_DRIVER_MEMORY").path("value").asText()).isEqualTo("2g");
        assertThat(env.get("KEEL_SCRIPT").path("value").asText()).contains("rewrite_data_files", "KEEL_TABLE_UUID");
        assertThat(job.toString()).as("no credential in the Job itself").doesNotContain("worker-token");

        assertThat(secrets).hasSize(1);
        JsonNode secret = secrets.getFirst();
        assertThat(secret.at("/metadata/name").asText()).as("a name of its own").startsWith(name + "-")
                .isNotEqualTo(name).isEqualTo(env.get("KEEL_AUTH").at("/valueFrom/secretKeyRef/name").asText());
        assertThat(secret.at("/metadata/ownerReferences/0/kind").asText()).isEqualTo("Job");
        assertThat(secret.at("/metadata/ownerReferences/0/name").asText()).isEqualTo(name);
        assertThat(secret.at("/metadata/ownerReferences/0/uid").asText()).isEqualTo("uid-" + name);
        assertThat(JSON.readTree(secret.at("/stringData/auth").asText()).path("token").asText())
                .isEqualTo("worker-token");
        assertThat(authorizations).isNotEmpty().containsOnly("Bearer sa-token");
    }

    @Test
    void theWorkersOwnNamespaceIsRefusedForTheJobs() throws Exception {
        Files.writeString(serviceAccount.resolve("token"), "sa-token\n");
        Files.writeString(serviceAccount.resolve("namespace"), "lakehouse");

        assertThatThrownBy(() -> new KubernetesJobs(settings("lakehouse"), Duration.ofDays(1),
                URI.create("http://ignored/"), TOKENS)).hasMessageContaining("the worker's own namespace");
    }

    private WorkerSettings.Compaction settings(String namespace) {
        return new WorkerSettings.Compaction("keel/spark:test", "Never", namespace, "keel-compaction",
                URI.create("http://127.0.0.1:" + api.getAddress().getPort()), serviceAccount,
                URI.create("http://keel-catalog:8181/"), "3Gi", "2g", Duration.ofHours(1), 185,
                Map.of("spark.sql.shuffle.partitions", "4"));
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
