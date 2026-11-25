package nuna.catalog.store.oracle;

import static nuna.catalog.store.oracle.Jdbc.first;
import static nuna.catalog.store.oracle.Jdbc.instant;
import static nuna.catalog.store.oracle.Jdbc.query;
import static nuna.catalog.store.oracle.Jdbc.update;
import static nuna.catalog.store.oracle.OracleGovernance.upsert;

import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.WorkerStorage;
import nuna.catalog.store.WriteResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the maintenance worker reports of its own work, kept for the console (D38): each table's latest assessment and
 * last compaction in {@code table_health}, and the recent scans in {@code maintenance_scan}, each with the worker's
 * groups and the storage it reached as text in CLOBs. The reports carry no change id and write no audit record; each
 * is safe to repeat. Recording locks the table's row (Oracle has no shared row lock), so a drop waits, and a purge's
 * completion deletes its health. A score is bound as a decimal: it has at most four decimals, so the NUMBER holds it
 * exactly.
 */
final class OracleHealth {

    private static final String HEALTH = "h.assessed_at, h.data_files, h.small_files, h.position_delete_files,"
            + " h.deletion_vectors, h.equality_delete_files, h.reasons, h.score, h.compaction_started_at,"
            + " h.compaction_ended_at, h.compaction_outcome";
    private static final String SCAN = "id, worker, started_at, ended_at, outcome, failures, error";
    private static final String SCAN_READ = SCAN + ", worker_groups, worker_storage";

    private final Transactions transactions;

