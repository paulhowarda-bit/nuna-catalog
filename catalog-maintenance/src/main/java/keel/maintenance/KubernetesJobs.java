package keel.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Compaction as one Kubernetes Job per table and night (D30 (3), amended 2026-11-15): the repo's Spark image
 * ({@code catalog-tests-engines/images/spark}) runs Iceberg's rewrite_data_files with partial progress, signed in to
 * the catalog as the worker. The Job is named after the table and the night, so at most one runs per table a night,
 * across restarts of the worker too, and a Job kept from an earlier night never stops tonight's. Its deadline
 * ({@code activeDeadlineSeconds}) is the window's end: Kubernetes stops it then, and the file groups it finished stay
 * committed. A finished Job is kept a day ({@code ttlSecondsAfterFinished}), so its log is there the morning after;
 * how long it is kept schedules nothing. The worker's sign-in (the client-credentials grant, so the Job renews its own
 * token) goes into a Secret of a name never used before, created after the Job and owned by it, so Kubernetes deletes
 * it with the Job. The Job names the table by its levels and by its id, and its script stops unless the table it loads
 * has that id: another table may have taken the name before the pod starts.
 *
 * <p>The Jobs run in a namespace of their own, never the worker's (nor the catalog's): a pod may run as any service
 * account and mount any Secret of its namespace. Their pods run as {@code job-service-account}, which needs no
 * rights. Talks to the API server with the worker's service account: it needs {@code get} and {@code create} on Jobs
 * and {@code create} on Secrets in the Jobs' namespace, and nothing else (the Helm chart's Role).
 */
