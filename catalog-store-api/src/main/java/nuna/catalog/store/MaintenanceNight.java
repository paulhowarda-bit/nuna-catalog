package nuna.catalog.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One maintenance night's totals, summed by the store over the jobs the worker reported for it
 * ({@link MaintenanceJob}): what the console's overview and maintenance pages lead with (D45, backlog 126).
 *
 * @param night            the date the night's window opened, in the window's zone
 * @param opens            when its window opened: the earliest any of its jobs reports
 * @param closes           when its window closed: the latest any of its jobs reports
 * @param tablesCompacted  compaction Jobs that succeeded, one a table
 * @param filesCompacted   data files every compaction Job replaced, those cut off included: what they finished stays
 * @param filesWritten     data files they wrote in their place
 * @param snapshotsExpired snapshots expired
 * @param filesDeleted     files deleted, due pending deletions and purged objects' files
 * @param bytesDeleted     their size
 * @param running          jobs still running
 * @param succeeded        jobs that succeeded
 * @param cutOff           jobs the window's end cut off
 * @param failed           jobs that failed
 */
public record MaintenanceNight(LocalDate night, Instant opens, Instant closes, long tablesCompacted,
                               long filesCompacted, long filesWritten, long snapshotsExpired, long filesDeleted,
                               long bytesDeleted, long running, long succeeded, long cutOff, long failed) {

    public MaintenanceNight {
        Objects.requireNonNull(night, "night");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
    }
}