    OracleHealth(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult recordAssessment(UUID objectId, TableAssessment assessment) {
        return transactions.writeAndRetryOnFailure("assessment record", connection -> {
            if (!activeTable(connection, objectId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            int written = upsert(connection, "table_health_pk", "MERGE INTO table_health t USING (SELECT ? object_id,"
                            + " ? assessed_at, ? data_files, ? small_files, ? position_delete_files, ? deletion_vectors,"
                            + " ? equality_delete_files, ? reasons, ? score, ? needed, ? urgency FROM dual) s"
                            + " ON (t.object_id = s.object_id)"
                            + " WHEN MATCHED THEN UPDATE SET t.assessed_at = s.assessed_at, t.data_files = s.data_files,"
                            + " t.small_files = s.small_files, t.position_delete_files = s.position_delete_files,"
                            + " t.deletion_vectors = s.deletion_vectors, t.equality_delete_files = s.equality_delete_files,"
                            + " t.reasons = s.reasons, t.score = s.score, t.needed = s.needed, t.urgency = s.urgency"
                            + " WHERE t.assessed_at IS NULL OR t.assessed_at <= s.assessed_at"
                            + " WHEN NOT MATCHED THEN INSERT (object_id, assessed_at, data_files, small_files,"
                            + " position_delete_files, deletion_vectors, equality_delete_files, reasons, score, needed,"
                            + " urgency) VALUES (s.object_id, s.assessed_at, s.data_files, s.small_files,"
                            + " s.position_delete_files, s.deletion_vectors, s.equality_delete_files, s.reasons, s.score,"
                            + " s.needed, s.urgency)",
                    objectId, assessment.assessedAt(), assessment.dataFiles(), assessment.smallFiles(),
                    assessment.positionDeleteFiles(), assessment.deletionVectors(), assessment.equalityDeleteFiles(),
                    String.join(",", assessment.reasons().stream().sorted().toList()),
                    BigDecimal.valueOf(assessment.score()), assessment.needsCompaction() ? 1 : 0,
                    TableHealth.Rank.urgency(assessment));
            return written == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        }, result -> result.equals(WriteResult.APPLIED));
    }

    WriteResult recordCompaction(UUID objectId, CompactionRun compaction) {
        return transactions.writeAndRetryOnFailure("compaction record", connection -> {
            if (!activeTable(connection, objectId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            // A later compaction replaces this one; the same one replaces it only while it was running.
            int written = upsert(connection, "table_health_pk", "MERGE INTO table_health t USING (SELECT ? object_id,"
                            + " ? started_at, CAST(? AS TIMESTAMP WITH TIME ZONE) ended_at, ? outcome FROM dual) s"
                            + " ON (t.object_id = s.object_id)"
                            + " WHEN MATCHED THEN UPDATE SET t.compaction_started_at = s.started_at,"
                            + " t.compaction_ended_at = s.ended_at, t.compaction_outcome = s.outcome"
                            + " WHERE t.compaction_started_at IS NULL OR t.compaction_started_at < s.started_at"
                            + " OR (t.compaction_started_at = s.started_at AND t.compaction_ended_at IS NULL)"
                            + " WHEN NOT MATCHED THEN INSERT (object_id, compaction_started_at, compaction_ended_at,"
                            + " compaction_outcome) VALUES (s.object_id, s.started_at, s.ended_at, s.outcome)",
                    objectId, compaction.startedAt(), compaction.endedAt().orElse(null), compaction.outcome().name());
            return written == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        }, result -> result.equals(WriteResult.APPLIED));
    }

    Optional<TableHealth> read(UUID objectId) {
        return transactions.autocommit("table health read", connection -> first(connection, "SELECT "
                + OracleObjects.COLUMNS + ", " + HEALTH + " FROM table_health h JOIN catalog_object o"
                + " ON o.id = h.object_id WHERE h.object_id = ?", OracleHealth::health, objectId));
    }

    Page<TableHealth> listNeediest(PageRequest page) {
        Optional<TableHealth.Rank> after = Jdbc.after(page, 3, TableHealth.Rank::parse);
        return transactions.autocommit("neediest tables", connection -> {
            List<Object> parameters = new ArrayList<>();
            String keyset = "";
            if (after.isPresent()) {
                TableHealth.Rank rank = after.get();
                BigDecimal score = BigDecimal.valueOf(rank.score());
                keyset = " AND (h.urgency > ? OR (h.urgency = ? AND (h.score < ? OR (h.score = ?"
                        + " AND h.object_id > ?))))";
                parameters.addAll(List.of(rank.urgency(), rank.urgency(), score, score, rank.objectId()));
            }
            parameters.add(page.size() + 1);
            List<TableHealth> tables = query(connection, "SELECT " + OracleObjects.COLUMNS + ", " + HEALTH
                    + " FROM table_health h JOIN catalog_object o ON o.id = h.object_id"
                    + " WHERE h.needed = 1 AND o.state = 'ACTIVE'" + keyset
                    + " ORDER BY h.urgency, h.score DESC, h.object_id FETCH FIRST ? ROWS ONLY", OracleHealth::health,
                    parameters.toArray());
            return Jdbc.page(tables, page, table -> table.rank().token());
        });
    }

    void recordScan(MaintenanceScan scan, int keep) {
        transactions.writeAndRetryOnFailure("scan record", connection -> {
            int written = upsert(connection, "maintenance_scan_pk", "MERGE INTO maintenance_scan t USING (SELECT"
                            + " ? id, ? worker, ? started_at, CAST(? AS TIMESTAMP WITH TIME ZONE) ended_at, ? outcome,"
                            + " ? failures, ? error FROM dual) s ON (t.id = s.id)"
                            + " WHEN MATCHED THEN UPDATE SET t.ended_at = s.ended_at, t.outcome = s.outcome,"
                            + " t.failures = s.failures, t.error = s.error WHERE t.ended_at IS NULL"
                            + " WHEN NOT MATCHED THEN INSERT (" + SCAN + ") VALUES (s.id, s.worker, s.started_at,"
                            + " s.ended_at, s.outcome, s.failures, s.error)",
                    scan.id(), scan.worker(), scan.startedAt(), scan.endedAt().orElse(null),
                    scan.outcome().map(Enum::name).orElse(null), failures(scan.failures()), scan.error().orElse(null));
            if (written > 0) {
                // The CLOBs on their own: a long text bound in MERGE's USING select is not a CLOB there.
                update(connection, "UPDATE maintenance_scan SET worker_groups = ?, worker_storage = ? WHERE id = ?",
                        scan.groups().map(MaintenanceScan::writeGroups).orElse(null),
                        scan.storage().map(WorkerStorage::write).orElse(null), scan.id());
            }
            update(connection, "DELETE FROM maintenance_scan WHERE id NOT IN (SELECT id FROM maintenance_scan"
                    + " ORDER BY started_at DESC, id DESC FETCH FIRST ? ROWS ONLY) AND id NOT IN (SELECT id"
                    + " FROM maintenance_scan WHERE outcome = 'SUCCEEDED' ORDER BY started_at DESC, id DESC"
                    + " FETCH FIRST 1 ROW ONLY)", keep);
            return true;
        }, written -> true);
    }

    List<MaintenanceScan> listScans() {
        return transactions.autocommit("scan listing", connection -> query(connection, "SELECT " + SCAN_READ
                + " FROM maintenance_scan ORDER BY started_at DESC, id DESC", OracleHealth::scan));
    }

    /** Whether the object is an active table, locked so a drop waits until this write is done. */
    private static boolean activeTable(Connection connection, UUID objectId) throws SQLException {
        return Jdbc.exists(connection, "SELECT 1 FROM catalog_object WHERE id = ? AND state = 'ACTIVE'"
                + " AND kind = 'TABLE' FOR UPDATE", objectId);
    }

    private static TableHealth health(ResultSet row) throws SQLException {
        Optional<TableAssessment> assessment = Optional.empty();
        Instant assessed = instant(row, "assessed_at");
        if (assessed != null) {
            assessment = Optional.of(new TableAssessment(assessed, row.getLong("data_files"), row.getLong("small_files"),
                    row.getLong("position_delete_files"), row.getLong("deletion_vectors"),
                    row.getLong("equality_delete_files"), reasons(Jdbc.text(row, "reasons")),
                    row.getBigDecimal("score").doubleValue()));
        }
        Optional<CompactionRun> compaction = Optional.empty();
        Instant started = instant(row, "compaction_started_at");
        if (started != null) {
            compaction = Optional.of(new CompactionRun(started, Optional.ofNullable(instant(row, "compaction_ended_at")),
                    CompactionRun.Outcome.valueOf(row.getString("compaction_outcome"))));
        }
        return new TableHealth(OracleObjects.object(row), assessment, compaction);
    }

    private static MaintenanceScan scan(ResultSet row) throws SQLException {
        return new MaintenanceScan(Jdbc.uuid(row, "id"), Jdbc.text(row, "worker"), instant(row, "started_at"),
                Optional.ofNullable(instant(row, "ended_at")),
                Optional.ofNullable(row.getString("outcome")).map(MaintenanceScan.Outcome::valueOf),
                failures(Jdbc.text(row, "failures")), Optional.ofNullable(Jdbc.text(row, "error")),
                Optional.ofNullable(Jdbc.text(row, "worker_groups")).map(MaintenanceScan::readGroups),
                Optional.ofNullable(Jdbc.text(row, "worker_storage")).map(WorkerStorage::read));
    }

    private static Set<String> reasons(String stored) {
        return stored == null || stored.isEmpty() ? Set.of() : Set.of(stored.split(","));
    }

    /** Failures by kind as {@code kind=count,...}: a kind is a label, never holding {@code =} or {@code ,}. */
    private static String failures(Map<String, Integer> failures) {
        return failures.entrySet().stream().map(failure -> failure.getKey() + "=" + failure.getValue())
                .collect(Collectors.joining(","));
    }

    private static Map<String, Integer> failures(String stored) {
        Map<String, Integer> failures = new LinkedHashMap<>();
        if (stored != null && !stored.isEmpty()) {
            Arrays.stream(stored.split(",")).map(failure -> failure.split("=", 2))
                    .forEach(failure -> failures.put(failure[0], Integer.parseInt(failure[1])));
        }
        return failures;
    }
}
