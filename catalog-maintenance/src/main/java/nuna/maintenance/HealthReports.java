package nuna.maintenance;

import io.micrometer.common.KeyValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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
 * each compaction's start and end. And each job of the night, for the console's overview and maintenance pages (D45,
 * backlog 126; docs/observability/maintenance-nights.md). A report the catalog does not take is logged and counted as a
 * {@code report} failure, and the work goes on; one for a table dropped or replaced since the work list was read is
 * refused, and left at that. Each report is a part of the scan ({@link ScanSpans}): {@code kept}, or {@code refused}.
 *
 * <p>A compaction Job, an orphan scan and a purge are each a job of their own. Snapshot expiry, manifest rewrite and
 * deletions run round the clock, at every scan: each table's runs of one kind that did something are added up over the
 * night, those that failed apart, and the sum is reported under one id each time it grows. The sums are kept in memory
 * for the night under way, so a worker restarted reports what it does from then on under new ids, and the catalog keeps
 * both.
 */
final class HealthReports {

    private static final Logger LOG = LoggerFactory.getLogger(HealthReports.class);
    /** The longest reason for a failed scan the catalog keeps. */
    static final int MAX_ERROR = 1000;

    /** What a job did. */
    record Counts(long filesIn, long filesOut, long snapshotsExpired, long filesDeleted, long bytesDeleted) {

        static final Counts NONE = new Counts(0, 0, 0, 0, 0);

        static Counts files(long in, long out) {
            return new Counts(in, out, 0, 0, 0);
        }

        static Counts expired(long snapshots) {
            return new Counts(0, 0, snapshots, 0, 0);
        }

        static Counts deleted(long files, long bytes) {
            return new Counts(0, 0, 0, files, bytes);
        }

        Counts plus(Counts other) {
            return new Counts(filesIn + other.filesIn, filesOut + other.filesOut,
                    snapshotsExpired + other.snapshotsExpired, filesDeleted + other.filesDeleted,
                    bytesDeleted + other.bytesDeleted);
        }
    }

    /** A job's kind, as the catalog names it. */
    enum Job {
        COMPACTION, ORPHAN_SCAN, SNAPSHOT_EXPIRY, MANIFEST_REWRITE, PURGE, DELETION;

        final String label = name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A table's runs of one kind on one night, those that succeeded or those that failed. */
    private record Key(LocalDate night, UUID object, Job kind, boolean failed) {
    }

    /** Their sum so far. */
    private static final class Tally {
        final UUID id = UUID.randomUUID();
        final Instant first;
        Instant last;
        Counts counts = Counts.NONE;

        Tally(Instant first) {
            this.first = first;
            this.last = first;
        }
    }

    private final Management management;
    private final ScanSpans spans;
    private final CleanupWindow window;
    private final Map<Key, Tally> tallies = new HashMap<>();

    HealthReports(Management management, ScanSpans spans, CleanupWindow window) {
        this.management = management;
        this.spans = spans;
        this.window = window;
    }

    /** Reports the scan's start, with what the store let the worker list beneath each prefix (backlog 250). */
    void scanStarted(UUID scan, Instant at, List<Storage.Reach> storage, ScanReport report) {
        send("scan-start", "reporting the start of scan " + scan, report, () -> {
            management.reportScan(scan, at, Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), storage);
            return true;
        });
    }

    /**
     * Reports the scan's end: how it ended, its failures so far by kind, for a failed scan, why (its first failed
     * work list read, else {@code error}), and what the store let the worker list and delete beneath each prefix.
     */
    void scanEnded(UUID scan, Instant started, Instant ended, boolean succeeded, Optional<String> error,
                   List<Storage.Reach> storage, ScanReport report) {
        Map<String, Integer> failures = new TreeMap<>();
        report.failuresByKind.forEach((kind, count) -> failures.put(kind.label, count));
        Optional<String> why = succeeded ? Optional.empty()
                : Optional.ofNullable(report.firstFailures.get(ScanReport.Failure.WORK_LIST)).or(() -> error)
                        .map(message -> message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message)
                        .filter(message -> !message.isEmpty());
        send("scan-end", "reporting the end of scan " + scan, report, () -> {
            management.reportScan(scan, started, Optional.of(ended), Optional.of(succeeded ? "succeeded" : "failed"),
                    failures, why, storage);
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
     * Reports a job of its own, a compaction Job, an orphan scan or a purge, as it stands: {@code outcome} is
     * {@code running} until it has {@code ended}, then {@code succeeded}, {@code failed} or {@code cut-off}.
     */
    void job(UUID id, CleanupWindow.Night night, Job kind, UUID object, Optional<Integer> slot, Instant started,
             Optional<Instant> ended, String outcome, Counts counts, ScanReport report) {
        job(id, night, kind, object, slot, started, ended, outcome, counts, Optional.empty(), report);
    }

    /** The same, for a job run on request (backlog 114): {@code requested} is the time it was asked. */
    void job(UUID id, CleanupWindow.Night night, Job kind, UUID object, Optional<Integer> slot, Instant started,
             Optional<Instant> ended, String outcome, Counts counts, Optional<Instant> requested, ScanReport report) {
        send("job", "reporting the " + kind.label + " of " + object, report, () -> {
            boolean kept = management.reportJob(id, night, kind, object, slot, started, ended, outcome, counts,
                    requested);
            if (!kept) {
                LOG.info("The catalog refused the {} report of {}: no such object", kind.label, object);
            }
            return kept;
        });
    }

    /**
     * Adds a run of a round-the-clock job, from {@code started} to {@code ended}, to the night's sum of the object's
     * runs of that kind that succeeded, or of those that failed, and reports the sum. The night is the window's last
     * opening at {@code started}. A run that succeeded and did nothing is not reported.
     */
    void ran(Job kind, UUID object, Instant started, Instant ended, boolean failed, Counts counts,
             ScanReport report) {
        if (!failed && counts.equals(Counts.NONE)) {
            return;
        }
        CleanupWindow.Night night = window.last(started);
        tallies.keySet().removeIf(key -> !key.night().equals(night.date()));
        Tally tally = tallies.computeIfAbsent(new Key(night.date(), object, kind, failed), key -> new Tally(started));
        tally.last = ended.isAfter(tally.last) ? ended : tally.last;
        tally.counts = tally.counts.plus(counts);
        job(tally.id, night, kind, object, Optional.empty(), tally.first, Optional.of(tally.last),
                failed ? "failed" : "succeeded", tally.counts, report);
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
