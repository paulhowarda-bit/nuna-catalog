package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventType;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Who may call each M7 management route (build-plan.md, M7). Each test signs in first as a caller that holds
 * something, but not what the route needs, and expects 403; then as one that holds it, and expects success.
 * The callers: {@code tester} holds create and modify on the catalog, the steward manage-tags, the outsider
 * nothing; the operator is a platform operator and {@code maintenance} the maintenance principal.
 */
@GovernanceServer
class ManagementAuthorizationTest {

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
    private String ns;
    private String table;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        ns = api.namespace();
        api.table(ns, "t");
        table = "/namespaces/" + ns + "/tables/t";
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    @Test
    void readingGovernanceNeedsDescribe() {
        refused(api.manage("GET", table + "/governance", null, AUTH, api.outsider()));
        expect(api.manage("GET", table + "/governance", null), 200);
    }

    @Test
    void settingAnAttributeNeedsManageTags() {
        String body = "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/1\"}";
        refused(api.manage("PUT", table + "/attributes/classification", body));
        expect(api.manage("PUT", table + "/attributes/classification", body, AUTH, api.steward()), 200);
    }

    @Test
    void removingAnAttributeNeedsManageTags() {
        expect(api.manage("PUT", table + "/attributes/owner", "{\"value\":\"desk\",\"provenance\":\"hr\"}",
                AUTH, api.steward()), 200);
        refused(api.manage("DELETE", table + "/attributes/owner", null));
        expect(api.manage("DELETE", table + "/attributes/owner", null, AUTH, api.steward()), 204);
    }

    @Test
    void placingAHoldNeedsManageTags() {
        refused(api.manage("POST", table + "/holds", "{\"reason\":\"case 9\"}"));
        String hold = hold();
        expect(api.manage("DELETE", table + "/holds/" + hold, null, AUTH, api.steward()), 204);
    }

    @Test
    void liftingAHoldNeedsManageTags() {
        String hold = hold();
        refused(api.manage("DELETE", table + "/holds/" + hold, null));
        expect(api.manage("DELETE", table + "/holds/" + hold, null, AUTH, api.steward()), 204);
    }

    @Test
    void readingTheMaintenancePolicyNeedsDescribe() {
        refused(api.manage("GET", table + "/maintenance-policy", null, AUTH, api.outsider()));
        expect(api.manage("GET", table + "/maintenance-policy", null, AUTH, api.steward()), 200);
    }

    @Test
    void settingTheMaintenancePolicyNeedsModify() {
        String body = "{\"compaction.enabled\":true}";
        refused(api.manage("PUT", table + "/maintenance-policy", body, AUTH, api.steward()));
        expect(api.manage("PUT", table + "/maintenance-policy", body), 200);
    }

    @Test
    void removingTheMaintenancePolicyNeedsModify() {
        expect(api.manage("PUT", table + "/maintenance-policy", "{\"compaction.enabled\":true}"), 200);
        refused(api.manage("DELETE", table + "/maintenance-policy", null, AUTH, api.steward()));
        expect(api.manage("DELETE", table + "/maintenance-policy", null), 204);
    }

    @Test
    void settingADrFreezeNeedsAPlatformOperator() {
        String freeze = "/namespaces/" + ns + "/dr-freeze";
        refused(api.manage("PUT", freeze, "{\"reason\":\"drill\"}"));
        expect(api.manage("PUT", freeze, "{\"reason\":\"drill\"}", AUTH, api.operator()), 200);
        expect(api.manage("DELETE", freeze, null, AUTH, api.operator()), 204);
    }

    @Test
    void reconcilingFromStorageNeedsAPlatformOperator() {
        refused(api.manage("POST", "/dr-reconcile", null));
        refused(api.manage("POST", "/dr-reconcile", null, AUTH, api.steward()));
        expect(api.manage("POST", "/dr-reconcile", null, AUTH, api.operator()), 200);
    }

