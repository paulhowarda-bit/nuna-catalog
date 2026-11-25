# 2026-11-25, a feed record's place in its server run's archive chain (backlog 272)

What changed:

- **The audit table** has `log_run` and `log_seq` (field ids 20 and 21), filled from the stamp (`nunarun`, `nunaseq`)
  for rows from the log. A table made before them is given them when the writer opens it; earlier rows read null.
  Rows from the topic have neither: the Kafka copy stays unstamped, as 262 designed it.
- **The lookup** `GET /management/v1/audit/chains/{run}/records/{number}?event-id=` finds a record in its run's chain
  (`ObjectLockArchive.place`): the segment, its hash and its neighbours', whether its hash matches the one recomputed
  from the hash before and its content, and whether the check has verified the chain through it. It searches near
  where the number puts it, and the whole chain only when the check has verified every number up to it. It is
  audited as an `AuditRead` of the audit catalog and takes an audit read from the caller's limit.
- **The feed** gives each row `log-run` and `log-seq`, and `chain.status` `verified` or `unverified` for a row with a
  run, `no-place` only for one without. Atelier found that `unverified` can be permanent: a row after a record the
  check reported missing never becomes verified. Such a row now also carries `chain.missing`, the gap that holds it
  back, and the console says so ("Missing from archive", "Earlier record missing").
- **The console** shows the pill, the Hash chain line, the run on the Read from line, and "Check this record", which
  calls the lookup, all in Atelier's words.
- **Metrics:** `nuna.audit.logged{run}` from each server (`AuditTrail.logged()`), and `nuna.audit.archive.highest{run}`
  from each server's check, for runs not done. The nuna catalog dashboard has a new row, "Audit: the archive".

Checked:

- `:catalog-audit:test` (PlacementTest 5, AuditRowsTest), `:catalog-audit-tables:test` (LogWriterTest,
  EventsReaderTest, OtlpLogsReceiverTest), and `:catalog-server:test --tests '*AuditFeedRoutesTest'
  --tests '*AuditLogToWriterTest'` all pass.
- `ConsoleBrowserTest`'s three audit tests, the new `aLoggedRecordSaysWhetherItsRunIsVerifiedAndLooksUpItsPlaceInTheChain`
  among them, pass with `-Pheavy -Pnuna.demo-beside` (148 s), beside the running demo.
- Bites: 11 new in `bites/OBS-272/` and 2 in `bites/UI/`, the 11 run with `bites/run.sh bites/OBS-272`: all BITES, tree restored; 5 re-recorded against the moved code
  (`D23/feed-row-verified-unchecked`, `D23/log-rows-not-deduplicated`, `OBS-262/receiver-archives-after-filter`,
  `UI/console-archive-check-without-archive`, `UI/console-log-record-shows-kafka`). All 756 apply.

Not covered:

- The two new UI bites and the two re-recorded UI bites were recorded, not run: they need Chrome, which the overnight
  run's bites give them.
- No query of the new dashboard row has run against live data (as for the rest of the dashboards, D43).
- Paul's question stays open: whether the Kafka copy should carry the stamp too, so a row from the topic can be placed.
