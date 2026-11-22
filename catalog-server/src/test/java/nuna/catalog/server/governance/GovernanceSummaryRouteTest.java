package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The governance summary and the holds and freezes on the wire (D45, item 128), on PostgreSQL with a Kafka broker:
 * a hold and a freeze placed, counted and listed from the store, then released and lifted, and listed again from the
 * audit topic once the relay has published them. The test catalog is shared with every M7 test, so this looks only
 * at its own namespace. {@link GovernanceSummaryTest} checks the counts and refusals case by case.
 */
@Tag("heavy")
@GovernanceServer
class GovernanceSummaryRouteTest {

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
    void aHoldAndAFreezeAreCountedListedAndOnceLiftedListedFromTheAuditTopic() {
        String ns = api.namespace();
        api.table(ns, "closed_one");
        api.table(ns, "held_one");
        String nsId = JsonPath.read(expect(api.manage("GET", "/namespaces/" + ns + "/governance", null), 200).body(),
                "$.target.id");
        String heldId = api.id(ns, "tables", "held_one");
        String closedId = api.id(ns, "tables", "closed_one");
        expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/regulated",
                "{\"value\":\"true\",\"provenance\":\"compliance-register\"}", "Authorization", api.steward()), 200);
        expect(api.manage("PUT", "/namespaces/" + ns + "/tables/held_one/attributes/classification",
                "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/7\"}", "Authorization", api.steward()),
                200);
        String reason = "Matter " + Api.suffix();
        String holdId = JsonPath.read(expect(api.manage("POST", "/namespaces/" + ns + "/tables/held_one/holds",
                "{\"reason\":\"" + reason + "\"}", "Authorization", api.steward()), 201).body(), "$.id");
        String drill = "DR drill " + Api.suffix();
        expect(api.manage("PUT", "/namespaces/" + ns + "/dr-freeze", "{\"reason\":\"" + drill + "\"}",
                "Authorization", api.operator()), 200);

        HttpResponse<String> summary = expect(api.manage("GET", "/governance/summary", null), 200);
        assertThat(JsonPath.<String>read(summary.body(), "$.catalog")).isEqualTo(Api.CATALOG);
        assertThat(JsonPath.<List<String>>read(summary.body(), "$.closed[*].id")).contains(closedId)
                .doesNotContain(heldId);
        assertThat(JsonPath.<List<Integer>>read(summary.body(),
                "$.regulated[?(@.on.id == '" + nsId + "')].tables")).containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(summary.body(),
                "$.regulated[?(@.on.id == '" + nsId + "')].closed")).containsExactly(1);
        assertThat(JsonPath.<Integer>read(summary.body(), "$.tables.held")).isPositive();
        assertThat(JsonPath.<Integer>read(summary.body(), "$.tables.frozen")).isGreaterThanOrEqualTo(2);

        HttpResponse<String> standing = expect(api.manage("GET", "/governance/holds", null), 200);
        assertThat(JsonPath.<List<String>>read(standing.body(), "$.standing[?(@.id == '" + holdId + "')].reason"))
                .containsExactly(reason);
        assertThat(JsonPath.<List<String>>read(standing.body(),
                "$.standing[?(@.kind == 'freeze' && @.on.id == '" + nsId + "')]['placed-by']")).containsExactly("ops");

        expect(api.manage("DELETE", "/namespaces/" + ns + "/tables/held_one/holds/" + holdId, null,
                "Authorization", api.steward()), 204);
        expect(api.manage("DELETE", "/namespaces/" + ns + "/dr-freeze", null, "Authorization", api.operator()), 204);

        HttpResponse<String> lifted = awaitLifted(holdId, nsId);
        Map<String, Object> hold = JsonPath.<List<Map<String, Object>>>read(lifted.body(),
                "$.lifted[?(@.id == '" + holdId + "')]").getFirst();
        assertThat(hold).containsEntry("kind", "hold").containsEntry("reason", reason)
                .containsEntry("placed-by", "stella").containsEntry("lifted-by", "stella");
        Map<String, Object> freeze = JsonPath.<List<Map<String, Object>>>read(lifted.body(),
                "$.lifted[?(@.kind == 'freeze' && @.on.id == '" + nsId + "')]").getFirst();
        assertThat(freeze).containsEntry("reason", drill).containsEntry("placed-by", "ops")
                .containsEntry("lifted-by", "ops");
        assertThat(JsonPath.<List<String>>read(lifted.body(), "$.standing[*].reason")).doesNotContain(reason, drill);
        assertThat(JsonPath.<Boolean>read(lifted.body(), "$.history.available")).isTrue();
    }

    @Test
    void aCallerWithoutDescribeOnTheCatalogIsRefused() {
        expectError(api.manage("GET", "/governance/summary", null, "Authorization", api.outsider()), 404,
                "NoSuchWarehouseException");
        expectError(api.manage("GET", "/governance/holds", null, "Authorization", api.bystander()), 403,
                "ForbiddenException");
        expectError(api.manage("GET", "/governance/summary", null, "Authorization", api.operator()), 403,
                "ForbiddenException");
    }

    /** The holds and freezes, once the relay has published the release and the lift; up to 30 seconds. */
    private HttpResponse<String> awaitLifted(String holdId, String nsId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            HttpResponse<String> holds = expect(api.manage("GET", "/governance/holds", null), 200);
            if (!JsonPath.<List<Object>>read(holds.body(), "$.lifted[?(@.id == '" + holdId + "')]").isEmpty()
                    && !JsonPath.<List<Object>>read(holds.body(),
                    "$.lifted[?(@.kind == 'freeze' && @.on.id == '" + nsId + "')]").isEmpty()) {
                return holds;
            }
            assertThat(System.nanoTime()).as("the release and lift reached the audit topic: " + holds.body())
                    .isLessThan(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
