package nuna.maintenance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchTableException;

/** What one scan of the work list did; logged after each scan and recorded as meters ({@link WorkerMetrics}). */
final class ScanReport {

    /** What failed, the label {@code kind} of {@code nuna.maintenance.failures}: bounded, never a table or file. */
    enum Failure {
        /** A page of the work list could not be read; the scan did not succeed. */
        WORK_LIST("work-list"),
        /**
         * The storage prefixes the catalog gives the worker could not be read, so the scan listed and deleted nothing;
         * or the worker refused one of the catalog's storage profiles or prefixes, and lists and deletes nothing
         * beneath it (item 256).
         */
        STORAGE_PREFIXES("storage-prefixes"),
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
    /**
     * The first failed jobs on a table, at most {@link #MAX_NAMED}, each with its table and why (backlog 327), for the
     * scan's report to the catalog; {@link #failuresByKind} counts them all.
     */
    final List<Named> named = new ArrayList<>();
    /**
     * Work-list entries, of the tables and requests sections, that the catalog said are closed until classified (D27;
     * backlog 334), each skipped without loading its table; not counted in {@link #failuresByKind}.
     */
    int closed;
    /** The closed tables {@link #skippedClosed} has named, each once a scan. */
    private final Set<UUID> namedClosed = new HashSet<>();

    /** The failed jobs a scan names at most, as the catalog keeps them. */
    static final int MAX_NAMED = 20;
    /** The longest message named, as the catalog keeps it. */
    static final int MAX_MESSAGE = 300;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");
    /**
     * The words of the catalog's refusal of a table closed until classified (D27; the authorizer's
     * {@code UNCLASSIFIED} denial), which answers 403 as every other refusal does.
     */
    static final String CLOSED_WORDS = "closed until it is classified";

    /**
     * A failed job on a table: its kind, the table as the work list named it, why in a word ({@code closed},
     * {@code refused}, {@code not-found}, {@code changed} or {@code error}) and the message the worker met.
     */
    record Named(Failure kind, Management.TableWork table, String reason, String message) {
    }

    /** Counts files a ref keeps, by its kind. */
    void kept(Map<Management.RefKind, Integer> byKind) {
        byKind.forEach((kind, files) -> kept.merge(kind, files, Integer::sum));
    }

    void failed(Failure kind, String what, RuntimeException e) {
        failed(kind, what + ": " + e);
    }

    /** A job on {@code table} that failed with {@code e}, named with the table and why (backlog 327). */
    void failed(Failure kind, Management.TableWork table, String what, RuntimeException e) {
        failed(kind, what + " " + table.id(), e);
        name(kind, table, reason(e), Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
    }

    /** A job on {@code table} that did not run, for {@code reason}, named with the table (backlog 327). */
    void failed(Failure kind, Management.TableWork table, String reason, String message) {
        failed(kind, message);
        name(kind, table, reason, message);
    }

    /**
     * A table the catalog said is closed until classified, which the worker did not load (backlog 334): named once a
     * scan as a {@code table-load} with reason {@code closed}, as a refused load of it was (backlog 327), but not
     * counted as a failure: nothing failed, and the closure is the catalog's rule (D27).
     */
    void skippedClosed(Management.TableWork table) {
        if (namedClosed.add(table.id())) {
            name(Failure.TABLE_LOAD, table, "closed", "table " + table.name() + " is closed until it is classified;"
                    + " not loaded");
        }
    }

    private void name(Failure kind, Management.TableWork table, String reason, String message) {
        if (named.size() < MAX_NAMED) {
            String words = CONTROL.matcher(message).replaceAll(" ").strip();
            words = words.isEmpty() ? reason : words;
            named.add(new Named(kind, table, reason, words.length() > MAX_MESSAGE ? words.substring(0, MAX_MESSAGE)
                    : words));
        }
    }

    /** Why a job failed, in a word, from what the catalog or the store answered. */
    static String reason(RuntimeException e) {
        if (e instanceof ForbiddenException) {
            return String.valueOf(e.getMessage()).contains(CLOSED_WORDS) ? "closed" : "refused";
        }
        return e instanceof NoSuchTableException ? "not-found" : "error";
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
                + ", closed until classified " + closed + ", failures " + failures.size();
    }
}
