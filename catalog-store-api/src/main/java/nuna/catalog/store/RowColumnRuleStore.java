package nuna.catalog.store;

import java.util.List;
import java.util.OptionalLong;
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
     * Puts the group's rule on the table, replacing the one it had, at {@link RowColumnRule#nextVersion}.
     *
     * @param expected the version the group's rule must be at (If-Match, backlog 244); empty for whatever it is, or
     *                 none
     * @return {@code Applied}; {@code Unchanged} when the group's rule was already this one; {@code Missing(OBJECT)}
     *         when the table is not an active table of the catalog; {@code Refused(NOT_FLAGGED)} when the table
     *         does not carry the row/column-policy flag set to true; or {@code VersionMismatch} when a version is
     *         expected and the rule is at another, carrying it, or 0 when the group has no rule
     */
    WriteResult putRule(RowColumnRule rule, OptionalLong expected, String setBy, Change change);

    /**
     * @param expected the version the rule must be at (If-Match, backlog 244); empty for whatever it is
     * @return {@code Applied}, {@code Missing(RULE)}, or {@code VersionMismatch} carrying the rule's version when one
     *         is expected and the rule is at another
     */
    WriteResult removeRule(UUID catalogId, UUID tableId, String group, OptionalLong expected, Change change);

    /** Every rule on the table, by group. */
    List<RowColumnRule.Stored> rulesOn(UUID tableId);
}
