package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.LabelChange;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The classification push API with provenance (D27) and the maintenance policy's inheritance and guardrails (D30). */
@GovernanceServer
class GovernanceRoutesTest {

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
    void pushedAttributesKeepTheirProvenanceAndTheNearestLevelWins() {
        String ns = api.namespace();
        api.table(ns, "t");
        String table = "/namespaces/" + ns + "/tables/t";
        expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/classification",
                "{\"value\":\"internal\",\"provenance\":\"collibra:domain/9\"}", "Authorization", api.steward()), 200);
        expect(api.manage("PUT", table + "/attributes/classification",
                "{\"value\":\"restricted\",\"provenance\":\"collibra:asset/42\"}", GovernanceServer.PRINCIPAL,
                "governance-sync", GovernanceServer.GROUPS, GovernanceServer.STEWARDS), 200);

        HttpResponse<String> governance = expect(api.manage("GET", table + "/governance", null), 200);
        assertThat(JsonPath.<String>read(governance.body(), "$.effective.classification")).isEqualTo("restricted");
        assertThat(JsonPath.<List<String>>read(governance.body(), "$.attributes[*].provenance"))
                .containsExactly("collibra:asset/42", "collibra:domain/9");
        assertThat(JsonPath.<String>read(governance.body(), "$.attributes[0].set-by"))
                .isEqualTo("governance-sync");

        expectError(api.manage("PUT", table + "/attributes/classification", "{\"value\":\"x\"}",
                "Authorization", api.steward()), 400, "BadRequestException");
        expectError(api.manage("PUT", table + "/attributes/regulated", "{\"value\":\"yes\",\"provenance\":\"p\"}",
                "Authorization", api.steward()), 400, "BadRequestException");
        expectError(api.manage("PUT", table + "/attributes/colour", "{\"value\":\"x\",\"provenance\":\"p\"}",
                "Authorization", api.steward()), 404, "NoSuchAttributeException");
        expectError(api.manage("DELETE", table + "/attributes/owner", null, "Authorization", api.steward()), 404,
                "NoSuchAttributeException");
        expectError(api.manage("GET", "/namespaces/" + ns + "/tables/nope/governance", null), 404,
                "NoSuchTableException");
    }

    /**
     * D27, amended 2026-11-12: a load sends the effective classification, owner and retention class as object
     * labels, never provenance or flags; a label changed on any level above changes the table's ETag; and each
     * change also goes out as a label change naming the level, the label and its new value.
     */
    @Test
    void loadsCarryTheEffectiveLabelsAndALabelChangeChangesTheETag() {
        String ns = api.namespace();
        api.table(ns, "t");
        api.view(ns, "v");
        String table = "/namespaces/" + ns + "/tables/t";
        String tableId = api.id(ns, "tables", "t");
        HttpResponse<String> unlabelled = expect(load(table), 200);
        assertThat(JsonPath.<Map<String, Object>>read(unlabelled.body(), "$")).doesNotContainKey("labels");
        String etag = unlabelled.headers().firstValue("ETag").orElseThrow();
        expect(load(table, "If-None-Match", etag), 304);

        expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/classification",
                "{\"value\":\"internal\",\"provenance\":\"collibra:domain/9\"}", "Authorization", api.steward()), 200);
        expect(api.manage("PUT", table + "/attributes/owner", "{\"value\":\"desk-7\",\"provenance\":\"hr-feed\"}",
                "Authorization", api.steward()), 200);

        HttpResponse<String> labelled = expect(load(table, "If-None-Match", etag), 200);
        assertThat(JsonPath.<Map<String, Object>>read(labelled.body(), "$.labels.object-labels"))
                .isEqualTo(Map.of("classification", "internal", "owner", "desk-7"));
        assertThat(labelled.body()).doesNotContain("collibra", "hr-feed");
        String labelledTag = labelled.headers().firstValue("ETag").orElseThrow();
        assertThat(labelledTag).isNotEqualTo(etag);
        expect(load(table, "If-None-Match", labelledTag), 304);
        assertThat(JsonPath.<Map<String, Object>>read(expect(api.rest("GET", "/namespaces/" + ns + "/views/v", null),
                200).body(), "$.labels.object-labels")).isEqualTo(Map.of("classification", "internal"));

        LabelChange owner = (LabelChange) api.awaitRecord(json -> json.contains("\"nuna.catalog.label\"")
                && json.contains("desk-7")).data();
        assertThat(owner.target().id().toString()).isEqualTo(tableId);
        assertThat(owner.label()).isEqualTo("owner");
        assertThat(owner.value()).isEqualTo("desk-7");

        expect(api.manage("DELETE", table + "/attributes/owner", null, "Authorization", api.steward()), 204);
        HttpResponse<String> removed = expect(load(table, "If-None-Match", labelledTag), 200);
        assertThat(JsonPath.<Map<String, Object>>read(removed.body(), "$.labels.object-labels"))
                .isEqualTo(Map.of("classification", "internal"));
        LabelChange gone = (LabelChange) api.awaitRecord(json -> json.contains("\"nuna.catalog.label\"")
                && json.contains(tableId) && !json.contains("desk-7")).data();
        assertThat(gone.label()).isEqualTo("owner");
        assertThat(gone.value()).isNull();
    }

    /**
     * Loads a table through the trusted engine, declining credentials (D20). Vended credentials expire by the S3
     * store's clock, but the server judges them by the shared test clock, which earlier tests move days ahead and
     * never back: a load after such a move would see them as expiring and send no ETag. With none vended, the ETag
     * depends only on the table and its labels.
     */
    private HttpResponse<String> load(String table, String... headers) {
        String[] all = new String[headers.length + 4];
        all[0] = GovernanceServer.ENGINE;
        all[1] = "true";
        all[2] = "X-Iceberg-Access-Delegation";
        all[3] = "none";
        System.arraycopy(headers, 0, all, 4, headers.length);
        return api.rest("GET", table, null, all);
    }

    @Test
    void maintenancePolicyInheritsKeyByKeyAndPassesTheGuardrails() {
        String ns = api.namespace();
        api.table(ns, "t");
        String namespace = "/namespaces/" + ns;
        String table = namespace + "/tables/t";
        expect(api.manage("PUT", namespace + "/maintenance-policy",
                "{\"snapshot-expiry.enabled\":true,\"snapshot-expiry.max-snapshot-age\":\"P10D\","
                        + "\"orphan-removal.enabled\":true}"), 200);
        expect(api.manage("PUT", table + "/maintenance-policy", "{\"snapshot-expiry.max-snapshot-age\":\"P2D\"}"), 200);

        HttpResponse<String> policy = expect(api.manage("GET", table + "/maintenance-policy", null), 200);
        String body = policy.body();
        assertThat(JsonPath.<String>read(body, "$.policy['snapshot-expiry.max-snapshot-age']")).isEqualTo("PT48H");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].value")).isEqualTo("PT48H");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].source"))
                .startsWith("table:");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.enabled'].source")).startsWith("namespace:");
        assertThat(JsonPath.<String>read(body, "$.resolved['compaction.enabled'].source")).isEqualTo("built-in");

        expect(api.manage("PUT", namespace + "/attributes/retention-class",
                "{\"value\":\"trade-records\",\"provenance\":\"records-office\"}", "Authorization", api.steward()), 200);
        String hold = JsonPath.read(expect(api.manage("POST", table + "/holds", "{\"reason\":\"case 3\"}",
                "Authorization", api.steward()), 201).body(), "$.id");
        body = expect(api.manage("GET", table + "/maintenance-policy", null), 200).body();
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].value")).isEqualTo("PT720H");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].source"))
                .isEqualTo("guardrail");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.enabled'].value")).isEqualTo("false");
        assertThat(JsonPath.<String>read(body, "$.resolved['orphan-removal.enabled'].value")).isEqualTo("false");
        expect(api.manage("DELETE", table + "/holds/" + hold, null, "Authorization", api.steward()), 204);

        expectError(api.manage("PUT", table + "/maintenance-policy", "{\"orphan-removal.older-than\":\"P1D\"}"), 400,
                "BadRequestException");
        expectError(api.manage("PUT", table + "/maintenance-policy", "{\"snapshot-expiry.max-snapshot-age\":\"PT1M\"}"),
                400, "BadRequestException");
        expectError(api.manage("PUT", table + "/maintenance-policy", "{\"vacuum\":true}"), 400, "BadRequestException");

        expect(api.manage("DELETE", table + "/maintenance-policy", null), 204);
        body = expect(api.manage("GET", table + "/maintenance-policy", null), 200).body();
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].source"))
                .isEqualTo("guardrail");
        expectError(api.manage("DELETE", table + "/maintenance-policy", null), 404, "NoSuchAttributeException");
    }
}
