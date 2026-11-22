package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Every table, one row each, for the console's ice line (backlog 127), through the server: a reader gets a row for each
 * table they may select, with its assessment, hold, freeze, closure and last night, and no row for any other table;
 * a caller who may select nothing gets none.
 */
@Tag("heavy")
@GovernanceServer
class MaintenanceEstateRoutesTest {

    private static final String AUTH = "Authorization";
    private static final String[] WORKER = {GovernanceServer.PRINCIPAL, "maintenance"};
    private static final String ESTATE = "/management/v1/maintenance/estate";

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
    void aReaderGetsOneRowForEachTableTheyMaySelectAndNoneForAnother() {
        String markets = api.namespace();
        String ledger = api.namespace();
        String hidden = api.namespace();
        api.table(markets, "trades");
        api.table(markets, "fx");
        api.table(ledger, "journal");
        api.table(ledger, "accounts");
        api.table(hidden, "scratch");
        String trades = api.id(markets, "tables", "trades");

        assess(markets, "trades", 2316, "[\"small-files\"]", 3.4);
        assess(ledger, "accounts", 22, "[]", 0);
        assess(hidden, "scratch", 31, "[\"small-files\"]", 1.4);
        // Tomorrow's is the newest night the read can find, so no other test's night is newer.
        LocalDate night = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1);
        compacted(night, trades);
        compacted(night, api.id(hidden, "tables", "scratch"));

        String steward = api.steward();
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ledger) + "/tables/journal/attributes/classification",
                "{\"value\":\"confidential\",\"provenance\":\"estate test\"}", AUTH, steward), 200);
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ledger) + "/attributes/regulated",
                "{\"value\":\"true\",\"provenance\":\"estate test\"}", AUTH, steward), 200);
        expect(api.manage("POST", "/namespaces/" + Api.ns(markets) + "/holds", "{\"reason\":\"Matter 2026-114\"}",
                AUTH, steward), 201);
        expect(api.manage("PUT", "/namespaces/" + Api.ns(ledger) + "/dr-freeze", "{\"reason\":\"DR drill\"}",
                AUTH, api.operator()), 200);

        String readers = "readers-" + Api.suffix();
        for (String readable : List.of(markets, ledger)) {
            expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + readable
                    + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        }
        String[] reader = {GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers};

        Map<String, Map<String, Object>> rows = rows(reader);
        assertThat(rows.keySet()).as("scratch is not rita's to see").containsExactlyInAnyOrder(markets + ".trades",
                markets + ".fx", ledger + ".journal", ledger + ".accounts");

        Map<String, Object> row = rows.get(markets + ".trades");
        assertThat(row).containsEntry("id", trades).containsEntry("catalog", Api.CATALOG)
                .containsEntry("held", true).containsEntry("frozen", false).containsEntry("closed", false)
                .containsEntry("fixed-last-night", true);
        assertThat(JsonPath.<Object>read(row, "$.assessment.data-files")).isEqualTo(2316);
        assertThat(JsonPath.<Object>read(row, "$.assessment.score")).isEqualTo(3.4);
        assertThat(JsonPath.<List<String>>read(row, "$.assessment.reasons")).containsExactly("small-files");
        assertThat(JsonPath.<Boolean>read(row, "$.assessment.needs-compaction")).isTrue();
        assertThat(rows.get(markets + ".fx")).doesNotContainKey("assessment").containsEntry("held", true)
                .containsEntry("fixed-last-night", false);
        assertThat(rows.get(ledger + ".journal")).containsEntry("frozen", true).containsEntry("closed", false);
        assertThat(rows.get(ledger + ".accounts")).as("closed until classified, and rita holds select on it")
                .containsEntry("frozen", true).containsEntry("closed", true);

        assertThat(rows(AUTH, api.outsider())).as("otto may select nothing").isEmpty();
        expectError(api.send("GET", ESTATE + "?pageToken=nope", null), 400, "BadRequestException");
    }

    /** Every row the caller is given, page by page, by namespace and name. */
    private Map<String, Map<String, Object>> rows(String... headers) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        String token = null;
        do {
            String body = expect(api.send("GET", ESTATE + "?pageSize=500" + (token == null ? "" : "&pageToken="
                    + token), null, headers), 200).body();
            for (Map<String, Object> row : JsonPath.<List<Map<String, Object>>>read(body, "$.tables[*]")) {
                List<String> path = new ArrayList<>(JsonPath.<List<String>>read(row, "$.namespace"));
                path.add((String) row.get("name"));
                rows.put(String.join(".", path), row);
            }
            List<String> next = JsonPath.read(body, "$..next-page-token");
            token = next.isEmpty() ? null : next.getFirst();
        } while (token != null);
        return rows;
    }

    private void assess(String namespace, String table, long data, String reasons, double score) {
        expect(api.manage("PUT", "/maintenance/tables/" + api.id(namespace, "tables", table) + "/assessment",
                "{\"catalog\":\"" + Api.CATALOG + "\",\"namespace\":[\"" + namespace + "\"],\"name\":\"" + table
                        + "\",\"assessed-at\":\"" + clock.instant() + "\",\"data-files\":" + data
                        + ",\"small-files\":0,\"position-delete-files\":0,\"deletion-vectors\":0,"
                        + "\"equality-delete-files\":0,\"reasons\":" + reasons + ",\"score\":" + score + "}", WORKER),
                204);
    }

    private void compacted(LocalDate night, String table) {
        Instant opens = night.atTime(1, 0).toInstant(ZoneOffset.UTC);
        expect(api.manage("PUT", "/maintenance/jobs/" + UUID.randomUUID(), "{\"night\":\"" + night + "\",\"opens\":\""
                + opens + "\",\"closes\":\"" + opens.plusSeconds(4 * 3600) + "\",\"kind\":\"compaction\","
                + "\"object-id\":\"" + table + "\",\"slot\":1,\"started-at\":\"" + opens + "\",\"ended-at\":\""
                + opens.plusSeconds(60) + "\",\"outcome\":\"succeeded\",\"files-in\":10,\"files-out\":1}", WORKER),
                204);
    }
}
