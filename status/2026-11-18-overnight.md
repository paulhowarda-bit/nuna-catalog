# 2026-11-18, overnight run

Recorded on 2026-11-18 by the rename-nuna branch, at Paul's word: the 07:00 report left no record of it (no status
entry, no backlog items, and no run of that day in the task's run list). The run predates the rename, so its logs and test names say keel.

Tested 2dee958 (last tested 30bab53), 00:00 to 01:46. The keel-demo stack was running: taken down for the run, and
rebuilt with `demo/demo.sh up` at 01:46, which worked.

- Heavy build: FAILED, 21 min. Three tests: `CredentialExpiryTest#noNotModifiedNearExpiryAndANewEpochIsANewETag`
  (401 on its first request, item 60), `KubernetesCompactionTest#compactionRunsAsAJobPerTable` (`kubectl get job`
  ran past its minute, item 61) and the worker's `TelemetryTest#theStandardVariablesAloneTurnExportOn` (no metric
  within 20 s, item 62). The heavy build stops at the first failing module, so only seven modules' tests ran.
- Ceph leg: passed, 6 min.
- Nightly: FAILED, 71 min. Step 1 failed on one test, `ConcurrentWritersTest` "32 writers, format v2" (a read timed
  out on its `pg_stat_database` check; added to item 55); step 2, the engine matrix, passed. Counts:
  catalog-maintenance 2, catalog-store-mongo 43, catalog-store-oracle 43, catalog-store-sql 43,
  catalog-store-sql-raft 43, catalog-tests-engines 5, catalog-tests-load 11 (1 failed), every other module 0.
  The CAS test passed on all four backends.
- Bites: not run, since the heavy build did not pass. The rotation did not move.

Logs: `C:\Users\paulh\code\nuna-overnight\runs\2026-11-18` (moved from keel-overnight with the rename).