    @Test
    void liftingADrFreezeNeedsAPlatformOperator() {
        String freeze = "/namespaces/" + ns + "/dr-freeze";
        expect(api.manage("PUT", freeze, "{\"reason\":\"drill\"}", AUTH, api.operator()), 200);
        refused(api.manage("DELETE", freeze, null));
        expectError(api.rest("POST", table, Api.SET_PROPERTY.formatted("still frozen")), 403, "ForbiddenException");
        expect(api.manage("DELETE", freeze, null, AUTH, api.operator()), 204);
    }

    @Test
    void listingDroppedObjectsNeedsDescribe() {
        refused(api.manage("GET", "/namespaces/" + ns + "/dropped", null, AUTH, api.outsider()));
        expect(api.manage("GET", "/namespaces/" + ns + "/dropped", null, AUTH, api.steward()), 200);
    }

    @Test
    void undropNeedsCreateOnTheNamespace() {
        String id = dropped();
        refused(api.manage("POST", "/objects/" + id + "/undrop", null, AUTH, api.steward()));
        HttpResponse<String> undropped = expect(api.manage("POST", "/objects/" + id + "/undrop", null), 200);
        assertThat(JsonPath.<String>read(undropped.body(), "$.state")).isEqualTo("active");
    }

    @Test
    void exportNeedsSelect() {
        String id = api.id(ns, "tables", "t");
        refused(api.manage("GET", "/objects/" + id + "/export", null, AUTH, api.steward()));
        expect(api.manage("GET", "/objects/" + id + "/export", null), 200);
    }

    @Test
    void thePurgeQueueIsForTheMaintenancePrincipal() {
        refused(api.manage("GET", "/maintenance/purge-eligible", null));
        expect(api.manage("GET", "/maintenance/purge-eligible", null, AUTH, api.maintenance()), 200);
    }

    @Test
    void theWorkListIsForTheMaintenancePrincipal() {
        refused(api.manage("GET", "/maintenance/work", null));
        refused(api.manage("GET", "/maintenance/work?section=deletions", null, AUTH, api.steward()));
        expect(api.manage("GET", "/maintenance/work?section=deletions", null, AUTH, api.maintenance()), 200);
    }

    @Test
    void theDeletePoliciesAreForAPlatformOperator() {
        refused(api.manage("GET", "/maintenance/delete-policies", null));
        refused(api.manage("GET", "/maintenance/delete-policies", null, AUTH, api.maintenance()));
        expect(api.manage("GET", "/maintenance/delete-policies", null, AUTH, api.operator()), 200);
    }

    @Test
    void aStewardCanClassifyATableTheRegulatedRuleKeepsClosed() {
        expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/regulated",
                "{\"value\":\"true\",\"provenance\":\"records-office\"}", AUTH, api.steward()), 200);
        expectError(api.rest("GET", table, null), 403, "ForbiddenException");

        expect(api.manage("PUT", table + "/attributes/classification",
                "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/7\"}", AUTH, api.steward()), 200);
        expect(api.rest("GET", table, null), 200);
    }

    @Test
    void aRefusalIsAuditedAsADenialOfTheRoute() {
        String requestId = "refused-" + UUID.randomUUID();
        refused(api.manage("PUT", table + "/attributes/classification",
                "{\"value\":\"x\",\"provenance\":\"p\"}", "X-Request-ID", requestId));
        var record = api.awaitRequest(requestId);
        assertThat(record.data().type()).isEqualTo(EventType.DENIAL);
        Denial denial = (Denial) record.data();
        assertThat(denial.operation()).isEqualTo("setGovernanceAttribute");
        assertThat(denial.privilege()).isEqualTo("manage-tags");
        assertThat(denial.securable().path()).containsExactly(ns, "t");
    }

    private static void refused(HttpResponse<String> response) {
        expectError(response, 403, "ForbiddenException");
    }

    private String hold() {
        return JsonPath.read(expect(api.manage("POST", table + "/holds", "{\"reason\":\"case 9\"}", AUTH,
                api.steward()), 201).body(), "$.id");
    }

    /** The table's id, after the tester drops it. */
    private String dropped() {
        String id = api.id(ns, "tables", "t");
        expect(api.rest("DELETE", table, null), 204);
        return id;
    }
}
