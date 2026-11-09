package fidice.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;

/** Requests to the server under test, the fixtures they need, and what reached the audit topic. */
final class Api implements AutoCloseable {

    static final String CATALOG = "trading";
    static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";
    static final String VIEW = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"x","required":false,"type":"int"}]},
             "view-version":{"version-id":1,"timestamp-ms":%d,"schema-id":0,"summary":{"engine-name":"spark"},
              "representations":[{"type":"sql","sql":"select 1 as x","dialect":"spark"}],"default-namespace":[]},
             "properties":{}}""";
    static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"k":"%s"}}]}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    private final KafkaConsumer<byte[], byte[]> audit;
    private final List<byte[]> seen = new ArrayList<>();

    Api(int port, JdbcTemplate jdbc, String bootstrapServers) {
        this.port = port;
        jdbc.update("INSERT INTO catalog (id, name_norm, name) VALUES (?, 'trading', 'Trading') "
                + "ON CONFLICT (name_norm) DO NOTHING", UUID.randomUUID());
        audit = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500"),
                new ByteArrayDeserializer(), new ByteArrayDeserializer());
        audit.subscribe(List.of("fidice.catalog.audit"));
    }

    // ---- requests ----

    /** A request to {@code /v1/trading...}. */
    HttpResponse<String> rest(String method, String path, String body, String... headers) {
        return send(method, "/v1/" + CATALOG + path, body, headers);
    }

    /** A request to {@code /management/v1...}; a path not under {@code /maintenance/} is under the catalog. */
    HttpResponse<String> manage(String method, String path, String body, String... headers) {
        return send(method, path.startsWith("/maintenance/") ? "/management/v1" + path
                : "/management/v1/catalogs/" + CATALOG + path, body, headers);
    }

    HttpResponse<String> send(String method, String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- fixtures ----

    String namespace() {
        String name = "ns_" + suffix();
        expect(rest("POST", "/namespaces", "{\"namespace\":[\"" + name + "\"]}"), 200);
        return name;
    }

    /** Creates a table and returns its metadata location. */
    String table(String namespace, String name) {
        HttpResponse<String> created = rest("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted(name));
        expect(created, 200);
        return JsonPath.read(created.body(), "$.metadata-location");
    }

    void view(String namespace, String name) {
        expect(rest("POST", "/namespaces/" + ns(namespace) + "/views",
                VIEW.formatted(name, System.currentTimeMillis())), 200);
    }

    /** The table's id, from the catalog's own listing of governance. */
    String id(String namespace, String kind, String name) {
        HttpResponse<String> governance = manage("GET", "/namespaces/" + ns(namespace) + "/" + kind + "/" + name
                + "/governance", null);
        expect(governance, 200);
        return JsonPath.read(governance.body(), "$.target.id");
    }

    static String snapshot(String location, long id, long timestampMs) {
        String root = location.substring(0, location.indexOf("/metadata/"));
        return """
                {"requirements":[],"updates":[
                 {"action":"add-snapshot","snapshot":{"snapshot-id":%d,"sequence-number":%d,"timestamp-ms":%d,
                  "manifest-list":"%s/metadata/snap-%d.avro","summary":{"operation":"append"},"schema-id":0}},
                 {"action":"set-snapshot-ref","ref-name":"main","snapshot-id":%d,"type":"branch"}]}"""
                .formatted(id, id, timestampMs, root, id, id);
    }

    static String removeSnapshot(long id) {
        return "{\"requirements\":[],\"updates\":[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[" + id + "]}]}";
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A namespace path segment: levels joined by 0x1F, URL-encoded. */
    static String ns(String... levels) {
        return Arrays.stream(levels)
                .map(l -> URLEncoder.encode(l, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("%1F"));
    }

    static HttpResponse<String> expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + ": "
                + response.body()).isEqualTo(status);
        return response;
    }

    static void expectError(HttpResponse<String> response, int status, String type) {
        expect(response, status);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo(type);
    }

    // ---- the audit topic ----

    /** The first audit record matching, waiting up to 30 seconds for the relay to publish it. */
    CatalogEvent awaitRecord(Predicate<String> json) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int checked = 0;
        while (true) {
            for (; checked < seen.size(); checked++) {
                String record = new String(seen.get(checked), StandardCharsets.UTF_8);
                if (json.test(record)) {
                    return EventCodec.decode(seen.get(checked));
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no matching audit record among " + seen.size() + " read");
            }
            audit.poll(Duration.ofMillis(200)).forEach(r -> seen.add(r.value()));
        }
    }

    /** The audit record of the request with this id. */
    CatalogEvent awaitRequest(String requestId) {
        return awaitRecord(json -> json.contains("\"" + requestId + "\""));
    }

    /** Every audit record read so far, after one more poll. */
    List<String> records() {
        audit.poll(Duration.ofMillis(500)).forEach(r -> seen.add(r.value()));
        return seen.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList();
    }

    @Override
    public void close() {
        audit.close();
    }
}
