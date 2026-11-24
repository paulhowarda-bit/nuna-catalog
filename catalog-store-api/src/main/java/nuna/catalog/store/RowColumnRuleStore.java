package nuna.catalog.store;

import java.util.List;
import java.util.UUID;

/**
 * Row filters and column masks, one rule per table and group (D20, backlog 115). Writes behave as in
 * {@link NamespaceStore}. A rule needs the table's row/column-policy flag: a put checks it under the table's lock, and
 * {@link GovernanceStore#removeAttribute} and {@link GovernanceStore#putAttribute} refuse to take the flag away while
 * the table has rules, so no rule ever sits on a table whose files a client that is no trusted engine may read. A
 * purge deletes the table's rules with its grants.
 */
public interface RowColumnRuleStore {

    /**
     * Puts the group's rule on the table, replacing the one it had.
     *
     * @return {@code Applied}; {@code Unchanged} when the group's rule was already this one; {@code Missing(OBJECT)}
     *         when the table is not an active table of the catalog; or {@code Refused(NOT_FLAGGED)} when the table
     *         does not carry the row/column-policy flag set to true
     */
    WriteResult putRule(RowColumnRule rule, String setBy, Change change);

    /** @return {@code Applied} or {@code Missing(RULE)} */
    WriteResult removeRule(UUID catalogId, UUID tableId, String group, Change change);

    /** Every rule on the table, by group. */
    List<RowColumnRule.Stored> rulesOn(UUID tableId);
}
