package nuna.catalog.server.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A small OTLP/HTTP receiver for tests, what a collector would be: it takes traces, metrics and logs as protobuf on
 * {@code /v1/traces}, {@code /v1/metrics} and {@code /v1/logs} and keeps what arrives, with the resource each came
 * from. The worker's tests use it too.
 */
public final class OtlpReceiver implements AutoCloseable {

    /** One span, metric or log record, and the service it came from. */
    public record Received<T>(Resource resource, T item) {

        public String service() {
            return attribute(resource.getAttributesList(), "service.name").orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Received<Span>> spans = new CopyOnWriteArrayList<>();
    private final List<Received<Metric>> metrics = new CopyOnWriteArrayList<>();
    private final List<Received<LogRecord>> logs = new CopyOnWriteArrayList<>();

    private OtlpReceiver(HttpServer server) {
        this.server = server;
    }

    public static OtlpReceiver start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            OtlpReceiver receiver = new OtlpReceiver(server);
            server.createContext("/v1/traces", exchange -> receiver.take(exchange, body ->
                    ExportTraceServiceRequest.parseFrom(body).getResourceSpansList().forEach(each ->
                            each.getScopeSpansList().forEach(scope -> scope.getSpansList().forEach(span ->
                                    receiver.spans.add(new Received<>(each.getResource(), span)))))));
            server.createContext("/v1/metrics", exchange -> receiver.take(exchange, body ->
                    ExportMetricsServiceRequest.parseFrom(body).getResourceMetricsList().forEach(each ->
                            each.getScopeMetricsList().forEach(scope -> scope.getMetricsList().forEach(metric ->
                                    receiver.metrics.add(new Received<>(each.getResource(), metric)))))));
            server.createContext("/v1/logs", exchange -> receiver.take(exchange, body ->
                    ExportLogsServiceRequest.parseFrom(body).getResourceLogsList().forEach(each ->
                            each.getScopeLogsList().forEach(scope -> scope.getLogRecordsList().forEach(log ->
                                    receiver.logs.add(new Received<>(each.getResource(), log)))))));
            server.start();
            return receiver;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code http://127.0.0.1:<port>}, what OTEL_EXPORTER_OTLP_ENDPOINT would name. */
    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** {@code <base>/v1/<signal>}: traces, metrics or logs. */
    public String url(String signal) {
        return base() + "/v1/" + signal;
    }

    public List<Received<Span>> spans() {
        return List.copyOf(spans);
    }

    public List<Received<Metric>> metrics() {
        return List.copyOf(metrics);
    }

    public List<Received<LogRecord>> logs() {
        return List.copyOf(logs);
    }

    /** The first span matching, waiting up to {@code timeout} for it to arrive. */
    public Received<Span> awaitSpan(Predicate<Received<Span>> match, Duration timeout) {
        return await(() -> spans.stream().filter(match).findFirst(), timeout, "span");
    }

    public Received<Metric> awaitMetric(Predicate<Received<Metric>> match, Duration timeout) {
        return await(() -> metrics.stream().filter(match).findFirst(), timeout, "metric");
    }

    public Received<LogRecord> awaitLog(Predicate<Received<LogRecord>> match, Duration timeout) {
        return await(() -> logs.stream().filter(match).findFirst(), timeout, "log record");
    }

    /** Every attribute value a span or a metric's data points carry, as text. */
    public static Stream<String> attributeValues(Span span) {
        return values(span.getAttributesList());
    }

    public static Stream<String> attributeValues(Metric metric) {
        return Stream.of(metric.getSum().getDataPointsList().stream().flatMap(p -> values(p.getAttributesList())),
                metric.getGauge().getDataPointsList().stream().flatMap(p -> values(p.getAttributesList())),
                metric.getHistogram().getDataPointsList().stream().flatMap(p -> values(p.getAttributesList())),
                metric.getSummary().getDataPointsList().stream().flatMap(p -> values(p.getAttributesList())))
                .flatMap(s -> s);
    }

    /** The data points' attribute values under {@code key}, e.g. a timer's {@code uri}. */
    public static List<String> attributeValues(Metric metric, String key) {
        return Stream.of(metric.getSum().getDataPointsList().stream().map(p -> attribute(p.getAttributesList(), key)),
                        metric.getGauge().getDataPointsList().stream().map(p -> attribute(p.getAttributesList(), key)),
                        metric.getHistogram().getDataPointsList().stream()
                                .map(p -> attribute(p.getAttributesList(), key)),
                        metric.getSummary().getDataPointsList().stream()
                                .map(p -> attribute(p.getAttributesList(), key)))
                .flatMap(s -> s).flatMap(Optional::stream).toList();
    }

    public static Optional<String> attribute(List<KeyValue> attributes, String key) {
        return attributes.stream().filter(a -> a.getKey().equals(key)).findFirst().map(a -> text(a.getValue()));
    }

    public static String hex(com.google.protobuf.ByteString id) {
        return HexFormat.of().formatHex(id.toByteArray());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Stream<String> values(List<KeyValue> attributes) {
        return attributes.stream().map(a -> text(a.getValue()));
    }

    private static String text(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> Boolean.toString(value.getBoolValue());
            case INT_VALUE -> Long.toString(value.getIntValue());
            case DOUBLE_VALUE -> Double.toString(value.getDoubleValue());
            default -> value.toString();
        };
    }

    private interface Body {
        void read(byte[] body) throws IOException;
    }

    private void take(HttpExchange exchange, Body handler) throws IOException {
        try (exchange; InputStream in = exchange.getRequestBody()) {
            handler.read(in.readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            // An empty Export*ServiceResponse: no partial success to report.
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static <T> T await(java.util.function.Supplier<Optional<T>> find, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> found = find.get();
            if (found.isPresent()) {
                return found.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no matching " + what + " arrived within " + timeout);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
