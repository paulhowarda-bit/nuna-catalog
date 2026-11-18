package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The REST contract on the wire (D9, D10, D13, D14): what the RCK does not pin down, such as ETags,
 * snapshots=refs with statistics, soft drops, idempotent replays and the fixed OAuth error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, AuditSinkForTests.class})
// Pages of two, so a listing without a pageToken reads several pages from the store.
@TestPropertySource(properties = {"nuna.rest.default-page-size=2", "nuna.rest.max-page-size=2"})
class RestRoutesTest {

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";
    private static final String VIEW = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"x","required":false,"type":"int"}]},
             "view-version":{"version-id":1,"timestamp-ms":%d,"schema-id":0,"summary":{"engine-name":"spark"},
              "representations":[{"type":"sql","sql":"select 1 as x","dialect":"spark"}],"default-namespace":[]},
             "properties":{}}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void grantPrivileges() {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
    }

    @Test
    void namespacesNestListDropAndReportTheirProperties() throws Exception {
        String top = "Équités-" + suffix();
        assertThat(createNamespace(Map.of("owner", "desk"), top).statusCode()).isEqualTo(200);
        assertError(createNamespace(Map.of(), top), 409, "AlreadyExistsException");
        assertThat(createNamespace(Map.of(), top, "Desk").statusCode()).isEqualTo(200);
        assertError(createNamespace(Map.of(), "missing-" + suffix(), "child"), 404, "NoSuchNamespaceException");

        HttpResponse<String> children = send("GET", "/namespaces?parent=" + ns(top), null);
        assertThat(JsonPath.<List<List<String>>>read(children.body(), "$.namespaces"))
                .containsExactly(List.of(top, "Desk"));
        assertThat(send("HEAD", "/namespaces/" + ns(top, "Desk"), null).statusCode()).isEqualTo(204);
        assertThat(send("HEAD", "/namespaces/" + ns(top, "nope"), null).statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(send("GET", "/namespaces/" + ns(top), null).body(), "$.properties.owner"))
                .isEqualTo("desk");

        HttpResponse<String> updated = send("POST", "/namespaces/" + ns(top) + "/properties",
                "{\"updates\":{\"comment\":\"x\"},\"removals\":[\"owner\",\"absent\"]}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(updated.body(), "$.updated")).containsExactly("comment");
        assertThat(JsonPath.<List<String>>read(updated.body(), "$.removed")).containsExactly("owner");
        assertThat(JsonPath.<List<String>>read(updated.body(), "$.missing")).containsExactly("absent");
        assertError(send("POST", "/namespaces/" + ns(top) + "/properties",
                "{\"updates\":{\"a\":\"1\"},\"removals\":[\"a\"]}"), 422, "UnprocessableEntityException");

        assertError(send("DELETE", "/namespaces/" + ns(top), null), 409, "NamespaceNotEmptyException");
        assertThat(send("DELETE", "/namespaces/" + ns(top, "Desk"), null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/namespaces/" + ns(top), null).statusCode()).isEqualTo(204);
        assertError(send("DELETE", "/namespaces/" + ns(top), null), 404, "NoSuchNamespaceException");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'nuna.catalog.namespace'",
                Integer.class)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void listingsArePagedWithOpaqueTokens() throws Exception {
        String parent = "paged-" + suffix();
        createNamespace(Map.of(), parent);
        for (int i = 0; i < 5; i++) {
            createNamespace(Map.of(), parent, "n" + i);
        }
        List<String> seen = new ArrayList<>();
        String token = "";
        int pages = 0;
        do {
            HttpResponse<String> page = send("GET", "/namespaces?parent=" + ns(parent) + "&pageSize=2&pageToken="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8), null);
            assertThat(page.statusCode()).isEqualTo(200);
            JsonPath.<List<List<String>>>read(page.body(), "$.namespaces").forEach(n -> seen.add(n.getLast()));
            token = JsonPath.read(page.body(), "$.next-page-token");
            pages++;
        } while (token != null);

        assertThat(seen).containsExactly("n0", "n1", "n2", "n3", "n4");
        assertThat(pages).isEqualTo(3);
        assertError(send("GET", "/namespaces?pageToken=not-ours", null), 400, "BadRequestException");

        // No pageToken: every item and a null next-page-token, whatever pageSize says (spec, PageToken).
        HttpResponse<String> all = send("GET", "/namespaces?parent=" + ns(parent) + "&pageSize=2", null);
        assertThat(JsonPath.<List<List<String>>>read(all.body(), "$.namespaces")).extracting(List::getLast)
                .containsExactly("n0", "n1", "n2", "n3", "n4");
        assertThat(JsonPath.<Object>read(all.body(), "$.next-page-token")).isNull();
        for (int i = 0; i < 5; i++) {
            send("POST", "/namespaces/" + ns(parent) + "/tables", TABLE.formatted("t" + i));
        }
        HttpResponse<String> tables = send("GET", "/namespaces/" + ns(parent) + "/tables", null);
        assertThat(JsonPath.<List<String>>read(tables.body(), "$.identifiers[*].name"))
                .containsExactly("t0", "t1", "t2", "t3", "t4");
        assertThat(JsonPath.<Object>read(tables.body(), "$.next-page-token")).isNull();
    }

    /** Iceberg names may hold any character; a client sends / as %2F and % as %25 inside a path segment. */
    @Test
    void namesMayHoldSlashesAndPercentSigns() throws Exception {
        String slashed = "a/b-" + suffix();
        assertThat(createNamespace(Map.of(), slashed).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/namespaces/" + ns(slashed), null).statusCode()).isEqualTo(200);
        assertThat(send("HEAD", "/namespaces/" + ns(slashed), null).statusCode()).isEqualTo(204);

        String tables = "/namespaces/" + ns(slashed) + "/tables";
        for (String name : List.of("t/1", "p%q")) {
            String table = tables + "/" + ns(name);
            assertThat(send("POST", tables, TABLE.formatted(name)).statusCode()).isEqualTo(200);
            HttpResponse<String> loaded = send("GET", table, null);
            assertThat(loaded.statusCode()).as(loaded.body()).isEqualTo(200);
            assertThat(send("HEAD", table, null).statusCode()).isEqualTo(204);
            assertThat(send("POST", table, "{\"requirements\":[],\"updates\":[" + setK(name) + "]}").statusCode())
                    .isEqualTo(200);
            assertThat(JsonPath.<String>read(send("GET", table, null).body(), "$.metadata.properties.k"))
                    .isEqualTo(name);
        }
        assertThat(JsonPath.<List<String>>read(send("GET", tables, null).body(), "$.identifiers[*].name"))
                .containsExactlyInAnyOrder("t/1", "p%q");
        assertThat(send("DELETE", tables + "/" + ns("t/1"), null).statusCode()).isEqualTo(204);
    }

    @Test
    void tableLoadsAnswer304WhileThePointerHolds() throws Exception {
        String namespace = namespace();
        HttpResponse<String> created = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        assertThat(created.statusCode()).isEqualTo(200);
        String etag = created.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> cached = send("GET", "/namespaces/" + ns(namespace) + "/tables/t", null,
                "If-None-Match", etag);
        assertThat(cached.statusCode()).isEqualTo(304);
        assertThat(cached.body()).isEmpty();
        assertThat(send("GET", "/namespaces/" + ns(namespace) + "/tables/t?snapshots=refs", null,
                "If-None-Match", etag).statusCode()).as("another snapshot mode is another ETag").isEqualTo(200);

        String location = JsonPath.read(created.body(), "$.metadata.location");
        assertThat(send("POST", "/namespaces/" + ns(namespace) + "/tables/t", snapshot(location, 1, null, true))
                .statusCode()).isEqualTo(200);
        HttpResponse<String> changed = send("GET", "/namespaces/" + ns(namespace) + "/tables/t", null,
                "If-None-Match", etag);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.headers().firstValue("ETag")).isPresent().get().isNotEqualTo(etag);
        assertError(send("GET", "/namespaces/" + ns(namespace) + "/tables/t?snapshots=some", null),
                400, "BadRequestException");
    }

    /** The reference fixture answers 400 here, because it keeps statistics of a snapshot it leaves out. */
    @Test
    void snapshotsRefsDropsUnreferencedSnapshotsWithTheirStatistics() throws Exception {
        String namespace = namespace();
        HttpResponse<String> created = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String location = JsonPath.read(created.body(), "$.metadata.location");
        String table = "/namespaces/" + ns(namespace) + "/tables/t";
        assertThat(send("POST", table, snapshot(location, 1, null, false)).statusCode()).isEqualTo(200);
        assertThat(send("POST", table, """
                {"requirements":[],"updates":[{"action":"set-statistics","snapshot-id":1,"statistics":{
                  "snapshot-id":1,"statistics-path":"%s/metadata/stats-1.puffin","file-size-in-bytes":100,
                  "file-footer-size-in-bytes":10,"blob-metadata":[]}}]}""".formatted(location)).statusCode())
                .isEqualTo(200);
        assertThat(send("POST", table, snapshot(location, 2, 1L, true)).statusCode()).isEqualTo(200);

        HttpResponse<String> refs = send("GET", table + "?snapshots=refs", null);
        assertThat(refs.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(refs.body(), "$.metadata.snapshots[*].snapshot-id")).containsExactly(2);
        assertThat(JsonPath.<List<Object>>read(refs.body(), "$.metadata.statistics")).isEmpty();
        HttpResponse<String> all = send("GET", table, null);
        assertThat(JsonPath.<List<Integer>>read(all.body(), "$.metadata.snapshots[*].snapshot-id"))
                .containsExactly(1, 2);
    }

    @Test
    void stageCreateAssignsTheLocationAndCreatesNothing() throws Exception {
        String namespace = namespace();
        HttpResponse<String> staged = send("POST", "/namespaces/" + ns(namespace) + "/tables",
                TABLE.formatted("s").replace("\"name\":\"s\"", "\"name\":\"s\",\"stage-create\":true,"
                        + "\"location\":\"s3://elsewhere/t\""));
        assertThat(staged.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(staged.body(), "$.metadata.location"))
                .startsWith("s3://" + S3ForTests.bucket() + "/tables/");
        assertThat(JsonPath.<Map<String, Object>>read(staged.body(), "$")).doesNotContainKey("metadata-location");
        assertThat(send("HEAD", "/namespaces/" + ns(namespace) + "/tables/s", null).statusCode()).isEqualTo(404);
    }

    @Test
    void dropIsSoftAndFreesTheName() throws Exception {
        String namespace = namespace();
        String table = "/namespaces/" + ns(namespace) + "/tables/t";
        HttpResponse<String> created = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String id = JsonPath.read(created.body(), "$.metadata.table-uuid");

        assertThat(send("DELETE", table + "?purgeRequested=true", null).statusCode()).isEqualTo(204);

        assertError(send("GET", table, null), 404, "NoSuchTableException");
        assertThat(send("HEAD", table, null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT state FROM catalog_object WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("DROPPED");
        assertThat(send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t")).statusCode())
                .isEqualTo(200);
    }

    /** D13, amended 2026-11-12: a dropped table's own file brings it back as itself; an active table's is taken. */
    @Test
    void registerBringsADroppedTableBackAndRefusesTakenUuidsAndLocationsOutsideTheProfile() throws Exception {
        String namespace = namespace();
        HttpResponse<String> created = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String file = JsonPath.read(created.body(), "$.metadata-location");
        String id = JsonPath.read(created.body(), "$.metadata.table-uuid");
        send("DELETE", "/namespaces/" + ns(namespace) + "/tables/t", null);
        String register = "/namespaces/" + ns(namespace) + "/register";

        HttpResponse<String> back = send("POST", register,
                "{\"name\":\"again\",\"metadata-location\":\"" + file + "\"}");
        assertThat(back.statusCode()).as(back.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.metadata.table-uuid")).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT state FROM catalog_object WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("ACTIVE");
        assertThat(send("HEAD", "/namespaces/" + ns(namespace) + "/tables/again", null).statusCode()).isEqualTo(204);
        assertError(send("POST", register, "{\"name\":\"copy\",\"metadata-location\":\"" + file + "\"}"),
                409, "AlreadyExistsException");
        assertError(send("POST", register, "{\"name\":\"again\",\"metadata-location\":\"" + file + "\"}"),
                409, "AlreadyExistsException");
        HttpResponse<String> overwrite = send("POST", register,
                "{\"name\":\"again\",\"metadata-location\":\"" + file + "\",\"overwrite\":true}");
        assertError(overwrite, 400, "BadRequestException");
        assertThat(JsonPath.<String>read(overwrite.body(), "$.error.message")).contains("overwrite");
        assertError(send("POST", register, "{\"name\":\"x\",\"metadata-location\":\"s3://elsewhere/m.json\"}"),
                400, "BadRequestException");
        String missing = "s3://" + S3ForTests.bucket() + "/tables/" + UUID.randomUUID() + "/metadata/1.metadata.json";
        HttpResponse<String> notFound = send("POST", register,
                "{\"name\":\"x\",\"metadata-location\":\"" + missing + "\"}");
        assertError(notFound, 404, "NotFoundException");
        assertThat(JsonPath.<String>read(notFound.body(), "$.error.message"))
                .isEqualTo("Failed to open input stream for file: " + missing);
    }

    /**
     * D13, amended 2026-11-12: an unregister answers the table's current metadata file and content and frees the
     * name; the table is gone for commits; its key replays the answer; registering the file brings it back.
     */
    @Test
    void unregisterAnswersTheMetadataAndRegisteringItBringsTheTableBack() throws Exception {
        String namespace = namespace();
        String table = "/namespaces/" + ns(namespace) + "/tables/t";
        HttpResponse<String> created = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String location = JsonPath.read(created.body(), "$.metadata.location");
        HttpResponse<String> committed = send("POST", table, snapshot(location, 1, null, true));
        String current = JsonPath.read(committed.body(), "$.metadata-location");
        String id = JsonPath.read(created.body(), "$.metadata.table-uuid");
        String key = UUID.randomUUID().toString();

        HttpResponse<String> unregistered = send("POST", table + "/unregister", null, "Idempotency-Key", key);

        assertThat(unregistered.statusCode()).as(unregistered.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(unregistered.body(), "$.metadata-location")).isEqualTo(current);
        assertThat(JsonPath.<String>read(unregistered.body(), "$.metadata.table-uuid")).isEqualTo(id);
        assertThat(JsonPath.<List<Integer>>read(unregistered.body(), "$.metadata.snapshots[*].snapshot-id"))
                .containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT state FROM catalog_object WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("UNREGISTERED");
        assertError(send("GET", table, null), 404, "NoSuchTableException");
        assertError(send("POST", table, snapshot(location, 2, 1L, true)), 404, "NoSuchTableException");
        HttpResponse<String> replayed = send("POST", table + "/unregister", null, "Idempotency-Key", key);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(unregistered.body());
        assertError(send("POST", table + "/unregister", null), 404, "NoSuchTableException");
        assertError(send("POST", "/namespaces/" + ns(namespace) + "/tables/nope/unregister", null), 404,
                "NoSuchTableException");

        HttpResponse<String> back = send("POST", "/namespaces/" + ns(namespace) + "/register",
                "{\"name\":\"t\",\"metadata-location\":\"" + current + "\"}");
        assertThat(back.statusCode()).as(back.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.metadata.table-uuid")).isEqualTo(id);
        assertThat(send("POST", table, snapshot(location, 2, 1L, true)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'nuna.catalog.unregister'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    /**
     * D13, amended 2026-11-12: register-view takes a view's existing metadata file; a dropped view's own file
     * brings it back, an active view's UUID is taken, and a table's file is no view.
     */
    @Test
    void registerViewTakesAViewsOwnFile() throws Exception {
        String namespace = namespace();
        String views = "/namespaces/" + ns(namespace) + "/views";
        String register = "/namespaces/" + ns(namespace) + "/register-view";
        HttpResponse<String> created = send("POST", views, VIEW.formatted("v", System.currentTimeMillis()));
        String file = JsonPath.read(created.body(), "$.metadata-location");
        String id = JsonPath.read(created.body(), "$.metadata.view-uuid");

        assertError(send("POST", register, "{\"name\":\"w\",\"metadata-location\":\"" + file + "\"}"),
                409, "AlreadyExistsException");
        assertThat(send("DELETE", views + "/v", null).statusCode()).isEqualTo(204);
        HttpResponse<String> back = send("POST", register, "{\"name\":\"w\",\"metadata-location\":\"" + file + "\"}");
        assertThat(back.statusCode()).as(back.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.metadata.view-uuid")).isEqualTo(id);
        assertThat(send("GET", views + "/w", null).statusCode()).isEqualTo(200);
        assertError(send("POST", register, "{\"name\":\"w\",\"metadata-location\":\"" + file + "\"}"),
                409, "AlreadyExistsException");

        HttpResponse<String> table = send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String tableFile = JsonPath.read(table.body(), "$.metadata-location");
        assertError(send("POST", register, "{\"name\":\"x\",\"metadata-location\":\"" + tableFile + "\"}"),
                400, "IllegalArgumentException");
        assertError(send("POST", register, "{\"metadata-location\":\"" + file + "\"}"), 400, "BadRequestException");
        assertError(send("POST", register, "{\"name\":\"x\",\"metadata-location\":\"s3://elsewhere/v.json\"}"),
                400, "BadRequestException");
    }

    @Test
    void renamesMoveTablesAndRefuseNamesTheOtherKindHolds() throws Exception {
        String namespace = namespace();
        send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        assertThat(send("POST", "/namespaces/" + ns(namespace) + "/views",
                VIEW.formatted("v", System.currentTimeMillis())).statusCode()).isEqualTo(200);

        assertThat(send("POST", "/tables/rename", rename(namespace, "t", namespace, "t2")).statusCode())
                .isEqualTo(204);
        assertThat(send("HEAD", "/namespaces/" + ns(namespace) + "/tables/t", null).statusCode()).isEqualTo(404);
        assertThat(send("HEAD", "/namespaces/" + ns(namespace) + "/tables/t2", null).statusCode()).isEqualTo(204);
        HttpResponse<String> taken = send("POST", "/tables/rename", rename(namespace, "t2", namespace, "v"));
        assertError(taken, 409, "AlreadyExistsException");
        assertThat(JsonPath.<String>read(taken.body(), "$.error.message"))
                .isEqualTo("Cannot rename " + namespace + ".t2 to " + namespace + ".v. View already exists");
        assertError(send("POST", "/tables/rename", rename(namespace, "t2", "nowhere-" + suffix(), "t")),
                404, "NoSuchNamespaceException");
        assertError(send("POST", "/views/rename", rename(namespace, "t2", namespace, "w")), 404, "NoSuchViewException");
    }

    @Test
    void viewsLoadListAndDrop() throws Exception {
        String namespace = namespace();
        String views = "/namespaces/" + ns(namespace) + "/views";
        assertThat(send("POST", views, VIEW.formatted("v", System.currentTimeMillis())).statusCode()).isEqualTo(200);

        HttpResponse<String> loaded = send("GET", views + "/v", null);
        assertThat(loaded.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(loaded.body(), "$.metadata.location"))
                .startsWith("s3://" + S3ForTests.bucket() + "/tables/");
        assertThat(JsonPath.<List<String>>read(send("GET", views, null).body(), "$.identifiers[*].name"))
                .containsExactly("v");
        assertError(send("GET", "/namespaces/" + ns(namespace) + "/tables/v", null), 404, "NoSuchTableException");
        assertError(send("POST", views + "/v", "{\"requirements\":[],\"updates\":[{\"action\":\"set-location\","
                + "\"location\":\"s3://elsewhere/v\"}]}"), 400, "BadRequestException");
        assertThat(send("DELETE", views + "/v", null).statusCode()).isEqualTo(204);
        assertError(send("GET", views + "/v", null), 404, "NoSuchViewException");
    }

    @Test
    void anIdempotencyKeyReplaysItsAnswerAndBelongsToOneRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        String name = "keyed-" + suffix();
        String body = "{\"namespace\":[\"" + name + "\"],\"properties\":{}}";

        HttpResponse<String> first = send("POST", "/namespaces", body, "Idempotency-Key", key);
        HttpResponse<String> again = send("POST", "/namespaces", body, "Idempotency-Key", key);
        HttpResponse<String> other = send("POST", "/namespaces", body.replace(name, name + "x"), "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).as("a replay, not a 409").isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertError(other, 400, "BadRequestException");

        String dropKey = UUID.randomUUID().toString();
        assertThat(send("DELETE", "/namespaces/" + ns(name), null, "Idempotency-Key", dropKey).statusCode())
                .isEqualTo(204);
        assertThat(send("DELETE", "/namespaces/" + ns(name), null, "Idempotency-Key", dropKey).statusCode())
                .isEqualTo(204);
    }

    @Test
    void metricsCredentialsAndTokens() throws Exception {
        String namespace = namespace();
        send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));
        String table = "/namespaces/" + ns(namespace) + "/tables/t";

        assertThat(send("POST", table + "/metrics", """
                {"report-type":"commit-report","table-name":"%s.t","snapshot-id":1,"sequence-number":1,
                 "operation":"append","metrics":{}}""".formatted(namespace)).statusCode()).isEqualTo(204);
        String report = "{\"report-type\":\"commit-report\",\"table-name\":\"x\",\"snapshot-id\":1,"
                + "\"sequence-number\":1,\"operation\":\"append\",\"metrics\":{}}";
        assertError(send("POST", "/namespaces/" + ns(namespace) + "/tables/ghost/metrics", report),
                404, "NoSuchTableException");
        assertError(send("POST", "/namespaces/" + ns("nowhere-" + suffix()) + "/tables/t/metrics", report),
                404, "NoSuchTableException");
        HttpResponse<String> credentials = send("GET", table + "/credentials", null);
        assertThat(credentials.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(credentials.body(), "$.storage-credentials[*].prefix"))
                .as("M6 vends credentials for the table's location").singleElement().asString()
                .startsWith("s3://" + S3ForTests.bucket() + "/tables/");

        HttpResponse<String> token = request("POST", URI.create("http://localhost:" + port + "/v1/oauth/tokens"),
                "grant_type=client_credentials", "Content-Type", "application/x-www-form-urlencoded");
        assertThat(token.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(token.body(), "$.error")).isEqualTo("unsupported_grant_type");
        assertThat(JsonPath.<String>read(token.body(), "$.error_description")).contains("identity provider");
    }

    /** D15 on the wire: 204 for all or nothing, the request refused whole for any table's fault, replayed by key. */
    @Test
    void transactionsCommitEveryTableOrNone() throws Exception {
        String namespace = namespace();
        for (String table : List.of("a", "b")) {
            assertThat(send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted(table)).statusCode())
                    .isEqualTo(200);
        }
        String uuidB = JsonPath.read(send("GET", "/namespaces/" + ns(namespace) + "/tables/b", null).body(),
                "$.metadata.table-uuid");

        HttpResponse<String> applied = send("POST", "/transactions/commit",
                transaction(change(namespace, "a", "", setK("1")), change(namespace, "b", assertUuid(uuidB), setK("1"))));
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(204);
        assertThat(applied.body()).isEmpty();
        assertThat(property(namespace, "a")).isEqualTo("1");
        assertThat(property(namespace, "b")).isEqualTo("1");

        String a = location(namespace, "a");
        assertError(send("POST", "/transactions/commit", transaction(change(namespace, "a", "", setK("2")),
                change(namespace, "b", assertUuid(UUID.randomUUID().toString()), setK("2")))),
                409, "CommitFailedException");
        assertError(send("POST", "/transactions/commit", transaction(change(namespace, "a", "", setK("2")),
                change(namespace, "ghost", "", setK("2")))), 404, "NoSuchTableException");
        assertError(send("POST", "/transactions/commit", transaction(change(namespace, "a", "", setK("2")),
                change(namespace, "A", "", setK("3")))), 400, "BadRequestException");
        assertError(send("POST", "/transactions/commit", transaction(change(namespace, "a", "", setK("2")),
                change(namespace, "b", "", "{\"action\":\"no-such-update\"}"))), 400, "BadRequestException");
        assertError(send("POST", "/transactions/commit",
                "{\"table-changes\":[{\"requirements\":[],\"updates\":[" + setK("2") + "]}]}"), 400, "BadRequestException");
        assertError(send("POST", "/transactions/commit", "{\"table-changes\":[]}"), 400, "BadRequestException");
        String[] overTheCap = new String[101];
        Arrays.fill(overTheCap, change(namespace, "a", "", setK("2")));
        HttpResponse<String> tooMany = send("POST", "/transactions/commit", transaction(overTheCap));
        assertError(tooMany, 400, "BadRequestException");
        assertThat(JsonPath.<String>read(tooMany.body(), "$.error.message")).contains("at most 100 tables");
        assertThat(location(namespace, "a")).as("no refused transaction changed a").isEqualTo(a);
        assertThat(property(namespace, "b")).isEqualTo("1");

        String key = UUID.randomUUID().toString();
        String request = transaction(change(namespace, "a", "", setK("4")), change(namespace, "b", "", setK("4")));
        assertThat(send("POST", "/transactions/commit", request, "Idempotency-Key", key).statusCode()).isEqualTo(204);
        String committed = location(namespace, "a");
        assertThat(send("POST", "/transactions/commit", request, "Idempotency-Key", key).statusCode()).isEqualTo(204);
        assertThat(location(namespace, "a")).as("replayed, not applied again").isEqualTo(committed);
    }

    @Test
    void metadataTablesAreNoSuchTable() throws Exception {
        String namespace = namespace();
        send("POST", "/namespaces/" + ns(namespace) + "/tables", TABLE.formatted("t"));

        assertError(send("GET", "/namespaces/" + ns(namespace, "t") + "/tables/snapshots", null),
                404, "NoSuchTableException");
    }

    // ---- helpers ----

    private static String snapshot(String location, long id, Long parent, boolean setMain) {
        String update = """
                {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,"timestamp-ms":%d,
                 "manifest-list":"%s/metadata/snap-%d.avro","summary":{"operation":"append"},"schema-id":0}}"""
                .formatted(id, parent == null ? "" : "\"parent-snapshot-id\":" + parent + ",", id,
                        System.currentTimeMillis(), location, id);
        String ref = ",{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"snapshot-id\":" + id
                + ",\"type\":\"branch\"}";
        return "{\"requirements\":[],\"updates\":[" + update + (setMain ? ref : "") + "]}";
    }

    private static String transaction(String... changes) {
        return "{\"table-changes\":[" + String.join(",", changes) + "]}";
    }

    private static String change(String namespace, String table, String requirement, String update) {
        return "{\"identifier\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + table + "\"},\"requirements\":["
                + requirement + "],\"updates\":[" + update + "]}";
    }

    private static String setK(String value) {
        return "{\"action\":\"set-properties\",\"updates\":{\"k\":\"" + value + "\"}}";
    }

    private static String assertUuid(String uuid) {
        return "{\"type\":\"assert-table-uuid\",\"uuid\":\"" + uuid + "\"}";
    }

    private String property(String namespace, String table) throws Exception {
        return JsonPath.read(send("GET", "/namespaces/" + ns(namespace) + "/tables/" + table, null).body(),
                "$.metadata.properties.k");
    }

    private String location(String namespace, String table) throws Exception {
        return JsonPath.read(send("GET", "/namespaces/" + ns(namespace) + "/tables/" + table, null).body(),
                "$.metadata-location");
    }

    private static String rename(String fromNamespace, String from, String toNamespace, String to) {
        return "{\"source\":{\"namespace\":[\"" + fromNamespace + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + toNamespace + "\"],\"name\":\"" + to + "\"}}";
    }

    private String namespace() throws Exception {
        String name = "ns_" + suffix();
        assertThat(createNamespace(Map.of(), name).statusCode()).isEqualTo(200);
        return name;
    }

    private HttpResponse<String> createNamespace(Map<String, String> properties, String... levels) throws Exception {
        String namespace = Arrays.stream(levels).map(l -> "\"" + l + "\"").collect(Collectors.joining(","));
        String props = properties.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(","));
        return send("POST", "/namespaces", "{\"namespace\":[" + namespace + "],\"properties\":{" + props + "}}");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A namespace path segment: levels joined by 0x1F, URL-encoded. */
    private static String ns(String... levels) {
        return Arrays.stream(levels)
                .map(l -> URLEncoder.encode(l, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("%1F"));
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        return request(method, URI.create("http://localhost:" + port + "/v1/trading" + path), body, headers);
    }

    private HttpResponse<String> request(String method, URI uri, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", IssuerForTests.bearer());
        if (body != null && Arrays.stream(headers).noneMatch("Content-Type"::equals)) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo(type);
    }
}
