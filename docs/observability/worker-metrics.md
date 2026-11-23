# The maintenance worker's meters

The maintenance worker (`catalog-maintenance`, `nuna-maintenance`) records what each scan did, how long it took and
when one last succeeded, with Micrometer (backlog 40), and how the night's compaction went (backlog 46). They go out over OTLP with the worker's other telemetry
([README.md](README.md)); the worker has no HTTP port, so Prometheus gets them through the collector. Every meter is
registered when the worker starts, so a dashboard or an alert sees zeros before the first scan ends, not nothing.

The code is `WorkerMetrics` and `ScanReport` in `catalog-maintenance/src/main/java/nuna/maintenance/`.

## When a scan succeeds

Every `scan-interval` (10 minutes by default) the worker reads the catalog's work list, page by page, and works
through its tables, purges and due deletions (`catalog-maintenance/README.md`, "What a scan does").

- A scan **succeeds** when it has read the whole work list: every page of all three sections.
- A job that fails inside it (one table's expiry, one purge, one object's deletions) is counted under
  `nuna.maintenance.failures` by its kind and tried again at the next scan. The scan still succeeds: the worker did
  scan, and the failure has its own count.
- A scan **fails** when a page of the work list cannot be read (the catalog down or unreachable, the worker's token
  refused, its identity provider down), or when it stops on an unexpected error. What it did before that is counted.

## Meters

