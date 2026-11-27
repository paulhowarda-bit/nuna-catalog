package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The maintenance nights (backlog 126): the maintenance worker alone reports a job, a job of an object the catalog
 * never had is refused, a night adds up its jobs, and each job names its table only to a caller who may select it.
 */
@Tag("heavy")
@GovernanceServer
class MaintenanceNightsRoutesTest {

    private static final String AUTH = "Authorization";
    private static final String[] WORKER = {GovernanceServer.PRINCIPAL, "maintenance"};
    private static final String NIGHTS = "/management/v1/maintenance/nights?days=32&jobs=32";

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
    void onlyTheWorkerReportsAJob() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        LocalDate night = today().minusDays(3);
        Instant opens = opens(night);
        String job = job(night, "compaction", id, 1, opens, null, "running", 0, 0, 0, 0, 0);

        for (String caller : List.of(api.outsider(), api.operator(), api.grantAdmin())) {
            expectError(report(UUID.randomUUID(), job, AUTH, caller), 403, "ForbiddenException");
        }
        expectError(report(UUID.randomUUID(), job), 403, "ForbiddenException");
        expectError(report(UUID.randomUUID(), job(night, "compaction", UUID.randomUUID().toString(), 1, opens, null,
                "running", 0, 0, 0, 0, 0), WORKER), 404, "NoSuchObjectException");
        expectError(report(UUID.randomUUID(), job(night, "compaction", id, null, opens, null, "running", 0, 0, 0, 0,
                0), WORKER), 400, "BadRequestException");
        expectError(report(UUID.randomUUID(), job(night, "compaction", id, 1, opens, opens.plusSeconds(1), "running",
                0, 0, 0, 0, 0), WORKER), 400, "BadRequestException");
        expectError(report(UUID.randomUUID(), job.replace("\"compaction\"", "\"vacuum\""), WORKER), 400,
                "BadRequestException");

