package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventType;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.S3ForTests;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Maintenance readiness (backlog 251): each of the four steps the worker needs before it maintains a catalog, done,
 * missing, refused or unknown, from the worker's last scan reports (backlog 250), the catalog's grants, its storage
 * profile and its policy; names only for those who may modify the catalog or manage its grants, and platform
 * operators.
 *
 * <p>The scans are the deployment's, not the catalog's, so each test starts with none kept: the last scan is the one
 * it reports.
 */
@Tag("heavy")
@GovernanceServer
class ReadinessRoutesTest {

    private static final String AUTH = "Authorization";
    private static final String READINESS = "/management/v1/catalogs/" + Api.CATALOG + "/maintenance/readiness";
    private static final String ENDPOINT = "https://silo.example:9000";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

    @Value("${local.server.port}")
    private int port;

    private Api api;
    /** The worker's group in this test, which holds nothing until the test grants it. */
    private String workers;
    /** The catalog's own maintenance policy before the test, put back after it. */
    private Map<String, Object> policyBefore;
    private final String bucket = S3ForTests.bucket();
    /** The one allowed prefix of the test catalog's profile ({@link S3ForTests}). */
    private final String allowed = "s3://" + bucket + "/tables";
    private Instant at;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        jdbc.update("DELETE FROM maintenance_scan");
        workers = "workers-" + Api.suffix();
        policyBefore = JsonPath.read(expect(api.manage("GET", "/maintenance-policy", null), 200).body(), "$.policy");
        at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @AfterEach
    void disconnect() {
        if (policyBefore.isEmpty()) {
            api.manage("DELETE", "/maintenance-policy", null);
        } else {
            expect(api.manage("PUT", "/maintenance-policy", Management.JSON.writeValueAsString(policyBefore)), 200);
        }
        api.close();
    }

    @Test
    void beforeTheWorkerReportsWhatItReachesTheFirstThreeStepsAreUnknown() {
        policy("{\"compaction.enabled\":\"true\"}");

        String body = readiness();
        assertThat(JsonPath.<String>read(body, "$.catalog")).isEqualTo(Api.CATALOG);
        assertThat(states(body)).containsExactly("unknown", "unknown", "unknown", "done");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKey("reported-at");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.grant")).containsOnlyKeys("state");
        assertThat(JsonPath.<String>read(body, "$.storage.profile")).isEqualTo(S3ForTests.PROFILE);
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.storage.prefixes"))
                .containsExactly(Map.of("prefix", allowed));
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.store.prefixes"))
                .containsExactly(Map.of("prefix", allowed));
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.store")).doesNotContainKey("code");

        // A worker of an earlier release reports its scans without storage: nothing more is known.
        scan(at, at.plusSeconds(5), null);
        assertThat(states(readiness())).containsExactly("unknown", "unknown", "unknown", "done");
    }

