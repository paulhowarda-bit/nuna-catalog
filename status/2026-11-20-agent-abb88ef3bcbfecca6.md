# 2026-11-20, the DR drill's leg with the DR store behind, the worker's DR settings documented (backlog 92, 93)

- **Item 93.** `Appender` (catalog-tests-load) takes the table's store client; given one, it writes each snapshot's
  manifest list, an empty one Iceberg reads, before the commit's first attempt, so the snapshots name files that
  exist. Only `DrDrillTest` passes the client; the other load tests write nothing and time the same commit route as
  before. Its ledger also records the metadata file each acknowledged commit made.
- **Item 92, the drill's leg.** `DrDrillTest#aRewindGivesUpExactlyTheCommitsWhoseFilesNeverReachedTheDrStore`
  (overnight, Silo only): a second Silo of its own as the DR store (`TestS3.siloOfItsOwn`, `Stack.withDrStore`), a
  `Replicator` thread copying the bucket there until the test pauses it, a DR user that may only read, and the
  maintenance worker from its runnable jar (`catalog-maintenance`'s new `execJar` configuration, consumed by
  catalog-tests-load as the server's is). The verifier runs in the primary region, then in DR: freeze, two passes,
  rewind plan and apply, a pass over the rewound versions, the walk, thaw. It asserts the watermarks stop exactly at
  the last version the DR store got whole, each lost tail is exactly the commits whose files never arrived, every
  table reads whole from the DR store, and commits resume. Written for D37 "Check from the watermark" as landed
  (5df8b5d). The first leg now gives its appenders the store client and gets a stack per test.
- **Item 92, the docs.** `deploy/helm/nuna-maintenance/values.yaml` comments `config.nuna.maintenance.dr`
  (verify interval, a commented replica, read-only keys, the secret key's name, the CA bundle, off without a
  replica). `catalog-maintenance/README.md`: a section on the DR verifier, the `dr.*` settings, the DR store's
  read-only keys, the chart's DR values, the DR tests. `docs/install-at-work.md` is held by item 23: its bullet is
  in item 92, as are two sentences for `deploy/README.md`.
- **New items:** 99 (a table committing faster than replication never gets a first watermark), 100 (a rewind giving
  up more than 100 versions leaves the table behind for good), 101 (the runbook waits on a log line the worker
  writes only at DEBUG). All three read from the code, not seen in a run.

Checked: the fast build through the agents' build script after merging main, "BUILD SUCCESSFUL in 1m 44s", "All 381
bites apply"; catalog-tests-load's test sources compiled. `helm template` of the worker chart with and without a
replica, exit 0 both, the replica's settings in the ConfigMap and its key in the Secret; `helm lint`, 0 failed.

Not covered: neither leg of `DrDrillTest` has run, nor any other `overnight` or `heavy` test; the fast build starts
no container. No bite: the branch adds no guarded check, only tests and docs. Items 92 and 93 stay open until an
overnight run passes the drill.
