# 2026-11-24, overnight run

Tested ee2084d2 (console-rewalk-fixes; last tested 02b22adb), 00:00 to 06:38.

- Heavy build: FAILED, 36 min, 9 tests: `ConsoleSignInTest#signsInThroughThePkceFlowAndTheCatalogAcceptsTheToken`,
  `ConsoleBrowserTest#auditFiltersByKindKeepsItsFiltersInTheLinkAndHidesItsOwnReads`, `ConsoleHealthTest` (2),
  `KafkaOutageTest#anOutageBlocksNothingAndEveryRecordIsInTheLog`, `LifecycleTest#onlyAMaintenancePrincipalPurgesAndOnlyAfterTheWindow`,
  `RestRoutesTest` (2), and `catalog-maintenance`'s `KafkaDownTest#aScanWithoutKafkaChangesNothingAndTheNextOneCatchesUp`.
  All were tests left behind by items 193, 195, 206, 207 and 214, which the fast build does not run.
- Ceph leg: FAILED, 5 min, `KafkaDownTest` again.
- Nightly: FAILED, 91 min, `EngineMatrixTest#refs` (its first run). Counts: catalog-maintenance 3, catalog-store-mongo 55,
  catalog-store-oracle 55, catalog-store-sql 55, catalog-store-sql-raft 55, catalog-tests-engines 7 (1 failed),
  catalog-tests-load 12, every other module 0.
- Bites: 573 of 639 ran in 247 min, 570 bit; 3 did not: `D33/metadata-log-ref-page-by-version` (NO BITE, really an
  OutOfMemoryError after 48 min; item 226), `D33/mongo-cut-read-outside-the-snapshot` (NO BITE, item 227),
  `M8/store-skips-postgresql-steps` (SETUP, item 228). 62 not run, their tests having failed tonight. The deadline
  stopped the rest; the next run starts at `bites/UI/name-search-decides-matches-only.patch`.
- Agents (three, $12.63 in all): catalog-maintenance landed item 223 (`KafkaDownTest` rewritten for item 195's outage
  behaviour, new bite) at 4db9457f, $2.83; catalog-server landed item 224 (the eight server tests, plus
  `SecurityGateTest`) at 21cf9506, $7.80; catalog-tests-engines landed the `refs_matrix.py` v2 tables under item 112
  (left open and released) at 78540ca6, $1.98. None of their fixes has run in a heavy build yet.
- nuna-demo: running, taken down for the run; `demo/demo.sh up` worked, rebuilt at 06:38.

Logs: `.overnight\runs\2026-11-24` in the main checkout.
