package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's jobs of the night as it reports them (backlog 126), to a stand-in catalog that keeps each report: each
 * compaction Job on the lowest free slot, with the data files its own {@code replace} commits took out and wrote; and
 * each table's round-the-clock work added up over the night under one id.
 */
class NightJobsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final Map<String, String> POLICY = Map.of(CompactionNeed.TARGET_FILE_SIZE, "1000");

    /** One report of a job: its id and body. */
    private record Report(UUID id, JsonNode body) {
    }

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    private final List<Report> reports = new CopyOnWriteArrayList<>();
    private final FakeClock clock = new FakeClock();
    private HttpServer server;
    private CleanupWindow window;
    private HealthReports health;

    @BeforeEach
    void start() throws IOException {
        catalog.initialize("nights", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://warehouse"));
        catalog.createNamespace(Namespace.of("ns"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.startsWith("/management/v1/maintenance/jobs/")) {
                reports.add(new Report(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)),
                        JSON.readTree(new String(body, StandardCharsets.UTF_8))));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/");
        TokenSource tokens = new TokenSource() {
            @Override
            public String token() {
                return "token";
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of();
            }
        };
        // Open all day, from noon twelve hours ago: tonight is the one under way now.
        LocalTime turn = LocalTime.ofInstant(clock.instant().minus(Duration.ofHours(12)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        window = new CleanupWindow(turn, turn, ZoneOffset.UTC);
        health = new HealthReports(new Management(uri, tokens, HttpClient.newHttpClient(), 100),
                new ScanSpans(ObservationRegistry.NOOP), window);
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(0);
        catalog.close();
    }

    @Test
    void eachJobTakesTheLowestFreeSlotAndCountsItsOwnReplaces() {
        Table a = needy("a");
        Table b = needy("b");
        Table c = needy("c");
        // A rewrite before tonight's assessment is not tonight's Job's.
        replace(a, 4);
        append(a, 5);
        StubCompactor compactor = new StubCompactor(Compactor.State.RUNNING);
        CleanupWindow.Night night = window.night(clock.instant()).orElseThrow();
        NightlyCompaction tonight = new NightlyCompaction(night, compactor, new Slots(2),
                new WorkerMetrics(new SimpleMeterRegistry(), clock), new ScanSpans(ObservationRegistry.NOOP), health,
                clock, (work, report) -> Optional.of(catalog.loadTable(TableIdentifier.of("ns", work.name()))));
        ScanReport report = new ScanReport();
        for (Table table : List.of(a, b, c)) {
            tonight.assess(work(table), table, report);
        }

        tonight.launch(clock, report);
        assertThat(compactor.order()).as("the neediest first, then by name").containsExactly(id(a), id(b));
        // a's Job rewrites eight files into two while a writer appends three more.
        replace(a, 8);
        append(a, 3);
        compactor.finish(id(a), Compactor.State.SUCCEEDED);
        tonight.launch(clock, report);
        assertThat(compactor.order()).containsExactly(id(a), id(b), id(c));
        tonight.close(report);

        assertThat(reports).extracting(r -> r.body().get("object-id").asText() + " " + r.body().get("slot").asInt()
                + " " + r.body().get("outcome").asText()).containsExactly(
                id(a) + " 1 running", id(b) + " 2 running",
                id(a) + " 1 succeeded", id(c) + " 1 running",
                id(b) + " 2 cut-off", id(c) + " 1 cut-off");
        JsonNode done = reports.get(2).body();
        assertThat(done.get("kind").asText()).isEqualTo("compaction");
        assertThat(done.get("night").asText()).isEqualTo(night.date().toString());
        assertThat(done.get("files-in").asLong()).as("eight files rewritten tonight").isEqualTo(8);
        assertThat(done.get("files-out").asLong()).as("into two").isEqualTo(2);
        assertThat(done.get("started-at").asText()).isEqualTo(reports.getFirst().body().get("started-at").asText());
        assertThat(reports.get(4).body().get("files-in").asLong()).as("b's Job committed nothing").isZero();
        assertThat(reports.get(0).id()).as("one id a Job, from its name").isEqualTo(reports.get(2).id())
                .isEqualTo(NightlyCompaction.id(work(a), night));
        assertThat(reports.get(1).id()).isNotEqualTo(reports.get(0).id());
    }

    @Test
    void aTablesWorkRoundTheClockAddsUpOverTheNight() {
        UUID table = UUID.randomUUID();
        ScanReport report = new ScanReport();
        Instant now = clock.instant();
        CleanupWindow.Night tonight = window.last(now);

        health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, table, now, now.plusSeconds(1), false,
                HealthReports.Counts.expired(3), report);
        health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, table, now.plusSeconds(60), now.plusSeconds(61), false,
                HealthReports.Counts.NONE, report);
        health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, table, now.plusSeconds(120), now.plusSeconds(122), false,
                HealthReports.Counts.expired(2), report);
        health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, table, now.plusSeconds(180), now.plusSeconds(181), true,
                HealthReports.Counts.NONE, report);
        health.ran(HealthReports.Job.DELETION, table, now.plusSeconds(200), now.plusSeconds(201), false,
                HealthReports.Counts.deleted(4, 4096), report);
        Instant tomorrow = window.on(tonight.date().plusDays(1)).opens().plusSeconds(1);
        health.ran(HealthReports.Job.SNAPSHOT_EXPIRY, table, tomorrow, tomorrow.plusSeconds(1), false,
                HealthReports.Counts.expired(1), report);

        assertThat(reports).as("a run that did nothing is not reported").hasSize(5);
        assertThat(reports.get(0).id()).as("one id for the night's sum").isEqualTo(reports.get(1).id());
        JsonNode sum = reports.get(1).body();
        assertThat(sum.get("snapshots-expired").asLong()).isEqualTo(5);
        assertThat(sum.get("started-at").asText()).isEqualTo(now.toString());
        assertThat(sum.get("ended-at").asText()).isEqualTo(now.plusSeconds(122).toString());
        assertThat(sum.get("outcome").asText()).isEqualTo("succeeded");
        assertThat(sum.get("night").asText()).isEqualTo(tonight.date().toString());
        assertThat(sum.has("slot")).isFalse();
        assertThat(reports.get(2).id()).as("the failed runs apart").isNotEqualTo(reports.get(0).id());
        assertThat(reports.get(2).body().get("outcome").asText()).isEqualTo("failed");
        assertThat(reports.get(3).body().get("kind").asText()).isEqualTo("deletion");
        assertThat(reports.get(3).body().get("bytes-deleted").asLong()).isEqualTo(4096);
        assertThat(reports.get(4).id()).as("the next night's sum is its own").isNotEqualTo(reports.get(0).id());
        assertThat(reports.get(4).body().get("night").asText()).isEqualTo(tonight.date().plusDays(1).toString());
        assertThat(reports.get(4).body().get("snapshots-expired").asLong()).isEqualTo(1);
    }

    /** A table of ten small data files, which needs compaction. */
    private Table needy(String name) {
        Table table = catalog.buildTable(TableIdentifier.of("ns", name), SCHEMA).create();
        append(table, 10);
        return table;
    }

    private static void append(Table table, int files) {
        AppendFiles append = table.newAppend();
        for (int i = 0; i < files; i++) {
            append.appendFile(data(table, 100));
        }
        append.commit();
    }

    /** Rewrites {@code count} of the table's data files into a quarter as many, as a compaction commits. */
    private static void replace(Table table, int count) {
        table.refresh();
        List<DataFile> current = new ArrayList<>();
        table.newScan().planFiles().forEach(task -> current.add(task.file()));
        var rewrite = table.newRewrite();
        current.subList(0, count).forEach(rewrite::deleteFile);
        for (int i = 0; i < count / 4; i++) {
            rewrite.addFile(data(table, 900));
        }
        rewrite.commit();
    }

    private static DataFile data(Table table, long size) {
        return DataFiles.builder(table.spec()).withPath(table.location() + "/data/" + UUID.randomUUID() + ".parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(size).withRecordCount(1).build();
    }

    private static Management.TableWork work(Table table) {
        return new Management.TableWork("lakehouse", id(table), List.of("ns"), table.name().replaceFirst(".*\\.", ""),
                table.location(), POLICY);
    }

    private static UUID id(Table table) {
        return UUID.fromString(table.uuid().toString());
    }
}
