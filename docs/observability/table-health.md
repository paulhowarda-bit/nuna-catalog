# Table health and the worker's scans

The catalog keeps what the maintenance worker finds out about its tables and its own runs, so that the console, which
the catalog serves, can show it (D38, backlog 47; the console's health dashboard is backlog 48). The worker reports
each table's assessment as it makes it, each compaction Job as it starts and ends, and each scan as it starts and
ends; the catalog keeps the latest assessment and the last compaction of each table, and the recent scans.

This is the catalog's own maintenance state, like its pending deletions. It is not the engines' metrics reports, which
D13 keeps out of the database (the catalog turns them into meters, catalog-metrics.md), and it does not replace the worker's meters
(worker-metrics.md), which are what alerts are built on.

## The reads

Three routes, all answering JSON. Times are ISO-8601 instants, kept to the millisecond.

| Route | Who may call | Returns |
|---|---|---|
| `GET /management/v1/maintenance/tables` | Anyone; each table is shown only to a caller with `select` on it | The neediest tables, a page at a time |
| `GET /management/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}/health` | `select` on the table | One table's health |
| `GET /management/v1/maintenance/scans` | Platform operators (`nuna.security.platform-operator-groups`) | The worker's recent scans and its last successful one |

A caller without the privilege gets 403 `ForbiddenException`, audited like every refusal on a management route; a
table that does not exist, 404 `NoSuchTableException`.

### The neediest tables

`GET /management/v1/maintenance/tables?pageSize=50&pageToken=...` lists the active tables whose latest assessment says
they need compaction, across every catalog, neediest first: tables with equality deletes, then the highest score, then
by id. Only tables the caller may `select` are listed; the others are skipped, never named.

```json
{
  "tables": [ { "id": "...", "catalog": "lake", "namespace": ["sales"], "name": "orders", "assessment": {...}, "last-compaction": {...} } ],
  "next-page-token": "..."
}
```

- `pageSize` is 50 by default, at most 200.
- `next-page-token` is there while more tables may follow; pass it back as `pageToken`. A page may hold fewer than
  `pageSize` tables, even none, when the caller may read few of those it looked at (it looks at 2,000 at most), so
  keep reading while there is a token.
- The order is the order the worker starts Jobs in, so the head of the list is what the next night compacts first,
  window and Job slots permitting.

### One table's health

`GET .../tables/{table}/health` answers the table's health whether or not it needs compaction. Before the worker has
assessed it, `assessment` is absent; before any Job has run for it, `last-compaction` is.

```json
{
  "id": "6c1d...",
  "catalog": "lake",
  "namespace": ["sales"],
  "name": "orders",
  "assessment": {
    "assessed-at": "2027-03-27T23:00:00.123Z",
    "needs-compaction": true,
    "reasons": ["equality-deletes", "small-files"],
    "score": 2.0,
    "data-files": 40,
    "small-files": 10,
    "small-file-share": 0.25,
    "delete-files": 3,
    "position-delete-files": 1,
    "deletion-vectors": 1,
    "equality-delete-files": 1
  },
  "last-compaction": {
    "started-at": "2027-03-27T23:00:01Z",
    "ended-at": "2027-03-28T01:00:00Z",
    "outcome": "cut-off"
  }
}
```

The same object is each item of the neediest list.

| Field | Meaning |
|---|---|
| `assessed-at` | When the worker read the table's manifests, by its clock. |
| `needs-compaction`, `reasons` | The verdict and why: `small-files`, `deletes`, `equality-deletes` (catalog-maintenance/README.md, "Compaction on need"). No reason, no need. |
| `score` | How far past its thresholds the table is: 1 at a threshold, 2 twice past it. Equality deletes put a table first whatever its score. Kept to four decimals. |
| `data-files` | Live data files. |
| `small-files` | Small data files in partitions holding enough of them to rewrite (the policy's `compaction.min-input-files`): what compaction would rewrite. A partition with fewer counts none. |
| `small-file-share` | `small-files` over `data-files`, to four decimals; 0 for a table with no data files. |
| `delete-files` | Live delete files of every kind: the next three added up. |
| `position-delete-files` | Position-delete files (format v2). |
| `deletion-vectors` | Deletion vectors (format v3, Puffin). |
| `equality-delete-files` | Equality-delete files, which every reader must join against. |
| `last-compaction.outcome` | `running`, `succeeded`, `failed`, or `cut-off` when the cleanup window's end stopped it. |
| `last-compaction.started-at`, `ended-at` | When the worker started the Job, or found tonight's already there after a restart; when it saw it end. No `ended-at` while it runs. |

### The scans

`GET /management/v1/maintenance/scans` returns the last 50 scans, the last started first, and the last that succeeded,
kept even when 50 failed scans have come since.

```json
{
  "scans": [
    {
      "id": "0b7e...",
      "worker": "nuna-maintenance",
      "started-at": "2027-03-28T01:00:00Z",
      "ended-at": "2027-03-28T01:00:04Z",
      "outcome": "succeeded",
      "failures": { "orphan-scan": 1 }
    }
  ],
  "last-success": { ... }
}
```

- `worker` is the maintenance principal that ran it.
- `outcome` is `succeeded` when the scan read the whole work list, as the worker's meters judge it, even if some jobs
  in it failed; `failed` when it could not. Absent while the scan runs, as is `ended-at`; a worker that died
  mid-scan leaves its scan that way.
- `failures` counts the jobs that failed, by kind (worker-metrics.md, "Failure kinds").
- `error`, for a failed scan only: why, the first failed work-list read, at most 1,000 characters.
- A scan the worker could not report at all, because it could not reach the catalog, is not there. The worker's
  meters still count it.

## The worker's reports

The worker reports as a maintenance principal acting as itself (`nuna.security.maintenance-principals`), as with its
other management calls; anyone else gets 403. Each answers 204.

| Route | Body |
|---|---|
| `PUT /management/v1/maintenance/scans/{scan id}` | `started-at`; once it has ended, `ended-at`, `outcome`, `failures` and for a failed scan `error` |
| `PUT /management/v1/maintenance/tables/{table id}/assessment` | The table's `catalog`, `namespace` and `name`, then `assessed-at`, `data-files`, `small-files`, `position-delete-files`, `deletion-vectors`, `equality-delete-files`, `reasons` and `score` |
| `PUT /management/v1/maintenance/tables/{table id}/compaction` | The table's `catalog`, `namespace` and `name`, then `started-at`, `outcome` and once it has ended `ended-at` |

- **The table must still be the one the worker read.** A report names the table by id and by the name the work list
  gave it. It is refused with 404 `NoSuchTableException` when the table has been dropped, or the name now names
  nothing, and with 409 `TableReplacedException` when the name now names another table. The worker logs the refusal
  and goes on.
- **The newest wins.** An assessment older than the one kept changes nothing. A compaction that started before the one
  kept changes nothing, and nor does one reported running once it has ended. A scan that has ended keeps its end.
- **Bookkeeping, not changes.** The reports change no table and write no audit record; a refused report is audited.
- **The worker never waits on them.** A report the catalog does not take is logged and counted as a `report` failure
  (worker-metrics.md), and the work goes on.
- A purged table's health goes with it.

## Where it is kept

Two new tables (collections on MongoDB), added by one schema step on each store. The step only adds, so older servers
keep serving while the first new one migrates, and the rolling upgrade stays safe.

| Store | Step | What it adds |
|---|---|---|
| PostgreSQL | `V8__table_health.sql` | `table_health`, `maintenance_scan` |
| CockroachDB | `V7__table_health.sql` | `table_health`, `maintenance_scan` |
| Oracle | `V9__table_health.sql` | `table_health`, `maintenance_scan` |
| MongoDB | step 7 (`MongoSchema`) | indexes on `table_health` (the neediest order, needy tables only) and `maintenance_scan` |

## Tests and bites

| Test | Shows |
|---|---|
| `HealthSuite` (catalog-store-api, run on each store) | The latest assessment kept; reports for gone tables refused; the last compaction kept; the neediest order and paging; a purge deletes the health; recent scans and the last success kept. |
| `catalog-server` `HealthRoutesTest` | The worker's reports make a table's health; only the worker reports; a report for a table gone or now another is refused; a reader sees only the neediest tables they may select; operators read the scans. |
| `catalog-maintenance` `NightlyCleanupTest`, `ScanMetricsTest` | Through a real night, the catalog keeps each assessment, how each Job stands and ended, and each scan, as the worker reports them. |

The bites are in `bites/D38/`: a report taken from someone other than the worker, and the neediest list unfiltered by
grants.
