package nuna.catalog.security;

import nuna.catalog.store.RowColumnRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a table's row filters and column masks mean for one user (D20, backlog 115): the filter and masks a trusted
 * engine applies when they read it. Grants add up and there is no DENY (D18), so rules add up too:
 * <ul>
 *     <li>a table with no rules filters and masks nothing;</li>
 *     <li>a table with rules, none of them for the user's groups, gives them no rows ({@code false}): fail closed;</li>
 *     <li>the user sees the rows any of their groups' filters admit: the filters joined with OR, and a rule of theirs
 *     with no filter admits every row;</li>
 *     <li>a column is unmasked if any of their rules leaves it unmasked, masked with the one expression if all their
 *     rules mask it the same way, and {@code NULL} if their masks of it differ.</li>
 * </ul>
 */
public final class RowColumnPolicy {

    /** The filter for a table with rules but none for the user: no row. */
    public static final String NO_ROWS = "false";
    /** The mask of a column the user's rules mask in different ways. */
    public static final String NO_VALUE = "NULL";

    private RowColumnPolicy() {
    }

    /**
     * @param rowFilter   the predicate the user's rows must meet; empty for every row
     * @param columnMasks each masked column's expression, by column name
     */
    public record Applied(Optional<String> rowFilter, SortedMap<String, String> columnMasks) {

        public static final Applied NOTHING = new Applied(Optional.empty(), new TreeMap<>());
    }

    /** The filter and masks for a user in {@code groups}, given every rule on the table. */
    public static Applied forUser(Collection<RowColumnRule> rules, Set<String> groups) {
        if (rules.isEmpty()) {
            return Applied.NOTHING;
        }
        List<RowColumnRule> theirs = rules.stream().filter(rule -> groups.contains(rule.group()))
                .sorted((a, b) -> a.group().compareTo(b.group())).toList();
        if (theirs.isEmpty()) {
            return new Applied(Optional.of(NO_ROWS), new TreeMap<>());
        }
        Optional<String> filter;
        if (theirs.stream().anyMatch(rule -> rule.rowFilter().isEmpty())) {
            filter = Optional.empty();
        } else if (theirs.size() == 1) {
            filter = theirs.getFirst().rowFilter();
        } else {
            List<String> each = new ArrayList<>();
            theirs.forEach(rule -> each.add("(" + rule.rowFilter().orElseThrow() + ")"));
            filter = Optional.of(String.join(" OR ", each));
        }
        SortedMap<String, String> masks = new TreeMap<>();
        Set<String> columns = new TreeSet<>();
        theirs.forEach(rule -> columns.addAll(rule.columnMasks().keySet()));
        for (String column : columns) {
            Set<String> ways = new TreeSet<>();
            boolean unmasked = false;
            for (RowColumnRule rule : theirs) {
                Map<String, String> ruleMasks = rule.columnMasks();
                if (!ruleMasks.containsKey(column)) {
                    unmasked = true;
                    break;
                }
                ways.add(ruleMasks.get(column));
            }
            if (!unmasked) {
                masks.put(column, ways.size() == 1 ? ways.iterator().next() : NO_VALUE);
            }
        }
        return new Applied(filter, masks);
    }
}
