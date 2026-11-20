package nuna.catalog.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.BaseUnits;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.iceberg.RestRequest.Counted;
import nuna.catalog.iceberg.RestRequest.MetricsSummary;
import nuna.catalog.iceberg.RestRequest.Result;
import nuna.catalog.iceberg.RestRequest.Timed;
import nuna.catalog.server.rest.CredentialVending.Vended;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The catalog's own meters (docs/observability/catalog-metrics.md): commit outcomes, credential vends and what
 * clients report. The request timer is {@link RequestMetrics}'s. Labels hold only the names in {@link #LABELS}, each
 * with a bounded set of values; never a namespace, table, principal or filter value.
 */
public final class CatalogMetrics {

    public static final String COMMITS = "nuna.catalog.commits";
    public static final String VENDS = "nuna.catalog.credential.vends";
    /** Each client report result becomes a meter of this name and the result's, its hyphens made dots. */
    public static final String REPORT = "nuna.catalog.report.";

    public static final String CATALOG = "catalog";
    public static final String OPERATION_ID = "operation.id";
    public static final String OUTCOME = "outcome";
    public static final String ERROR_TYPE = "error.type";
    public static final String REQUIREMENT_TYPE = "requirement.type";
    public static final String REPORT_TYPE = "report.type";
    public static final String OPERATION = "operation";
    public static final String ENGINE_NAME = "engine.name";
    /** A ref's kind, never its name (D33, choice 13): {@code branch} or {@code tag}. */
    public static final String REF_KIND = "ref.kind";
    /** Every label a catalog meter may carry. */
    public static final Set<String> LABELS = Set.of(CATALOG, OPERATION_ID, OUTCOME, ERROR_TYPE, REQUIREMENT_TYPE,
            REPORT_TYPE, OPERATION, ENGINE_NAME, REF_KIND);

    /** A label's value when there is nothing to say: no catalog, no error, no requirement. */
    public static final String NONE = "none";
    /** A client's value that is not one of the bounded set a label allows. */
    public static final String OTHER = "other";

    /** The snapshot operations the spec names; a commit report's other operations count as {@value #OTHER}. */
    private static final Set<String> OPERATIONS = Set.of("append", "replace", "overwrite", "delete");
    /** At most this many engine names are kept per server; later ones count as {@value #OTHER}. */
    static final int MAX_ENGINES = 20;
    private static final Pattern ENGINE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,31}");
    private static final String STATE_UNKNOWN = "CommitStateUnknownException";

    private final MeterRegistry registry;
    private final Set<String> engines = new HashSet<>();

    public CatalogMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * One reply of the commit engine to {@code operationId}: committed, unchanged, replayed, requirement-failed (a
     * requirement did not hold: 409, with its type), refused (any other 4xx), unknown (the commit may or may not have
     * happened) or failed (any other 5xx).
     */
    public void commit(String catalog, String operationId, CommitReply reply) {
        String outcome;
        String requirement = NONE;
        String error = NONE;
        switch (reply) {
            case CommitReply.Success success -> outcome = how(success.how());
            case CommitReply.Applied applied -> outcome = how(applied.how());
            case CommitReply.Failure failure when failure.requirement().isPresent() -> {
                outcome = "requirement-failed";
                requirement = failure.requirement().get();
                error = failure.type();
            }
            case CommitReply.Failure failure -> {
                outcome = "refused";
                error = failure.type();
            }
            case CommitReply.ServerError server -> {
                outcome = STATE_UNKNOWN.equals(server.type()) ? "unknown" : "failed";
                error = server.type();
            }
            case CommitReply.Unavailable unavailable -> {
                outcome = "failed";
                error = "ServiceUnavailableException";
            }
        }
        Counter.builder(COMMITS)
                .description("Replies of the commit engine, by operation and outcome")
                .tags(CATALOG, catalog, OPERATION_ID, operationId, OUTCOME, outcome, REQUIREMENT_TYPE, requirement,
                        ERROR_TYPE, error)
                .register(registry)
                .increment();
    }

    /**
     * Times one credential vend (D21): issued (new credentials), cached (from the credential cache), none (no
     * credentials for this caller) or failed.
     */
    public Vended vend(String catalog, Supplier<Vended> vend) {
        long start = System.nanoTime();
        String outcome = "failed";
        String error = NONE;
        try {
            Vended vended = vend.get();
            outcome = vended.credentials().isEmpty() ? NONE : vended.cached() ? "cached" : "issued";
            return vended;
        } catch (CatalogError e) {
            error = e.type();
            throw e;
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
            throw e;
        } finally {
            Timer.builder(VENDS)
                    .description("Storage credential vends for table loads and the credentials route")
                    .tags(CATALOG, catalog, OUTCOME, outcome, ERROR_TYPE, error)
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * A client's scan or commit report (D13): each result it gives, a timer's total as one timing and a counter's value
     * as one sample. A report of a type the server does not know records nothing.
     */
    public void report(String catalog, MetricsSummary summary) {
        if (!summary.known()) {
            return;
        }
        Tags tags = Tags.of(CATALOG, catalog, REPORT_TYPE, summary.reportType(),
                OPERATION, summary.operation().isEmpty() ? NONE
                        : OPERATIONS.contains(summary.operation()) ? summary.operation() : OTHER,
                ENGINE_NAME, engine(summary.engineName()));
        for (Result result : summary.results()) {
            String name = REPORT + result.name().replace('-', '.');
            switch (result) {
                case Timed timed when timed.count() > 0 ->
                        Timer.builder(name).tags(tags).register(registry).record(timed.total());
                case Timed timed -> {
                    // Nothing was timed.
                }
                case Counted counted -> DistributionSummary.builder(name)
                        .baseUnit("bytes".equals(counted.unit()) ? BaseUnits.BYTES : null)
                        .tags(tags)
                        .register(registry)
                        .record(counted.value());
            }
        }
    }

    /** The engine name as sent, when it looks like one and is among the first {@value #MAX_ENGINES} seen. */
    private synchronized String engine(String name) {
        if (name.isEmpty()) {
            return NONE;
        }
        if (!ENGINE.matcher(name).matches()) {
            return OTHER;
        }
        if (engines.contains(name) || engines.size() < MAX_ENGINES && engines.add(name)) {
            return name;
        }
        return OTHER;
    }

    private static String how(CommitReply.How how) {
        return switch (how) {
            case COMMITTED -> "committed";
            case UNCHANGED -> "unchanged";
            case REPLAYED -> "replayed";
        };
    }
}
