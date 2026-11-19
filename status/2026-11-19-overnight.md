# 2026-11-19, overnight run

Recorded on 2026-11-19 by the overnight-1119-upgrade branch, at Paul's word: the 07:00 report left no record of it.
The PC was in standby from 18:19 on 2026-11-18 until 07:08, so the task started at 07:08:55 instead of midnight
(item 66) and the run ended at 09:22, after the report had looked.

Tested d34547f (last tested 2dee958), 07:15 to 09:22, the first run after the rename to nuna. The nuna-demo stack was
running: taken down for the run, and rebuilt with `demo/demo.sh up` at 09:22, which worked.

- Heavy build: FAILED, 22 min. One test, the worker's `TelemetryTest#theStandardVariablesAloneTurnExportOn` (no
  metric within 20 s, item 62 again). The heavy build stops at the first failing module, so only seven modules' tests
  ran.
- Ceph leg: passed, 7 min.
- Nightly: FAILED, 88 min. Step 1 failed on one test, `RollingUpgradeTest#upgradingOneServerAtATimeAcrossV5ToV8LosesNothing`
  (the previous release would not start: it reads `keel.` settings and was given `nuna.` ones since the rename; item
  65); step 2, the engine matrix, passed. Counts: catalog-maintenance 2, catalog-store-mongo 43,
  catalog-store-oracle 43, catalog-store-sql 43, catalog-store-sql-raft 43, catalog-tests-engines 5,
  catalog-tests-load 11 (1 failed), every other module 0. The CAS test passed on all four backends (MongoDB 63 s,
  Oracle 91 s, PostgreSQL 156 s, CockroachDB 391 s).
- Bites: not run, since the heavy build did not pass. The rotation did not move.

Logs: `.overnight\runs\2026-11-19` in the main checkout.
