# The maintenance nights

The catalog keeps every job the maintenance worker runs, each with its slot, its counts and how it ended, for a month,
so the redesigned console can lead its overview with last night's totals and draw each night's compaction Jobs on
their slots (D45, backlog 126). The worker reports each job; one route answers the nights.

A **night** is one opening of the cleanup window (D30, amended 2026-11-15) and the day that follows, until the window
next opens. It is named by the date the window opened on, in the window's zone. Compaction Jobs and orphan scans run
inside the window. Expiry, manifest rewrite, purges and deletions run round the clock: each counts to the night that
opened last before it started.

This is bookkeeping, like the table health (table-health.md). Alerts are built on the worker's meters
(worker-metrics.md), not on these rows.

## The read

`GET /management/v1/maintenance/nights?days=30&jobs=1`. Anyone signed in may call it.

- `days`: how many nights to answer, the newest first. Only nights that have at least one job count, up to tomorrow
  by the catalog's clock (UTC). The default is 30; the value is clamped to 1 to 32.
- `jobs`: how many of those nights, the newest first, carry their jobs. The default is 1: the overview needs only last
  night's jobs. The value is clamped to 0 to `days`.

```json
{
  "nights": [
    {
      "night": "2027-03-27",
      "opens": "2027-03-27T23:00:00Z",
      "closes": "2027-03-28T01:00:00Z",
      "tables-compacted": 12,
      "files-compacted": 4180,
      "files-written": 96,
      "snapshots-expired": 340,
      "files-deleted": 2210,
      "bytes-deleted": 912345678,
      "jobs-running": 0,
      "jobs-succeeded": 57,
      "jobs-cut-off": 2,
      "jobs-failed": 1,
      "jobs": [
        {
          "id": "1f0c...",
          "kind": "compaction",
          "slot": 1,
          "started-at": "2027-03-27T23:00:01.250Z",
          "ended-at": "2027-03-27T23:41:09Z",
          "outcome": "succeeded",
          "files-in": 410,
          "files-out": 8,
          "table": { "id": "6c1d...", "catalog": "lake", "namespace": ["sales"], "name": "orders" }
        },
        {
          "id": "93ab...",
          "kind": "orphan-scan",
          "started-at": "2027-03-27T23:05:00Z",
          "ended-at": "2027-03-28T01:00:00Z",
          "outcome": "cut-off",
          "files-in": 0,
          "files-out": 0
        }
      ]
    },
    { "night": "2027-03-26", "...": "totals only" }
  ]
}
```

### A night's totals

