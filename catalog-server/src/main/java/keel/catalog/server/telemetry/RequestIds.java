package keel.catalog.server.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decides each request's id once, before authentication, so that its audit events, its STS session tags (seen in
 * CloudTrail), its log lines and its trace share one key: the client's {@value #HEADER} when it sends a usable one
 * (1 to {@value #MAX_LENGTH} characters, not blank), otherwise the request's trace id. Every response carries the id
 * back in {@value #HEADER}, error responses included, and every log line written while the request runs carries it
 * as {@value #MDC_KEY}, beside the trace and span ids Micrometer Tracing puts there.
 *
 * <p>Runs right after Boot's server observation filter, which starts the request's trace ({@link TelemetryWiring}).
 * With tracing switched off (OTEL_SDK_DISABLED) there is no trace id, and the id is a random UUID, as before.
 */
public final class RequestIds extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    /** The key log lines carry the request id under. */
    public static final String MDC_KEY = "requestId";
    static final int MAX_LENGTH = 128;
    private static final String ATTRIBUTE = RequestIds.class.getName();

    private final Tracer tracer;

    RequestIds(Tracer tracer) {
        this.tracer = tracer;
    }

    /** The id this filter decided for the request. */
    public static String of(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank() || id.length() > MAX_LENGTH) {
            id = traceId();
        }
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String traceId() {
        Span span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        if (traceId == null || traceId.isBlank() || traceId.chars().allMatch(c -> c == '0')) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