    @Test
    void whenTheWorkerReachesEverythingEveryStepIsDone() {
        String readers = "readers-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, readers, "select");
        IssuerForTests.grant(jdbc, Api.CATALOG, workers, "write");
        policy("{\"compaction.enabled\":\"true\",\"snapshot-expiry.enabled\":\"true\",\"orphan-removal.enabled\":"
                + "\"false\"}");
        // The worker's prefix is the whole bucket, written as s3a://: the worker matches it as s3://.
        scan(at, at.plusSeconds(20), identity(ENDPOINT, prefix("s3a://" + bucket, "ok", null, 4, 0, null)),
                workers, readers);

        String body = readiness();
        assertThat(states(body)).containsExactly("done", "done", "done", "done");
        assertThat(JsonPath.<String>read(body, "$.reported-at")).isEqualTo(at.plusSeconds(20).toString());
        assertThat(JsonPath.<Boolean>read(body, "$.names")).isTrue();
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.grant.groups")).containsExactly(
                Map.of("group", readers, "write", false), Map.of("group", workers, "write", true));
        assertThat(JsonPath.<List<String>>read(body, "$.storage.prefixes[*].prefix")).containsExactly(allowed);
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.storage.prefixes[0].covered-by"))
                .isEqualTo(Map.of("kind", "s3", "endpoint", ENDPOINT, "prefix", "s3a://" + bucket));
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.store.prefixes[0]")).isEqualTo(Map.of("prefix",
                allowed, "listed", "ok", "deleted", 4, "delete-refused", 0, "delete-failed", 0));
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.store")).doesNotContainKey("code");
        assertThat(JsonPath.<List<String>>read(body, "$.policy.jobs")).containsExactly("snapshot-expiry",
                "compaction");

        // modify gives write too.
        String owners = "owners-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, owners, "modify");
        scan(at.plusSeconds(60), at.plusSeconds(70), identity(ENDPOINT, prefix(allowed, "ok", null, 0, 0, null)),
                owners);
        assertThat(states(readiness())).containsExactly("done", "done", "done", "done");
    }

    @Test
    void stepsTheCatalogsGrantsAndPolicyLeaveUndoneAreMissing() {
        String namespace = api.namespace();
        expect(api.manage("POST", "/grants", "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + namespace
                + "\"]},\"group\":\"" + workers + "\",\"privilege\":\"write\"}", AUTH, api.grantAdmin()), 204);
        String readers = "readers-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, readers, "select");
        policy("{\"snapshot-expiry.enabled\":\"false\",\"orphan-removal.enabled\":\"false\",\"compaction.enabled\":"
                + "\"false\",\"manifest-rewrite.enabled\":\"false\"}");
        scan(at, at.plusSeconds(20), identity(ENDPOINT, prefix(allowed, "ok", null, 0, 0, null)), workers, readers);

        String body = readiness();
        assertThat(states(body)).as("write on one namespace, or select on the catalog, is not write on the catalog")
                .containsExactly("missing", "done", "done", "missing");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.grant.groups[*].write")).containsExactly(false, false);
        assertThat(JsonPath.<List<String>>read(body, "$.policy.jobs")).isEmpty();
    }

    @Test
    void aPrefixNoReportedPrefixCoversIsMissingAndItsStoreUnknown() {
        IssuerForTests.grant(jdbc, Api.CATALOG, workers, "write");
        // Beneath the allowed prefix, a longer name beside it, and another bucket: none covers it.
        scan(at, at.plusSeconds(20), identity(ENDPOINT,
                prefix(allowed + "/deeper", "ok", null, 0, 0, null),
                prefix("s3://" + bucket + "/tab", "ok", null, 0, 0, null),
                prefix(allowed + "-archive", "ok", null, 0, 0, null),
                prefix("s3://other-" + bucket + "/tables", "ok", null, 0, 0, null)), workers);

        String body = readiness();
        assertThat(states(body).subList(0, 3)).containsExactly("done", "missing", "unknown");
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.storage.prefixes"))
                .containsExactly(Map.of("prefix", allowed));
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.store")).doesNotContainKey("code");
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.store.prefixes"))
                .containsExactly(Map.of("prefix", allowed));

        // Once a reported prefix is the allowed one, it covers it.
        scan(at.plusSeconds(60), at.plusSeconds(70), identity(ENDPOINT, prefix(allowed, "ok", null, 0, 0, null)),
                workers);
        assertThat(states(readiness()).subList(0, 3)).containsExactly("done", "done", "done");
    }

    @Test
    void theStoresRefusalIsRefusedWithItsCodeAndNoAnswerIsUnknown() {
        IssuerForTests.grant(jdbc, Api.CATALOG, workers, "write");

        scan(at, at.plusSeconds(10), identity(ENDPOINT, prefix(allowed, "refused", "AccessDenied", 0, 0, null)),
                workers);
        String body = readiness();
        assertThat(states(body).subList(0, 3)).containsExactly("done", "done", "refused");
        assertThat(JsonPath.<String>read(body, "$.store.code")).isEqualTo("AccessDenied");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.store.prefixes[0]")).isEqualTo(Map.of("prefix",
                allowed, "listed", "refused", "list-code", "AccessDenied", "deleted", 0, "delete-refused", 0,
                "delete-failed", 0));

        scan(at.plusSeconds(100), at.plusSeconds(110), identity(ENDPOINT, prefix(allowed, "ok", null, 1, 2,
                "AccessDenied")), workers);
        body = readiness();
        assertThat(JsonPath.<String>read(body, "$.store.state")).isEqualTo("refused");
        assertThat(JsonPath.<String>read(body, "$.store.code")).isEqualTo("AccessDenied");

        // A scan under way has reported no deletes yet: the refused ones of the last scan that ended still count.
        Instant running = at.plusSeconds(200);
        scan(running, null, identity(ENDPOINT, prefix(allowed, "ok", null, 0, 0, null)), workers);
        body = readiness();
        assertThat(JsonPath.<String>read(body, "$.reported-at")).isEqualTo(running.toString());
        assertThat(JsonPath.<String>read(body, "$.store.state")).isEqualTo("refused");
        assertThat(JsonPath.<Integer>read(body, "$.store.prefixes[0].delete-refused")).isEqualTo(2);

        scan(running, running.plusSeconds(10), identity(ENDPOINT, prefix(allowed, "ok", null, 3, 0, null)), workers);
        assertThat(JsonPath.<String>read(readiness(), "$.store.state")).isEqualTo("done");

        scan(at.plusSeconds(300), at.plusSeconds(310), identity(ENDPOINT, prefix(allowed, "failed",
                "SdkClientException", 0, 0, null)), workers);
        body = readiness();
        assertThat(JsonPath.<String>read(body, "$.store.state")).as("the store did not answer").isEqualTo("unknown");
        assertThat(JsonPath.<String>read(body, "$.store.code")).isEqualTo("SdkClientException");

        scan(at.plusSeconds(400), at.plusSeconds(410), identity(ENDPOINT, prefix(allowed, "refused", "NoSuchBucket",
                0, 0, null)), workers);
        body = readiness();
        assertThat(JsonPath.<String>read(body, "$.store.state")).isEqualTo("refused");
        assertThat(JsonPath.<String>read(body, "$.store.code")).isEqualTo("NoSuchBucket");
    }

    @Test
    void namesOnlyForThoseWhoMayModifyTheCatalogOrManageItsGrantsAndPlatformOperators() {
        IssuerForTests.grant(jdbc, Api.CATALOG, workers, "write");
        String describers = "describers-" + Api.suffix();
        IssuerForTests.grant(jdbc, Api.CATALOG, describers, "describe");
        policy("{\"compaction.enabled\":\"true\"}");
        scan(at, at.plusSeconds(10), identity(ENDPOINT, prefix(allowed, "refused", "AccessDenied", 0, 0, null)),
                workers);

        String named = readiness();
        for (String[] caller : List.of(new String[] {AUTH, api.grantAdmin()},
                new String[] {GovernanceServer.PRINCIPAL, "olga", GovernanceServer.GROUPS,
                        GovernanceServer.PLATFORM_OPS + "," + describers})) {
            assertThat(readiness(caller)).as("the grant admin, and an operator who may describe the catalog")
                    .isEqualTo(named);
        }
        assertThat(named).contains(workers, bucket, ENDPOINT);

        for (String[] caller : List.of(new String[] {GovernanceServer.PRINCIPAL, "dora", GovernanceServer.GROUPS,
                describers}, new String[] {AUTH, api.steward()})) {
            String body = readiness(caller);
            assertThat(JsonPath.<Boolean>read(body, "$.names")).isFalse();
            assertThat(states(body)).containsExactly("done", "done", "refused", "done");
            assertThat(JsonPath.<String>read(body, "$.store.code")).isEqualTo("AccessDenied");
            assertThat(JsonPath.<List<String>>read(body, "$.policy.jobs")).containsExactly("compaction");
            assertThat(JsonPath.<String>read(body, "$.reported-at")).isEqualTo(at.plusSeconds(10).toString());
            for (String step : List.of("grant", "storage", "store")) {
                String[] keys = step.equals("store") ? new String[] {"state", "code"} : new String[] {"state"};
                assertThat(JsonPath.<Map<String, Object>>read(body, "$." + step)).as(step).containsOnlyKeys(keys);
            }
            assertThat(body).doesNotContain(workers, bucket, "s3://", ENDPOINT, S3ForTests.PROFILE + "\"");
        }
    }

    @Test
    void aCallerWhoMayNotDescribeTheCatalogIsRefusedAndAudited() {
        String read = "readiness-" + UUID.randomUUID();
        expect(api.send("GET", READINESS, null, "X-Request-ID", read), 200);
        expectError(api.send("GET", READINESS, null, AUTH, api.outsider()), 404, "NoSuchWarehouseException");
        expectError(api.send("GET", READINESS, null, AUTH, api.operator()), 403, "ForbiddenException");
        String refused = "readiness-refused-" + UUID.randomUUID();
        expectError(api.send("GET", READINESS, null, AUTH, api.bystander(), "X-Request-ID", refused), 403,
                "ForbiddenException");

        var record = api.awaitRequest(refused);
        assertThat(record.data().type()).isEqualTo(EventType.DENIAL);
        Denial denial = (Denial) record.data();
        assertThat(denial.operation()).isEqualTo("loadMaintenanceReadiness");
        assertThat(denial.privilege()).isEqualTo("describe");
        assertThat(api.records()).as("a read, like the catalog's other governance reads, writes no record")
                .noneMatch(json -> json.contains(read));
    }

    // ---- helpers ----

    /** The readiness as the tester (modify on the catalog) reads it, or the caller {@code headers} name. */
    private String readiness(String... headers) {
        return expect(api.send("GET", READINESS, null, headers), 200).body();
    }

    /** The four steps' states, in order: grant, storage, store, policy. */
    private static List<String> states(String body) {
        return List.of("grant", "storage", "store", "policy").stream()
                .map(step -> JsonPath.<String>read(body, "$." + step + ".state")).toList();
    }

    private void policy(String keys) {
        expect(api.manage("PUT", "/maintenance-policy", keys), 200);
    }

    /** A scan's report by the worker in {@code groups} ({@link #workers} when none are named); ended when given. */
    private void scan(Instant started, Instant ended, String storage, String... groups) {
        String body = "{\"started-at\":\"" + started + "\"" + (ended == null ? ""
                : ",\"ended-at\":\"" + ended + "\",\"outcome\":\"succeeded\"") + ",\"failures\":{}"
                + (storage == null ? "" : ",\"storage\":[" + storage + "]") + "}";
        String id = UUID.nameUUIDFromBytes(started.toString().getBytes(StandardCharsets.UTF_8))
                .toString();
        expect(api.manage("PUT", "/maintenance/scans/" + id, body, GovernanceServer.PRINCIPAL, "maintenance",
                GovernanceServer.GROUPS, String.join(",", groups.length == 0 ? new String[] {workers} : groups)), 204);
    }

    private static String identity(String endpoint, String... prefixes) {
        return "{\"kind\":\"s3\",\"endpoint\":\"" + endpoint + "\",\"prefixes\":[" + String.join(",", prefixes) + "]}";
    }

    private static String prefix(String prefix, String listed, String listCode, long deleted, long deleteRefused,
                                 String deleteCode) {
        return "{\"prefix\":\"" + prefix + "\",\"listed\":\"" + listed + "\""
                + (listCode == null ? "" : ",\"list-code\":\"" + listCode + "\"") + ",\"deleted\":" + deleted
                + ",\"delete-refused\":" + deleteRefused + ",\"delete-failed\":0"
                + (deleteCode == null ? "" : ",\"delete-code\":\"" + deleteCode + "\"") + "}";
    }
}
