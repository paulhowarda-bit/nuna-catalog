# Table health and the worker's scans

The catalog keeps what the maintenance worker finds out about its tables and its own runs, so that the console, which
the catalog serves, can show it (D38, backlog 47; the console's health dashboard is backlog 48). The worker reports
each table's assessment as it makes it, each compaction Job as it starts and ends, and each scan as it starts and
ends; the catalog keeps the latest assessment and the last compaction of each table, and the recent scans.

This is the catalog's own maintenance state, like its pending deletions. It is not the engines' metrics reports, which
D13 keeps out of the database (the catalog turns them into meters, catalog-metrics.md), and it does not replace the worker's meters
(worker-metrics.md), which are what alerts are built on.

## The reads

Five routes, all answering JSON. Times are ISO-8601 instants, kept to the millisecond.

| Route | Who may call | Returns |
|---|---|---|
| `GET /management/v1/maintenance/tables` | Anyone; each table is shown only to a caller with `select` on it | The neediest tables, a page at a time |
| `GET /management/v1/maintenance/estate` | Anyone; each table is shown only to a caller with `select` on it | Every table, one row each, a page at a time |
| `GET /management/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}/health` | `select` on the table | One table's health |
| `GET /management/v1/maintenance/scans` | Platform operators (`nuna.security.platform-operator-groups`) | The worker's recent scans and its last successful one |
| `GET /management/v1/catalogs/{catalog}/maintenance/readiness` | `describe` on the catalog; names only to those who may `modify` it or manage its grants, and platform operators | Whether the worker can maintain the catalog, step by step |

A refusal is 403 `ForbiddenException`, except on a table's health to a caller that holds nothing on the table: that is
404 `NoSuchTableException`, the answer for a table that does not exist (D18, backlog 73), and on readiness to a caller
that holds nothing in the catalog: 404 `NoSuchWarehouseException`, as for a catalog that does not exist (item 68).
Every refusal is audited, as on every management route; a read that is answered is not.

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

### Every table

`GET /management/v1/maintenance/estate?pageSize=200&pageToken=...` gives one row for every active table the caller
may `select`, across every catalog, by id. It is for the console's ice line and the tree's marks (D45, backlog 127). The
neediest list leaves out the tables in shape, so it cannot draw them above the line. A table the caller may not select
is skipped: it is neither named nor counted.

```json
{
  "night": "2026-11-21",
  "tables": [
    {
      "id": "6c1d...",
      "catalog": "lake",
      "namespace": ["sales"],
      "name": "orders",
      "assessment": {
        "assessed-at": "2026-11-22T06:50:00.123Z",
        "needs-compaction": true,
        "reasons": ["small-files"],
        "score": 3.4,
        "data-files": 2316
      },
      "held": true,
      "frozen": false,
      "closed": false,
      "fixed-last-night": false
    }
  ],
  "next-page-token": "..."
}
```

| Field | Meaning |
|---|---|
| `night` | Last night: the newest night that has a job, as `GET /management/v1/maintenance/nights` counts them (maintenance-nights.md). Absent before the worker has reported any job. |
| `assessment` | The latest assessment's verdict, reasons and score, and the data-file count, as in a table's health (below). Absent until the worker has assessed the table. A table with no reason is in shape. |
| `held` | A legal hold is on the table, or on a namespace or catalog above it. |
| `frozen` | A DR freeze is on the table, or on a namespace or catalog above it. |
| `closed` | The table is closed until classified (D27): it lies in a regulated area and has no classification of its own. |
| `fixed-last-night` | Last night's compaction Job of the table succeeded. A Job cut off, failed or still running fixed nothing. |

- **Who counts.** A table counts when the authorizer allows the caller `select` on it. It also counts when the
  authorizer refuses only because the table is closed until classified: the caller holds the grant, and the closure
  keeps the data closed, not the name. Such a row has `closed` set. A caller whose grants give no `select` on a table
  never sees it, closed or not.
- **Paging.** `pageSize` is 200 by default, from 1 to 500. A page may hold fewer rows than `pageSize`, even none,
  when the caller may select few of the tables it looked at. It looks at 2,000 tables at most. `next-page-token` is
  there while more tables may follow, so keep reading while there is a token. A token the route did not issue is 400
  `BadRequestException`.
- **No totals.** The route counts nothing across pages, since that would read every table on every call. The console
  counts the rows it has read: in shape, queued, held, frozen, closed and fixed.
- **The cost of a page.** The active tables are read in batches no larger than the rows the page still wants, and
  each batch is authorized in one call. Then each row's health is read on its own, holds and attributes are read once
  for every 500 levels, and last night's compaction Jobs are read once.

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
  },
  "requests": [
    {
      "job": "compaction",
      "requested-by": "ana",
      "requested-at": "2027-03-28T09:14:02.511Z",
      "state": "waiting"
    }
  ]
}
```

The same object, without `requests`, is each item of the neediest list.

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
| `requests` | The table's maintenance on request, by job, at most one each ("Maintenance on request"): `job`, `requested-by`, `requested-at`, `state` (`waiting`, or `started` once the worker reports it) and, once started, `started-at`. Empty when nothing was asked. |

### Maintenance on request

D30, amended 2026-11-20; backlog 114. A caller with `modify` on a table asks for its compaction or its orphan scan to
run now, inside the nightly cleanup window or outside it:

```
POST /management/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}/maintenance/requests
{"job": "compaction"}
```

`job` is `compaction` or `orphan-scan`; expiry and manifest rewrite need no request, since they run at every scan
their policy makes them due. The answer is 202 with the request as the table's health shows it, `state` `waiting`.

- **One waits per table and job.** Asking again while one waits writes nothing and answers 202 with the one waiting,
  who asked it and when. A request the worker has started is replaced by the next one asked.
- **Who may ask:** `modify` on the table, as for its maintenance policy. Without it the answer is 403, audited as a
  denial; a caller who holds nothing on the table is told it does not exist (404), as everywhere (D18).
- **The catalog's guards stay.** A DR freeze over the table refuses either job (403, as every freeze refusal is).
  A job the table's resolved policy does not run is refused with 409 `MaintenanceNotAllowedException`: turned off at
  some level, or, for an orphan scan, by a legal hold's guardrail. Each refusal is audited as a denial, its reason
  `dr_freeze`, `legal_hold` or `maintenance_policy`. A requested compaction runs whether or not the assessment says
  the table needs one: that is the point of asking.
- **Audited.** Each request taken is a governance change of the table in the audit trail, attribute
  `maintenance-request`, the job as its value, provenance `management-api`, with who asked.
- **The worker still polls.** The work list (`GET /management/v1/maintenance/work`) carries the waiting requests in a
  section of their own, `requests`, after the deletions, by the time asked, each with its table as the `tables`
  section shows it and `job`, `requested-at` and `requested-by`. A request whose job the table's policy no longer runs,
  a hold or freeze placed since included, is left off and keeps waiting. The worker takes each at its next scan, so a
  request starts within one `scan-interval`.
- **Cleared when started.** The worker reports a requested job as it reports the night's
  (`PUT /management/v1/maintenance/jobs/{id}`, maintenance-nights.md), with `requested-at` naming the request; the
  first such report marks it started. A report naming a request no longer waiting, or another time, changes nothing.
- **Deadline.** A requested compaction Job stops at `nuna.maintenance.request-deadline` (two hours by default) after
  it starts, in place of the window's end; a requested orphan scan stops then too and records nothing
  (catalog-maintenance/README.md, "Maintenance on request").
- A purged table's requests go with it.

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
      "failures": { "orphan-scan": 1 },
      "groups": ["nuna-maintenance"],
      "storage": [
        {
          "kind": "minio",
          "endpoint": "https://silo.example:9000",
          "prefixes": [
            { "prefix": "s3://lake/tables", "listed": "ok", "deleted": 12, "delete-refused": 0, "delete-failed": 0 },
            { "prefix": "s3://ledger-warehouse/tables", "listed": "refused", "list-code": "AccessDenied",
              "deleted": 0, "delete-refused": 3, "delete-failed": 0, "delete-code": "AccessDenied" }
          ]
        }
      ]
    }
  ],
  "last-success": { ... }
}
```

