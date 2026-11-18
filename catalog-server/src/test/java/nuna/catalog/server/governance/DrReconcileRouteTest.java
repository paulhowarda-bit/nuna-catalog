package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static nuna.catalog.server.governance.Api.ns;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.EventType;
import nuna.catalog.server.S3ForTests;
import nuna.catalog.tests.s3.TestS3;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The DR reconcile route (D37). The database loses a table's last commits, as a DR replica behind its
 * storage would: the pointer row is put back to its first version while the files stay.
 */
@GovernanceServer
class DrReconcileRouteTest {

    private static final String AUTH = "Authorization";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

    @Autowired
    private TestS3 objectStore;

    @Value("${local.server.port}")
    private int port;

    private Api api;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    private String commit(String ns, String value) {
        return JsonPath.read(expect(api.rest("POST", "/namespaces/" + ns(ns) + "/tables/orders",
                Api.SET_PROPERTY.formatted(value)), 200).body(), "$.metadata-location");
    }

    @Test
    void aTableBehindItsStorageCatchesUpThroughItsProvenFiles() {
        String ns = api.namespace();
        String v1 = api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        Map<String, Object> first = jdbc.queryForMap("SELECT metadata_location, version, sha256, projection,"
                + " last_commit_id FROM pointer WHERE object_id = ?::uuid AND branch = 'main'", id);
        String v2 = commit(ns, "a");
        String v3 = commit(ns, "b");
        jdbc.update("UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = ?,"
                + " last_commit_id = ? WHERE object_id = ?::uuid AND branch = 'main'", first.get("metadata_location"),
                first.get("version"), first.get("sha256"), first.get("projection"), first.get("last_commit_id"), id);
        String stranger = "s3://" + S3ForTests.bucket() + "/tables/" + UUID.randomUUID();
        try (S3Client s3 = objectStore.client()) {
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(stranger.substring(("s3://" + S3ForTests.bucket()
                    + "/").length()) + "/metadata/00001-" + UUID.randomUUID() + ".metadata.json"), RequestBody.fromString("{}"));
        }

        HttpResponse<String> dryRun = expect(api.manage("POST", "/dr-reconcile", null, AUTH, api.operator()), 200);
        String table = "$.tables[?(@.id == '" + id + "')]";
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".from.metadata-location")).containsExactly(v1);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".steps[*].metadata-location")).containsExactly(v2);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".steps[*].proof")).containsExactly("successor");
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".tips[*]")).containsExactly(v3);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), "$.unknown-locations")).contains(stranger);
        assertThat(location(ns)).isEqualTo(v1);

        HttpResponse<String> unfrozen = expect(api.manage("POST", "/dr-reconcile", "{\"apply\":true}",
                AUTH, api.operator()), 200);
        assertThat(JsonPath.<List<String>>read(unfrozen.body(), table + ".apply-problem")).singleElement()
                .asString().contains("not frozen");
        assertThat(location(ns)).isEqualTo(v1);

        expect(api.manage("PUT", "/namespaces/" + ns(ns) + "/dr-freeze", "{\"reason\":\"DR drill\"}",
                AUTH, api.operator()), 200);
        String requestId = "m8-reconcile-" + UUID.randomUUID();
        HttpResponse<String> applied = expect(api.manage("POST", "/dr-reconcile",
                "{\"apply\":true,\"accept\":[\"" + v3 + "\",\"s3://" + S3ForTests.bucket() + "/tables/none\"]}",
                AUTH, api.operator(), Management.REQUEST_ID, requestId), 200);
        assertThat(JsonPath.<List<Integer>>read(applied.body(), table + ".steps-applied")).containsExactly(2);
        assertThat(JsonPath.<List<String>>read(applied.body(), table + ".steps[*].proof"))
                .containsExactly("successor", "accepted");
        assertThat(JsonPath.<List<String>>read(applied.body(), "$.unused-accepts")).containsExactly(
                "s3://" + S3ForTests.bucket() + "/tables/none");
        assertThat(location(ns)).isEqualTo(v3);
        assertThat(jdbc.queryForObject("SELECT version FROM pointer WHERE object_id = ?::uuid AND branch = 'main'",
                Long.class, id)).isEqualTo(3);
        Commit published = (Commit) api.awaitRecord(json -> json.contains(requestId) && json.contains(v3)
                && json.contains("\"type\":\"" + EventType.COMMIT.typeName() + "\"")).data();
        assertThat(published.actor().principal()).isEqualTo("ops");

        expectError(api.rest("POST", "/namespaces/" + ns(ns) + "/tables/orders", Api.SET_PROPERTY.formatted("c")),
                403, "ForbiddenException");
        expect(api.manage("DELETE", "/namespaces/" + ns(ns) + "/dr-freeze", null, AUTH, api.operator()), 204);
        String v4 = commit(ns, "c");
        assertThat(v4).contains("/metadata/00004-");
    }

    @Test
    void onlyAPlatformOperatorReconciles() {
        expectError(api.manage("POST", "/dr-reconcile", null), 403, "ForbiddenException");
        expectError(api.manage("POST", "/dr-reconcile", null, AUTH, api.steward()), 403, "ForbiddenException");
    }

    private String location(String ns) {
        return JsonPath.read(expect(api.rest("GET", "/namespaces/" + ns(ns) + "/tables/orders", null), 200).body(),
                "$.metadata-location");
    }
}
