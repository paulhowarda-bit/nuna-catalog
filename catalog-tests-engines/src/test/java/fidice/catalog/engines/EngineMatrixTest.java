package fidice.catalog.engines;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import fidice.catalog.security.TestIdp;
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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The nightly engine matrix (M8, D31): each engine creates, writes, reads, evolves and drops v2 and v3
 * tables in a three-level namespace of the catalog server, running in docker compose on PostgreSQL, Kafka
 * and Silo. Every step an engine runs prints a {@code RESULT <engine> <check> <value>} line, and every
 * refusal it is expected to make prints {@code LIMIT <engine> <check> <reason>}; each test asserts the
 * whole set, so a skipped step fails as surely as a wrong answer.
 *
 * <p>Spark runs first and leaves three tables in {@code m8.shared.spark}: v2 and v3, already evolved (four
 * rows, one with the added column set), and v3 with a deletion vector. Every later engine reads all three,
 * SingleStore through its Iceberg pipelines. Results and timings go to {@code build/engines-run/}.
 *
 * <p>{@code -Pengines.keep-stack=true} leaves the stack up afterwards, with the token in
 * {@code build/engines-run/token}, to rerun one engine by hand.
 */
@Tag("heavy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineMatrixTest {

    private static final String ISSUER = "https://idp.test/engines";
    private static final String AUDIENCE = "fidice-catalog";
    private static final String GROUP = "engine-clients";
    private static final String SINGLESTORE = "singlestoredb-dev";
    private static final String NETWORK = "fidice-engines_default";
    private static final Duration ENGINE_TIMEOUT = Duration.ofMinutes(15);

    private static Path runDir;
    private static Compose compose;
    private static String token;

    @BeforeAll
    static void startBase() throws Exception {
        runDir = Path.of(System.getProperty("engines.run-dir"));
        Files.createDirectories(runDir.resolve("idp"));
        RSAKey key = TestIdp.rsaKey("engines");
        Files.writeString(runDir.resolve("idp/keys.json"), new JWKSet(key).toPublicJWKSet().toString());
        Instant now = Instant.now();
        token = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject("engine-matrix").claim("groups", List.of(GROUP))
                .claim("azp", "engine-matrix")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                .build());
        // Kept for rerunning one engine by hand against a stack left up with -Pengines.keep-stack=true.
        Files.writeString(runDir.resolve("token"), token);
        Files.deleteIfExists(runDir.resolve("results.txt"));
        Path log = runDir.resolve("compose.log");
        Files.deleteIfExists(log);
        compose = new Compose(Path.of(System.getProperty("engines.compose")), Map.of(
                "CATALOG_JAR", Path.of(System.getProperty("engines.catalog-jar")).toAbsolutePath().normalize().toString(),
                "RUN_DIR", runDir.toAbsolutePath().toString(),
                "FIDICE_TOKEN", token), log);
        compose.compose(Duration.ofMinutes(2), "--profile", "*", "down", "-v", "--remove-orphans");
        long started = System.nanoTime();
        compose.compose(Duration.ofMinutes(10), "up", "-d", "--wait", "catalog");
        // The catalog is declared in engines.properties and created at startup; the group gets create and
        // modify on it, as ConformanceServer grants.
        compose.compose(Duration.ofMinutes(1), "exec", "-T", "postgres", "psql", "-U", "catalog", "-d", "catalog",
                "-v", "ON_ERROR_STOP=1", "-c",
                "INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                        + " granted_by, granted_at) SELECT c.id, 'CATALOG', c.id, '" + GROUP + "', p.privilege,"
                        + " 'engine-matrix', now() FROM catalog c, (VALUES ('create'), ('modify')) p(privilege)"
                        + " WHERE c.name_norm = 'engines'");
        record("base", "stack up and grants in " + seconds(started));
    }

    @AfterAll
    static void stopBase() {
        if (compose != null && !Boolean.getBoolean("engines.keep-stack")) {
            compose.compose(Duration.ofMinutes(3), "--profile", "*", "down", "-v", "--remove-orphans");
        }
    }

    @Test
    @Order(1)
    void spark() {
        Map<String, String> results = runEngine("spark", () -> compose.compose(ENGINE_TIMEOUT,
                "run", "--rm", "--build", "spark"));
        assertThat(results).containsAllEntriesOf(lifecycle("spark", "m8.deep.spark"));
        assertThat(results).containsAllEntriesOf(Map.of(
                "spark shared.v2", "2 4 1",
                "spark shared.v3", "3 4 1",
                "spark shared.v3dv", "3 3 8 1"));
    }

    @Test
    @Order(2)
    void trino() {
        Map<String, String> results = runEngine("trino", () -> {
            try {
                compose.compose(Duration.ofMinutes(5), "up", "-d", "--wait", "trino");
                return compose.compose(ENGINE_TIMEOUT, "exec", "-T", "trino", "trino", "--catalog", "fid",
                        "--output-format", "TSV", "--file", "/scripts/trino.sql");
            } finally {
                compose.compose(Duration.ofMinutes(2), "rm", "-s", "-f", "-v", "trino");
            }
        });
        assertThat(results).containsAllEntriesOf(lifecycle("trino", "m8.deep.trino"));
        assertThat(results).containsAllEntriesOf(crossReads("trino"));
    }

    @Test
    @Order(3)
    void flink() {
        Map<String, String> results = runEngine("flink", () -> compose.compose(ENGINE_TIMEOUT,
                "run", "--rm", "--build", "flink"));
        assertThat(results).containsAllEntriesOf(lifecycle("flink", "m8.deep.flink"));
        assertThat(results).containsAllEntriesOf(crossReads("flink"));
    }

    @Test
    @Order(4)
    void pyiceberg() {
        Map<String, String> results = runEngine("pyiceberg", () -> compose.compose(ENGINE_TIMEOUT,
                "run", "--rm", "--build", "pyiceberg"));
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

    @Test
    @Order(5)
    void duckdb() {
        Map<String, String> results = runEngine("duckdb", () -> compose.compose(ENGINE_TIMEOUT,
                "run", "--rm", "--build", "duckdb"));
        assertThat(results).containsAllEntriesOf(lifecycle("duckdb", "m8.deep.duckdb"));
        assertThat(results).containsAllEntriesOf(crossReads("duckdb"));
    }

    /**
     * SingleStore is a read-only client (D35): it ingests Iceberg tables through pipelines. It runs in the
     * long-lived {@code singlestoredb-dev} container, joined to the compose network for this test only, with
     * Iceberg ingest switched on for the test and set back afterwards.
     */
    @Test
    @Order(6)
    void singlestore() {
        Map<String, String> results = runEngine("singlestore", () -> {
            String before = singlestoreSql(singlestoreScript("enable.sql", "")).lines()
                    .filter(line -> line.strip().matches("\\d+")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("enable_iceberg_ingest was not read"));
            try {
                compose.docker(Duration.ofSeconds(30), "network", "connect", NETWORK, SINGLESTORE);
                return singlestoreSql(singlestoreScript("ingest.sql", before));
            } finally {
                singlestoreSql(singlestoreScript("cleanup.sql", before.strip()));
                compose.dockerResult(Duration.ofSeconds(30), "network", "disconnect", NETWORK, SINGLESTORE);
            }
        });
        // SingleStore documents v1/v2 only (F9); 10.9.0 reads both v3 tables, the deletion vector applied.
        assertThat(results).containsAllEntriesOf(Map.of(
                "singlestore cross.v2", "4 1",
                "singlestore cross.v3", "4 1",
                "singlestore cross.v3dv", "3 8"));
    }

    /**
     * Runs a script as root in the SingleStore container, on stdin (a double quote inside a command-line
     * argument does not survive Windows); the password stays in the container's environment.
     */
    private static String singlestoreSql(Path script) {
        Compose.Result result = compose.dockerResult(Duration.ofMinutes(5), script, "exec", "-i", SINGLESTORE,
                "sh", "-c", "exec /usr/lib/singlestore-client/singlestore-client -h 127.0.0.1 -P 3306 -uroot"
                        + " -p$ROOT_PASSWORD --batch");
        if (result.exit() != 0) {
            throw new IllegalStateException("SingleStore failed on " + script.getFileName() + ":\n" + result.output());
        }
        return result.output();
    }

    /** The script with this run's token and the setting found before the test, written to the run directory. */
    private static Path singlestoreScript(String name, String ingestBefore) throws IOException {
        Path source = Path.of(System.getProperty("engines.compose")).resolveSibling("singlestore").resolve(name);
        Path rendered = runDir.resolve("singlestore-" + name);
        Files.writeString(rendered, Files.readString(source, StandardCharsets.UTF_8)
                .replace("${FIDICE_TOKEN}", token).replace("${ICEBERG_INGEST_BEFORE}", ingestBefore),
                StandardCharsets.UTF_8);
        return rendered;
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
     * Reads {@code RESULT} and {@code LIMIT} lines, whatever the engine's table format: Spark and SingleStore
     * print tabs, Trino TSV, Flink a boxed table.
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
