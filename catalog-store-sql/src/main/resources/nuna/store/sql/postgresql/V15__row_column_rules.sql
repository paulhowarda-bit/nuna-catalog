-- D20, backlog 115: each group's row filter and column masks on a table, which nuna serves to trusted engines (Trino's
-- access control) to apply. One row per table and group: the filter, an SQL predicate (null for every row), and the
-- masks, a JSON object from column name to SQL expression ('{}' for none); who set it and when. A purge deletes a
-- table's rules with its grants. The table is new and no server of the previous release reads or writes it, so those
-- servers keep serving while the first server of this release creates it: no table they use is altered or locked.
CREATE TABLE row_column_rule (
    table_id     UUID        NOT NULL,
    group_name   TEXT        NOT NULL,
    catalog_id   UUID        NOT NULL,
    row_filter   TEXT,
    column_masks TEXT        NOT NULL,
    set_by       TEXT        NOT NULL,
    set_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (table_id, group_name)
);
