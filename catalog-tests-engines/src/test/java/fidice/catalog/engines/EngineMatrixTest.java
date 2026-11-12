package fidice.catalog.engines;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import fidice.catalog.security.TestIdp;
import fidice.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * The nightly engine matrix (M8, D31), the demo: the catalog server deployed by its Helm chart
 * ({@code deploy/helm/fidice-catalog}) into a throwaway kind cluster, with PostgreSQL, Kafka and the run's S3
 * store ({@code -Ds3}: Silo or Ceph as Service {@code s3}, or real AWS) beside it, and each engine run as a
 * Kubernetes Job. Every step an engine runs prints a {@code RESULT <engine> <check> <value>} line, and every
 * refusal it is expected to make prints {@code LIMIT <engine> <check> <reason>}; each test asserts the whole
 * set, so a skipped step fails as surely as a wrong answer.
 *
 * <p>Spark runs first and leaves three tables in {@code m8.shared.spark}: v2 and v3, already evolved (four
 * rows, one with the added column set), and v3 with a deletion vector. Kafka Connect's Iceberg sink commits
 * records from a Kafka topic into a table it creates; PyIceberg and SingleStore (through its Iceberg
 * pipelines) read Spark's tables. Results, timings and every command go to {@code target/engines-run/}.
 *
 * <p>Needs Docker, kind, kubectl and Helm on the PATH. The cluster gets its own kubeconfig in the run
 * directory, so the current kubectl context is never changed. {@code -Dengines.keep-stack=true} leaves the
 * cluster up afterwards ({@code KUBECONFIG=target/engines-run/kubeconfig kubectl get pods}), with the token in
 * {@code target/engines-run/token}, to rerun one engine by hand.
 */
