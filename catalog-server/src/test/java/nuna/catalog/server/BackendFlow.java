package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

/**
 * What every backend must carry through the server's routes: grants made over the management API, a namespace,
 * a table, a commit to it and a reload that sees the commit. The store suites test each backend's whole contract;
 * this proves the server is wired to it. The server needs {@link #GRANT_ADMINS} as its grant-admin group.
 */
final class BackendFlow {

    static final String GRANT_ADMINS = "grant-admins";

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    BackendFlow(int port) {
        this.port = port;
    }

    void run() {
        for (String privilege : List.of("create", "modify")) {
            expect(send("POST", "/management/v1/catalogs/trading/grants", """
                    {"securable":{"type":"catalog"},"group":"%s","privilege":"%s"}"""
                    .formatted(IssuerForTests.GROUP, privilege), IssuerForTests.bearer("gina", GRANT_ADMINS)), 204);
        }
        String namespace = "ns_" + UUID.randomUUID().toString().replace("-", "");
        expect(send("POST", "/v1/trading/namespaces", "{\"namespace\":[\"" + namespace + "\"]}"), 200);
        String tables = "/v1/trading/namespaces/" + namespace + "/tables";
        expect(send("POST", tables, TABLE.formatted("trades")), 200);
        expect(send("POST", tables + "/trades", """
                {"requirements":[],"updates":[{"action":"set-properties","updates":{"desk":"rates"}}]}"""), 200);

        HttpResponse<String> loaded = expect(send("GET", tables + "/trades", null), 200);
        assertThat(JsonPath.<String>read(loaded.body(), "$.metadata.properties.desk")).isEqualTo("rates");
        HttpResponse<String> listed = expect(send("GET", tables, null), 200);
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.identifiers[*].name")).containsExactly("trades");
    }

    private HttpResponse<String> send(String method, String path, String body) {
        return send(method, path, body, IssuerForTests.bearer());
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", authorization);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + ": "
                + response.body()).isEqualTo(status);
        return response;
    }
}
