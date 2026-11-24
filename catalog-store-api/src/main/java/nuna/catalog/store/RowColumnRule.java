package nuna.catalog.store;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A group's row filter and column masks on a table (D20, backlog 115): what a trusted engine applies when someone in
 * the group reads it. The filter is an SQL predicate the rows must meet, empty for every row; each mask is an SQL
 * expression that stands in for the column it names. The store keeps both as given; what they mean is the engine's,
 * and how a user's several rules combine is the core's ({@code RowColumnPolicy} in catalog-security).
 *
 * @param catalogId   the table's catalog
 * @param tableId     the table
 * @param group       the group the rule is for, never a person (D18, D19)
 * @param rowFilter   the predicate a row must meet; empty for every row
 * @param columnMasks each masked column's expression, by column name
 */
public record RowColumnRule(UUID catalogId, UUID tableId, String group, Optional<String> rowFilter,
                           SortedMap<String, String> columnMasks) {

    public RowColumnRule {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(rowFilter, "rowFilter");
        Objects.requireNonNull(columnMasks, "columnMasks");
        columnMasks = Collections.unmodifiableSortedMap(new TreeMap<>(columnMasks));
        if (rowFilter.isEmpty() && columnMasks.isEmpty()) {
            throw new IllegalArgumentException("a rule filters rows, masks columns, or both");
        }
    }

    public static RowColumnRule of(UUID catalogId, UUID tableId, String group, Optional<String> rowFilter,
                                   Map<String, String> columnMasks) {
        return new RowColumnRule(catalogId, tableId, group, rowFilter, new TreeMap<>(columnMasks));
    }

    /** A stored rule: who set it and when. */
    public record Stored(RowColumnRule rule, String setBy, Instant setAt) {

        public Stored {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(setBy, "setBy");
            Objects.requireNonNull(setAt, "setAt");
        }
    }
}