- `worker` is the maintenance principal that ran it.
- `groups` are the groups its token carried when it reported (backlog 250): the groups its grants are held by. Absent
  for a scan kept before the catalog recorded them.
- `storage` is what the worker's own storage identity reached in that scan (backlog 250), one entry for each of its
  stores (`nuna.maintenance.storage`), by kind (`s3`, `minio`, `ceph`) and endpoint as the worker's settings name
  them, with the prefixes the catalog gave it there at that scan (item 256; none when the worker could not read them).
  `endpoint` is absent from a scan an earlier release reported for AWS S3's own. Each prefix has:
  - `listed`: at the scan's start the worker lists at most one key beneath the prefix, as its delete policy reads it
    (`<prefix>/`). `ok` when the store let it, `refused` when the store answered with an error, whose code is
    `list-code` (`AccessDenied`, `NoSuchBucket`, ...), and `failed` when the store did not answer, `list-code` then
    naming the client's failure (`SdkClientException`).
  - `deleted`, `delete-refused` and `delete-failed`: the objects beneath it the worker deleted in that scan, the store
    refused it (each key of a DeleteObjects answered with an error), and those it could not reach the store to delete;
    `delete-code` is the code of the first not deleted. The start's report carries 0s; the end's replaces it.

  A report names prefixes and the store's codes, never an object key, a credential or the store's message. `storage`
  is absent for a scan reported by a worker of an earlier release, and for a scan kept before the catalog recorded it.
  The deletes beneath a prefix since a given time are the sum over the scans since; the first scan in the list with
  `storage` holds what the worker last reached.
