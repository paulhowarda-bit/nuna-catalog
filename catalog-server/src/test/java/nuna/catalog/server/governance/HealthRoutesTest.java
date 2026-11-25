package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Table health and the worker's scans (D38): the maintenance worker alone reports them, a report for a table gone or
 * now another is refused, a reader sees the neediest tables they may select and a table's health with select on it,
 * and platform operators see the scans.
 */
@Tag("heavy")
@GovernanceServer
class HealthRoutesTest {

    private static final String AUTH = "Authorization";
    private static final String[] WORKER = {GovernanceServer.PRINCIPAL, "maintenance"};

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
    void theWorkersReportsMakeTheTablesHealth() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String health = "/namespaces/" + ns + "/tables/orders/health";
        assertThat(JsonPath.<Map<String, Object>>read(expect(api.manage("GET", health, null), 200).body(), "$"))
                .as("nothing reported yet").containsOnlyKeys("id", "catalog", "namespace", "name", "requests")
                .containsEntry("requests", List.of());

        expect(report(id, "assessment", ns, "orders", assessment(at, 40, 10, 2, 1, 0, List.of("small-files",
                "deletes"), 2.5)), 204);
        expect(report(id, "compaction", ns, "orders", compaction(at.plusSeconds(5), null, "running")), 204);
        expect(report(id, "compaction", ns, "orders", compaction(at.plusSeconds(5), at.plusSeconds(65), "cut-off")),
                204);
        expect(report(id, "compaction", ns, "orders", compaction(at.plusSeconds(5), null, "running")), 204);

