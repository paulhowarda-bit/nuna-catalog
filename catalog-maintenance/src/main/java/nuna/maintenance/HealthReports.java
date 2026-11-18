package nuna.maintenance;

import io.micrometer.common.KeyValues;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the worker reports to the catalog of its own work, which the catalog keeps for the console's health pages
 * (D38; docs/observability/table-health.md): each scan's start and end, each table's assessment as it is made, and
 * each compaction's start and end. A report the catalog does not take is logged and counted as a {@code report}
 * failure, and the work goes on; one for a table dropped or replaced since the work list was read is refused, and
 * left at that. Each report is a part of the scan ({@link ScanSpans}): {@code kept}, or {@code refused}.
 */
final class HealthReports {

    private static final Logger LOG = LoggerFactory.getLogger(HealthReports.class);
    /** The longest reason for a failed scan the catalog keeps. */
    static final int MAX_ERROR = 1000;

    private final Management management;
    private final ScanSpans spans;

    HealthReports(Management management, ScanSpans spans) {
        this.management = management;
        this.spans = spans;
    }

    void scanStarted(UUID scan, Instant at, ScanReport report) {
        send("scan-start", "reporting the start of scan " + scan, report, () -> {
            management.reportScan(scan, at, Optional.empty(), Optional.empty(), Map.of(), Optional.empty());
            return true;
        });
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
        send("scan-end", "reporting the end of scan " + scan, report, () -> {
            management.reportScan(scan, started, Optional.of(ended), Optional.of(succeeded ? "succeeded" : "failed"),
                    failures, why);
            return true;
        });
    }

    void assessed(Management.TableWork table, CompactionNeed need, Instant at, ScanReport report) {
        send("assessment", "reporting the assessment of " + table.id(), report, () -> {
            boolean kept = management.reportAssessment(table, need, at);
            if (!kept) {
                LOG.info("The catalog refused the assessment of table {} ({}): gone or replaced", table.name(),
                        table.id());
            }
            return kept;
        });
    }

    /** Reports how the table's compaction, started at {@code started}, stands; {@code ended} once it has ended. */
    void compaction(Management.TableWork table, Instant started, Optional<Instant> ended, Compactor.State state,
                    ScanReport report) {
        send("compaction", "reporting the compaction of " + table.id(), report, () -> {
            boolean kept = management.reportCompaction(table, started, ended,
                    state.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (!kept) {
                LOG.info("The catalog refused the compaction report of table {} ({}): gone or replaced", table.name(),
                        table.id());
            }
            return kept;
        });
    }

    /**
     * Sends one report, {@code which} kind, as a part of the scan.
     *
     * @param call true when the catalog kept the report, false when it refused it
     */
    private void send(String which, String what, ScanReport report, Supplier<Boolean> call) {
        try {
            spans.part(ScanSpans.Part.REPORT, KeyValues.of(ScanSpans.REPORT, which), call,
                    kept -> kept ? "kept" : "refused");
        } catch (RuntimeException e) {
            LOG.warn("Failed {}", what, e);
            report.failed(ScanReport.Failure.REPORT, what, e);
        }
    }
}
