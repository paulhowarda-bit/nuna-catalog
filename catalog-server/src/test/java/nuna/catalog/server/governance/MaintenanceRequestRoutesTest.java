package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A table's maintenance on request through the server (backlog 114): a caller with {@code modify} asks, the table's
 * health shows the request waiting, the work list's {@code requests} section carries it with the table's policy, and
 * the worker's job report naming it marks it started and takes it off the work list. A reader is refused, and so is a
 * request under a DR freeze.
 */
@Tag("heavy")
@GovernanceServer
class MaintenanceRequestRoutesTest {

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

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    @Test
    void aRequestWaitsIsListedForTheWorkerAndItsJobReportStartsIt() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        String table = "/namespaces/" + ns + "/tables/orders";
        expect(api.manage("PUT", table + "/maintenance-policy",
                "{\"compaction.enabled\":\"true\",\"orphan-removal.enabled\":\"true\"}"), 200);

        String asked = expect(api.manage("POST", table + "/maintenance/requests", "{\"job\":\"compaction\"}"), 202)
                .body();
        assertThat(JsonPath.<String>read(asked, "$.state")).isEqualTo("waiting");
        assertThat(JsonPath.<String>read(asked, "$.requested-by")).isEqualTo("tester");
        String requestedAt = JsonPath.read(asked, "$.requested-at");
        assertThat(JsonPath.<String>read(expect(api.manage("POST", table + "/maintenance/requests",
                "{\"job\":\"compaction\"}"), 202).body(), "$.requested-at")).as("asked again: the one waiting")
                .isEqualTo(requestedAt);

        String health = expect(api.manage("GET", table + "/health", null), 200).body();
        assertThat(JsonPath.<List<String>>read(health, "$.requests[*].state")).containsExactly("waiting");
        assertThat(JsonPath.<List<String>>read(health, "$.requests[*].job")).containsExactly("compaction");

        Map<String, Object> listed = requested(id);
        assertThat(listed).containsEntry("job", "compaction").containsEntry("requested-at", requestedAt)
                .containsEntry("requested-by", "tester").containsEntry("name", "orders");
        assertThat(JsonPath.<String>read(listed, "$.policy['compaction.enabled']")).isEqualTo("true");

        Instant started = clock.instant();
        LocalDate night = LocalDate.ofInstant(started, ZoneOffset.UTC);
        Instant opens = night.atTime(1, 0).toInstant(ZoneOffset.UTC);
        expect(api.manage("PUT", "/maintenance/jobs/" + UUID.randomUUID(), "{\"night\":\"" + night + "\",\"opens\":\""
                + opens + "\",\"closes\":\"" + opens.plusSeconds(4 * 3600) + "\",\"kind\":\"compaction\","
                + "\"object-id\":\"" + id + "\",\"slot\":1,\"started-at\":\"" + started + "\",\"outcome\":\"running\","
                + "\"requested-at\":\"" + requestedAt + "\"}", AUTH, api.maintenance()), 204);

        String after = expect(api.manage("GET", table + "/health", null), 200).body();
        assertThat(JsonPath.<List<String>>read(after, "$.requests[*].state")).containsExactly("started");
        assertThat(JsonPath.<List<String>>read(after, "$.requests[*].started-at")).hasSize(1);
        assertThat(requestedOrNone(id)).as("started: off the work list").isEmpty();
    }

    @Test
    void aReaderIsRefusedAndSoIsARequestUnderADrFreeze() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String table = "/namespaces/" + ns + "/tables/orders";
        expect(api.manage("PUT", table + "/maintenance-policy", "{\"compaction.enabled\":\"true\"}"), 200);
        String readers = "readers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + ns
                + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);

        expect(api.manage("POST", table + "/maintenance/requests", "{\"job\":\"compaction\"}",
                GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers), 403);
        expectError(api.manage("POST", table + "/maintenance/requests", "{\"job\":\"rewrite\"}"), 400,
                "BadRequestException");

        expect(api.manage("PUT", "/namespaces/" + Api.ns(ns) + "/dr-freeze", "{\"reason\":\"DR drill\"}", AUTH,
                api.operator()), 200);
        expect(api.manage("POST", table + "/maintenance/requests", "{\"job\":\"compaction\"}"), 403);
        assertThat(JsonPath.<List<Object>>read(expect(api.manage("GET", table + "/health", null), 200).body(),
                "$.requests")).isEmpty();
    }

    private Map<String, Object> requested(String id) {
        return requestedOrNone(id).orElseThrow(() -> new AssertionError("no request of " + id + " listed"));
    }

    private java.util.Optional<Map<String, Object>> requestedOrNone(String id) {
        List<Map<String, Object>> items = new ArrayList<>();
        String token = null;
        do {
            HttpResponse<String> page = expect(api.manage("GET", "/maintenance/work?section=requests&pageSize=1000"
                    + (token == null ? "" : "&pageToken=" + token), null, AUTH, api.maintenance()), 200);
            items.addAll(JsonPath.<List<Map<String, Object>>>read(page.body(), "$.requests"));
            List<String> next = JsonPath.read(page.body(), "$..next-page-token");
            token = next.isEmpty() ? null : next.getFirst();
        } while (token != null);
        return items.stream().filter(item -> id.equals(item.get("id"))).findFirst();
    }
}
