package fidice.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.iceberg.rest.ReferenceServer;
import org.junit.jupiter.api.Test;

/**
 * The response diff of D31: one scripted run of requests against our server and against the 1.12.0
 * reference {@code RESTCatalogServer} in process (F1). Each response is reduced to its shape: the status,
 * the error type, and the keys of the body and of its {@code metadata}. Values such as UUIDs, locations
 * and timestamps differ by nature and are not compared. Every difference must be one the ledger decided,
 * listed in {@link #DEVIATIONS}; a deviation that disappears must be removed from the list.
 */
class FixtureDiffTest {

    private static final String NS = "fixture_diff";
    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]}}""";
    private static final String VIEW = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"x","required":false,"type":"int"}]},
             "view-version":{"version-id":1,"timestamp-ms":%d,"schema-id":0,"summary":{"engine-name":"spark"},
              "representations":[{"type":"sql","sql":"select 1 as x","dialect":"spark"}],"default-namespace":[]},
             "properties":{}}""";

    /** Differences the ledger decided, by step. */
    private static final Map<String, String> DEVIATIONS = Map.of(
            "create-namespace-malformed", "D9: a malformed body is a 400 BadRequestException; the reference lets"
                    + " the parse error escape as a 500 HTML page",
            "create-table", "D12: new tables are v3, so the metadata has next-row-id; the reference creates v2",
            "load-table", "D12: v3 metadata has next-row-id",
            "load-table-refs", "D12: v3 metadata has next-row-id",
            "commit-table", "D12: v3 metadata has next-row-id",
            "create-table-missing-namespace", "A table's namespace must exist (404 NoSuchNamespaceException, the"
                    + " RCK's requires-namespace-create); the reference's JDBC backend creates it implicitly",
            "oauth-tokens", "D13: /v1/oauth/tokens is never served; a fixed error names the IdP");

    private record Step(String name, String method, String path, String body) {
    }

    private static List<Step> steps() {
        String tables = "/namespaces/" + NS + "/tables";
        String views = "/namespaces/" + NS + "/views";
        long now = System.currentTimeMillis();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("create-namespace", "POST", "/namespaces",
                "{\"namespace\":[\"" + NS + "\"],\"properties\":{\"owner\":\"x\"}}"));
        steps.add(new Step("create-namespace-again", "POST", "/namespaces", "{\"namespace\":[\"" + NS + "\"]}"));
        steps.add(new Step("create-namespace-malformed", "POST", "/namespaces", "{"));
        steps.add(new Step("load-namespace", "GET", "/namespaces/" + NS, null));
        steps.add(new Step("namespace-exists", "HEAD", "/namespaces/" + NS, null));
        steps.add(new Step("missing-namespace-exists", "HEAD", "/namespaces/nope_fd", null));
        steps.add(new Step("load-missing-namespace", "GET", "/namespaces/nope_fd", null));
        steps.add(new Step("list-namespaces", "GET", "/namespaces", null));
        steps.add(new Step("properties-clash", "POST", "/namespaces/" + NS + "/properties",
                "{\"updates\":{\"a\":\"1\"},\"removals\":[\"a\"]}"));
        steps.add(new Step("properties-update", "POST", "/namespaces/" + NS + "/properties",
                "{\"updates\":{\"a\":\"1\"},\"removals\":[\"owner\",\"absent\"]}"));
        steps.add(new Step("create-table", "POST", tables, TABLE.formatted("t")));
        steps.add(new Step("create-table-again", "POST", tables, TABLE.formatted("t")));
        steps.add(new Step("create-table-missing-namespace", "POST", "/namespaces/nope_fd/tables",
                TABLE.formatted("t")));
        steps.add(new Step("load-table", "GET", tables + "/t", null));
        steps.add(new Step("load-table-refs", "GET", tables + "/t?snapshots=refs", null));
        steps.add(new Step("load-missing-table", "GET", tables + "/nope", null));
        steps.add(new Step("table-exists", "HEAD", tables + "/t", null));
        steps.add(new Step("commit-table", "POST", tables + "/t",
                "{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\",\"updates\":{\"k\":\"v\"}}]}"));
        steps.add(new Step("commit-requirement-failed", "POST", tables + "/t",
                "{\"requirements\":[{\"type\":\"assert-table-uuid\",\"uuid\":\"" + UUID.randomUUID() + "\"}],"
                        + "\"updates\":[]}"));
        steps.add(new Step("commit-missing-table", "POST", tables + "/nope",
                "{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\",\"updates\":{\"k\":\"v\"}}]}"));
        steps.add(new Step("rename-table", "POST", "/tables/rename", rename("t", "t2")));
        steps.add(new Step("rename-missing-table", "POST", "/tables/rename", rename("t", "t3")));
        steps.add(new Step("list-tables", "GET", tables, null));
        steps.add(new Step("create-view", "POST", views, VIEW.formatted("v", now)));
        steps.add(new Step("load-view", "GET", views + "/v", null));
        steps.add(new Step("create-view-over-table", "POST", views, VIEW.formatted("t2", now)));
        steps.add(new Step("list-views", "GET", views, null));
        steps.add(new Step("drop-view", "DELETE", views + "/v", null));
        steps.add(new Step("load-dropped-view", "GET", views + "/v", null));
        steps.add(new Step("report-metrics", "POST", tables + "/t2/metrics",
                "{\"report-type\":\"commit-report\",\"table-name\":\"" + NS + ".t2\",\"snapshot-id\":1,"
                        + "\"sequence-number\":1,\"operation\":\"append\",\"metrics\":{}}"));
        steps.add(new Step("drop-namespace-not-empty", "DELETE", "/namespaces/" + NS, null));
        steps.add(new Step("drop-table", "DELETE", tables + "/t2", null));
        steps.add(new Step("drop-missing-table", "DELETE", tables + "/t2", null));
        steps.add(new Step("drop-namespace", "DELETE", "/namespaces/" + NS, null));
        steps.add(new Step("drop-missing-namespace", "DELETE", "/namespaces/" + NS, null));
        steps.add(new Step("oauth-tokens", "POST", "oauth/tokens",
                "grant_type=client_credentials&client_id=c&client_secret=s&scope=catalog"));
        return steps;
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void responsesHaveTheReferenceShapeExceptWhereTheLedgerDecided() throws Exception {
        URI ours = ConformanceServer.start();
        Map<String, String> referenceCatalog = new HashMap<>(ConformanceServer.clientStorage());
        referenceCatalog.put("warehouse", "s3://warehouse/reference");
        URI reference = ReferenceServer.start(referenceCatalog);

        Map<String, String> differences = new LinkedHashMap<>();
        StringBuilder report = new StringBuilder();
        for (Step step : steps()) {
            HttpResponse<String> ourResponse = send(ours.resolve("v1/" + ConformanceServer.WAREHOUSE + "/"), step);
            HttpResponse<String> theirResponse = send(reference.resolve("/v1/"), step);
            String mine = shape(ourResponse);
            String theirs = shape(theirResponse);
            report.append(step.name()).append('\t').append(mine).append('\t').append(theirs).append('\t')
                    .append(errorMessage(ourResponse)).append('\t').append(errorMessage(theirResponse)).append('\n');
            if (!mine.equals(theirs)) {
                differences.put(step.name(), "ours " + mine + "; reference " + theirs);
            }
        }
        Files.writeString(Path.of(System.getProperty("rck.results")).resolveSibling("fixture-diff.txt"), report);

        assertThat(differences.keySet())
                .as("steps whose response differs from the reference: %s", differences)
                .containsExactlyInAnyOrderElementsOf(DEVIATIONS.keySet());
    }

    private HttpResponse<String> send(URI base, Step step) throws Exception {
        URI uri = step.path().startsWith("oauth/")
                ? base.resolve("/v1/" + step.path())
                : URI.create(base.toString().replaceAll("/$", "") + step.path());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).method(step.method(),
                step.body() == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(step.body()));
        if (step.body() != null) {
            request.header("Content-Type", step.path().startsWith("oauth/")
                    ? "application/x-www-form-urlencoded" : "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Status, error type, and the sorted keys of the body and of its metadata. */
    private String shape(HttpResponse<String> response) throws Exception {
        StringBuilder shape = new StringBuilder().append(response.statusCode());
        if (response.body() == null || response.body().isBlank()) {
            return shape.toString();
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.startsWith("application/json")) {
            return shape.append(" not JSON (").append(type).append(')').toString();
        }
        JsonNode body = json.readTree(response.body());
        if (body.has("error") && body.get("error").isObject()) {
            return shape.append(' ').append(body.get("error").path("type").asText()).toString();
        }
        shape.append(' ').append(keys(body));
        if (body.has("metadata")) {
            shape.append(" metadata").append(keys(body.get("metadata")));
        }
        return shape.toString();
    }

    private String errorMessage(HttpResponse<String> response) {
        try {
            return json.readTree(response.body()).path("error").path("message").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static TreeSet<String> keys(JsonNode node) {
        TreeSet<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String rename(String from, String to) {
        return "{\"source\":{\"namespace\":[\"" + NS + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + NS + "\"],\"name\":\"" + to + "\"}}";
    }
}
