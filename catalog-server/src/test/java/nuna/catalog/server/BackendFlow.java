package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What every backend must carry through the server's routes: grants made over the management API, a namespace,
 * a table, a commit to it and a reload that sees the commit; and, in {@link #outage}, a database the server cannot
 * reach, answered as one. The store suites test each backend's whole contract; this proves the server is wired to
 * it. The server needs {@link #GRANT_ADMINS} as its grant-admin group.
 */
final class BackendFlow {

    static final String GRANT_ADMINS = "grant-admins";

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";
    private static final String SET_DESK = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"desk":"rates"}}]}""";
    /** How long a request repeated on Retry-After may go on answering 503 once the link is back. */
    private static final Duration RECOVERY = Duration.ofMinutes(2);

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    BackendFlow(int port) {
        this.port = port;
    }

    void run() {
        grant();
        String tables = newNamespace() + "/tables";
        expect(send("POST", tables, TABLE.formatted("trades")), 200);
        expect(send("POST", tables + "/trades", SET_DESK), 200);

        HttpResponse<String> loaded = expect(send("GET", tables + "/trades", null), 200);
        assertThat(JsonPath.<String>read(loaded.body(), "$.metadata.properties.desk")).isEqualTo("rates");
        HttpResponse<String> listed = expect(send("GET", tables, null), 200);
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.identifiers[*].name")).containsExactly("trades");
    }

    /**
     * A database the server cannot reach (backlog 70): with the link cut, a load and a commit each answer 503
     * ServiceUnavailableException with Retry-After, in the Iceberg error shape, so a client may repeat even the
     * commit. Once the link is back the same requests, repeated as Retry-After says, succeed, and the load shows that
     * the commit that answered 503 applied nothing. {@code healLink} runs however the outage part ends.
     */
    void outage(Runnable cutLink, Runnable healLink) {
        grant();
        String tables = newNamespace() + "/tables";
        expect(send("POST", tables, TABLE.formatted("ledger")), 200);
        String table = tables + "/ledger";

        cutLink.run();
        try {
            unavailable(send("GET", table, null));
            unavailable(send("POST", table, SET_DESK));
        } finally {
            healLink.run();
        }

        HttpResponse<String> loaded = expect(repeated("GET", table, null), 200);
        assertThat(JsonPath.<Map<String, Object>>read(loaded.body(), "$.metadata.properties"))
                .as("the commit that answered 503 applied nothing").doesNotContainKey("desk");
        HttpResponse<String> committed = expect(repeated("POST", table, SET_DESK), 200);
        assertThat(JsonPath.<String>read(committed.body(), "$.metadata.properties.desk")).isEqualTo("rates");
        HttpResponse<String> reloaded = expect(send("GET", table, null), 200);
        assertThat(JsonPath.<String>read(reloaded.body(), "$.metadata.properties.desk")).isEqualTo("rates");
    }

    /** The test group may create and commit in the catalog; granting again answers 204 as the first time did. */
    private void grant() {
        for (String privilege : List.of("create", "modify")) {
            expect(send("POST", "/management/v1/catalogs/trading/grants", """
                    {"securable":{"type":"catalog"},"group":"%s","privilege":"%s"}"""
                    .formatted(IssuerForTests.GROUP, privilege), IssuerForTests.bearer("gina", GRANT_ADMINS)), 204);
        }
    }

    /** A new namespace, by its path. */
    private String newNamespace() {
        String namespace = "ns_" + UUID.randomUUID().toString().replace("-", "");
        expect(send("POST", "/v1/trading/namespaces", "{\"namespace\":[\"" + namespace + "\"]}"), 200);
        return "/v1/trading/namespaces/" + namespace;
    }

    /** 503 ServiceUnavailableException with Retry-After, in the Iceberg error shape, as {@link StoreOutage} has it. */
    private static void unavailable(HttpResponse<String> response) {
        expect(response, StoreOutage.STATUS);
        String what = response.request().method() + " " + response.request().uri();
        assertThat(response.headers().firstValue("Retry-After")).as(what).contains(StoreOutage.RETRY_AFTER_SECONDS);
        assertThat(response.headers().firstValue("Content-Type")).as(what)
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.error.code")).as(what).isEqualTo(StoreOutage.STATUS);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).as(what)
                .isEqualTo("ServiceUnavailableException");
        assertThat(JsonPath.<String>read(response.body(), "$.error.message")).as(what).isNotBlank();
    }

    /**
     * Sends the request, and again after Retry-After's seconds while it answers 503 with Retry-After, as a client
     * does: the server's database client reconnects on its own once the link is back, not always by the next request.
     */
    private HttpResponse<String> repeated(String method, String path, String body) {
        Instant deadline = Instant.now().plus(RECOVERY);
        while (true) {
            HttpResponse<String> response = send(method, path, body);
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");
            if (response.statusCode() != StoreOutage.STATUS || retryAfter.isEmpty() || Instant.now().isAfter(deadline)) {
                return response;
            }
            try {
                Thread.sleep(Duration.ofSeconds(Long.parseLong(retryAfter.get())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
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
