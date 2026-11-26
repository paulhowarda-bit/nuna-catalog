# 2026-11-25, grants that name a branch (backlog 113)

- A grant of write or modify may name one branch and holds on it alone; a commit on a branch is allowed by such a
  grant or by one on every ref. Branch grants live in `ref_grant` on every store (PostgreSQL V19, CockroachDB V18,
  Oracle V20, MongoDB step 15), read with the others through `any_grant`; `grant_record` is unchanged, so a server of
  the release before works beside a new one. Deleting or merging the branch, dropping its namespace or purging its
  object deletes them.
- Security finding 11 closed for branch-only writers: their vended credentials read the whole table and write only
  beneath `<table>/branches/<ref id>/`, and the load response points `write.data.path` and `write.metadata.path`
  there.
- Grant routes take and show `branch`; a tag answers 400, a gone branch 404, any other privilege 400. The console
  shows "on @<branch> only" and revokes there. `docs/refs.md` describes it.
- Audit: `grant` and `credential-issue` have a version 2 (`branch`, `branchId`; `writePrefix`), written only when
  those are set; every other record is written as version 1, as before, and the codec and the audit tables read both.
- Checked: `:catalog-audit:test` (333), `:catalog-objectstore:test` (208), `:catalog-iceberg:test` (230),
  `:catalog-security:test` (82), the server's grant, vending, ETag, ref-read and telemetry tests (118), `GrantSuite`
  on PostgreSQL and MongoDB. On Oracle and CockroachDB it never started, three times each: with the nuna-demo stack
  up and about 2 GB of Windows memory free, Oracle's container timed out in migration V3 and CockroachDB's container
  did not start; the overnight run runs both with `-Pheavy`. Seven bites in `bites/D33/` and `bites/D23/`, each run with
  `bites/run.sh`: all bite and restore.
- Not covered: `GrantSuite` on Oracle and CockroachDB (above).
  Item 169 (audit records by branch) should fill its `branch` column for a grant that names a branch once both land.
