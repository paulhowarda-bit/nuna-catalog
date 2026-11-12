-- M2a: the journal is partitioned by commit time (D8.5; docs/storage-design.md 9 (g) and the M2
-- prep PostgreSQL deviation 1). PostgreSQL only: YugabyteDB keeps its journal whole and splits
-- its tablets, as CockroachDB splits ranges (section 5.5). The table so far becomes the partition
-- below the cut-over, the first of the next month (UTC). journal_extend creates the monthly
-- partitions from the cut-over on; the store calls it after every migration and a maintenance job
-- keeps twelve months ahead. A default partition takes any commit beyond the horizon, so a commit
-- never fails for want of a partition. Nodes of the previous release keep inserting into
-- "journal": the name now leads to the partitioned table, whose sequence default replaces the
-- identity.

-- The rename takes an ACCESS EXCLUSIVE lock on the journal, held until this step commits: it
-- waits for open commits that wrote the journal, and new ones wait for the step.
ALTER TABLE journal RENAME TO journal_m1;
ALTER INDEX journal_by_pointer RENAME TO journal_m1_by_pointer;
ALTER TABLE journal_m1 ALTER COLUMN seq DROP IDENTITY;

-- seq continues above every number the identity handed out.
CREATE SEQUENCE journal_seq;
SELECT setval('journal_seq', COALESCE(max(seq), 0) + 1, false) FROM journal_m1;

-- No primary key on the partitioned table: it would have to include committed_at, and giving
-- journal_m1 that key rebuilds it. Each partition keeps (commit_id, object_id, branch) as its
-- own key; every row of a batch has the batch's committed_at, so a batch never spans partitions.
CREATE TABLE journal (
    commit_id    UUID        NOT NULL,
    object_id    UUID        NOT NULL,
    branch       TEXT        NOT NULL,
    seq          BIGINT      NOT NULL DEFAULT nextval('journal_seq'),
    committed_at TIMESTAMPTZ NOT NULL,
    principal    TEXT        NOT NULL,
    end_user     TEXT,
    engine       TEXT,
    requirements JSON        NOT NULL,
    updates      JSON        NOT NULL,
    old_location TEXT,
    new_location TEXT        NOT NULL,
    old_version  BIGINT,
    new_version  BIGINT      NOT NULL
) PARTITION BY RANGE (committed_at);
ALTER SEQUENCE journal_seq OWNED BY journal.seq;
CREATE INDEX journal_by_pointer ON journal (object_id, branch, new_version);

-- The cut-over; months from it on get their own partitions.
CREATE TABLE journal_layout (
    singleton BOOLEAN     PRIMARY KEY CHECK (singleton),
    cutover   TIMESTAMPTZ NOT NULL
);
INSERT INTO journal_layout (singleton, cutover)
SELECT true, (date_trunc('month', greatest(now(), COALESCE(max(committed_at), now())) AT TIME ZONE 'UTC')
              + interval '1 month') AT TIME ZONE 'UTC'
FROM journal_m1;

DO $$
BEGIN
    EXECUTE format('ALTER TABLE journal ATTACH PARTITION journal_m1 FOR VALUES FROM (MINVALUE) TO (%L)',
                   (SELECT cutover FROM journal_layout));
END $$;

CREATE TABLE journal_default (LIKE journal INCLUDING DEFAULTS, PRIMARY KEY (commit_id, object_id, branch));
ALTER TABLE journal ATTACH PARTITION journal_default DEFAULT;

-- Attaches a partition for each month from the cut-over through the month holding "through"
-- that has none, moving the month's rows out of the default partition first, and returns how
-- many it attached. Callers serialize on the journal_layout row.
CREATE FUNCTION journal_extend(through TIMESTAMPTZ) RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    month_start TIMESTAMPTZ;
    month_end   TIMESTAMPTZ;
    part        TEXT;
    attached    INTEGER := 0;
BEGIN
    SELECT cutover INTO month_start FROM journal_layout FOR UPDATE;
    WHILE month_start <= through LOOP
        month_end := ((month_start AT TIME ZONE 'UTC') + interval '1 month') AT TIME ZONE 'UTC';
        part := 'journal_p' || to_char(month_start AT TIME ZONE 'UTC', 'YYYYMM');
        IF to_regclass(part) IS NULL THEN
            EXECUTE format('CREATE TABLE %I (LIKE journal INCLUDING DEFAULTS,'
                           ' PRIMARY KEY (commit_id, object_id, branch))', part);
            EXECUTE format('WITH moved AS (DELETE FROM journal_default WHERE committed_at >= %L'
                           ' AND committed_at < %L RETURNING *) INSERT INTO %I SELECT * FROM moved',
                           month_start, month_end, part);
            EXECUTE format('ALTER TABLE journal ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)',
                           part, month_start, month_end);
            attached := attached + 1;
        END IF;
        month_start := month_end;
    END LOOP;
    RETURN attached;
END $$;
