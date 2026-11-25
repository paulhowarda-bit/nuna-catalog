package nuna.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.inmemory.InMemoryInputFile;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/**
 * The DR drill (D37, M8), one stack per leg.
 *
 * <p>The reconcile from storage: the DR region's database stops receiving WAL while writers keep committing (with
 * contention, so storage also holds the orphan files of lost swaps), then the primary region is lost and the DR
 * database is promoted, behind its storage. The runbook: start servers in DR, freeze the catalog (writes and cleanup
 * stop), dry-run the reconcile, apply it with the tips the audit archive records as committed ({@code accept-archived},
 * backlog 10), lift the freeze, register the table the DR database never heard of. Every acknowledged commit is then in
 * its table's history, and commits resume.
 *
 * <p>The DR store behind (D37, amended 2026-11-20; backlog 92): the primary region's storage replicates to the DR
 * region's own store through a replicator the test pauses, while the DR database keeps up; the maintenance worker's
 * DR verifier keeps each table's watermark. After the region is lost, the runbook (deploy/README.md, "Recovering in
 * the DR region") rewinds each table to the last version the DR store holds whole, giving up exactly the commits whose
 * files never arrived.
 *
 * <p>Not on {@code -Ps3=aws}: the servers lock the archive in COMPLIANCE mode, as they always do (D23), and an AWS
 * bucket so locked could not be deleted for seven years.
 */
@Tag("overnight")
class DrDrillTest {

    private static final List<String> NAMESPACE = List.of("dr", "drill", "tables");
    private static final List<String> TABLES = List.of("a", "b", "c");
    private static final String LATE = "late";
    private static final int WRITERS_PER_TABLE = 3;
    private static final String MANAGE = "/management/v1/catalogs/" + Stack.CATALOG;
    /** In the leg with the DR store behind: a table whose commits all reach the DR store before it falls behind. */
    private static final String QUIET = "quiet";
    private static final int QUIET_COMMITS = 10;
    /** The time between one writer's commits in that leg. */
    private static final Duration PACE = Duration.ofMillis(150);
    private static final Duration PATIENCE = Duration.ofMinutes(3);
    private static final Pattern DR_PASS = Pattern.compile("DR pass done: (Pass\\[[^]]*])");

    private Stack stack;

    @BeforeAll
    static void notOnAws() {
        Assumptions.assumeFalse(TestS3.kindFromSystemProperty() == TestS3.Kind.AWS,
                "the archive's COMPLIANCE lock would keep an AWS bucket for seven years");
    }

    @AfterEach
    void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void reconcileFromStorageRecoversEveryAcknowledgedCommit() throws Exception {
        stack = new Stack(true);
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
                        Appender appender = new Appender(primary, NAMESPACE, table, ledger, stack.files());
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
        Appender lateAppender = new Appender(primary, NAMESPACE, LATE, lateLedger, stack.files());
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

        // The promoted DR database has no synchronous standby, so its self-check reports pg.no-sync-standby, and a
        // server refuses to start there unless that finding is allowed (D8.2). The primary region's servers above
        // ran with nothing allowed.
        ServerProcess dr = stack.server("dr-region-1", Path.of(System.getProperty("m8.server-jar")),
                stack.postgres.drServerUrl(), "--nuna.database.allow-unsafe=pg.no-sync-standby");
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

        // The archive proves each tip once the DR server's archive consumer has the audit topic's last records: it
        // takes the primary servers' partitions when Kafka drops them, up to a session timeout after they died.
        JsonNode proven = null;
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            proven = expect(operator.send("POST", MANAGE + "/dr-reconcile", "{\"apply\":false,\"accept-archived\":true}",
                    Map.of()), 200).json();
            if (archiveProves(proven, acknowledgedTips)) {
                break;
            }
            ServerProcess.pause(Duration.ofSeconds(2));
        }
        Latencies.report("dr-drill.txt", "dry run with the archive: " + summary(proven));
        assertThat(archiveProves(proven, acknowledgedTips)).as("the archive proves each acknowledged tip: " + proven)
                .isTrue();
        assertThat(proven.get("contradicted-accepts")).isEmpty();

