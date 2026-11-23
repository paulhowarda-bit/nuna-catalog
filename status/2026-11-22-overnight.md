# 2026-11-22, overnight run

Tested b1f073d (last tested 9813b9f), 00:00 to 01:43. The nuna-demo stack was running: taken down for the run, and
rebuilt with `demo/demo.sh up` at 01:43, which worked. The 07:00 report task did not record it (its last run time
moved to 07:31 but it started no session); recorded here by hand later that morning, at Paul's word.

- Heavy build: FAILED, 21 min, 8 tests. On MongoDB, seven store-suite cases: ref deletion (item 102) and the merge
  (item 103) both leave a projection piece of the branch's own pointer, and five `DrSuite` cases (items 28 and 100).
  In catalog-server, `ConsoleBrowserTest#theViewTabsShowEveryVersionWithItsSql` could not open its first browser
  session, connection reset (new item 121). The build ran without `--continue`, so it stopped once those two modules
  finished: catalog-store-oracle, catalog-store-sql, catalog-store-sql-raft, catalog-tests-conformance,
  catalog-tests-s3 and singlestore-change-feed were not tested (new item 122).
- Ceph leg: passed, 4 min.
- Nightly: FAILED, 66 min. Step 1 failed one test, `CockroachStoreSuiteTest` "1 CAS": one commit could not reach the
  database in three tries, after 7 others were repeated (item 55). Step 2, the engine matrix, passed.
  `RollingUpgradeTest` passed, so item 117 is done; `PrivateCaCompactionTest` passed (item 89 waits on its bite).
  Counts: catalog-maintenance 3, catalog-store-mongo 53, catalog-store-oracle 53, catalog-store-sql 53,
  catalog-store-sql-raft 53 (1 failed), catalog-tests-engines 6, catalog-tests-load 12, every other module 0.
- Bites: not run, since the heavy build did not pass. The rotation did not move.

Logs: `.overnight\runs\2026-11-22` in the main checkout.
