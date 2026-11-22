# 2026-11-22, the catalog-wide audit feed (item 125)

- Routes, in `AuditReadRoutes`, documented in `catalog-audit-tables/README.md`:
  - `GET /management/v1/catalogs/{catalog}/audit/feed`
  - `GET /management/v1/catalogs/{catalog}/audit/summary`
  - `GET /management/v1/audit/archive`
  - `GET /management/v1/audit/chains/{partition}/records/{offset}?event-id=`
- Each needs `select` on the audit catalog. Paul decided this on 2026-11-22 ("Audit readers only"), and that the
  server links the audit table writer's libraries ("Yes, link them"); D23 is amended with both.
- New code:
  - `EventsReader` (catalog-audit-tables) pages the table `events` newest first, with a cursor and a scan budget, and
    counts a span of time. It reads only files under the table's location.
  - `ArchiveCheck` (catalog-audit) verifies each chain incrementally up to the archive consumer's anchor.
    `ObjectLockArchive.place` finds a record's place in its chain.
  - `AuditRows.event` turns a row back into its record.
  - In the server, `AuditFeed` reads the table and `AuditArchiveCheck` runs the check hourly. The check keeps what it
    verified in the consumer group `<archive group>-check`.
- Tests, all Java-only:
  - `EventsReaderTest`: 9 tests, passing.
  - `ArchiveCheckTest`: 8 tests, passing. `AuditRowsTest` gained 1 test, passing. The catalog-audit archive and row
    tests ran 47 tests, all passing.
  - `AuditFeedRoutesTest` (catalog-server): 16 tests, one for each refusal. Not yet compiled or run: a by-hand nightly
    held the machine.
- Bites in `bites/D23/`, each checked with `git apply --check` and not run:
  - `feed-without-select`
  - `feed-row-verified-unchecked`
  - `check-skips-records`
  - `placed-link-not-hashed`
  - `feed-page-repeats-a-row`
  - `feed-reads-outside-the-table`
- Fast build: not yet run on the finished branch (see above).
- Not covered:
  - a heavy test of the routes against a real table and archive;
  - the console's use of the routes (item 124);
  - `build-plan.md`'s UI v1 note, which item 131 holds.
