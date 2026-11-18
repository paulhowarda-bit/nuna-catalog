package nuna.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import nuna.catalog.iceberg.RestRequest.InvalidRequest;
import nuna.catalog.iceberg.RestRequest.MetricsSummary;
import org.junit.jupiter.api.Test;

/** What a metrics report (D13) keeps when it leaves the catalog: no filter literal, and no unknown type's body. */
class MetricsReportRequestTest {

    /** A scan report as a client that does not sanitize would send it: its filter holds the values themselves. */
    static final String UNSANITIZED_SCAN = """
            {"report-type":"scan-report","table-name":"cat.hr.staff","snapshot-id":7,
             "filter":{"type":"and",
               "left":{"type":"eq","term":"ssn","value":"123-45-6789"},
               "right":{"type":"in","term":"salary","values":[85125,92250]}},
             "schema-id":0,"projected-field-ids":[1,2],"projected-field-names":["id","ssn"],"metrics":{}}""";

    @Test
    void aScanFilterIsSanitizedWhateverTheClientSent() {
        MetricsSummary summary = RestRequest.reportMetrics(UNSANITIZED_SCAN);

        assertThat(summary.reportType()).isEqualTo(MetricsSummary.SCAN);
        assertThat(summary.known()).isTrue();
        assertThat(summary.tableName()).isEqualTo("cat.hr.staff");
        assertThat(summary.snapshotId()).isEqualTo(7);
        assertThat(summary.json())
                .as("no literal from the client's filter is forwarded")
                .doesNotContain("123-45-6789", "85125", "92250")
                .contains("\"term\":\"ssn\"", "\"term\":\"salary\"", "\"projected-field-names\":[\"id\",\"ssn\"]");
    }

    @Test
    void aFilterTheClientSanitizedIsSanitizedAgain() {
        // As the stock Java client sends it. The server cannot tell its placeholders from values, so it hashes them
        // too: no string the client chose is forwarded, at the cost of the client's "(5-digit-int)" description.
        String sanitized = UNSANITIZED_SCAN.replace("123-45-6789", "(hash-3f2a1b4c)")
                .replace("[85125,92250]", "[\"(5-digit-int)\"]");

        assertThat(RestRequest.reportMetrics(sanitized).json())
                .doesNotContain("(hash-3f2a1b4c)", "(5-digit-int)")
                .containsPattern("\"value\":\"\\(hash-[0-9a-f]{8}\\)\"");
    }

    @Test
    void aCommitReportIsForwardedWhole() {
        MetricsSummary summary = RestRequest.reportMetrics("""
                {"report-type":"commit-report","table-name":"cat.hr.staff","snapshot-id":9,"sequence-number":3,
                 "operation":"append","metrics":{},"metadata":{"engine-name":"spark"}}""");

        assertThat(summary.reportType()).isEqualTo(MetricsSummary.COMMIT);
        assertThat(summary.known()).isTrue();
        assertThat(summary.snapshotId()).isEqualTo(9);
        assertThat(summary.json()).contains("\"operation\":\"append\"", "\"engine-name\":\"spark\"");
    }

    @Test
    void anUnknownTypeKeepsItsNameTableAndSizeButNotItsBody() {
        String body = """
                {"report-type":"plan-report","table-name":"t","metrics":{},
                 "filter":{"type":"eq","term":"ssn","value":"123-45-6789"}}""";

        MetricsSummary summary = RestRequest.reportMetrics(body);

        assertThat(summary.reportType()).isEqualTo("plan-report");
        assertThat(summary.known()).isFalse();
        assertThat(summary.tableName()).isEqualTo("t");
        assertThat(summary.snapshotId()).isEqualTo(-1);
        assertThat(summary.bytes()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(summary.json()).isEqualTo("{\"report-type\":\"plan-report\",\"table-name\":\"t\"}");
    }

    @Test
    void anUnknownTypeWithoutATableKeepsItsName() {
        String body = "{\"report-type\":\"plan-report\",\"metrics\":{\"élapsed\":1}}";

        MetricsSummary summary = RestRequest.reportMetrics(body);

        assertThat(summary.tableName()).isEmpty();
        assertThat(summary.bytes()).as("UTF-8 bytes, not characters").isEqualTo(body.length() + 1);
        assertThat(summary.json()).isEqualTo("{\"report-type\":\"plan-report\"}");
    }

    @Test
    void anUnknownTypeNameIsEscapedNotCopied() {
        MetricsSummary summary = RestRequest.reportMetrics(
                "{\"report-type\":\"plan\\nreport \\\"x\\\"\",\"table-name\":\"t\\r\\nscan-report forged=1\"}");

        assertThat(summary.reportType()).isEqualTo("plan\nreport \"x\"");
        assertThat(summary.json()).doesNotContain("\n", "\r")
                .isEqualTo("{\"report-type\":\"plan\\nreport \\\"x\\\"\",\"table-name\":\"t\\r\\nscan-report forged=1\"}");
    }

    @Test
    void aBadBodyIsRefused() {
        for (String body : new String[] {"", "[]", "{\"report-type\":7}", "{\"report-type\":\"scan-report\"}",
                "{\"report-type\":\"plan-report\",\"table-name\":5}"}) {
            assertThatThrownBy(() -> RestRequest.reportMetrics(body)).as(body)
                    .isInstanceOfSatisfying(InvalidRequest.class, e -> assertThat(e.status()).isEqualTo(400));
        }
    }
}
