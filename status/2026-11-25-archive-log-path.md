# 2026-11-25, archive-log-path: the audit archive fed from the log (backlog 262)

Paul answered item 262 with "(c) Log path" (D23, amended 2026-11-25). The Object Lock archive no longer comes from
Kafka; the audit table writer writes it from the log `nuna.audit`.

What changed:

- `catalog-audit`: the log stamp (`LogStamp`, CloudEvent extensions `nunarun` and `nunaseq`) and the run-end record
  type; the archive writer `ArchiveSink` and its locked, linked `Checkpoint`s; `ArchiveCheck` rewritten for one chain per
  server run (missing numbers, unended runs, repeats, late or broken checkpoints, a segment no checkpoint covers,
  records of another run, delete markers and second versions), its state kept in the bucket; `ArchiveConsumer` removed.
- `catalog-server`: `AuditTrail` stamps every logged line and logs the run end in the last phase of stopping; the
  audit runtime needs no Kafka for an archive and writes none of it; the hourly check without Kafka; the archive status
  route's new shape; the chain lookup route removed; feed rows say `no-place` where an archive is configured; the
  console's archive panel shows runs, checkpoints and findings.
- `catalog-audit-tables`: `nuna.audit-tables.archive.*`; the OTLP receiver archives every audit record first and
  answers 503, taking nothing into the table, when the archive cannot take a request.
- `catalog-tests-load`: the DR drill's servers export their log to an in-test archive writer (`ArchiveFromLog`).
- Helm values for the writer's archive and its CA ConfigMap; docs: audit-log.md ("The archive", "What the archive can
  miss, and how a miss shows"), catalog-audit-tables/README.md, deploy/README.md, spans.md.
- Backlog 272 added: placing a feed row in its run's chain, and a running server's own count beside the archive's.

Checked:

- `./gradlew build` after merging `main` (already up to date): BUILD SUCCESSFUL in 1m 56s; the modules' test results
  hold 1,793 tests, 0 failures.
- Bites: 17 new in `bites/OBS-262/`, 9 re-recorded (D23 five, M7, M8, UI two), 2 retired with the code they broke
  (`D23/placed-link-not-hashed`, `M7/archive-rereads-from-start`). `bites/run.sh` on the 23 that need no container:
  "23 bites, 0 not biting or not restored (0 of them SETUP)". Every recorded bite applies (`git apply --check`, 742).

Not covered here, left to the overnight run: `LockedArchiveTest` (heavy, real Object Lock) and its bite
`OBS-262/checkpoint-not-locked`; `ConsoleBrowserTest` (Chrome, refuses beside the demo, which was up) and the two
console bites; `DrDrillTest` (overnight).
