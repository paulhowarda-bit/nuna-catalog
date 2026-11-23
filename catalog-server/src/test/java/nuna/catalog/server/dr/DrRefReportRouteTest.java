package nuna.catalog.server.dr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.S3ForTests;
import nuna.catalog.server.governance.GovernanceServer;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Ref;
import nuna.catalog.tests.s3.TestS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The reconcile's report on refs after a regional failover (D33, amended 2026-11-20: "A report only"; item 111), on
 * PostgreSQL and the run's S3 store, which stands in for the DR store. A table has a tag and two branches, made through
 * the refs route (item 106); one branch commits to the table (item 105), and that commit's metadata file is then taken
 * out of the store, as a file that never reached the DR store. The operator's reconcile lists that branch under
 * {@code refs}, with the table and the file its own pointer names, and no other ref naming a file of this test's table;
 * nothing moves. Once the branch is deleted, as the runbook says, it is gone from the report.
 *
 * <p>The catalog is the one every governance server test shares, and a ref's cut names all its tables, which would
 * refuse their purge claims, so the refs are deleted after the test. Other tests' tables may name files the store
 * lacks, so only this test's table is asserted on.
 */
@Tag("heavy")
@GovernanceServer
class DrRefReportRouteTest {

    private static final String CATALOG = "trading";
    private static final String TABLE = """
            {"name":"orders","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"k":"on-the-branch"}}]}""";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogStore store;

    @Autowired
    private Clock clock;

    @Autowired
    private TestS3 objectStore;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> made = new ArrayList<>();

    @AfterEach
    void deleteRefs() {
        made.forEach(name -> send("DELETE", "/management/v1/catalogs/" + CATALOG + "/refs/" + name, null, tester()));
    }

    @Test
    void aBranchWhoseHeadTheDrStoreLacksIsReportedAndNoOtherRef() {
        IssuerForTests.grant(jdbc, CATALOG, IssuerForTests.GROUP, "create", "modify");
        String ns = "ns_" + UUID.randomUUID().toString().substring(0, 8);
        expect(send("POST", "/v1/" + CATALOG + "/namespaces", "{\"namespace\":[\"" + ns + "\"]}", tester()), 200);
        expect(send("POST", "/v1/" + CATALOG + "/namespaces/" + ns + "/tables", TABLE, tester()), 200);
        CatalogRecord catalog = store.findCatalog(CATALOG).orElseThrow();
        NamespaceRecord namespace = store.findNamespace(catalog.id(), List.of(ns)).orElseThrow();
        CatalogObject table = store.findObject(namespace.id(), "orders").orElseThrow();
        Pointer main = store.readPointer(table.mainKey()).orElseThrow();
        String suffix = ns.substring(3);
        Ref tag = make("eod_" + suffix, "tag");
        Ref dev = make("dev_" + suffix, "branch");
        Ref qa = make("qa_" + suffix, "branch");
        // Dev's first commit to the table, on the primary: its metadata file never reached the DR store.
        String lost = JsonPath.read(expect(send("POST", "/v1/" + CATALOG + "@" + dev.name().display() + "/namespaces/"
                + ns + "/tables/orders", SET_PROPERTY, tester()), 200).body(), "$.metadata-location");
        assertThat(lost).as("a branch's file is marked").contains(".branch-" + dev.id());
        try (S3Client s3 = objectStore.client()) {
            s3.deleteObject(b -> b.bucket(S3ForTests.bucket())
                    .key(lost.substring(("s3://" + S3ForTests.bucket() + "/").length())));
        }

        String report = reconcile();

        assertThat(JsonPath.<Integer>read(report, "$.refs-checked")).isGreaterThanOrEqualTo(3);
        assertThat(namingThisTestsTable(report, ns)).as("refs naming a file of this test's table the store lacks")
                .containsOnlyKeys(dev.id().toString());
        assertThat(namingThisTestsTable(report, ns).get(dev.id().toString())).singleElement().satisfies(file ->
                assertThat(file).containsEntry("id", table.id().toString()).containsEntry("kind", "table")
                        .containsEntry("path", List.of(ns, "orders")).containsEntry("pointer", "head")
                        .containsEntry("version", 1).containsEntry("metadata-location", lost));
        String listed = "$.refs[?(@.id == '" + dev.id() + "')]";
        assertThat(JsonPath.<List<String>>read(report, listed + ".name")).containsExactly(dev.name().display());
        assertThat(JsonPath.<List<String>>read(report, listed + ".kind")).containsExactly("branch");
        assertThat(store.readPointer(table.mainKey())).as("a report only").contains(main);
        assertThat(store.readPointer(dev.key(table.id()))).as("a report only").map(Pointer::metadataLocation)
                .contains(lost);
        assertThat(List.of(tag, qa)).allSatisfy(ref -> assertThat(store.readRef(ref.id())).isPresent());

        // The runbook: delete a ref the report lists.
        expect(send("DELETE", "/management/v1/catalogs/" + CATALOG + "/refs/" + dev.name().display(), null,
                tester()), 204);
        made.remove(dev.name().display());

        assertThat(JsonPath.<List<Object>>read(reconcile(), listed)).isEmpty();
    }

    /** A ref made from main through the refs route (item 106), as the store then holds it. */
    private Ref make(String name, String kind) {
        expect(send("POST", "/management/v1/catalogs/" + CATALOG + "/refs",
                "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}", tester()), 201);
        made.add(name);
        return store.findRef(store.findCatalog(CATALOG).orElseThrow().id(), name).orElseThrow();
    }

    private String reconcile() {
        return expect(send("POST", "/management/v1/catalogs/" + CATALOG + "/dr-reconcile", null, operator()), 200)
                .body();
    }

    /** The refs the report lists, by id, each with its missing files that lie in namespace {@code ns}. */
    private static Map<String, List<Map<String, Object>>> namingThisTestsTable(String report, String ns) {
        Map<String, List<Map<String, Object>>> refs = new TreeMap<>();
        for (Map<String, Object> ref : JsonPath.<List<Map<String, Object>>>read(report, "$.refs")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> missing = ((List<Map<String, Object>>) ref.get("missing")).stream()
                    .filter(file -> ((List<?>) file.get("path")).getFirst().equals(ns)).toList();
            if (!missing.isEmpty()) {
                refs.put((String) ref.get("id"), missing);
            }
        }
        return refs;
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

    private String tester() {
        return token("tester", IssuerForTests.GROUP);
    }

    private String operator() {
        return token("ops", GovernanceServer.PLATFORM_OPS);
    }

    /** A token valid for an hour by the server's clock, which the governance tests move forward. */
    private String token(String user, String group) {
        Instant now = clock.instant();
        return IssuerForTests.sign(IssuerForTests.claims(user, "server-tests", group)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    }
}
