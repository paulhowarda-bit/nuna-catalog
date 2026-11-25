package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.audit.AuthFailure;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.MaintenanceExemption;
import nuna.catalog.core.Names;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.server.governance.AuditLogCapture;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The M5 gate on the wire: authentication failures, privilege inheritance, separation of duties, flagged tables
 * (D20), unclassified tables in regulated namespaces (D27), the identity-less-commit switch (D17, on here), trusted
 * engines naming end users, the grant APIs and the engine registry; what unregister and register ask (D13); and the
 * maintenance exemption (D30), which svc-maintenance gets as itself and svc-trino, a maintenance principal too,
 * never for an end user. Every 401 and 403 this suite provokes must
 * leave exactly one audit event in the test sink behind the {@code AuditPublisher} seam; {@link #everyRefusalIsAudited}
 * checks that after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nuna.security.grant-admin-groups=grant-admins",
        "nuna.security.platform-operator-groups=platform-ops",
        "nuna.security.refuse-identity-less-commits=true",
        "nuna.security.maintenance-principals=svc-maintenance,svc-trino",
        "nuna.storage.catalogs.gate.profile=" + S3ForTests.PROFILE,
        "nuna.storage.catalogs.gate.kms-key-set=test-keys",
        "nuna.storage.catalogs.gate.legal-entity=Test Entity",
        "nuna.opa.catalogs.trino_gate=gate"})
