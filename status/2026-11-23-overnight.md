# 2026-11-23, overnight run

Tested 02b22ad (the first refs landing; last tested b1f073d), 00:00 to 02:22.

- Heavy build: FAILED, 36 min, 9 tests. Item 26's three (`PostgresStoreSuiteTest$Refs`, `CockroachStoreSuiteTest$Refs`:
  `NullPointerException: metadataLocation`; `IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch`) were
  fixed afterwards in e0512ca (item 105). The other six are new items: `MutatingRoutesAuditTest` (both gates) and
  `HealthRoutesTest#theWorkersReportsMakeTheTablesHealth`, item 114's route and field (160);
  `ConsoleBrowserTest#theTableTabsShowSchemasSpecsSnapshotsAndHistory`, the Metadata log tab (161);
  `ConsoleHealthTest#aTableNotAssessedYetIsNeitherInShapeNorQueued` (162);
  `ConsoleSettingsBrowserTest#aListedSettingIsChangedAndResetThroughItsEditorAsAnAuditedCommit` (163). Causes traced
  in `status/2026-11-23-agent-a9284fd56a1d18c39.md`.
- Ceph leg: passed, 5 min.
- Nightly: passed, 91 min. Counts: catalog-maintenance 3, catalog-store-mongo 53, catalog-store-oracle 53,
  catalog-store-sql 53, catalog-store-sql-raft 53, catalog-tests-engines 6, catalog-tests-load 12, every other module 0.
- Bites: not run, since the heavy build did not pass. The rotation did not move (next: `bites/D11/replace-exemption-in-place.patch`).
- nuna-demo: running, taken down for the run; `demo/demo.sh up` FAILED at 02:22 on the seed's first grant (403, "no one
  grants to a group they belong to"), item 159, fixed since in f7e949c.

Logs: `.overnight\runs\2026-11-23` in the main checkout.
