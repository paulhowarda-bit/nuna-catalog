# Spans and timers for the database, the object store, STS and Kafka

Backlog 41. A request's server span (README.md, "Traces") has a child span for each call the catalog makes to its
database, its object store, STS and Kafka, so a slow load or commit splits into the four. Each call is one Micrometer
Observation: one span and one timer of the same name, with the same tags.

## The spans

| Observation (timer) | Span | One per | Tags |
|---|---|---|---|
| `nuna.store` | `store <method>`, e.g. `store commit`, `store readPointer` | call to the catalog store: each is one transaction, on whichever backend is configured | `backend`, `operation`, `outcome`, `exception` |
| `nuna.objectstore` | `objectstore <method>`: `write`, `read`, `metadataFiles`, `tableLocations` | request for metadata files to the object store | `profile`, `operation`, `outcome`, `exception` |
| `nuna.sts` | `sts assume-role` | AssumeRole for a credential vend; a vend served from the credential cache calls nothing | `profile`, `access`, `outcome`, `exception` |
| `nuna.audit.publish` | `audit publish` | synchronous audit record (D23), such as a read or a credential issue, from the send to Kafka's acknowledgement | `topic`, `event`, `outcome`, `exception` |

The tags, and nothing else, are the spans' attributes:

| Tag | Values |
|---|---|
| `backend` | `postgresql`, `oracle` or `mongodb` (`nuna.database.backend`) |
| `operation` | the store's method (`commit`, `readPointer`, `findObject`, `grantsFor`, ...), or the object store's (`write`, `read`, `metadataFiles`, `tableLocations`) |
| `profile` | the storage profile's configured name |
| `access` | `read` or `read-write` |
| `topic` | the audit topic (`nuna.audit.audit-topic`) |
| `event` | the event's type, e.g. `nuna.catalog.read`, `nuna.catalog.credential-issue` |
| `outcome` | what the call came to (below), or `error` |
| `exception` | the simple name of the exception the call threw, e.g. `CommitOutcomeUnknownException`, or `none` |

Outcomes:

- **Store.** A result that is one of a fixed set names its kind: a commit is `committed`, `conflict` or `inactive`;
  another write `applied`, `unchanged`, `missing`, `name-taken`, `version-mismatch` or `refused`; an idempotency key
  `absent`, `claimed`, `in-flight`, `done` or `mismatch`; a commit lookup `committed` or `not-committed`. A read that
  may find nothing is `found` or `absent`. Anything else is `ok`.
- **Object store.** `written`; `intact`, or `mismatch` when the bytes read do not hash as the pointer says (D26);
  `listed`.
- **STS.** `issued`.
- **Kafka.** `acknowledged`.

A failed call's span has status ERROR, and its exception event names the exception's class and nothing else: no
message, cause or stack trace. Those messages can name a table's location, the principal an STS session is for, or a
value a database refused. The timer's own `error` tag (Micrometer's) reads `CallFailed` for every failure; the
`exception` tag says which.

## What a commit and a load look like

From `SpanTreeTest`, on PostgreSQL, Silo's S3 and STS, and one Kafka broker:

```
http post /v1/{prefix}/namespaces/{namespace}/tables/{table}      (a commit)
  store findTrustedEngine    absent
  store findCatalog, findNamespace, findObject, ...   found
  store grantsFor, attributesOf, ...                  ok
  store readPointer          found
  objectstore write          written        the new metadata file
  store commit               committed      the pointer moves, after the file is written
  store readObject, readNamespace, attributesOf, grantsFor, ...
  sts assume-role            issued         the caller had no cached credentials for the table
  audit publish              acknowledged   nuna.catalog.credential-issue

http get /v1/{prefix}/namespaces/{namespace}/tables/{table}       (a load)
  store findTrustedEngine, findCatalog, findNamespace, findObject, grantsFor, attributesOf, ...
  store readPointer          found
  audit publish              acknowledged   nuna.catalog.read
```

The load read no metadata file: the metadata cache held it. Its credentials came from the credential cache, so it
called no STS and published no credential issue.

## What is not observed

- **Calls made outside a request.** A call is observed only inside another observation, today a request's. The outbox
  relay claims the outbox every 250 ms; observed, each claim would start a trace of its own and fill the trace store.
  Its calls, the server's startup (migration, storage profiles, declared catalogs) and the relay's publishes to Kafka
  make neither spans nor timers.
- **Methods that reach nothing.** `TableFiles.newLocation` and `isWithin` compute an answer and call no store.
- **The archive** (Object Lock writes, `nuna.audit.archive.*`) runs on its own thread, outside any request.

## What tags never hold

No tag or attribute of these spans names a person, a namespace, a table, a location, a key or a token, or holds a
filter value: each value comes from the small fixed sets above. The server span still records the request's path
(README.md, "What labels and attributes never hold").

## In Prometheus

Over OTLP the timers arrive as `nuna.store`, `nuna.objectstore`, `nuna.sts` and `nuna.audit.publish`. By
Micrometer's Prometheus naming they become `nuna_store_seconds` (`_count`, `_sum`, `_max`), `nuna_objectstore_seconds`,
`nuna_sts_seconds` and `nuna_audit_publish_seconds`, each labelled with the tags above plus Micrometer's `error`.
That naming is for a Prometheus that scrapes Micrometer. Pushed over OTLP, as nuna does today, they arrive in
milliseconds, `nuna_store_milliseconds_count` and `_sum` and so on, as the dashboards assume (dashboards-and-alerts.md).

`nuna.audit.publish` also has histogram buckets, `nuna_audit_publish_milliseconds_bucket`, at the console health
page's bounds: 5, 10, 25, 50, 100, 250 and 500 ms, 1, 2.5 and 5 s (`ObservedPublisher.BUCKETS`, item 71). The catalog
dashboard's p99 of the Kafka acknowledgement is read from them. The other three timers have no buckets.

## Code, tests and bites

The decorators are in `catalog-server`, `nuna.catalog.server.tracing`, and are applied where the collaborators are
wired: `ObservedStore` wraps the store in `StoreWiring`, `ObservedTableFiles` each profile's files in
`CatalogStorage`, and `ObservedPublisher` the audit topic's publisher in `GovernanceWiring`; `StsCredentialVending`
observes AssumeRole. `Observed` makes the observation. `TracingWiring` gives the audit publish timer its buckets, a
meter filter Boot adds to every registry; a registry that already gives the timer buckets of its own, as the health
page's does, keeps them. The stores, `catalog-audit` and `catalog-objectstore` are unchanged.

| Test | Shows |
|---|---|
| `SpanTreeTest` | Through an in-memory span exporter on a running server: a commit's tree (server span, then the object store's write before the store's pointer move, STS, and the credential issue's publish to Kafka) and a load's (server span, the store's reads and the read audit's publish); no attribute names a person, table, namespace, location or credential, and each span carries only its own tags. |
| `ObservedTest` | On Micrometer's OpenTelemetry bridge: a call inside an observation is one child span and one timer with its outcome; outside any, neither; a failure's span is an error naming the exception's type, never its message; a store call's span and outcome. |
| `AuditPublishBucketsTest` | At a small OTLP receiver, through a composite as in the server: the audit publish timer arrives with the health page's buckets and its records in the right ones, another timer with none; a registry with buckets of its own for the timer keeps them. |

Each guarded rule's bite is in `bites/OBS-41/`, and the audit publish buckets' in `bites/OBS-71/`.
