package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.EventType;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * Soft drop, undrop within the window, purge after it by a maintenance principal only, as a claim then its
 * completion (D25, D30), and export.
 */
@Tag("heavy")
@GovernanceServer
class LifecycleTest {

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
    void undropRestoresTheSameObjectWithinTheWindowOnly() {
        String ns = api.namespace();
        String location = api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t", null), 204);
        HttpResponse<String> dropped = expect(api.manage("GET", "/namespaces/" + ns + "/dropped", null), 200);
        assertThat(JsonPath.<List<String>>read(dropped.body(), "$.dropped[*].id")).containsExactly(id);

        api.table(ns, "t");
        expectError(api.manage("POST", "/objects/" + id + "/undrop", null), 409, "AlreadyExistsException");
        HttpResponse<String> undropped = expect(api.manage("POST", "/objects/" + id + "/undrop",
                "{\"name\":\"t_restored\"}"), 200);
        assertThat(JsonPath.<String>read(undropped.body(), "$.state")).isEqualTo("active");
        HttpResponse<String> loaded = expect(api.rest("GET", "/namespaces/" + ns + "/tables/t_restored", null), 200);
        assertThat(JsonPath.<String>read(loaded.body(), "$.metadata-location")).isEqualTo(location);
        expectError(api.manage("POST", "/objects/" + id + "/undrop", null), 409, "NotDroppedException");

        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t_restored", null), 204);
        clock.advance(Duration.ofDays(30));
        expectError(api.manage("POST", "/objects/" + id + "/undrop", "{\"name\":\"late\"}"), 409,
                "UndropWindowClosedException");
    }

    @Test
    void onlyAMaintenancePrincipalPurgesAndOnlyAfterTheWindow() {
        String ns = api.namespace();
        api.table(ns, "t");
        String id = api.id(ns, "tables", "t");
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t", null), 204);

        for (String route : List.of("/purge-claim", "/purge")) {
            String denied = "denied-" + UUID.randomUUID();
            expectError(api.manage("POST", "/objects/" + id + route, null, GovernanceServer.PRINCIPAL, "analyst",
                    "X-Request-ID", denied), 403, "ForbiddenException");
            assertThat(api.awaitRequest(denied).data().type()).as(route).isEqualTo(EventType.DENIAL);
        }

        expectError(claim(id, "maintenance"), 409, "PurgeNotDueException");
        clock.advance(Duration.ofDays(31));
        expectError(complete(id, "maintenance"), 409, "PurgeNotClaimedException");
        HttpResponse<String> claimed = expect(claim(id, "maintenance"), 200);
        assertThat(JsonPath.<String>read(claimed.body(), "$.state")).isEqualTo("purging");
        assertThat(JsonPath.<String>read(claimed.body(), "$.purge-claimant")).isEqualTo("maintenance");
        expect(claim(id, "maintenance"), 200);
        expectError(claim(id, "maintenance-2"), 409, "PurgeClaimedException");
        expectError(complete(id, "maintenance-2"), 409, "PurgeClaimedException");
        expectError(api.manage("POST", "/objects/" + id + "/undrop", "{\"name\":\"back\"}"), 409, "NotDroppedException");

        expect(complete(id, "maintenance"), 204);

        HttpResponse<String> export = expect(api.manage("GET", "/objects/" + id + "/export", null), 200);
        assertThat(JsonPath.<String>read(export.body(), "$.object.state")).isEqualTo("purged");
        expectError(complete(id, "maintenance"), 409, "NotDroppedException");
        expectError(claim(id, "maintenance"), 409, "NotDroppedException");
    }

    @Test
    void purgesAreRateLimited() {
        String ns = api.namespace();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            api.table(ns, "t" + i);
            ids.add(api.id(ns, "tables", "t" + i));
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t" + i, null), 204);
        }
        clock.advance(Duration.ofDays(31));
        for (int i = 0; i < 3; i++) {
            expect(claim(ids.get(i), "maintenance"), 200);
        }
        HttpResponse<String> limited = claim(ids.get(3), "maintenance");
        expectError(limited, 429, "TooManyRequestsException");
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        expect(claim(ids.get(0), "maintenance"), 200);

        clock.advance(Duration.ofMinutes(2));
        expect(claim(ids.get(3), "maintenance"), 200);
        for (String id : ids) {
            expect(complete(id, "maintenance"), 204);
        }
    }

    @Test
    void exportCarriesTheCurrentSnapshotAndTheWholeJournal() {
        String ns = api.namespace();
        String table = "/namespaces/" + ns + "/tables/t";
        String location = api.table(ns, "t");
        expect(api.rest("POST", table, Api.snapshot(location, 7, System.currentTimeMillis())), 200);
        String id = api.id(ns, "tables", "t");

        HttpResponse<String> export = expect(api.manage("GET", "/objects/" + id + "/export", null), 200);
        String current = JsonPath.read(expect(api.rest("GET", table, null), 200).body(), "$.metadata-location");
        assertThat(JsonPath.<String>read(export.body(), "$.pointer.metadata-location")).isEqualTo(current);
        assertThat(JsonPath.<Integer>read(export.body(), "$.metadata.current-snapshot-id")).isEqualTo(7);
        assertThat(JsonPath.<List<String>>read(export.body(), "$.journal[*].new-location"))
                .containsExactly(location, current);
        expectError(api.manage("GET", "/objects/" + UUID.randomUUID() + "/export", null), 404, "NoSuchObjectException");
    }

    private HttpResponse<String> claim(String id, String principal) {
        return api.manage("POST", "/objects/" + id + "/purge-claim", null, GovernanceServer.PRINCIPAL, principal);
    }

    private HttpResponse<String> complete(String id, String principal) {
        return api.manage("POST", "/objects/" + id + "/purge", null, GovernanceServer.PRINCIPAL, principal);
    }
}
