# The maintenance worker's spans

Backlog 49. Each scan of the maintenance worker (`nuna-maintenance`) is a trace: a span for the scan at its root, a
span for each part of the scan beneath it, and beneath those a span for each call a part makes to the catalog. Those
calls carry the trace to the catalog as a W3C `traceparent`, so the catalog's own spans for answering them (README.md,
"Traces"; spans.md) join the scan's trace, and the catalog's request ID for each call is the scan's trace ID (README.md,
"The request ID"). The spans go out over OTLP with the worker's other telemetry when a traces endpoint is set.

Sampling decides per scan: a deployment keeps 10 % of new traces (README.md, "Sampling"), so one scan in ten is traced
end to end, the catalog's spans included, and the rest not at all.

## The spans

| Span | Observation (timer) | One per | Attributes |
|---|---|---|---|
| `maintenance scan` | `nuna.maintenance.scan.run` | scan, every `scan-interval`; the trace's root | `outcome`, `exception` |
| `maintenance <part>` | `nuna.maintenance.part` | part of a scan, below | `part`, `outcome`, `exception`; `section` or `report` on two parts |
| `HTTP GET`, `HTTP PUT`, `HTTP POST` | `http.client.requests` | call to the catalog's management API, or to the identity provider for a token, through the JDK's client | `method`, `uri`, `status`, `outcome` |

The parts, each a child of the scan's span, save an assessment's report, a child of its `compaction-assessment`:

| `part` | One per | `outcome` |
|---|---|---|
| `work-list` | page of the work list read; `section` is `tables`, `purges` or `deletions` | `ok` |
| `table-load` | table loaded by name through the catalog's Iceberg REST API, before its jobs | `loaded`, or `changed` when another table now has the name |
| `snapshot-expiry` | table whose snapshots are due to expire, recording the files left unreferenced | `ok` |
| `manifest-rewrite` | table whose manifests are rewritten, recording the files left unreferenced | `ok` |
| `compaction-assessment` | table assessed in the cleanup window, from its manifests | `ok` |
| `compaction-launch` | compaction Job started from the night's queue | `started`, `exists` (started before), `off` |
| `orphan-scan` | table scanned for orphans in the cleanup window | `ok`, also when the window's end cut it short |
| `purge` | dropped object: claim, delete its files, complete | `purged`, or `skipped` when another worker holds it or the catalog refused or rate-limited the claim |
| `deletion` | object whose due files are deleted and reported done | `ok` |
| `report` | report to the catalog of the worker's own work (table-health.md); `report` is `scan-start`, `scan-end`, `assessment` or `compaction` | `kept`, or `refused` when the table is gone or now another |

Every part's `outcome` is `error` when it threw, and `exception` the simple name of what it threw (else `none`). A
scan's `outcome` is its timer's (worker-metrics.md): `success` when it read the whole work list, else `failure`.

A failed span has status ERROR, and its exception event names the exception's class and nothing else: no message,
cause or stack trace, which name tables, their ids and their locations (`ScanSpans.Failed`, as the catalog's
`CallFailed`). The log's WARN line for the failure names them.

Beneath `table-load`, `snapshot-expiry`, `manifest-rewrite` and `orphan-scan` the catalog's spans for Iceberg's REST
requests (the client's configuration, the table's load, a commit) hang straight from the part's span: Micrometer does
not instrument Iceberg's client, so it makes no client span of its own. Each request carries the part's trace all the
same (`BearerAuthManager` adds it, `TraceHeaders`). Reads and deletes in the object store make no span of the worker's.

## What a scan looks like

From `WorkerTraceTest`, a scan of one table due for snapshot expiry, the worker and the catalog exporting to one
receiver:

```
maintenance scan                         success
  maintenance report        scan-start   kept
    HTTP PUT  /management/v1/maintenance/scans/{id}
      http put /management/v1/maintenance/scans/{id}           the catalog's span, and its store calls
  maintenance work-list     tables       ok
    HTTP GET  /management/v1/maintenance/work
      http get /management/v1/maintenance/work
  maintenance table-load                 loaded
    http get /v1/config                                        Iceberg's client, the catalog's spans
    http get /v1/{prefix}/namespaces/{namespace}/tables/{table}
  maintenance snapshot-expiry            ok
    http get /v1/{prefix}/namespaces/{namespace}/tables/{table}
  maintenance work-list     purges       ok
  maintenance work-list     deletions    ok
  maintenance report        scan-end     kept
```

## What attributes never hold

No attribute of the worker's spans names a table, a namespace, a location, a catalog or a person, or holds a table's,
object's or scan's id: each value comes from the small fixed sets above. A call's `uri` is its route, its ids
replaced by `{id}` and the catalog's name by `{catalog}` (`/management/v1/catalogs/{catalog}/objects/{id}/purge-claim`),
without its query (`Management.route`). The token endpoint's `uri` is its path, set by the deployment. The catalog's
server spans in the same trace still record the request's path (README.md, "What labels and attributes never hold").

`section` and `report` are on the span alone, not on the `nuna.maintenance.part` timer: a timer's name keeps one set of
labels (worker-metrics.md, "Adding a meter").

## The timers

Each observation is also a timer of its name (worker-metrics.md lists them): `nuna.maintenance.scan.run` and
`nuna.maintenance.part` by `outcome`, `exception` and Micrometer's `error` (`Failed` for every failure), the part's by
`part` too; `http.client.requests` by `method`, `uri`, `status`, `outcome` and `error`; and for each, a long-task timer
`<name>.active` of those under way. The scan timer
`nuna.maintenance.scan` stays what alerts are built on: it is registered when the worker starts, these at their first
use.

## Code, tests and bites

`ScanSpans` (catalog-maintenance, `nuna.maintenance`) makes the scan's and the parts' observations; `Worker`,
`NightlyCompaction` and `HealthReports` call it. `Worker.of` wraps the worker's JDK client in Micrometer's
(`micrometer-java11`'s `MicrometerHttpClient`), for its management calls and its token requests; `TraceHeaders` and
`BearerAuthManager` carry the current span to Iceberg's client. `WorkerApplication` hands the worker Boot's
`ObservationRegistry`, `Tracer` and `Propagator`.

| Test | Shows |
|---|---|
| `WorkerTraceTest` | The worker as a service, against a catalog, both exporting to one OTLP receiver: its first scan is one trace, the scan at the root; the catalog's span for a work list read is the child of the JDK client's span, whose parent is the `work-list` part; the catalog's span for a table's load, through Iceberg's client, is the child of the `table-load` part; a report's call has its route as `uri`; no worker span's attribute names the table, its namespace, location or catalog, or the worker's principal, and each carries only the attributes above. |
| `ScanSpansTest` | On a registry that keeps each observation: the scan is the root and each part its child, with its outcome; a scan that could not read the work list fails; a failure records its type, never its message, cause or stack; a call's `uri` is its route. |

Each guarded rule's bite is in `bites/OBS-49/`.
