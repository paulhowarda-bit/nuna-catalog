package nuna.catalog.server.security;

import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.security.RuleColumns;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.RowColumnRule;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A schema change never takes away a column a row filter or column mask names (D20, backlog 240): a commit whose
 * current schema after it lacks a column, ignoring case, that its current schema before it had and that a rule on the
 * table names is refused, naming each such rule's group, and audited as a denial with reason {@code row_column_rule}
 * (D18). A mask is served by its column's name, so a rename it let through would serve the column unmasked under its
 * new name; a filter would fail every query of its group. The commit engine judges commits on a branch and merges
 * with the same rules, so neither gets round it.
 *
 * <p>The rules are read before the commit's swap, not inside it, so a rule set in the same instant as the rename could
 * still pass; Trino's answer then fails closed ({@link OpaAnswers}).
 */
@Component
public class RuleColumnGuard implements CommitRule {

    static final String REASON = "row_column_rule";

    private final CatalogStore store;

    public RuleColumnGuard(CatalogStore store) {
        this.store = store;
    }

    @Override
    public Optional<Rejection> judge(Change change) {
        if (!(change.before() instanceof TableFacts before) || !(change.after() instanceof TableFacts after)
                || after.columns().containsAll(before.columns())) {
            return Optional.empty();
        }
        return RuleColumns.taken(before.columns(), after.columns(),
                        store.rulesOn(change.object().id()).stream().map(RowColumnRule.Stored::rule).toList())
                .map(message -> Rejection.refused(REASON, message));
    }
}
