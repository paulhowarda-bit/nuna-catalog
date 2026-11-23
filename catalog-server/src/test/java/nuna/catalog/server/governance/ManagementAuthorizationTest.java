package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventType;
import nuna.catalog.server.S3ForTests;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Who may call each M7 management route (build-plan.md, M7). Each test signs in first as a caller that holds
 * something, but not what the route needs, and expects 403; then as one that holds it, and expects success. A route
 * that needs only describe, which every privilege gives, is refused only to a caller that holds nothing on the
 * name, which is answered as if the name did not exist (D18, backlog 73).
 * The callers: {@code tester} holds create and modify on the catalog, the steward manage-tags, the outsider
 * nothing; the operator is a platform operator and {@code maintenance} the maintenance principal.
 */
@Tag("heavy")
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

    @Value("${nuna.storage.profiles." + S3ForTests.PROFILE + ".access-key-id:}")
    private String accessKey;

    @Value("${nuna.storage.profiles." + S3ForTests.PROFILE + ".secret-access-key:}")
    private String secretKey;

    @Value("${nuna.storage.profiles." + S3ForTests.PROFILE + ".sts-role:}")
    private String stsRole;

    @Value("${nuna.storage.profiles." + S3ForTests.PROFILE + ".maintenance-principal:}")
    private String maintenancePrincipal;

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
        hidden(api.manage("GET", table + "/governance", null, AUTH, api.bystander()), "NoSuchTableException");
        // Holding nothing in the catalog at all, the outsider is answered as for a missing catalog (item 68).
        hidden(api.manage("GET", table + "/governance", null, AUTH, api.outsider()), "NoSuchWarehouseException");
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
        hidden(api.manage("GET", table + "/maintenance-policy", null, AUTH, api.bystander()), "NoSuchTableException");
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
        hidden(api.manage("GET", "/namespaces/" + ns + "/dropped", null, AUTH, api.bystander()),
                "NoSuchNamespaceException");
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
    void theMetadataLogNeedsSelect() {
        String id = api.id(ns, "tables", "t");
        refused(api.manage("GET", "/objects/" + id + "/metadata-log", null, AUTH, api.steward()));
        HttpResponse<String> log = expect(api.manage("GET", "/objects/" + id + "/metadata-log", null), 200);
        assertThat(JsonPath.<List<Integer>>read(log.body(), "$.journal[*].new-version")).containsExactly(1);
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
    void theDrWorkListAndReportsAreForTheMaintenancePrincipal() {
        String id = api.id(ns, "tables", "t");
        String report = "/maintenance/tables/" + id + "/dr-watermark";
        String location = JsonPath.read(expect(api.rest("GET", table, null), 200).body(), "$.metadata-location");
        String body = "{\"version\":1,\"metadata-location\":\"" + location + "\"}";
        refused(api.manage("GET", "/maintenance/dr-work", null));
        refused(api.manage("GET", "/maintenance/dr-work", null, AUTH, api.operator()));
        refused(api.manage("PUT", report, body));
        refused(api.manage("PUT", report, body, AUTH, api.operator()));
        expect(api.manage("GET", "/maintenance/dr-work", null, AUTH, api.maintenance()), 200);
        expect(api.manage("PUT", report, body, AUTH, api.maintenance()), 204);
    }

    @Test
    void makingRenewingAndDeletingARefNeedCreateOnTheCatalog() {
        String name = "auth-" + Api.suffix();
        String body = "{\"name\":\"" + name + "\",\"kind\":\"branch\"}";
        refused(api.manage("POST", "/refs", body, AUTH, api.steward()));
        expect(api.manage("POST", "/refs", body), 201);
        clock.advance(java.time.Duration.ofDays(1));
        refused(api.manage("POST", "/refs/" + name + "/renew", null, AUTH, api.steward()));
        expect(api.manage("POST", "/refs/" + name + "/renew", null), 200);
        refused(api.manage("DELETE", "/refs/" + name, null, AUTH, api.steward()));
        expect(api.manage("DELETE", "/refs/" + name, null), 204);
    }

    @Test
    void listingAndReadingRefsNeedAnyHoldingInTheCatalog() {
        String name = "auth-" + Api.suffix();
        expect(api.manage("POST", "/refs", "{\"name\":\"" + name + "\",\"kind\":\"tag\"}"), 201);
        hidden(api.manage("GET", "/refs", null, AUTH, api.outsider()), "NoSuchWarehouseException");
        hidden(api.manage("GET", "/refs/" + name, null, AUTH, api.outsider()), "NoSuchWarehouseException");
        expect(api.manage("GET", "/refs", null, AUTH, api.bystander()), 200);
        expect(api.manage("GET", "/refs/" + name, null, AUTH, api.bystander()), 200);
    }

    @Test
    void warningOfAndEndingABranchAreForTheMaintenancePrincipal() {
        String id = JsonPath.read(expect(api.manage("POST", "/refs", "{\"name\":\"auth-" + Api.suffix()
                + "\",\"kind\":\"branch\"}"), 201).body(), "$.id");
        refused(api.manage("POST", "/maintenance/refs/" + id + "/warn", null));
        refused(api.manage("POST", "/maintenance/refs/" + id + "/end", null, AUTH, api.operator()));
        expectError(api.manage("POST", "/maintenance/refs/" + id + "/warn", null, AUTH, api.maintenance()), 409,
                "RefNotExpiringException");
        expectError(api.manage("POST", "/maintenance/refs/" + id + "/end", null, AUTH, api.maintenance()), 409,
                "RefNotExpiredException");
    }

    @Test
    void theDrWatermarksAreForAPlatformOperator() {
        refused(api.send("GET", "/management/v1/dr/watermarks", null));
        refused(api.send("GET", "/management/v1/dr/watermarks", null, AUTH, api.maintenance()));
        expect(api.send("GET", "/management/v1/dr/watermarks", null, AUTH, api.operator()), 200);
    }

    @Test
    void theCatalogListShowsOnlyCatalogsTheCallerHoldsSomethingOn() {
        // D18, amended 2026-11-19 (item 20). The tester and the steward hold grants on the catalog, the grant
        // admin manage-grants on every catalog; the operator holds none but runs the deployment.
        assertThat(catalogs(null)).containsExactly(Api.CATALOG);
        for (String holder : List.of(api.steward(), api.grantAdmin(), api.operator())) {
            assertThat(catalogs(holder)).containsExactly(Api.CATALOG);
        }
        assertThat(catalogs(api.outsider())).isEmpty();

        // A grant on one table is something in the catalog; revoked, it is gone.
        String readers = "table-readers-" + Api.suffix();
        String grant = "{\"securable\":{\"type\":\"table\",\"namespace\":[\"" + ns + "\"],\"name\":\"t\"},"
                + "\"group\":\"" + readers + "\",\"privilege\":\"select\"}";
        assertThat(catalogs(null, GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers)).isEmpty();
        expect(api.manage("POST", "/grants", grant, AUTH, api.grantAdmin()), 204);
        assertThat(catalogs(null, GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers))
                .containsExactly(Api.CATALOG);
        expect(api.manage("POST", "/grants/revoke", grant, AUTH, api.grantAdmin()), 204);
        assertThat(catalogs(null, GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers)).isEmpty();
    }

    @Test
    void readingACatalogsPropertiesNeedsSomethingInIt() {
        // Item 131: the outsider holds nothing in the catalog and is answered as for a missing one.
        hidden(api.manage("GET", "/properties", null, AUTH, api.outsider()), "NoSuchWarehouseException");
        for (String holder : java.util.Arrays.asList(null, api.steward(), api.operator())) {
            String[] auth = holder == null ? new String[0] : new String[] {AUTH, holder};
            String body = expect(api.manage("GET", "/properties", null, auth), 200).body();
            assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo(Api.CATALOG);
            assertThat(JsonPath.<String>read(body, "$.kms-key-set")).isEqualTo("test-keys");
            assertThat(JsonPath.<String>read(body, "$.legal-entity")).isEqualTo("Test Entity");
            assertThat(JsonPath.<String>read(body, "$.storage-profile.name")).isEqualTo(S3ForTests.PROFILE);
            assertThat(JsonPath.<String>read(body, "$.storage-profile.table-root"))
                    .isEqualTo("s3://" + S3ForTests.bucket() + "/tables");
            // The profile's keys, role and maintenance principal stay out.
            for (String kept : new String[] {accessKey, secretKey, stsRole, maintenancePrincipal}) {
                if (!kept.isEmpty()) {
                    assertThat(body).doesNotContain(kept);
                }
            }
        }
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

    @Test
    void aRefusalAnsweredAsAMissingNameIsAuditedToo() {
        String requestId = "hidden-" + UUID.randomUUID();
        hidden(api.manage("GET", table + "/governance", null, AUTH, api.bystander(), "X-Request-ID", requestId),
                "NoSuchTableException");
        var record = api.awaitRequest(requestId);
        assertThat(record.data().type()).isEqualTo(EventType.DENIAL);
        Denial denial = (Denial) record.data();
        assertThat(denial.operation()).isEqualTo("loadGovernance");
        assertThat(denial.privilege()).isEqualTo("describe");
        assertThat(denial.securable().path()).containsExactly(ns, "t");
    }

    private static void refused(HttpResponse<String> response) {
        expectError(response, 403, "ForbiddenException");
    }

    /** A refusal answered as if the name did not exist (D18, backlog 73). */
    private static void hidden(HttpResponse<String> response, String type) {
        expectError(response, 404, type);
    }

    /**
     * The names {@code GET /management/v1/catalogs} lists to the caller {@code authorization} names, or, when it is
     * null, to the one {@code headers} name ({@link Api#send}).
     */
    private List<String> catalogs(String authorization, String... headers) {
        String[] sent = authorization == null ? headers : new String[] {AUTH, authorization};
        return JsonPath.read(expect(api.send("GET", "/management/v1/catalogs", null, sent), 200).body(),
                "$.catalogs[*].name");
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
