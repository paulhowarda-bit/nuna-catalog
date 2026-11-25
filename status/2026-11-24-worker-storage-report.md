# 2026-11-24, what the worker's own storage reaches, in each scan report (backlog 250)

**Built** on `worktree-agent-a21d235605d6834e2`: what items 251 and 252 read.
- At each scan's start the worker lists at most one key beneath each prefix of `nuna.maintenance.storage`
  (`<prefix>/`, as the delete policy reads it) and reports `ok`, `refused` with the store's code (AccessDenied,
  NoSuchBucket), or `failed` with the client's failure; at its end, the scan's deletes beneath each prefix, done,
  refused by the store and failed, with the first one's code. Keyed by kind and endpoint (not profile name, ahead of
  item 256), prefixes under each. The worker now sends DeleteObjects itself on the profile's client (`StorePrefix`,
  250 keys a call as S3FileIO does), since S3FileIO's `deleteFiles` drops each refused key's code.
- The catalog keeps the report and the groups of the worker's token with the scan on all four stores
  (`worker_groups`, `worker_storage`: PostgreSQL V17, CockroachDB V16, Oracle V18; arrays on MongoDB) and shows both
  to platform operators on `GET /management/v1/maintenance/scans`. `MaintenanceScan.covering(location)` gives the
  reported prefix over a catalog's location. Shape: docs/observability/table-health.md, "The scans".

**Checked:**
- `StorageReportTest` 1/1 (fast, stand-in stores): ok, AccessDenied, NoSuchBucket and no answer each reported under
  its endpoint; 2 deletes done and 2 refused (AccessDenied) beneath their prefixes, counted afresh the next scan; no
  access key, secret, object key or store message in any report.
- `WorkerStorageTest` 4/4; `:catalog-maintenance:test` 86/86 and `:catalog-store-api:test` 30/30.
- `PostgresStoreSuiteTest$Health` 10/10 and `HealthRoutesTest` 6/6 (heavy, run beside the nuna-demo stack).
- Each new bite's test failed on its assertion with the break in, tree restored: the two worker bites, the two
  `WorkerStorage` bites, `scan-end-keeps-start-storage-sql` and `scan-groups-not-recorded`. The Oracle and MongoDB
  bites compile with the break in; not run. `bites/M6/worker-ca-bundle-ignored` recorded again against `Storage.of`.
- `./gradlew build` on the branch with main merged (95cbcd5b): green, 2 m 6 s, 1,723 test results, none failed;
  `:bitesApply` passed with the nine new and re-recorded bites.

**Not run:** `HealthSuite#aScanKeepsWhatTheWorkerReaches` on CockroachDB, Oracle and MongoDB, and the Oracle and
MongoDB bites (heavy: the overnight run). On `-Ps3=aws` the tests' worker (`TestCatalog.asRole`) deletes with
`deleteFiles`, so that leg does not run `StorePrefix`.

**Open:** the console shows none of it (item 252); no readiness route reads it (item 251).
