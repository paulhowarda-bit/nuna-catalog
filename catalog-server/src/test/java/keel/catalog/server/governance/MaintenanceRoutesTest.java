package keel.catalog.server.governance;

import static keel.catalog.server.governance.Api.expect;
import static keel.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import keel.catalog.server.S3ForTests;
import keel.catalog.tests.s3.TestS3;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The maintenance worker's routes (D30): the work list of tables whose policy runs a job, the purge queue and
 * the due pending deletions, and the pending deletions it records and completes, which wait out the database's
 * PITR window (7 days here); and the delete policies an operator applies so that the worker alone deletes.
 */
@GovernanceServer
class MaintenanceRoutesTest {

    private static final String AUTH = "Authorization";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

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

    @Test
    void theWorkListCarriesTablesWhosePolicyRunsAJob() {
        String ns = api.namespace();
        String table = "/namespaces/" + ns + "/tables/kept";
        api.table(ns, "idle");
        api.table(ns, "kept");
        String idle = api.id(ns, "tables", "idle");
        String kept = api.id(ns, "tables", "kept");
        expect(api.manage("PUT", table + "/maintenance-policy",
                "{\"snapshot-expiry.enabled\":\"true\",\"compaction.enabled\":\"true\"}"), 200);

        Map<String, Object> listed = listed("tables", kept).orElseThrow();

        assertThat(listed("tables", idle)).as("no job runs on it").isEmpty();
        assertThat(listed.get("catalog")).isEqualTo(Api.CATALOG);
        assertThat(listed.get("namespace")).isEqualTo(List.of(ns));
        assertThat(listed.get("name")).isEqualTo("kept");
        assertThat(listed.get("location")).isEqualTo(location(kept));
        assertThat(policy(listed)).containsEntry("snapshot-expiry.enabled", "true")
                .containsEntry("compaction.enabled", "true").containsEntry("orphan-removal.enabled", "false")
                .containsEntry("snapshot-expiry.max-snapshot-age", "P5D");

        String hold = JsonPath.read(expect(api.manage("POST", table + "/holds", "{\"reason\":\"matter 9\"}",
                AUTH, api.steward()), 201).body(), "$.id");
        assertThat(policy(listed("tables", kept).orElseThrow())).as("a hold stops expiry, not compaction")
                .containsEntry("snapshot-expiry.enabled", "false").containsEntry("compaction.enabled", "true");
        expect(api.manage("DELETE", table + "/holds/" + hold, null, AUTH, api.steward()), 204);
        expect(api.manage("PUT", "/namespaces/" + ns + "/dr-freeze", "{\"reason\":\"drill\"}", AUTH, api.operator()), 200);
        assertThat(listed("tables", kept)).as("a DR freeze stops every job").isEmpty();
        expect(api.manage("DELETE", "/namespaces/" + ns + "/dr-freeze", null, AUTH, api.operator()), 204);
        expect(api.rest("DELETE", table, null), 204);
        assertThat(listed("tables", kept)).as("a dropped table is not maintained").isEmpty();
    }

    @Test
    void theWorkListCarriesThePurgeQueue() {
        String ns = api.namespace();
        api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        String location = location(id);
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t", null), 204);
        assertThat(listed("purges", id)).isEmpty();
        clock.advance(Duration.ofDays(31));

        Map<String, Object> queued = listed("purges", id).orElseThrow();

        assertThat(queued.get("catalog")).isEqualTo(Api.CATALOG);
        assertThat(queued.get("state")).isEqualTo("dropped");
        assertThat(queued.get("location")).isEqualTo(location);
    }

    @Test
    void pendingDeletionsWaitOutThePitrWindow() {
        String ns = api.namespace();
        api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        String root = location(id);
        Instant before = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        HttpResponse<String> recorded = expect(record(id, root + "/data/a.parquet", root + "/metadata/b.avro"), 201);
        assertThat(JsonPath.<Integer>read(recorded.body(), "$.recorded")).isEqualTo(2);
        assertThat(Instant.parse(JsonPath.read(recorded.body(), "$.not-before")))
                .isAfterOrEqualTo(before.plus(Duration.ofDays(7)));

        assertThat(due(id)).isEmpty();
        clock.advance(Duration.ofDays(6));
        assertThat(due(id)).as("not before the PITR window has passed").isEmpty();
        clock.advance(Duration.ofDays(1).plusMinutes(1));

        List<Map<String, Object>> due = due(id);

        assertThat(due).extracting(deletion -> deletion.get("location"))
                .containsExactlyInAnyOrder(root + "/data/a.parquet", root + "/metadata/b.avro");
        assertThat(due).extracting(deletion -> deletion.get("catalog")).containsOnly(Api.CATALOG);
        String ids = "{\"ids\":[\"" + due.get(0).get("id") + "\",\"" + due.get(1).get("id") + "\"]}";
        expect(complete(id, ids), 204);
        assertThat(due(id)).isEmpty();
        expect(complete(id, ids), 204);
    }