Labels hold only bounded values: a failure's `kind`, a scan's `outcome`, a compaction `reason` and a catalog ref's
kind (`ref.kind`, D33), and on the three timers the spans make (backlog 49), a `part`, an `exception`'s simple name,
a call's `method`, route and `status`. Never a table, namespace, file, ref's name or person, with one exception, `nuna.maintenance.table.compaction.need` ("The needy
tables" below).

| Meter | Type | Labels | What it records | Supports |
|---|---|---|---|---|
| `nuna.maintenance.scan` | timer | `outcome`: `success`, `failure` | Each scan's duration. Its count is the number of scans by outcome. | Scans slower than the interval; the scans panel |
| `nuna.maintenance.scan.last.success` | gauge, seconds since the epoch | none | When the last successful scan ended, by the worker's clock. `0` until the worker's first succeeds. | Worker not scanning; worker gone |
| `nuna.maintenance.failures` | counter | `kind`, below | Failed jobs and work list reads. | A job failing at every scan; purges failing; deletions failing |
| `nuna.maintenance.tables` | counter | none | Tables the work list named, summed over scans. | Panel: tables per scan (divided by the scan count) |
| `nuna.maintenance.snapshots.expired` | counter | none | Snapshots expired. | Panel |
| `nuna.maintenance.manifests.rewritten` | counter | none | Manifests rewritten. | Panel |
| `nuna.maintenance.orphan.scans` | counter | none | Orphan scans, each listing one table's location. | Panel |
| `nuna.maintenance.orphans.found` | counter | none | Unreachable files an orphan scan found old enough, recorded for deletion. | Panel: a rise means writers leave files behind |
| `nuna.maintenance.tables.assessed` | counter | none | Tables whose current manifests were read, in the cleanup window, to judge whether they need compaction: each table whose policy turns compaction on, once a night. | Panel: assessed against needing |
| `nuna.maintenance.tables.needing.compaction` | counter | `reason`, below | Tables an assessment found needing compaction, once for each reason they have. | Panel: needs by reason |
| `nuna.maintenance.compactions.started` | counter | none | Compaction Jobs started: at most one per table a night, the neediest first. | Panel |
| `nuna.maintenance.compactions.cut.off` | counter | none | Compaction Jobs the cleanup window's end stopped: still running when it closed, or stopped by Kubernetes at their deadline. The file groups they finished stay committed. | Compaction falling behind |
| `nuna.maintenance.tables.left.over` | counter | none | Tables that needed compaction and got no Job before the window closed, counted by the first scan after it closes. They are assessed again the next night. | Compaction falling behind |
| `nuna.maintenance.table.compaction.need` | gauge | `catalog`, `namespace`, `table` | For each table that needed compaction at its last assessment, how far past its thresholds it was: `1` at a threshold, `2` twice past it. | Panel: the neediest tables |
| `nuna.maintenance.files.recorded` | counter | none | Files handed to the catalog's pending-deletion list (some may be pending already). | Panel: recorded against deleted |
| `nuna.maintenance.files.outside` | counter | none | Unreferenced files left alone because they lie outside their table's location. | Files outside a table |
| `nuna.maintenance.purges.completed` | counter | none | Dropped objects purged. | Purges failing |
| `nuna.maintenance.purges.skipped` | counter | none | Purges another worker holds, or the catalog refused or rate-limited. | Panel |
| `nuna.maintenance.files.deleted` | counter | none | Files deleted: due pending deletions and purged objects' files. | Deletions failing |
| `nuna.maintenance.files.kept` | counter | `ref.kind`: `branch`, `tag` | Files left alone because a catalog ref reaches them (D33, item 108): a file a job left unreferenced on main that a branch or tag still reaches, not recorded; a due file that a ref made since it was recorded reaches, reported done without being deleted. A file both kinds reach counts for the branch. | Panel: what refs cost in storage |
| `nuna.maintenance.refs.warned` | counter | none | Branches whose expiry warning the worker sent (D33, choice 11). | Panel |
| `nuna.maintenance.refs.ended` | counter | none | Branches past their expiry the worker ended. | Panel |
| `nuna.maintenance.scan.run` | timer | `outcome`, `exception`, `error` | Each scan, as its span's observation (worker-spans.md): the same durations as `nuna.maintenance.scan`, which alerts use. Registered at the first scan. | None of its own |
| `nuna.maintenance.part` | timer | `part`, `outcome`, `exception`, `error` | Each part of a scan, as its span's observation: a work list page, a table's load, each job, a purge, a deletion, a report. Registered at first use. | Panel: where a scan's time goes |
| `http.client.requests` | timer | `method`, `uri` (a route), `status`, `outcome`, `error` | Each call to the catalog and to the identity provider, Micrometer's (`micrometer-java11`). Registered at first use. | Panel: the catalog's answers to the worker |

Micrometer also keeps a long-task timer for each of the last three, `<name>.active`, counting those under way, labelled
as they were when they started.

The reasons of `nuna.maintenance.tables.needing.compaction` (the thresholds are maintenance policy keys,
`catalog-maintenance/README.md`, "Compaction on need"):

| `reason` | The table has |
|---|---|
| `small-files` | In one partition, at least `compaction.min-input-files` data files under 75% of `compaction.target-file-size-bytes`. |
| `deletes` | More delete files (position deletes, deletion vectors, equality deletes) than `compaction.delete-file-ratio` of its data files. |
| `equality-deletes` | Any equality-delete file. |

The kinds of `nuna.maintenance.failures`:

| `kind` | What failed |
|---|---|
| `work-list` | Reading a page of the work list. The scan fails. |
| `table-load` | Loading a table the work list named. |
| `table-changed` | The table under that name is now another table, by id, so nothing ran on it. Normal after a drop and recreate between the work list read and the load; the next scan sees the new table. |
| `snapshot-expiry` | Expiring a table's snapshots, or recording the files it left unreferenced. |
| `manifest-rewrite` | Rewriting a table's manifests, or recording files. |
| `orphan-scan` | Scanning a table's location for orphans, or recording them. |
| `compaction` | Assessing a table's need (reading its manifests), or launching its compaction Job. |
| `purge` | Claiming, deleting or completing one dropped object's purge. |
| `deletion` | Deleting one object's due files, checking them against the refs made since they were recorded, or reporting them done. |
| `refs` | Warning of or ending one branch the work list's refs section named. A branch renewed, ended or warned of since the list was read is not a failure. |
| `report` | Reporting a scan, an assessment or a compaction Job to the catalog, which keeps them for the console (table-health.md). The work goes on. A report the catalog refuses because the table is gone or now another is not a failure. |

Each failure is also a WARN log line naming the table or object, which the meters never do.

## The needy tables

`nuna.maintenance.table.compaction.need` is the one meter labelled by table, as Paul accepted (D30, amended
2026-11-15): a series for every table that needs compaction, labelled with its catalog, its namespace (levels joined
with `.`) and its name, with no cap. Everyone with access to the metrics, Grafana included, sees those names.

- A table gets its series at the first assessment that finds it needy and keeps it while it stays needy; the next
  assessment that finds no need removes it. A renamed table's series moves to its new name. A table not assessed in a
  night (dropped, its compaction turned off, under a DR freeze) loses its series when that night's window closes.
- The value is the larger of its small files over `compaction.min-input-files` and its delete files' share of its
  data files over `compaction.delete-file-ratio`. A table with equality deletes needs compaction whatever its value,
  and its Job starts first.
- There are as many series as needy tables, so a catalog of many neglected tables makes many: that is what the series
  is for, and the fix is in the tables' writers.

The log names each needy table too, at INFO, with its counts, and each Job started, finished, cut off or left over.

## The DR verifier

With a DR replica named (`nuna.maintenance.dr.replicas`), the worker also runs the DR replication-watermark verifier
(D37, amended 2026-11-20; [dr-watermark.md](dr-watermark.md)), every `nuna.maintenance.dr.verify-interval` (1 minute
by default) on a thread of its own. Its meters are registered when it starts, and are absent when no replica is
named. Like the others, they name no table: the readout `GET /management/v1/dr/watermarks` names the tables behind.

A pass **succeeds** when it has read the whole DR work list; a table or view whose check or report failed is counted
under `nuna.maintenance.dr.failures` and checked again at the next pass. A pass **fails** when a page of the work list
cannot be read.

| Meter | Type | Labels | What it records | Supports |
|---|---|---|---|---|
| `nuna.maintenance.dr.pass` | timer | `outcome`: `success`, `failure` | Each pass's duration. | Panel |
| `nuna.maintenance.dr.pass.last.success` | gauge, seconds since the epoch | none | When the last successful pass ended. `0` until the first. | DR verifier not passing |
| `nuna.maintenance.dr.tables.behind` | gauge | none | Tables and views whose DR watermark is below their pointer after the last successful pass. | Panel |
| `nuna.maintenance.dr.lag.max` | gauge, seconds | none | The largest DR lag after the last successful pass: the time since the oldest commit the DR store does not yet hold whole. `0` when every table is at its watermark. | DR lagging |
| `nuna.maintenance.dr.versions.verified` | counter | none | Versions the watermarks moved past. | Panel |
| `nuna.maintenance.dr.failures` | counter | none | Tables and views whose check (the DR store unreachable, a metadata file unreadable) or report failed. | Panel; the WARN lines name each one |

In Prometheus they are `nuna_maintenance_dr_pass_milliseconds_count` and `_sum` by `outcome`,
`nuna_maintenance_dr_pass_last_success_seconds`, `nuna_maintenance_dr_tables_behind`,
`nuna_maintenance_dr_lag_max_seconds`, `nuna_maintenance_dr_versions_verified_total` and
`nuna_maintenance_dr_failures_total`. Its two alerts are under Alerts below.

## Names in Prometheus

The names above are Micrometer's. Through the OpenTelemetry collector's Prometheus exporter, or Prometheus's own OTLP
receiver, with their default translation, dots become underscores, the unit is added and counters end in `_total`:

| Micrometer | Prometheus |
|---|---|
| `nuna.maintenance.scan` | `nuna_maintenance_scan_milliseconds_count`, `_sum`, by `outcome`. Over OTLP Micrometer sends times in its base time unit, milliseconds (`management.otlp.metrics.export.base-time-unit`); `TelemetryTest` checks the unit. |
| `nuna.maintenance.scan.last.success` | `nuna_maintenance_scan_last_success_seconds` |
| `nuna.maintenance.failures` | `nuna_maintenance_failures_total`, by `kind` |
| `nuna.maintenance.tables.needing.compaction` | `nuna_maintenance_tables_needing_compaction_total`, by `reason` |
| `nuna.maintenance.table.compaction.need` | `nuna_maintenance_table_compaction_need`, by `catalog`, `namespace`, `table` |
| `nuna.maintenance.tables` and the other counters | `nuna_maintenance_tables_total` and so on |

The resource attributes (`service.name`, the pod) become the series' `job` and `instance` labels, or labels of their
own, as the collector or Prometheus is set up. The rules below assume the default translation and group by `job`;
check both against work's Prometheus when they are written there (items 43 and 44).

## Alerts

`600` below is the worker's `scan-interval` in seconds, and `20m` two of them; use the deployment's own. These rules,
named, are in `deploy/grafana/rules/nuna-maintenance.rules.yaml` (dashboards-and-alerts.md), a ticket there as
`severity: warning`.

**Worker not scanning** (page). No scan has succeeded for three intervals:

```
time() - max by (job) (max_over_time(nuna_maintenance_scan_last_success_seconds[1d])) > 3 * 600
```

A new worker process reads `0` until its first scan ends; `max_over_time` keeps the last success from before a
restart, so a restart does not fire it, and a worker that crashes before each scan ends does once three intervals have
passed. It also fires when the catalog or the worker's identity provider has been unreachable that long, and when one
scan has run for three intervals (a scan succeeds only at its end). A worker with no success in the last day reads `0`
and fires, and so does a new deployment until its first scan ends: give the rule a `for:` longer than a first scan,
which runs every table's orphan scan.

**Worker gone** (page). No series at all, so the rule above has nothing to compare: the worker is not running, or its
telemetry does not reach the collector:

```
absent_over_time(nuna_maintenance_scan_last_success_seconds[15m])
```

**Purges failing** (ticket). Purges fail and none completes, so dropped objects' files stay in storage. The rule
waits two scan intervals before it fires (its `for: 20m`; D43, item 80), so a failure the next scan's retry clears
opens no ticket:

```
sum by (job) (increase(nuna_maintenance_failures_total{kind="purge"}[1h])) > 0
  and sum by (job) (increase(nuna_maintenance_purges_completed_total[1h])) == 0
```

**Deletions failing** (ticket). Due deletions fail and nothing is deleted, often the worker's storage identity or the
bucket's delete policy (`catalog-maintenance/README.md`, "The identity it needs"). Like purges failing, it waits two
scan intervals (`for: 20m`):

```
sum by (job) (increase(nuna_maintenance_failures_total{kind="deletion"}[1h])) > 0
  and sum by (job) (increase(nuna_maintenance_files_deleted_total[1h])) == 0
```

**A job failing at every scan** (ticket). Failures of one kind in every half hour for three hours (the rule's `for:
3h`); the WARN lines name the table or object:

```
sum by (job, kind) (increase(nuna_maintenance_failures_total[30m])) > 0
```

**Scans slower than the interval** (ticket). On average over an hour a scan takes longer than the interval, so the
worker spends most of its time scanning and the next scan starts late (a scan never overlaps the next). The work list
has outgrown one worker, or a store or the catalog is slow:

```
sum by (job) (rate(nuna_maintenance_scan_milliseconds_sum[1h]))
  / sum by (job) (rate(nuna_maintenance_scan_milliseconds_count[1h])) > 600 * 1000
```

**Compaction falling behind** (ticket). Needy tables were left over at the window's end, or Jobs cut off by it,
night after night (the rule's `for: 3d`). The window is too short for the work, or `compaction.max-jobs` too low:

```
sum by (job) (increase(nuna_maintenance_tables_left_over_total[1d]))
  + sum by (job) (increase(nuna_maintenance_compactions_cut_off_total[1d])) > 0
```

**Files outside a table** (ticket). A table writes files outside its own location (a `write.data.path` elsewhere),
which the worker never records or deletes, so they stay in storage:

```
sum by (job) (increase(nuna_maintenance_files_outside_total[1d])) > 0
```

**DR lagging** (page; D37, amended 2026-11-20). Some commit has not been whole in the DR store for more than 15
minutes (the rule's `for: 5m` rides out one slow pass). Replication is behind or stopped, or the verifier cannot read
the DR store; the readout `GET /management/v1/dr/watermarks` names the tables, dr-watermark.md says what to do:

```
max by (job) (nuna_maintenance_dr_lag_max_seconds) > 900
```

**DR verifier not passing** (page). No DR pass has succeeded for 15 minutes, so the lag above is stale. Only a worker
with a DR replica named has the series:

```
time() - max by (job) (max_over_time(nuna_maintenance_dr_pass_last_success_seconds[1d])) > 900
```

## Adding a meter

Items 46 (compaction on need), 47 (the reports the catalog keeps, table-health.md) and 49 (the worker's spans,
worker-spans.md) built on these.

- A new count of a scan: a field in `ScanReport` and one line in `WorkerMetrics.COUNTS`. A new kind of failure: a
  value of `ScanReport.Failure`. Both are registered when the worker starts.
- Register a new meter when the worker starts, not when it first has something to count: Prometheus misses the first
  increase of a counter that appears already above zero.
- Labels stay bounded. The one exception Paul accepted is item 46's series for each needy table,
  `nuna.maintenance.table.compaction.need`, labelled with catalog, namespace and table name (D30, amended
  2026-11-15); `WorkerMetricsTest.assertBoundedLabels` allows it and no other.
- A meter name keeps one set of label keys: a Prometheus registry refuses a second. An Observation makes a timer of
  its own name with an `error` label, so the scan's Observation is `nuna.maintenance.scan.run`, apart from
  `nuna.maintenance.scan`, and an attribute only some parts have (`section`, `report`) goes on the span alone, as a
  high-cardinality key value (worker-spans.md).
- List the meter here: `WorkerMetricsTest#theDocsListEveryMeter` fails until it is.

## Tests and bites

| Test | Shows |
|---|---|
| `WorkerMetricsTest` | Every meter reads zero before the first scan; a scan adds each count, each need by reason, the files kept by ref kind and each failure by kind; only a scan that read the whole work list moves the last success, and one that throws is still counted; labels hold only a kind, an outcome, a reason or a ref's kind, save the needy tables' gauge, which a needy table gets, keeps under its current name and loses once it needs none or is not assessed; this page lists every meter. |
| `ScanMetricsTest` | Real scans of a catalog: one that expires, rewrites, finds an orphan, finds a table needing compaction for its small files and starts its Job, purges and skips a purge is counted as its report says and dated by the worker's clock, and the needy table has its gauge; one whose token source fails is a failure of kind `work-list`, cannot report itself to the catalog either (`report`), and leaves the last success where it was; the next moves it; one whose store fails counts its orphan scan, purge and deletion failures by kind and still succeeds. |
| `NightlyCleanupTest` | Scans across a cleanup window: tables assessed once a night, needs by reason, one Job at a time, the neediest first, the Job cut off and the table left over at the window's end, the needy tables' gauges; the catalog keeps each assessment, Job and scan the worker reports. |
| `TelemetryTest` | The worker's meters, from Boot's registry, arrive over OTLP: the failed first scan's `work-list` failures, its timer in milliseconds, the last success at `0`. |
| `DrVerifierTest` | The DR verifier's meters read zero before the first pass; a pass sets the tables behind, the largest lag and the last success, counts versions verified and failed checks, and a pass that cannot read the work list leaves the gauges; this page lists every DR meter. |

Each guarded rule's bite is in `bites/OBS-40/`; those of compaction on need are in `bites/D30/`; the DR verifier's
are in `bites/D37/`.
