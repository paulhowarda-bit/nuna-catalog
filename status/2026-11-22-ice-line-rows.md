# 2026-11-22, item 127: one row per table for the ice line

Branch `worktree-agent-afa336687a08edc46`. The console's ice line and the tree's marks (D45) can now draw every table
the caller may select, the tables in shape as well as the queued ones.

What changed:

- **The read** is `GET /management/v1/maintenance/estate?pageSize=&pageToken=`. It gives one row per active table the
  caller may `select`, in every catalog, by id. Each row has the table's assessment (verdict, reasons, score,
  data-file count), and whether it is held, frozen, closed until classified, and fixed last night. Each page names
  last night in `night`.
- **Who counts.** A table counts when the authorizer allows `select`. A table closed until classified (D27) also
  counts, marked `closed`, for a caller whose grants give `select` on it. No other table is named or counted.
- **Paging.** 200 rows by default, 1 to 500. A page looks at 2,000 tables at most and may come back short, or empty,
  with a token.
- **Docs:** a new section, "Every table", in `docs/observability/table-health.md`, and a line in the observability
  README. Backlog 127 records the choices.

What was checked:

- `MaintenanceEstateTest` (fast, 7 tests) passes. It runs the real route and authorizer over a store double.
- `MaintenanceEstateRoutesTest` (heavy) was written and not run here; the overnight run runs it.
- Five bites are recorded in `bites/D45/` and were run with `bites/run.sh`: "5 bites, 0 not biting or not restored
  (0 of them SETUP)".
- The fast build, `./gradlew build`, passed after main was merged at fc4d7aa (BUILD SUCCESSFUL in 2m 33s, with
  `:bitesApply`), and again after main was merged at a918c96 (BUILD SUCCESSFUL in 1m 37s).

Not covered: each row's health is one store read, since no store reads health for many tables at once. A joined
store read would make a page one query, at the cost of a new method on all four stores.
