package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's side of the catalog's refs (D33, item 108): it warns of and ends branches as the work list's refs
 * section says, before the tables; it records for deletion only files no ref reaches, and when the catalog answers that
 * a ref was made since, it checks the files again against the refs the catalog hands back; and it checks each due file
 * against the refs made since it was recorded, completing one a ref reaches without deleting it.
 *
 * <p>Needs only Java: the catalog is a stand-in on the JDK's HTTP server, which serves a table of Iceberg's in-memory
 * catalog, and the worker reads its metadata through Iceberg's in-memory files.
 */
class WorkerRefsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final String WORK = "/management/v1/maintenance/work";

    /** One answer of the stand-in. */
    private record Answer(int status, String body) {
    }

    private final InMemoryCatalog tables = new InMemoryCatalog();
    private final FakeClock clock = new FakeClock();
    /** Each call the worker made: its method, path and query. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** Each record of deletions the worker sent, taken or refused. */
    private final List<JsonNode> records = new CopyOnWriteArrayList<>();
    /** Each completion of deletions the worker sent, by object. */
    private final Map<String, List<String>> completed = new ConcurrentHashMap<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final List<String> listed = new CopyOnWriteArrayList<>();
    /** Each section of the work list, by name. */
    private final Map<String, String> sections = new ConcurrentHashMap<>();
    /** The answer to a warning or end of a branch, by its route. */
    private final Map<String, Answer> refRoutes = new ConcurrentHashMap<>();
    private volatile Function<JsonNode, Answer> onRecord = body -> new Answer(201, "{}");
    private Table table;
    private HttpServer server;
    private Worker worker;

    @BeforeEach
    void start() throws IOException {
        tables.initialize("refs", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://warehouse"));
        tables.createNamespace(Namespace.of("ns"));
        table = tables.buildTable(TableIdentifier.of("ns", "t"), SCHEMA)
                .withLocation("mem://warehouse/" + UUID.randomUUID() + "/t").create();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
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
        // A window open round the clock, as FailedOrphanScanTest's: the orphan scan is due at once.
        LocalTime turn = LocalTime.ofInstant(clock.instant().minus(Duration.ofHours(12)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        worker = new Worker(new Management(uri, tokens, HttpClient.newHttpClient(), 100),
                new Catalogs(uri, tokens, Map.of(CatalogProperties.FILE_IO_IMPL, InMemoryFileIO.class.getName()),
                        TraceHeaders.NONE), new Listed(), Compactor.NONE, clock,
                new CleanupWindow(turn, turn, ZoneOffset.UTC), Duration.ofDays(1), Duration.ofHours(2), 2,
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @AfterEach
    void stop() {
        worker.close();
        server.stop(0);
    }

    @Test
    void branchesAreWarnedOfAndEndedBeforeTheTablesAreRead() {
        sections.put("refs", "{\"refs\":[" + expiry("near", "warn") + "," + expiry("warned", "warn") + ","
                + expiry("past", "end") + "," + expiry("ended", "end") + "," + expiry("failing", "end") + "]}");
        refRoutes.put(id("near") + "/warn", new Answer(200, "{\"sent\":true}"));
        refRoutes.put(id("warned") + "/warn", new Answer(200, "{\"sent\":false}"));
        refRoutes.put(id("past") + "/end", new Answer(204, null));
        refRoutes.put(id("ended") + "/end", new Answer(404, "{\"error\":{\"message\":\"gone\","
                + "\"type\":\"NoSuchRefException\",\"code\":404}}"));
        refRoutes.put(id("failing") + "/end", new Answer(500, "{\"error\":{\"message\":\"down\","
                + "\"type\":\"Internal\",\"code\":500}}"));

        ScanReport report = worker.runOnce();

        assertThat(report.refsWarned).as("the warning this scan sent").isEqualTo(1);
        assertThat(report.refsEnded).as("the branch this scan ended").isEqualTo(1);
        assertThat(report.failuresByKind).containsExactly(Map.entry(ScanReport.Failure.REFS, 1));
        assertThat(calls).filteredOn(call -> call.startsWith("POST /management/v1/maintenance/refs/"))
                .containsExactly(refCall("near", "warn"), refCall("warned", "warn"), refCall("past", "end"),
                        refCall("ended", "end"), refCall("failing", "end"));
        List<String> reads = calls.stream().filter(call -> call.startsWith("GET " + WORK)).toList();
        assertThat(reads.getFirst()).contains("section=refs");
        assertThat(calls.indexOf(refCall("failing", "end")))
                .isLessThan(calls.indexOf(reads.stream().filter(read -> read.contains("section=tables")).findFirst()
                        .orElseThrow()));
    }

    /**
     * An orphan the work list's refs do not reach, and one a tag made since the list was read reaches: the catalog
     * refuses the record, hands back the tag, and the worker records only the file the tag does not reach.
     */
    @Test
    void aRecordTheCatalogRefusesIsCheckedAgainstTheRefsItHandsBack() {
        Orphans orphans = orphans();
        String tag = UUID.randomUUID().toString();
        onRecord = body -> names(body, tag) ? new Answer(201, "{}") : refused(ref(tag, "tag", orphans.cut()));

        ScanReport report = worker.runOnce();

        assertThat(report.failures).isEmpty();
        assertThat(report.orphansFound).isEqualTo(2);
        assertThat(records).as("refused, then taken").hasSize(2);
        assertThat(strings(records.get(0).path("refs"))).as("the work list named no ref").isEmpty();
        assertThat(strings(records.get(0).path("locations"))).containsExactlyInAnyOrder(orphans.reached(),
                orphans.unreached());
        assertThat(strings(records.get(1).path("refs"))).containsExactly(tag);
        assertThat(strings(records.get(1).path("locations"))).containsExactly(orphans.unreached());
        assertThat(report.filesRecorded).isEqualTo(1);
        assertThat(report.kept).containsExactly(Map.entry(Management.RefKind.TAG, 1));
    }

    /** A table whose refs change at every read is left until the next scan, after {@link Worker#REF_READS} reads. */
    @Test
    void aTableWhoseRefsKeepChangingWaitsForTheNextScan() {
        Orphans orphans = orphans();
        onRecord = body -> refused(ref(UUID.randomUUID().toString(), "tag", orphans.cut()));

        ScanReport report = worker.runOnce();

        assertThat(records).hasSize(Worker.REF_READS);
        assertThat(report.failuresByKind).containsExactly(Map.entry(ScanReport.Failure.ORPHAN_SCAN, 1));
        assertThat(report.filesRecorded).isZero();
    }

    /**
     * Due files of a live table that refs made since they were recorded name: the one a ref reaches is completed and
     * not deleted, the one it does not is deleted. A dropped table's such files wait. Files no ref made since names are
     * deleted as ever.
     */
    @Test
    void dueFilesAreCheckedAgainstTheRefsMadeSinceTheyWereRecorded() {
        DataFile a = file("a");
        table.newAppend().appendFile(a).commit();
        String cut = metadata();
        table.newDelete().deleteFile(a).commit();
        String unreached = table.location() + "/data/x.parquet";
        String unnamed = table.location() + "/data/y.parquet";
        String dropped = UUID.randomUUID().toString();
        String tag = ref(UUID.randomUUID().toString(), "tag", cut);
        sections.put("deletions", "{\"deletions\":[" + due("d1", table.uuid().toString(), a.location(), tag, true)
                + "," + due("d2", table.uuid().toString(), unreached, tag, true) + ","
                + due("d3", table.uuid().toString(), unnamed, null, true) + ","
                + due("d4", dropped, "mem://warehouse/dropped/data/z.parquet", tag, false) + ","
                + due("d5", dropped, "mem://warehouse/dropped/data/w.parquet", null, false) + "]}");

        ScanReport report = worker.runOnce();

        assertThat(report.failures).isEmpty();
        assertThat(deleted).containsExactlyInAnyOrder(unreached, unnamed, "mem://warehouse/dropped/data/w.parquet");
        assertThat(completed.get(table.uuid().toString())).as("the kept file is done too")
                .containsExactlyInAnyOrder(id("d1"), id("d2"), id("d3"));
        assertThat(completed.get(dropped)).as("the dropped table's file a ref names waits").containsExactly(id("d5"));
        assertThat(report.filesDeleted).isEqualTo(3);
        assertThat(report.kept).containsExactly(Map.entry(Management.RefKind.TAG, 1));
    }

    /** Files of an orphan scan: one a tag's cut reaches, one nothing reaches, beside one main reaches. */
    private record Orphans(String cut, String reached, String unreached) {
    }

    /**
     * The table with orphan removal on, as the work list names it (with no refs): a file its first snapshot added and
     * its second deleted, which a tag on the first reaches, a file main reaches, and a stray file, all old.
     */
    private Orphans orphans() {
        DataFile a = file("a");
        table.newAppend().appendFile(a).commit();
        String cut = metadata();
        long first = table.currentSnapshot().snapshotId();
        DataFile b = file("b");
        table.newOverwrite().deleteFile(a).addFile(b).commit();
        table.expireSnapshots().expireSnapshotId(first).deleteWith(file -> { }).commit();
        String stray = table.location() + "/data/stray.parquet";
        listed.addAll(List.of(a.location(), b.location(), stray));
        sections.put("tables", "{\"tables\":[{\"catalog\":\"lakehouse\",\"id\":\"" + table.uuid() + "\",\"namespace\":"
                + "[\"ns\"],\"name\":\"t\",\"location\":\"" + table.location() + "\",\"policy\":{"
                + "\"orphan-removal.enabled\":\"true\",\"orphan-removal.older-than\":\"P14D\"},\"refs\":[]}]}");
        return new Orphans(cut, a.location(), stray);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String method = exchange.getRequestMethod();
        calls.add(method + " " + path + (query == null ? "" : "?" + query));
        byte[] sent = exchange.getRequestBody().readAllBytes();
        JsonNode body = sent.length == 0 ? JSON.createObjectNode() : JSON.readTree(sent);
        Answer answer;
        if (path.equals(WORK)) {
            String section = query.replaceAll(".*section=([a-z]+).*", "$1");
            answer = new Answer(200, sections.getOrDefault(section, "{}"));
        } else if (path.equals("/v1/config")) {
            answer = new Answer(200, "{\"defaults\":{},\"overrides\":{}}");
        } else if (path.equals("/v1/namespaces/ns/tables/t")) {
            TableMetadata current = ((HasTableOperations) table).operations().current();
            answer = new Answer(200, "{\"metadata-location\":\"" + current.metadataFileLocation() + "\",\"metadata\":"
                    + TableMetadataParser.toJson(current) + ",\"config\":{}}");
        } else if (path.startsWith("/management/v1/maintenance/refs/")) {
            answer = refRoutes.get(path.substring("/management/v1/maintenance/refs/".length()));
        } else if (path.endsWith("/pending-deletions") && method.equals("POST")) {
            records.add(body);
            answer = onRecord.apply(body);
        } else if (path.endsWith("/pending-deletions/complete")) {
            String object = path.replaceAll(".*/objects/([^/]+)/.*", "$1");
            completed.computeIfAbsent(object, key -> new CopyOnWriteArrayList<>()).addAll(strings(body.path("ids")));
            answer = new Answer(204, null);
        } else if (method.equals("PUT") && path.startsWith("/management/v1/maintenance/")) {
            answer = new Answer(204, null);
        } else {
            answer = new Answer(404, "{\"error\":{\"message\":\"no route " + path + "\",\"type\":\"NotFound\","
                    + "\"code\":404}}");
        }
        if (answer.body() == null) {
            exchange.sendResponseHeaders(answer.status(), -1);
            exchange.close();
            return;
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The catalog's refusal of a record: a ref naming the table was made since; {@code refs} are its refs now. */
    private static Answer refused(String... refs) {
        return new Answer(409, "{\"error\":{\"message\":\"refs changed\",\"type\":\"RefsChangedException\","
                + "\"code\":409},\"refs\":[" + String.join(",", refs) + "]}");
    }

    private static boolean names(JsonNode record, String ref) {
        return strings(record.path("refs")).contains(ref);
    }

    private static String ref(String id, String kind, String... metadata) {
        ObjectNode ref = JSON.createObjectNode().put("id", id).put("kind", kind);
        ArrayNode locations = ref.putArray("metadata-locations");
        for (String location : metadata) {
            locations.add(location);
        }
        return ref.toString();
    }

    /** A due deletion, by a short name for its id; with a ref made since, or none; its table named, or dropped. */
    private static String due(String name, String object, String location, String ref, boolean named) {
        return "{\"catalog\":\"lakehouse\",\"id\":\"" + id(name) + "\",\"object-id\":\"" + object
                + "\",\"location\":\"" + location + "\",\"not-before\":\"2026-11-01T00:00:00Z\",\"refs\":["
                + (ref == null ? "" : ref) + "]" + (named ? ",\"namespace\":[\"ns\"],\"name\":\"t\"" : "") + "}";
    }

    private static String id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String expiry(String name, String action) {
        return "{\"catalog\":\"lakehouse\",\"id\":\"" + id(name) + "\",\"name\":\"" + name
                + "\",\"expires-at\":\"2026-11-23T00:00:00Z\",\"action\":\"" + action + "\"}";
    }

    private static String refCall(String name, String action) {
        return "POST /management/v1/maintenance/refs/" + id(name) + "/" + action;
    }

    private static List<String> strings(JsonNode array) {
        List<String> strings = new ArrayList<>();
        array.forEach(node -> strings.add(node.asText()));
        return strings;
    }

    private DataFile file(String name) {
        return DataFiles.builder(table.spec()).withPath(table.location() + "/data/" + name + ".parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(10).withRecordCount(1).build();
    }

    private String metadata() {
        return ((HasTableOperations) table).operations().current().metadataFileLocation();
    }

    /** The worker's own storage: lists {@link #listed}, all old, and notes what it deletes. */
    private final class Listed implements Storage {

        @Override
        public Iterable<FileInfo> list(String prefix) {
            return listed.stream().filter(location -> location.startsWith(prefix))
                    .map(location -> new FileInfo(location, 10, 0)).toList();
        }

        @Override
        public void delete(Collection<String> locations) {
            deleted.addAll(locations);
        }

        @Override
        public void close() {
        }
    }
}
