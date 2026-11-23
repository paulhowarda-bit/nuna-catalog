# 2026-11-22, items 114 and 124: maintenance on request, and the console's last panels

Branch `worktree-agent-a7262517afa8744fd`. A table's owner can now ask for its compaction or orphan scan to run now,
from the console or the API, inside the cleanup window or outside it (D30, amended 2026-11-20). The console's
redesign (D45) has its last panels: the ice line over every table, with its counts, and the requests.

What changed:

- **The route** is `POST /management/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}/maintenance/requests`
  with `{"job": "compaction" | "orphan-scan"}`, answering 202 with the request.
  - It needs `modify` (403 without it, 404 to a caller who holds nothing on the table).
  - It is refused under a DR freeze (403, `dr_freeze`), and with 409 `MaintenanceNotAllowedException` when the
    table's policy does not run the job (`maintenance_policy`) or a legal hold stops an orphan scan (`legal_hold`).
  - Each request is audited as a governance change (`maintenance-request`), and each refusal as a denial.
  - One request waits per table and job; asking again answers 202 with the one waiting and writes nothing.
- **The catalog** keeps requests in `maintenance_request`, a new table on all four stores, each step add-only:
  PostgreSQL V13, CockroachDB V12, Oracle V14 and MongoDB step 12.
  - A table's health (`GET .../tables/{table}/health`) lists its requests: who asked, when, waiting or started.
  - The work list carries the waiting ones in a new last section, `requests`. A request whose job the policy no
    longer runs stays waiting and off the list.
  - A job report with `requested-at` marks its request started. A purge deletes the table's requests.
- **The worker** takes requests at its next scan, inside the window or outside it.
  - A requested compaction is assessed and reported, then its Job starts whatever the assessment says. The Job is
    named `nuna-compact-<table>-r<time asked, base 36>`, and its Kubernetes deadline is the new setting
    `nuna.maintenance.request-deadline` (PT2H) after it starts.
  - Requested Jobs and the night's share the `max-jobs` slots (the new `Slots`), the requested first, and a table
    holds at most one. With compaction off, a request waits.
  - A requested orphan scan stops at the same deadline and records nothing.
  - Each is reported as a job of the night the window last opened, with `requested-at`.
- **The console:**
  - **Compact now** and **Scan for orphans now** on a table's Health tab, **Compact now** in its header, and
    **Compact a table now** on Maintenance. Each shows only to a viewer whose groups hold `modify`.
  - The requests, waiting or started, are on the Health tab and as marks beside the table's name.
  - The overview's ice line draws every table from `/maintenance/estate`, paging while there is a token. It shows the
    tables in shape above the line, and has an **In shape** figure and a legend with the counts.
  - The remaining "waits on" notes for 127 and 114 are resolved. Contents' snapshots and last commit point at the
    new item 137. The DR replication and store panels on Health are left out: no route serves them.
- **Docs:** table-health.md ("Maintenance on request", the schema steps, tests and bites), health-page.md,
  maintenance-nights.md (`requested-at`), catalog-maintenance/README.md (the section, the setting, the window's
  exception), and the console design README.
- `SchemaAtStartupTest` and `RollingUpgradeTest` now expect PostgreSQL V13, and `TelemetryTest` four work-list sections.

What was checked:

- Fast tests run here and passing:
  - `RequestedWorkTest` (4);
  - `MaintenanceRequestsTest` (7);
  - `KubernetesJobsTest` and `NightJobsTest`, after the Job name and slots changed.
- Heavy tests, written and not run here (the overnight run runs them):
  - `HealthSuite`'s three request tests, on each of the four stores;
  - `MaintenanceRequestRoutesTest` (2);
  - `ConsoleHealthTest`'s new owner test, and its reader test's check that a reader sees no buttons.
- Four new bites are in `bites/D30/`: `request-taken-without-modify`, `request-taken-under-hold`,
  `requested-job-window-deadline` and `requested-job-waits-for-window`. Four older ones were re-recorded with the
  same breaks against the shared slots and the `Run`: `compaction-slot-never-freed`, `compaction-with-token-file`,
  `job-deadline-not-window-end` and `secret-reuses-job-name`.
  - All eight test fast tests. Each was applied, its test run and seen to fail, and reverted, with git status clean
    afterwards.
- The fast build, `./gradlew build`, after merging main at 9e230ae.
  - It first failed in `TelemetryTest`, which counted three work-list failures for a scan that cannot reach the
    catalog. The requests section makes four; the test now expects four.
  - Then it passed: BUILD SUCCESSFUL in 1m 16s, `:bitesApply` included.

Not covered:

- The heavy tests above.
- The console's buttons, which were not clicked in a browser here. `ConsoleHealthTest` covers them.