@Tag("heavy")
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, AuditSinkForTests.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityGateTest {

    private static final String TABLE = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"}]},"properties":{"format-version":"2"}}""";
    private static final String VIEW = """
            {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"x","required":false,"type":"int"}]},
             "view-version":{"version-id":1,"timestamp-ms":%d,"schema-id":0,"summary":{"engine-name":"spark"},
              "representations":[{"type":"sql","sql":"select 1 as x","dialect":"spark"}],"default-namespace":[]},
             "properties":{}}""";
    private static final String SET_PROPERTY = """
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"touched":"%s"}}]}""";
    private static final String GRANTS = "/management/v1/catalogs/gate/grants";
    private static final String ENGINES = "/management/v1/engines";

    private static final String ANN = IssuerForTests.bearer("ann", "analysts");
    private static final String DANA = IssuerForTests.bearer("dana", "data-admins");
    private static final String GINA = IssuerForTests.bearer("gina", "grant-admins");
    private static final String OLGA = IssuerForTests.bearer("olga", "platform-ops");
    private static final String SAM = IssuerForTests.bearer("sam", "stewards");
    /** The maintenance worker, a maintenance principal acting as itself; it holds write on sales and no more. */
    private static final String WORKER = IssuerForTests.bearer("svc-maintenance", "maintainers");
    /** What snapshot expiry sends besides removing snapshots: the partition specs and schemas no snapshot uses. */
    private static final String EXPIRY = """
            {"requirements":[],"updates":[{"action":"remove-partition-specs","spec-ids":[]},
              {"action":"remove-schemas","schema-ids":[]}]}""";
    /** A registered trusted engine; its own service principal may write anywhere. */
    private static final String ENGINE = IssuerForTests.sign(IssuerForTests.claims("svc-trino", "trino-prod",
            "loaders").build());
    /** The same kind of client, never registered. */
    private static final String ROGUE = IssuerForTests.sign(IssuerForTests.claims("svc-rogue", "rogue-app",
            "loaders").build());

    private final HttpClient http = HttpClient.newHttpClient();
    private int refusals;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogStore store;

    @Autowired
    private AuditSinkForTests.Sink audit;

    // The relay writes each change to the audit log and takes it out of the outbox (D23, amended 2026-11-23). Made
    // once the Spring context is up: a context that starts resets logback and detaches it (backlog 270).
    private AuditLogCapture log;

    @AfterAll
    void closeTheLog() {
        log.close();
    }

    @BeforeAll
    void buildTheEstate() throws Exception {
        log = new AuditLogCapture();
        IssuerForTests.grant(jdbc, "gate", "data-admins", "create", "modify");
        IssuerForTests.grant(jdbc, "gate", "loaders", "modify");
        for (List<String> namespace : List.of(List.of("sales"), List.of("sales", "eu"), List.of("hr"),
                List.of("finance"))) {
            ok(send(DANA, "POST", "/v1/gate/namespaces", "{\"namespace\":" + json(namespace) + "}"));
        }
        for (String table : List.of("sales/eu/orders", "sales/eu/pii", "hr/staff", "finance/ledger",
                "finance/budget")) {
            List<String> path = List.of(table.split("/"));
            ok(send(DANA, "POST", "/v1/gate/namespaces/" + ns(path.subList(0, path.size() - 1)) + "/tables",
                    TABLE.formatted(path.getLast())));
        }
        attribute(table("sales/eu/pii").securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true");
        attribute(new Securable(Securable.Kind.NAMESPACE, namespace("finance").id()),
                GovernanceAttribute.Kind.REGULATED, "true");
        attribute(table("finance/budget").securable(), GovernanceAttribute.Kind.CLASSIFICATION, "internal");

        noContent(grant(GINA, namespaceSpec("sales"), "analysts", "select"));
        noContent(grant(GINA, namespaceSpec("finance"), "analysts", "select"));
        noContent(grant(GINA, namespaceSpec("sales"), "stewards", "manage-grants"));
        noContent(grant(GINA, namespaceSpec("sales"), "maintainers", "write"));
        noContent(send(OLGA, "PUT", ENGINES, """
                {"issuer":"%s","client-id":"trino-prod","end-user-claim":"sub","flags":["delegation-none"]}"""
                .formatted(IssuerForTests.ISSUER)));
    }

    @BeforeEach
    void startCounting() {
        audit.clear();
        refusals = 0;
    }

    @AfterEach
    void everyRefusalIsAudited() {
        assertThat(audit.denials().size() + audit.authFailures().size())
                .as("one audit event for each 401 or 403, and for each refusal answered as a missing name")
                .isEqualTo(refusals);
    }

    @Test
    void missingWrongAudienceExpiredAndUnknownIssuerTokensAreRefused() throws Exception {
        HttpResponse<String> none = send(null, "GET", "/v1/gate/namespaces", null);
        assertError(none, 401, "NotAuthorizedException");
        assertThat(none.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

        Instant now = Instant.now();
        JWTClaimsSet wrongAudience = IssuerForTests.claims("ann", "spark", "analysts").audience("someone-else").build();
        JWTClaimsSet expired = IssuerForTests.claims("ann", "spark", "analysts")
                .issueTime(Date.from(now.minusSeconds(3600))).expirationTime(Date.from(now.minusSeconds(600))).build();
        JWTClaimsSet unknownIssuer = IssuerForTests.claims("ann", "spark", "analysts")
                .issuer("https://rogue.test/").build();
        for (JWTClaimsSet claims : List.of(wrongAudience, expired, unknownIssuer)) {
            HttpResponse<String> refused = send(IssuerForTests.sign(claims), "GET", "/v1/gate/namespaces", null);
            assertError(refused, 401, "NotAuthorizedException");
            assertThat(refused.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
            assertThat(refused.body()).as("the reason stays in the audit record").doesNotContain("audience", "expired");
        }
        assertError(send("Bearer not.a.token", "GET", "/v1/gate/namespaces", null), 401, "NotAuthorizedException");

        List<AuthFailure> failures = audit.authFailures();
        assertThat(failures).hasSize(5);
        assertThat(failures.getFirst().reason()).isEqualTo("no bearer token");
        assertThat(failures.get(1).issuer()).isEqualTo(IssuerForTests.ISSUER);
        assertThat(failures.get(1).claimedSubject()).isEqualTo("ann");
        assertThat(failures.get(1).clientId()).isEqualTo("spark");
        assertThat(failures.get(1).reason()).containsIgnoringCase("aud");
        assertThat(failures.get(2).reason()).containsIgnoringCase("expired");
        assertThat(failures.get(3).issuer()).isEqualTo("https://rogue.test/");
        assertThat(failures.get(4).issuer()).isNull();
    }

    @Test
    void privilegesInheritDownTheTreeAndNeverSideways() throws Exception {
        ok(send(ANN, "GET", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders", null));
        ok(send(ANN, "GET", "/v1/gate/namespaces?parent=" + ns(List.of("sales")), null));

        // Ann holds nothing on hr: the table is hidden from her (D18), and the refusal is audited all the same.
        assertHidden(send(ANN, "GET", "/v1/gate/namespaces/hr/tables/staff", null), "NoSuchTableException",
                "loadTable");
        Denial denial = audit.denials().getLast();
        assertThat(denial.privilege()).isEqualTo("select");
        assertThat(denial.securable().path()).containsExactly("hr", "staff");
        assertThat(denial.actor().principal()).isEqualTo("ann");

        assertHidden(send(ANN, "HEAD", "/v1/gate/namespaces/hr/tables/staff", null), null, "tableExists");
        assertError(send(ANN, "POST", "/v1/gate/namespaces/sales/tables", TABLE.formatted("new_one")),
                403, "ForbiddenException");
        assertThat(audit.denials().getLast().privilege()).as("select gives no create").isEqualTo("create");
        assertError(send(ANN, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders",
                SET_PROPERTY.formatted("ann")), 403, "ForbiddenException");
        assertThat(audit.denials().getLast().privilege()).as("select gives no modify").isEqualTo("modify");
    }

    /**
     * D18, amended 2026-11-19 (backlog 14): a caller that holds nothing on a namespace, table or view, or above it,
     * or (for a namespace) on anything inside it, gets the answer a missing one gets, word for word but for the
     * name. One that holds anything there may learn that it exists, and is refused with 403. Every refusal is
     * audited either way.
     */
    @Test
    void namesAreHiddenFromACallerThatHoldsNothingOnThem() throws Exception {
        String hr = "/v1/gate/namespaces/hr";
        String nowhere = "/v1/gate/namespaces/nowhere";
        assertAnsweredAsMissing(send(ANN, "GET", hr, null), send(ANN, "GET", nowhere, null), "hr", "nowhere",
                "loadNamespaceMetadata");
        assertHidden(send(ANN, "HEAD", hr, null), null, "namespaceExists");
        assertAnsweredAsMissing(send(ANN, "GET", hr + "/tables", null), send(ANN, "GET", nowhere + "/tables", null),
                "hr", "nowhere", "listTables");
        assertAnsweredAsMissing(send(ANN, "GET", "/v1/gate/namespaces?parent=hr", null),
                send(ANN, "GET", "/v1/gate/namespaces?parent=nowhere", null), "hr", "nowhere", "listNamespaces");

        String staff = hr + "/tables/staff";
        String none = hr + "/tables/none";
        assertAnsweredAsMissing(send(ANN, "GET", staff, null), send(ANN, "GET", none, null), "staff", "none",
                "loadTable");
        assertAnsweredAsMissing(send(ANN, "GET", staff + "/credentials", null),
                send(ANN, "GET", none + "/credentials", null), "staff", "none", "loadCredentials");
        String report = """
                {"report-type":"commit-report","table-name":"t","snapshot-id":1,"sequence-number":1,
                 "operation":"append","metrics":{}}""";
        assertAnsweredAsMissing(send(ANN, "POST", staff + "/metrics", report),
                send(ANN, "POST", none + "/metrics", report), "staff", "none", "reportMetrics");
        assertAnsweredAsMissing(send(ANN, "DELETE", staff, null), send(ANN, "DELETE", none, null), "staff", "none",
                "dropTable");
        assertAnsweredAsMissing(send(ANN, "POST", staff + "/unregister", null),
                send(ANN, "POST", none + "/unregister", null), "staff", "none", "unregisterTable");
        assertThat(table("hr/staff").state()).isEqualTo(CatalogObject.State.ACTIVE);

        // Hugo holds describe on hr.staff alone: he may learn that hr and the table exist, and is refused the rest.
        String hugo = IssuerForTests.bearer("hugo", "hr-describers");
        noContent(grant(GINA, tableSpec("hr/staff"), "hr-describers", "describe"));
        noContent(send(hugo, "HEAD", staff, null));
        assertDenied(send(hugo, "GET", staff, null), "loadTable", "no_privilege");
        assertDenied(send(hugo, "GET", hr, null), "loadNamespaceMetadata", "no_privilege");
        assertThat(send(hugo, "HEAD", hr, null).statusCode()).isEqualTo(403);
        assertThat(audit.denials().getLast().operation()).isEqualTo("namespaceExists");
    }

    /**
     * D18, amended again 2026-11-19 (backlog 73): the management API hides names as the Iceberg routes do. Ann holds
     * nothing on hr, so its governance, its DR freeze and the grants on its tables answer as for a name that does
     * not exist, and the batch check answers for hr.staff as for a missing table; on sales, where she holds select,
     * she is refused with 403.
     */
    @Test
    void theManagementApiHidesNamesAsTheIcebergRoutesDo() throws Exception {
        String hr = "/management/v1/catalogs/gate/namespaces/hr";
        String nowhere = "/management/v1/catalogs/gate/namespaces/nowhere";
        assertAnsweredAsMissing(send(ANN, "GET", hr + "/governance", null),
                send(ANN, "GET", nowhere + "/governance", null), "hr", "nowhere", "loadGovernance");
        assertAnsweredAsMissing(send(ANN, "GET", hr + "/tables/staff/governance", null),
                send(ANN, "GET", hr + "/tables/none/governance", null), "staff", "none", "loadGovernance");
        assertAnsweredAsMissing(send(ANN, "GET", hr + "/tables/staff/governance", null),
                send(ANN, "GET", nowhere + "/tables/staff/governance", null), "hr", "nowhere", "loadGovernance");
        assertAnsweredAsMissing(send(ANN, "PUT", hr + "/dr-freeze", "{\"reason\":\"drill\"}"),
                send(ANN, "PUT", nowhere + "/dr-freeze", "{\"reason\":\"drill\"}"), "hr", "nowhere", "setDrFreeze");
        assertAnsweredAsMissing(send(ANN, "GET", GRANTS + "/on?type=table&namespace=hr&name=staff", null),
                send(ANN, "GET", GRANTS + "/on?type=table&namespace=hr&name=none", null), "staff", "none",
                "listGrants");
        assertDenied(send(ANN, "PUT", "/management/v1/catalogs/gate/namespaces/sales/attributes/owner",
                "{\"value\":\"desk\",\"provenance\":\"hr\"}"), "setGovernanceAttribute", "no_privilege");

        HttpResponse<String> checked = send(ANN, "POST", GRANTS + "/check", """
                {"checks":[
                  {"securable":{"type":"table","namespace":["hr"],"name":"staff"},"privilege":"select"},
                  {"securable":{"type":"table","namespace":["hr"],"name":"none"},"privilege":"select"}]}""");
        ok(checked);
        assertThat(JsonPath.<Object>read(checked.body(), "$.results[0]")).isEqualTo(java.util.Map.of(
                "privilege", "select", "allowed", false, "reason", "table does not exist: hr.staff"));
        assertThat(JsonPath.<Object>read(checked.body(), "$.results[1]")).isEqualTo(java.util.Map.of(
                "privilege", "select", "allowed", false, "reason", "table does not exist: hr.none"));
    }

    /**
     * A metrics report needs select on its table, as a load does; a refusal is audited like any other, and a caller
     * that holds nothing on the table is answered as if it did not exist (D18).
     */
    @Test
    void metricsAreReportedOnlyOnATableTheCallerMayRead() throws Exception {
        String report = """
                {"report-type":"commit-report","table-name":"t","snapshot-id":1,"sequence-number":1,
                 "operation":"append","metrics":{}}""";
        noContent(send(ANN, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders/metrics",
                report));
        assertHidden(send(ANN, "POST", "/v1/gate/namespaces/hr/tables/staff/metrics", report),
                "NoSuchTableException", "reportMetrics");
        assertThat(audit.denials().getLast().privilege()).isEqualTo("select");
    }

    /** D15: a transaction asks for each table what that table's own commit route asks; one refusal refuses all. */
    @Test
    void aTransactionNeedsTheRightOnEveryTable() throws Exception {
        String tia = IssuerForTests.bearer("tia", "txn-writers");
        noContent(grant(GINA, tableSpec("sales/eu/orders"), "txn-writers", "modify"));
        long orders = store.readPointer(table("sales/eu/orders").mainKey()).orElseThrow().version();
        long staff = store.readPointer(table("hr/staff").mainKey()).orElseThrow().version();

        // Tia holds nothing on hr.staff, so it is answered as missing (D18); the refusal still refuses the lot.
        assertHidden(send(tia, "POST", "/v1/gate/transactions/commit",
                transaction(List.of("sales", "eu", "orders"), List.of("hr", "staff"))), "NoSuchTableException",
                "updateTable");
        assertThat(audit.denials().getLast().securable().path()).containsExactly("hr", "staff");
        assertThat(store.readPointer(table("sales/eu/orders").mainKey()).orElseThrow().version()).isEqualTo(orders);
        assertThat(store.readPointer(table("hr/staff").mainKey()).orElseThrow().version()).isEqualTo(staff);

        noContent(send(tia, "POST", "/v1/gate/transactions/commit", transaction(List.of("sales", "eu", "orders"))));
        assertThat(store.readPointer(table("sales/eu/orders").mainKey()).orElseThrow().version()).isEqualTo(orders + 1);
    }

    @SafeVarargs
    private static String transaction(List<String>... tables) {
        StringBuilder body = new StringBuilder("{\"table-changes\":[");
        for (int i = 0; i < tables.length; i++) {
            List<String> path = tables[i];
            body.append(i == 0 ? "" : ",").append("{\"identifier\":{\"namespace\":")
                    .append(json(path.subList(0, path.size() - 1))).append(",\"name\":\"").append(path.getLast())
                    .append("\"},").append(SET_PROPERTY.formatted("tia").substring(1));
        }
        return body.append("]}").toString();
    }

    /**
     * D13, amended 2026-11-12: unregister needs modify, and a regulated or row/column-flagged table is refused
     * until a steward removes the flag. A dropped or unregistered table's own file brings it back only in the
     * namespace it left, for a caller with create there: registered anywhere else it is 409, even for a caller
     * with every right, since a move would hand it that namespace's grants. A caller that holds nothing on the
     * namespace it registers into is answered as if the namespace did not exist (D18).
     */
    @Test
    void unregisterNeedsModifyAndATableComesBackOnlyInItsOwnNamespace() throws Exception {
        String sales = "/v1/gate/namespaces/sales/tables";
        HttpResponse<String> created = send(DANA, "POST", sales, TABLE.formatted("passing"));
        ok(created);
        String file = JsonPath.read(created.body(), "$.metadata-location");
        String id = JsonPath.read(created.body(), "$.metadata.table-uuid");

        assertDenied(send(ANN, "POST", sales + "/passing/unregister", null), "unregisterTable", "no_privilege");
        assertThat(audit.denials().getLast().privilege()).isEqualTo("modify");
        assertDenied(send(DANA, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii/unregister",
                null), "unregisterTable", "flagged_table");
        assertDenied(send(DANA, "POST", "/v1/gate/namespaces/finance/tables/budget/unregister", null),
                "unregisterTable", "regulated");
        assertThat(table("finance/budget").state()).isEqualTo(CatalogObject.State.ACTIVE);

        noContent(send(DANA, "DELETE", sales + "/passing", null));
        String rita = IssuerForTests.bearer("rita", "registrars");
        noContent(grant(GINA, namespaceSpec("hr"), "registrars", "create"));
        String again = "{\"name\":\"passing\",\"metadata-location\":\"" + file + "\"}";
        assertHidden(send(rita, "POST", "/v1/gate/namespaces/sales/register", again), "NoSuchNamespaceException",
                "registerTable");
        assertThat(audit.denials().getLast().securable().path()).containsExactly("sales");
        assertError(send(rita, "POST", "/v1/gate/namespaces/hr/register", again), 409, "AlreadyExistsException");
        assertError(send(DANA, "POST", "/v1/gate/namespaces/hr/register", again), 409, "AlreadyExistsException");
        assertThat(store.readObject(UUID.fromString(id)).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);

        HttpResponse<String> back = send(DANA, "POST", "/v1/gate/namespaces/sales/register", again);
        ok(back);
        assertThat(JsonPath.<String>read(back.body(), "$.metadata.table-uuid")).isEqualTo(id);
        HttpResponse<String> unregistered = send(DANA, "POST", sales + "/passing/unregister", null);
        ok(unregistered);
        String left = "{\"name\":\"passing\",\"metadata-location\":\""
                + JsonPath.read(unregistered.body(), "$.metadata-location") + "\"}";
        assertError(send(rita, "POST", "/v1/gate/namespaces/hr/register", left), 409, "AlreadyExistsException");
        assertHidden(send(rita, "POST", "/v1/gate/namespaces/sales/register", left), "NoSuchNamespaceException",
                "registerTable");
        assertThat(store.readObject(UUID.fromString(id)).orElseThrow().state())
                .isEqualTo(CatalogObject.State.UNREGISTERED);
    }

    /**
     * D13, amended 2026-11-12: register-view needs create on the namespace, as register does, and a dropped
     * view's own file brings it back only in the namespace it left. A caller that holds nothing on that namespace
     * is answered as if it did not exist (D18).
     */
    @Test
    void registerViewNeedsCreateAndADroppedViewComesBackOnlyInItsOwnNamespace() throws Exception {
        String views = "/v1/gate/namespaces/sales/views";
        HttpResponse<String> created = send(DANA, "POST", views, VIEW.formatted("passing_view",
                System.currentTimeMillis()));
        ok(created);
        String id = JsonPath.read(created.body(), "$.metadata.view-uuid");
        String again = "{\"name\":\"passing_view\",\"metadata-location\":\""
                + JsonPath.read(created.body(), "$.metadata-location") + "\"}";
        noContent(send(DANA, "DELETE", views + "/passing_view", null));
        String vera = IssuerForTests.bearer("vera", "view-registrars");
        noContent(grant(GINA, namespaceSpec("hr"), "view-registrars", "create"));

        assertHidden(send(vera, "POST", "/v1/gate/namespaces/sales/register-view", again),
                "NoSuchNamespaceException", "registerView");
        assertThat(audit.denials().getLast().privilege()).isEqualTo("create");
        assertThat(audit.denials().getLast().securable().path()).containsExactly("sales");
        assertError(send(vera, "POST", "/v1/gate/namespaces/hr/register-view", again), 409, "AlreadyExistsException");
        assertThat(store.readObject(UUID.fromString(id)).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);

        HttpResponse<String> back = send(DANA, "POST", "/v1/gate/namespaces/sales/register-view", again);
        ok(back);
        assertThat(JsonPath.<String>read(back.body(), "$.metadata.view-uuid")).isEqualTo(id);
    }

    @Test
    void separationOfDutiesHoldsForEveryGrant() throws Exception {
        noContent(grant(GINA, catalogSpec(), "loaders-two", "write"));
        assertDenied(grant(GINA, catalogSpec(), "loaders-two", "manage-tags"), "grant", "separation-of-duties");
        assertDenied(grant(GINA, catalogSpec(), "analysts", "manage-grants"), "grant", "separation-of-duties");
        assertDenied(grant(GINA, catalogSpec(), "platform-ops", "select"), "grant", "separation-of-duties");
        assertDenied(grant(GINA, catalogSpec(), "grant-admins", "select"), "grant", "separation-of-duties");

        assertDenied(grant(SAM, namespaceSpec("sales"), "stewards", "select"), "grant", "separation-of-duties");
        assertThat(audit.denials().getLast().reason()).contains("no one grants to a group they belong to");
        noContent(grant(SAM, tableSpec("sales/eu/orders"), "auditors", "select"));
        // Sam holds nothing on hr: it is hidden from him (D18, backlog 73), and the refusal is audited all the same.
        assertHidden(grant(SAM, namespaceSpec("hr"), "auditors", "select"), "NoSuchNamespaceException", "grant");
        assertAnsweredAsMissing(grant(SAM, namespaceSpec("hr"), "auditors", "select"),
                grant(SAM, namespaceSpec("nowhere"), "auditors", "select"), "hr", "nowhere", "grant");
        assertDenied(grant(DANA, catalogSpec(), "auditors", "select"), "grant", "no_privilege");
        assertError(grant(GINA, catalogSpec(), "auditors", "superuser"), 400, "BadRequestException");

        noContent(send(GINA, "POST", GRANTS + "/revoke", grantBody(catalogSpec(), "loaders-two", "write")));
        assertError(send(GINA, "POST", GRANTS + "/revoke", grantBody(catalogSpec(), "loaders-two", "write")),
                404, "NotFoundException");
        assertDenied(send(ANN, "POST", GRANTS + "/revoke", grantBody(namespaceSpec("sales"), "analysts", "select")),
                "revoke", "no_privilege");
    }

    @Test
    void flaggedTablesGiveStorageAccessOnlyToTrustedEngines() throws Exception {
        String pii = "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii";
        assertDenied(send(ANN, "GET", pii, null), "loadTable", "flagged_table");
        assertDenied(send(ANN, "GET", pii + "/credentials", null), "loadCredentials", "flagged_table");
        assertThat(send(ANN, "HEAD", pii, null).statusCode()).as("describe is not storage access").isEqualTo(204);

        HttpResponse<String> viaEngine = send(ENGINE, "GET", pii, null, "X-Nuna-End-User", "ann",
                "X-Nuna-End-User-Groups", "analysts");
        ok(viaEngine);
        assertHidden(send(ENGINE, "GET", "/v1/gate/namespaces/hr/tables/staff", null, "X-Nuna-End-User", "ann",
                "X-Nuna-End-User-Groups", "analysts"), "NoSuchTableException", "loadTable");
        Denial denial = audit.denials().getLast();
        assertThat(denial.actor().principal()).isEqualTo("svc-trino");
        assertThat(denial.actor().endUser()).as("the engine's end user is the one decided for").isEqualTo("ann");
        assertThat(denial.actor().engine()).isEqualTo("trino-prod");
    }

    @Test
    void anEngineHeaderVouchesForTheUsersOwnToken() throws Exception {
        String pii = "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii";
        String header = "X-Nuna-Engine-Authorization";

        ok(send(ANN, "GET", pii, null, header, ENGINE));
        assertHidden(send(ANN, "GET", "/v1/gate/namespaces/hr/tables/staff", null, header, ENGINE),
                "NoSuchTableException", "loadTable");
        Denial denial = audit.denials().getLast();
        assertThat(denial.actor().principal()).isEqualTo("svc-trino");
        assertThat(denial.actor().endUser()).as("the user the Authorization token names").isEqualTo("ann");
        assertThat(denial.actor().engine()).isEqualTo("trino-prod");

        assertError(send(ANN, "GET", pii, null, header, ROGUE), 403, "ForbiddenException");
        assertThat(audit.authFailures().getLast().clientId()).as("the engine refused").isEqualTo("rogue-app");
        assertError(send(ANN, "GET", pii, null, header, ENGINE, "X-Nuna-End-User", "ann"), 401,
                "NotAuthorizedException");
        assertError(send(ANN, "GET", pii, null, header, "Basic x"), 401, "NotAuthorizedException");
        ok(send(ENGINE, "GET", "/v1/config?warehouse=gate", null, header, ENGINE));
    }

    @Test
    void trinoGetsEachUsersRowFilterAndColumnMasksThroughOpa() throws Exception {
        String rules = "/management/v1/catalogs/gate/row-column-rules";
        String table = "{\"type\":\"table\",\"namespace\":[\"sales\",\"eu\"],\"name\":\"pii\"}";
        assertDenied(send(GINA, "POST", rules, """
                {"table":%s,"group":"grant-admins","row-filter":"true"}""".formatted(table)),
                "setRowColumnRule", "separation-of-duties");
        noContent(send(GINA, "POST", rules, """
                {"table":%s,"group":"analysts","row-filter":"id > 10","column-masks":{"id":"NULL"}}"""
                .formatted(table)));
        try {
            String ann = ANN.substring(7);
            assertThat(send(null, "POST", "/opa/v1/row-filters", opaRowFilters("trino_gate", ann)).body())
                    .isEqualTo("{\"result\":[{\"expression\":\"id > 10\"}]}");
            assertThat(send(null, "POST", "/opa/v1/row-filters", opaRowFilters("trino_gate",
                    IssuerForTests.bearer("wendy", "maintainers").substring(7))).body())
                    .as("a user whose groups have no rule").isEqualTo("{\"result\":[{\"expression\":\"false\"}]}");
            assertThat(send(null, "POST", "/opa/v1/row-filters", opaRowFilters("trino_gate", "forged")).body())
                    .isEqualTo("{\"result\":[{\"expression\":\"false\"}]}");
            assertThat(send(null, "POST", "/opa/v1/row-filters", opaRowFilters("system", ann)).body())
                    .isEqualTo("{\"result\":[]}");
            assertThat(send(null, "POST", "/opa/v1/column-masks", """
                    {"input":{"context":{"identity":{"user":"ann","extraCredentials":{"token":"%s"}}},
                     "action":{"operation":"GetColumnMask","filterResources":[{"column":{"catalogName":"trino_gate",
                      "schemaName":"sales.eu","tableName":"pii","columnName":"id","columnType":"bigint"}}]}}}"""
                    .formatted(ann)).body())
                    .isEqualTo("{\"result\":[{\"index\":0,\"viewExpression\":{\"expression\":\"NULL\"}}]}");
            assertThat(send(null, "POST", "/opa/v1/allow", "{\"input\":{}}").body()).isEqualTo("{\"result\":true}");
        } finally {
            noContent(send(GINA, "POST", rules + "/remove", "{\"table\":%s,\"group\":\"analysts\"}"
                    .formatted(table)));
        }
    }

    /**
     * A rule names only columns pii has: a misspelt mask or a filter on a column it lacks is refused, and a mask is kept
     * under the schema's spelling; a commit that renames or drops a column a rule names is refused and audited, so a
     * rename never unmasks it (backlog 240).
     */
    @Test
    void aRuleNamesOnlyColumnsTheTableHasAndNoSchemaChangeTakesThemAway() throws Exception {
        String rules = "/management/v1/catalogs/gate/row-column-rules";
        String table = "{\"type\":\"table\",\"namespace\":[\"sales\",\"eu\"],\"name\":\"pii\"}";
        HttpResponse<String> typo = send(GINA, "POST", rules, """
                {"table":%s,"group":"analysts","column-masks":{"idd":"NULL"}}""".formatted(table));
        assertError(typo, 400, "BadRequestException");
        assertThat(JsonPath.<String>read(typo.body(), "$.error.message")).startsWith("column-masks names idd,");
        assertError(send(GINA, "POST", rules, """
                {"table":%s,"group":"analysts","row-filter":"desk = 'EQ'"}""".formatted(table)),
                400, "BadRequestException");

        noContent(send(GINA, "POST", rules, """
                {"table":%s,"group":"analysts","column-masks":{"ID":"NULL"}}""".formatted(table)));
        try {
            HttpResponse<String> listed = send(GINA, "GET", rules + "?namespace=sales&namespace=eu&name=pii", null);
            ok(listed);
            assertThat(JsonPath.<Map<String, String>>read(listed.body(), "$.rules[0].column-masks"))
                    .containsExactly(Map.entry("id", "NULL"));

            String pii = "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii";
            assertDenied(send(ENGINE, "POST", pii, """
                    {"requirements":[{"type":"assert-current-schema-id","current-schema-id":0}],"updates":[
                     {"action":"add-schema","schema":{"type":"struct","schema-id":1,"fields":[
                      {"id":1,"name":"pii_id","required":true,"type":"long"}]}},
                     {"action":"set-current-schema","schema-id":-1}]}"""), "updateTable", "row_column_rule");
            assertThat(audit.denials().getLast().reason()).contains("group analysts's rule names id");
        } finally {
            noContent(send(GINA, "POST", rules + "/remove", "{\"table\":%s,\"group\":\"analysts\"}"
                    .formatted(table)));
        }
    }

    /**
     * What the console's rules panel reads (backlog 242), on the wire: the flagged table's schema for a reader whose
     * load of it is refused, their own view without the expressions, the steward's rule count and groups, the
     * summary's flagged tables, and the export, with the expressions for the grant admin alone.
     */
    @Test
    void theConsoleReadsAFlaggedTablesSchemaRuleCountsAndEachCallersOwnView() throws Exception {
        String rules = "/management/v1/catalogs/gate/row-column-rules";
        String table = "{\"type\":\"table\",\"namespace\":[\"sales\",\"eu\"],\"name\":\"pii\"}";
        String pii = "/management/v1/catalogs/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii";
        noContent(send(GINA, "POST", rules, """
                {"table":%s,"group":"analysts","row-filter":"id > 10","column-masks":{"id":"NULL"}}"""
                .formatted(table)));
        try {
            assertDenied(send(ANN, "GET", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii", null),
                    "loadTable", "flagged_table");
            HttpResponse<String> schema = send(ANN, "GET", pii + "/schema", null);
            ok(schema);
            assertThat(JsonPath.<List<String>>read(schema.body(), "$.schema.fields[*].name")).containsExactly("id");
            assertThat(schema.body()).doesNotContain("s3://", "manifest");

            HttpResponse<String> mine = send(ANN, "GET", pii + "/row-column-rules/mine", null);
            ok(mine);
            assertThat(JsonPath.<String>read(mine.body(), "$.rows")).isEqualTo("filtered");
            assertThat(JsonPath.<List<String>>read(mine.body(), "$.masked-columns")).containsExactly("id");
            assertThat(mine.body()).doesNotContain("id > 10", "NULL");

            HttpResponse<String> governance = send(SAM, "GET", pii + "/governance", null);
            ok(governance);
            assertThat(JsonPath.<Integer>read(governance.body(), "$.row-column-rules.count")).isEqualTo(1);
            assertThat(JsonPath.<List<String>>read(governance.body(), "$.row-column-rules.groups"))
                    .containsExactly("analysts");
            assertThat(send(ANN, "GET", pii + "/governance", null).body()).doesNotContain("row-column-rules");

            HttpResponse<String> summary = send(GINA, "GET", "/management/v1/catalogs/gate/governance/summary", null);
            ok(summary);
            assertThat(JsonPath.<List<Integer>>read(summary.body(), "$.flagged[?(@.on.id == '"
                    + table("sales/eu/pii").id() + "')].rules")).containsExactly(1);

            HttpResponse<String> export = send(GINA, "GET", rules + "/export", null);
            ok(export);
            assertThat(JsonPath.<List<String>>read(export.body(), "$.rules[?(@.group == 'analysts')]['row-filter']"))
                    .containsExactly("id > 10");
            assertDenied(send(ANN, "GET", rules + "/export", null), "exportRowColumnRules", "no_privilege");
        } finally {
            noContent(send(GINA, "POST", rules + "/remove", "{\"table\":%s,\"group\":\"analysts\"}"
                    .formatted(table)));
        }
    }

    private static String opaRowFilters(String catalog, String token) {
        return """
                {"input":{"context":{"identity":{"user":"anyone","groups":["grant-admins"],
                  "extraCredentials":{"token":"%s"}}},
                 "action":{"operation":"GetRowFilters","resource":{"table":{"catalogName":"%s",
                  "schemaName":"sales.eu","tableName":"pii"}}}}}""".formatted(token, catalog);
    }

    @Test
    void theMaintenanceExemptionIsAuditedAndNeverAnEndUsers() throws Exception {
        String pii = "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/pii";

        ok(send(WORKER, "GET", pii, null));
        ok(send(WORKER, "GET", pii + "/credentials", null));
        ok(send(WORKER, "POST", pii, EXPIRY));

        assertThat(audit.events()).extracting(CatalogEvent::data).filteredOn(MaintenanceExemption.class::isInstance)
                .extracting(data -> ((MaintenanceExemption) data).operation())
                .containsExactly("loadTable", "loadCredentials", "updateTable");
        MaintenanceExemption exemption = (MaintenanceExemption) audit.events().stream().map(CatalogEvent::data)
                .filter(MaintenanceExemption.class::isInstance).findFirst().orElseThrow();
        assertThat(exemption.actor().principal()).isEqualTo("svc-maintenance");
        assertThat(exemption.actor().endUser()).isNull();
        assertThat(exemption.object().path()).containsExactly("sales", "eu", "pii");

        assertDenied(send(ENGINE, "POST", pii, EXPIRY, "X-Nuna-End-User", "wendy", "X-Nuna-End-User-Groups",
                "maintainers"), "updateTable", "no_privilege");
        assertDenied(send(IssuerForTests.bearer("wendy", "maintainers"), "GET", pii, null), "loadTable", "flagged_table");
        assertDenied(send(IssuerForTests.bearer("wendy", "maintainers"), "POST", "/v1/gate/namespaces/"
                + ns(List.of("sales", "eu")) + "/tables/orders", EXPIRY), "updateTable", "no_privilege");
    }

    @Test
    void unclassifiedTablesInRegulatedNamespacesAreDeniedByDefault() throws Exception {
        assertDenied(send(ANN, "GET", "/v1/gate/namespaces/finance/tables/ledger", null), "loadTable", "unclassified");
        assertThat(send(ANN, "HEAD", "/v1/gate/namespaces/finance/tables/ledger", null).statusCode()).isEqualTo(204);
        ok(send(ANN, "GET", "/v1/gate/namespaces/finance/tables/budget", null));
    }

    @Test
    void onlyRegisteredEnginesMayNameAnEndUserAndTheSwitchRefusesIdentityLessRegulatedWrites() throws Exception {
        HttpResponse<String> rogue = send(ROGUE, "GET", "/v1/gate/namespaces", null, "X-Nuna-End-User", "ann");
        assertError(rogue, 403, "ForbiddenException");
        assertThat(audit.authFailures().getLast().clientId()).isEqualTo("rogue-app");

        assertDenied(send(ENGINE, "POST", "/v1/gate/namespaces/finance/tables/budget", SET_PROPERTY.formatted("1")),
                "updateTable", "identity_less");
        ok(send(ENGINE, "POST", "/v1/gate/namespaces/finance/tables/budget", SET_PROPERTY.formatted("2"),
                "X-Nuna-End-User", "lee", "X-Nuna-End-User-Groups", "loaders"));
        ok(send(ENGINE, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders",
                SET_PROPERTY.formatted("3")));
        ok(send(ROGUE, "POST", "/v1/gate/namespaces/finance/tables/budget", SET_PROPERTY.formatted("4")));
    }

    @Test
    void theEngineRegistryIsForPlatformOperatorsOnly() throws Exception {
        assertDenied(send(DANA, "PUT", ENGINES, """
                {"issuer":"%s","client-id":"spark"}""".formatted(IssuerForTests.ISSUER)),
                "registerEngine", "no_privilege");
        assertThat(audit.denials().getLast().securable()).as("the registry is no securable").isNull();
        assertDenied(send(ANN, "GET", ENGINES, null), "listEngines", "no_privilege");

        HttpResponse<String> engines = send(OLGA, "GET", ENGINES, null);
        ok(engines);
        assertThat(JsonPath.<List<String>>read(engines.body(), "$.engines[*].client-id")).contains("trino-prod");
        assertError(send(OLGA, "PUT", ENGINES, "{\"issuer\":\"https://rogue.test/\",\"client-id\":\"x\"}"),
                400, "BadRequestException");
        assertError(send(OLGA, "DELETE", ENGINES + "?issuer=" + enc(IssuerForTests.ISSUER) + "&client-id=nobody",
                null), 404, "NotFoundException");
        log.await("\"type\":\"nuna.catalog.engine\"");
    }

    @Test
    void grantsAreListedByPrincipalAndByObjectCheckedInBatchAndExported() throws Exception {
        HttpResponse<String> own = send(ANN, "GET", GRANTS, null);
        ok(own);
        assertThat(JsonPath.<List<String>>read(own.body(), "$.grants[?(@.group == 'analysts')].privilege"))
                .containsOnly("select");
        assertDenied(send(ANN, "GET", GRANTS + "?group=data-admins", null), "listGrants", "no_privilege");
        HttpResponse<String> admins = send(GINA, "GET", GRANTS + "?group=data-admins", null);
        ok(admins);
        assertThat(JsonPath.<List<String>>read(admins.body(), "$.grants[*].privilege"))
                .containsExactlyInAnyOrder("create", "modify");

        HttpResponse<String> onSales = send(GINA, "GET", GRANTS + "/on?type=namespace&namespace=sales", null);
        ok(onSales);
        assertThat(JsonPath.<List<String>>read(onSales.body(), "$.grants[*].group"))
                .contains("analysts", "stewards");
        assertDenied(send(ANN, "GET", GRANTS + "/on?type=namespace&namespace=sales", null),
                "listGrants", "no_privilege");

        HttpResponse<String> checked = send(ANN, "POST", GRANTS + "/check", """
                {"checks":[
                  {"securable":{"type":"table","namespace":["sales","eu"],"name":"orders"},"privilege":"select"},
                  {"securable":{"type":"table","namespace":["hr"],"name":"staff"},"privilege":"select"},
                  {"securable":{"type":"table","namespace":["sales","eu"],"name":"orders"},"privilege":"modify"},
                  {"securable":{"type":"table","namespace":["finance"],"name":"ledger"},"privilege":"select"},
                  {"securable":{"type":"table","namespace":["hr"],"name":"nowhere"},"privilege":"select"}]}""");
        ok(checked);
        assertThat(JsonPath.<List<Boolean>>read(checked.body(), "$.results[*].allowed"))
                .containsExactly(true, false, false, false, false);

        HttpResponse<String> export = send(GINA, "GET", GRANTS + "/export", null);
        ok(export);
        assertThat(JsonPath.<String>read(export.body(), "$.exported-by")).isEqualTo("gina");
        assertThat(JsonPath.<List<String>>read(export.body(), "$.grants[*].group"))
                .contains("data-admins", "loaders", "analysts", "stewards");
        assertThat(JsonPath.<List<String>>read(export.body(),
                "$.grants[?(@.group == 'analysts')].securable.namespace[0]")).contains("sales", "finance");
        assertDenied(send(ANN, "GET", GRANTS + "/export", null), "exportGrants", "no_privilege");
        log.await(3, "\"type\":\"nuna.catalog.grant\"");
    }

    /**
     * D45 (backlog 129): the effective grants on a table, every grant that reaches it from the catalog down, each
     * with the securable it was granted on. A grant on a namespace above it appears and a revoke removes it. The
     * steward, who manages grants on sales, may read them there; the analyst, who only reads, is refused, and on hr,
     * where she holds nothing, answered as for a missing table.
     */
    @Test
    void theEffectiveGrantsOnATableIncludeEveryGrantAboveIt() throws Exception {
        String orders = GRANTS + "/effective?type=table&namespace=sales&namespace=eu&name=orders";
        HttpResponse<String> effective = send(GINA, "GET", orders, null);
        ok(effective);
        assertThat(JsonPath.<String>read(effective.body(), "$.securable.name")).isEqualTo("orders");
        assertThat(JsonPath.<List<String>>read(effective.body(),
                "$.grants[?(@.securable.type == 'catalog')].group")).contains("data-admins", "loaders");
        assertThat(JsonPath.<List<String>>read(effective.body(),
                "$.grants[?(@.group == 'analysts')].securable.namespace[0]")).contains("sales");
        List<String> types = JsonPath.read(effective.body(), "$.grants[*].securable.type");
        assertThat(types.getFirst()).as("from the catalog down").isEqualTo("catalog");
        assertThat(types.indexOf("namespace")).isGreaterThan(types.lastIndexOf("catalog"));
        assertThat(JsonPath.<List<String>>read(effective.body(), "$.grant-admin-groups"))
                .containsExactly("grant-admins");

        String euReaders = "eu-readers-" + UUID.randomUUID().toString().substring(0, 8);
        String onEu = grantBody(namespaceSpec("sales", "eu"), euReaders, "select");
        noContent(send(GINA, "POST", GRANTS, onEu));
        HttpResponse<String> granted = send(GINA, "GET", orders, null);
        ok(granted);
        assertThat(JsonPath.<List<List<String>>>read(granted.body(),
                "$.grants[?(@.group == '" + euReaders + "')].securable.namespace"))
                .containsExactly(List.of("sales", "eu"));
        noContent(send(GINA, "POST", GRANTS + "/revoke", onEu));
        HttpResponse<String> revoked = send(GINA, "GET", orders, null);
        ok(revoked);
        assertThat(JsonPath.<List<String>>read(revoked.body(), "$.grants[*].group")).doesNotContain(euReaders);

        ok(send(SAM, "GET", orders, null));
        assertDenied(send(ANN, "GET", orders, null), "listEffectiveGrants", "no_privilege");
        assertAnsweredAsMissing(send(ANN, "GET", GRANTS + "/effective?type=table&namespace=hr&name=staff", null),
                send(ANN, "GET", GRANTS + "/effective?type=table&namespace=hr&name=none", null), "staff", "none",
                "listEffectiveGrants");
    }

    private HttpResponse<String> grant(String token, String securable, String group, String privilege)
            throws Exception {
        return send(token, "POST", GRANTS, grantBody(securable, group, privilege));
    }

    private static String grantBody(String securable, String group, String privilege) {
        return "{\"securable\":" + securable + ",\"group\":\"" + group + "\",\"privilege\":\"" + privilege + "\"}";
    }

    private static String catalogSpec() {
        return "{\"type\":\"catalog\"}";
    }

    private static String namespaceSpec(String... levels) {
        return "{\"type\":\"namespace\",\"namespace\":" + json(List.of(levels)) + "}";
    }

    private static String tableSpec(String path) {
        List<String> levels = List.of(path.split("/"));
        return "{\"type\":\"table\",\"namespace\":" + json(levels.subList(0, levels.size() - 1)) + ",\"name\":\""
                + levels.getLast() + "\"}";
    }

    private NamespaceRecord namespace(String path) {
        CatalogRecord catalog = store.findCatalog(Names.normalize("gate")).orElseThrow();
        return store.findNamespace(catalog.id(), List.of(path.split("/")).stream().map(Names::normalize).toList())
                .orElseThrow();
    }

    private CatalogObject table(String path) {
        int slash = path.lastIndexOf('/');
        return store.findObject(namespace(path.substring(0, slash)).id(), Names.normalize(path.substring(slash + 1)))
                .orElseThrow();
    }

    private void attribute(Securable target, GovernanceAttribute.Kind kind, String value) {
        Change change = new Change(UUID.randomUUID(), Instant.now(), List.of(new OutboxEvent("test.setup", "{}")),
                Optional.empty());
        assertThat(store.putAttribute(new GovernanceAttribute(target, kind, value, "test", "gate-setup",
                Instant.now()), change)).isEqualTo(WriteResult.APPLIED);
    }

    private void assertDenied(HttpResponse<String> response, String operation, String reason) {
        assertError(response, 403, "ForbiddenException");
        Denial denial = audit.denials().getLast();
        assertThat(denial.operation()).isEqualTo(operation);
        assertThat(denial.reason()).startsWith(reason);
    }

    /**
     * A refusal answered as a missing name (D18): a 404 of the given type (none for a HEAD), with its denial audited.
     * It counts among the refusals, since {@link #everyRefusalIsAudited} expects its audit event.
     */
    private void assertHidden(HttpResponse<String> response, String type, String operation) {
        assertError(response, 404, type);
        refusals++;
        Denial denial = audit.denials().getLast();
        assertThat(denial.operation()).isEqualTo(operation);
        assertThat(denial.reason()).startsWith("no_privilege");
    }

    /** {@code hidden} is {@code missing}'s answer word for word, but for the name; only {@code hidden} is audited. */
    private void assertAnsweredAsMissing(HttpResponse<String> hidden, HttpResponse<String> missing, String name,
                                         String missingName, String operation) {
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(404);
        assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(404);
        assertThat(hidden.body()).isEqualTo(missing.body().replace(missingName, name));
        refusals++;
        assertThat(audit.denials().getLast().operation()).isEqualTo(operation);
    }

    private static void assertError(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (type != null) {
            assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo(type);
        }
    }

    private static void ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static void noContent(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
    }

    private HttpResponse<String> send(String bearer, String method, String path, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            refusals++;
        }
        return response;
    }

    private static String ns(List<String> levels) {
        return enc(String.join("\u001f", levels));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String json(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
