# 2026-11-25, the audit table writers make their table again when it is gone (backlog 284)

- `LogWriter` and `TableWriter` reopen their table through the opener that creates it when the catalog answers
  `NoSuchTableException` on a reload, with a WARN that a dropped audit table should be undropped rather than replaced;
  a refusal is thrown as before. Work's stopgap in `demo.sh` (work note 005, item 4) is not needed; `demo.sh` unchanged.
- Loss: from the log, rows committed before the drop stay only in the dropped table and on stdout; from the topic,
  records older than Kafka keeps. No duplicates in the new table.
- Docs: `catalog-audit-tables/README.md`, `docs/observability/audit-log.md`.
- Tests: `./gradlew :catalog-audit-tables:test` 54 tests, 0 failed. Bites `bites/OBS-284/` (4 new), each applied by
  hand and its test seen to fail (drop tests: the writer throwing `NoSuchTableException`; refusal tests: an assertion),
  then restored; `bites/D23/restart-forgets-written-ids` recorded again; `bites/run.sh` not run.
- Fast build after merging `main` at 7c6a31eb: BUILD SUCCESSFUL in 9m 27s, 1,836 tests, 0 failed; `:bitesApply` and
  `:secretShapes` green. `main` has moved in code since (208d1e30, including `EventsReader`); not rebuilt.
- **Not checked:** against a real catalog server (no heavy test drops the table), or live on the demo.
