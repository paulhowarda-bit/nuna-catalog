package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The jobs of the maintenance worker's nights, kept for the console (D45, backlog 126) in {@code maintenance_job}, and
 * each night's totals, summed here. A report carries no change id and writes no audit record; it is safe to repeat.
 */
final class SqlNights {

    private static final String JOB = "id, worker, night, night_opens, night_closes, kind, object_id, slot, started_at,"
            + " ended_at, outcome, files_in, files_out, snapshots_expired, files_deleted, bytes_deleted";

    private final Transactions transactions;

    SqlNights(Transactions transactions) {
        this.transactions = transactions;
    }

    void record(MaintenanceJob job, LocalDate keepFrom) {
        transactions.writeAndRetryOnFailure("maintenance job record", connection -> {
            // MaintenanceJob.replaces: unless the row kept ended later, or has ended and this report has not.
            update(connection, "INSERT INTO maintenance_job AS j (" + JOB + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET worker = excluded.worker,"
                            + " night_opens = excluded.night_opens, night_closes = excluded.night_closes,"
                            + " slot = excluded.slot, started_at = LEAST(j.started_at, excluded.started_at),"
                            + " ended_at = excluded.ended_at, outcome = excluded.outcome, files_in = excluded.files_in,"
                            + " files_out = excluded.files_out, snapshots_expired = excluded.snapshots_expired,"
                            + " files_deleted = excluded.files_deleted, bytes_deleted = excluded.bytes_deleted"
                            + " WHERE j.ended_at IS NULL OR excluded.ended_at >= j.ended_at",
                    job.id(), job.worker(), job.night(), job.opens(), job.closes(), job.kind().name(), job.objectId(),
                    job.slot().orElse(null), job.startedAt(), job.endedAt().orElse(null), job.outcome().name(),
                    job.filesIn(), job.filesOut(), job.snapshotsExpired(), job.filesDeleted(), job.bytesDeleted());
            update(connection, "DELETE FROM maintenance_job WHERE night < ?", keepFrom);
            return true;
        }, written -> true);
    }

    List<MaintenanceNight> nights(LocalDate through, int limit) {
        return transactions.autocommit("maintenance nights", connection -> query(connection, "SELECT night,"
                + " MIN(night_opens) AS opens, MAX(night_closes) AS closes,"
                + " COUNT(CASE WHEN kind = 'COMPACTION' AND outcome = 'SUCCEEDED' THEN 1 END) AS tables_compacted,"
                + " COALESCE(SUM(CASE WHEN kind = 'COMPACTION' THEN files_in END), 0) AS files_compacted,"
                + " COALESCE(SUM(CASE WHEN kind = 'COMPACTION' THEN files_out END), 0) AS files_written,"
                + " SUM(snapshots_expired) AS snapshots_expired, SUM(files_deleted) AS files_deleted,"
                + " SUM(bytes_deleted) AS bytes_deleted,"
                + " COUNT(CASE WHEN outcome = 'RUNNING' THEN 1 END) AS running,"
                + " COUNT(CASE WHEN outcome = 'SUCCEEDED' THEN 1 END) AS succeeded,"
                + " COUNT(CASE WHEN outcome = 'CUT_OFF' THEN 1 END) AS cut_off,"
                + " COUNT(CASE WHEN outcome = 'FAILED' THEN 1 END) AS failed"
                + " FROM maintenance_job WHERE night <= ? GROUP BY night ORDER BY night DESC LIMIT ?",
                SqlNights::night, through, limit));
    }

    List<MaintenanceJob> jobs(LocalDate night, Set<MaintenanceJob.Kind> kinds) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        return transactions.autocommit("maintenance jobs", connection -> query(connection, "SELECT " + JOB
                + " FROM maintenance_job WHERE night = ? AND kind = ANY (?) ORDER BY started_at, id", SqlNights::job,
                night, kinds.stream().map(Enum::name).toArray(String[]::new)));
    }

    private static MaintenanceNight night(ResultSet row) throws SQLException {
        return new MaintenanceNight(row.getObject("night", LocalDate.class), instant(row, "opens"),
                instant(row, "closes"), row.getLong("tables_compacted"), row.getLong("files_compacted"),
                row.getLong("files_written"), row.getLong("snapshots_expired"), row.getLong("files_deleted"),
                row.getLong("bytes_deleted"), row.getLong("running"), row.getLong("succeeded"), row.getLong("cut_off"),
                row.getLong("failed"));
    }

    private static MaintenanceJob job(ResultSet row) throws SQLException {
        int number = row.getInt("slot");
        Optional<Integer> slot = row.wasNull() ? Optional.empty() : Optional.of(number);
        return new MaintenanceJob(Jdbc.uuid(row, "id"), row.getString("worker"), row.getObject("night",
                LocalDate.class), instant(row, "night_opens"), instant(row, "night_closes"),
                MaintenanceJob.Kind.valueOf(row.getString("kind")), Jdbc.uuid(row, "object_id"),
                slot, instant(row, "started_at"),
                Optional.ofNullable(instant(row, "ended_at")), MaintenanceJob.Outcome.valueOf(row.getString("outcome")),
                row.getLong("files_in"), row.getLong("files_out"), row.getLong("snapshots_expired"),
                row.getLong("files_deleted"), row.getLong("bytes_deleted"));
    }
}
