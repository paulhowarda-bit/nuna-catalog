package nuna.catalog.audit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A group's row filter and column masks on a table set or removed (D20, backlog 115): what a trusted engine applies
 * when someone in the group reads it. Set by the grant admin, under D18's separation of duties, as grants are.
 *
 * @param action      set or removed
 * @param table       the table
 * @param group       the IdP group the rule is for
 * @param rowFilter   the predicate rows must meet; null for every row, and on a removal
 * @param columnMasks each masked column's expression, by column name; empty on a removal
 * @param actor       who changed it
 */
public record RowColumnRuleChange(Action action, Securable table, String group, String rowFilter,
                                  Map<String, String> columnMasks, Actor actor) implements EventData {

    public enum Action { SET, REMOVED }

    public RowColumnRuleChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(columnMasks, "columnMasks");
        Objects.requireNonNull(actor, "actor");
        columnMasks = Collections.unmodifiableMap(new TreeMap<>(columnMasks));
    }

    @Override
    public EventType type() {
        return EventType.ROW_COLUMN_RULE_CHANGE;
    }

    @Override
    public String subject() {
        return table.id().toString();
    }
}
