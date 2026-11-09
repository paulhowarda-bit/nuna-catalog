package fidice.catalog.server.governance;

import static fidice.catalog.server.governance.Api.expect;
import static fidice.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M7 gate, held tables cannot lose snapshots, and the other commit-time guards: a hold on the
 * table or a level above it refuses snapshot removal, drop and purge (D24); a retention class keeps
 * snapshots for its minimum age; a DR freeze refuses writes (D37).
 */
@GovernanceServer
class HeldTablesTest {

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
    void aHeldTableCannotLoseSnapshotsOrBeDropped() {
        String ns = api.namespace();
        String table = "/namespaces/" + ns + "/tables/t";
        String location = api.table(ns, "t");
        expect(api.rest("POST", table, Api.snapshot(location, 1, System.currentTimeMillis())), 200);
        expect(api.rest("POST", table, Api.snapshot(location, 2, System.currentTimeMillis())), 200);

        String tableHold = hold(table);
        expectError(api.rest("POST", table, Api.removeSnapshot(1)), 403, "ForbiddenException");
        expectError(api.rest("DELETE", table, null), 403, "ForbiddenException");
        expect(api.rest("POST", table, Api.SET_PROPERTY.formatted("held")), 200);
        expect(api.manage("DELETE", table + "/holds/" + tableHold, null, "Authorization", api.steward()), 204);

        String namespaceHold = hold("/namespaces/" + ns);
        expectError(api.rest("POST", table, Api.removeSnapshot(1)), 403, "ForbiddenException");
        expectError(api.rest("DELETE", table, null), 403, "ForbiddenException");
        expect(api.manage("DELETE", "/namespaces/" + ns + "/holds/" + namespaceHold, null,
                "Authorization", api.steward()), 204);

        expect(api.rest("POST", table, Api.removeSnapshot(1)), 200);
        HttpResponse<String> loaded = expect(api.rest("GET", table, null), 200);
        assertThat(JsonPath.<List<Integer>>read(loaded.body(), "$.metadata.snapshots[*].snapshot-id")).containsExactly(2);
    }

    @Test
    void aHeldDroppedTableIsNeitherEligibleNorPurged() {
        String ns = api.namespace();
        api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t", null), 204);
        String hold = hold("/namespaces/" + ns);
        clock.advance(Duration.ofDays(31));

        assertThat(eligible().body()).doesNotContain(id);
        expectError(purge(id), 403, "ForbiddenException");

        expect(api.manage("DELETE", "/namespaces/" + ns + "/holds/" + hold, null, "Authorization", api.steward()), 204);
        assertThat(eligible().body()).contains(id);
        expect(purge(id), 204);
    }

    @Test
    void aRetentionClassKeepsSnapshotsForItsMinimumAge() {
        String ns = api.namespace();
        String table = "/namespaces/" + ns + "/tables/t";
        String location = api.table(ns, "t");
        expect(api.rest("POST", table, Api.snapshot(location, 1, System.currentTimeMillis())), 200);
        expect(api.rest("POST", table, Api.snapshot(location, 2, System.currentTimeMillis())), 200);

        expectError(api.manage("PUT", "/namespaces/" + ns + "/attributes/retention-class",
                "{\"value\":\"no-such-class\",\"provenance\":\"records-office\"}", "Authorization", api.steward()), 400,
                "BadRequestException");
        expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/retention-class",
                "{\"value\":\"trade-records\",\"provenance\":\"records-office\"}", "Authorization", api.steward()), 200);
        HttpResponse<String> refused = api.rest("POST", table, Api.removeSnapshot(1));
        expectError(refused, 403, "ForbiddenException");
        assertThat(refused.body()).contains("trade-records");

        clock.advance(Duration.ofDays(31));
        expect(api.rest("POST", table, Api.removeSnapshot(1)), 200);
    }

    @Test
    void aDrFreezeRefusesWritesUntilLifted() {
        String ns = api.namespace();
        String table = "/namespaces/" + ns + "/tables/t";
        api.table(ns, "t");
        api.table(ns, "other");
        expect(api.manage("PUT", "/namespaces/" + ns + "/dr-freeze", "{\"reason\":\"reconcile after failover\"}",
                "Authorization", api.operator()), 200);

        HttpResponse<String> commit = api.rest("POST", table, Api.SET_PROPERTY.formatted("frozen"));
        expectError(commit, 403, "ForbiddenException");
        assertThat(commit.body()).contains("reconcile after failover");
        expectError(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("new")), 403,
                "ForbiddenException");
        expectError(api.rest("DELETE", "/namespaces/" + ns + "/tables/other", null), 403, "ForbiddenException");
        expectError(api.rest("POST", "/tables/rename", "{\"source\":{\"namespace\":[\"" + ns + "\"],\"name\":\"t\"},"
                + "\"destination\":{\"namespace\":[\"" + ns + "\"],\"name\":\"t2\"}}"), 403, "ForbiddenException");
        expect(api.rest("GET", table, null), 200);

        expect(api.manage("DELETE", "/namespaces/" + ns + "/dr-freeze", null, "Authorization", api.operator()), 204);
        expect(api.rest("POST", table, Api.SET_PROPERTY.formatted("thawed")), 200);
    }

    private String hold(String target) {
        return JsonPath.read(expect(api.manage("POST", target + "/holds", "{\"reason\":\"litigation 7\"}",
                "Authorization", api.steward()), 201).body(), "$.id");
    }

    private HttpResponse<String> eligible() {
        return expect(api.manage("GET", "/maintenance/purge-eligible?pageSize=1000", null,
                "Authorization", api.maintenance()), 200);
    }

    private HttpResponse<String> purge(String id) {
        return api.manage("POST", "/objects/" + id + "/purge", null, GovernanceServer.PRINCIPAL, "maintenance");
    }
}
