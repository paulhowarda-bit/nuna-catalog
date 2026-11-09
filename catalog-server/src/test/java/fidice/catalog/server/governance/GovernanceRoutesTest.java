package fidice.catalog.server.governance;

import static fidice.catalog.server.governance.Api.expect;
import static fidice.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
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

    @Value("${local.server.port}")
    private int port;

    private Api api;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers());
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
                "{\"value\":\"internal\",\"provenance\":\"collibra:domain/9\"}"), 200);
        expect(api.manage("PUT", table + "/attributes/classification",
                "{\"value\":\"restricted\",\"provenance\":\"collibra:asset/42\"}", GovernanceServer.PRINCIPAL,
                "governance-sync"), 200);

        HttpResponse<String> governance = expect(api.manage("GET", table + "/governance", null), 200);
        assertThat(JsonPath.<String>read(governance.body(), "$.effective.classification")).isEqualTo("restricted");
        assertThat(JsonPath.<List<String>>read(governance.body(), "$.attributes[*].provenance"))
                .containsExactly("collibra:asset/42", "collibra:domain/9");
        assertThat(JsonPath.<String>read(governance.body(), "$.attributes[0].set-by"))
                .isEqualTo("governance-sync");

        expectError(api.manage("PUT", table + "/attributes/classification", "{\"value\":\"x\"}"), 400,
                "BadRequestException");
        expectError(api.manage("PUT", table + "/attributes/regulated", "{\"value\":\"yes\",\"provenance\":\"p\"}"),
                400, "BadRequestException");
        expectError(api.manage("PUT", table + "/attributes/colour", "{\"value\":\"x\",\"provenance\":\"p\"}"), 404,
                "NoSuchAttributeException");
        expectError(api.manage("DELETE", table + "/attributes/owner", null), 404, "NoSuchAttributeException");
        expectError(api.manage("GET", "/namespaces/" + ns + "/tables/nope/governance", null), 404,
                "NoSuchTableException");
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
                "{\"value\":\"trade-records\",\"provenance\":\"records-office\"}"), 200);
        String hold = JsonPath.read(expect(api.manage("POST", table + "/holds", "{\"reason\":\"case 3\"}"), 201)
                .body(), "$.id");
        body = expect(api.manage("GET", table + "/maintenance-policy", null), 200).body();
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].value")).isEqualTo("PT720H");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.max-snapshot-age'].source"))
                .isEqualTo("guardrail");
        assertThat(JsonPath.<String>read(body, "$.resolved['snapshot-expiry.enabled'].value")).isEqualTo("false");
        assertThat(JsonPath.<String>read(body, "$.resolved['orphan-removal.enabled'].value")).isEqualTo("false");
        expect(api.manage("DELETE", table + "/holds/" + hold, null), 204);

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
