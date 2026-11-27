# 2026-11-27, overnight run

Tested a7a929ac (Land backlog-151; last tested 639b0da5), 00:00 to 06:30.

- Heavy build: FAILED, 37 min, one test: `ChangeFeedTest#initializationError` in singlestore-change-feed, the
  SingleStore dev container timed out becoming healthy (item 302).
- Ceph leg: passed, 4 min.
- Nightly: FAILED, 88 min, `DrDrillTest#reconcileFromStorageRecoversEveryAcknowledgedCommit`, the archive did not prove
  each acknowledged tip within 3 minutes (item 303). Counts: catalog-maintenance 3, catalog-store-mongo 55,
  catalog-store-oracle 55, catalog-store-sql 55, catalog-store-sql-raft 55, catalog-tests-engines 7, catalog-tests-load
  12 (1 failed), every other module 0 (catalog-audit, catalog-audit-tables, catalog-core, catalog-iceberg,
  catalog-objectstore, catalog-security, catalog-server, catalog-store-api, catalog-tests-conformance,
  catalog-tests-docker, catalog-tests-s3, demo-datagen, singlestore-change-feed).
- Bites: 545 of 899 ran in 250 min, 542 bit; 3 did not: `TEST-291/connections-reused-at-any-age` SETUP (item 304),
  `UI/console-goto-lookup-overdrawn` NO BITE (item 305), `UI/console-readiness-grant-for-anyone-with-names` SETUP, no
  test ran, its test method renamed (item 306). 2 not run, their test (`ChangeFeedTest`) having failed tonight. The
  deadline stopped the rest; the next run starts at `bites/D33/mongo-branch-commit-unmarked-ref.patch`.
- Agents: two started (singlestore-change-feed, catalog-tests-load); both ended at once with "OAuth session expired
  and could not be refreshed", $0, claimed nothing, the second night running. The claude CLI needs `claude auth
  login`. This report filed their failures.
- nuna-demo: running, taken down for the run; `demo/demo.sh up` worked, rebuilt at 06:30 on a7a929ac.

Logs: `.overnight\runs\2026-11-27` in the main checkout.
