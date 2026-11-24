package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.EventType;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Kafka outage blocks nothing (D23, amended 2026-11-23: "Log as the main audit path"). While the broker is paused,
 * a load that hands out new credentials (M6's vending, on the run's S3 store), the credentials route, a trusted engine's
 * load that declines credentials, commits, creates, drops and governance changes all succeed, and every one's audit
 * record is in the log {@code nuna.audit}: the synchronous ones before the response, the changes once the relay has
 * drained them. Once the broker is back, records reach the audit topic again.
 */
@Tag("heavy")
@GovernanceServer
class KafkaOutageTest {

    private static final String DELEGATION = "X-Iceberg-Access-Delegation";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

    @Value("${local.server.port}")
    private int port;

    private Api api;
    private AuditLogCapture log;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
        log = new AuditLogCapture();
    }

    @AfterEach
    void disconnect() {
        log.close();
        api.close();
    }

    @Test
    void anOutageBlocksNothingAndEveryRecordIsInTheLog() {
        String ns = api.namespace();
        api.table(ns, "t");
        api.table(ns, "doomed");
        String table = "/namespaces/" + ns + "/tables/t";
        String loadId = "outage-load-" + UUID.randomUUID();
        String credentialsId = "outage-credentials-" + UUID.randomUUID();
        String commitId = "outage-commit-" + UUID.randomUUID();
        String createId = "outage-create-" + UUID.randomUUID();
        String dropId = "outage-drop-" + UUID.randomUUID();
        String governanceId = "outage-governance-" + UUID.randomUUID();

        String reader = "reader-" + Api.suffix();

        broker.pause();
        try {
            HttpResponse<String> vended = expect(api.rest("GET", table, null, GovernanceServer.PRINCIPAL, reader,
                    "X-Request-ID", loadId), 200);
            assertThat(vended.body()).contains("s3.session-token");
            assertThat(log.await(loadId)).contains("credential-issue");
            expect(api.rest("GET", table + "/credentials", null, GovernanceServer.PRINCIPAL, reader,
                    "X-Request-ID", credentialsId), 200);
            assertThat(log.await(credentialsId)).contains("credential-issue");

            HttpResponse<String> load = expect(api.rest("GET", table, null, GovernanceServer.ENGINE, "true",
                    DELEGATION, "none"), 200);
            assertThat(load.body()).doesNotContain("s3.session-token");
            expect(api.rest("POST", table, Api.SET_PROPERTY.formatted("during"), "X-Request-ID", commitId), 200);
            expect(api.rest("POST", "/namespaces/" + ns + "/tables", Api.TABLE.formatted("new"),
                    "X-Request-ID", createId), 200);
            expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/doomed", null, "X-Request-ID", dropId), 204);
            expect(api.manage("PUT", "/namespaces/" + ns + "/attributes/classification",
                    "{\"value\":\"internal\",\"provenance\":\"collibra\"}", "X-Request-ID", governanceId,
                    "Authorization", api.steward()), 200);

            // A commit's or create's answer vends credentials too, so its request id is on a credential issue's
            // line as well: wait for the line of the type expected.
            log.await(commitId, "\"type\":\"" + EventType.COMMIT.typeName() + "\"");
            log.await(createId, "\"type\":\"" + EventType.COMMIT.typeName() + "\"");
            log.await(dropId, "\"type\":\"" + EventType.DROP.typeName() + "\"");
            log.await(governanceId, "\"type\":\"" + EventType.GOVERNANCE_CHANGE.typeName() + "\"");
        } finally {
            broker.unpause();
        }

        String issueId = "outage-after-" + UUID.randomUUID();
        assertThat(credentialIssueInTheTopicOnceBack(table, reader, issueId).data().type())
                .isEqualTo(EventType.CREDENTIAL_ISSUE);
    }

    /**
     * Loads with credentials, each with a request id of its own, until one's record reaches the audit topic: the trail
     * skips Kafka for the probe interval after a failure, and the producer reconnects.
     */
    private nuna.catalog.audit.CatalogEvent credentialIssueInTheTopicOnceBack(String table, String reader,
                                                                               String requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        for (int attempt = 0; ; attempt++) {
            String id = requestId + "-" + attempt;
            expect(api.rest("GET", table, null, GovernanceServer.PRINCIPAL, reader, "X-Request-ID", id), 200);
            for (String record : api.records()) {
                if (record.contains(id) && record.contains("credential-issue")) {
                    return api.awaitRequest(id);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no credential issue reached the audit topic within 60 s of the broker's return");
            }
        }
    }
}