final class KubernetesJobs implements Compactor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT = script();
    private static final String COMMAND = "printf '%s' \"$KEEL_SCRIPT\" > /tmp/compact.py && exec /opt/spark/bin/"
            + "spark-submit --master 'local[2]' --driver-memory \"$KEEL_DRIVER_MEMORY\" /tmp/compact.py";

    /** How long a finished Job is kept: a day, for its log. It schedules nothing. */
    static final Duration KEPT = Duration.ofDays(1);

    private final WorkerSettings.Compaction settings;
    private final Clock clock;
    private final URI catalogUri;
    private final TokenSource tokens;
    private final String namespace;
    private final HttpClient http;

    /**
     * @param clock the worker's clock, which times each Job's deadline to the window's end
     */
    KubernetesJobs(WorkerSettings.Compaction settings, URI workerCatalogUri, TokenSource tokens, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.catalogUri = settings.catalogUri() != null ? settings.catalogUri() : workerCatalogUri;
        this.tokens = tokens;
        this.namespace = settings.namespace();
        Path own = settings.serviceAccount().resolve("namespace");
        if (Files.exists(own) && read(own).equals(namespace)) {
            throw new IllegalArgumentException("keel.maintenance.compaction.namespace " + namespace + " is the worker's"
                    + " own namespace: compaction Jobs need one of their own");
        }
        this.http = HttpClient.newBuilder().sslContext(trust(settings.serviceAccount().resolve("ca.crt")))
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The Job that compacts this table on this night: {@code keel-compact-<table id>-<the night's date>}. */
    static String jobName(Management.TableWork table, CleanupWindow.Night night) {
        return "keel-compact-" + table.id() + "-" + night.date().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Override
    public Launch launch(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night) {
        String name = jobName(table, night);
        HttpResponse<String> existing = send("GET", "/apis/batch/v1/namespaces/" + namespace + "/jobs/" + name, null);
        if (existing.statusCode() == 200) {
            return Launch.EXISTS;
        }
        expect("reading Job " + name, existing, 404);
        // A name no earlier Job's Secret has, so it never meets one Kubernetes has not collected yet. The pod waits
        // for it to appear.
        String secretName = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/apis/batch/v1/namespaces/" + namespace + "/jobs",
                job(table, need, night, secretName));
        if (created.statusCode() == 409) {
            return Launch.EXISTS;
        }
        String uid = read(expect("creating Job " + name, created, 201).body()).path("metadata").path("uid").asText();
        expect("creating Secret " + secretName, send("POST", "/api/v1/namespaces/" + namespace + "/secrets",
                secret(secretName, name, uid)), 201);
        return Launch.STARTED;
    }

    @Override
    public State state(Management.TableWork table, CleanupWindow.Night night) {
        String name = jobName(table, night);
        HttpResponse<String> job = send("GET", "/apis/batch/v1/namespaces/" + namespace + "/jobs/" + name, null);
        if (job.statusCode() == 404) {
            return State.SUCCEEDED;
        }
        for (JsonNode condition : read(expect("reading Job " + name, job, 200).body()).path("status")
                .path("conditions")) {
            if (!"True".equals(condition.path("status").asText())) {
                continue;
            }
            switch (condition.path("type").asText()) {
                case "Complete", "SuccessCriteriaMet" -> {
                    return State.SUCCEEDED;
                }
                case "Failed", "FailureTarget" -> {
                    return "DeadlineExceeded".equals(condition.path("reason").asText()) ? State.CUT_OFF
                            : State.FAILED;
                }
                default -> {
                }
            }
        }
        return State.RUNNING;
    }

    private ObjectNode job(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night,
                           String secretName) {
        String name = jobName(table, night);
        ObjectNode job = JSON.createObjectNode().put("apiVersion", "batch/v1").put("kind", "Job");
        ObjectNode metadata = job.putObject("metadata").put("name", name);
        labels(metadata, table);
        metadata.putObject("annotations").put("keel.catalog/table",
                table.catalog() + ":" + String.join(".", table.namespace()) + "." + table.name());
        ObjectNode spec = job.putObject("spec").put("backoffLimit", 0)
                .put("ttlSecondsAfterFinished", KEPT.toSeconds())
                .put("activeDeadlineSeconds", deadline(night));
        ObjectNode template = spec.putObject("template");
        labels(template.putObject("metadata"), table);
        ObjectNode pod = template.putObject("spec").put("restartPolicy", "Never").put("enableServiceLinks", false)
                .put("automountServiceAccountToken", false);
        if (settings.jobServiceAccount() != null) {
            pod.put("serviceAccountName", settings.jobServiceAccount());
        }
        pod.putObject("securityContext").put("runAsUser", settings.runAsUser()).put("runAsNonRoot", true);
        ObjectNode container = pod.putArray("containers").addObject().put("name", "compaction")
                .put("image", settings.image()).put("imagePullPolicy", settings.imagePullPolicy());
        container.putArray("command").add("sh").add("-c").add(COMMAND);
        ArrayNode env = container.putArray("env");
        env(env, "KEEL_SCRIPT", SCRIPT);
        env(env, "KEEL_CONF", sparkConf(table).toString());
        env(env, "KEEL_TABLE", JSON.valueToTree(levels(table)).toString());
        env(env, "KEEL_TABLE_UUID", table.id().toString());
        env(env, "KEEL_OPTIONS", JSON.valueToTree(need.options()).toString());
        env(env, "KEEL_DRIVER_MEMORY", settings.driverMemory());
        env.addObject().put("name", "KEEL_AUTH").putObject("valueFrom").putObject("secretKeyRef")
                .put("name", secretName).put("key", "auth");
        ObjectNode resources = container.putObject("resources");
        resources.putObject("limits").put("memory", settings.memory());
        resources.putObject("requests").put("memory", settings.memory());
        return job;
    }

    /** Seconds from now to the window's end, rounded up: at least one, which Kubernetes requires. */
    private long deadline(CleanupWindow.Night night) {
        return Math.max(1, (Duration.between(clock.instant(), night.closes()).toMillis() + 999) / 1000);
    }

    private ObjectNode sparkConf(Management.TableWork table) {
        Map<String, String> conf = new LinkedHashMap<>();
        conf.put("spark.ui.enabled", "false");
        conf.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.sql.catalog.keel", "org.apache.iceberg.spark.SparkCatalog");
        conf.put("spark.sql.catalog.keel.type", "rest");
        conf.put("spark.sql.catalog.keel.uri", catalogUri.toString());
        conf.put("spark.sql.catalog.keel.warehouse", table.catalog());
        conf.put("spark.sql.catalog.keel.header.X-Iceberg-Access-Delegation", "vended-credentials");
        conf.put("spark.sql.catalog.keel.io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
        conf.putAll(settings.sparkConf());
        return JSON.valueToTree(conf);
    }

    private ObjectNode secret(String name, String job, String jobUid) {
        ObjectNode secret = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Secret").put("type", "Opaque");
        ObjectNode metadata = secret.putObject("metadata").put("name", name);
        metadata.putObject("labels").put("app.kubernetes.io/name", "keel-compaction")
                .put("app.kubernetes.io/managed-by", "keel-maintenance");
        metadata.putArray("ownerReferences").addObject().put("apiVersion", "batch/v1").put("kind", "Job")
                .put("name", job).put("uid", jobUid);
        secret.putObject("stringData").put("auth", JSON.valueToTree(tokens.clientProperties()).toString());
        return secret;
    }

    private static List<String> levels(Management.TableWork table) {
        List<String> levels = new ArrayList<>(table.namespace());
        levels.add(table.name());
        return levels;
    }

    private static void labels(ObjectNode metadata, Management.TableWork table) {
        metadata.putObject("labels").put("app.kubernetes.io/name", "keel-compaction")
                .put("app.kubernetes.io/managed-by", "keel-maintenance").put("keel.catalog/table-id",
                        table.id().toString());
    }

    private static void env(ArrayNode env, String name, String value) {
        env.addObject().put("name", name).put("value", value);
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.apiServer() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + read(settings.serviceAccount().resolve("token")))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body.toString()));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach the Kubernetes API server " + settings.apiServer(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static HttpResponse<String> expect(String what, HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new IllegalStateException(what + ": the Kubernetes API server answered " + response.statusCode()
                    + ": " + response.body());
        }
        return response;
    }

    private static JsonNode read(String body) throws UncheckedIOException {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Trusts the cluster's CA when its file is there, else the JVM's default trust. */
    private static SSLContext trust(Path ca) {
        try {
            if (!Files.exists(ca)) {
                return SSLContext.getDefault();
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream in = Files.newInputStream(ca)) {
                int i = 0;
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("ca-" + i++, certificate);
                }
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the cluster CA " + ca, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot trust the cluster CA " + ca, e);
        }
    }

    private static String script() {
        try (InputStream in = KubernetesJobs.class.getResourceAsStream("compact.py")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