| Field | Meaning |
|---|---|
| `night` | The date the window opened on, in the window's zone. |
| `opens`, `closes` | The window as the worker saw it, which may differ between workers whose settings differ. |
| `tables-compacted` | Compaction Jobs that succeeded. Each table gets at most one Job a night. |
| `files-compacted`, `files-written` | The data files the night's compaction Jobs replaced, and those they wrote. |
| `snapshots-expired` | Snapshots expired, over the whole night. |
| `files-deleted`, `bytes-deleted` | Files deleted, and their bytes, by purges and by the due pending deletions. Files that a job only listed for deletion count when they are deleted. |
| `jobs-running`, `jobs-succeeded`, `jobs-cut-off`, `jobs-failed` | Jobs of every kind, by how they stand. Round-the-clock work counts once for each table and kind (see the worker's reports). |

### A night's jobs

Only compaction Jobs and orphan scans are listed, by start: these are what the console draws. Compaction Jobs go on
their slots, orphan scans in a lane of their own. Round-the-clock work counts in the totals only.

| Field | Meaning |
|---|---|
| `id` | The job's id. A compaction's id comes from its Kubernetes Job's name, so a worker restarted reports the same Job under the same id. |
| `kind` | `compaction` or `orphan-scan`. |
| `slot` | A compaction's slot, from 1 to `nuna.maintenance.compaction.max-jobs`: the lowest that no other running Job held when it started. Not present for an orphan scan. |
| `started-at`, `ended-at` | When the worker started the job, or found tonight's Job already started after a restart; when it saw it end. No `ended-at` while it runs. |
| `outcome` | `running`, `succeeded`, `failed`, or `cut-off` when the window's end stopped it. |
| `files-in`, `files-out` | For a compaction: the data files its `replace` commits on main took out, and those they wrote. These are counted from the snapshot the table had when it was assessed, and no further back than an hour before the Job started. Another writer's own rewrite in that time counts too. 0 for an orphan scan. |
| `table` | The table, but only to a caller who may `select` it. Anyone else sees the job but not what it ran on. It is absent too once the table is purged. |

## The worker's reports

`PUT /management/v1/maintenance/jobs/{job id}`, as a maintenance principal acting as itself
(`nuna.security.maintenance-principals`). Anyone else gets 403. The answer is 204.

```json
{
  "night": "2027-03-27", "opens": "2027-03-27T23:00:00Z", "closes": "2027-03-28T01:00:00Z",
  "kind": "compaction", "object-id": "6c1d...", "slot": 1,
  "started-at": "2027-03-27T23:00:01.250Z", "ended-at": "2027-03-27T23:41:09Z", "outcome": "succeeded",
  "files-in": 410, "files-out": 8, "snapshots-expired": 0, "files-deleted": 0, "bytes-deleted": 0
}
```

- **Kinds.** `kind` is one of `compaction`, `orphan-scan`, `snapshot-expiry`, `manifest-rewrite`, `purge` or
  `deletion`. A compaction has a `slot`; no other kind does.
- **Required fields.** A job has `ended-at` exactly when its `outcome` is not `running`. Counts left out are 0.
  Anything else is 400 `BadRequestException`.
- **The object must be known.** `object-id` must be a table or view the catalog has or had: active, dropped or purged.
  Otherwise the report is 404 `NoSuchObjectException`.
- **The latest end wins.** A report replaces the one kept unless the kept one ended later, or has ended and this one
  has not. The earlier start is kept. The night, the kind and the object stay as first reported.
- **A month is kept.** Each report deletes the jobs of nights more than 31 days before today, by the catalog's clock in
  UTC. That keeps at least 30 whole nights in any zone.
- **Bookkeeping, not changes.** A report writes no audit record. A refused report is audited.
- **A job run on request** (backlog 114) carries `requested-at` too, the time the request it runs was asked, to the
  millisecond. The first such report of a `compaction` or `orphan-scan` marks that request started (table-health.md,
  "Maintenance on request"). It counts to the night the window last opened, as the night's jobs do.

What the worker reports:

| Job | When | Counts |
|---|---|---|
| Compaction | It reports as the Job starts, or when it is found already started, as `running` on its slot. It reports again when the worker sees the Job end, or when the window closes on it (`cut-off`). | `files-in`, `files-out` |
| Orphan scan | Once it has ended: `succeeded`, `cut-off` when the window's end cut it short, or `failed`. | none: the orphans it finds go to the pending-deletion list and count when they are deleted |
| Purge | Once a claimed purge has ended, `succeeded` or `failed`. | `files-deleted`, `bytes-deleted`, the sizes coming from the listing the purge already makes |
| Snapshot expiry, manifest rewrite, deletions | After each run that did something, or failed. | `snapshots-expired`; the manifests rewritten as `files-in` and those written as `files-out`; `files-deleted`, `bytes-deleted` |

Round-the-clock runs are added up. For each table, kind and night, the worker adds its runs that succeeded into one
job, and its runs that failed into another. Each sum is reported under one id each time it grows. The job starts with
the first run and ends with the last. The sums are kept in memory for the night under way, so a worker restarted
reports its later runs under new ids, and the night keeps both.

A deletion's bytes come from listing each file by its own key before it is deleted. The worker's storage identity may
list and delete but never read, so it cannot ask for an object's size directly. A file already gone, or one the list
fails on, counts no bytes, and the deletion goes on.

A report the catalog does not take is logged and counted as a `report` failure (worker-metrics.md). The work goes on.
Each report is a `report` span with `report=job` (worker-spans.md).

## Where it is kept

One new table, `maintenance_job` (a collection on MongoDB), added by one schema step on each store. It is keyed by the
job's id and indexed by night, kind, start and id. The step only adds, so servers of the previous release keep serving
while the first new one migrates.

| Store | Step |
|---|---|
| PostgreSQL | `V12__maintenance_jobs.sql` |
| CockroachDB | `V11__maintenance_jobs.sql` |
| Oracle | `V13__maintenance_jobs.sql` |
| MongoDB | step 11 (`MongoSchema`), the index `maintenance_job_night` |

There is no foreign key: a job outlives its table's purge.

## Tests and bites

| Test | Shows |
|---|---|
| `NightSuite` (catalog-store-api, run on each store) | A job is kept by its id, the latest end winning; a night's totals add up its jobs; a night's jobs of a kind, by start; old nights are deleted. |
| `MaintenanceJobTest` (catalog-store-api) | What a job may be, and which report replaces which. |
| `catalog-server` `MaintenanceNightsTest` | A report read as a job, and what is refused; a night's totals as the route answers them. |
| `catalog-server` `MaintenanceNightsRoutesTest` | Only the worker reports; an unknown object is refused; a night adds up its jobs; each job names its table only to a caller who may select it. |
| `catalog-maintenance` `NightJobsTest` | Each compaction Job on the lowest free slot, with its own `replace` commits counted; round-the-clock work added up over the night, failures apart, the next night's under a new id. |
| `catalog-maintenance` `NightlyCleanupTest` | Through a real night, the catalog keeps each Job on its slot and each orphan scan, cut off or done. |

The bites are in `bites/D30/`:

- `job-report-from-anyone`: a report taken from someone other than the worker.
- `night-names-unreadable-tables`: the jobs name tables the caller may not select.
- `job-report-replaces-a-later-one-{sql,oracle,mongo}`: a report replaces a later end.
- `night-jobs-never-pruned-{sql,oracle,mongo}`: old nights are never deleted.
- `compaction-slot-never-freed`: a Job does not take the lowest free slot.
- `compaction-counts-every-commit`: a Job's counts take in every commit, not only `replace` ones.
- `compaction-counts-before-assessment`: a Job's counts take in commits from before the table was assessed.
- `night-tally-restarts-each-run`: each run gets a new id instead of being added to the night's sum.
