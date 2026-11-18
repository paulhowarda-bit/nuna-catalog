# 2026-11-15, the first overnight run

Run by hand at 06:14 on Paul's word (`overnight-task.ps1 -Force` from a one-off Task Scheduler task, deadline 12:00
instead of 06:30), on `main` at 071d2cc. Ended 09:50, 216 min. Logs: `C:\Users\paulh\code\nuna-overnight\runs\2026-11-15`.

| Leg | Result | Time |
|---|---|---|
| Heavy build (`-Pheavy`) | passed | 25 min |
| Ceph leg | FAILED: Testcontainers found no Docker (backlog 32) | 0 min |
| Nightly (`./nightly.sh`) | passed, both steps | 65 min |
| Bites | 218 of 218 ran, 216 bite; the rotation is back at its start | 113 min |

- Ceph leg: 15 of `catalog-maintenance`'s 20 tests failed with "Could not find a valid Docker environment", the rest
  with "Previous attempts to find a Docker environment failed", 39 s after the heavy build ended. Backlog 32.
- Bites not biting: `D7/oracle-backend-runs-sql-store` and `M8/store-skips-postgresql-steps`, each
  `SETUP: 1 of 1 failed in setup`, the two of backlog 30. The Oracle one ran for the first time: its context fails
  to start, as item 30 predicted.
- Nightly counts (tests, none skipped or failed): catalog-audit 0, catalog-core 0, catalog-iceberg 0,
  catalog-maintenance 2, catalog-objectstore 0, catalog-security 0, catalog-server 0, catalog-store-api 0,
  catalog-store-mongo 43, catalog-store-oracle 43, catalog-store-sql 43, catalog-store-sql-raft 43,
  catalog-tests-conformance 0, catalog-tests-engines 5, catalog-tests-load 11, catalog-tests-s3 0,
  singlestore-change-feed 0. The engine matrix ran Spark's `replace.required` and `replace.retype` lines. Backlog 2
  is done.
- nuna-demo was up; the run took it down at 06:14 and rebuilt it with `demo/demo.sh up` at 09:50, which worked.

Not covered: the Ceph leg (backlog 32). The midnight start from Task Scheduler has not run yet; this run went through
the same `overnight-task.ps1`, started by a one-off task.
