# Catalog metrics

The catalog server's own meters (backlog 39), beside Spring Boot's standard ones. They go out with every other
metric (README.md, "What is exported"). The code is in `catalog-server`, package `nuna.catalog.server.metrics`, with
the alert counters in `rest/ServerAlerts.java`. The dashboards and alert rules (item 44, dashboards-and-alerts.md)
and the console's health page (item 48) read these names; item 43 records how they land in work's Prometheus.

Names are Micrometer's, with dots. Over OTLP Micrometer sends timers in milliseconds by default. A Prometheus backend
turns the dots into underscores and adds unit and type suffixes, so `nuna.catalog.requests` arrives as a family such
as `nuna_catalog_requests_milliseconds_bucket`.

Each server holds only its own meters. A deployment's figure is the sum over its servers.

## Labels

A catalog meter carries only these labels. Each has a bounded set of values. No meter is ever labelled with a table,
namespace, principal or filter value, nor a warehouse prefix that names no catalog. That keeps the Prometheus series
bounded, and those names may be sensitive. The table-level detail is in the log lines instead: the metrics report
line (item 37) and the alert lines on the `nuna.alerts` logger.

| Label | Values |
|---|---|
| `catalog` | The name of a catalog that exists. It is `none` for a call that named no catalog, named one that does not exist, or was refused before its catalog was looked up (a 401, for example). |
| `operation.id` | The operationId the Iceberg REST spec gives the route, such as `loadTable`, `updateTable`, `commitTransaction`, `loadCredentials`, `reportMetrics` or `getConfig`. |
| `outcome` | Set per meter; see the meters below. |
| `error.type` | The Iceberg error type the client was answered with, such as `CommitFailedException`, `NoSuchTableException` or `NotAuthorizedException`. For the token route it is the OAuth error, such as `invalid_client`. It is `none` when there was no error, and also when an error body names no type. |
| `requirement.type` | For a commit refused because a requirement failed, that requirement's type in the spec, such as `assert-ref-snapshot-id` or `assert-table-uuid`. It is `none` otherwise. |
| `report.type` | `scan-report` or `commit-report`. |
| `operation` | A commit report's snapshot operation: `append`, `replace`, `overwrite` or `delete`. Any other value the client sent is `other`, and a scan report has `none`. |
| `engine.name` | The `engine-name` in the report's `metadata`, as the client sent it, such as `spark` or `trino`. The first 20 distinct names a server sees are kept; later names count as `other`. So does a name that is not 1 to 32 letters, digits, spaces, dots, hyphens or underscores starting with a letter or digit. It is `none` when the report gives no engine name. |

## Meters

| Meter | Type | Labels | The question it answers |
|---|---|---|---|
| `nuna.catalog.requests` | Timer; histogram buckets at 10, 50 and 300 ms | `catalog`, `operation.id`, `outcome` (`2xx`, `3xx`, `4xx`, `5xx`), `error.type` | How long does each kind of Iceberg call take, and how often does it fail, and with what? |
| `nuna.catalog.commits` | Counter | `catalog`, `operation.id`, `outcome`, `requirement.type`, `error.type` | How do commits end, and how many lose to a concurrent change, by requirement? |
| `nuna.catalog.credential.vends` | Timer | `catalog`, `outcome` (`issued`, `cached`, `none`, `failed`), `error.type` | How long does vending storage credentials take, how often is it served from the cache, and how often does it fail? |
| `nuna.catalog.metadata.cache.lookups` | Counter | `outcome` (`hit`, `miss`) | How often is a table's current metadata file served from memory rather than read from the object store? |
| `nuna.catalog.alerts.metadata.integrity` | Counter | none | Has a metadata file stopped hashing to what its pointer recorded? |
| `nuna.catalog.alerts.regulated.schema.change` | Counter | none | Has a commit changed a regulated table's schema or partition spec? |
| `nuna.catalog.alerts.large.metadata` | Counter | none | Is a loaded table's metadata over the size or snapshot limit (D14)? |
| `nuna.catalog.report.*` | One timer or distribution summary per report result | `catalog`, `report.type`, `operation`, `engine.name` | What do engines report about their own scans and commits? |

### `nuna.catalog.requests`

