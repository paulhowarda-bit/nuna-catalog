package nuna.audittables;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit table writer's OTLP/HTTP logs receiver (D23, amended 2026-11-23): {@code POST /v1/logs}, the body an
 * {@code ExportLogsServiceRequest} as protobuf ({@code application/x-protobuf}) or as OTLP's JSON
 * ({@code application/json}), gzip-compressed or not. It keeps only the log records of the catalog's audit logger,
 * {@value #AUDIT_LOGGER}, whose name OpenTelemetry's logback appender puts in the record's instrumentation scope; each
 * one's body is a CloudEvent, which goes to the {@link LogWriter}. Every other record is ignored, so the catalog's
 * whole log may be sent here.
 *
 * <p>A request must carry the shared token in {@value #TOKEN_HEADER}, or it is refused with 401 and nothing in it is
 * read: the table is the console's Audit page, and nothing but the catalog may add to it. Answers follow OTLP/HTTP: 200
 * when the records are taken, with a partial success naming how many were rejected (a body that is no CloudEvent, or of
 * a type this writer cannot map), 400 for a body that is no OTLP request, 413 past {@value #MAX_BODY} bytes, 415 for
 * another content type, and 503 while the writer is too far behind, which the exporter retries.
 */
final class OtlpLogsReceiver implements AutoCloseable {

    /** The catalog's audit logger ({@code AuditTrail.LOGGER} in catalog-server). */
    static final String AUDIT_LOGGER = "nuna.audit";
    /** The header that carries the shared token. */
    static final String TOKEN_HEADER = "X-Nuna-Audit-Token";
    /** The largest request body taken, compressed or not. */
    static final int MAX_BODY = 16 * 1024 * 1024;
    static final String PATH = "/v1/logs";

    private static final Logger LOG = LoggerFactory.getLogger(OtlpLogsReceiver.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROTOBUF = "application/x-protobuf";
    private static final String JSON_TYPE = "application/json";

    private final HttpServer server;
    private final ExecutorService threads;
    private final byte[] token;
    private final LogWriter writer;

    private OtlpLogsReceiver(HttpServer server, ExecutorService threads, String token, LogWriter writer) {
        this.server = server;
        this.threads = threads;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.writer = writer;
    }

    /** Listens on {@code port} of every address; 0 picks a free one. */
    static OtlpLogsReceiver start(int port, String token, LogWriter writer) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            ExecutorService threads = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("otlp-logs-", 0)
                    .daemon(true).factory());
            OtlpLogsReceiver receiver = new OtlpLogsReceiver(server, threads, token, writer);
            server.createContext(PATH, receiver::handle);
            server.setExecutor(threads);
            server.start();
            return receiver;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The port it listens on. */
    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(PATH)) {
                answer(exchange, 404, null);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                answer(exchange, 405, null);
                return;
            }
            String presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
                LOG.warn("Refused an OTLP logs request from {} without the audit token", exchange.getRemoteAddress());
                answer(exchange, 401, null);
                return;
            }
            String type = mediaType(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (!type.equals(PROTOBUF) && !type.equals(JSON_TYPE)) {
                answer(exchange, 415, null);
                return;
            }
            byte[] body = body(exchange);
            if (body == null) {
                answer(exchange, 413, null);
                return;
            }
            List<String> records;
            try {
                records = type.equals(PROTOBUF) ? fromProtobuf(body) : fromJson(body);
            } catch (InvalidProtocolBufferException | JacksonException | IllegalArgumentException e) {
                answer(exchange, 400, null);
                return;
            }
            List<CatalogEvent> events = new ArrayList<>();
            int rejected = 0;
            for (String record : records) {
                try {
                    events.add(EventCodec.decode(record.getBytes(StandardCharsets.UTF_8)));
                } catch (IllegalArgumentException e) {
                    LOG.error("A log record of {} is not a catalog event; it is not written to the audit table: {}",
                            AUDIT_LOGGER, e.getMessage());
                    rejected++;
                }
            }
            LogWriter.Taken taken;
            try {
                taken = writer.offer(events);
            } catch (LogWriter.Busy e) {
                exchange.getResponseHeaders().set("Retry-After", "10");
                answer(exchange, 503, null);
                return;
            }
            rejected += taken.rejected();
            answer(exchange, 200, type.equals(PROTOBUF) ? protobufAnswer(rejected) : jsonAnswer(rejected));
        } catch (RuntimeException e) {
            LOG.error("The OTLP logs receiver failed on a request", e);
            exchange.sendResponseHeaders(500, -1);
        }
    }

    /** The bodies of the audit logger's records, in order. */
    static List<String> fromProtobuf(byte[] body) throws InvalidProtocolBufferException {
        List<String> records = new ArrayList<>();
        ExportLogsServiceRequest.parseFrom(body).getResourceLogsList().forEach(resource ->
                resource.getScopeLogsList().stream()
                        .filter(scope -> scope.getScope().getName().equals(AUDIT_LOGGER))
                        .forEach(scope -> scope.getLogRecordsList().forEach(log -> {
                            if (log.getBody().getValueCase() == AnyValue.ValueCase.STRING_VALUE) {
                                records.add(log.getBody().getStringValue());
                            }
                        })));
        return records;
    }

    /** {@link #fromProtobuf} for OTLP's JSON encoding, whose field names are lower camel case. */
    static List<String> fromJson(byte[] body) {
        JsonNode request = JSON.readTree(body);
        if (!request.isObject()) {
            throw new IllegalArgumentException("not an OTLP logs request");
        }
        List<String> records = new ArrayList<>();
        for (JsonNode resource : request.path("resourceLogs")) {
            for (JsonNode scope : resource.path("scopeLogs")) {
                if (!scope.path("scope").path("name").asString("").equals(AUDIT_LOGGER)) {
                    continue;
                }
                for (JsonNode log : scope.path("logRecords")) {
                    JsonNode text = log.path("body").path("stringValue");
                    if (text.isString()) {
                        records.add(text.asString());
                    }
                }
            }
        }
        return records;
    }

    private static byte[] protobufAnswer(int rejected) {
        ExportLogsServiceResponse.Builder response = ExportLogsServiceResponse.newBuilder();
        if (rejected > 0) {
            response.setPartialSuccess(ExportLogsPartialSuccess.newBuilder().setRejectedLogRecords(rejected)
                    .setErrorMessage(rejected + " audit records are not catalog events this writer can map"));
        }
        return response.build().toByteArray();
    }

    private static byte[] jsonAnswer(int rejected) {
        if (rejected == 0) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
        return ("{\"partialSuccess\":{\"rejectedLogRecords\":\"" + rejected + "\",\"errorMessage\":\"" + rejected
                + " audit records are not catalog events this writer can map\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /** The request body, gunzipped if it says so; null past {@link #MAX_BODY} bytes either way. */
    private static byte[] body(HttpExchange exchange) throws IOException {
        byte[] raw = read(exchange.getRequestBody());
        if (raw == null) {
            return null;
        }
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && encoding.trim().equalsIgnoreCase("gzip")) {
            try (InputStream unzipped = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                return read(unzipped);
            }
        }
        return raw;
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) >= 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BODY) {
                return null;
            }
        }
        return out.toByteArray();
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type"));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Stops taking requests, letting those under way finish for up to five seconds. */
    @Override
    public void close() {
        server.stop(5);
        threads.shutdown();
    }
}
