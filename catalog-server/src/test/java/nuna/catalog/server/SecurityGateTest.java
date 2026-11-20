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
import java.util.Optional;
import java.util.UUID;
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
        "nuna.storage.catalogs.gate.legal-entity=Test Entity"})
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

    @BeforeAll
    void buildTheEstate() throws Exception {
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
                .as("one audit event for each 401 or 403").isEqualTo(refusals);
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

        assertError(send(ANN, "GET", "/v1/gate/namespaces/hr/tables/staff", null), 403, "ForbiddenException");
        Denial denial = audit.denials().getLast();
        assertThat(denial.operation()).isEqualTo("loadTable");
        assertThat(denial.privilege()).isEqualTo("select");
        assertThat(denial.reason()).startsWith("no_privilege");
        assertThat(denial.securable().path()).containsExactly("hr", "staff");
        assertThat(denial.actor().principal()).isEqualTo("ann");

        assertError(send(ANN, "HEAD", "/v1/gate/namespaces/hr/tables/staff", null), 403, null);
        assertError(send(ANN, "POST", "/v1/gate/namespaces/sales/tables", TABLE.formatted("new_one")),
                403, "ForbiddenException");
        assertThat(audit.denials().getLast().privilege()).as("select gives no create").isEqualTo("create");
        assertError(send(ANN, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders",
                SET_PROPERTY.formatted("ann")), 403, "ForbiddenException");
        assertThat(audit.denials().getLast().privilege()).as("select gives no modify").isEqualTo("modify");
    }

    /** A metrics report needs select on its table, as a load does; a refusal is audited like any other. */
    @Test
    void metricsAreReportedOnlyOnATableTheCallerMayRead() throws Exception {
        String report = """
                {"report-type":"commit-report","table-name":"t","snapshot-id":1,"sequence-number":1,
                 "operation":"append","metrics":{}}""";
        noContent(send(ANN, "POST", "/v1/gate/namespaces/" + ns(List.of("sales", "eu")) + "/tables/orders/metrics",
                report));
        assertDenied(send(ANN, "POST", "/v1/gate/namespaces/hr/tables/staff/metrics", report), "reportMetrics",
                "no_privilege");
        assertThat(audit.denials().getLast().privilege()).isEqualTo("select");
    }

    /** D15: a transaction asks for each table what that table's own commit route asks; one refusal refuses all. */
    @Test
    void aTransactionNeedsTheRightOnEveryTable() throws Exception {
        String tia = IssuerForTests.bearer("tia", "txn-writers");
        noContent(grant(GINA, tableSpec("sales/eu/orders"), "txn-writers", "modify"));
        long orders = store.readPointer(table("sales/eu/orders").mainKey()).orElseThrow().version();
        long staff = store.readPointer(table("hr/staff").mainKey()).orElseThrow().version();

        assertDenied(send(tia, "POST", "/v1/gate/transactions/commit",
                transaction(List.of("sales", "eu", "orders"), List.of("hr", "staff"))), "updateTable", "no_privilege");
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
     * with every right, since a move would hand it that namespace's grants.
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
        assertDenied(send(rita, "POST", "/v1/gate/namespaces/sales/register", again), "registerTable", "no_privilege");
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
        assertDenied(send(rita, "POST", "/v1/gate/namespaces/sales/register", left), "registerTable", "no_privilege");
        assertThat(store.readObject(UUID.fromString(id)).orElseThrow().state())
                .isEqualTo(CatalogObject.State.UNREGISTERED);
    }

    /**
     * D13, amended 2026-11-12: register-view needs create on the namespace, as register does, and a dropped
     * view's own file brings it back only in the namespace it left.
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

        assertDenied(send(vera, "POST", "/v1/gate/namespaces/sales/register-view", again), "registerView",
                "no_privilege");
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
        assertDenied(grant(SAM, namespaceSpec("hr"), "auditors", "select"), "grant", "no_privilege");
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
        assertDenied(send(ENGINE, "GET", "/v1/gate/namespaces/hr/tables/staff", null, "X-Nuna-End-User", "ann",
                "X-Nuna-End-User-Groups", "analysts"), "loadTable", "no_privilege");
        Denial denial = audit.denials().getLast();
        assertThat(denial.actor().principal()).isEqualTo("svc-trino");
        assertThat(denial.actor().endUser()).as("the engine's end user is the one decided for").isEqualTo("ann");
        assertThat(denial.actor().engine()).isEqualTo("trino-prod");
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'nuna.catalog.engine'",
                Integer.class)).as("registration went through the outbox").isGreaterThanOrEqualTo(1);
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'nuna.catalog.grant'",
                Integer.class)).as("grants went through the outbox").isGreaterThanOrEqualTo(3);
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
