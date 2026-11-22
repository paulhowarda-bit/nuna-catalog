package nuna.catalog.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One job of a maintenance night, as the maintenance worker reports it to the catalog (D30, D45): what it did to one
 * table, when, on which of the night's compaction slots, and how it ended. The catalog keeps the jobs of the last
 * nights for the console's overview and maintenance pages (backlog 126).
 *
 * <p>A night is one opening of the worker's cleanup window and the day that follows it, until the window opens again:
 * compaction and orphan scans run in the window, and the jobs that run round the clock (snapshot expiry, manifest
 * rewrite, purges, deletions) count to the night that opened last. The worker reports a compaction Job as it starts and
 * as it ends, and each orphan scan and purge once it has ended. It adds up a round-the-clock job of a table over the
 * night, and reports the sum each time it grows, under one id: a job that succeeded and one that failed are each a job
 * of their own.
 *
 * @param id               the worker's id for the job; a report under the same id replaces the one kept, unless the
 *                         one kept ended later, or has ended and this one has not ({@link #replaces}), and the job
 *                         keeps the earlier of the two starts
 * @param worker           the maintenance principal that reported it
 * @param night            the date the night's window opened, in the window's zone
 * @param opens            when the night's window opened
 * @param closes           when it closed, or will close
 * @param kind             what the job did
 * @param objectId         the table, or the dropped table or view a purge or deletion was for
 * @param slot             the compaction slot it ran in, 1 for the first, while at most that many Jobs run at once;
 *                         only a compaction has one
 * @param startedAt        when it started, by the worker's clock
 * @param endedAt          when it ended; empty while it runs
 * @param outcome          how it ended, or {@link Outcome#RUNNING}
 * @param filesIn          files it rewrote: the data files a compaction replaced, the manifests a rewrite replaced
 * @param filesOut         files it wrote in their place
 * @param snapshotsExpired snapshots an expiry removed
 * @param filesDeleted     files a deletion or purge deleted
 * @param bytesDeleted     their size
 */
public record MaintenanceJob(UUID id, String worker, LocalDate night, Instant opens, Instant closes, Kind kind,
                             UUID objectId, Optional<Integer> slot, Instant startedAt, Optional<Instant> endedAt,
                             Outcome outcome, long filesIn, long filesOut, long snapshotsExpired, long filesDeleted,
                             long bytesDeleted) {

    public enum Kind {
        /** A compaction Job in the window, on a slot of its own. */
        COMPACTION,
        /** An orphan scan in the window, in the orphan scans' own lane. */
        ORPHAN_SCAN,
        SNAPSHOT_EXPIRY,
        MANIFEST_REWRITE,
        /** The deletion of a dropped object's files at the end of its drop window. */
        PURGE,
        /** The deletion of due pending deletions of one object. */
        DELETION
    }

    public enum Outcome {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Stopped by the cleanup window's end: what a compaction finished stays committed. */
        CUT_OFF
    }

    /** The most slots a night has: far more Jobs than one worker runs at once. */
    public static final int MAX_SLOT = 1000;

    public MaintenanceJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(night, "night");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (worker.isEmpty() || worker.length() > 1000) {
            throw new IllegalArgumentException("a worker is named in 1 to 1000 characters");
        }
        if (night.getYear() < 1 || night.getYear() > 9999) {
            throw new IllegalArgumentException("a night falls in the years 1 to 9999: " + night);
        }
        if (!closes.isAfter(opens)) {
            throw new IllegalArgumentException("a night's window closes after it opens");
        }
        if (slot.isPresent() != (kind == Kind.COMPACTION) || slot.isPresent() && (slot.get() < 1
                || slot.get() > MAX_SLOT)) {
            throw new IllegalArgumentException("a compaction, and only a compaction, has a slot from 1 to " + MAX_SLOT);
        }
        if (endedAt.isPresent() == (outcome == Outcome.RUNNING)) {
            throw new IllegalArgumentException("a job has an end exactly when it is no longer running");
        }
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt)) {
            throw new IllegalArgumentException("a job ends after it starts");
        }
        if (filesIn < 0 || filesOut < 0 || snapshotsExpired < 0 || filesDeleted < 0 || bytesDeleted < 0) {
            throw new IllegalArgumentException("a job's counts are 0 or more");
        }
    }

    /**
     * Whether this report replaces {@code kept}, a report of the same job: unless {@code kept} ended later, or has
     * ended and this one has not. So a report repeated late changes nothing, and a running job's report never undoes
     * its end. Every store keeps this rule.
     */
    public boolean replaces(MaintenanceJob kept) {
        if (kept.endedAt().isEmpty()) {
            return true;
        }
        return endedAt.isPresent() && !endedAt.get().isBefore(kept.endedAt().get());
    }
}
