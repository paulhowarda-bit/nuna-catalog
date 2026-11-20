package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventType;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * {@code GET .../audit}: an object's recent audit records, read back from the audit topic. It needs
 * manage-grants, refusals are Denials, and every read is itself audited.
 */
@Tag("heavy")
@GovernanceServer
class AuditReadRouteTest {

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
        api.table(ns, "other");
        table = "/namespaces/" + ns + "/tables/t";
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    @Test
    void readingTheTrailNeedsManageGrants() {
        // The tester holds create and modify, the steward manage-tags: neither may read who used the table.
        expectError(api.manage("GET", table + "/audit", null), 403, "ForbiddenException");
        expectError(api.manage("GET", table + "/audit", null, AUTH, api.steward()), 403, "ForbiddenException");
        expectError(api.manage("GET", table + "/audit", null, AUTH, api.outsider()), 403, "ForbiddenException");
        expect(api.manage("GET", table + "/audit", null, AUTH, api.grantAdmin()), 200);
    }

    @Test
    void aRefusalIsAuditedAsADenial() {
        String requestId = "audit-refused-" + UUID.randomUUID();
        expectError(api.manage("GET", table + "/audit", null, "X-Request-ID", requestId), 403, "ForbiddenException");
        CatalogEvent record = api.awaitRequest(requestId);
        assertThat(record.data().type()).isEqualTo(EventType.DENIAL);
        Denial denial = (Denial) record.data();
        assertThat(denial.operation()).isEqualTo("readAudit");
        assertThat(denial.privilege()).isEqualTo("manage-grants");
        assertThat(denial.securable().path()).containsExactly(ns, "t");
    }

    @Test
    void returnsTheObjectsOwnRecordsNewestFirst() {
        String id = api.id(ns, "tables", "t");
        // A read of the table, then a refused change to it: both are records about it, after its create. The relay
        // may publish the create after them, so the topic's order is not time order; the answer's is.
        expect(api.rest("GET", table, null), 200);
        expectError(api.manage("PUT", table + "/attributes/owner", "{\"value\":\"x\",\"provenance\":\"p\"}"),
                403, "ForbiddenException");
        HttpResponse<String> response = awaitTypes(table, "nuna.catalog.commit", "nuna.catalog.read",
                "nuna.catalog.denial");

        List<String> subjects = JsonPath.read(response.body(), "$.records[*].subject");
        assertThat(subjects).isNotEmpty().allMatch(id::equals);
        List<String> times = JsonPath.read(response.body(), "$.records[*].time");
        assertThat(times).isSortedAccordingTo((a, b) -> Instant.parse(b).compareTo(Instant.parse(a)));
        List<String> types = JsonPath.read(response.body(), "$.records[*].type");
        assertThat(types.indexOf("nuna.catalog.denial")).isLessThan(types.lastIndexOf("nuna.catalog.commit"));
        assertThat(JsonPath.<String>read(response.body(), "$.target.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(response.body(), "$.window.topic")).isEqualTo("nuna.catalog.audit");
        assertThat(JsonPath.<Integer>read(response.body(), "$.window['from-offset']"))
                .isLessThan(JsonPath.<Integer>read(response.body(), "$.window['to-offset']"));
    }

    @Test
    void theLimitBoundsTheAnswer() {
        expect(api.rest("GET", table, null), 200);
        expect(api.rest("GET", table, null), 200);
        awaitTypes(table, "nuna.catalog.commit", "nuna.catalog.read");
        HttpResponse<String> one = expect(api.manage("GET", table + "/audit?limit=1", null, AUTH, api.grantAdmin()), 200);
        assertThat(JsonPath.<List<Object>>read(one.body(), "$.records")).hasSize(1);
        expectError(api.manage("GET", table + "/audit?limit=0", null, AUTH, api.grantAdmin()), 400, "BadRequestException");
        expectError(api.manage("GET", table + "/audit?limit=201", null, AUTH, api.grantAdmin()), 400, "BadRequestException");
    }

    @Test
    void worksOnEveryLevel() {
        expect(api.manage("GET", "/audit", null, AUTH, api.grantAdmin()), 200);
        expect(api.manage("GET", "/namespaces/" + ns + "/audit", null, AUTH, api.grantAdmin()), 200);
        api.view(ns, "v");
        expect(api.manage("GET", "/namespaces/" + ns + "/views/v/audit", null, AUTH, api.grantAdmin()), 200);
        expectError(api.manage("GET", "/namespaces/" + ns + "/tables/missing/audit", null, AUTH, api.grantAdmin()),
                404, "NoSuchTableException");
    }

    @Test
    void eachReadIsAudited() {
        String requestId = "audit-read-" + UUID.randomUUID();
        HttpResponse<String> response = expect(api.manage("GET", table + "/audit", null, AUTH, api.grantAdmin(),
                "X-Request-ID", requestId), 200);
        CatalogEvent record = api.awaitRequest(requestId);
        assertThat(record.data().type()).isEqualTo(EventType.AUDIT_READ);
        AuditRead read = (AuditRead) record.data();
        assertThat(read.target().path()).containsExactly(ns, "t");
        assertThat(read.target().id().toString()).isEqualTo(api.id(ns, "tables", "t"));
        assertThat(read.returned()).isEqualTo(JsonPath.<List<Object>>read(response.body(), "$.records").size());
        assertThat(read.actor().principal()).isEqualTo("gina");
    }

    /** The trail once every named type is in it; the relay delivers changes a moment after they commit. */
    private HttpResponse<String> awaitTypes(String path, String... types) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            HttpResponse<String> response = expect(api.manage("GET", path + "/audit", null, AUTH, api.grantAdmin()), 200);
            List<String> seen = JsonPath.read(response.body(), "$.records[*].type");
            if (seen.containsAll(List.of(types))) {
                return response;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("audit trail of " + path + " never held all of " + List.of(types) + ": " + seen);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
