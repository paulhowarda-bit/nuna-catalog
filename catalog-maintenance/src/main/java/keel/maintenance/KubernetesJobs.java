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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Compaction as one Kubernetes Job per table (D30 (3)): the repo's Spark image
 * ({@code catalog-tests-engines/images/spark}) runs Iceberg's rewrite_data_files, signed in to the catalog as the
 * worker. The Job is named after the table, so at most one runs per table; a finished Job stays for the compaction
 * interval ({@code ttlSecondsAfterFinished}), which is what spaces two compactions of one table apart, across
 * restarts of the worker too. The worker's sign-in goes into a Secret the Job owns, so Kubernetes deletes it with
 * the Job.
 *
 * <p>Talks to the API server with the worker's service account: it needs {@code get} and {@code create} on Jobs
 * and {@code create} and {@code update} on Secrets in the Jobs' namespace (the Helm chart's Role).
 */
final class KubernetesJobs implements Compactor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT = script();
    private static final String COMMAND = "printf '%s' \"$KEEL_SCRIPT\" > /tmp/compact.py && exec /opt/spark/bin/"
            + "spark-submit --master 'local[2]' --driver-memory \"$KEEL_DRIVER_MEMORY\" /tmp/compact.py";

    private final WorkerSettings.Compaction settings;
    private final Duration interval;
    private final URI catalogUri;
    private final TokenSource tokens;
    private final String namespace;
    private final HttpClient http;

    KubernetesJobs(WorkerSettings.Compaction settings, Duration interval, URI workerCatalogUri, TokenSource tokens) {
        this.settings = settings;
        this.interval = interval;
        this.catalogUri = settings.catalogUri() != null ? settings.catalogUri() : workerCatalogUri;
        this.tokens = tokens;
        this.namespace = settings.namespace() != null ? settings.namespace()
                : read(settings.serviceAccount().resolve("namespace"));
        this.http = HttpClient.newBuilder().sslContext(trust(settings.serviceAccount().resolve("ca.crt")))
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The Job that compacts this table. */
    static String jobName(Management.TableWork table) {
        return "keel-compact-" + table.id();
    }

    @Override
    public Launch launch(Management.TableWork table) {
        String name = jobName(table);
        HttpResponse<String> existing = send("GET", "/apis/batch/v1/namespaces/" + namespace + "/jobs/" + name, null);
        if (existing.statusCode() == 200) {
            return Launch.RUNNING;
        }
        expect("reading Job " + name, existing, 404);
        HttpResponse<String> created = send("POST", "/apis/batch/v1/namespaces/" + namespace + "/jobs", job(table));
        if (created.statusCode() == 409) {
            return Launch.RUNNING;
        }
        String uid = read(expect("creating Job " + name, created, 201).body()).path("metadata").path("uid").asText();
        ObjectNode secret = secret(name, uid);
        HttpResponse<String> stored = send("POST", "/api/v1/namespaces/" + namespace + "/secrets", secret);
        if (stored.statusCode() == 409) {
            stored = send("PUT", "/api/v1/namespaces/" + namespace + "/secrets/" + name, secret);
            expect("replacing Secret " + name, stored, 200);
        } else {
            expect("creating Secret " + name, stored, 201);
        }
        return Launch.STARTED;
    }

    private ObjectNode job(Management.TableWork table) {
        String name = jobName(table);
        ObjectNode job = JSON.createObjectNode().put("apiVersion", "batch/v1").put("kind", "Job");
        ObjectNode metadata = job.putObject("metadata").put("name", name);
        labels(metadata, table);
        metadata.putObject("annotations").put("keel.catalog/table",
                table.catalog() + ":" + String.join(".", table.namespace()) + "." + table.name());
        ObjectNode spec = job.putObject("spec").put("backoffLimit", 0)
                .put("ttlSecondsAfterFinished", interval.toSeconds())
                .put("activeDeadlineSeconds", settings.deadline().toSeconds());
        ObjectNode template = spec.putObject("template");
        labels(template.putObject("metadata"), table);
        ObjectNode pod = template.putObject("spec").put("restartPolicy", "Never").put("enableServiceLinks", false)
                .put("automountServiceAccountToken", false);
        pod.putObject("securityContext").put("runAsUser", settings.runAsUser()).put("runAsNonRoot", true);
        ObjectNode container = pod.putArray("containers").addObject().put("name", "compaction")
                .put("image", settings.image()).put("imagePullPolicy", settings.imagePullPolicy());
        container.putArray("command").add("sh").add("-c").add(COMMAND);
        ArrayNode env = container.putArray("env");
        env(env, "KEEL_SCRIPT", SCRIPT);
        env(env, "KEEL_CONF", sparkConf(table).toString());
        env(env, "KEEL_TABLE", JSON.valueToTree(levels(table)).toString());
        ObjectNode options = JSON.createObjectNode();
        String target = table.policy().get("compaction.target-file-size-bytes");
        if (target != null) {
            options.put("target-file-size-bytes", target);
        }
        env(env, "KEEL_OPTIONS", options.toString());
        env(env, "KEEL_DRIVER_MEMORY", settings.driverMemory());
        env.addObject().put("name", "KEEL_AUTH").putObject("valueFrom").putObject("secretKeyRef")
                .put("name", name).put("key", "auth");
        ObjectNode resources = container.putObject("resources");
        resources.putObject("limits").put("memory", settings.memory());
        resources.putObject("requests").put("memory", settings.memory());
        return job;
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

    private ObjectNode secret(String name, String jobUid) {
        ObjectNode secret = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Secret").put("type", "Opaque");
        ObjectNode metadata = secret.putObject("metadata").put("name", name);
        metadata.putObject("labels").put("app.kubernetes.io/name", "keel-compaction")
                .put("app.kubernetes.io/managed-by", "keel-maintenance");
        metadata.putArray("ownerReferences").addObject().put("apiVersion", "batch/v1").put("kind", "Job")
                .put("name", name).put("uid", jobUid);
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
