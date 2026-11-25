# 2026-11-25, overnight run

Tested 639b0da5 (demo-worker-policy; last tested ee2084d2), 00:00 to 06:31.

- Heavy build: FAILED, 41 min, 7 tests in catalog-server: `ConsoleBrowserTest#aBranchIsMadeCommittedOnPreviewedAndMerged`
  (item 271), `ConsoleReadinessBrowserTest#anaSeesWhatIsMissingWithNamesAndCopiesANoteForAnOperator` (a 404 on the old
  readiness path; main already fixed the test in b6c84ae9, item 254, so nothing filed), `MutatingRoutesAuditTest`
  (item 269), `SchemaAtStartupTest` (item 268), `SecurityGateTest` (2, item 270).
- Ceph leg: passed, 5 min.
- Nightly: FAILED, 93 min, `RollingUpgradeTest#upgradingOneServerAtATimeAcrossV9ToV15LosesNothing` (item 268). Counts:
  catalog-maintenance 3, catalog-store-mongo 55, catalog-store-oracle 55, catalog-store-sql 55, catalog-store-sql-raft 55,
  catalog-tests-engines 7, catalog-tests-load 12 (1 failed), every other module 0.
- Bites: 561 of 724 ran in 240 min, 559 bit; 2 did not: `D33/metadata-log-ref-page-by-version` (item 226) and
  `D33/mongo-cut-read-outside-the-snapshot` (item 227), both NO BITE again. 25 not run, their tests having failed
  tonight. The deadline stopped the rest; the next run starts at `bites/OBS-38/requestid-before-trace.patch`.
- Agents: two started (catalog-server, catalog-tests-load); both ended at once with "OAuth session expired and could
  not be refreshed", $0, claimed nothing. The claude CLI needs `claude auth login`. This report filed their failures.
- nuna-demo: running, taken down for the run; `demo/demo.sh up` worked, rebuilt at 06:31.

Logs: `.overnight\runs\2026-11-25` in the main checkout.
