# 2026-11-22, item 126: each night's maintenance and each job on its slot

Branch `worktree-agent-a47ef595081b1e798`. The console's overview and maintenance pages (D45) can now lead with last
night's totals and draw each night's compaction Jobs on their slots.

What changed:

- **The worker** reports each job of the night with `PUT /management/v1/maintenance/jobs/{id}`: its start, end,
  slot, outcome, files in and out, snapshots expired, and files and bytes deleted.
  - Compaction Jobs report on the lowest free slot, under an id made from the Job's name. Files in and out are read
    from the table's `replace` snapshots since its assessment.
  - Orphan scans and purges report once they end. A purge's bytes come from the listing it already makes.
  - Expiry, manifest rewrite and deletions are added up per table, kind and night. A deletion's bytes come from
    listing each file under its own key, since the worker's identity cannot read objects.
- **The catalog** keeps the reports in `maintenance_job`, a new table added on all four stores:
  - PostgreSQL V12, CockroachDB V11, Oracle V13 and MongoDB step 11;
  - each step only adds;
  - a report replaces the kept row unless that row ended later;
  - each report deletes nights more than 31 days old, so at least 30 whole nights are kept.
- **The read** is `GET /management/v1/maintenance/nights?days=&jobs=`. It answers each night's totals to anyone signed
  in. The newest `jobs` nights also list their compaction Jobs and orphan scans. A job names its table only to a
  caller who may `select` it.
- **Docs:** `docs/observability/maintenance-nights.md` is new, and catalog-maintenance/README.md, the observability
  README and worker-spans.md (`report=job`) are updated.

What was checked:

- Fast tests run here and passing: `NightJobsTest` (2), `MaintenanceNightsTest` (3) and `MaintenanceJobTest` (3).
- Heavy tests, written and not run here (the overnight run runs them):
  - `NightSuite` on each of the four stores;
  - `MaintenanceNightsRoutesTest`;
  - the new night assertions in `NightlyCleanupTest`;
  - the new route in `MutatingRoutesAuditTest`;
  - `SchemaAtStartupTest` and `RollingUpgradeTest`, moved to PostgreSQL V12.
- Twelve bites are recorded in `bites/D30/`, each checked with `git apply --check`. The four in catalog-maintenance
  were run with `bites/run.sh` here, and each bites. The eight against heavy tests were not run.
- The fast build, `./gradlew build`, after merging main: see the item.

Not covered: the console's pages themselves (items 124 to 132).
