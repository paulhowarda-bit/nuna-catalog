package keel.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The reconcile-from-storage DR drill (D37, M8). The DR region's database stops receiving WAL while writers
 * keep committing (with contention, so storage also holds the orphan files of lost swaps), then the primary
 * region is lost and the DR database is promoted, behind its storage. The runbook: start servers in DR,
 * freeze the catalog (writes and cleanup stop), dry-run the reconcile, apply it accepting the tips the
 * writers were told committed, lift the freeze, register the table the DR database never heard of. Every
 * acknowledged commit is then in its table's history, and commits resume.
 */
@Tag("overnight")
class DrDrillTest {

    private static final List<String> NAMESPACE = List.of("dr", "drill", "tables");
    private static final List<String> TABLES = List.of("a", "b", "c");
    private static final String LATE = "late";
    private static final int WRITERS_PER_TABLE = 3;
    private static final String MANAGE = "/management/v1/catalogs/" + Stack.CATALOG;

    private static Stack stack;

    @BeforeAll
    static void start() {
        stack = new Stack(true);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void reconcileFromStorageRecoversEveryAcknowledgedCommit() throws Exception {
        ServerProcess p1 = stack.server("dr-primary-1");
        stack.grantWriters();
        ServerProcess p2 = stack.server("dr-primary-2");
        String writerToken = stack.token("m8-writer", Stack.WRITERS);
        Catalog primary = new Catalog(writerToken, p1.uri(), p2.uri());
        primary.createNamespace(NAMESPACE);
        for (String table : TABLES) {
            primary.createTable(NAMESPACE, table, table.equals("c") ? 3 : 2);
        }

        Appender.Ledger ledger = new Appender.Ledger();
        Set<String> ops = ConcurrentHashMap.newKeySet();
        AtomicBoolean running = new AtomicBoolean(true);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (String table : TABLES) {
                for (int w = 0; w < WRITERS_PER_TABLE; w++) {
                    int writer = w;
                    done.add(pool.submit(() -> {
                        Appender appender = new Appender(primary, NAMESPACE, table, ledger);
                        for (int i = 0; running.get(); i++) {
                            String op = table + "-w" + writer + "-" + i;
                            ops.add(op);
                            appender.append(op, Duration.ofMinutes(2));
                        }
                    }));
                }
            }
            ServerProcess.pause(Duration.ofSeconds(5));
            stack.postgres.awaitDrCaughtUp();
            stack.postgres.cutDrReplication();
            ServerProcess.pause(Duration.ofSeconds(10));
            running.set(false);
            for (Future<?> f : done) {
                f.get();
            }
        }
        primary.createTable(NAMESPACE, LATE, 2);
        Appender.Ledger lateLedger = new Appender.Ledger();
        Appender lateAppender = new Appender(primary, NAMESPACE, LATE, lateLedger);
        for (int i = 0; i < 5; i++) {
            assertThat(lateAppender.append(LATE + "-" + i, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(ledger.unknown).as("commits the primary region left unknown").isEmpty();
        Map<String, String> acknowledgedTips = new HashMap<>();
        for (String table : List.of("a", "b", "c", LATE)) {
            acknowledgedTips.put(table, location(primary, table));
        }

        stack.postgres.loseRegionAndPromoteDr();
        p1.close();
        p2.close();

        ServerProcess dr = stack.server("dr-region-1", Path.of(System.getProperty("m8.server-jar")),
                stack.postgres.drServerUrl());
        Catalog writers = new Catalog(writerToken, dr.uri());
        Catalog operator = new Catalog(stack.token("m8-ops", Stack.OPERATORS), dr.uri());
        Map<String, String> behind = new HashMap<>();
        for (String table : TABLES) {
            behind.put(table, location(writers, table));
            assertThat(behind.get(table)).as("table " + table + " in the DR database is behind its storage")
                    .isNotEqualTo(acknowledgedTips.get(table));
        }
        assertThat(writers.load(NAMESPACE, LATE, Map.of()).status()).as("the late table in DR").isEqualTo(404);

        // Freeze: writes are refused and cleanup is off everywhere in the catalog.
        expect(operator.send("PUT", MANAGE + "/dr-freeze", "{\"reason\":\"M8 DR drill\"}", Map.of()), 200);
        Catalog.Answer refused = writers.commit(NAMESPACE, "a", """
                {"requirements":[],"updates":[{"action":"set-properties","updates":{"drill":"x"}}]}""",
                java.util.UUID.randomUUID().toString());
        assertThat(refused.status()).as("a commit under the freeze").isEqualTo(403);
        // Reading the policy needs describe, which operators do not hold (M7); the writers read it.
        JsonNode policy = expect(writers.send("GET", MANAGE + "/maintenance-policy", null, Map.of()), 200).json();
        for (String key : List.of("snapshot-expiry.enabled", "orphan-removal.enabled", "compaction.enabled",
                "manifest-rewrite.enabled")) {
            JsonNode setting = policy.path("resolved").path(key);
            assertThat(setting.path("value").asString()).as(key + " under the freeze").isEqualTo("false");
            assertThat(setting.path("reason").asString()).isEqualTo("frozen for DR reconcile");
        }

        // Dry run: proven steps for each table, the acknowledged tip among the tips, the late table unknown.
        JsonNode dryRun = expect(operator.send("POST", MANAGE + "/dr-reconcile", "{\"apply\":false}", Map.of()), 200)
                .json();
        Latencies.report("dr-drill.txt", "dry run: " + summary(dryRun));
        Map<String, JsonNode> planned = byTable(dryRun);
        assertThat(planned.keySet()).containsExactlyInAnyOrderElementsOf(TABLES);
        for (String table : TABLES) {
            JsonNode plan = planned.get(table);
            assertThat(plan.has("problem")).as("problem planning " + table + ": " + plan).isFalse();
            assertThat(plan.get("from").get("metadata-location").asString()).isEqualTo(behind.get(table));
            List<String> tips = new ArrayList<>();
            plan.get("tips").forEach(t -> tips.add(t.asString()));
            assertThat(tips).as("tips of " + table).contains(acknowledgedTips.get(table));
        }
        List<String> unknown = new ArrayList<>();
        dryRun.get("unknown-locations").forEach(l -> unknown.add(l.asString()));
        assertThat(unknown).as("table locations the DR database does not know").hasSize(1);
        assertThat(acknowledgedTips.get(LATE)).startsWith(unknown.getFirst());

        // Apply, accepting the tips the writers were told committed.
        StringBuilder accept = new StringBuilder();
        for (String table : TABLES) {
            accept.append(accept.isEmpty() ? "" : ",").append('"').append(acknowledgedTips.get(table)).append('"');
        }
        JsonNode applied = expect(operator.send("POST", MANAGE + "/dr-reconcile",
                "{\"apply\":true,\"accept\":[" + accept + "]}", Map.of()), 200).json();
        Latencies.report("dr-drill.txt", "apply: " + summary(applied));
        assertThat(applied.get("unused-accepts")).isEmpty();
        for (JsonNode table : applied.get("tables")) {
            assertThat(table.has("apply-problem")).as("apply problem: " + table).isFalse();
        }
        for (String table : TABLES) {
            assertThat(location(writers, table)).as("pointer of " + table).isEqualTo(acknowledgedTips.get(table));
        }
        JsonNode again = expect(operator.send("POST", MANAGE + "/dr-reconcile", "{\"apply\":false}", Map.of()), 200)
                .json();
        assertThat(byTable(again).values()).as("tables still behind after the apply")
                .allSatisfy(t -> assertThat(t.get("steps")).isEmpty());

        // Lift the freeze, register the table DR never heard of, and check every acknowledged commit.
        expect(operator.send("DELETE", MANAGE + "/dr-freeze", null, Map.of()), 204);
        expect(writers.send("POST", "/v1/" + Stack.CATALOG + "/namespaces/" + Catalog.ns(NAMESPACE) + "/register",
                "{\"name\":\"" + LATE + "\",\"metadata-location\":\"" + acknowledgedTips.get(LATE) + "\"}", Map.of()), 200);
        int history = 0;
        for (String table : TABLES) {
            Appender.Ledger mine = new Appender.Ledger();
            ledger.acknowledged.forEach((op, id) -> {
                if (op.startsWith(table + "-")) {
                    mine.acknowledged.put(op, id);
                }
            });
            history += Appender.verify(writers, NAMESPACE, table, mine, ops);
        }
        assertThat(history).isEqualTo(ledger.acknowledged.size());
        Set<String> lateOps = new HashSet<>(lateLedger.acknowledged.keySet());
        assertThat(Appender.verify(writers, NAMESPACE, LATE, lateLedger, lateOps)).isEqualTo(5);

        for (String table : List.of("a", "b", "c", LATE)) {
            Appender.Ledger after = new Appender.Ledger();
            assertThat(new Appender(writers, NAMESPACE, table, after).append(table + "-after", Duration.ofMinutes(1)))
                    .as("a commit to " + table + " after the drill").isTrue();
        }
        Latencies.report("dr-drill.txt", "acknowledged=%d (+5 late) recovered=%d; statuses primary %s, DR %s"
                .formatted(ledger.acknowledged.size(), history, primary.statusSummary(), writers.statusSummary()));
        assertThat(writers.serverErrors).as("5xx answers in DR").isEmpty();
    }

    private static String location(Catalog catalog, String table) {
        Catalog.Answer answer = catalog.load(NAMESPACE, table, Map.of());
        assertThat(answer.status()).as("load " + table).isEqualTo(200);
        return answer.json().get("metadata-location").asString();
    }

    private static Catalog.Answer expect(Catalog.Answer answer, int status) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(status);
        return answer;
    }

    private static Map<String, JsonNode> byTable(JsonNode report) {
        Map<String, JsonNode> tables = new HashMap<>();
        for (JsonNode table : report.get("tables")) {
            JsonNode path = table.get("path");
            tables.put(path.get(path.size() - 1).asString(), table);
        }
        return tables;
    }

    private static String summary(JsonNode report) {
        StringBuilder text = new StringBuilder("checked=" + report.get("tables-checked"));
        for (JsonNode table : report.get("tables")) {
            JsonNode path = table.get("path");
            text.append(" | ").append(path.get(path.size() - 1).asString()).append(": from v")
                    .append(table.get("from").get("version")).append(", steps=").append(table.get("steps").size())
                    .append(", tips=").append(table.get("tips").size());
            if (table.has("steps-applied")) {
                text.append(", applied=").append(table.get("steps-applied"));
            }
            if (table.has("problem")) {
                text.append(", problem=").append(table.get("problem").asString());
            }
        }
        return text.append(" | unknown=").append(report.get("unknown-locations").size()).toString();
    }
}
