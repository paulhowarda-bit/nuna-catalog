# 2026-11-27: the server adds the audit table's new columns (backlog 316)

Branch `audit-columns-at-startup`. The demo's audit table writer had every commit refused 403: its table predates the
writer's four added columns, and adding them needs `modify`, which the writers' group does not hold. Paul chose to
have the server add them (D23, amended 2026-11-27).

- `AuditTableColumns` (catalog-server) adds the absent columns at startup as `nuna-configuration`, through the commit
  engine; `EventsColumns` (catalog-audit-tables) builds the commit.
- Tests: `EventsColumnsTest` (fast); `AuditTablesTest#theServerGivesAnOlderTableTheColumnsTheWriterMayNotAdd` (heavy,
  run once with `-Pbites --tests`, passed beside the demo).
- Bite `D23/server-adds-present-columns`: BITES.
