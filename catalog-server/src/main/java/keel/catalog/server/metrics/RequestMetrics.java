package keel.catalog.server.metrics;

import static keel.catalog.server.metrics.CatalogMetrics.CATALOG;
import static keel.catalog.server.metrics.CatalogMetrics.ERROR_TYPE;
import static keel.catalog.server.metrics.CatalogMetrics.NONE;
import static keel.catalog.server.metrics.CatalogMetrics.OPERATION_ID;
import static keel.catalog.server.metrics.CatalogMetrics.OUTCOME;

import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import keel.catalog.server.rest.Operations;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Times every Iceberg REST call, named by the spec's operationId ({@link Operations}), with its catalog, its outcome
 * (the status class) and, for an error, the Iceberg error type its body names. The histogram's buckets sit at the
 * SLO bounds of the build plan (R7, M8): load p99 under 50 ms, a 304 under 10 ms, a commit under 300 ms. Runs before
 * authentication, so a refused call is timed too. Any other request passes untouched.
 */
public final class RequestMetrics extends OncePerRequestFilter {

    public static final String REQUESTS = "keel.catalog.requests";
    /** The histogram's bucket bounds: the SLOs, set here rather than in configuration. */
    public static final List<Duration> SLO = List.of(Duration.ofMillis(10), Duration.ofMillis(50),
            Duration.ofMillis(300));
    private static final String CATALOG_ATTRIBUTE = RequestMetrics.class.getName() + ".catalog";

    private final MeterProvider<Timer> timers;

    RequestMetrics(MeterRegistry registry) {
        this.timers = Timer.builder(REQUESTS)
                .description("Iceberg REST calls, by operationId and outcome")
                .serviceLevelObjectives(SLO.toArray(Duration[]::new))
                .withRegistry(registry);
    }

    /** Names the catalog the request in hand turned out to address, a known one, for its labels. */
    public static void catalog(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(CATALOG_ATTRIBUTE, name, RequestAttributes.SCOPE_REQUEST);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> operation = Operations.of(request.getMethod(),
                request.getRequestURI().substring(request.getContextPath().length()));
        if (operation.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ErrorCapture capture = new ErrorCapture(response);
        long start = System.nanoTime();
        boolean answered = false;
        try {
            chain.doFilter(request, capture);
            answered = true;
        } finally {
            // A failure that escapes the chain becomes the container's 500.
            int status = answered ? capture.getStatus() : 500;
            Object catalog = request.getAttribute(CATALOG_ATTRIBUTE);
            timers.withTags(CATALOG, catalog == null ? NONE : catalog.toString(),
                            OPERATION_ID, operation.get(),
                            OUTCOME, status / 100 + "xx",
                            ERROR_TYPE, status >= 400 ? capture.errorType() : NONE)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
