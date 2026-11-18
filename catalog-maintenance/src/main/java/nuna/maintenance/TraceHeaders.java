package nuna.maintenance;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The current span as W3C trace headers ({@code traceparent}), for the worker's requests through Iceberg's REST client,
 * which Micrometer does not instrument ({@link BearerAuthManager} adds them to each). The worker's own calls to the
 * catalog go through the JDK's client, which Micrometer does instrument ({@link Worker#of}).
 */
interface TraceHeaders {

    /** No trace: for a worker outside the service (tests, the child JVMs some tests start). */
    TraceHeaders NONE = Map::of;

    /** The headers that carry the current span's trace; none outside a span. */
    Map<String, String> current();

    static TraceHeaders of(Tracer tracer, Propagator propagator) {
        return () -> {
            Span span = tracer.currentSpan();
            if (span == null) {
                return Map.of();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            propagator.inject(span.context(), headers, Map::put);
            return headers;
        };
    }
}