- `outcome` is `succeeded` when the scan read the whole work list, as the worker's meters judge it, even if some jobs
  in it failed; `failed` when it could not. Absent while the scan runs, as is `ended-at`; a worker that died
  mid-scan leaves its scan that way.
- `failures` counts the jobs that failed, by kind (worker-metrics.md, "Failure kinds").
- `error`, for a failed scan only: why, the first failed work-list read, at most 1,000 characters.
- A scan the worker could not report at all, because it could not reach the catalog, is not there. The worker's
  meters still count it.

### Readiness

`GET /management/v1/catalogs/{catalog}/maintenance/readiness` says, for each of the four steps the worker needs before
it maintains a catalog, whether it is done (backlog 251; the console's checklist is backlog 252). Each step is `done`,
`missing`, `refused` or `unknown`.

```json
{
  "catalog": "ledger",
  "names": true,
  "reported-at": "2026-11-24T14:05:31.204Z",
  "grant": { "state": "missing", "groups": [ { "group": "nuna-maintenance", "write": false } ] },
  "storage": {
    "state": "done",
    "profile": "ledger-s3",
    "endpoint": "https://silo.example:9000",
    "prefixes": [
      { "prefix": "s3://ledger-warehouse/tables",
        "covered-by": { "kind": "s3", "endpoint": "https://silo.example:9000", "prefix": "s3://ledger-warehouse" } }
    ]
  },
  "store": {
    "state": "refused",
    "code": "AccessDenied",
    "prefixes": [
      { "prefix": "s3://ledger-warehouse/tables", "listed": "refused", "list-code": "AccessDenied",
        "deleted": 0, "delete-refused": 0, "delete-failed": 0 }
    ]
  },
  "policy": { "state": "done", "jobs": ["snapshot-expiry", "compaction"] }
}
```

The first three steps come from the newest scan that reported `storage` ("The scans"); before any has, they are
`unknown`.

| Step | `done` when | Otherwise |
|---|---|---|
| `grant` | A group the worker's token carried holds a grant on the catalog itself whose privilege gives `write` (`write` or `modify`), as the authorizer reads it: a grant on one namespace does not count | `missing` |
| `storage` | Every allowed prefix of the catalog's storage profile is, or lies beneath, a prefix the worker reported, matched as the worker matches the locations it lists and deletes (`s3a://` and `s3n://` read as `s3://`, whole segments) | `missing` |
| `store` | The store let the worker list beneath each allowed prefix, and refused none of its deletes there | `refused`, with the store's `code`, when it refused a listing or a delete; `unknown` while a prefix is not covered (no `code`), or when the store did not answer the listing (`code` names the client's failure) |
| `policy` | The catalog's resolved maintenance policy turns at least one job on; `jobs` names them (`snapshot-expiry`, `orphan-removal`, `compaction`, `manifest-rewrite`) | `missing` |

- `reported-at` is when the worker last reported what it reaches: the end of that scan, or its start while it runs.
  Absent before any report.
- The listing is judged by the newest scan with `storage`, the deletes by the newest such scan that has ended: a scan
  reports its deletes only at its end, so one under way does not hide the refusals of the one before.