        // Apply, with the tips the archive records as committed.
        JsonNode applied = expect(operator.send("POST", MANAGE + "/dr-reconcile",
                "{\"apply\":true,\"accept-archived\":true}", Map.of()), 200).json();
        Latencies.report("dr-drill.txt", "apply: " + summary(applied));
        assertThat(archiveProves(applied, acknowledgedTips)).as("the applied plan ends at each acknowledged tip").isTrue();
        assertThat(applied.get("contradicted-accepts")).isEmpty();
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
            assertThat(new Appender(writers, NAMESPACE, table, after, stack.files())
                    .append(table + "-after", Duration.ofMinutes(1))).as("a commit to " + table + " after the drill")
                    .isTrue();
        }
        Latencies.report("dr-drill.txt", "acknowledged=%d (+5 late) recovered=%d; statuses primary %s, DR %s"
                .formatted(ledger.acknowledged.size(), history, primary.statusSummary(), writers.statusSummary()));
        assertThat(writers.serverErrors).as("5xx answers in DR").isEmpty();
    }

    /**
     * The DR store behind (D37, amended 2026-11-20; item 28's gate, backlog 92). One writer per table commits at a steady
     * pace while the replicator copies the primary's bucket to the DR store; the DR database keeps up. The maintenance
     * worker's verifier runs in the primary region, the DR store its replica, so each table's watermark follows the DR
     * store and reaches DR with the database. Then the replicator pauses: the writers' later commits never reach the DR
     * store, and the watermark stops at the last version whose files did. The region is lost and the DR database
     * promoted, ahead of its store. In DR: servers on the DR store, freeze, a verifier pass, the rewind (plan, then
     * apply), the walk, thaw. Each table that lost commits is back at its last version the DR store holds whole, the
     * commits it gave up are exactly those whose files never arrived, the quiet table, whole in DR, is not touched, and
     * every table reads whole in DR.
     *
     * <p>The rewind records each table's watermark at the version it writes, in its commit (D37, amended again
     * 2026-11-20: "Rewind sets watermark"; backlog 100), so no table is behind once it answers, whatever its lost tail,
     * and the walk runs right after it with no second verifier pass, as the runbook says. The walk would take each
     * table from its rewound version even with the watermark below it, judging the versions between them as the
     * verifier does ("Judge like the verifier"; backlog 28): the version a rewind writes adds nothing to the watermark.
     */
    @Test
    void aRewindGivesUpExactlyTheCommitsWhoseFilesNeverReachedTheDrStore() throws Exception {
        Assumptions.assumeTrue(TestS3.kindFromSystemProperty() == TestS3.Kind.SILO, "the leg's two stores are Silo");
        stack = Stack.withDrStore();
        ServerProcess p1 = stack.server("store-primary-1");
        stack.grantWriters();
        ServerProcess p2 = stack.server("store-primary-2");
        String writerToken = stack.token("m8-writer", Stack.WRITERS);
        String opsToken = stack.token("m8-ops", Stack.OPERATORS);
        Catalog primary = new Catalog(writerToken, p1.uri(), p2.uri());
        Catalog primaryOps = new Catalog(opsToken, p1.uri(), p2.uri());
        primary.createNamespace(NAMESPACE);
        List<String> all = new ArrayList<>(TABLES);
        all.add(QUIET);
        for (String table : all) {
            primary.createTable(NAMESPACE, table, table.equals("c") ? 3 : 2);
        }

        // Before a disaster, the verifier runs in the primary region. A table's first watermark comes from a check of
        // the whole table at its pointer, so the worker starts once the new tables' files are in the DR store, and the
        // writers once each table has its watermark.
        stack.replicator().awaitRound();
        ServerProcess primaryWorker = stack.worker("store-primary-worker", p1.uri(), false);
        awaitWatermarks(primaryOps, Map.of(), "each table's first watermark, at its pointer");

        Appender.Ledger ledger = new Appender.Ledger();
        Map<String, List<String>> committed = new HashMap<>();
        all.forEach(table -> committed.put(table, new CopyOnWriteArrayList<>()));
        AtomicBoolean writing = new AtomicBoolean(true);
        Set<String> arrived;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<?>> done = new HashMap<>();
            for (String table : all) {
                done.put(table, pool.submit(() -> {
                    Appender appender = new Appender(primary, NAMESPACE, table, ledger, stack.files());
                    for (int i = 0; table.equals(QUIET) ? i < QUIET_COMMITS : writing.get(); i++) {
                        String op = table + "-" + i;
                        if (!appender.append(op, Duration.ofMinutes(1))) {
                            throw new AssertionError("commit " + op + " was never acknowledged");
                        }
                        committed.get(table).add(op);
                        ServerProcess.pause(PACE);
                    }
                }));
            }
            ServerProcess.pause(Duration.ofSeconds(5));
            done.get(QUIET).get();
            stack.replicator().awaitRound();
            arrived = stack.replicator().pause();
            ServerProcess.pause(Duration.ofSeconds(4));
            writing.set(false);
            for (Future<?> f : done.values()) {
                f.get();
            }
        }

        // What reached the DR store: in each table, the commits whose metadata file and manifest list both arrived,
        // then the commits whose metadata file never did. The quiet table's all arrived.
        Map<String, Integer> kept = new HashMap<>();
        Map<String, Long> watermarks = new HashMap<>();
        for (String table : all) {
            List<String> ops = committed.get(table);
            String location = expect(primary.load(NAMESPACE, table, Map.of()), 200).json().get("metadata").get("location")
                    .asString();
            int whole = 0;
            while (whole < ops.size() && arrived.contains(metadataFile(ledger, ops.get(whole)))
                    && arrived.contains(Appender.manifestList(location, ledger.acknowledged.get(ops.get(whole))))) {
                whole++;
            }
            for (String op : ops.subList(whole, ops.size())) {
                assertThat(arrived).as("commits of " + table + " after its first whose files did not all arrive")
                        .doesNotContain(metadataFile(ledger, op));
            }
            if (table.equals(QUIET)) {
                assertThat(whole).as("commits of the quiet table whose files arrived").isEqualTo(ops.size());
            } else {
                assertThat(whole).as("commits of " + table + " whose files arrived").isPositive();
                assertThat(ops.size() - whole).as("commits of " + table + " whose files never arrived").isPositive();
                watermarks.put(table, version(metadataFile(ledger, ops.get(whole - 1))));
            }
            kept.put(table, whole);
        }
        Latencies.report("dr-drill.txt", "store behind: commits kept/made " + all.stream()
                .map(t -> t + "=" + kept.get(t) + "/" + committed.get(t).size()).toList());
        awaitWatermarks(primaryOps, watermarks, "the primary region's watermarks to stop at the DR store's edge");

        stack.postgres.awaitDrCaughtUp();
        primaryWorker.close();
        stack.postgres.loseRegionAndPromoteDr();
        p1.close();
        p2.close();

        ServerProcess dr = stack.drServer("store-dr-1");
        Catalog writers = new Catalog(writerToken, dr.uri());
        Catalog operator = new Catalog(opsToken, dr.uri());
        // A load reads the pointer's metadata file, which only the quiet table's is in the DR store; the rewind's plan
        // below shows where the others' pointers are.
        assertThat(location(writers, QUIET)).as("the quiet table in the DR database, which kept up")
                .isEqualTo(metadataFile(ledger, committed.get(QUIET).getLast()));

        // Freeze, then a verifier pass in DR, its replica the DR store itself: the watermarks stay where they were.
        expect(operator.send("PUT", MANAGE + "/dr-freeze", "{\"reason\":\"M8 DR drill, store behind\"}", Map.of()), 200);
        ServerProcess drWorker = stack.worker("store-dr-worker", dr.uri(), true);
        Latencies.report("dr-drill.txt", "store behind: DR verifier " + awaitDrPasses(drWorker, 2));
        awaitWatermarks(operator, watermarks, "the watermarks in DR");

        // The rewind's plan: each table that lost commits, back to its watermark, giving up exactly those commits.
        JsonNode plan = expect(operator.send("POST", MANAGE + "/dr-reconcile", "{\"rewind\":true}", Map.of()), 200)
                .json();
        Latencies.report("dr-drill.txt", "store behind: rewind plan " + rewindSummary(plan));
        Map<String, JsonNode> rewinds = byTable(plan);
        assertThat(rewinds.keySet()).as("tables above their watermark").containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(plan.get("views")).isEmpty();
        for (String table : TABLES) {
            JsonNode rewind = rewinds.get(table);
            List<String> ops = committed.get(table);
            int whole = kept.get(table);
            assertThat(rewind.has("problem")).as("problem rewinding " + table + ": " + rewind).isFalse();
            assertThat(rewind.get("from").get("metadata-location").asString())
                    .isEqualTo(metadataFile(ledger, ops.getLast()));
            assertThat(rewind.get("watermark").get("metadata-location").asString()).as("the watermark of " + table)
                    .isEqualTo(metadataFile(ledger, ops.get(whole - 1)));
            List<String> lost = new ArrayList<>();
            rewind.get("lost").forEach(commit -> {
                lost.add(commit.get("metadata-location").asString());
                assertThat(commit.get("principal").asString()).isEqualTo("m8-writer");
            });
            assertThat(lost).as("the commits " + table + " gives up").containsExactlyElementsOf(
                    ops.subList(whole, ops.size()).stream().map(op -> metadataFile(ledger, op)).toList());
        }

        // The rewind: each such table moves back in one commit, the watermark's metadata written again.
        JsonNode applied = expect(operator.send("POST", MANAGE + "/dr-reconcile", "{\"rewind\":true,\"apply\":true}",
                Map.of()), 200).json();
        Latencies.report("dr-drill.txt", "store behind: rewind " + rewindSummary(applied));
        Map<String, JsonNode> rewound = byTable(applied);
        for (String table : TABLES) {
            JsonNode moved = rewound.get(table);
            List<String> ops = committed.get(table);
            assertThat(moved.has("apply-problem")).as("rewind problem: " + moved).isFalse();
            assertThat(moved.get("rewound-to").get("version").asLong())
                    .isEqualTo(version(metadataFile(ledger, ops.getLast())) + 1);
            assertThat(location(writers, table)).isEqualTo(moved.get("rewound-to").get("metadata-location").asString());
            JsonNode metadata = expect(writers.load(NAMESPACE, table, Map.of()), 200).json().get("metadata");
            assertThat(metadata.get("refs").get("main").get("snapshot-id").asLong()).as("main of " + table)
                    .isEqualTo(ledger.acknowledged.get(ops.get(kept.get(table) - 1)));
        }

        // Each rewind recorded its table's watermark at the version it wrote (item 100), so no table is behind, with
        // no verifier pass; the walk, right after, from each pointer, has nothing to check or take.
        awaitWatermarks(operator, Map.of(), "the rewinds' watermarks", Duration.ZERO);
        Map<String, String> pointers = new HashMap<>();
        for (String table : all) {
            pointers.put(table, location(writers, table));
        }
        for (String body : List.of("{\"apply\":false}", "{\"apply\":true}")) {
            JsonNode walk = expect(operator.send("POST", MANAGE + "/dr-reconcile", body, Map.of()), 200).json();
            Latencies.report("dr-drill.txt", "store behind: walk " + body + " " + summary(walk));
            assertThat(walk.get("tables")).as("tables the walk moves or stops at: " + walk).isEmpty();
            assertThat(walk.get("unknown-locations")).isEmpty();
        }
        for (String table : all) {
            assertThat(location(writers, table)).as("pointer of " + table + " after the walk")
                    .isEqualTo(pointers.get(table));
        }

        // Thaw. Every table reads whole in DR, its history is the commits it kept, and commits resume.
        expect(operator.send("DELETE", MANAGE + "/dr-freeze", null, Map.of()), 204);
        for (String table : all) {
            readsWhole(stack.drFiles(), location(writers, table));
            List<String> keptOps = committed.get(table).subList(0, kept.get(table));
            Appender.Ledger mine = new Appender.Ledger();
            keptOps.forEach(op -> mine.acknowledged.put(op, ledger.acknowledged.get(op)));
            assertThat(Appender.verify(writers, NAMESPACE, table, mine, new HashSet<>(keptOps)))
                    .as("history of " + table + " in DR").isEqualTo(keptOps.size());
            assertThat(new Appender(writers, NAMESPACE, table, new Appender.Ledger(), stack.drFiles())
                    .append(table + "-after", Duration.ofMinutes(1))).as("a commit to " + table + " after the drill")
                    .isTrue();
        }
        Latencies.report("dr-drill.txt", "store behind: statuses primary %s, DR %s".formatted(primary.statusSummary(),
                writers.statusSummary()));
        assertThat(writers.serverErrors).as("5xx answers in DR").isEmpty();
        assertThat(operator.serverErrors).as("5xx answers to operators in DR").isEmpty();
    }

    /** The metadata file of the version the acknowledged commit {@code op} made. */
    private static String metadataFile(Appender.Ledger ledger, String op) {
        String location = ledger.locations.get(op);
        assertThat(location).as("the metadata file of commit " + op).isNotNull();
        return location;
    }

    /** The pointer version a metadata file was written for: its name's leading number. */
    private static long version(String metadataLocation) {
        String name = metadataLocation.substring(metadataLocation.lastIndexOf('/') + 1);
        return Long.parseLong(name.substring(0, name.indexOf('-')));
    }

    /**
     * Waits until the operators' readout of the tables behind their DR watermark lists, of this catalog's tables,
     * exactly those in {@code expected}, each at the watermark version it names; fails at once on a watermark above
     * the version expected, which would name a version the DR store does not hold whole.
     */
    private static void awaitWatermarks(Catalog operator, Map<String, Long> expected, String what) {
        awaitWatermarks(operator, expected, what, PATIENCE);
    }

    /** As {@link #awaitWatermarks(Catalog, Map, String)}, reading at least once and waiting at most {@code patience}. */
    private static void awaitWatermarks(Catalog operator, Map<String, Long> expected, String what, Duration patience) {
        long deadline = System.nanoTime() + patience.toNanos();
        JsonNode readout = null;
        do {
            readout = expect(operator.send("GET", "/management/v1/dr/watermarks?pageSize=200", null, Map.of()), 200)
                    .json();
            Map<String, Long> listed = new HashMap<>();
            for (JsonNode table : readout.get("tables")) {
                if (!Stack.CATALOG.equals(table.path("catalog").asString())) {
                    continue;
                }
                String name = table.get("name").asString();
                JsonNode watermark = table.path("watermark-version");
                Long version = watermark.isNumber() ? watermark.asLong() : null;
                listed.put(name, version);
                if (version != null && expected.containsKey(name)) {
                    assertThat(version).as("the watermark of " + name + ", above the DR store's last whole version")
                            .isLessThanOrEqualTo(expected.get(name));
                }
            }
            if (listed.equals(expected)) {
                return;
            }
            if (System.nanoTime() < deadline) {
                ServerProcess.pause(Duration.ofMillis(500));
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for " + what + ": expected " + expected + ", last readout "
                + readout);
    }

    /**
     * Waits until {@code passes} more of the worker's DR passes have ended, as its log says ({@code DR pass done}, at
     * DEBUG), and returns the last, which must have failed no check.
     */
    private static String awaitDrPasses(ServerProcess worker, int passes) {
        int before = drPasses(worker).size();
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (!worker.alive()) {
                throw new IllegalStateException("worker " + worker.name() + " exited; see " + worker.log());
            }
            List<String> done = drPasses(worker);
            if (done.size() >= before + passes) {
                assertThat(done.getLast()).as("the last DR pass of " + worker.name()).contains("failed=0");
                return done.getLast();
            }
            ServerProcess.pause(Duration.ofMillis(500));
        }
        throw new AssertionError("worker " + worker.name() + " ended no " + passes + " DR passes in " + PATIENCE
                + "; see " + worker.log());
    }

    /** The DR passes the worker's log records, oldest first. Read as Latin-1, which no byte breaks. */
    private static List<String> drPasses(ServerProcess worker) {
        if (!Files.exists(worker.log())) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(worker.log(), StandardCharsets.ISO_8859_1)) {
            return lines.map(DR_PASS::matcher).filter(Matcher::find).map(m -> m.group(1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the table whose metadata is at {@code metadataLocation} from {@code store} as an engine would: the metadata,
     * every snapshot's manifest list, the manifests those list and the data and delete files the manifests list. Fails
     * on the first file the store lacks.
     */
    private static void readsWhole(S3Client store, String metadataLocation) throws IOException {
        TableMetadata metadata = TableMetadataParser.fromJson(metadataLocation,
                new String(read(store, metadataLocation), StandardCharsets.UTF_8));
        FileIO io = new FileIO() {
            @Override
            public InputFile newInputFile(String path) {
                return new InMemoryInputFile(path, read(store, path));
            }

            @Override
            public OutputFile newOutputFile(String path) {
                throw new UnsupportedOperationException("only read");
            }

            @Override
            public void deleteFile(String path) {
                throw new UnsupportedOperationException("only read");
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }
        };
        for (Snapshot snapshot : metadata.snapshots()) {
            for (ManifestFile manifest : snapshot.allManifests(io)) {
                if (manifest.content() == ManifestContent.DATA) {
                    try (ManifestReader<DataFile> files = ManifestFiles.read(manifest, io, metadata.specsById())) {
                        files.forEach(file -> read(store, file.location()));
                    }
                } else {
                    try (ManifestReader<DeleteFile> files = ManifestFiles.readDeleteManifest(manifest, io,
                            metadata.specsById())) {
                        files.forEach(file -> read(store, file.location()));
                    }
                }
            }
        }
    }

    private static byte[] read(S3Client store, String location) {
        String path = location.substring("s3://".length());
        int slash = path.indexOf('/');
        try {
            return store.getObjectAsBytes(r -> r.bucket(path.substring(0, slash)).key(path.substring(slash + 1)))
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new AssertionError("the DR store lacks " + location, e);
        }
    }

    private static String rewindSummary(JsonNode report) {
        StringBuilder text = new StringBuilder("checked=" + report.get("tables-checked"));
        for (JsonNode table : report.get("tables")) {
            JsonNode path = table.get("path");
            text.append(" | ").append(path.get(path.size() - 1).asString()).append(": from v")
                    .append(table.get("from").get("version")).append(", watermark v")
                    .append(table.path("watermark").path("version")).append(", lost=").append(table.get("lost").size());
            if (table.has("rewound-to")) {
                text.append(", rewound to v").append(table.get("rewound-to").get("version"));
            }
            if (table.has("problem")) {
                text.append(", problem=").append(table.get("problem").asString());
            }
            if (table.has("apply-problem")) {
                text.append(", apply-problem=").append(table.get("apply-problem").asString());
            }
        }
        return text.toString();
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

    /** Whether each drill table's plan in {@code report} ends at its acknowledged tip, taken on the archive's proof. */
    private static boolean archiveProves(JsonNode report, Map<String, String> acknowledgedTips) {
        Map<String, JsonNode> planned = byTable(report);
        for (String table : TABLES) {
            JsonNode plan = planned.get(table);
            if (plan == null || plan.has("problem") || plan.get("steps").isEmpty()) {
                return false;
            }
            JsonNode last = plan.get("steps").get(plan.get("steps").size() - 1);
            if (!last.get("metadata-location").asString().equals(acknowledgedTips.get(table))
                    || !last.get("proof").asString().equals("archived")) {
                return false;
            }
        }
        return true;
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
            int archived = 0;
            for (JsonNode step : table.get("steps")) {
                archived += step.get("proof").asString().equals("archived") ? 1 : 0;
            }
            if (archived > 0) {
                text.append(", archived=").append(archived);
            }
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
