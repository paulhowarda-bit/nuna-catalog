package nuna.catalog.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import nuna.catalog.core.CatalogError;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.store.StoreUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Every 5xx answer ends its server span with status ERROR, whether an exception handler built it, a route or a filter
 * wrote it without a throw, or a failure escaped to the container; the span names the failure's class or the body's
 * error type and never its message (backlog 265, docs/observability/spans.md). A 4xx or a 2xx leaves the status unset,
 * so a tail sampler keeping traces with status ERROR keeps the failed calls and no others. Spring's server observation
 * filter, {@link RequestMetrics} and the server's own exception handlers, wired in the server's order, on Micrometer's
 * OpenTelemetry bridge as Boot wires it.
 */
class ServerSpanStatusTest {

    /** A message that names a table, a location and a person, as a failure's message can. */
    private static final String SECRET = "s3://warehouse/trading/positions/metadata/00001.metadata.json for pia_7f3a";
    private static final String TABLE = "/v1/trading/namespaces/risk/tables/positions";
    private static final String FAULT = "X-Fault";

    private final Recorder exported = new Recorder();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exported)).build();
    private final ObservationRegistry observations = ObservationRegistry.create();
    /** Boot's {@code http.server.requests}, which the server observation makes. */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MockMvc mvc;

    ServerSpanStatusTest() throws ReflectiveOperationException {
        io.opentelemetry.api.trace.Tracer otel = tracerProvider.get("server-span-status-test");
        OtelTracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(new FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer,
                        new OtelPropagator(ContextPropagators.noop(), otel)),
                new DefaultTracingObservationHandler(tracer)));
        mvc = MockMvcBuilders.standaloneSetup(new Routes())
                .setControllerAdvice(advice("nuna.catalog.server.ErrorResponses"))
                // The server's order: the observation filter starts the trace, then the request timer, then the rest.
                .addFilters(new ServerHttpObservationFilter(observations),
                        new RequestMetrics(new SimpleMeterRegistry(), observations), new Faults())
                .build();
    }

    @AfterEach
    void close() {
        tracerProvider.close();
    }

    @ParameterizedTest(name = "{0}: {1}, the span names {2}")
    @DisplayName("every 5xx answer's server span has status ERROR, naming the failure's class or error type, never"
            + " its message")
    @CsvSource({
            "store-outage,  503, nuna.catalog.store.StoreUnavailableException",
            "unhandled,     500, java.lang.IllegalStateException",
            "catalog-error, 500, nuna.catalog.core.CatalogError",
            "route-503,     503, ServiceUnavailableException",
            "filter-503,    503, ServiceUnavailableException"})
    void aFiveHundredAnswerEndsItsServerSpanInError(String fault, int status, String named) throws Exception {
        int answered = mvc.perform(get(TABLE).header(FAULT, fault)).andReturn().getResponse().getStatus();

        assertThat(answered).isEqualTo(status);
        SpanData span = serverSpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertFailureNamed(span, status + " " + named);
        assertThat(meters.get("http.server.requests").timer().getId().getTag("exception"))
                .as("Boot's request timer names the failure as the span does").isEqualTo("AnswerFailed");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a failure that escapes to the container ends its server span ERROR, naming its class, never its"
            + " message")
    void anEscapedFailureEndsItsServerSpanInError() {
        Throwable escaped = catchThrowable(() -> mvc.perform(get(TABLE).header(FAULT, "escape")));

        SpanData span = serverSpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertFailureNamed(span, "500 java.lang.IllegalStateException");
        assertThat(escaped).as("the container still gets a failure, and answers 500").isNotNull();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @DisplayName("a 4xx or 2xx answer leaves the server span's status unset")
    @CsvSource({"not-found, 404", "none, 200"})
    void anAnswerBelowFiveHundredLeavesTheStatusUnset(String fault, int status) throws Exception {
        int answered = mvc.perform(get(TABLE).header(FAULT, fault)).andReturn().getResponse().getStatus();

        assertThat(answered).isEqualTo(status);
        SpanData span = serverSpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getEvents()).isEmpty();
    }

    private SpanData serverSpan() {
        assertThat(exported.spans).as("one span: the request's server span").hasSize(1);
        return exported.spans.getFirst();
    }

    /** The span's exception events name the failure and carry nothing of its message. */
    private static void assertFailureNamed(SpanData span, String failure) {
        assertThat(span.getStatus().getDescription()).doesNotContain(SECRET);
        assertThat(span.getEvents()).as("the span's exception events").isNotEmpty().allSatisfy(event -> {
            assertThat(event.getName()).isEqualTo("exception");
            assertThat(attributesOf(event)).doesNotContain(SECRET);
        });
        assertThat(span.getEvents()).extracting(ServerSpanStatusTest::attributesOf)
                .anySatisfy(attributes -> assertThat(attributes)
                        .contains(RequestMetrics.AnswerFailed.class.getName())
                        .contains(failure));
    }

    private static String attributesOf(EventData event) {
        return event.getAttributes().asMap().toString();
    }

    private static Object advice(String className) throws ReflectiveOperationException {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** loadTable, failing as the fault header says. */
    @RestController
    static class Routes {

        @GetMapping(TABLE)
        ResponseEntity<String> load(@RequestHeader(name = FAULT, defaultValue = "none") String fault) {
            return switch (fault) {
                case "store-outage" -> throw new StoreUnavailableException(SECRET, new IOException(SECRET));
                case "unhandled" -> throw new IllegalStateException(SECRET);
                case "catalog-error" -> throw CatalogError.of(500, "CommitStateUnknownException", SECRET);
                case "not-found" -> throw CatalogError.of(404, "NoSuchTableException", SECRET);
                case "route-503" -> ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                        .body(Wire.error(503, "ServiceUnavailableException", SECRET));
                default -> ResponseEntity.ok("{}");
            };
        }
    }

    /** After the request timer, as authentication is: answers a 5xx itself, or lets a failure escape. */
    static final class Faults extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            switch (String.valueOf(request.getHeader(FAULT))) {
                case "filter-503" -> {
                    response.setStatus(503);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(Wire.error(503, "ServiceUnavailableException", SECRET));
                }
                case "escape" -> throw new IllegalStateException(SECRET);
                default -> chain.doFilter(request, response);
            }
        }
    }

    /** An in-memory span exporter. */
    private static final class Recorder implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
