package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.CompactionRun;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import nuna.catalog.store.WriteResult;
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
 * last compaction in {@code table_health}, and the recent scans in {@code maintenance_scan}. The reports carry no
 * change id and write no audit record; each is safe to repeat. Recording share-locks the table, so a drop waits, and a
 * purge's completion deletes its row.
 */
final class SqlHealth {

    private static final String HEALTH = "h.assessed_at, h.data_files, h.small_files, h.position_delete_files,"
            + " h.deletion_vectors, h.equality_delete_files, h.reasons, h.score, h.compaction_started_at,"
            + " h.compaction_ended_at, h.compaction_outcome";
    private static final String SCAN = "id, worker, started_at, ended_at, outcome, failures, error";

    private final Transactions transactions;

    SqlHealth(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult recordAssessment(UUID objectId, TableAssessment assessment) {
        return transactions.writeAndRetryOnFailure("assessment record", connection -> {
            if (!activeTable(connection, objectId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            int written = update(connection, "INSERT INTO table_health AS h (object_id, assessed_at, data_files,"
                            + " small_files, position_delete_files, deletion_vectors, equality_delete_files, reasons, score,"
                            + " needed, urgency) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (object_id) DO UPDATE"
                            + " SET assessed_at = excluded.assessed_at, data_files = excluded.data_files,"
                            + " small_files = excluded.small_files, position_delete_files = excluded.position_delete_files,"
                            + " deletion_vectors = excluded.deletion_vectors,"
                            + " equality_delete_files = excluded.equality_delete_files, reasons = excluded.reasons,"
                            + " score = excluded.score, needed = excluded.needed, urgency = excluded.urgency"
                            + " WHERE h.assessed_at IS NULL OR h.assessed_at <= excluded.assessed_at",
                    objectId, assessment.assessedAt(), assessment.dataFiles(), assessment.smallFiles(),
                    assessment.positionDeleteFiles(), assessment.deletionVectors(), assessment.equalityDeleteFiles(),
                    String.join(",", assessment.reasons().stream().sorted().toList()), assessment.score(),
                    assessment.needsCompaction(), (short) TableHealth.Rank.urgency(assessment));
            return written == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        }, result -> result.equals(WriteResult.APPLIED));
    }

    WriteResult recordCompaction(UUID objectId, CompactionRun compaction) {
        return transactions.writeAndRetryOnFailure("compaction record", connection -> {
            if (!activeTable(connection, objectId)) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            // A later compaction replaces this one; the same one replaces it only while it was running.
            int written = update(connection, "INSERT INTO table_health AS h (object_id, compaction_started_at,"
                            + " compaction_ended_at, compaction_outcome) VALUES (?, ?, ?, ?) ON CONFLICT (object_id)"
                            + " DO UPDATE SET compaction_started_at = excluded.compaction_started_at,"
                            + " compaction_ended_at = excluded.compaction_ended_at,"
                            + " compaction_outcome = excluded.compaction_outcome"
                            + " WHERE h.compaction_started_at IS NULL"
                            + " OR h.compaction_started_at < excluded.compaction_started_at"
                            + " OR (h.compaction_started_at = excluded.compaction_started_at"
                            + " AND h.compaction_ended_at IS NULL)",
                    objectId, compaction.startedAt(), compaction.endedAt().orElse(null), compaction.outcome().name());
            return written == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        }, result -> result.equals(WriteResult.APPLIED));
    }

    Optional<TableHealth> read(UUID objectId) {
        return transactions.autocommit("table health read", connection -> first(connection, "SELECT "
                + SqlObjects.COLUMNS + ", " + HEALTH + " FROM table_health h JOIN catalog_object o ON o.id = h.object_id"
                + " WHERE h.object_id = ?", SqlHealth::health, objectId));
    }

    Page<TableHealth> listNeediest(PageRequest page) {
        Optional<TableHealth.Rank> after = Jdbc.after(page, 3, TableHealth.Rank::parse);
        return transactions.autocommit("neediest tables", connection -> {
            List<Object> parameters = new ArrayList<>();
            String keyset = "";
            if (after.isPresent()) {
                TableHealth.Rank rank = after.get();
                keyset = " AND (h.urgency > ? OR (h.urgency = ? AND (h.score < ? OR (h.score = ?"
                        + " AND h.object_id > ?))))";
                parameters.addAll(List.of(rank.urgency(), rank.urgency(), rank.score(), rank.score(), rank.objectId()));
            }
            parameters.add(page.size() + 1);
            List<TableHealth> tables = query(connection, "SELECT " + SqlObjects.COLUMNS + ", " + HEALTH
                    + " FROM table_health h JOIN catalog_object o ON o.id = h.object_id"
                    + " WHERE h.needed AND o.state = 'ACTIVE'" + keyset
                    + " ORDER BY h.urgency, h.score DESC, h.object_id LIMIT ?", SqlHealth::health, parameters.toArray());
            return Jdbc.page(tables, page, table -> table.rank().token());
        });
    }

    void recordScan(MaintenanceScan scan, int keep) {
        transactions.writeAndRetryOnFailure("scan record", connection -> {
            update(connection, "INSERT INTO maintenance_scan AS s (" + SCAN + ") VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (id) DO UPDATE SET ended_at = excluded.ended_at, outcome = excluded.outcome,"
                            + " failures = excluded.failures, error = excluded.error WHERE s.ended_at IS NULL",
                    scan.id(), scan.worker(), scan.startedAt(), scan.endedAt().orElse(null),
                    scan.outcome().map(Enum::name).orElse(null), failures(scan.failures()), scan.error().orElse(null));
            update(connection, "DELETE FROM maintenance_scan WHERE id NOT IN (SELECT id FROM maintenance_scan"
                    + " ORDER BY started_at DESC, id DESC LIMIT ?) AND id NOT IN (SELECT id FROM maintenance_scan"
                    + " WHERE outcome = 'SUCCEEDED' ORDER BY started_at DESC, id DESC LIMIT 1)", keep);
            return true;
        }, written -> true);
    }

    List<MaintenanceScan> listScans() {
        return transactions.autocommit("scan listing", connection -> query(connection, "SELECT " + SCAN
                + " FROM maintenance_scan ORDER BY started_at DESC, id DESC", SqlHealth::scan));
    }

    /** Whether the object is an active table, share-locked so a drop waits until this write is done. */
    private static boolean activeTable(Connection connection, UUID objectId) throws SQLException {
        return Jdbc.exists(connection, "SELECT 1 FROM catalog_object WHERE id = ? AND state = 'ACTIVE'"
                + " AND kind = 'TABLE' FOR SHARE", objectId);
    }

    private static TableHealth health(ResultSet row) throws SQLException {
        Optional<TableAssessment> assessment = Optional.empty();
        Instant assessed = instant(row, "assessed_at");
        if (assessed != null) {
            assessment = Optional.of(new TableAssessment(assessed, row.getLong("data_files"), row.getLong("small_files"),
                    row.getLong("position_delete_files"), row.getLong("deletion_vectors"),
                    row.getLong("equality_delete_files"), reasons(row.getString("reasons")), row.getDouble("score")));
        }
        Optional<CompactionRun> compaction = Optional.empty();
        Instant started = instant(row, "compaction_started_at");
        if (started != null) {
            compaction = Optional.of(new CompactionRun(started, Optional.ofNullable(instant(row, "compaction_ended_at")),
                    CompactionRun.Outcome.valueOf(row.getString("compaction_outcome"))));
        }
        return new TableHealth(SqlObjects.object(row), assessment, compaction);
    }

    private static MaintenanceScan scan(ResultSet row) throws SQLException {
        return new MaintenanceScan(Jdbc.uuid(row, "id"), row.getString("worker"), instant(row, "started_at"),
                Optional.ofNullable(instant(row, "ended_at")),
                Optional.ofNullable(row.getString("outcome")).map(MaintenanceScan.Outcome::valueOf),
                failures(row.getString("failures")), Optional.ofNullable(row.getString("error")));
    }

    static Set<String> reasons(String stored) {
        return stored == null || stored.isEmpty() ? Set.of() : Set.of(stored.split(","));
    }

    /** Failures by kind as {@code kind=count,...}: a kind is a label, never holding {@code =} or {@code ,}. */
    static String failures(Map<String, Integer> failures) {
        return failures.entrySet().stream().map(failure -> failure.getKey() + "=" + failure.getValue())
                .collect(Collectors.joining(","));
    }

    static Map<String, Integer> failures(String stored) {
        Map<String, Integer> failures = new LinkedHashMap<>();
        if (stored != null && !stored.isEmpty()) {
            Arrays.stream(stored.split(",")).map(failure -> failure.split("=", 2))
                    .forEach(failure -> failures.put(failure[0], Integer.parseInt(failure[1])));
        }
        return failures;
    }
}
