package keel.maintenance;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the worker reports to the catalog of its own work, which the catalog keeps for the console's health pages
 * (D38; docs/observability/table-health.md): each scan's start and end, each table's assessment as it is made, and
 * each compaction's start and end. A report the catalog does not take is logged and counted as a {@code report}
 * failure, and the work goes on; one for a table dropped or replaced since the work list was read is refused, and
 * left at that.
 */
final class HealthReports {

    private static final Logger LOG = LoggerFactory.getLogger(HealthReports.class);
    /** The longest reason for a failed scan the catalog keeps. */
    static final int MAX_ERROR = 1000;

    private final Management management;

    HealthReports(Management management) {
        this.management = management;
    }

    void scanStarted(UUID scan, Instant at, ScanReport report) {
        send("reporting the start of scan " + scan, report, () -> management.reportScan(scan, at, Optional.empty(),
                Optional.empty(), Map.of(), Optional.empty()));
    }

    /**
     * Reports the scan's end: how it ended, its failures so far by kind, and for a failed scan, why (its first failed
     * work list read, else {@code error}).
     */
    void scanEnded(UUID scan, Instant started, Instant ended, boolean succeeded, Optional<String> error,
                   ScanReport report) {
        Map<String, Integer> failures = new TreeMap<>();
        report.failuresByKind.forEach((kind, count) -> failures.put(kind.label, count));
        Optional<String> why = succeeded ? Optional.empty()
                : Optional.ofNullable(report.firstFailures.get(ScanReport.Failure.WORK_LIST)).or(() -> error)
                        .map(message -> message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message)
                        .filter(message -> !message.isEmpty());
        send("reporting the end of scan " + scan, report, () -> management.reportScan(scan, started,
                Optional.of(ended), Optional.of(succeeded ? "succeeded" : "failed"), failures, why));
    }

    void assessed(Management.TableWork table, CompactionNeed need, Instant at, ScanReport report) {
        send("reporting the assessment of " + table.id(), report, () -> {
            if (!management.reportAssessment(table, need, at)) {
                LOG.info("The catalog refused the assessment of table {} ({}): gone or replaced", table.name(),
                        table.id());
            }
        });
    }

    /** Reports how the table's compaction, started at {@code started}, stands; {@code ended} once it has ended. */
    void compaction(Management.TableWork table, Instant started, Optional<Instant> ended, Compactor.State state,
                    ScanReport report) {
        send("reporting the compaction of " + table.id(), report, () -> {
            if (!management.reportCompaction(table, started, ended,
                    state.name().toLowerCase(Locale.ROOT).replace('_', '-'))) {
                LOG.info("The catalog refused the compaction report of table {} ({}): gone or replaced", table.name(),
                        table.id());
            }
        });
    }

    private static void send(String what, ScanReport report, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.warn("Failed {}", what, e);
            report.failed(ScanReport.Failure.REPORT, what, e);
        }
    }
}
