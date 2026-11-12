package keel.catalog.server.governance;

import static keel.catalog.server.governance.Api.expect;
import static keel.catalog.server.governance.Api.expectError;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.audit.EventType;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M7 gate: a Kafka outage blocks credential issuance and nothing else (D23). While the broker is
 * paused, a load that would hand out new credentials (M6's vending, on the run's S3 store) answers 503, and a trusted
 * engine's load that declines credentials, commits, creates, drops and governance changes all succeed;
 * their records wait in the outbox and reach the audit topic once the broker is back. A commit's or
 * create's own load response then carries no credentials (M6).
 */
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

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    @Test
    void anOutageBlocksCredentialIssuanceAndNothingElse() {
        String ns = api.namespace();
        api.table(ns, "t");
        api.table(ns, "doomed");
        String table = "/namespaces/" + ns + "/tables/t";
        String commitId = "outage-commit-" + UUID.randomUUID();
        String createId = "outage-create-" + UUID.randomUUID();
        String dropId = "outage-drop-" + UUID.randomUUID();
        String governanceId = "outage-governance-" + UUID.randomUUID();

        String reader = "reader-" + Api.suffix();

        broker.pause();
        try {
            expectError(api.rest("GET", table, null, GovernanceServer.PRINCIPAL, reader), 503,
                    "ServiceUnavailableException");
            expectError(api.rest("GET", table + "/credentials", null, GovernanceServer.PRINCIPAL, reader), 503,
                    "ServiceUnavailableException");

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
        } finally {
            broker.unpause();
        }

        assertThat(api.awaitRequest(commitId).data().type()).isEqualTo(EventType.COMMIT);
        assertThat(api.awaitRequest(createId).data().type()).isEqualTo(EventType.COMMIT);
        assertThat(api.awaitRequest(dropId).data().type()).isEqualTo(EventType.DROP);
        assertThat(api.awaitRequest(governanceId).data().type()).isEqualTo(EventType.GOVERNANCE_CHANGE);

        String issueId = "outage-after-" + UUID.randomUUID();
        HttpResponse<String> vended = loadWithCredentialsOnceBack(table, reader, issueId);
        assertThat(vended.body()).contains("s3.session-token");
        assertThat(api.awaitRecord(json -> json.contains(issueId) && json.contains("credential-issue"))
                .data().type()).isEqualTo(EventType.CREDENTIAL_ISSUE);
    }

    /** The first load with credentials to succeed once the producer has reconnected. */
    private HttpResponse<String> loadWithCredentialsOnceBack(String table, String reader, String requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            HttpResponse<String> response = api.rest("GET", table, null, GovernanceServer.PRINCIPAL, reader,
                    "X-Request-ID", requestId);
            if (response.statusCode() == 200 || System.nanoTime() > deadline) {
                return expect(response, 200);
            }
            expect(response, 503);
        }
    }
}
