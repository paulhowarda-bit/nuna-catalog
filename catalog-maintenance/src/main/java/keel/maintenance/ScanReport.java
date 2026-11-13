package keel.maintenance;

import java.util.ArrayList;
import java.util.List;

/** What one scan of the work list did; logged after each scan. */
final class ScanReport {

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

    void failed(String what, RuntimeException e) {
        failures.add(what + ": " + e);
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
