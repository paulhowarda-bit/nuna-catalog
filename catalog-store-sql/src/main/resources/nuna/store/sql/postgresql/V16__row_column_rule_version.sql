-- Backlog 244: each rule carries a version, which the list gives and a set or removal may name in If-Match, so that
-- two grant admins editing one rule cannot lose an edit unseen. A set writes the time of the write in microseconds
-- since 1970, or one past the rule's version when the clock is behind it, so a rule removed and set again never comes
-- back at a version a client read before. NULL reads as version 1: a rule stored before this step, or written by a
-- server of the previous release, which does not name the column. A nullable column with no default changes only the
-- table's definition; those servers' reads, puts and removals keep working.
ALTER TABLE row_column_rule ADD COLUMN rule_version BIGINT;