This meter times every call to a route of the Iceberg REST spec that the catalog serves, `POST /v1/oauth/tokens`
included. Other requests (the management API, the console, the actuator) are timed only by Boot's
`http.server.requests`.

The timing starts right after the request's trace and request ID are set, before authentication, so a refused call
is timed too. It ends when the response has been written.

The histogram's bucket bounds are the build plan's SLOs (R7, M8). They are set in code (`RequestMetrics.SLO`), not in
`application.yaml`:

- Load p99 under 50 ms: `operation.id="loadTable"`, `outcome="2xx"`.
- A 304 under 10 ms: `operation.id="loadTable"`, `outcome="3xx"`. The share of loads answered 304 (D14) is the 3xx
  count over the 2xx and 3xx counts together.
- Commit p99 under 300 ms: `operation.id` `updateTable` or `commitTransaction`, `outcome="2xx"`.
- At least 500 commits a second: the rate of that count, summed over the servers.

### `nuna.catalog.commits`

There is one count for each reply of the commit engine to a route that commits. The routes are `createTable`,
`updateTable`, `registerTable`, `unregisterTable`, `commitTransaction`, `createView`, `replaceView` and
`registerView`. A staged create commits nothing, so it is not counted.

| `outcome` | Meaning |
|---|---|
| `committed` | The change was committed. |
| `unchanged` | The request changed nothing, so nothing was written. |
| `replayed` | An `Idempotency-Key` repeated a commit already made, and its first answer was given again. |
| `requirement-failed` | A requirement did not hold: 409 `CommitFailedException`. `requirement.type` names the requirement. This is contention with another writer, or a stale client. |
| `refused` | Any other refusal (4xx): a missing table, a bad update, a commit that lost to concurrent commits too many times, a governance rule. `error.type` says which. A refusal given again for a repeated `Idempotency-Key` counts here too, even one first refused by a requirement, because no requirement was checked the second time. |
| `unknown` | The commit may or may not have happened: 5xx `CommitStateUnknownException`. The client must not retry blindly. |
| `failed` | Any other server failure (5xx), including the catalog store being unavailable (`ServiceUnavailableException`, nothing applied). |

### `nuna.catalog.credential.vends`

This meter times each vend of storage credentials (D21). A vend comes from a table load, a create, a commit or a
registration response, or from the `loadCredentials` route.

| `outcome` | Meaning |
|---|---|
| `issued` | New credentials were minted for this vend, audited and handed out. |
| `cached` | Credentials were handed out from the credential cache: the same caller had been given them for this table and access, and they were not yet near expiry. No credentials were minted, so no credential issue was audited. |
| `none` | No credentials, because the caller declined them (`X-Iceberg-Access-Delegation: none`), as any caller may (D21, amended 2026-11-19): the web console always does, and an engine using its own storage identity may. No STS call was made and no credential issue was audited. |
| `failed` | None could be issued. `error.type` is the error, such as `ServiceUnavailableException` when STS or the audit is down, or `ForbiddenException` when the location is outside the storage profile. |

An `issued` vend made one STS AssumeRole, the `sts assume-role` span and `nuna.sts` timer beneath its request
(spans.md); a `cached` one made none. Two vends for the same caller, table and access at once mint once: the one that
waits for the other counts as `cached`. The share of vends served from the cache is the `cached` count over the
`issued` and `cached` counts together.

A failed vend in a create, commit or registration response does not fail the response. The change is already
committed, so the response carries no credentials and the client asks the credentials route.

### `nuna.catalog.metadata.cache.lookups`

This meter counts lookups in the in-memory cache of metadata files (D14). The lookups come from loads, and from
commits that read their base. A `hit` is a file found in memory. A `miss` is one read from the object store and then
cached. The count is per server and covers every catalog, so it has no `catalog` label.

### The alerts

Each alert kind is counted once each time it fires, alongside its line on the `nuna.alerts` logger. The line names
the table and file; the counter does not.

- `nuna.catalog.alerts.metadata.integrity` should page.
- `nuna.catalog.alerts.regulated.schema.change` and `nuna.catalog.alerts.large.metadata` should warn.
- Large metadata is alerted once per file a server loads, not on every load.

