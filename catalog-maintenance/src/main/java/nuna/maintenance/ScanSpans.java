package nuna.maintenance;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Each scan, and each part of it, as a Micrometer Observation (backlog 49, docs/observability/worker-spans.md): a span
 * when tracing exports, and a timer of the observation's name. The scan is the trace's root; each part is a child of
 * it, and each call the part makes to the catalog a child of the part's, which the catalog's own spans join
 * ({@code traceparent}).
 *
 * <p>Every attribute holds a value from a small fixed set: never a table, a namespace, a location or a person. A failed
 * part's or scan's span records the exception's type, never its message, which can name a table or its location
 * ({@link Failed}).
 */
final class ScanSpans {

    /** The observation of a whole scan, apart from the scan timer {@value WorkerMetrics#SCAN}. */
    static final String SCAN = WorkerMetrics.PREFIX + "scan.run";
    /** The scan's span. */
    static final String SCAN_SPAN = "maintenance scan";
    /** The observation of one part of a scan; its span is {@code maintenance <part>}. */
    static final String PART = WorkerMetrics.PREFIX + "part";
    static final String PART_KEY = "part";
    /** A part's or a scan's outcome: a scan's as its timer's, a part's from a small set, or {@value #ERROR}. */
    static final String OUTCOME = "outcome";
    /** The simple name of the exception a part or a scan threw, or {@value #NONE}. */
    static final String EXCEPTION = "exception";
    /** On a work list read's span only: the section read, {@code tables}, {@code purges} or {@code deletions}. */
    static final String SECTION = "section";
    /** On a report's span only: what was reported, {@code scan-start}, {@code scan-end}, {@code assessment} or
     * {@code compaction}. */
    static final String REPORT = "report";
    static final String OK = "ok";
    static final String ERROR = "error";
    static final String NONE = "none";

    /** The parts of a scan, each named for what it does. */
    enum Part {
        /** A page of one section of the work list. */
        WORK_LIST("work-list"),
        /** A table loaded by name through the catalog's Iceberg REST API. */
        TABLE_LOAD("table-load"),
        SNAPSHOT_EXPIRY("snapshot-expiry"),
        MANIFEST_REWRITE("manifest-rewrite"),
        /** A table's manifests read to judge whether it needs compaction. */
        COMPACTION_ASSESSMENT("compaction-assessment"),
        /** A compaction Job started, or found started. */
        COMPACTION_LAUNCH("compaction-launch"),
        ORPHAN_SCAN("orphan-scan"),
        /** One dropped object's purge: claim, delete, complete. */
        PURGE("purge"),
        /** One object's due files deleted and reported done. */
        DELETION("deletion"),
        /** A report of the worker's own work to the catalog (docs/observability/table-health.md). */
        REPORT("report");

        final String label;

        Part(String label) {
            this.label = label;
        }
    }

    private final ObservationRegistry registry;

    ScanSpans(ObservationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Runs one scan, which fills {@code report}, as the trace's root. Its outcome is its timer's: {@code success} when
     * it read the whole work list, else {@code failure}.
     */
    void scan(ScanReport report, Runnable scan) {
        Observation observation = Observation.createNotStarted(SCAN, registry).contextualName(SCAN_SPAN).start();
        try (Observation.Scope scope = observation.openScope()) {
            scan.run();
            observation.lowCardinalityKeyValue(OUTCOME, report.failuresByKind.containsKey(ScanReport.Failure.WORK_LIST)
                    ? WorkerMetrics.FAILURE : WorkerMetrics.SUCCESS);
            observation.lowCardinalityKeyValue(EXCEPTION, NONE);
        } catch (RuntimeException | Error e) {
            failed(observation, WorkerMetrics.FAILURE, e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** Runs one part of a scan; its outcome is {@value #OK}. */
    void part(Part part, Runnable call) {
        part(part, KeyValues.empty(), () -> {
            call.run();
            return OK;
        }, outcome -> outcome);
    }

    /**
     * Runs one part of a scan.
     *
     * @param attributes the span's own attributes, bounded values only, which its timer does not carry: a timer's
     *                   name keeps one set of labels
     * @param outcome    names the result's outcome, from a small fixed set
     */
    <T> T part(Part part, KeyValues attributes, Supplier<T> call, Function<? super T, String> outcome) {
        Observation observation = Observation.createNotStarted(PART, registry)
                .contextualName("maintenance " + part.label)
                .lowCardinalityKeyValue(PART_KEY, part.label)
                .highCardinalityKeyValues(attributes)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            T result = call.get();
            observation.lowCardinalityKeyValue(OUTCOME, outcome.apply(result));
            observation.lowCardinalityKeyValue(EXCEPTION, NONE);
            return result;
        } catch (RuntimeException | Error e) {
            failed(observation, ERROR, e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private static void failed(Observation observation, String outcome, Throwable e) {
        observation.lowCardinalityKeyValue(OUTCOME, outcome);
        observation.lowCardinalityKeyValue(EXCEPTION, e.getClass().getSimpleName());
        observation.error(new Failed(e));
    }

    /**
     * What a failed span records in place of the exception: its class name, with no message, cause or stack trace of
     * its own. The worker's messages name tables, their ids and locations. The timer tags a failure
     * {@code error=Failed}; the exception is in {@value #EXCEPTION}.
     */
    static final class Failed extends RuntimeException {

        Failed(Throwable failure) {
            super(failure.getClass().getName(), null, false, false);
        }
    }
}
