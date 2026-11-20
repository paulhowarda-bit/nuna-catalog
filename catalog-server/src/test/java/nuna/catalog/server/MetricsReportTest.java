package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * What a metrics report (D13) leaves the catalog as, read where it leaves: the {@code nuna.telemetry.metrics} logger.
 * A scan filter's literals never reach it, whatever the client sent, and a report of a type the server does not know
 * reaches it as its type, table name and size, never its body.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
class MetricsReportTest {

    private static final String TABLE = """
            {"name":"staff","schema":{"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"},
              {"id":2,"name":"ssn","required":false,"type":"string"}]}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> forwarded = new ListAppender<>();
    private final Logger telemetry = (Logger) LoggerFactory.getLogger("nuna.telemetry.metrics");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private String namespace;

    @BeforeEach
    void tableAndListener() throws Exception {
        IssuerForTests.grant(jdbc, "trading", IssuerForTests.GROUP, "create", "modify");
        namespace = "metrics_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(send("/namespaces", "{\"namespace\":[\"" + namespace + "\"]}").statusCode()).isEqualTo(200);
        assertThat(send("/namespaces/" + namespace + "/tables", TABLE).statusCode()).isEqualTo(200);
        forwarded.start();
        telemetry.addAppender(forwarded);
    }

    @AfterEach
    void stopListening() {
        telemetry.detachAppender(forwarded);
    }

    @Test
    void aScanFilterLeavesWithoutItsLiterals() throws Exception {
        HttpResponse<String> answer = report("""
                {"report-type":"scan-report","table-name":"trading.%s.staff","snapshot-id":7,
                 "filter":{"type":"eq","term":"ssn","value":"123-45-6789"},
                 "schema-id":0,"projected-field-ids":[1,2],"projected-field-names":["id","ssn"],"metrics":{}}"""
                .formatted(namespace));

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(lines()).singleElement().asString()
                .startsWith("scan-report catalog=")
                .contains(" namespace=" + namespace + " table=staff snapshot=7 report={")
                .contains("\"term\":\"ssn\"")
                .doesNotContain("123-45-6789");
    }

    @Test
    void anUnknownTypeLeavesAsItsNameTableAndSizeOnly() throws Exception {
        String body = """
                {"report-type":"plan-report","table-name":"t","metrics":{},
                 "filter":{"type":"eq","term":"ssn","value":"123-45-6789"}}""";

        HttpResponse<String> answer = report(body);

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(lines()).singleElement().asString()
                .startsWith("unknown-report catalog=")
                .endsWith(" namespace=%s table=staff bytes=%d report=%s".formatted(namespace,
                        body.getBytes(StandardCharsets.UTF_8).length,
                        "{\"report-type\":\"plan-report\",\"table-name\":\"t\"}"));
    }

    @Test
    void anUnknownTypeCannotStartALineOfItsOwn() throws Exception {
        HttpResponse<String> answer = report("""
                {"report-type":"plan-report\\nscan-report catalog=forged","metrics":{}}""");

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(lines()).singleElement().asString()
                .startsWith("unknown-report catalog=")
                .doesNotContain("\n")
                .endsWith("report={\"report-type\":\"plan-report\\nscan-report catalog=forged\"}");
    }

    @Test
    void aBadBodyIsRefusedAndNothingLeaves() throws Exception {
        HttpResponse<String> answer = report("{\"report-type\":\"scan-report\",\"table-name\":\"t\"}");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.body()).contains("\"type\":\"BadRequestException\"");
        assertThat(lines()).isEmpty();
    }

    private HttpResponse<String> report(String body) throws Exception {
        return send("/namespaces/" + namespace + "/tables/staff/metrics", body);
    }

    /** What this test's namespace forwarded; another test class may report at the same time. */
    private List<String> lines() {
        return forwarded.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains(" namespace=" + namespace + " "))
                .toList();
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/trading" + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", IssuerForTests.bearer())
                .header("Content-Type", "application/json")
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