- A `refused` anywhere outweighs an `unknown` elsewhere. `code` is the first such code among the allowed prefixes.
- **Names.** `names` is true for a caller who may `modify` the catalog or manage its grants, and for platform
  operators. Only then does the answer carry the worker's `groups` (each with whether it holds `write`), the profile's
  name, its `endpoint` (absent for AWS S3's own), the allowed `prefixes`, what covers each (`covered-by`) and what the
  store answered beneath each (from the scans as above). Anyone else who may `describe` the catalog gets each step's
  `state`, the store's `code` and the policy's `jobs`.

## The worker's reports

The worker reports as a maintenance principal acting as itself (`nuna.security.maintenance-principals`), as with its
other management calls; anyone else gets 403. Each answers 204.

| Route | Body |
|---|---|
| `PUT /management/v1/maintenance/scans/{scan id}` | `started-at` and `storage` (above, "The scans"); once it has ended, `ended-at`, `outcome`, `failures` and for a failed scan `error`. The catalog keeps the groups of the worker's token with it. |
| `PUT /management/v1/maintenance/tables/{table id}/assessment` | The table's `catalog`, `namespace` and `name`, then `assessed-at`, `data-files`, `small-files`, `position-delete-files`, `deletion-vectors`, `equality-delete-files`, `reasons` and `score` |
| `PUT /management/v1/maintenance/tables/{table id}/compaction` | The table's `catalog`, `namespace` and `name`, then `started-at`, `outcome` and once it has ended `ended-at` |
| `PUT /management/v1/maintenance/jobs/{job id}` | A job of the night (maintenance-nights.md); for a job run on request, `requested-at` too, which marks the request started |

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

The requests of "Maintenance on request" are kept the same way, one more add-only step on each store: a table
`maintenance_request` keyed by table and job, with who asked, when, and when the worker started it, and an index over
the waiting ones in the order the work list reads them.

| Store | Step | What it adds |
|---|---|---|
| PostgreSQL | `V13__maintenance_requests.sql` | `maintenance_request`, and a partial index over the waiting |
| CockroachDB | `V12__maintenance_requests.sql` | `maintenance_request`, and a partial index over the waiting |
| Oracle | `V14__maintenance_requests.sql` | `maintenance_request`, and an index over the time asked |
| MongoDB | step 12 (`MongoSchema`) | the `maintenance_request` collection, unique by table and job, and an index over the time asked |

What a scan reaches (backlog 250) is two more columns of `maintenance_scan`, nullable and without a default, so the
step changes only the table's definition and a server of the previous release goes on writing scans without them:
`worker_groups` (one group a line) and `worker_storage` (a line for each store, then one for each of its prefixes,
fields separated by tabs; `WorkerStorage.write`). MongoDB keeps them as arrays in the scan's document, with no step.

| Store | Step | What it adds |
|---|---|---|
| PostgreSQL | `V17__worker_reach.sql` | `worker_groups`, `worker_storage` (`TEXT`) |
| CockroachDB | `V16__worker_reach.sql` | `worker_groups`, `worker_storage` (`TEXT`) |
| Oracle | `V18__worker_reach.sql` | `worker_groups`, `worker_storage` (`CLOB`) |

## Tests and bites

| Test | Shows |
|---|---|
| `HealthSuite` (catalog-store-api, run on each store) | The latest assessment kept; reports for gone tables refused; the last compaction kept; the neediest order and paging; a purge deletes the health; recent scans and the last success kept; a scan's groups and storage kept, a refused listing as refused, the end's in place of the start's. |
| `catalog-server` `HealthRoutesTest` | The worker's reports make a table's health; only the worker reports; a report for a table gone or now another is refused; a reader sees only the neediest tables they may select; operators read the scans, with the groups and storage each kept; a storage report holding a code where nothing was refused, a message for a code, or an unknown listing is a bad request. |
| `catalog-store-api` `WorkerStorageTest` (fast) | What a storage report may hold, the text the SQL and Oracle stores keep it as, and the reported prefix that covers a location. |
| `catalog-server` `ReadinessRoutesTest` (heavy) | Readiness: the first three steps unknown before a scan reports storage; every step done; write on a namespace or select on the catalog leaves the grant missing, a policy with every job off leaves the policy missing; a reported prefix beneath, beside or in another bucket leaves the storage missing and the store unknown; a refused listing or delete is refused with the store's code, a scan under way keeps the last ended scan's refused deletes, no answer is unknown with the client's failure; names only to the tester (modify), the grant admin and an operator, states without names to a describer and a tag steward; refused to an outsider (404), a bystander and an operator without describe (403), the refusal audited and the read not. |
| `catalog-maintenance` `StorageReportTest` (fast) | Through `Storage.of` and stand-in stores: each scan reports, by kind and endpoint, a listing let, refused (AccessDenied, NoSuchBucket) or failed, and the deletes done and refused with the store's code, counted afresh each scan; no key, credential or store message in any report. |
| `catalog-maintenance` `NightlyCleanupTest`, `ScanMetricsTest` | Through a real night, the catalog keeps each assessment, how each Job stands and ended, and each scan, as the worker reports them. |
| `catalog-server` `MaintenanceEstateTest` (fast) | Every table: a row for each table the caller may select and none for another; its assessment; a hold or freeze from any level above; a closed table counted only for a caller whose grants give select; fixed last night only by a compaction that succeeded; paging, the 500-row page and the 2,000 tables a page looks at. |
| `catalog-server` `MaintenanceEstateRoutesTest` (heavy) | Every table through the server, with real reports, holds, freezes, grants and classifications; a caller who may select nothing gets no row. |
| `HealthSuite` requests (catalog-store-api, run on each store) | A request kept; asking again while it waits changes nothing; the worker's start report clears it, a report naming another time changes nothing, and a later request replaces it; only an active table may be asked for; the waiting listed by the time asked, then table and job, a page at a time, without the started and the dropped; a purge's completion deletes the requests. |
| `catalog-server` `MaintenanceRequestsTest` (fast) | Who may ask and every refusal, each audited: `modify` asks and asking again keeps the one waiting; `select` alone is refused; a caller who holds nothing gets 404; another job is a bad request; a DR freeze refuses either job; a legal hold refuses the orphan scan, not the compaction; a policy that turns the job off refuses it. |
| `catalog-server` `MaintenanceRequestRoutesTest` (heavy) | Through the server: a request waits in the table's health, the work list carries it with the table's policy, the worker's job report naming it marks it started and takes it off the work list; a reader is refused, and so is a request under a DR freeze. |
| `catalog-maintenance` `RequestedWorkTest` (fast) | The worker runs a request in the day, outside the window: a compaction whatever its assessment, its Job named after the time asked and stopping at the request's deadline, sharing the night's slots, one Job per table at once; with compaction off it waits; an orphan scan stops at the deadline and records nothing. Each report names the request. |

The bites are in `bites/D38/`: a report taken from someone other than the worker, and the neediest list unfiltered by
grants. The estate's bites are in `bites/D45/`, each against `MaintenanceEstateTest`:

- `estate-names-unselectable-tables`: every table is a row, whether the caller may select it or not.
- `estate-closed-for-any-refusal`: a table refused for any reason counts as closed.
- `estate-examines-without-bound`: a page looks at every table until it is full.
- `estate-fixed-by-any-compaction`: a compaction cut off or failed counts as fixing its table.
- `estate-page-unbounded`: a page holds as many rows as it is asked for.

Maintenance on request has four bites in `bites/D30/`:

- `request-taken-without-modify` (`MaintenanceRequestsTest`): a request needs only `select`.
- `request-taken-under-hold` (`MaintenanceRequestsTest`): a requested orphan scan is taken under a legal hold.
- `requested-job-window-deadline` (`RequestedWorkTest`): a requested Job stops at the window's end, not the request's
  deadline.
- `requested-job-waits-for-window` (`RequestedWorkTest`): a requested job waits for the cleanup window.

What the worker reaches (backlog 250) has eight bites in `bites/D30/`:

- `worker-refused-listing-reported-ok` (`StorageReportTest`): a refused listing is reported as let, with no code.
- `worker-refused-deletes-counted-deleted` (`StorageReportTest`): keys the store refuses are counted as deleted.
- `worker-storage-code-takes-a-message` (`WorkerStorageTest`): any text is kept as a store's code, a message naming a
  key among them.
- `worker-storage-text-swaps-delete-counts` (`WorkerStorageTest`): the SQL and Oracle stores read refused deletes as
  failed and failed as refused.
- `scan-end-keeps-start-storage-sql`, `scan-end-keeps-start-storage-oracle`, `scan-end-drops-storage-mongo`
  (`HealthSuite` on each store): the end's report does not replace the start's storage.
- `scan-groups-not-recorded` (`HealthRoutesTest`): a scan is kept without the groups of the worker's token.

Readiness (backlog 251) has one bite in `bites/D30/`: `readiness-uncovered-prefix-done`
(`ReadinessRoutesTest#aPrefixNoReportedPrefixCoversIsMissingAndItsStoreUnknown`), the storage step done whenever the
worker has reported any storage, so an allowed prefix no reported prefix covers is never `missing`.
