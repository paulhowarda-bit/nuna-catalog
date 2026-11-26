package nuna.catalog.server.metrics;

import static nuna.catalog.server.metrics.CatalogMetrics.CATALOG;
import static nuna.catalog.server.metrics.CatalogMetrics.ERROR_TYPE;
import static nuna.catalog.server.metrics.CatalogMetrics.NONE;
import static nuna.catalog.server.metrics.CatalogMetrics.OPERATION_ID;
import static nuna.catalog.server.metrics.CatalogMetrics.OUTCOME;

import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nuna.catalog.server.rest.Operations;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Times every Iceberg REST call, named by the spec's operationId ({@link Operations}), with its catalog, its outcome
 * (the status class) and, for an error, the Iceberg error type its body names. The histogram's buckets sit at the
 * SLO bounds of the build plan (R7, M8): load p99 under 50 ms, a 304 under 10 ms, a commit under 300 ms. Runs before
 * authentication, so a refused call is timed too. Any other request passes untouched.
 *
 * <p>It also ends the request's server span with status ERROR when the answer is a 5xx (backlog 265,
 * docs/observability/spans.md): Spring marks the span only for an exception that escapes its filter, never for one an
 * {@code @ExceptionHandler} answered nor for a 5xx a route or filter wrote itself, so a tail sampler keeping traces
 * with status ERROR would miss them. The span records {@link AnswerFailed}, which names the status and the exception's
 * class, or the error type the body names, and never a message.
 */
public final class RequestMetrics extends OncePerRequestFilter {

    public static final String REQUESTS = "nuna.catalog.requests";
    /** The histogram's bucket bounds: the SLOs, set here rather than in configuration. */
    public static final List<Duration> SLO = List.of(Duration.ofMillis(10), Duration.ofMillis(50),
            Duration.ofMillis(300));
    private static final Logger LOG = LoggerFactory.getLogger(RequestMetrics.class);
    private static final String CATALOG_ATTRIBUTE = RequestMetrics.class.getName() + ".catalog";

    private final MeterProvider<Timer> timers;
    private final ObservationRegistry observations;

    RequestMetrics(MeterRegistry registry, ObservationRegistry observations) {
        this.observations = observations;
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
        } catch (ServletException | IOException | RuntimeException e) {
            // Spring's observation filter records what escapes on the server span, message and all: it gets the class,
            // and the log line the whole failure.
            LOG.error("request failed outside the routes", e);
            throw new AnswerFailed(500, e.getClass().getName());
        } finally {
            // A failure that escapes the chain becomes the container's 500.
            int status = answered ? capture.getStatus() : 500;
            Object catalog = request.getAttribute(CATALOG_ATTRIBUTE);
            timers.withTags(CATALOG, catalog == null ? NONE : catalog.toString(),
                            OPERATION_ID, operation.get(),
                            OUTCOME, status / 100 + "xx",
                            ERROR_TYPE, status >= 400 ? capture.errorType() : NONE)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            if (answered && status >= 500) {
                failed(request, status, capture);
            }
        }
    }

    /** Marks the request's server span ERROR, naming the failure's class or the body's error type, never a message. */
    private void failed(HttpServletRequest request, int status, ErrorCapture capture) {
        Observation server = observations.getCurrentObservation();
        if (server == null || !(server.getContext() instanceof ServerRequestObservationContext)) {
            return;
        }
        Object handled = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        server.error(new AnswerFailed(status, handled == null ? capture.errorType() : handled.getClass().getName()));
    }

    /**
     * What a 5xx answer's server span records: its status and the class of the exception behind it, or the Iceberg
     * error type its body names, with no message, cause or stack trace. Messages can name a table, a location or a
     * principal (docs/observability/spans.md, "What tags never hold").
     */
    static final class AnswerFailed extends RuntimeException {

        AnswerFailed(int status, String failure) {
            super(status + " " + failure, null, false, false);
        }
    }
}
