package nuna.maintenance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What one scan of the work list did; logged after each scan and recorded as meters ({@link WorkerMetrics}). */
final class ScanReport {

    /** What failed, the label {@code kind} of {@code nuna.maintenance.failures}: bounded, never a table or file. */
    enum Failure {
        /** A page of the work list could not be read; the scan did not succeed. */
        WORK_LIST("work-list"),
        TABLE_LOAD("table-load"),
        /** The table the work list named is now another table, by id, so nothing ran on it. */
        TABLE_CHANGED("table-changed"),
        SNAPSHOT_EXPIRY("snapshot-expiry"),
        MANIFEST_REWRITE("manifest-rewrite"),
        ORPHAN_SCAN("orphan-scan"),
        COMPACTION("compaction"),
        PURGE("purge"),
        DELETION("deletion"),
        /** Warning of or ending one branch the work list's refs section named (D33). */
        REFS("refs"),
        /** A report of the worker's own work to the catalog (D38), which did not take it; the work went on. */
        REPORT("report");

        final String label;

        Failure(String label) {
            this.label = label;
        }
    }

    /** The scan's id, as reported to the catalog (D38). */
    final UUID id = UUID.randomUUID();
    /** Tables the work list named. */
    int tables;
    int snapshotsExpired;
    int manifestsRewritten;
    int orphanScans;
    /** Orphan scans the window's end stopped; not counted in {@link #orphanScans}, and done again the next night. */
    int orphanScansCutShort;
    int orphansFound;
    /** Tables whose manifests were read to judge whether they need compaction. */
    int tablesAssessed;
    /** Tables that need compaction, by reason; a table may have more than one. */
    final Map<CompactionNeed.Reason, Integer> needs = new EnumMap<>(CompactionNeed.Reason.class);
    int compactionsStarted;
    /** Compaction Jobs the window's end stopped, counted once the window has closed. */
    int compactionsCutOff;
    /** Tables that needed compaction and got no Job before the window closed. */
    int tablesLeftOver;
    /** Files handed to the catalog's pending-deletion list (some may have been pending already). */
    int filesRecorded;
    /** Files left alone because they lie outside their table's location, which the catalog would refuse. */
    int filesOutside;
    int purged;
    /** Purges another worker holds, or the catalog refused or rate-limited. */
    int purgesSkipped;
    /** Files deleted: due pending deletions and purged objects' files. */
    int filesDeleted;
    /**
     * Files left alone because a catalog ref reaches them (D33), by the ref's kind: unreferenced on main and not
     * recorded, or due and completed without being deleted.
     */
    final Map<Management.RefKind, Integer> kept = new EnumMap<>(Management.RefKind.class);
    /** Branches whose expiry warning this scan sent (D33). */
    int refsWarned;
    /** Branches past their expiry this scan ended (D33). */
    int refsEnded;
    final List<String> failures = new ArrayList<>();
    /** How many of {@link #failures} are of each kind. */
    final Map<Failure, Integer> failuresByKind = new EnumMap<>(Failure.class);
    /** The first of {@link #failures} of each kind. */
    final Map<Failure, String> firstFailures = new EnumMap<>(Failure.class);

    /** Counts files a ref keeps, by its kind. */
    void kept(Map<Management.RefKind, Integer> byKind) {
        byKind.forEach((kind, files) -> kept.merge(kind, files, Integer::sum));
    }

    void failed(Failure kind, String what, RuntimeException e) {
        failed(kind, what + ": " + e);
    }

    void failed(Failure kind, String message) {
        failures.add(message);
        failuresByKind.merge(kind, 1, Integer::sum);
        firstFailures.putIfAbsent(kind, message);
    }

    @Override
    public String toString() {
        return "tables " + tables + ", snapshots expired " + snapshotsExpired + ", manifests rewritten "
                + manifestsRewritten + ", orphan scans " + orphanScans + " (" + orphansFound + " orphans, "
                + orphanScansCutShort + " cut short), tables assessed " + tablesAssessed + " (needing compaction "
                + needs + "), compactions " + compactionsStarted + " (" + compactionsCutOff + " cut off, "
                + tablesLeftOver + " tables left over), files recorded " + filesRecorded + " (" + filesOutside
                + " outside), purged " + purged + " (" + purgesSkipped + " skipped), files deleted " + filesDeleted
                + ", files kept for refs " + kept + ", branches warned " + refsWarned + " and ended " + refsEnded
                + ", failures " + failures.size();
    }
}