    @Test
    void onlyAnActiveOrDroppedTablesOwnFilesAreRecorded() {
        String ns = api.namespace();
        api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        String root = location(id);

        expectError(record(id, "s3://elsewhere/x.parquet"), 400, "BadRequestException");
        expectError(record(id, root + "-twin/x.parquet"), 400, "BadRequestException");
        expectError(api.manage("POST", "/objects/" + id + "/pending-deletions", "{\"locations\":[]}",
                GovernanceServer.PRINCIPAL, "maintenance"), 400, "BadRequestException");
        expect(api.rest("POST", "/namespaces/" + ns + "/tables/t/unregister", null), 200);
        expectError(record(id, root + "/data/a.parquet"), 409, "NotDeletableException");
        expectError(api.manage("GET", "/maintenance/work?section=nothing", null, AUTH, api.maintenance()), 400,
                "BadRequestException");
    }

    @Test
    void theDeletePoliciesLeaveDeleteToTheWorker() {
        TestS3 s3 = TestS3.get();
        String body = expect(api.manage("GET", "/maintenance/delete-policies", null, AUTH, api.operator()), 200).body();

        List<Map<String, Object>> policies = JsonPath.read(body, "$.policies");

        assertThat(JsonPath.<List<String>>read(body, "$.unprotected")).isEmpty();
        assertThat(policies).hasSize(1);
        Map<String, Object> policy = policies.get(0);
        assertThat(policy.get("profiles")).isEqualTo(List.of(S3ForTests.PROFILE));
        assertThat(policy.get("endpoint")).isEqualTo(s3.endpoint.toString());
        String text = (String) policy.get("policy");
        assertThat(JsonPath.<String>read(text, "$.Statement[0].Effect")).isEqualTo("Deny");
        assertThat(JsonPath.<List<String>>read(text, "$.Statement[0].Action")).contains("s3:DeleteObject");
        assertThat(JsonPath.<List<String>>read(text, "$.Statement[0].Resource"))
                .containsExactly("arn:aws:s3:::" + S3ForTests.bucket() + "/tables/*");
        if (s3.kind == TestS3.Kind.SILO) {
            assertThat(policy.get("apply-as")).isEqualTo("user-policy");
            assertThat(policy.get("except")).isEqualTo(s3.maintenancePrincipal());
        } else {
            assertThat(policy.get("apply-as")).isEqualTo("bucket-policy");
            assertThat(policy.get("bucket")).isEqualTo(S3ForTests.bucket());
            assertThat(text).contains(s3.maintenancePrincipal());
        }
    }

    private HttpResponse<String> record(String id, String... locations) {
        return api.manage("POST", "/objects/" + id + "/pending-deletions",
                "{\"locations\":[\"" + String.join("\",\"", locations) + "\"]}", GovernanceServer.PRINCIPAL, "maintenance");
    }

    private HttpResponse<String> complete(String id, String body) {
        return api.manage("POST", "/objects/" + id + "/pending-deletions/complete", body,
                GovernanceServer.PRINCIPAL, "maintenance");
    }

    private String location(String id) {
        return JsonPath.read(expect(api.manage("GET", "/objects/" + id + "/export", null), 200).body(), "$.object.location");
    }

    private List<Map<String, Object>> due(String objectId) {
        return section("deletions").stream().filter(deletion -> objectId.equals(deletion.get("object-id"))).toList();
    }

    private Optional<Map<String, Object>> listed(String section, String id) {
        return section(section).stream().filter(item -> id.equals(item.get("id"))).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> policy(Map<String, Object> table) {
        return (Map<String, String>) table.get("policy");
    }

    /** Every item of one section of the work list, page by page. */
    private List<Map<String, Object>> section(String section) {
        List<Map<String, Object>> items = new ArrayList<>();
        String token = null;
        do {
            HttpResponse<String> page = expect(api.manage("GET", "/maintenance/work?section=" + section + "&pageSize=1000"
                    + (token == null ? "" : "&pageToken=" + token), null, AUTH, api.maintenance()), 200);
            Map<String, Object> body = JsonPath.read(page.body(), "$");
            items.addAll(JsonPath.<List<Map<String, Object>>>read(page.body(), "$." + section));
            token = (String) body.get("next-page-token");
        } while (token != null);
        return items;
    }
}