The rules that do so are in `deploy/grafana/rules/nuna-catalog.rules.yaml` (dashboards-and-alerts.md).

### `nuna.catalog.report.*`: client reports

A client's scan or commit report (`reportMetrics`, D13) is aggregated by its results. Each result the spec names
becomes a meter named `nuna.catalog.report.` followed by the result's name with its hyphens made dots. For example,
`total-duration` becomes `nuna.catalog.report.total.duration`.

- A timer result becomes a timer. The report's `total-duration` is converted by the `time-unit` the result states,
  never an assumed one, and recorded as one timing. A timer result with a count of 0 records nothing.
- A counter result becomes a distribution summary, with one sample of its value per report. Its stated `unit` must be
  the spec's for that result (`bytes` or `count`), otherwise it is skipped. The `bytes` ones have base unit bytes.
- A result the spec does not name is skipped, not invented. So is every result of a report type the server does not
  know.

The per-table detail stays in the report's log line (item 37).

Scan report results (`report.type="scan-report"`):

| Meter | Type |
|---|---|
| `nuna.catalog.report.total.planning.duration` | timer |
| `nuna.catalog.report.result.data.files` | count |
| `nuna.catalog.report.result.delete.files` | count |
| `nuna.catalog.report.total.data.manifests` | count |
| `nuna.catalog.report.total.delete.manifests` | count |
| `nuna.catalog.report.scanned.data.manifests` | count |
| `nuna.catalog.report.skipped.data.manifests` | count |
| `nuna.catalog.report.scanned.delete.manifests` | count |
| `nuna.catalog.report.skipped.delete.manifests` | count |
| `nuna.catalog.report.total.file.size.in.bytes` | bytes |
| `nuna.catalog.report.total.delete.file.size.in.bytes` | bytes |
| `nuna.catalog.report.skipped.data.files` | count |
| `nuna.catalog.report.skipped.delete.files` | count |
| `nuna.catalog.report.indexed.delete.files` | count |
| `nuna.catalog.report.equality.delete.files` | count |
| `nuna.catalog.report.positional.delete.files` | count |
| `nuna.catalog.report.dvs` | count |

Commit report results (`report.type="commit-report"`):

| Meter | Type |
|---|---|
| `nuna.catalog.report.total.duration` | timer |
| `nuna.catalog.report.attempts` | count |
| `nuna.catalog.report.added.data.files` | count |
| `nuna.catalog.report.removed.data.files` | count |
| `nuna.catalog.report.total.data.files` | count |
| `nuna.catalog.report.added.delete.files` | count |
| `nuna.catalog.report.added.equality.delete.files` | count |
| `nuna.catalog.report.added.positional.delete.files` | count |
| `nuna.catalog.report.added.dvs` | count |
| `nuna.catalog.report.removed.delete.files` | count |
| `nuna.catalog.report.removed.equality.delete.files` | count |
| `nuna.catalog.report.removed.positional.delete.files` | count |
| `nuna.catalog.report.removed.dvs` | count |
| `nuna.catalog.report.total.delete.files` | count |
| `nuna.catalog.report.added.records` | count |
| `nuna.catalog.report.removed.records` | count |
| `nuna.catalog.report.total.records` | count |
| `nuna.catalog.report.added.files.size.bytes` | bytes |
| `nuna.catalog.report.removed.files.size.bytes` | bytes |
| `nuna.catalog.report.total.files.size.bytes` | bytes |
| `nuna.catalog.report.added.positional.deletes` | count |
| `nuna.catalog.report.removed.positional.deletes` | count |
| `nuna.catalog.report.total.positional.deletes` | count |
| `nuna.catalog.report.added.equality.deletes` | count |
| `nuna.catalog.report.removed.equality.deletes` | count |
| `nuna.catalog.report.total.equality.deletes` | count |
| `nuna.catalog.report.manifests.created` | count |
| `nuna.catalog.report.manifests.replaced` | count |
| `nuna.catalog.report.manifests.kept` | count |
| `nuna.catalog.report.manifest.entries.processed` | count |

The lists are iceberg-core's own (`RestRequest.reportResults`); `CatalogMetricsTest` fails if this page misses one or
names one that does not exist.