        String body = expect(api.manage("GET", health, null), 200).body();
        assertThat(JsonPath.<String>read(body, "$.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(body, "$.catalog")).isEqualTo(Api.CATALOG);
        assertThat(JsonPath.<List<String>>read(body, "$.namespace")).containsExactly(ns);
        assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("orders");
        assertThat(JsonPath.<String>read(body, "$.assessment.assessed-at")).isEqualTo(at.toString());
        assertThat(JsonPath.<Boolean>read(body, "$.assessment.needs-compaction")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.assessment.reasons")).containsExactly("deletes", "small-files");
        assertThat(JsonPath.<Double>read(body, "$.assessment.score")).isEqualTo(2.5);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.data-files")).isEqualTo(40);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.small-files")).isEqualTo(10);
        assertThat(JsonPath.<Double>read(body, "$.assessment.small-file-share")).isEqualTo(0.25);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.delete-files")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.position-delete-files")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.deletion-vectors")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.assessment.equality-delete-files")).isEqualTo(0);
        assertThat(JsonPath.<String>read(body, "$.last-compaction.started-at")).isEqualTo(at.plusSeconds(5).toString());
        assertThat(JsonPath.<String>read(body, "$.last-compaction.ended-at")).as("its start, reported again once it"
                + " ended, changes nothing").isEqualTo(at.plusSeconds(65).toString());
        assertThat(JsonPath.<String>read(body, "$.last-compaction.outcome")).isEqualTo("cut-off");
        expectError(report(id, "assessment", ns, "orders", "{\"score\":1}"), 400, "BadRequestException");
    }

    @Test
    void onlyTheWorkerReports() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String body = withName(ns, "orders", assessment(at, 40, 40, 0, 0, 0, List.of("small-files"), 8));

        for (String caller : List.of(api.outsider(), api.operator(), api.grantAdmin())) {
            expectError(api.manage("PUT", "/maintenance/tables/" + id + "/assessment", body, AUTH, caller), 403,
                    "ForbiddenException");
            expectError(api.manage("PUT", "/maintenance/tables/" + id + "/compaction", withName(ns, "orders",
                    compaction(at, null, "running")), AUTH, caller), 403, "ForbiddenException");
            expectError(api.manage("PUT", "/maintenance/scans/" + UUID.randomUUID(), scan(at, null, null, "{}", null),
                    AUTH, caller), 403, "ForbiddenException");
        }
        expectError(api.manage("PUT", "/maintenance/tables/" + id + "/assessment", body), 403, "ForbiddenException");

        assertThat(JsonPath.<Map<String, Object>>read(expect(api.manage("GET", "/namespaces/" + ns
                + "/tables/orders/health", null), 200).body(), "$")).doesNotContainKeys("assessment", "last-compaction");
        assertThat(neediest()).doesNotContain(id);
    }

    @Test
    void aReportForATableGoneOrNowAnotherIsRefused() {
        String ns = api.namespace();
        api.table(ns, "t");
        String renamed = api.id(ns, "tables", "t");
        expect(api.rest("POST", "/tables/rename", rename(ns, "t", "t_old")), 204);
        api.table(ns, "t");
        String current = api.id(ns, "tables", "t");
        api.table(ns, "dropped");
        String dropped = api.id(ns, "tables", "dropped");
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/dropped", null), 204);
        api.view(ns, "v");
        String view = api.id(ns, "views", "v");
        String assessment = assessment(Instant.now().truncatedTo(ChronoUnit.MILLIS), 4, 4, 0, 0, 0,
                List.of("small-files"), 1);

        expectError(report(renamed, "assessment", ns, "t", assessment), 409, "TableReplacedException");
        expectError(report(renamed, "compaction", ns, "t", compaction(Instant.now(), null, "running")), 409,
                "TableReplacedException");
        expectError(report(current, "assessment", ns, "t_old", assessment), 409, "TableReplacedException");
        expectError(report(dropped, "assessment", ns, "dropped", assessment), 404, "NoSuchTableException");
        expectError(report(view, "assessment", ns, "v", assessment), 404, "NoSuchTableException");
        expectError(report(UUID.randomUUID().toString(), "assessment", ns, "t", assessment), 404,
                "NoSuchTableException");
        expectError(report(renamed, "assessment", ns, "gone", assessment), 404, "NoSuchTableException");

        expect(report(renamed, "assessment", ns, "t_old", assessment), 204);
        expect(report(current, "assessment", ns, "t", assessment), 204);
    }

    @Test
    void aReaderSeesTheNeediestTablesTheyMaySelect() {
        String readable = api.namespace();
        String hidden = api.namespace();
        api.table(readable, "a");
        api.table(readable, "c");
        api.table(readable, "fine");
        api.table(hidden, "b");
        String a = api.id(readable, "tables", "a");
        String c = api.id(readable, "tables", "c");
        String fine = api.id(readable, "tables", "fine");
        String b = api.id(hidden, "tables", "b");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        expect(report(a, "assessment", readable, "a", assessment(at, 50, 50, 0, 0, 0, List.of("small-files"), 5)), 204);
        expect(report(c, "assessment", readable, "c", assessment(at, 10, 0, 0, 0, 1, List.of("equality-deletes"),
                0.1)), 204);
        expect(report(fine, "assessment", readable, "fine", assessment(at, 10, 0, 0, 0, 0, List.of(), 0.5)), 204);
        expect(report(b, "assessment", hidden, "b", assessment(at, 90, 90, 0, 0, 0, List.of("small-files"), 9)), 204);
        String readers = "readers-" + Api.suffix();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + readable
                + "\"]},\"group\":\"" + readers + "\",\"privilege\":\"select\"}", AUTH, api.grantAdmin()), 204);
        String[] reader = {GovernanceServer.PRINCIPAL, "rita", GovernanceServer.GROUPS, readers};
        Set<String> mine = Set.of(a, b, c, fine);

        assertThat(neediest(reader).stream().filter(mine::contains).toList()).as("one table a page")
                .containsExactly(c, a);

        assertThat(neediest(AUTH, api.operator())).as("platform operators hold no grant")
                .doesNotContainAnyElementsOf(mine);
        assertThat(neediest().stream().filter(mine::contains).toList()).containsExactly(c, b, a);
        expect(api.manage("GET", "/namespaces/" + readable + "/tables/a/health", null, reader), 200);
        // Rita holds nothing on the hidden namespace: its table is answered as if it did not exist (D18, backlog 73).
        expectError(api.manage("GET", "/namespaces/" + hidden + "/tables/b/health", null, reader), 404,
                "NoSuchTableException");
    }

    @Test
    void operatorsReadTheWorkersScans() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(3600);
        String succeeded = UUID.randomUUID().toString();
        String failed = UUID.randomUUID().toString();

        expect(api.manage("PUT", "/maintenance/scans/" + succeeded, scan(at, null, null, "{}", null), WORKER), 204);
        expect(api.manage("PUT", "/maintenance/scans/" + succeeded, scan(at, at.plusSeconds(30), "succeeded",
                "{\"purge\":2}", null), WORKER), 204);
        expect(api.manage("PUT", "/maintenance/scans/" + failed, scan(at.plusSeconds(60), at.plusSeconds(61),
                "failed", "{\"work-list\":1}", "the tables section: no catalog"), WORKER), 204);
        expectError(api.manage("PUT", "/maintenance/scans/" + UUID.randomUUID(), scan(at, null, "failed", "{}",
                null), WORKER), 400, "BadRequestException");

        String body = expect(api.send("GET", "/management/v1/maintenance/scans", null, AUTH, api.operator()), 200)
                .body();
        assertThat(JsonPath.<List<String>>read(body, "$.scans[*].id")).containsSubsequence(failed, succeeded);
        assertThat(JsonPath.<String>read(body, "$.last-success.id")).isEqualTo(succeeded);
        assertThat(JsonPath.<String>read(body, "$.last-success.worker")).isEqualTo("maintenance");
        assertThat(JsonPath.<String>read(body, "$.last-success.ended-at")).isEqualTo(at.plusSeconds(30).toString());
        assertThat(JsonPath.<Integer>read(body, "$.last-success.failures.purge")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.scans[?(@.id == '" + failed + "')].error"))
                .containsExactly("the tables section: no catalog");
        expectError(api.send("GET", "/management/v1/maintenance/scans", null), 403, "ForbiddenException");
    }

    /**
     * Backlog 250: a scan keeps what the worker reports its own storage identities reach, by kind and endpoint with
     * the prefixes under each, and the groups its token carried, and operators read both with the scans.
     */
    @Test
    void aScanKeepsWhatTheWorkerReachesAndTheGroupsItsTokenCarried() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(7200);
        String id = UUID.randomUUID().toString();
        String[] worker = {GovernanceServer.PRINCIPAL, "maintenance", GovernanceServer.GROUPS, "maintainers"};
        String storage = "[{\"kind\":\"s3\",\"endpoint\":\"https://silo.example:9000\",\"prefixes\":["
                + "{\"prefix\":\"s3://lake/tables\",\"listed\":\"ok\",\"deleted\":12,\"delete-refused\":0,"
                + "\"delete-failed\":0},"
                + "{\"prefix\":\"s3://ledger-warehouse/tables\",\"listed\":\"refused\",\"list-code\":\"AccessDenied\","
                + "\"deleted\":0,\"delete-refused\":3,\"delete-failed\":1,\"delete-code\":\"AccessDenied\"}]},"
                + "{\"kind\":\"s3\",\"prefixes\":[{\"prefix\":\"s3://archive\",\"listed\":\"failed\","
                + "\"list-code\":\"SdkClientException\"}]}]";

        expect(api.manage("PUT", "/maintenance/scans/" + id, withStorage(scan(at, at.plusSeconds(30), "succeeded",
                "{}", null), storage), worker), 204);

        String body = expect(api.send("GET", "/management/v1/maintenance/scans", null, AUTH, api.operator()), 200)
                .body();
        String scan = "$.scans[?(@.id == '" + id + "')]";
        assertThat(JsonPath.<List<List<String>>>read(body, scan + ".groups")).containsExactly(List.of("maintainers"));
        assertThat(JsonPath.<List<String>>read(body, scan + ".storage[0].endpoint"))
                .containsExactly("https://silo.example:9000");
        assertThat(JsonPath.<List<String>>read(body, scan + ".storage[0].prefixes[*].prefix"))
                .containsExactly("s3://lake/tables", "s3://ledger-warehouse/tables");
        assertThat(JsonPath.<List<String>>read(body, scan + ".storage[0].prefixes[1].listed"))
                .containsExactly("refused");
        assertThat(JsonPath.<List<String>>read(body, scan + ".storage[0].prefixes[1].list-code"))
                .containsExactly("AccessDenied");
        assertThat(JsonPath.<List<Integer>>read(body, scan + ".storage[0].prefixes[1].delete-refused"))
                .containsExactly(3);
        assertThat(JsonPath.<List<Integer>>read(body, scan + ".storage[0].prefixes[0].deleted")).containsExactly(12);
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, scan + ".storage[1]")).as("AWS S3's own: no endpoint")
                .singleElement().satisfies(store -> assertThat(store).doesNotContainKey("endpoint"));
        assertThat(JsonPath.<List<Integer>>read(body, scan + ".storage[1].prefixes[0].delete-refused"))
                .as("a count left out is 0").containsExactly(0);

        for (String refused : List.of(
                "[{\"kind\":\"s3\",\"prefixes\":[{\"prefix\":\"s3://lake/tables\",\"listed\":\"ok\","
                        + "\"list-code\":\"AccessDenied\"}]}]",
                "[{\"kind\":\"s3\",\"prefixes\":[{\"prefix\":\"s3://lake/tables\",\"listed\":\"maybe\"}]}]",
                "[{\"kind\":\"s3\",\"prefixes\":[{\"prefix\":\"s3://lake/tables\",\"listed\":\"refused\","
                        + "\"list-code\":\"Access Denied for tables/ns/t/data/a.parquet\"}]}]",
                "[{\"kind\":\"s3\",\"prefixes\":[{\"prefix\":\"s3://lake/tables\",\"listed\":\"ok\","
                        + "\"delete-refused\":1}]}]",
                "{\"kind\":\"s3\"}")) {
            expectError(api.manage("PUT", "/maintenance/scans/" + UUID.randomUUID(), withStorage(scan(at, null, null,
                    "{}", null), refused), worker), 400, "BadRequestException");
        }
    }

    private static String withStorage(String scan, String storage) {
        return scan.substring(0, scan.length() - 1) + ",\"storage\":" + storage + "}";
    }

    private HttpResponse<String> report(String id, String what, String namespace, String name, String body) {
        return api.manage("PUT", "/maintenance/tables/" + id + "/" + what, body.contains("\"catalog\"") ? body
                : withName(namespace, name, body), WORKER);
    }

    /**
     * The ids of the neediest tables the caller (the tester, unless {@code headers} name another) may read, walking
     * every page one table at a time.
     */
    private List<String> neediest(String... headers) {
        List<String> ids = new ArrayList<>();
        String token = null;
        for (int pages = 0; pages < 10_000; pages++) {
            String path = "/management/v1/maintenance/tables?pageSize=1"
                    + (token == null ? "" : "&pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            String body = expect(api.send("GET", path, null, headers), 200).body();
            ids.addAll(JsonPath.read(body, "$.tables[*].id"));
            Map<String, Object> page = JsonPath.read(body, "$");
            if (!page.containsKey("next-page-token")) {
                return ids;
            }
            token = (String) page.get("next-page-token");
        }
        throw new AssertionError("the listing did not end within 10,000 pages");
    }

    private static String withName(String namespace, String name, String body) {
        return "{\"catalog\":\"" + Api.CATALOG + "\",\"namespace\":[\"" + namespace + "\"],\"name\":\"" + name + "\","
                + body.substring(1);
    }

    private static String assessment(Instant at, long data, long small, long position, long vectors, long equality,
                                     List<String> reasons, double score) {
        return "{\"assessed-at\":\"" + at + "\",\"data-files\":" + data + ",\"small-files\":" + small
                + ",\"position-delete-files\":" + position + ",\"deletion-vectors\":" + vectors
                + ",\"equality-delete-files\":" + equality + ",\"reasons\":["
                + String.join(",", reasons.stream().map(reason -> "\"" + reason + "\"").toList()) + "],\"score\":"
                + score + "}";
    }

    private static String compaction(Instant started, Instant ended, String outcome) {
        return "{\"started-at\":\"" + started + "\"" + (ended == null ? "" : ",\"ended-at\":\"" + ended + "\"")
                + ",\"outcome\":\"" + outcome + "\"}";
    }

    private static String scan(Instant started, Instant ended, String outcome, String failures, String error) {
        return "{\"started-at\":\"" + started + "\"" + (ended == null ? "" : ",\"ended-at\":\"" + ended + "\"")
                + (outcome == null ? "" : ",\"outcome\":\"" + outcome + "\"") + ",\"failures\":" + failures
                + (error == null ? "" : ",\"error\":\"" + error + "\"") + "}";
    }

    private static String rename(String namespace, String from, String to) {
        return "{\"source\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + to + "\"}}";
    }
}
