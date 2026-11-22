package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A table's maintenance on request as the worker runs it (backlog 114), against a stand-in catalog that keeps each
 * report: a requested compaction starts in the day, outside the cleanup window, whatever its assessment says, its Job
 * named after the time asked and stopping at the request's deadline, in one of the slots the night's Jobs use; a
 * requested orphan scan stops at the request's deadline. Each report names the request, which clears it.
 */
class RequestedWorkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final Map<String, String> POLICY = Map.of(CompactionNeed.TARGET_FILE_SIZE, "1000",
            "orphan-removal.older-than", "P3D");
    private static final Duration DEADLINE = Duration.ofHours(2);

    /** One report the worker sent: its path and body. */
    private record Report(String path, JsonNode body) {
    }

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    private final List<Report> reports = new CopyOnWriteArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final StubCompactor compactor = new StubCompactor(Compactor.State.RUNNING);
    private final List<String> recorded = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private CleanupWindow window;
    private HealthReports health;

    @BeforeEach
    void start() throws IOException {
        catalog.initialize("requests", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://warehouse"));
        catalog.createNamespace(Namespace.of("ns"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            reports.add(new Report(exchange.getRequestURI().getPath(),
                    JSON.readTree(new String(body, StandardCharsets.UTF_8))));
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
        // Open for an hour from six hours on: now is the day, outside the window.
        LocalTime opens = LocalTime.ofInstant(clock.instant().plus(Duration.ofHours(6)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        window = new CleanupWindow(opens, opens.plusHours(1), ZoneOffset.UTC);
        health = new HealthReports(new Management(uri, tokens, HttpClient.newHttpClient(), 100),
                new ScanSpans(ObservationRegistry.NOOP), window);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aRequestedCompactionStartsInTheDayAndStopsAtTheRequestsDeadline() {
        assertThat(window.night(clock.instant())).as("the window is closed").isEmpty();
        Table table = table("t", 1, 900);
        Instant asked = clock.instant().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        RequestedWork requested = requested(new Slots(2), storage(List.of(), Duration.ZERO));
        ScanReport report = new ScanReport();
        Instant before = clock.instant();

        requested.compact(List.of(request(table, "compaction", asked)), report);

        assertThat(compactor.launched).hasSize(1);
        Compactor.Run run = compactor.launched.getFirst().run();
        assertThat(run.key()).isEqualTo("r" + Long.toString(asked.toEpochMilli(), 36));
        assertThat(run.deadline()).as("the request's deadline, not the window's end")
                .isBetween(before.plus(DEADLINE), clock.instant().plus(DEADLINE));
        assertThat(KubernetesJobs.jobName(work(table), run).length()).as("a Job name Kubernetes takes")
                .isLessThanOrEqualTo(63);
        assertThat(compactor.launched.getFirst().need().needed()).as("one file: no need, run all the same")
                .isFalse();
        assertThat(report.compactionsStarted).isEqualTo(1);
        assertThat(paths()).containsExactly(tables(table) + "/assessment", tables(table) + "/compaction",
                jobs(table, run));
        JsonNode running = reports.get(2).body();
        assertThat(running.get("requested-at").asText()).as("names the request, which clears it")
                .isEqualTo(asked.toString());
        assertThat(running.get("outcome").asText()).isEqualTo("running");
        assertThat(running.get("slot").asInt()).isEqualTo(1);
        assertThat(running.get("night").asText()).isEqualTo(window.last(before).date().toString());

        requested.compact(List.of(), report);
        assertThat(reports).as("still running: nothing new").hasSize(3);
        compactor.finish(id(table), Compactor.State.SUCCEEDED);
        requested.compact(List.of(), report);

        assertThat(paths().subList(3, 5)).containsExactly(tables(table) + "/compaction", jobs(table, run));
        JsonNode ended = reports.get(4).body();
        assertThat(ended.get("outcome").asText()).isEqualTo("succeeded");
        assertThat(ended.get("requested-at").asText()).isEqualTo(asked.toString());
        assertThat(ended.get("started-at")).isEqualTo(running.get("started-at"));
    }

    @Test
    void requestedJobsShareTheSlotsAndATableHasOneJobAtOnce() {
        Table a = table("a", 1, 900);
        Table b = table("b", 1, 900);
        Slots slots = new Slots(1);
        RequestedWork requested = requested(slots, storage(List.of(), Duration.ZERO));
        Instant asked = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ScanReport report = new ScanReport();

        requested.compact(List.of(request(a, "compaction", asked), request(b, "compaction", asked)), report);
        assertThat(compactor.order()).as("one slot: b waits").containsExactly(id(a));
        assertThat(slots.holds(id(a))).isTrue();
        requested.compact(List.of(request(a, "compaction", asked.plusSeconds(1)), request(b, "compaction", asked)),
                report);
        assertThat(compactor.order()).as("a's Job still runs").containsExactly(id(a));

        compactor.finish(id(a), Compactor.State.CUT_OFF);
        requested.compact(List.of(request(b, "compaction", asked)), report);

        assertThat(compactor.order()).containsExactly(id(a), id(b));
        assertThat(report.compactionsCutOff).isEqualTo(1);
        assertThat(slots.holds(id(a))).isFalse();
        assertThat(slots.holds(id(b))).isTrue();
    }

    @Test
    void withCompactionOffARequestWaits() {
        Table table = table("t", 1, 900);
        RequestedWork requested = new RequestedWork(Compactor.NONE, new Slots(2), DEADLINE,
                storage(List.of(), Duration.ZERO), window, health, new ScanSpans(ObservationRegistry.NOOP), clock,
                (work, report) -> Optional.of(catalog.loadTable(TableIdentifier.of("ns", work.name()))),
                (work, loaded, files, report) -> recorded.addAll(files));

        requested.compact(List.of(request(table, "compaction", clock.instant())), new ScanReport());

        assertThat(reports).as("nothing started, nothing reported: the request still waits").isEmpty();
    }

    @Test
    void aRequestedOrphanScanRunsInTheDayAndStopsAtTheRequestsDeadline() {
        Table table = table("t", 1, 900);
        String orphan = table.location() + "/data/orphan.parquet";
        Instant asked = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ScanReport report = new ScanReport();
        int[] between = {0};

        requested(new Slots(2), storage(List.of(orphan), Duration.ZERO))
                .scanOrphans(List.of(request(table, "orphan-scan", asked)), () -> between[0]++, report);

        assertThat(recorded).containsExactly(orphan);
        assertThat(report.orphanScans).isEqualTo(1);
        assertThat(between[0]).as("the night's Jobs are started before it").isEqualTo(1);
        assertThat(reports).extracting(r -> r.body().get("kind").asText() + " " + r.body().get("outcome").asText()
                + " " + r.body().get("requested-at").asText()).containsExactly(
                "orphan-scan running " + asked, "orphan-scan succeeded " + asked);

        reports.clear();
        recorded.clear();
        requested(new Slots(2), storage(List.of(orphan, orphan + "2"), DEADLINE.plusMinutes(1)))
                .scanOrphans(List.of(request(table, "orphan-scan", asked)), () -> { }, report);

        assertThat(recorded).as("cut short: nothing recorded").isEmpty();
        assertThat(report.orphanScansCutShort).isEqualTo(1);
        assertThat(reports.getLast().body().get("outcome").asText()).isEqualTo("cut-off");
    }

    private RequestedWork requested(Slots slots, Storage storage) {
        return new RequestedWork(compactor, slots, DEADLINE, storage, window, health,
                new ScanSpans(ObservationRegistry.NOOP), clock,
                (work, report) -> Optional.of(catalog.loadTable(TableIdentifier.of("ns", work.name()))),
                (work, loaded, files, report) -> recorded.addAll(files));
    }

    /**
     * A store listing these files, long ago, and the table's own; each file listed moves the clock on by
     * {@code each}.
     */
    private Storage storage(List<String> files, Duration each) {
        return new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                List<FileInfo> all = new ArrayList<>();
                files.forEach(file -> all.add(new FileInfo(file, 100, 0)));
                return () -> {
                    Iterator<FileInfo> listed = all.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return listed.hasNext();
                        }

                        @Override
                        public FileInfo next() {
                            clock.advance(each);
                            return listed.next();
                        }
                    };
                };
            }

            @Override
            public void delete(Collection<String> locations) {
                throw new AssertionError("a scan deletes nothing");
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of();
            }

            @Override
            public void close() {
            }
        };
    }

    private Table table(String name, int files, long size) {
        Table table = catalog.buildTable(TableIdentifier.of("ns", name), SCHEMA).create();
        var append = table.newAppend();
        for (int i = 0; i < files; i++) {
            append.appendFile(DataFiles.builder(table.spec())
                    .withPath(table.location() + "/data/" + UUID.randomUUID() + ".parquet")
                    .withFormat(FileFormat.PARQUET).withFileSizeInBytes(size).withRecordCount(1).build());
        }
        append.commit();
        return table;
    }

    private static Management.RequestWork request(Table table, String job, Instant at) {
        return new Management.RequestWork(work(table), job, at, "ana");
    }

    private static Management.TableWork work(Table table) {
        return new Management.TableWork("lakehouse", id(table), List.of("ns"), table.name().replaceFirst(".*\\.", ""),
                table.location(), POLICY);
    }

    private List<String> paths() {
        return reports.stream().map(Report::path).toList();
    }

    private static String tables(Table table) {
        return "/management/v1/maintenance/tables/" + id(table);
    }

    private static String jobs(Table table, Compactor.Run run) {
        return "/management/v1/maintenance/jobs/" + RequestedWork.id(work(table), run);
    }

    private static UUID id(Table table) {
        return UUID.fromString(table.uuid().toString());
    }
}
