package keel.catalog.server.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.CommitResult;
import keel.catalog.store.KeyState;
import keel.catalog.store.PointerKey;
import keel.catalog.store.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Observed} and {@link ObservedStore} on Micrometer's OpenTelemetry bridge, the way Boot wires it: a call inside
 * an observation is one child span and one timer, with its outcome; a call outside any makes neither; a failure's span
 * records the exception's type and never its message; a store call's outcome names the result's kind.
 */
class ObservedTest {

    private static final String NAME = "keel.test";
    private static final String SECRET = "s3://warehouse/tables/0b6e/metadata/00001.metadata.json for pia_7f3a";

    private final SpanRecorder exported = new SpanRecorder();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exported)).build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry registry = ObservationRegistry.create();

    {
        OtelTracer tracer = new OtelTracer(tracerProvider.get("observed-test"), new OtelCurrentTraceContext(),
                event -> { });
        registry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(new DefaultTracingObservationHandler(tracer));
    }

    @AfterEach
    void close() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("a call made outside any observation is neither a span nor a timer")
    void outsideAnObservationNothingIsObserved() {
        assertThat(Observed.call(registry, NAME, "test call", KeyValues.of("k", "v"), () -> "result", r -> "ok"))
                .isEqualTo("result");

        assertThat(exported.spans()).isEmpty();
        assertThat(meters.find(NAME).timers()).isEmpty();
    }

    @Test
    @DisplayName("inside an observation a call is one child span and one timer, with its keys and outcome")
    void insideAnObservationACallIsOneChildSpanAndOneTimer() {
        Observation request = Observation.start("test.request", registry);
        try (Observation.Scope scope = request.openScope()) {
            assertThat(Observed.call(registry, NAME, "test call", KeyValues.of("k", "v"), () -> "result",
                    r -> "found")).isEqualTo("result");
        } finally {
            request.stop();
        }

        SpanData parent = exported.named("test.request");
        SpanData call = exported.named("test call");
        assertThat(call.getParentSpanId()).isEqualTo(parent.getSpanId());
        assertThat(call.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(attribute(call, "k")).isEqualTo("v");
        assertThat(attribute(call, Observed.OUTCOME)).isEqualTo("found");
        assertThat(attribute(call, Observed.EXCEPTION)).isEqualTo("none");
        assertThat(call.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);

        Timer timer = meters.get(NAME).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag("k")).isEqualTo("v");
        assertThat(timer.getId().getTag(Observed.OUTCOME)).isEqualTo("found");
        assertThat(timer.getId().getTag(Observed.EXCEPTION)).isEqualTo("none");
    }

    @Test
    @DisplayName("a failed call rethrows its exception; its span is an error that names the exception's type and"
            + " nothing of its message")
    void aFailureRecordsItsTypeNeverItsMessage() {
        IllegalStateException failure = new IllegalStateException("reading " + SECRET + " failed",
                new RuntimeException(SECRET));
        Observation request = Observation.start("test.request", registry);
        try (Observation.Scope scope = request.openScope()) {
            assertThatThrownBy(() -> Observed.call(registry, NAME, "test call", KeyValues.empty(), () -> {
                throw failure;
            }, r -> "ok")).isSameAs(failure);
        } finally {
            request.stop();
        }

        SpanData call = exported.named("test call");
        assertThat(call.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attribute(call, Observed.OUTCOME)).isEqualTo("error");
        assertThat(attribute(call, Observed.EXCEPTION)).isEqualTo("IllegalStateException");
        List<String> recorded = Stream.concat(Stream.of(call.getName(), call.getStatus().getDescription()),
                Stream.concat(call.getAttributes().asMap().values().stream(),
                        call.getEvents().stream().flatMap(e -> Stream.concat(Stream.of(e.getName()),
                                e.getAttributes().asMap().values().stream()))).map(String::valueOf)).toList();
        assertThat(recorded).anyMatch(value -> value.contains(IllegalStateException.class.getName()))
                .noneMatch(value -> value.contains("s3://") || value.contains("pia_7f3a") || value.contains("reading"));

        Timer timer = meters.get(NAME).timer();
        assertThat(timer.getId().getTag(Observed.OUTCOME)).isEqualTo("error");
        assertThat(timer.getId().getTag(Observed.EXCEPTION)).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("a store call is a span named for its method, tagged with the backend and its result's kind")
    void aStoreCallNamesItsMethodAndOutcome() {
        CommitResult committed = new CommitResult.Committed(UUID.randomUUID());
        CatalogStore fake = fake(committed);
        CatalogStore store = ObservedStore.of(fake, "postgresql", registry);
        Observation request = Observation.start("test.request", registry);
        try (Observation.Scope scope = request.openScope()) {
            assertThat(store.commit(null)).isSameAs(committed);
            assertThat(store.readPointer(null)).isEmpty();
            assertThatThrownBy(() -> store.findCommit(UUID.randomUUID(), null))
                    .isExactlyInstanceOf(CommitOutcomeUnknownException.class);
        } finally {
            request.stop();
        }

        SpanData commit = exported.named("store commit");
        assertThat(attribute(commit, "backend")).isEqualTo("postgresql");
        assertThat(attribute(commit, "operation")).isEqualTo("commit");
        assertThat(attribute(commit, Observed.OUTCOME)).isEqualTo("committed");
        assertThat(attribute(exported.named("store readPointer"), Observed.OUTCOME)).isEqualTo("absent");
        assertThat(attribute(exported.named("store findCommit"), Observed.EXCEPTION))
                .isEqualTo("CommitOutcomeUnknownException");
        assertThat(meters.get(ObservedStore.NAME).tag("operation", "commit").tag(Observed.OUTCOME, "committed")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a store result's outcome is its kind, in words")
    void storeOutcomesNameTheResultsKind() {
        PointerKey key = null;
        assertThat(Stream.of(new CommitResult.Committed(UUID.randomUUID()), new CommitResult.Conflict(key,
                        Optional.empty()), new CommitResult.Inactive(key), WriteResult.APPLIED,
                        new WriteResult.NameTaken(UUID.randomUUID()), new WriteResult.VersionMismatch(3),
                        new WriteResult.Refused(WriteResult.Reason.HELD), new KeyState.InFlight(UUID.randomUUID(),
                        Instant.EPOCH), CommitLookup.NOT_COMMITTED, Optional.of(1), Optional.empty(), List.of(), 7)
                .map(ObservedStore::outcome))
                .containsExactly("committed", "conflict", "inactive", "applied", "name-taken", "version-mismatch",
                        "refused", "in-flight", "not-committed", "found", "absent", "ok", "ok");
        assertThat(ObservedStore.outcome(null)).isEqualTo("ok");
    }

    /** A store whose commit returns {@code committed}, whose findCommit cannot decide, and that finds nothing. */
    private static CatalogStore fake(CommitResult committed) {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "commit" -> committed;
                    case "findCommit" -> throw new CommitOutcomeUnknownException((UUID) args[0],
                            new IllegalStateException(SECRET));
                    case "readPointer" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
