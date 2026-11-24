package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.exists;
import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.Change;
import nuna.catalog.store.ColumnMasksJson;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Row filters and column masks (D20, backlog 115). A put and a removal of the table's row/column-policy flag both lock
 * the table's row for update first, so one runs after the other and each reads what the other committed: a rule
 * never lands on a table whose flag a concurrent removal took away.
 */
final class SqlRules {

    private static final String COLUMNS = "table_id, group_name, catalog_id, row_filter, column_masks, set_by, set_at";

    private final Transactions transactions;

    SqlRules(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(RowColumnRule rule, String setBy, Change change) {
        return transactions.change(change, "rule put", connection -> {
            if (!lockTable(connection, rule.tableId(), Optional.of(rule.catalogId()))) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (!exists(connection, "SELECT 1 FROM governance_attribute WHERE target_id = ? AND kind = ? AND value = ?",
                    rule.tableId(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY.name(), "true")) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_FLAGGED);
            }
            Optional<RowColumnRule> was = first(connection, "SELECT " + COLUMNS + " FROM row_column_rule"
                    + " WHERE table_id = ? AND group_name = ?", row -> rule(row).rule(), rule.tableId(), rule.group());
            if (was.isPresent() && was.get().equals(rule)) {
                return WriteResult.UNCHANGED;
            }
            update(connection, "DELETE FROM row_column_rule WHERE table_id = ? AND group_name = ?", rule.tableId(),
                    rule.group());
            update(connection, "INSERT INTO row_column_rule (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    rule.tableId(), rule.group(), rule.catalogId(), rule.rowFilter().orElse(null),
                    ColumnMasksJson.write(rule.columnMasks()), setBy, change.at());
            return WriteResult.APPLIED;
        });
    }

    WriteResult remove(UUID catalogId, UUID tableId, String group, Change change) {
        return transactions.change(change, "rule remove", connection -> {
            int deleted = update(connection, "DELETE FROM row_column_rule WHERE table_id = ? AND group_name = ?"
                    + " AND catalog_id = ?", tableId, group, catalogId);
            return deleted == 1 ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.RULE);
        });
    }

    List<RowColumnRule.Stored> on(UUID tableId) {
        return transactions.autocommit("rule read", connection -> query(connection, "SELECT " + COLUMNS
                + " FROM row_column_rule WHERE table_id = ? ORDER BY group_name", SqlRules::rule, tableId));
    }

    /**
     * Inside a write that would take the row/column-policy flag from a table: locks the table's row for update, as
     * a put does, and answers {@code Refused(HAS_RULES)} if the table has rules. Empty when the write may go on.
     */
    static Optional<WriteResult> flagRemovalRefused(Connection connection, UUID tableId) throws SQLException {
        lockTable(connection, tableId, Optional.empty());
        return exists(connection, "SELECT 1 FROM row_column_rule WHERE table_id = ?", tableId)
                ? Optional.of(new WriteResult.Refused(WriteResult.Reason.HAS_RULES)) : Optional.empty();
    }

    /** Whether the active table exists, in the catalog when one is given; its row is locked for update if so. */
    private static boolean lockTable(Connection connection, UUID tableId, Optional<UUID> catalogId) throws SQLException {
        Optional<UUID> owner = first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ? AND kind = ?"
                + " AND state = 'ACTIVE' FOR UPDATE", row -> Jdbc.uuid(row, "catalog_id"), tableId, "TABLE");
        return owner.isPresent() && catalogId.map(owner.get()::equals).orElse(true);
    }

    private static RowColumnRule.Stored rule(ResultSet row) throws SQLException {
        RowColumnRule rule = new RowColumnRule(Jdbc.uuid(row, "catalog_id"), Jdbc.uuid(row, "table_id"),
                row.getString("group_name"), Optional.ofNullable(row.getString("row_filter")),
                ColumnMasksJson.read(row.getString("column_masks")));
        return new RowColumnRule.Stored(rule, row.getString("set_by"), instant(row, "set_at"));
    }
}
