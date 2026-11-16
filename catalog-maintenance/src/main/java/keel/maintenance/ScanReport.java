package keel.maintenance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What one scan of the work list did; logged after each scan and recorded as meters ({@link WorkerMetrics}). */
final class ScanReport {

    /** What failed, the label {@code kind} of {@code keel.maintenance.failures}: bounded, never a table or file. */
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
        DELETION("deletion");

        final String label;

        Failure(String label) {
            this.label = label;
        }
    }

    /** Tables the work list named. */
    int tables;
    int snapshotsExpired;
    int manifestsRewritten;
    int orphanScans;
    int orphansFound;
    int compactionsStarted;
    /** Files handed to the catalog's pending-deletion list (some may have been pending already). */
    int filesRecorded;
    /** Files left alone because they lie outside their table's location, which the catalog would refuse. */
    int filesOutside;
    int purged;
    /** Purges another worker holds, or the catalog refused or rate-limited. */
    int purgesSkipped;
    /** Files deleted: due pending deletions and purged objects' files. */
    int filesDeleted;
    final List<String> failures = new ArrayList<>();
    /** How many of {@link #failures} are of each kind. */
    final Map<Failure, Integer> failuresByKind = new EnumMap<>(Failure.class);

    void failed(Failure kind, String what, RuntimeException e) {
        failed(kind, what + ": " + e);
    }

    void failed(Failure kind, String message) {
        failures.add(message);
        failuresByKind.merge(kind, 1, Integer::sum);
    }

    @Override
    public String toString() {
        return "tables " + tables + ", snapshots expired " + snapshotsExpired + ", manifests rewritten "
                + manifestsRewritten + ", orphan scans " + orphanScans + " (" + orphansFound + " orphans), compactions "
                + compactionsStarted + ", files recorded " + filesRecorded + " (" + filesOutside + " outside), purged "
                + purged + " (" + purgesSkipped + " skipped), files deleted " + filesDeleted + ", failures "
                + failures.size();
    }
}
