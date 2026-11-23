package nuna.catalog.store.oracle;

import static nuna.catalog.store.oracle.Jdbc.instant;
import static nuna.catalog.store.oracle.Jdbc.query;
import static nuna.catalog.store.oracle.Jdbc.update;
import static nuna.catalog.store.oracle.OracleGovernance.upsert;

import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The jobs of the maintenance worker's nights, kept for the console (D45, backlog 126) in {@code maintenance_job}, and
 * each night's totals, summed here. A report carries no change id and writes no audit record; it is safe to repeat.
 * Two first reports of one job at once both miss the row; the MERGE that loses repeats and finds it.
 */
final class OracleNights {

    private static final String JOB = "id, worker, night, night_opens, night_closes, kind, object_id, slot, started_at,"
            + " ended_at, outcome, files_in, files_out, snapshots_expired, files_deleted, bytes_deleted";

    private final Transactions transactions;

    OracleNights(Transactions transactions) {
        this.transactions = transactions;
    }

    void record(MaintenanceJob job, LocalDate keepFrom) {
        transactions.writeAndRetryOnFailure("maintenance job record", connection -> {
            // MaintenanceJob.replaces: unless the row kept ended later, or has ended and this report has not.
            upsert(connection, "maintenance_job_pk", "MERGE INTO maintenance_job t USING (SELECT ? id, ? worker,"
                            + " ? night, ? night_opens, ? night_closes, ? kind, ? object_id, CAST(? AS NUMBER(10)) slot,"
                            + " ? started_at, CAST(? AS TIMESTAMP WITH TIME ZONE) ended_at, ? outcome, ? files_in,"
                            + " ? files_out, ? snapshots_expired, ? files_deleted, ? bytes_deleted FROM dual) s"
                            + " ON (t.id = s.id)"
                            + " WHEN MATCHED THEN UPDATE SET t.worker = s.worker, t.night_opens = s.night_opens,"
                            + " t.night_closes = s.night_closes, t.slot = s.slot,"
                            + " t.started_at = LEAST(t.started_at, s.started_at), t.ended_at = s.ended_at,"
                            + " t.outcome = s.outcome, t.files_in = s.files_in, t.files_out = s.files_out,"
                            + " t.snapshots_expired = s.snapshots_expired, t.files_deleted = s.files_deleted,"
                            + " t.bytes_deleted = s.bytes_deleted"
                            + " WHERE t.ended_at IS NULL OR s.ended_at >= t.ended_at"
                            + " WHEN NOT MATCHED THEN INSERT (" + JOB + ") VALUES (s.id, s.worker, s.night,"
                            + " s.night_opens, s.night_closes, s.kind, s.object_id, s.slot, s.started_at, s.ended_at,"
                            + " s.outcome, s.files_in, s.files_out, s.snapshots_expired, s.files_deleted,"
                            + " s.bytes_deleted)",
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
                + " FROM maintenance_job WHERE night <= ? GROUP BY night ORDER BY night DESC FETCH FIRST ? ROWS ONLY",
                OracleNights::night, through, limit));
    }

    List<MaintenanceJob> jobs(LocalDate night, Set<MaintenanceJob.Kind> kinds) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(night);
        String kind = Jdbc.in("kind", kinds.stream().map(Enum::name).toList(), parameters);
        return transactions.autocommit("maintenance jobs", connection -> query(connection, "SELECT " + JOB
                + " FROM maintenance_job WHERE night = ? AND " + kind + " ORDER BY started_at, id", OracleNights::job,
                parameters.toArray()));
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
        return new MaintenanceJob(Jdbc.uuid(row, "id"), Jdbc.text(row, "worker"),
                row.getObject("night", LocalDate.class), instant(row, "night_opens"), instant(row, "night_closes"),
                MaintenanceJob.Kind.valueOf(row.getString("kind")), Jdbc.uuid(row, "object_id"), slot,
                instant(row, "started_at"), Optional.ofNullable(instant(row, "ended_at")),
                MaintenanceJob.Outcome.valueOf(row.getString("outcome")), row.getLong("files_in"),
                row.getLong("files_out"), row.getLong("snapshots_expired"), row.getLong("files_deleted"),
                row.getLong("bytes_deleted"));
    }
}
