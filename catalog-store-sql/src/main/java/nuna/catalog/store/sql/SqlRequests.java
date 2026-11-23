package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.Change;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.WriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests to run a table's compaction or orphan scan now (D30, amended 2026-11-20; backlog 114), in
 * {@code maintenance_request}: one row per table and job, waiting until the worker reports the job started. A request
 * share-locks the table, so a drop waits; a purge's completion deletes the table's rows.
 */
final class SqlRequests {

    private static final String COLUMNS = "r.object_id, r.job, r.requested_by, r.requested_at, r.started_at";

    private final Transactions transactions;

    SqlRequests(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult request(MaintenanceRequest request, Change change) {
        return transactions.change(change, "maintenance request", connection -> {
            if (!Jdbc.exists(connection, "SELECT 1 FROM catalog_object WHERE id = ? AND state = 'ACTIVE'"
                    + " AND kind = 'TABLE' FOR SHARE", request.objectId())) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            // A request waiting already is the one asked for; a started one gives way to this.
            int written = update(connection, "INSERT INTO maintenance_request AS r (object_id, job, requested_by,"
                            + " requested_at, started_at) VALUES (?, ?, ?, ?, NULL) ON CONFLICT (object_id, job)"
                            + " DO UPDATE SET requested_by = excluded.requested_by,"
                            + " requested_at = excluded.requested_at, started_at = NULL"
                            + " WHERE r.started_at IS NOT NULL",
                    request.objectId(), request.job().name(), request.requestedBy(), request.requestedAt());
            return written == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        });
    }

    List<MaintenanceRequest> list(UUID objectId) {
        return transactions.autocommit("maintenance requests read", connection -> query(connection, "SELECT "
                + COLUMNS + " FROM maintenance_request r WHERE r.object_id = ? ORDER BY r.job", SqlRequests::request,
                objectId));
    }

    Page<MaintenanceRequest> listWaiting(PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 3, MaintenanceRequest::after);
        return transactions.autocommit("waiting maintenance requests", connection -> {
            List<Object> parameters = new ArrayList<>();
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (r.requested_at, r.object_id, r.job) > (?, ?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<MaintenanceRequest> waiting = query(connection, "SELECT " + COLUMNS + " FROM maintenance_request r"
                    + " JOIN catalog_object o ON o.id = r.object_id WHERE r.started_at IS NULL AND o.state = 'ACTIVE'"
                    + keyset + " ORDER BY r.requested_at, r.object_id, r.job LIMIT ?", SqlRequests::request,
                    parameters.toArray());
            return Jdbc.page(waiting, page, MaintenanceRequest::token);
        });
    }

    WriteResult start(UUID objectId, MaintenanceRequest.Job job, Instant requestedAt, Instant startedAt) {
        return transactions.writeAndRetryOnFailure("maintenance request start", connection -> update(connection,
                "UPDATE maintenance_request SET started_at = ? WHERE object_id = ? AND job = ? AND requested_at = ?"
                        + " AND started_at IS NULL", startedAt, objectId, job.name(), requestedAt) == 0
                ? WriteResult.UNCHANGED : WriteResult.APPLIED, result -> result.equals(WriteResult.APPLIED));
    }

    private static MaintenanceRequest request(ResultSet row) throws SQLException {
        return new MaintenanceRequest(Jdbc.uuid(row, "object_id"), MaintenanceRequest.Job.valueOf(row.getString("job")),
                row.getString("requested_by"), instant(row, "requested_at"),
                Optional.ofNullable(instant(row, "started_at")));
    }
}
