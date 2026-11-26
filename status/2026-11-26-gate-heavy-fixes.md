# 2026-11-26, the three heavy failures blocking item 26's gate (backlog 289)

All three were code or test faults that landed on 2026-11-25. None came from Podman or the environment.

- **`AuditTablesTest`, 3 of 3.** Cause: backlog 272 (4b5bf57a) made the writer create the events table with its first
  columns and then add `log_run` and `log_seq` with `updateSchema()`. Item 169 then added `branch` and `subject_ids` the
  same way. The catalog treats a schema change as `modify`, and the writer's group holds only `create` and `write` (D23).
  Items 284, 113 and 270 did not cause it. Fix: `EventsTable.open` makes a new table in one staged create
  (`createTransaction`) that adds the columns before it commits. The server judges that commit as a create
  (`Guard.CREATE_TABLE`), and the column ids come out as they did before. A table made before 272 still needs a caller
  with `modify` to gain the columns: the writer cannot do it.
- **`LockedArchiveTest`, 3 of 4.** Cause: backlog 262 (c8a763ac). `ObjectLockArchive.write` and `writeCheckpoint`
  returned this process's clock as `written`, but a listing returns the store's `LastModified`. A segment that was
  written therefore never equalled itself when listed. The checkpoint test also rewrote "at the same key" using that
  clock instant, which matched the key only when both fell in the same millisecond. Fix: the write calls return the
  store's time from a version listing. A HEAD would not do, because it answers to the second. The test now rewrites at
  the checkpoint's own `at`.
- **`SecurityGateTest`, 2 of 20.** Cause: the test's `attribute` fixture wrote the outbox payload `"{}"`. The relay
  stops at an entry it cannot decode and retries it forever. The gate run logged "outbox entry 13 … is not a catalog
  event" 63 times, so no audit line after the setup was ever logged. Item 270's capture fix was right but not enough.
  Fix: the fixture writes a real `GovernanceChange` event.

Bites recorded again with the same break against the moved code, each checked to bite by running its test:
`D23/old-table-not-given-ref-columns` and `OBS-272/old-table-not-given-log-columns` (their EventsReaderTest tests
fail), and `OBS-262/checkpoint-not-locked` (LockedArchiveTest#writerLocksSegmentsAndCheckpoints fails). Every other bite on these files still applies.

Checked on Podman, on the branch before `main` (14b64111, Oracle V21 only) was merged in:
- `:catalog-audit-tables:test --tests '*AuditTablesTest' -Pheavy`: 3 tests, 0 failures.
- `:catalog-audit:test --tests '*LockedArchiveTest' -Pheavy`: 4 tests, 0 failures.
- `:catalog-server:test --tests '*SecurityGateTest' -Pheavy`: 20 tests, 0 failures, and no relay error in its output.
- The fast build `./gradlew build` after the merge: BUILD SUCCESSFUL, every bite applying.

Not covered: the rest of the heavy build was not run again.
