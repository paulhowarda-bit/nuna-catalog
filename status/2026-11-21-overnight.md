# 2026-11-21, overnight run

Tested 9813b9f (last tested d34547f), 00:00 to 01:52. The nuna-demo stack was running: taken down for the run, and
rebuilt with `demo/demo.sh up` at 01:52, which worked.

- Heavy build: FAILED, 4 min. One test, `AuditTablesTest#initializationError`: its server refused to start on the
  test PostgreSQL's self-check findings (item 116). The build stopped there, so only catalog-core, catalog-audit and
  catalog-audit-tables ran.
- Ceph leg: passed, 8 min.
- Nightly: FAILED, 91 min. Step 1 failed on two tests: `RollingUpgradeTest#upgradingOneServerAtATimeAcrossV5ToV11LosesNothing`
  (the new release refuses catalog m8, bound to another storage profile id; item 117) and
  `PrivateCaCompactionTest#aJobTrustsItsStoresPrivateCaAndCompacts` (its first run; the Job's pod found the network
  unreachable; item 89). Step 2, the engine matrix, passed. Counts: catalog-maintenance 3 (1 failed),
  catalog-store-mongo 53, catalog-store-oracle 53, catalog-store-sql 53, catalog-store-sql-raft 53,
  catalog-tests-engines 6, catalog-tests-load 12 (1 failed), every other module 0.
- Bites: not run, since the heavy build did not pass. The rotation did not move.

Logs: `.overnight\runs\2026-11-21` in the main checkout.