@Tag("heavy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineMatrixTest {

    private static final String CLUSTER = "fidice-engines";
    private static final String ISSUER = "https://idp.test/engines";
    private static final String AUDIENCE = "fidice-catalog";
    private static final String GROUP = "engine-clients";
    private static final String CATALOG_IMAGE = "fidice/catalog-server:engines";
    private static final String SPARK_IMAGE = "fidice-engines/spark:4.1.4-iceberg-1.12.0";
    private static final String KAFKA_CONNECT_IMAGE = "fidice-engines/kafka-connect:4.2.2-iceberg-1.12.0";
    private static final String PYTHON_IMAGE = "fidice-engines/python:pyiceberg-0.12.0";
    private static final String SINGLESTORE_IMAGE = "ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88";
    private static final Duration ENGINE_TIMEOUT = Duration.ofMinutes(15);

    private static Path runDir;
    private static Path engines;
    private static Cli cli;
    private static String token;
    private static String s3Endpoint;
    private static Map<String, String> placeholders;

    @BeforeAll
    static void startBase() throws Exception {
        runDir = Path.of(System.getProperty("engines.run-dir"));
        engines = Path.of(System.getProperty("engines.dir"));
        Files.createDirectories(runDir);
        RSAKey key = TestIdp.rsaKey("engines");
        Path keys = runDir.resolve("keys.json");
        Files.writeString(keys, new JWKSet(key).toPublicJWKSet().toString());
        Instant now = Instant.now();
        token = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject("engine-matrix").claim("groups", List.of(GROUP))
                .claim("azp", "engine-matrix")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                .build());
        // Kept for rerunning one engine by hand against a cluster left up with -Dengines.keep-stack=true.
        Files.writeString(runDir.resolve("token"), token);
        Files.deleteIfExists(runDir.resolve("results.txt"));
        Path log = runDir.resolve("commands.log");
        Files.deleteIfExists(log);
        Path kubeconfig = runDir.resolve("kubeconfig");
        cli = new Cli(Map.of("KUBECONFIG", kubeconfig.toAbsolutePath().toString()), log);
        TestS3.Kind store = TestS3.kindFromSystemProperty();
        placeholders = new LinkedHashMap<>(Map.of(
                "SILO_IMAGE", System.getProperty("silo.image"),
                "CEPH_IMAGE", System.getProperty("ceph.image"),
                "SINGLESTORE_IMAGE", SINGLESTORE_IMAGE));
        Map<String, String> storage = new LinkedHashMap<>();
        Map<String, String> secretEnv = new LinkedHashMap<>(Map.of("SPRING_DATASOURCE_PASSWORD", "catalog"));
        storage(store, storage, secretEnv);
        placeholders.put("S3_ENDPOINT", s3Endpoint);

        long started = System.nanoTime();
        cli.run(Duration.ofMinutes(2), null, "kind", "delete", "cluster", "--name", CLUSTER);
        cli.ok(Duration.ofMinutes(5), "kind", "create", "cluster", "--name", CLUSTER, "--wait", "2m");
        List<String> images = new ArrayList<>(List.of(System.getProperty("postgres.image"),
                System.getProperty("kafka.image"), "busybox:1.37.0"));
        if (store != TestS3.Kind.AWS) {
            images.add(System.getProperty("silo.image"));
        }
        if (store == TestS3.Kind.CEPH) {
            images.add(System.getProperty("ceph.image"));
        }
        for (String image : images) {
            cli.ok(Duration.ofMinutes(10), "docker", "pull", "-q", image);
        }
        Path repo = Path.of(System.getProperty("engines.repo-root"));
        build(CATALOG_IMAGE, repo.resolve("catalog-server"));
        build(SPARK_IMAGE, engines.resolve("images/spark"));
        build(KAFKA_CONNECT_IMAGE, engines.resolve("images/kafka-connect"));
        build(PYTHON_IMAGE, engines.resolve("images/python"));
        images.addAll(List.of(CATALOG_IMAGE, SPARK_IMAGE, KAFKA_CONNECT_IMAGE, PYTHON_IMAGE));
        load(images);
        record("cluster", "created and images loaded in " + seconds(started));

        started = System.nanoTime();
        cli.kubectl(Duration.ofMinutes(1), "create", "secret", "generic", "engine-token", "--from-literal=token=" + token);
        cli.kubectl(Duration.ofMinutes(1), "create", "configmap", "idp-keys", "--from-file=keys.json=" + keys);
        apply("infra.yaml");
        List<String> deployments = new ArrayList<>(List.of("postgres", "kafka", "idp"));
        if (store != TestS3.Kind.AWS) {
            apply("store-" + store.name().toLowerCase(Locale.ROOT) + ".yaml");
            deployments.add("s3");
        }
        for (String deployment : deployments) {
            cli.kubectl(Duration.ofMinutes(8), "rollout", "status", "deployment/" + deployment, "--timeout=8m");
        }
        if (store != TestS3.Kind.AWS) {
            apply("seed.yaml");
            cli.kubectl(Duration.ofMinutes(3), "wait", "--for=condition=complete", "job/seed", "--timeout=3m");
        }
        if (store == TestS3.Kind.CEPH) {
            StringBuilder script = new StringBuilder("set -e\n");
            for (List<String> command : TestS3.cephRoleCommands()) {
                script.append(String.join(" ", command.stream().map(arg -> "'" + arg + "'").toList())).append('\n');
            }
            Path roles = runDir.resolve("ceph-roles.sh");
            Files.writeString(roles, script, StandardCharsets.UTF_8);
            cli.ok(Duration.ofMinutes(1), roles, "kubectl", "exec", "-i", "deployment/s3", "--", "sh");
        }

        Map<String, String> config = properties(engines.resolve("k8s/catalog.properties"));
        config.put("spring.datasource.url", "jdbc:postgresql://postgres:5432/catalog");
        config.put("spring.datasource.username", "catalog");
        config.putAll(storage);
        Path values = runDir.resolve("catalog-values.json");
        Files.writeString(values, "{\"image\": {\"repository\": \"" + CATALOG_IMAGE.split(":")[0] + "\", \"tag\": \""
                + CATALOG_IMAGE.split(":")[1] + "\", \"pullPolicy\": \"Never\"},\n \"config\": " + json(config)
                + ",\n \"secretEnv\": " + json(secretEnv) + "}\n", StandardCharsets.UTF_8);
        cli.ok(Duration.ofMinutes(6), "helm", "upgrade", "--install", "catalog",
                repo.resolve("deploy/helm/fidice-catalog").toString(), "-f", values.toString(), "--wait",
                "--timeout", "5m");
        // The catalog is declared in the chart's config and created at startup; the group gets create and
        // modify on it, as ConformanceServer grants.
        cli.kubectl(Duration.ofMinutes(1), "exec", "deployment/postgres", "--", "psql", "-U", "catalog", "-d", "catalog",
                "-v", "ON_ERROR_STOP=1", "-c",
                "INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                        + " granted_by, granted_at) SELECT c.id, 'CATALOG', c.id, '" + GROUP + "', p.privilege,"
                        + " 'engine-matrix', now() FROM catalog c, (VALUES ('create'), ('modify')) p(privilege)"
                        + " WHERE c.name_norm = 'engines'");
        record("base", "stack up on " + store.name().toLowerCase(Locale.ROOT) + " and grants in " + seconds(started));
    }

    @AfterAll
    static void stopBase() {
        if (cli != null && !Boolean.getBoolean("engines.keep-stack")) {
            cli.run(Duration.ofMinutes(3), null, "kind", "delete", "cluster", "--name", CLUSTER);
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
    }

    /**
     * Kafka Connect only writes: its Iceberg sink creates the namespace and table through the catalog, commits
     * the topic's records with vended credentials, and adds a column when a record carries a new field. The
     * run script reads the results back from the catalog's REST API and drops what the sink made.
     */
    @Test
    @Order(2)
    void kafkaConnect() {
        Map<String, String> results = runEngine("kafka", () -> job("kafka-connect", KAFKA_CONNECT_IMAGE,
                List.of("bash", "/scripts/run.sh"), "1Gi"));
        assertThat(results).containsAllEntriesOf(Map.of(
                "kafka ns.create", "m8.deep.kafka",
                "kafka t.create", "3",
                "kafka t.write", "3",
                "kafka t.evolve", "4 note",
                "kafka t.drop", "ok",
                "kafka ns.drop", "ok"));
    }

    @Test
    @Order(3)
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
    }

    /**
     * SingleStore is a read-only client (D35): it ingests Iceberg tables through pipelines. It runs as a pod
     * in the cluster for this test only, with Iceberg ingest switched on.
     */
    @Test
    @Order(4)
    void singlestore() {
        Map<String, String> results = runEngine("singlestore", () -> {
            cli.ok(Duration.ofMinutes(20), "docker", "pull", "-q", SINGLESTORE_IMAGE);
            load(List.of(SINGLESTORE_IMAGE));
            apply("singlestore.yaml");
            cli.kubectl(Duration.ofMinutes(11), "rollout", "status", "deployment/singlestore", "--timeout=10m");
            singlestoreSql("enable.sql");
            return singlestoreSql("ingest.sql");
        });
        // SingleStore documents v1/v2 only (F9); 10.9.0 reads both v3 tables, the deletion vector applied.
        assertThat(results).containsAllEntriesOf(Map.of(
                "singlestore cross.v2", "4 1",
                "singlestore cross.v3", "4 1",
                "singlestore cross.v3dv", "3 8"));
    }

    /**
     * Sets the catalog's storage profile {@code s3} for the run's store into {@code storage}, and on AWS the
     * catalog's short-lived credentials into {@code secretEnv}. Silo and Ceph run in the cluster as Service
     * {@code s3}, so every client there uses the same host; on AWS the bucket and role are {@link TestS3}'s,
     * removed when the run ends.
     */
    private static void storage(TestS3.Kind kind, Map<String, String> storage, Map<String, String> secretEnv) {
        String p = "fidice.storage.profiles.s3.";
        if (kind == TestS3.Kind.AWS) {
            TestS3 s3 = TestS3.get();
            storage.putAll(s3.serverProperties("s3", s3.bucket("warehouse")));
            s3Endpoint = s3.endpoint.toString();
            AwsCredentials credentials = s3.credentials.resolveCredentials();
            secretEnv.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
            secretEnv.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
            if (credentials instanceof AwsSessionCredentials session) {
                secretEnv.put("AWS_SESSION_TOKEN", session.sessionToken());
            }
            return;
        }
        s3Endpoint = kind == TestS3.Kind.CEPH ? "http://s3:8080" : "http://s3:9000";
        storage.put("fidice.storage.require-tls", "false");
        storage.put(p + "kind", kind == TestS3.Kind.CEPH ? "CEPH" : "MINIO");
        storage.put(p + "endpoint", s3Endpoint);
        storage.put(p + "region", TestS3.REGION);
        storage.put(p + "table-root", "s3://warehouse/tables");
        storage.put(p + "allowed-prefixes", "s3://warehouse/tables");
        storage.put(p + "access-key-id", TestS3.ACCESS_KEY);
        storage.put(p + "secret-access-key", TestS3.SECRET_KEY);
        if (kind == TestS3.Kind.CEPH) {
            storage.put(p + "sts-role", TestS3.CEPH_ROLE_ARN);
        }
    }

    private static void build(String image, Path context) {
        cli.ok(Duration.ofMinutes(20), "docker", "build", "-q", "-t", image, context.toString());
    }

    private static void load(List<String> images) {
        List<String> command = new ArrayList<>(List.of("kind", "load", "docker-image", "--name", CLUSTER));
        command.addAll(images);
        cli.ok(Duration.ofMinutes(20), command.toArray(String[]::new));
    }

    /** Applies a manifest from {@code k8s/} with this run's {@code ${...}} values filled in. */
    private static void apply(String manifest) throws IOException {
        apply(manifest, placeholders);
    }

    private static void apply(String manifest, Map<String, String> values) throws IOException {
        String text = Files.readString(engines.resolve("k8s").resolve(manifest), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("${" + value.getKey() + "}", value.getValue());
        }
        Path rendered = runDir.resolve(manifest.replace(".yaml", "") + "-" + values.getOrDefault("ENGINE", "base")
                + ".yaml");
        Files.writeString(rendered, text, StandardCharsets.UTF_8);
        cli.kubectl(Duration.ofMinutes(1), "apply", "-f", rendered.toString());
    }

    /** Runs one engine as a Job, waits for it to succeed or fail, and returns its log. */
    private static String job(String name, String image, List<String> command, String memory) throws Exception {
        Map<String, String> values = new LinkedHashMap<>(placeholders);
        values.put("ENGINE", name);
        values.put("IMAGE", image);
        values.put("COMMAND", json(command));
        values.put("MEMORY", memory);
        apply("engine-job.yaml", values);
        Instant deadline = Instant.now().plus(ENGINE_TIMEOUT);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = cli.kubectl(Duration.ofMinutes(1), "get", "job", name, "-o",
                    "jsonpath={.status.succeeded}/{.status.failed}").strip();
            if (status.startsWith("1/") || status.endsWith("/1")) {
                break;
            }
            Thread.sleep(5_000);
        }
        String output = cli.run(Duration.ofMinutes(1), null, "kubectl", "logs", "job/" + name).output();
        cli.run(Duration.ofMinutes(1), null, "kubectl", "delete", "job", name, "--wait=false");
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
                StandardCharsets.UTF_8).replace("${FIDICE_TOKEN}", token).replace("${S3_ENDPOINT}", s3Endpoint),
                StandardCharsets.UTF_8);
        return cli.ok(Duration.ofMinutes(5), rendered, "kubectl", "exec", "-i", "deployment/singlestore", "--",
                "sh", "-c", "exec /usr/lib/singlestore-client/singlestore-client -h 127.0.0.1 -P 3306 -uroot"
                        + " -p$ROOT_PASSWORD --batch");
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

    private static String json(Map<String, String> map) {
        List<String> entries = new ArrayList<>();
        map.forEach((key, value) -> entries.add(quote(key) + ": " + quote(value)));
        return "{" + String.join(", ", entries) + "}";
    }

    private static String json(List<String> list) {
        return "[" + String.join(", ", list.stream().map(EngineMatrixTest::quote).toList()) + "]";
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