        String kept = UUID.randomUUID().toString();
        expect(report(UUID.fromString(kept), job, WORKER), 204);
        assertThat(jobs(nights(), night)).extracting(each -> each.get("id")).containsExactly(kept);
    }

    @Test
    void aNightAddsUpItsJobsAndNamesOnlyTheTablesTheCallerMaySelect() {
        String readable = api.namespace();
        String hidden = api.namespace();
        api.table(readable, "a");
        api.table(hidden, "b");
        String a = api.id(readable, "tables", "a");
        String b = api.id(hidden, "tables", "b");
        LocalDate night = today().minusDays(1);
        Instant opens = opens(night);
        UUID compactA = UUID.randomUUID();
        UUID compactB = UUID.randomUUID();
        UUID orphans = UUID.randomUUID();

        expect(report(compactA, job(night, "compaction", a, 1, opens, null, "running", 0, 0, 0, 0, 0), WORKER), 204);
        expect(report(compactA, job(night, "compaction", a, 1, opens, opens.plusSeconds(60), "succeeded", 10, 2, 0,
                0, 0), WORKER), 204);
        expect(report(compactA, job(night, "compaction", a, 1, opens.plusSeconds(30), null, "running", 0, 0, 0, 0, 0),
                WORKER), 204);
        expect(report(compactB, job(night, "compaction", b, 2, opens.plusSeconds(5), null, "running", 0, 0, 0, 0, 0),
                WORKER), 204);
        expect(report(orphans, job(night, "orphan-scan", a, null, opens.plusSeconds(10), opens.plusSeconds(20),
                "cut-off", 0, 0, 0, 0, 0), WORKER), 204);
        expect(report(UUID.randomUUID(), job(night, "snapshot-expiry", a, null, opens.plusSeconds(1),
                opens.plusSeconds(2), "succeeded", 0, 0, 4, 0, 0), WORKER), 204);
        expect(report(UUID.randomUUID(), job(night, "deletion", b, null, opens.plusSeconds(3), opens.plusSeconds(4),
                "succeeded", 0, 0, 0, 3, 300), WORKER), 204);

        Map<String, Object> totals = night(nights(), night);
        assertThat(totals).containsEntry("opens", opens.toString())
                .containsEntry("closes", opens.plusSeconds(4 * 3600).toString())
                .containsEntry("tables-compacted", 1).containsEntry("files-compacted", 10)
                .containsEntry("files-written", 2).containsEntry("snapshots-expired", 4)
                .containsEntry("files-deleted", 3).containsEntry("bytes-deleted", 300)
                .containsEntry("jobs-running", 1).containsEntry("jobs-succeeded", 3)
                .containsEntry("jobs-cut-off", 1).containsEntry("jobs-failed", 0);

        List<Map<String, Object>> all = jobs(nights(), night);
        assertThat(all).extracting(job -> job.get("id")).as("the drawn jobs, by start")
                .containsExactly(compactA.toString(), compactB.toString(), orphans.toString());
        assertThat(all.get(0)).as("a report of it running, after its end, changes nothing")
                .containsEntry("kind", "compaction").containsEntry("slot", 1)
                .containsEntry("started-at", opens.toString())
                .containsEntry("ended-at", opens.plusSeconds(60).toString()).containsEntry("outcome", "succeeded")
                .containsEntry("files-in", 10).containsEntry("files-out", 2);
        assertThat(all.get(1)).containsEntry("slot", 2).containsEntry("outcome", "running")
                .doesNotContainKey("ended-at");
        assertThat(all.get(2)).containsEntry("kind", "orphan-scan").containsEntry("outcome", "cut-off")
                .doesNotContainKey("slot");
        assertThat(all).allSatisfy(job -> assertThat(job).containsKey("table"));
        assertThat(JsonPath.<List<String>>read(nights(), "$.nights[?(@.night == '" + night + "')].jobs[*].table.name"))
                .containsExactly("a", "b", "a");

        String readers = "readers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + readable
                + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        String[] reader = {GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers};
        List<Map<String, Object>> rita = jobs(nights(reader), night);
        assertThat(rita).extracting(job -> job.containsKey("table")).as("b is not rita's to see")
                .containsExactly(true, false, true);
        assertThat(night(nights(reader), night)).as("the totals are anyone's").isEqualTo(totals);

        List<Map<String, Object>> otto = jobs(nights(AUTH, api.outsider()), night);
        assertThat(otto).hasSize(3).allSatisfy(job -> assertThat(job).doesNotContainKey("table"));

        String first = expect(api.send("GET", "/management/v1/maintenance/nights", null), 200).body();
        assertThat(JsonPath.<List<Object>>read(first, "$.nights[*].jobs")).as("only the newest night lists its jobs")
                .hasSize(1);
        assertThat(JsonPath.<List<Object>>read(expect(api.send("GET", "/management/v1/maintenance/nights?days=1&jobs=0",
                null), 200).body(), "$.nights[*]")).hasSize(1);
        // A number that is not one is the caller's mistake, named, not a 500 (backlog 311).
        expectError(api.send("GET", "/management/v1/maintenance/nights?jobs=abc", null), 400, "BadRequestException");
        expectError(api.send("GET", "/management/v1/maintenance/nights?days=1.5", null), 400, "BadRequestException");
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The night's window, 01:00 to 05:00 UTC. */
    private static Instant opens(LocalDate night) {
        return night.atTime(1, 0).toInstant(ZoneOffset.UTC);
    }

    private HttpResponse<String> report(UUID id, String body, String... headers) {
        return api.manage("PUT", "/maintenance/jobs/" + id, body, headers);
    }

    private String nights(String... headers) {
        return expect(api.send("GET", NIGHTS, null, headers), 200).body();
    }

    private static Map<String, Object> night(String body, LocalDate night) {
        List<Map<String, Object>> found = JsonPath.read(body, "$.nights[?(@.night == '" + night + "')]");
        assertThat(found).hasSize(1);
        Map<String, Object> totals = new LinkedHashMap<>(found.getFirst());
        totals.remove("jobs");
        return totals;
    }

    private static List<Map<String, Object>> jobs(String body, LocalDate night) {
        return JsonPath.read(body, "$.nights[?(@.night == '" + night + "')].jobs[*]");
    }

    private static String job(LocalDate night, String kind, String object, Integer slot, Instant started,
                              Instant ended, String outcome, long in, long out, long expired, long deleted,
                              long bytes) {
        Instant opens = opens(night);
        return "{\"night\":\"" + night + "\",\"opens\":\"" + opens + "\",\"closes\":\"" + opens.plusSeconds(4 * 3600)
                + "\",\"kind\":\"" + kind + "\",\"object-id\":\"" + object + "\""
                + (slot == null ? "" : ",\"slot\":" + slot) + ",\"started-at\":\"" + started + "\""
                + (ended == null ? "" : ",\"ended-at\":\"" + ended + "\"") + ",\"outcome\":\"" + outcome + "\""
                + ",\"files-in\":" + in + ",\"files-out\":" + out + ",\"snapshots-expired\":" + expired
                + ",\"files-deleted\":" + deleted + ",\"bytes-deleted\":" + bytes + "}";
    }
}
