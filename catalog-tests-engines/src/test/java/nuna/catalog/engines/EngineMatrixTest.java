package nuna.catalog.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.security.TestIdp;
import nuna.catalog.tests.s3.TestS3;
import nuna.maintenance.MatrixCompaction;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * The nightly engine matrix (M8, D31): Spark, Kafka Connect, PyIceberg and SingleStore against the catalog, on
 * the stack it shares with the demo ({@code demo/k8s}): the catalog server deployed by its Helm chart
 * ({@code deploy/helm/nuna-catalog}, as Service {@value #RELEASE}), PostgreSQL, Kafka, kafbat, the run's S3
 * store ({@code -Ps3}: Silo or Ceph as Service {@code s3}, or real AWS) and SingleStore, with each other engine
 * run as a Kubernetes Job. Every step an engine runs prints a {@code RESULT <engine> <check> <value>} line, and every
 * refusal it is expected to make prints {@code LIMIT <engine> <check> <reason>}; each test asserts the whole
 * set, so a skipped step fails as surely as a wrong answer.
 *
 * <p>Compaction ({@link #compaction()}) runs the maintenance worker's own compaction Job (D30 (3)) on a table of
 * small files, in a namespace of its own beside {@value #NAMESPACE}. The audit table ({@link #auditTables()}) runs the
 * audit table writer (D23) from its image and chart, and Spark and PyIceberg read its table as the readers' group.
 *
 * <p>Spark runs first and leaves its tables in {@code m8.shared.spark}: v2 and v3, already evolved (four
 * rows, one with the added column set), v3 with a deletion vector, four that took DELETE, UPDATE and MERGE
 * INTO, copy-on-write or merge-on-read on v2 or v3 ({@link #rowLevelWrites()}), and those of the later cases:
 * schema and partition evolution, time travel, branches and write-audit-publish, maintenance procedures, the v2
 * to v3 upgrade, v3 types, CREATE OR REPLACE, a view and table statistics. Kafka Connect's Iceberg sink commits
 * records from a Kafka topic into a table it creates; PyIceberg reads Spark's tables and view and writes a v2
 * table of its own (delete, overwrite, upsert); SingleStore (through its Iceberg pipelines) reads Spark's tables
 * and PyIceberg's. Results, timings and every command go to {@code build/engines-run/}.
 *
 * <p>Needs Docker, kubectl and Helm on the PATH, and kind only with {@code -Pengines.cluster=kind}.
 * On its own, everything runs in namespace {@value #NAMESPACE}.
 * <ul>
 *   <li>By default the harness deploys into the existing cluster of kubectl context {@value #DEFAULT_CLUSTER},
 *       Docker Desktop's Kubernetes; {@code -Pengines.cluster=<kubectl context>} names another. Namespace
 *       {@value #NAMESPACE} is recreated at the start and deleted at the end. The cluster's nodes must see the
 *       locally built images, as Docker Desktop's do; nothing is pushed.</li>
 *   <li>{@code -Pengines.cluster=kind} creates kind cluster {@value #CLUSTER} instead, with its own kubeconfig
 *       in the run directory, so the current kubectl context is never changed, and deletes the cluster at the
 *       end.</li>
 * </ul>
 * {@code -Pengines.keep-stack=true} leaves everything up afterwards, with the token in
 * {@code build/engines-run/token}, to rerun one engine by hand, and the kafbat UI on Service {@code kafka-ui}.
 * {@code -Pengines.skip-build=true} uses the images already built instead of building them.
 *
 * <p>{@code -Pengines.stack=<namespace>} runs the engines against the stack already up in that namespace of
 * {@code -Pengines.cluster} ({@code demo/demo.sh engines} does this for the demo's): the harness deploys and
 * deletes nothing but its engines' Jobs, and signs in with the token in {@code -Pengines.token-file}, which
 * the stack's issuer signed for group {@value #GROUP} (granted create and modify on catalog {@code engines}).
 */
@Tag("overnight")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineMatrixTest {

    /** The kubectl context deployed into without {@code -Pengines.cluster}: Docker Desktop's Kubernetes. */
    private static final String DEFAULT_CLUSTER = "docker-desktop";
    /** The kind cluster made with {@code -Pengines.cluster=kind}. */
    private static final String CLUSTER = "nuna-engines";
    private static final String NAMESPACE = "nuna-engines";
    private static final String ISSUER = "https://idp.test/engines";
    private static final String AUDIENCE = "nuna-catalog";
    private static final String GROUP = "engine-clients";
    /** The maintenance worker's principal (in the catalog's nuna.security.maintenance-principals) and group. */
    private static final String WORKER = "nuna-maintenance";
    private static final String WORKERS = "maintainers";
    /**
     * The audit table writer's principal (and its chart's release) and group, and the audit table's readers' group:
     * the catalog grants both groups on catalog nuna_audit at startup ({@code nuna.audit.tables.*} in
     * {@code k8s/catalog.properties}).
     */
    private static final String AUDIT_WRITER = "nuna-audit-tables";
    private static final String AUDIT_WRITERS = "audit-writers";
    private static final String AUDITORS = "auditors";
    private static final String AUDIT_TOPIC = "nuna.catalog.audit";
    /** The chart's release, and so its Service: engines reach the catalog on http://nuna-catalog:8181. */
    private static final String RELEASE = "nuna-catalog";
    private static final String CATALOG_IMAGE = "nuna-catalog:engines";
    private static final String AUDIT_TABLES_IMAGE = "nuna-audit-tables:engines";
    private static final String SPARK_IMAGE = "nuna-engines/spark:4.1.4-iceberg-1.12.0";
    private static final String KAFKA_CONNECT_IMAGE = "nuna-engines/kafka-connect:4.2.2-iceberg-1.12.0";
    private static final String PYTHON_IMAGE = "nuna-engines/python:pyiceberg-0.12.0";
    private static final String SINGLESTORE_IMAGE = "ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88";
    private static final String KAFKA_UI_IMAGE = "kafbat/kafka-ui:v1.5.0";
    private static final Duration ENGINE_TIMEOUT = Duration.ofMinutes(15);

    private static Path runDir;
    private static Path engines;
    /** The shared stack's manifests and chart values: {@code demo/k8s}. */
    private static Path stack;
    /** Whether the engines run against a stack already up ({@code -Pengines.stack}), deploying none. */
    private static boolean existing;
    private static String namespace;
    private static Cli cli;
    /** Whether the harness made (and will delete) a kind cluster, rather than using an existing one. */
    private static boolean kind;
    /** The kubectl context everything is deployed through. */
    private static String context;
    private static String token;
    /** The run's token issuer key; none against a stack already up. */
    private static RSAKey key;
    private static String s3Endpoint;
    private static Map<String, String> placeholders;

    @BeforeAll
    static void startBase() throws Exception {
        runDir = Path.of(System.getProperty("engines.run-dir"));
        engines = Path.of(System.getProperty("engines.dir"));
        Path repo = Path.of(System.getProperty("engines.repo-root"));
        stack = repo.resolve("demo/k8s");
        Files.createDirectories(runDir);
        String existingStack = System.getProperty("engines.stack", "").strip();
        existing = !existingStack.isEmpty();
        namespace = existing ? existingStack : NAMESPACE;
        Path keys = runDir.resolve("keys.json");
        if (existing) {
            token = Files.readString(Path.of(System.getProperty("engines.token-file", "")), StandardCharsets.UTF_8)
                    .strip();
        } else {
            key = TestIdp.rsaKey("engines");
            Files.writeString(keys, new JWKSet(key).toPublicJWKSet().toString());
            Instant now = Instant.now();
            token = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject("engine-matrix").claim("groups", List.of(GROUP))
                    .claim("azp", "engine-matrix")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
        }
        // Kept for rerunning one engine by hand against a cluster left up with -Pengines.keep-stack=true.
        Files.writeString(runDir.resolve("token"), token);
        Files.deleteIfExists(runDir.resolve("results.txt"));
        Path log = runDir.resolve("commands.log");
        Files.deleteIfExists(log);
        Path kubeconfig = runDir.resolve("kubeconfig");
        String cluster = System.getProperty("engines.cluster", "").strip();
        if (cluster.isEmpty()) {
            cluster = DEFAULT_CLUSTER;
        }
        kind = cluster.equals("kind");
        if (existing && kind) {
            throw new IllegalStateException("-Pengines.stack runs in the cluster the stack is up in, not a new kind"
                    + " cluster");
        }
        context = kind ? "kind-" + CLUSTER : cluster;
        cli = new Cli(kind ? Map.of("KUBECONFIG", kubeconfig.toAbsolutePath().toString()) : Map.of(), log);
        TestS3.Kind store = TestS3.kindFromSystemProperty();
        placeholders = new LinkedHashMap<>(Map.of(
                "SILO_IMAGE", System.getProperty("silo.image"),
                "CEPH_IMAGE", System.getProperty("ceph.image"),
                "SINGLESTORE_IMAGE", SINGLESTORE_IMAGE,
                "KAFKA_UI_IMAGE", KAFKA_UI_IMAGE));
        Map<String, String> storage = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        Map<String, String> awsCredentials = new LinkedHashMap<>();
        if (existing) {
            if (store == TestS3.Kind.AWS) {
                throw new IllegalStateException("-Pengines.stack runs on the stack's own Silo or Ceph, not -Ps3=aws");
            }
            s3Endpoint = inClusterEndpoint(store);
        } else {
            storage(store, storage, secrets, awsCredentials);
        }
        placeholders.put("S3_ENDPOINT", s3Endpoint);

        long started = System.nanoTime();
        if (existing) {
            buildEngineImages();
            // The engines' Jobs read the token from Secret engine-token, as on a stack of the harness's own.
            kubectl(Duration.ofMinutes(1), "delete", "secret", "engine-token", "--ignore-not-found");
            kubectl(Duration.ofMinutes(1), "create", "secret", "generic", "engine-token", "--from-literal=token=" + token);
            record("cluster", "the stack in namespace " + namespace + " of " + context + ", engine images ready in "
                    + seconds(started));
            return;
        }
        if (kind) {
            cli.run(Duration.ofMinutes(2), null, "kind", "delete", "cluster", "--name", CLUSTER);
            cli.ok(Duration.ofMinutes(5), "kind", "create", "cluster", "--name", CLUSTER, "--wait", "2m");
        } else {
            cli.ok(Duration.ofMinutes(6), "kubectl", "--context", context, "delete", "namespace", namespace,
                    "--ignore-not-found", "--wait=true", "--timeout=5m");
        }
        cli.ok(Duration.ofMinutes(1), "kubectl", "--context", context, "create", "namespace", namespace);
        List<String> images = new ArrayList<>(List.of(System.getProperty("postgres.image"),
                System.getProperty("kafka.image"), "busybox:1.37.0", KAFKA_UI_IMAGE));
        if (store != TestS3.Kind.AWS) {
            images.add(System.getProperty("silo.image"));
        }
        if (store == TestS3.Kind.CEPH) {
            images.add(System.getProperty("ceph.image"));
        }
        for (String image : images) {
            cli.ok(Duration.ofMinutes(10), "docker", "pull", "-q", image);
        }
        // The server's own image (deploy/image/Dockerfile), from the folder holding the runnable jar.
        build(CATALOG_IMAGE, "-f", repo.resolve("deploy/image/Dockerfile").toString(),
                Path.of(System.getProperty("engines.catalog-jar")).getParent().toString());
        // The audit table writer's (deploy/image/audit-tables.Dockerfile), likewise from its runnable jar's folder.
        build(AUDIT_TABLES_IMAGE, "-f", repo.resolve("deploy/image/audit-tables.Dockerfile").toString(),
                Path.of(System.getProperty("engines.audit-tables-jar")).getParent().toString());
        buildEngineImages();
        images.addAll(List.of(CATALOG_IMAGE, AUDIT_TABLES_IMAGE, SPARK_IMAGE, KAFKA_CONNECT_IMAGE, PYTHON_IMAGE));
        load(images);
        record("cluster", (kind ? "kind cluster created" : "namespace created in " + context)
                + " and images ready in " + seconds(started));

        started = System.nanoTime();
        kubectl(Duration.ofMinutes(1), "create", "secret", "generic", "engine-token", "--from-literal=token=" + token);
        kubectl(Duration.ofMinutes(1), "create", "configmap", "idp-keys", "--from-file=keys.json=" + keys);
        apply(stack, "stack.yaml");
        List<String> deployments = new ArrayList<>(List.of("postgres", "kafka", "idp", "kafka-ui"));
        if (store != TestS3.Kind.AWS) {
            apply(stack, "store-" + store.name().toLowerCase(Locale.ROOT) + ".yaml");
            deployments.add("s3");
        }
        for (String deployment : deployments) {
            kubectl(Duration.ofMinutes(8), "rollout", "status", "deployment/" + deployment, "--timeout=8m");
        }
        if (store != TestS3.Kind.AWS) {
            apply(stack, "seed.yaml");
            kubectl(Duration.ofMinutes(3), "wait", "--for=condition=complete", "job/seed", "--timeout=3m");
        }
        if (store == TestS3.Kind.CEPH) {
            StringBuilder script = new StringBuilder("set -e\n");
            for (List<String> command : TestS3.cephRoleCommands()) {
                script.append(String.join(" ", command.stream().map(arg -> "'" + arg + "'").toList())).append('\n');
            }
            Path roles = runDir.resolve("ceph-roles.sh");
            Files.writeString(roles, script, StandardCharsets.UTF_8);
            cli.ok(Duration.ofMinutes(1), roles, kubectlCommand("exec", "-i", "deployment/s3", "--", "sh"));
        }

        // The shared stack's chart values (demo/k8s/catalog-values.yaml: one server, both catalogs, the
        // database), and this run's on top: the image tag, the issuer and storage profile s3 for the run's store,
        // its secret key in the Secret the chart reads as a config tree.
        Map<String, String> config = properties(engines.resolve("k8s/catalog.properties"));
        config.putAll(storage);
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("image", Map.of("tag", CATALOG_IMAGE.split(":")[1]));
        chart.put("config", nested(config));
        chart.put("secrets", Map.of("values", secrets));
        if (!awsCredentials.isEmpty()) {
            // The AWS SDK reads these from the environment, not from Spring's settings: a Secret of their own.
            Path secret = runDir.resolve("catalog-aws.json");
            Files.writeString(secret, json(Map.of("apiVersion", "v1", "kind", "Secret",
                    "metadata", Map.of("name", "catalog-aws"), "stringData", awsCredentials)), StandardCharsets.UTF_8);
            kubectl(Duration.ofMinutes(1), "apply", "-f", secret.toString());
            chart.put("env", awsCredentials.keySet().stream().map(name -> Map.of("name", name,
                    "valueFrom", Map.of("secretKeyRef", Map.of("name", "catalog-aws", "key", name)))).toList());
        }
        Path values = runDir.resolve("catalog-values.json");
        Files.writeString(values, json(chart) + "\n", StandardCharsets.UTF_8);
        cli.ok(Duration.ofMinutes(6), "helm", "--kube-context", context, "--namespace", NAMESPACE, "upgrade",
                "--install", RELEASE, repo.resolve("deploy/helm/nuna-catalog").toString(),
                "-f", stack.resolve("catalog-values.yaml").toString(), "-f", values.toString(),
                "--wait",
                "--timeout", "5m");
        // The catalog is declared in the chart's config and created at startup; the group gets create and
        // modify on it, as ConformanceServer grants, and the maintenance worker's group write, as a deployment
        // grants it (catalog-maintenance/README.md).
        kubectl(Duration.ofMinutes(1), "exec", "deployment/postgres", "--", "psql", "-U", "catalog", "-d", "catalog",
                "-v", "ON_ERROR_STOP=1", "-c",
                "INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                        + " granted_by, granted_at) SELECT c.id, 'CATALOG', c.id, p.grantee, p.privilege,"
                        + " 'engine-matrix', now() FROM catalog c, (VALUES ('" + GROUP + "', 'create'), ('" + GROUP
                        + "', 'modify'), ('" + WORKERS + "', 'write')) p(grantee, privilege)"
                        + " WHERE c.name_norm = 'engines'");
        record("base", "stack up on " + store.name().toLowerCase(Locale.ROOT) + " and grants in " + seconds(started));
    }

    @AfterAll
    static void stopBase() {
        if (cli == null || existing || Boolean.getBoolean("engines.keep-stack")) {
            return;
        }
        if (kind) {
            cli.run(Duration.ofMinutes(3), null, "kind", "delete", "cluster", "--name", CLUSTER);
        } else {
            cli.run(Duration.ofMinutes(6), null, "kubectl", "--context", context, "delete", "namespace", namespace,
                    jobsNamespace(), "--ignore-not-found", "--wait=true", "--timeout=5m");
        }
    }

    @Test
    @Order(1)
    void spark() {
        Map<String, String> results = runEngine("spark", () -> job("spark", SPARK_IMAGE,
                List.of("bash", "/scripts/run.sh"), "2Gi"));
        assertThat(results).containsAllEntriesOf(lifecycle("spark", "m8.deep.spark"));
        assertThat(results).containsAllEntriesOf(Map.of(
                "spark shared.v2", "2 4 1",
                "spark shared.v3", "3 4 1",
                "spark shared.v3dv", "3 3 8 1"));
        assertThat(results).containsAllEntriesOf(rowLevelWrites());
        assertThat(results).containsAllEntriesOf(evolution());
        assertThat(results).containsAllEntriesOf(travelAndBranches());
        assertThat(results).containsAllEntriesOf(maintenance());
        assertThat(results).containsAllEntriesOf(upgradeAndV3());
        assertThat(results).containsAllEntriesOf(replaceViewAndStats());
    }

    /**
     * Compaction (D30 (3)) through the maintenance worker's own Job path. The matrix's Spark makes
     * {@code m8.compaction.t}, v2 and identity-partitioned four ways, from eight inserts of a row per partition: 32
     * rows in 32 small files ({@code images/spark/compaction_matrix.py}). The worker's {@code KubernetesJobs} then
     * launches its Job for the table, in namespace {@code <namespace>-compaction} with the maintenance chart's Role
     * and Job service account, signed in to the catalog as the worker ({@value #WORKER}, group {@value #WORKERS},
     * granted write); its {@code compact.py} checks the table's id and runs rewrite_data_files: 32 files rewritten
     * into 4. After it, Spark reads the same 32 rows from 4 files in a {@code replace} snapshot, and the snapshot
     * before it still reads all 32, so no replaced file was deleted: they wait for expiry, and the catalog lists no
     * pending deletion for the table (only the worker's expiry hands files over).
     *
     * <p>Runs right after Spark, before SingleStore takes its memory. Not against a stack already up
     * ({@code -Pengines.stack}): it adds a namespace, a service account and a Role, and needs the run's token issuer
     * for the worker's token.
     */
    @Test
    @Order(2)
    void compaction() {
        assumeFalse(existing, "-Pengines.stack: the worker's principal, group and grant are the run's own stack's");
        Map<String, String> results = runEngine("compaction", () -> {
            String setup = job("compaction-setup", SPARK_IMAGE, List.of("bash", "/scripts/run.sh",
                    "/scripts/compaction_matrix.py", "setup"), "2Gi");
            String[] table = results("compaction", setup).getOrDefault("compaction table", "").split(" ");
            if (table.length != 2) {
                throw new IllegalStateException("the setup printed no table id and location:\n" + setup);
            }
            String compacted = compact(UUID.fromString(table[0]), table[1]);
            String check = job("compaction-check", SPARK_IMAGE, List.of("bash", "/scripts/run.sh",
                    "/scripts/compaction_matrix.py", "check"), "2Gi");
            return setup + "\n" + compacted + "\n" + check;
        });
        assertThat(results).containsAllEntriesOf(Map.of(
                "compaction setup", "32 32 496",
                "compaction job", "32 4",
                "compaction check", "4 32 496 replace",
                "compaction before", "32 496",
                "compaction pending", "0"));
    }

    /**
     * Sets up the worker's side of compaction as the maintenance chart does and launches the worker's Job for the
     * table: the worker's service account in the stack's namespace, and the Jobs' namespace with the chart's Role
     * and Job service account, rendered by Helm from {@code deploy/helm/nuna-maintenance}. The Jobs reach the
     * catalog and the store by the stack's Service names, through ExternalName Services of the same names. Returns
     * the Job's log with {@code RESULT compaction job <rewritten> <added>} and the table's pending deletions in the
     * catalog's database.
     */
    private static String compact(UUID id, String location) throws Exception {
        String jobs = jobsNamespace();
        cli.ok(Duration.ofMinutes(6), "kubectl", "--context", context, "delete", "namespace", jobs,
                "--ignore-not-found", "--wait=true", "--timeout=5m");
        cli.ok(Duration.ofMinutes(1), "kubectl", "--context", context, "create", "namespace", jobs);
        List<String> services = new ArrayList<>(List.of(RELEASE));
        if (s3Endpoint.startsWith("http://s3:")) {
            services.add("s3");
        }
        for (String service : services) {
            Path manifest = runDir.resolve("compaction-service-" + service + ".json");
            Files.writeString(manifest, json(Map.of("apiVersion", "v1", "kind", "Service",
                    "metadata", Map.of("name", service, "namespace", jobs),
                    "spec", Map.of("type", "ExternalName",
                            "externalName", service + "." + namespace + ".svc.cluster.local"))), StandardCharsets.UTF_8);
            cli.ok(Duration.ofMinutes(1), "kubectl", "--context", context, "apply", "-f", manifest.toString());
        }
        // Into files, as Helm's warnings would spoil its output; --show-only does not combine with --output-dir.
        Path rendered = runDir.resolve("compaction-chart");
        cli.ok(Duration.ofMinutes(1), "helm", "template", WORKER,
                Path.of(System.getProperty("engines.repo-root")).resolve("deploy/helm/nuna-maintenance").toString(),
                "--namespace", namespace, "--output-dir", rendered.toString(),
                "--set", "config.nuna.maintenance.catalog-uri=http://" + RELEASE + ":8181/",
                "--set", "config.nuna.maintenance.storage.s3.prefixes[0]=s3://warehouse/tables",
                "--set-string", "config.nuna.maintenance.cleanup-window.start=01:00",
                "--set-string", "config.nuna.maintenance.cleanup-window.end=05:00",
                "--set", "config.nuna.maintenance.cleanup-window.zone=UTC",
                "--set", "config.nuna.maintenance.compaction.image=" + SPARK_IMAGE,
                "--set", "config.nuna.maintenance.compaction.namespace=" + jobs);
        Path templates = rendered.resolve("nuna-maintenance/templates");
        kubectl(Duration.ofMinutes(1), "apply", "-f", templates.resolve("serviceaccount.yaml").toString());
        cli.ok(Duration.ofMinutes(1), "kubectl", "--context", context, "apply", "-f",
                templates.resolve("rbac.yaml").toString());

        Path account = runDir.resolve("compaction-account");
        Files.createDirectories(account);
        Files.writeString(account.resolve("token"), kubectl(Duration.ofMinutes(1), "create", "token", WORKER,
                "--duration=2h").strip());
        Files.writeString(account.resolve("namespace"), namespace);
        Files.write(account.resolve("ca.crt"), Base64.getDecoder().decode(kubectl(Duration.ofMinutes(1), "config",
                "view", "--raw", "--minify", "-o",
                "jsonpath={.clusters[0].cluster.certificate-authority-data}").strip()));
        URI apiServer = URI.create(kubectl(Duration.ofMinutes(1), "config", "view", "--minify", "-o",
                "jsonpath={.clusters[0].cluster.server}").strip());
        Instant now = Instant.now();
        String workerToken = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(WORKER).claim("groups", List.of(WORKERS))
                .claim("azp", WORKER)
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(2 * 3600)))
                .build());
        String job = MatrixCompaction.launch(SPARK_IMAGE, jobs, apiServer, account,
                URI.create("http://" + RELEASE + ":8181/"), "engines", id, List.of("m8", "compaction"), "t", location,
                workerToken);

        Instant deadline = Instant.now().plus(ENGINE_TIMEOUT);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = cli.ok(Duration.ofMinutes(1), "kubectl", "--context", context, "--namespace", jobs, "get", "job",
                    job, "-o", "jsonpath={.status.succeeded}/{.status.failed}").strip();
            if (status.startsWith("1/") || status.endsWith("/1")) {
                break;
            }
            Thread.sleep(5_000);
        }
        String log = cli.run(Duration.ofMinutes(1), null, "kubectl", "--context", context, "--namespace", jobs,
                "logs", "job/" + job).output();
        if (!status.startsWith("1/")) {
            throw new IllegalStateException("compaction Job " + job + " did not succeed (succeeded/failed: " + status
                    + "):\n" + log);
        }
        Matcher compacted = Pattern.compile("COMPACTED .*rewritten_data_files_count=(\\d+) "
                + "added_data_files_count=(\\d+)").matcher(log);
        String counts = compacted.find() ? compacted.group(1) + " " + compacted.group(2) : "none";
        String pending = kubectl(Duration.ofMinutes(1), "exec", "deployment/postgres", "--", "psql", "-U", "catalog",
                "-d", "catalog", "-tA", "-c", "SELECT count(*) FROM pending_deletion WHERE object_id = '" + id + "'")
                .strip();
        return log + "\nRESULT compaction job " + counts + "\nRESULT compaction pending " + pending + "\n";
    }

    /** The compaction Jobs' namespace: never the stack's, as the maintenance chart requires. */
    private static String jobsNamespace() {
        return namespace + "-compaction";
    }

    /**
     * Kafka Connect only writes: its Iceberg sink creates the namespace and table through the catalog, commits
     * the topic's records with vended credentials, and adds a column when a record carries a new field. The
     * run script reads the results back from the catalog's REST API and drops what the sink made.
     */
    @Test
    @Order(3)
    void kafkaConnect() {
        Map<String, String> results = runEngine("kafka", () -> job("kafka-connect", KAFKA_CONNECT_IMAGE,
                List.of("bash", "/scripts/run.sh"), "1536Mi"));
        assertThat(results).containsAllEntriesOf(Map.of(
                "kafka ns.create", "m8.deep.kafka",
                "kafka t.create", "3",
                "kafka t.write", "3",
                "kafka t.evolve", "4 note",
                "kafka t.drop", "ok",
                "kafka ns.drop", "ok"));
    }

    @Test
    @Order(4)
    void pyiceberg() {
        Map<String, String> results = runEngine("pyiceberg", () -> job("pyiceberg", PYTHON_IMAGE,
                List.of("python", "-u", "/scripts/pyiceberg_matrix.py"), "1Gi"));
        Map<String, String> expected = new LinkedHashMap<>(lifecycle("pyiceberg", "m8.deep.pyiceberg"));
        // PyIceberg 0.12 reads v3 but does not write data to it (F9): the catalog creates the table, the
        // client refuses the append, and the empty table still reads, takes the new column and drops.
        expected.put("pyiceberg v3.read", "0 0");
        expected.remove("pyiceberg v3.evolve");
        expected.put("pyiceberg v3.evolve-schema", "id,name,note");
        assertThat(results).containsAllEntriesOf(expected);
        assertThat(results).containsEntry("pyiceberg v3.write-refused",
                "ValueError: Cannot write manifest for table version: 3");
        assertThat(results).containsAllEntriesOf(crossReads("pyiceberg"));
        assertThat(results).containsAllEntriesOf(rowLevelReads("pyiceberg"));
        assertThat(results).containsAllEntriesOf(pyicebergReads());
        assertThat(results).containsAllEntriesOf(pyicebergWrites());
    }

    /**
     * The queryable audit table (D23, backlog 85): the audit table writer, from its image and its chart
     * ({@code deploy/helm/nuna-audit-tables}), writes the audit topic into {@code nuna_audit.audit.events}, and Spark
     * and PyIceberg read the table as the readers' group ({@code images/spark/audit_tables_matrix.py},
     * {@code images/python/audit_tables_matrix.py}). The catalog declares {@code nuna_audit} and, from
     * {@code nuna.audit.tables.*} in {@code k8s/catalog.properties}, grants the readers ({@value #AUDITORS}) select
     * there and the writer's group ({@value #AUDIT_WRITERS}) create and write at startup. The writer signs in with the
     * client-credentials grant, as in a deployment, against a token endpoint of the harness's own
     * ({@code k8s/token-endpoint.yaml}) that answers with a token the harness signed for it. Once the writer has
     * committed everything the topic held when it started, both readers find: format v2; the three grants the server
     * made at startup, in their grant records; the matrix's own commits in catalog engines; no row of the writer's
     * own requests; and each row at a place of its own in the topic. The writer and its token endpoint are removed
     * afterwards, before SingleStore takes its memory.
     *
     * <p>Not against a stack already up ({@code -Pengines.stack}): the audit catalog and its groups are the run's own
     * catalog settings, and the run's token issuer signs the writer's and the readers' tokens.
     */
    @Test
    @Order(5)
    void auditTables() {
        assumeFalse(existing, "-Pengines.stack: the audit catalog, its groups and the tokens are the run's own stack's");
        Map<String, String> results = runEngine("audit", () -> {
            try {
                String writer = startAuditWriter();
                String spark = job("audit-spark", SPARK_IMAGE, List.of("bash", "/scripts/run.sh",
                        "/scripts/audit_tables_matrix.py"), "2Gi");
                String pyiceberg = job("audit-pyiceberg", PYTHON_IMAGE, List.of("python", "-u",
                        "/scripts/audit_tables_matrix.py"), "1Gi");
                return writer + "\n" + spark + "\n" + pyiceberg;
            } finally {
                stopAuditWriter();
            }
        });
        Map<String, String> expected = new LinkedHashMap<>();
        for (String reader : List.of("spark", "pyiceberg")) {
            expected.put("audit " + reader + ".format", "2");
            expected.put("audit " + reader + ".seeded", "create,select,write");
            expected.put("audit " + reader + ".commits", "yes");
            expected.put("audit " + reader + ".own", "0");
            expected.put("audit " + reader + ".offsets", "unique");
        }
        assertThat(results).containsAllEntriesOf(expected);
    }

    /**
     * Starts the audit table writer as a deployment runs it, once the harness knows how far the audit topic reaches,
     * and waits until the writer has committed the topic that far. Returns the commit's log line.
     */
    private static String startAuditWriter() throws Exception {
        // From a Job of the Kafka image: a second JVM in the broker's pod would crowd its memory limit.
        String listed = job("audit-offsets", System.getProperty("kafka.image"), List.of(
                "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "kafka:9092", "--topic", AUDIT_TOPIC),
                "512Mi");
        Map<Integer, Long> end = new LinkedHashMap<>();
        Matcher offset = Pattern.compile("(?m)^" + Pattern.quote(AUDIT_TOPIC) + ":(\\d+):(\\d+)\\s*$").matcher(listed);
        while (offset.find()) {
            end.put(Integer.parseInt(offset.group(1)), Long.parseLong(offset.group(2)));
        }
        if (end.isEmpty()) {
            throw new IllegalStateException("no offsets of topic " + AUDIT_TOPIC + ":\n" + listed);
        }

        kubectl(Duration.ofMinutes(1), "delete", "secret", "audit-writer-token", "audit-token", "--ignore-not-found");
        kubectl(Duration.ofMinutes(1), "create", "secret", "generic", "audit-writer-token",
                "--from-literal=token=" + sign(AUDIT_WRITER, AUDIT_WRITERS));
        kubectl(Duration.ofMinutes(1), "create", "secret", "generic", "audit-token",
                "--from-literal=token=" + sign("avery", AUDITORS));
        Map<String, String> endpoint = new LinkedHashMap<>(placeholders);
        endpoint.put("IMAGE", PYTHON_IMAGE);
        apply(engines.resolve("k8s"), "token-endpoint.yaml", endpoint);
        kubectl(Duration.ofMinutes(4), "rollout", "status", "deployment/token-endpoint", "--timeout=3m");

        Map<String, Object> writer = new LinkedHashMap<>();
        writer.put("catalog-uri", "http://" + RELEASE + ":8181/");
        writer.put("principal", AUDIT_WRITER);
        writer.put("commit-interval", "PT10S");
        writer.put("token", Map.of("endpoint", "http://token-endpoint:8080/token", "client-id", AUDIT_WRITER,
                "audience", AUDIENCE));
        writer.put("kafka", Map.of("bootstrap-servers", "kafka:9092", "topic", AUDIT_TOPIC));
        Map<String, Object> chart = new LinkedHashMap<>();
        String[] image = AUDIT_TABLES_IMAGE.split(":");
        chart.put("image", Map.of("repository", image[0], "tag", image[1], "pullPolicy", "Never"));
        chart.put("resources", Map.of("requests", Map.of("cpu", "100m", "memory", "512Mi"),
                "limits", Map.of("memory", "512Mi")));
        chart.put("config", Map.of("nuna", Map.of("audit-tables", writer)));
        chart.put("secrets", Map.of("values", Map.of("nuna.audit-tables.token.client-secret", "engine-matrix")));
        Path values = runDir.resolve("audit-tables-values.json");
        Files.writeString(values, json(chart) + "\n", StandardCharsets.UTF_8);
        cli.ok(Duration.ofMinutes(6), "helm", "--kube-context", context, "--namespace", namespace, "upgrade",
                "--install", AUDIT_WRITER,
                Path.of(System.getProperty("engines.repo-root")).resolve("deploy/helm/nuna-audit-tables").toString(),
                "-f", values.toString(), "--wait", "--timeout", "5m");

        // The writer logs each commit: "Wrote <n> audit records to the audit table, up to offsets {0=<next>, ...}".
        Pattern committed = Pattern.compile("Wrote \\d+ audit records to the audit table, up to offsets \\{([^}]*)}");
        Path saved = runDir.resolve("audit-tables-writer.log");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        String log = "";
        while (Instant.now().isBefore(deadline)) {
            log = cli.run(Duration.ofMinutes(1), null, kubectlCommand("logs", "deployment/" + AUDIT_WRITER)).output();
            Matcher commit = committed.matcher(log);
            while (commit.find()) {
                if (reaches(commit.group(1), end)) {
                    Files.writeString(saved, log, StandardCharsets.UTF_8);
                    return commit.group();
                }
            }
            Thread.sleep(10_000);
        }
        Files.writeString(saved, log, StandardCharsets.UTF_8);
        throw new IllegalStateException("the audit table writer committed nothing up to " + end + " in 5 minutes:\n"
                + log);
    }

    /** Whether a commit's offsets, as the writer logs them ({@code 0=12, 1=7}), reach {@code end} on every partition. */
    private static boolean reaches(String offsets, Map<Integer, Long> end) {
        Map<Integer, Long> reached = new LinkedHashMap<>();
        for (String entry : offsets.split(",")) {
            String[] parts = entry.strip().split("=");
            if (parts.length == 2) {
                reached.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
            }
        }
        return end.entrySet().stream().allMatch(e -> reached.getOrDefault(e.getKey(), 0L) >= e.getValue());
    }

    /** Removes the audit table writer and its token endpoint, and waits until the writer's pod is gone. */
    private static void stopAuditWriter() {
        cli.run(Duration.ofMinutes(3), null, "helm", "--kube-context", context, "--namespace", namespace, "uninstall",
                AUDIT_WRITER);
        cli.run(Duration.ofMinutes(3), null, kubectlCommand("delete", "deployment,service", "token-endpoint",
                "--ignore-not-found", "--wait=true"));
        cli.run(Duration.ofMinutes(3), null, kubectlCommand("wait", "--for=delete", "pod", "-l",
                "app.kubernetes.io/instance=" + AUDIT_WRITER, "--timeout=2m"));
    }

    /** A token of the run's issuer for {@code subject} in {@code group}, valid for two hours. */
    private static String sign(String subject, String group) throws Exception {
        Instant now = Instant.now();
        return TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(subject).claim("groups", List.of(group))
                .claim("azp", subject)
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(2 * 3600)))
                .build());
    }

    /**
     * SingleStore reads Iceberg tables through pipelines (D35) into database {@code nuna_m8}. On its own the
     * harness starts it (the plain dev image) for this test only; an existing stack already has it.
     */
    @Test
    @Order(6)
    void singlestore() {
        Map<String, String> results = runEngine("singlestore", () -> {
            if (!existing) {
                cli.ok(Duration.ofMinutes(20), "docker", "pull", "-q", SINGLESTORE_IMAGE);
                load(List.of(SINGLESTORE_IMAGE));
                apply(stack, "singlestore.yaml");
            }
            kubectl(Duration.ofMinutes(11), "rollout", "status", "deployment/singlestore", "--timeout=10m");
            singlestoreSql("enable.sql");
            return singlestoreSql("ingest.sql");
        });
        // SingleStore documents v1/v2 only (F9); 10.9.0 reads both v3 tables, the deletion vector applied.
        assertThat(results).containsAllEntriesOf(Map.of(
                "singlestore cross.v2", "4 1",
                "singlestore cross.v3", "4 1",
                "singlestore cross.v3dv", "3 8"));
        assertThat(results).containsAllEntriesOf(rowLevelReads("singlestore"));
        // By pipeline, mapping the current column names: the rows written before the rename keep their notes.
        assertThat(results).containsAllEntriesOf(Map.of(
                "singlestore cross.schema", "3 6 n1,n2,n3 60 x",
                "singlestore cross.partition", "10 55",
                "singlestore cross.maint", "4 17",
                "singlestore cross.upgrade", "2 5",
                "singlestore cross.pyiceberg-writes", "4 13 a,c2,d2,e"));
    }

    /**
     * Sets the catalog's storage profile {@code s3} for the run's store: its settings into {@code storage}, its
     * secret key into {@code secrets}, and on AWS the catalog's short-lived credentials, as the AWS SDK's
     * environment variables, into {@code awsCredentials}. Silo and Ceph run in the cluster as Service
     * {@code s3}, so every client there uses the same host; on AWS the bucket and role are {@link TestS3}'s,
     * removed when the run ends.
     */
    private static void storage(TestS3.Kind kind, Map<String, String> storage, Map<String, String> secrets,
                                Map<String, String> awsCredentials) {
        String p = "nuna.storage.profiles.s3.";
        if (kind == TestS3.Kind.AWS) {
            TestS3 s3 = TestS3.get();
            storage.putAll(s3.serverProperties("s3", s3.bucket("warehouse")));
            s3Endpoint = s3.endpoint.toString();
            AwsCredentials credentials = s3.credentials.resolveCredentials();
            awsCredentials.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
            awsCredentials.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
            if (credentials instanceof AwsSessionCredentials session) {
                awsCredentials.put("AWS_SESSION_TOKEN", session.sessionToken());
            }
            return;
        }
        s3Endpoint = inClusterEndpoint(kind);
        storage.put("nuna.storage.require-tls", "false");
        storage.put(p + "kind", kind == TestS3.Kind.CEPH ? "CEPH" : "MINIO");
        storage.put(p + "endpoint", s3Endpoint);
        storage.put(p + "region", TestS3.REGION);
        storage.put(p + "table-root", "s3://warehouse/tables");
        storage.put(p + "allowed-prefixes", "s3://warehouse/tables");
        storage.put(p + "access-key-id", TestS3.ACCESS_KEY);
        secrets.put(p + "secret-access-key", TestS3.SECRET_KEY);
        if (kind == TestS3.Kind.CEPH) {
            storage.put(p + "sts-role", TestS3.CEPH_ROLE_ARN);
        }
    }

    /** Silo's or Ceph's endpoint in the cluster: Service {@code s3}. */
    private static String inClusterEndpoint(TestS3.Kind kind) {
        return kind == TestS3.Kind.CEPH ? "http://s3:8080" : "http://s3:9000";
    }

    private static void buildEngineImages() {
        build(SPARK_IMAGE, engines.resolve("images/spark").toString());
        build(KAFKA_CONNECT_IMAGE, engines.resolve("images/kafka-connect").toString());
        build(PYTHON_IMAGE, engines.resolve("images/python").toString());
    }

    /**
     * Builds an image from {@code source} (a folder, after {@code -f <Dockerfile>} when the Dockerfile is
     * elsewhere), unless -Pengines.skip-build=true asks to use the ones already built.
     */
    private static void build(String image, String... source) {
        if (Boolean.getBoolean("engines.skip-build")) {
            return;
        }
        List<String> command = new ArrayList<>(List.of("docker", "build", "-q", "-t", image));
        command.addAll(List.of(source));
        cli.ok(Duration.ofMinutes(20), command.toArray(String[]::new));
    }

    /** Loads images into the kind cluster; an existing cluster's nodes must already see them. */
    private static void load(List<String> images) {
        if (!kind) {
            return;
        }
        List<String> command = new ArrayList<>(List.of("kind", "load", "docker-image", "--name", CLUSTER));
        command.addAll(images);
        cli.ok(Duration.ofMinutes(20), command.toArray(String[]::new));
    }

    /** Applies a manifest from {@code dir} with this run's {@code ${...}} values filled in. */
    private static void apply(Path dir, String manifest) throws IOException {
        apply(dir, manifest, placeholders);
    }

    private static void apply(Path dir, String manifest, Map<String, String> values) throws IOException {
        String text = Files.readString(dir.resolve(manifest), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("${" + value.getKey() + "}", value.getValue());
        }
        Path rendered = runDir.resolve(manifest.replace(".yaml", "") + "-" + values.getOrDefault("ENGINE", "base")
                + ".yaml");
        Files.writeString(rendered, text, StandardCharsets.UTF_8);
        kubectl(Duration.ofMinutes(1), "apply", "-f", rendered.toString());
    }

    /** Runs one engine as a Job, waits for it to succeed or fail, and returns its log. */
    private static String job(String name, String image, List<String> command, String memory) throws Exception {
        Map<String, String> values = new LinkedHashMap<>(placeholders);
        values.put("ENGINE", name);
        values.put("IMAGE", image);
        values.put("COMMAND", json(command));
        values.put("MEMORY", memory);
        apply(engines.resolve("k8s"), "engine-job.yaml", values);
        Instant deadline = Instant.now().plus(ENGINE_TIMEOUT);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = kubectl(Duration.ofMinutes(1), "get", "job", name, "-o",
                    "jsonpath={.status.succeeded}/{.status.failed}").strip();
            if (status.startsWith("1/") || status.endsWith("/1")) {
                break;
            }
            // A pod that can never start (a missing Secret, an image the node does not have) waits, not fails.
            String waiting = kubectl(Duration.ofMinutes(1), "get", "pods", "-l", "job-name=" + name, "-o",
                    "jsonpath={.items[*].status.containerStatuses[*].state.waiting.reason}");
            if (waiting.contains("CreateContainerConfigError") || waiting.contains("ErrImageNeverPull")) {
                status = "cannot start: " + waiting.strip();
                break;
            }
            Thread.sleep(5_000);
        }
        String output = cli.run(Duration.ofMinutes(1), null, kubectlCommand("logs", "job/" + name)).output();
        cli.run(Duration.ofMinutes(1), null, kubectlCommand("delete", "job", name, "--wait=false"));
        if (!status.startsWith("1/")) {
            throw new IllegalStateException("job " + name + " did not succeed (succeeded/failed: " + status + "):\n"
                    + output);
        }
        return output;
    }

    /**
     * Runs a script as root in the SingleStore pod, on stdin (a double quote inside a command-line argument does
     * not survive Windows); the password stays in the pod's environment.
     */
    private static String singlestoreSql(String name) throws IOException {
        Path rendered = runDir.resolve("singlestore-" + name);
        Files.writeString(rendered, Files.readString(engines.resolve("k8s/singlestore").resolve(name),
                StandardCharsets.UTF_8).replace("${NUNA_TOKEN}", token).replace("${S3_ENDPOINT}", s3Endpoint),
                StandardCharsets.UTF_8);
        return cli.ok(Duration.ofMinutes(5), rendered, kubectlCommand("exec", "-i", "deployment/singlestore", "--",
                "sh", "-c", "exec /usr/lib/singlestore-client/singlestore-client -h 127.0.0.1 -P 3306 -uroot"
                        + " -p$ROOT_PASSWORD --batch"));
    }

    /** {@code kubectl} in the run's context and namespace; fails unless it exits 0. */
    private static String kubectl(Duration timeout, String... args) {
        return cli.ok(timeout, kubectlCommand(args));
    }

    private static String[] kubectlCommand(String... args) {
        List<String> command = new ArrayList<>(List.of("kubectl", "--context", context, "--namespace", namespace));
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    /** A {@code .properties} file's entries, in file order. */
    private static Map<String, String> properties(Path file) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            properties.put(trimmed.substring(0, separator).strip(), trimmed.substring(separator + 1).strip());
        }
        return properties;
    }

    /**
     * Spring property names to the nested form the chart's {@code config} takes: {@code a.b.c=v} becomes
     * {@code {a: {b: {c: v}}}}, and {@code a.list[0].c=v} puts a map in a list. Only {@code catalog.properties}
     * and the storage settings come here, and none of them is a list of plain values.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, String> properties) {
        Map<String, Object> root = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            Map<String, Object> node = root;
            String[] parts = name.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                int bracket = part.indexOf('[');
                if (bracket < 0) {
                    node = (Map<String, Object>) node.computeIfAbsent(part, k -> new LinkedHashMap<String, Object>());
                } else {
                    List<Object> list = (List<Object>) node.computeIfAbsent(part.substring(0, bracket),
                            k -> new ArrayList<>());
                    int index = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                    while (list.size() <= index) {
                        list.add(new LinkedHashMap<String, Object>());
                    }
                    node = (Map<String, Object>) list.get(index);
                }
            }
            node.put(parts[parts.length - 1], value);
        });
        return root;
    }

    /** JSON for maps, lists, strings and numbers. */
    private static String json(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                List<String> entries = new ArrayList<>();
                map.forEach((key, entry) -> entries.add(quote(key.toString()) + ": " + json(entry)));
                yield "{" + String.join(", ", entries) + "}";
            }
            case List<?> list -> "[" + String.join(", ", list.stream().map(EngineMatrixTest::json).toList()) + "]";
            case Number number -> number.toString();
            default -> quote(value.toString());
        };
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The checks every engine makes on its own tables, as {@code "<engine> <check>" -> value}. */
    private static Map<String, String> lifecycle(String engine, String namespace) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(engine + " ns.create", namespace);
        for (String version : List.of("2", "3")) {
            String v = "v" + version;
            expected.put(engine + " " + v + ".create", version);
            expected.put(engine + " " + v + ".read", "3 6");
            expected.put(engine + " " + v + ".evolve", "4 1");
            expected.put(engine + " " + v + ".drop", "ok");
        }
        expected.put(engine + " ns.drop", "ok");
        return expected;
    }

    /**
     * Spark's shared tables, read by another engine: v2 and v3 with four rows, one with the added column, and
     * v3 with a deletion vector, three rows left (ids 1, 3, 4).
     */
    private static Map<String, String> crossReads(String engine) {
        return Map.of(engine + " cross.v2", "4 1", engine + " cross.v3", "4 1", engine + " cross.v3dv", "3 8");
    }

    /**
     * Spark's row-level tables {@code t_v2_cow}, {@code t_v2_mor}, {@code t_v3_cow} and {@code t_v3_mor}, each
     * with its {@code write.delete.mode}, {@code write.update.mode} and {@code write.merge.mode} set to
     * copy-on-write or merge-on-read. Four rows go into one data file ({@code .files}), so no statement can be
     * met by dropping a whole file from metadata. Then DELETE id 2, UPDATE id 3's note to u, and MERGE INTO that
     * sets id 4's note to m and inserts id 5 with n: four rows left (ids 1, 3, 4, 5, summing to 13) with notes
     * u, m, n in id order; a MERGE that missed id 4 would leave x, which count and sum alone would not notice.
     *
     * <p>The mechanism shows in the current snapshot's delete files ({@code .deletes}: kinds, files, rows
     * deleted) and in the snapshot operations from first to current ({@code .ops}). Copy-on-write rewrites the
     * data file each time, an overwrite, and leaves no delete files. Merge-on-read only adds delete files against
     * it: position deletes in Parquet on v2, a deletion vector in Puffin on v3. Its DELETE adds nothing else, a
     * delete; its UPDATE and MERGE also add the changed rows, overwrites. One delete file is left holding all
     * three deleted rows, as Spark replaces a data file's earlier file-scoped deletes with merged ones (and a v3
     * data file can have only one deletion vector).
     */
    private static Map<String, String> rowLevelWrites() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String version : List.of("2", "3")) {
            for (String mode : List.of("cow", "mor")) {
                String table = "spark shared.v" + version + mode;
                boolean copy = mode.equals("cow");
                expected.put(table + ".files", "1");
                expected.put(table, version + " 4 13 u,m,n");
                expected.put(table + ".deletes", copy ? "none 0 0"
                        : version.equals("2") ? "position-parquet 1 3" : "dv-puffin 1 3");
                expected.put(table + ".ops", copy ? "append,overwrite,overwrite,overwrite"
                        : "append,delete,overwrite,overwrite");
            }
        }
        return expected;
    }

    /** Spark's row-level tables read by another engine: rows, sum of ids, notes in id order. */
    private static Map<String, String> rowLevelReads(String engine) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String table : List.of("v2cow", "v2mor", "v3cow", "v3mor")) {
            expected.put(engine + " cross." + table, "4 13 u,m,n");
        }
        return expected;
    }

    /**
     * Schema and partition evolution in Spark (v2 tables). {@code t_schema}: two rows, then a rename, a dropped
     * column, int widened to bigint, a struct field added and a column moved first, then a row in the new layout;
     * columns are read by field ID, so all three rows keep their notes and struct values. {@code t_partition}: two
     * rows unpartitioned (spec 0), by {@code cat} (spec 1), by {@code bucket(4, id)} (spec 2), after the field is
     * dropped (a v2 spec with no fields is spec 0's twin, so spec 0 again), and after {@code WRITE ORDERED BY id}
     * with the rows sent as 10, 9: ten rows, the last file in sort order 1 with 9 before 10.
     */
    private static Map<String, String> evolution() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("spark shared.schema.columns", "struct<name:string,id:bigint,note:string,info:struct<a:int,b:string>>");
        expected.put("spark shared.schema", "3 6 n1,n2,n3 60 x");
        expected.put("spark shared.partition", "10 55");
        expected.put("spark shared.partition.specs", "0:6,1:2,2:2");
        expected.put("spark shared.partition.sorted", "id ASC NULLS FIRST 2 9,10");
        return expected;
    }

    /**
     * Time travel, branches and write-audit-publish in Spark. {@code t_travel}: ids 1-3, then id 4; the first
     * snapshot read by id, by its commit time and by tag {@code first}. {@code t_branch}: a write to branch
     * {@code audit} leaves main at three rows, {@code fast_forward} moves main to the branch's snapshot and tag
     * {@code v1} stays at three. {@code t_wap}: with {@code write.wap.enabled} and {@code spark.wap.id} the insert
     * is staged, main unchanged; its parent is main's snapshot, so {@code cherrypick_snapshot} fast-forwards main to
     * it (two snapshots, no new one).
     */
    private static Map<String, String> travelAndBranches() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String by : List.of("version", "timestamp", "tag")) {
            expected.put("spark shared.travel." + by, "3 6");
        }
        expected.put("spark shared.travel.current", "4 10");
        expected.put("spark shared.branch.write", "3 4");
        expected.put("spark shared.branch.ff", "main same 4 3");
        expected.put("spark shared.branch.refs", "audit:BRANCH,main:BRANCH,v1:TAG");
        expected.put("spark shared.wap.staged", "3 1");
        expected.put("spark shared.wap.publish", "4 fast-forward 2");
        return expected;
    }

    /**
     * Maintenance procedures on {@code t_maint}, v2 merge-on-read: three one-file appends (ids 1-6) and a DELETE
     * of ids 1 and 3, one file-scoped position-delete file each. {@code rewrite_position_delete_files} rewrites the
     * two into two. {@code rewrite_data_files} compacts three data files into one, and its commit removes the
     * replaced files' file-scoped deletes (two, in the snapshot summary; the procedure's own
     * {@code removed_delete_files_count} counts only its optional dangling-delete pass, so 0). That leaves four
     * data manifests (one live, three holding only deleted entries) and a delete manifest; {@code rewrite_manifests}
     * rewrites the four into one and skips the lone delete manifest. {@code expire_snapshots} commits (seven
     * snapshots to one) but deletes nothing: vended credentials carry no s3:DeleteObject (D25), and the three
     * replaced data files are still in storage. {@code remove_orphan_files} cannot list through Hadoop (no s3
     * scheme), finds none older than its 3-day default through S3FileIO, and refuses a cut-off within 24 hours.
     */
    private static Map<String, String> maintenance() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("spark shared.maint.setup", "3 position-parquet 2 2");
        expected.put("spark shared.maint.position-deletes", "2 2 position-parquet 2 2");
        expected.put("spark shared.maint.data-files", "3 1 0 0 2 1 none 0 0");
        expected.put("spark shared.maint.manifests", "4 1 4 1 1 1");
        expected.put("spark shared.maint.expire", "7 1 0 0 0 0");
        expected.put("spark shared.maint.expired-files", "3 3");
        expected.put("spark shared.maint.orphans-hadoop", "remove_orphan_files lists the table location through"
                + " Hadoop's FileSystem, which has no s3 scheme here (S3FileIO only); prefix_listing => true lists"
                + " through S3FileIO");
        expected.put("spark shared.maint.orphans", "0");
        expected.put("spark shared.maint.orphans-recent", "the procedure refuses an older_than within 24 hours (the"
                + " default is 3 days back), so files this run left unreferenced, the expired ones above, are never"
                + " candidates");
        expected.put("spark shared.maint", "4 17");
        return expected;
    }

    /**
     * The v2 to v3 upgrade and v3 features in Spark. {@code t_upgrade}: v2 merge-on-read, four rows in one file,
     * DELETE id 2 (a Parquet position delete), format-version 3, DELETE id 3: a v3 data file has one deletion
     * vector, so the old delete is merged into it (two rows) and the Parquet file removed. {@code t_lineage}: row
     * IDs 0-2 at sequence number 1; a copy-on-write UPDATE of id 2 keeps every row ID and moves only that row's
     * last-updated sequence number. Default values are refused both ways; variant, geometry and geography (the
     * last two behind {@code spark.sql.geospatial.enabled}) write and read back.
     */
    private static Map<String, String> upgradeAndV3() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("spark shared.upgrade.v2", "2 position-parquet 1 1");
        expected.put("spark shared.upgrade", "3 2 5 dv-puffin 1 2");
        expected.put("spark shared.upgrade.ops", "append,delete,delete");
        expected.put("spark v3.lineage.insert", "0,1,2 1,1,1");
        expected.put("spark v3.lineage.update", "0,1,2 1,2,1");
        expected.put("spark v3.default.create",
                "CREATE TABLE with a DEFAULT: the Iceberg table does not support column default values");
        expected.put("spark v3.default.add",
                "Cannot add column d since setting default values in Spark is currently unsupported");
        expected.put("spark v3.variant", "2 3 x,y");
        expected.put("spark v3.geo.disabled",
                "Spark 4.1 refuses geometry and geography columns unless spark.sql.geospatial.enabled=true");
        String point = "0101000000000000000000F03F0000000000000040";
        expected.put("spark v3.geo", "1 " + point + " 4326 " + point + " 4326");
        return expected;
    }

    /**
     * CREATE OR REPLACE TABLE ... AS SELECT, a view and table statistics in Spark. {@code t_replace} (v3, two
     * snapshots, four rows) replaced by two rows: a third snapshot, an overwrite with no parent, and the old
     * snapshot still reads four rows. A second replace whose query changes id from bigint to string is accepted
     * (D11, amended 2026-11-12: a replace starts main afresh, as Iceberg's reference handler takes it): a fourth
     * snapshot, one row, id a string. A replace that makes a nullable column required is accepted too (D11,
     * widened 2026-11-13): {@code t_replace_required}, id INT with rows 1 and null, replaced by
     * {@code SELECT 1 AS id} through a handle with use-nullable-query-schema=false, has two snapshots, one row,
     * id required. View {@code v_rows} on {@code t_v2}: ids 1 and 2, then (by CREATE OR REPLACE VIEW, as
     * ALTER VIEW ... AS is refused) the row with a note; a second view is created and dropped.
     * {@code compute_table_stats} writes a statistics file into {@code t_v2}'s metadata folder.
     */
    private static Map<String, String> replaceViewAndStats() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("spark replace", "3 3 2 30 overwrite");
        expected.put("spark replace.history", "4 10");
        expected.put("spark replace.retype", "4 1 string");
        expected.put("spark replace.required", "2 1 required");
        expected.put("spark view.create", "2 3");
        expected.put("spark view.alter-as", "ALTER VIEW ... AS is not supported. Use CREATE OR REPLACE VIEW instead");
        expected.put("spark view.replace", "1 4");
        expected.put("spark view.list", "v_rows");
        expected.put("spark stats", "metadata");
        return expected;
    }

    /**
     * PyIceberg 0.12 on the tables and view Spark left: field IDs kept across the rename (note 3, info.b 7);
     * three specs with the default back at 0 and sort order 1; the tag and the snapshot as of the first commit's
     * time; main fast-forwarded to the branch; one snapshot left after expiry; the upgraded table at v3;
     * next-row-id 6 after three appended rows and three rewritten by the update (first row IDs 0 and 3); the
     * replaced table after Spark's retyping replace: four snapshots, one row, id a string, its current snapshot a
     * root; the view's two versions, columns, comment and
     * Spark SQL; and the statistics file of {@code t_v2}'s current snapshot, one theta sketch per column with its
     * distinct count. PyIceberg 0.12 cannot load a table with a variant column, nor parse Iceberg Java's
     * {@code geometry(OGC:CRS84)} (it expects the CRS quoted).
     */
    private static Map<String, String> pyicebergReads() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("pyiceberg cross.schema", "name,id,note,info 3 6 n1,n2,n3 60 x");
        expected.put("pyiceberg cross.schema.ids", "note=3 info.b=7 id=long");
        expected.put("pyiceberg cross.partition", "10 55 3 0 1");
        expected.put("pyiceberg cross.travel", "3 3 4");
        expected.put("pyiceberg cross.branch", "4 3 same");
        expected.put("pyiceberg cross.wap", "4 2");
        expected.put("pyiceberg cross.maint", "4 17 1");
        expected.put("pyiceberg cross.upgrade", "3 2 5");
        expected.put("pyiceberg cross.lineage", "3 6 0,3");
        expected.put("pyiceberg cross.variant", "PyIceberg 0.12 has no variant type: Unsupported field type: 'variant'");
        expected.put("pyiceberg cross.geo", "PyIceberg 0.12 parses only a quoted CRS, geometry('...'); Iceberg Java"
                + " writes geometry(OGC:CRS84): Could not parse geometry(OGC:CRS84) into a GeometryType");
        expected.put("pyiceberg cross.replace", "4 1 string root");
        expected.put("pyiceberg cross.view", "2 2 id,name,note rows with a note note-filter");
        expected.put("pyiceberg cross.stats", "1 current apache-datasketches-theta-v1 id:4,name:4,note:1");
        return expected;
    }

    /**
     * PyIceberg's own writes on a v2 table it creates, {@code m8.shared.pyiceberg.t_writes}: four rows appended,
     * {@code delete("id = 2")}, {@code overwrite} of id 3 with c2, {@code upsert} of id 4 (to d2) and id 5. All
     * copy-on-write: a delete that matches part of a file commits an overwrite; overwrite is that delete plus an
     * append; upsert overwrites the changed row away, then appends it and the new row.
     */
    private static Map<String, String> pyicebergWrites() {
        return Map.of(
                "pyiceberg writes", "2 4 13 a,c2,d2,e",
                "pyiceberg writes.upsert", "1 1",
                "pyiceberg writes.ops", "append,overwrite,overwrite,append,overwrite,append,append");
    }

    private interface Run {
        String output() throws Exception;
    }

    private static Map<String, String> runEngine(String engine, Run run) {
        long started = System.nanoTime();
        String output;
        try {
            output = run.output();
        } catch (Exception e) {
            record(engine, "FAILED after " + seconds(started) + ": " + e.getMessage().lines().findFirst().orElse(""));
            throw new AssertionError(engine + " failed", e);
        }
        Map<String, String> results = results(engine, output);
        List<String> lines = new ArrayList<>();
        results.forEach((check, value) -> lines.add("  " + check + " = " + value));
        record(engine, "ran in " + seconds(started) + "\n" + String.join("\n", lines));
        return results;
    }

    /**
     * Reads {@code RESULT} and {@code LIMIT} lines, whatever the engine's output format: Spark and SingleStore
     * print tabs, the others spaces.
     */
    static Map<String, String> results(String engine, String output) {
        Map<String, String> results = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String cleaned = line.replace('|', ' ').replace('\t', ' ').replace("\"", "").strip();
            String[] tokens = cleaned.split("\\s+", 4);
            if (tokens.length >= 3 && (tokens[0].equals("RESULT") || tokens[0].equals("LIMIT"))
                    && tokens[1].equals(engine)) {
                results.put(engine + " " + tokens[2], tokens.length == 4 ? tokens[3].strip().replaceAll("\\s+", " ") : "");
            }
        }
        return results;
    }

    private static String seconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000 + " s";
    }

    private static synchronized void record(String engine, String text) {
        try {
            Files.writeString(runDir.resolve("results.txt"), engine + ": " + text + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        System.out.println("[engines] " + engine + ": " + text);
    }
}
