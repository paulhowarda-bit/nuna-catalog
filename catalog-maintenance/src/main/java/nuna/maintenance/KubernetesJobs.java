package nuna.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nuna.catalog.objectstore.CaBundle;
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
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>A table on a store whose certificate a private CA signed (its storage profile names a {@code ca-bundle}, backlog
 * 21) gets that CA too (D21, amended 2026-11-20 for backlog 88): a pod mounts only ConfigMaps of its own namespace, so
 * the worker copies the bundle it trusts into a ConfigMap of the Secret's name, one certificate per key, owned by the
 * Job like the Secret, and mounts it at {@value #STORAGE_CA}. Before Spark starts, the pod builds a trust store of the
 * image's JDK CAs ({@code $JAVA_HOME/lib/security/cacerts}) and the bundle's with the JDK's keytool, and Spark's driver
 * trusts that store ({@code javax.net.ssl.trustStore} in {@code spark.driver.extraJavaOptions}): its S3 clients, on
 * the credentials the catalog vends, and its catalog client with them. That needs {@code create} on ConfigMaps too.
 * A table on a profile without a bundle gets a Job as before.
 */
final class KubernetesJobs implements Compactor {

    /** Where a Job's pod finds its storage profile's CA bundle: one certificate per file, {@code ca-<n>.pem}. */
    static final String STORAGE_CA = "/etc/nuna/storage-ca";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT = script();
    private static final String COMMAND = "printf '%s' \"$NUNA_SCRIPT\" > /tmp/compact.py && exec /opt/spark/bin/"
            + "spark-submit --master 'local[2]' --driver-memory \"$NUNA_DRIVER_MEMORY\" /tmp/compact.py";
    /**
     * The trust store Spark's driver uses with a storage CA bundle, and its password: it holds only public certificates,
     * but keytool needs one.
     */
    private static final String TRUST_STORE = "/tmp/storage-trust.p12";
    private static final String TRUST_PASSWORD = "nuna-storage-trust";
    /**
     * {@link #COMMAND} with a storage CA bundle: keytool, quiet unless it fails, copies the image's JDK CAs into
     * {@link #TRUST_STORE} and adds each certificate of the bundle; then Spark's driver JVM trusts that store. The JVM
     * options go on spark-submit's command line: the driver is already running when the script builds its session.
     */
    private static final String TRUSTING_COMMAND = "keytool_quiet() { \"$JAVA_HOME/bin/keytool\" \"$@\" -noprompt"
            + " > /tmp/keytool.log 2>&1 || { cat /tmp/keytool.log >&2; exit 1; }; };"
            + " keytool_quiet -importkeystore -srckeystore \"$JAVA_HOME/lib/security/cacerts\" -srcstorepass changeit"
            + " -destkeystore " + TRUST_STORE + " -deststoretype PKCS12 -deststorepass " + TRUST_PASSWORD + ";"
            + " for ca in " + STORAGE_CA + "/*.pem; do keytool_quiet -importcert -alias \"nuna-storage-$(basename \"$ca\""
            + " .pem)\" -file \"$ca\" -keystore " + TRUST_STORE + " -storepass " + TRUST_PASSWORD + "; done;"
            + " printf '%s' \"$NUNA_SCRIPT\" > /tmp/compact.py && exec /opt/spark/bin/spark-submit --master 'local[2]'"
            + " --driver-memory \"$NUNA_DRIVER_MEMORY\" --conf 'spark.driver.extraJavaOptions=-Djavax.net.ssl.trustStore="
            + TRUST_STORE + " -Djavax.net.ssl.trustStorePassword=" + TRUST_PASSWORD + "' /tmp/compact.py";

    /** How long a finished Job is kept: a day, for its log. It schedules nothing. */
    static final Duration KEPT = Duration.ofDays(1);

    private final WorkerSettings.Compaction settings;
    private final Clock clock;
    private final URI catalogUri;
    private final TokenSource tokens;
    private final String namespace;
    private final HttpClient http;
    /** Every storage profile's prefixes, longest first, each with its profile's CA bundle, if it names one. */
    private final Map<String, Optional<CaBundle>> storageCas;

    /**
     * @param storage the worker's storage profiles; a Job for a table under one with a {@code ca-bundle} trusts it.
     *                Each bundle is read now, so one that cannot be read stops the worker starting
     *                ({@link CaBundle#read} names the file)
     * @param clock   the worker's clock, which times each Job's deadline to the window's end
     */
    KubernetesJobs(WorkerSettings.Compaction settings, URI workerCatalogUri, TokenSource tokens,
                   Map<String, WorkerSettings.StorageProfile> storage, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.catalogUri = settings.catalogUri() != null ? settings.catalogUri() : workerCatalogUri;
        this.tokens = tokens;
        this.namespace = settings.namespace();
        Path own = settings.serviceAccount().resolve("namespace");
        if (Files.exists(own) && read(own).equals(namespace)) {
            throw new IllegalArgumentException("nuna.maintenance.compaction.namespace " + namespace + " is the worker's"
                    + " own namespace: compaction Jobs need one of their own");
        }
        this.http = HttpClient.newBuilder().sslContext(trust(settings.serviceAccount().resolve("ca.crt")))
                .connectTimeout(Duration.ofSeconds(10)).build();
        this.storageCas = storageCas(storage);
    }

    /** The Job that compacts this table on this night: {@code nuna-compact-<table id>-<the night's date>}. */
    static String jobName(Management.TableWork table, CleanupWindow.Night night) {
        return jobName(table, Run.of(night));
    }

    /**
     * The Job of this run: {@code nuna-compact-<table id>-<its key>}, the night's date or a request's {@code r} and
     * time (backlog 114), 59 characters at most: within the 63 a Job's name may have, since its pods carry it as a
     * label.
     */
    static String jobName(Management.TableWork table, Run run) {
        return "nuna-compact-" + table.id() + "-" + run.key();
    }

    @Override
    public Launch launch(Management.TableWork table, CompactionNeed need, Run run) {
        String name = jobName(table, run);
        HttpResponse<String> existing = send("GET", "/apis/batch/v1/namespaces/" + namespace + "/jobs/" + name, null);
        if (existing.statusCode() == 200) {
            return Launch.EXISTS;
        }
        expect("reading Job " + name, existing, 404);
        // A name no earlier Job's Secret has, so it never meets one Kubernetes has not collected yet. The pod waits
        // for it to appear.
        String secretName = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/apis/batch/v1/namespaces/" + namespace + "/jobs",
                job(table, need, run, secretName));
        if (created.statusCode() == 409) {
            return Launch.EXISTS;
        }
        String uid = read(expect("creating Job " + name, created, 201).body()).path("metadata").path("uid").asText();
        expect("creating Secret " + secretName, send("POST", "/api/v1/namespaces/" + namespace + "/secrets",
                secret(secretName, name, uid)), 201);
        Optional<CaBundle> storageCa = storageCa(table);
        if (storageCa.isPresent()) {
            expect("creating ConfigMap " + secretName, send("POST", "/api/v1/namespaces/" + namespace + "/configmaps",
                    storageCaConfigMap(secretName, name, uid, storageCa.get())), 201);
        }
        return Launch.STARTED;
    }

    @Override
    public State state(Management.TableWork table, Run run) {
        String name = jobName(table, run);
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

    private ObjectNode job(Management.TableWork table, CompactionNeed need, Run run, String secretName) {
        String name = jobName(table, run);
        ObjectNode job = JSON.createObjectNode().put("apiVersion", "batch/v1").put("kind", "Job");
        ObjectNode metadata = job.putObject("metadata").put("name", name);
        labels(metadata, table);
        metadata.putObject("annotations").put("nuna.catalog/table",
                table.catalog() + ":" + String.join(".", table.namespace()) + "." + table.name());
        ObjectNode spec = job.putObject("spec").put("backoffLimit", 0)
                .put("ttlSecondsAfterFinished", KEPT.toSeconds())
                .put("activeDeadlineSeconds", deadline(run));
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
        Optional<CaBundle> storageCa = storageCa(table);
        container.putArray("command").add("sh").add("-c").add(storageCa.isPresent() ? TRUSTING_COMMAND : COMMAND);
        ArrayNode env = container.putArray("env");
        env(env, "NUNA_SCRIPT", SCRIPT);
        env(env, "NUNA_CONF", sparkConf(table).toString());
        env(env, "NUNA_TABLE", JSON.valueToTree(levels(table)).toString());
        env(env, "NUNA_TABLE_UUID", table.id().toString());
        env(env, "NUNA_OPTIONS", JSON.valueToTree(need.options()).toString());
        env(env, "NUNA_DRIVER_MEMORY", settings.driverMemory());
        env.addObject().put("name", "NUNA_AUTH").putObject("valueFrom").putObject("secretKeyRef")
                .put("name", secretName).put("key", "auth");
        ObjectNode resources = container.putObject("resources");
        resources.putObject("limits").put("memory", settings.memory());
        resources.putObject("requests").put("memory", settings.memory());
        if (storageCa.isPresent()) {
            // The ConfigMap the worker creates after the Job, of the Secret's name; the pod waits for it to appear.
            container.putArray("volumeMounts").addObject().put("name", "storage-ca").put("mountPath", STORAGE_CA)
                    .put("readOnly", true);
            pod.putArray("volumes").addObject().put("name", "storage-ca").putObject("configMap")
                    .put("name", secretName);
        }
        return job;
    }

    /**
     * The CA bundle of the storage profile the table lies in, found as the worker's own storage finds a location's
     * profile ({@link Storage#of}): by its longest prefix.
     */
    private Optional<CaBundle> storageCa(Management.TableWork table) {
        String location = Storage.normalize(table.location());
        for (Map.Entry<String, Optional<CaBundle>> prefix : storageCas.entrySet()) {
            String under = prefix.getKey().endsWith("/") ? prefix.getKey() : prefix.getKey() + "/";
            if (location.startsWith(under) || location.equals(prefix.getKey())) {
                return prefix.getValue();
            }
        }
        return Optional.empty();
    }

    private static Map<String, Optional<CaBundle>> storageCas(Map<String, WorkerSettings.StorageProfile> storage) {
        Map<String, Optional<CaBundle>> byPrefix = new HashMap<>();
        storage.values().forEach(profile -> {
            Optional<CaBundle> bundle = Optional.ofNullable(profile.caBundle()).map(CaBundle::read);
            profile.prefixes().forEach(prefix -> byPrefix.put(Storage.normalize(prefix), bundle));
        });
        Map<String, Optional<CaBundle>> longestFirst = new LinkedHashMap<>();
        byPrefix.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(prefix -> longestFirst.put(prefix, byPrefix.get(prefix)));
        return longestFirst;
    }

    /** The storage profile's CA bundle for one Job, one PEM certificate per key, owned by the Job. */
    private static ObjectNode storageCaConfigMap(String name, String job, String jobUid, CaBundle bundle) {
        ObjectNode configMap = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "ConfigMap");
        ownedBy(configMap.putObject("metadata").put("name", name), job, jobUid);
        ObjectNode data = configMap.putObject("data");
        List<X509Certificate> certificates = bundle.certificates();
        for (int i = 0; i < certificates.size(); i++) {
            data.put("ca-" + i + ".pem", pem(certificates.get(i)));
        }
        return configMap;
    }

    private static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("cannot encode the CA certificate " + certificate.getSubjectX500Principal(),
                    e);
        }
    }

    /** Seconds from now to the run's deadline, rounded up: at least one, which Kubernetes requires. */
    private long deadline(Run run) {
        return Math.max(1, (Duration.between(clock.instant(), run.deadline()).toMillis() + 999) / 1000);
    }

    private ObjectNode sparkConf(Management.TableWork table) {
        Map<String, String> conf = new LinkedHashMap<>();
        conf.put("spark.ui.enabled", "false");
        conf.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.sql.catalog.nuna", "org.apache.iceberg.spark.SparkCatalog");
        conf.put("spark.sql.catalog.nuna.type", "rest");
        conf.put("spark.sql.catalog.nuna.uri", catalogUri.toString());
        conf.put("spark.sql.catalog.nuna.warehouse", table.catalog());
        conf.put("spark.sql.catalog.nuna.header.X-Iceberg-Access-Delegation", "vended-credentials");
        conf.put("spark.sql.catalog.nuna.io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
        conf.putAll(settings.sparkConf());
        return JSON.valueToTree(conf);
    }

    private ObjectNode secret(String name, String job, String jobUid) {
        ObjectNode secret = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Secret").put("type", "Opaque");
        ownedBy(secret.putObject("metadata").put("name", name), job, jobUid);
        secret.putObject("stringData").put("auth", JSON.valueToTree(tokens.clientProperties()).toString());
        return secret;
    }

    /** Labels an object the worker makes for a Job, and has the Job own it, so Kubernetes deletes it with the Job. */
    private static void ownedBy(ObjectNode metadata, String job, String jobUid) {
        metadata.putObject("labels").put("app.kubernetes.io/name", "nuna-compaction")
                .put("app.kubernetes.io/managed-by", "nuna-maintenance");
        metadata.putArray("ownerReferences").addObject().put("apiVersion", "batch/v1").put("kind", "Job")
                .put("name", job).put("uid", jobUid);
    }

    private static List<String> levels(Management.TableWork table) {
        List<String> levels = new ArrayList<>(table.namespace());
        levels.add(table.name());
        return levels;
    }

    private static void labels(ObjectNode metadata, Management.TableWork table) {
        metadata.putObject("labels").put("app.kubernetes.io/name", "nuna-compaction")
                .put("app.kubernetes.io/managed-by", "nuna-maintenance").put("nuna.catalog/table-id",
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
