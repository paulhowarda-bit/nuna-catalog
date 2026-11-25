package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import nuna.catalog.audit.archive.ArchiveSink;
import java.util.zip.GZIPOutputStream;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The writer's OTLP/HTTP logs receiver (D23, amended 2026-11-23): it takes OTLP log requests as protobuf and as JSON,
 * gzipped or not, keeps only the records of the catalog's audit logger, refuses a request without the audit token and
 * reads nothing of it, and names the records it rejected in a partial success. With an archive (D23, amended
 * 2026-11-25), every audit record goes to it first, as it came, and an archive that cannot take them turns the whole
 * request away.
 */
class OtlpLogsReceiverTest {

    private static final String TOKEN = "t0ken-for-the-audit-tables-writer-tests";
    private static final Namespace NAMESPACE = Namespace.of("audit");
    private static final URI SOURCE = URI.create("urn:nuna:deployment:test");
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "lake",
            List.of("sales", "orders"));
    private static final PointerState V1 = new PointerState("s3://lake/orders/metadata/00001.json", 1, "a".repeat(64));

    private final FaultyCatalog catalog = new FaultyCatalog();
    private final MovableClock clock = new MovableClock();
    private final LogWriter writer = new LogWriter(() -> EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE),
            "nuna-audit-tables", Duration.ofMinutes(1), 1_000, Duration.ofMinutes(15), clock);
    private OtlpLogsReceiver receiver = OtlpLogsReceiver.start(0, TOKEN, writer);
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        receiver.close();
    }

    private CatalogEvent read() {
        return CatalogEvent.create(SOURCE, clock.instant(), new ObjectRead(ORDERS, "main", V1,
                new Actor("reader", null, null, "req-" + UUID.randomUUID())));
    }

    private static ScopeLogs scope(String logger, String... bodies) {
        ScopeLogs.Builder scope = ScopeLogs.newBuilder().setScope(InstrumentationScope.newBuilder().setName(logger));
        for (String body : bodies) {
            scope.addLogRecords(LogRecord.newBuilder().setBody(AnyValue.newBuilder().setStringValue(body)));
        }
        return scope.build();
    }

    private static String json(CatalogEvent event) {
        return new String(EventCodec.encode(event), StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> post(String contentType, byte[] body, String token, boolean gzip) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port()
                        + "/v1/logs")).header("Content-Type", contentType);
        if (token != null) {
            request.header(OtlpLogsReceiver.TOKEN_HEADER, token);
        }
        if (gzip) {
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(zipped)) {
                out.write(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            body = zipped.toByteArray();
            request.header("Content-Encoding", "gzip");
        }
        try {
            return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private List<String> written() {
        writer.commit();
        List<String> ids = new ArrayList<>();
        try (CloseableIterable<Record> rows = IcebergGenerics.read(catalog.loadTable(
                TableIdentifier.of(NAMESPACE, EventsTable.NAME))).build()) {
            rows.forEach(row -> ids.add((String) row.getField("event_id")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }

    @Test
    void keepsOnlyTheAuditLoggersRecordsFromAProtobufRequest() throws Exception {
        CatalogEvent first = read();
        CatalogEvent second = read();
        CatalogEvent elsewhere = read();
        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
                .addScopeLogs(scope("nuna.catalog.server.governance.AuditTrail", json(elsewhere)))
                .addScopeLogs(scope(OtlpLogsReceiver.AUDIT_LOGGER, json(first), json(second)))).build();

        HttpResponse<byte[]> response = post("application/x-protobuf", request.toByteArray(), TOKEN, true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ExportLogsServiceResponse.parseFrom(response.body()).hasPartialSuccess()).isFalse();
        assertThat(written()).containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
    }

    @Test
    void takesOtlpsJsonEncodingAndNamesWhatItRejected() {
        CatalogEvent event = read();
        String body = """
                {"resourceLogs":[{"resource":{},"scopeLogs":[
                  {"scope":{"name":"nuna.audit"},"logRecords":[
                    {"timeUnixNano":"1","body":{"stringValue":%s}},
                    {"body":{"stringValue":"not a catalog event"}}]},
                  {"scope":{"name":"org.springframework.boot"},"logRecords":[{"body":{"stringValue":"Started"}}]}]}]}
                """.formatted(JsonMapper.builder().build().writeValueAsString(json(event)));

        HttpResponse<byte[]> response = post("application/json", body.getBytes(StandardCharsets.UTF_8), TOKEN, false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("\"rejectedLogRecords\":\"1\"");
        assertThat(written()).containsExactly(event.id().toString());
    }

    @Test
    void refusesARequestWithoutTheTokenAndTakesNothingFromIt() {
        byte[] request = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
                .addScopeLogs(scope(OtlpLogsReceiver.AUDIT_LOGGER, json(read())))).build().toByteArray();

        assertThat(post("application/x-protobuf", request, null, false).statusCode()).isEqualTo(401);
        assertThat(post("application/x-protobuf", request, TOKEN + "x", false).statusCode()).isEqualTo(401);
        assertThat(writer.waiting()).isZero();
    }

    /** The receiver again, handing every audit record to {@code archive} first. */
    private void archivingTo(Function<List<byte[]>, ArchiveSink.Taken> archive) {
        receiver.close();
        receiver = OtlpLogsReceiver.start(0, TOKEN, writer, Optional.of(archive));
    }

    @Test
    void archivesEveryAuditRecordAsItCameEvenThoseTheTableLeavesOut() {
        List<String> archived = new ArrayList<>();
        archivingTo(bodies -> {
            bodies.forEach(b -> archived.add(new String(b, StandardCharsets.UTF_8)));
            return new ArchiveSink.Taken(bodies.size(), 0, 0);
        });
        CatalogEvent kept = read();
        CatalogEvent own = CatalogEvent.create(SOURCE, clock.instant(), new ObjectRead(ORDERS, "main", V1,
                new Actor("nuna-audit-tables", null, null, "req-own")));
        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
                .addScopeLogs(scope("org.springframework.boot", "Started"))
                .addScopeLogs(scope(OtlpLogsReceiver.AUDIT_LOGGER, json(own), "not a catalog event", json(kept))))
                .build();

        HttpResponse<byte[]> response = post("application/x-protobuf", request.toByteArray(), TOKEN, false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(archived).containsExactly(json(own), "not a catalog event", json(kept));
        assertThat(written()).containsExactly(kept.id().toString());
    }

    @Test
    void anArchiveThatCannotTakeTheRecordsTurnsTheRequestAwayAndTheTableTakesNoneOfThem() {
        archivingTo(bodies -> {
            throw new IllegalStateException("the bucket cannot be reached");
        });
        byte[] request = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
                .addScopeLogs(scope(OtlpLogsReceiver.AUDIT_LOGGER, json(read())))).build().toByteArray();

        HttpResponse<byte[]> response = post("application/x-protobuf", request, TOKEN, false);

        assertThat(response.statusCode()).as("which the exporter retries").isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(writer.waiting()).isZero();
    }

    @Test
    void refusesWhatIsNoOtlpLogsRequest() {
        assertThat(post("text/plain", "hello".getBytes(StandardCharsets.UTF_8), TOKEN, false).statusCode())
                .isEqualTo(415);
        assertThat(post("application/x-protobuf", new byte[] {(byte) 0xff, 0x01}, TOKEN, false).statusCode())
                .isEqualTo(400);
        assertThat(post("application/json", "[1]".getBytes(StandardCharsets.UTF_8), TOKEN, false).statusCode())
                .isEqualTo(400);
    }
}
