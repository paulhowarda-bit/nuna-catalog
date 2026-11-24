# catalog-audit-tables: the audit table writer (D23)

A separate service that writes every audit record of the catalog to one Iceberg table, `events`, in an audit catalog of
the deployment's own, about a minute behind, so access reviewers can ask across objects, people and
months in SQL from any engine: every table a principal read last quarter, every denial in a namespace. It is an
Iceberg Java client of the catalog over REST, like the maintenance worker (D2 as amended), and signs in as a
principal of its own. The decisions are D23's amendment of 2026-11-20 and `backlog/27.md`. It takes the records from
one of two sources, `nuna.audit-tables.source`: the audit topic (`kafka`, the default), or the catalog's log
`nuna.audit`, which the catalog's OTLP log exporter sends to the writer's logs receiver (`otlp`; D23, amended
2026-11-23, backlog 195), so the console's Audit page needs no Kafka.

## The table

`<catalog>.<namespace>.events`, by default `nuna_audit.audit.events`: format v2, partitioned by `day(time)`, one row
per record of the topic. The writer creates the namespace and the table, and refuses to write to a table of that
name with other columns.

| Column | Type | Meaning |
|---|---|---|
| `event_id` | string | the record's CloudEvents id |
| `time` | timestamptz | when the catalog recorded it, to the microsecond |
| `type`, `source` | string | e.g. `nuna.catalog.read`, and the deployment that recorded it |
| `principal`, `end_user`, `engine`, `request_id` | string | who asked, for whom, through which engine, and the request; an authentication failure has only a request id |
| `catalog`, `object_kind`, `object_id`, `object_path` | string, string, string, list of string | what it is about: a table, view, namespace or catalog, by name as it was then and by stable id |
| `operation`, `privilege`, `reason` | string | where the type has them: a denial's operation, missing privilege and reason, a grant's privilege, a hold's reason |
| `data` | string | the whole payload as the topic carries it, JSON, so a new payload version needs no new column |
| `topic_partition`, `topic_offset` | int, long | the record's place in the topic, which ties a row to its archive chain; for a row from the log, `-1` and the writer's number for it ("From the log", below) |

The mapping from each event type lives in `catalog-audit` (`AuditRows`), without Iceberg. The relay is at least once
(D23), so an `event_id` can repeat, at another offset; an offset is never written twice.

Not in it: from the topic, history older than its retention and records Kafka did not take in an outage (they are in
the catalog's log only); from the log, what was lost before it reached the writer (docs/observability/audit-log.md);
and the records of the writer's own requests (below). Rows are never deleted by the writer: the archive is
the five-year record (D24).

## How it writes

### From the topic

The writer reads the topic in its own consumer group. Rows wait in memory and are committed every
`commit-interval` (1 minute by default) after the first of them arrived, or once `max-rows` wait. Each commit adds
the rows' Parquet files and records each partition's next offset in the same Iceberg commit, in the snapshot's summary
(`nuna.audit-tables.offsets.<topic>`). On start, after a failed commit, a rebalance or a restart, it reads those
offsets back and resumes from them: a commit that never lands is read again, and one that landed is not written
again. The consumer group's own offsets are committed after each commit only so Kafka's tools show the lag.

- **Its own records.** Its every commit makes records (its loads, the credentials vended to it, its commits), which
  would otherwise make the next commit. It leaves out every record whose principal is its own (`principal`); they
  stay in the topic, the SIEM and the archive.
- **One replica.** A second writer's commit fails Iceberg's validation that no rows were added since the snapshot it
  started from, and it reads the offsets again; the chart runs one, with the `Recreate` strategy.
- **A record it cannot map** stops it there, every attempt, with the record's place in the error: nothing after it is
  written and nothing is skipped. A catalog newer than the writer can do this; upgrade the writer first.
- **Outages** of the catalog, the store or Kafka delay rows; no request waits for the writer. It logs the failure and
  retries every 10 seconds.
- **Snapshot expiry.** The offsets live in the writer's snapshots. Compaction and manifest rewrites add snapshots
  without them, which the writer looks past. If expiry removed every snapshot that records offsets (the writer down
  longer than the audit catalog's `max-snapshot-age`, and only newer snapshots kept), the writer refuses to start
  rather than guess. Keep the audit catalog's snapshots longer than the writer may be down.

### From the log

With `source: otlp` the writer listens for OTLP/HTTP log exports, `POST /v1/logs` on `otlp.port` (4318), the body an
`ExportLogsServiceRequest` as protobuf (`application/x-protobuf`) or OTLP's JSON (`application/json`), gzipped or not
(`OtlpLogsReceiver`). The catalog's `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` names it, directly or through a collector.

- **What it keeps.** Only the log records whose instrumentation scope is `nuna.audit`, the catalog's audit logger;
  OpenTelemetry's logback appender names the scope after the logger. Each one's body is a CloudEvent. Every other
  record is ignored, so the catalog's whole log may be sent.
- **The token.** Every request must carry `otlp.token` in the header `X-Nuna-Audit-Token`, or it is refused with 401
  and nothing in it is read: the table is the console's Audit page, and nothing but the catalog may add rows to it.
  The token is at least 32 letters, digits, `-`, `_`, `.` or `~`, so it travels in `OTEL_EXPORTER_OTLP_LOGS_HEADERS`
  unencoded; the writer refuses to start without one.
- **Answers**, as OTLP/HTTP has them: 200 once the records are taken, with a partial success counting those rejected
  (a body that is no CloudEvent, or of a type the writer cannot map: logged as an error, not written, still in the
  catalog's log); 400 for a body that is no OTLP request, 413 past 16 MiB, 415 for another content type, and 503 with
  `Retry-After` while twice `max-rows` wait, which the exporter retries.
- **Each record once.** The log has no offsets, and a record can arrive twice: the exporter retries a batch whose
  answer it lost, and the catalog's relay is at least once. Rows are deduplicated by CloudEvent id: an id seen in the
  last `otlp.dedup-window` (15 minutes) is left out. On start and after a failed commit the writer reads the ids of the
  table's rows of that window back, so a commit whose answer was lost, or a restart, writes nothing twice.
- **Partition and offset.** A row from the log is in no topic: its `topic_partition` is `-1`, and its `topic_offset` a
  number the writer gives it, the current time in microseconds or one more than the last it gave, whichever is higher.
  The numbers rise, never repeat while the writer's clock does not go back, and keep rows apart where the feed's reader
  orders and finds them by partition and offset. Each commit records the next one in its snapshot summary, under
  `nuna.audit-tables.offsets.otlp:nuna.audit`, which a restart starts above. Such a row has no archive chain: its
  `chain.status` in the feed is `unverified` where an archive is configured.
- **Waiting rows.** Records wait in memory and are committed as from the topic, every `commit-interval` or at
  `max-rows`. A commit that fails keeps them for the next. A writer that dies before its commit loses them (they are
  still on the catalog's stdout); a shutdown stops the receiver and commits them first.
- A table the topic filled before can be written from the log: the two sources' offsets are kept apart.

## Running it

```
./gradlew :catalog-audit-tables:bootJar
java -jar catalog-audit-tables/build/libs/catalog-audit-tables-<version>-exec.jar \
    --spring.config.additional-location=file:audit-tables.yaml
```

With the source `kafka` it has no HTTP port; with `otlp`, only the logs receiver's. It logs a line per commit, as JSON
on stdout.

### Settings (`nuna.audit-tables.*`)

| Setting | Default | Meaning |
|---|---|---|
| `catalog-uri` | required | the catalog's base URI, e.g. `http://nuna-catalog:8181/` |
| `catalog` | `nuna_audit` | the audit catalog, declared in the catalog's `nuna.storage.catalogs` |
| `namespace` | `audit` | the namespace of the table `events` in it |
| `principal` | required | the writer's own principal id as the catalog records it (its token's user-id claim) |
| `commit-interval` | `PT1M` | how often waiting rows are committed |
| `max-rows` | `100000` | rows that make a commit before the interval has passed |
| `token.endpoint`, `token.client-id`, `token.client-secret` | required | the client-credentials grant against the identity provider; Iceberg's client renews it |
| `token.scope`, `token.audience` | none | a scope or audience to ask for |
| `source` | `kafka` | where the records come from: `kafka`, the audit topic, or `otlp`, the catalog's log over OTLP/HTTP |
| `kafka.bootstrap-servers` | required for `kafka` | the cluster holding the audit topic |
| `kafka.topic` | `nuna.catalog.audit` | the catalog's `nuna.audit.audit-topic` |
| `kafka.group` | `nuna-audit-tables` | the writer's consumer group |
| `kafka.properties.*` | none | further Kafka client settings (TLS, SASL); the writer owns `bootstrap.servers`, `group.id`, `enable.auto.commit`, `auto.offset.reset`, `isolation.level` and the deserializers |
| `otlp.port` | `4318` | the logs receiver's port, for `otlp` |
| `otlp.token` | required for `otlp` | the token every request carries in `X-Nuna-Audit-Token`; at least 32 letters, digits, `-`, `_`, `.` or `~` |
| `otlp.dedup-window` | `15m` | how long a record's id is remembered, to leave out a second copy |
| `client-properties.*` | none | further Iceberg client properties, e.g. `s3.endpoint` |

## Deploying it

1. Declare the audit catalog on the catalog server, in `nuna.storage.catalogs.nuna_audit` (profile, KMS key set,
   legal entity), as any catalog.
2. Give the writer a principal of its own at the identity provider, in a group of its own that holds nothing anywhere
   else.
3. Name the audit catalog and its two groups on the catalog server: `nuna.audit.tables.catalog` (e.g. `nuna_audit`),
   `nuna.audit.tables.readers` (the readers' group) and `nuna.audit.tables.writers` (the writer's group); in the
   catalog's chart, under `config.nuna.audit.tables`. At startup the server grants the readers `select` on the audit
   catalog and the writer's group `create` and `write` there, as the grants API grants (separation of duties checked,
   D18), each recorded in the audit trail as granted by `nuna-configuration`; a grant that already exists is left as
   it is. It refuses to start if the catalog is not declared or a grant would break the separation of duties. Rows
   are not filtered: a reader sees every object and person in the deployment's records, as a SIEM user does, so the
   readers' group must be narrow. Everyone else finds no such catalog (D18). Grant admins may grant other groups there
   through the grants API, `POST /management/v1/catalogs/nuna_audit/grants`; the server neither refuses nor revokes
   them.
4. Give the maintenance worker's group `write` on the audit catalog too, so its expiry and compaction keep the table
   tidy (D30), within the expiry caveat above.
5. Build the image, `deploy/image/audit-tables.Dockerfile`, and install the chart `deploy/helm/nuna-audit-tables`:
   one replica, its settings under `config`, the client secret (and any Kafka secret, or the OTLP token) under
   `secrets`, each key a property name such as `nuna.audit-tables.token.client-secret`. The chart refuses to render
   without `catalog-uri`, `principal`, `token.endpoint`, `token.client-id`, and `kafka.bootstrap-servers` for the
   source `kafka`. For the source `otlp` it adds the container port and a Service port, both named `otlp-http`, and
   the catalog's chart sends its logs there with `telemetry.logsEndpoint` and `telemetry.logsTokenSecret`
   (deploy/README.md, "Audit").

## The console's audit feed (D45, item 125)

The catalog server reads the table back for the console's Audit page, through this module's own reader,
`EventsReader` (D23, amended 2026-11-22: Paul, "Yes, link them"; no server code imports Iceberg). It finds the table
as `<nuna.audit.tables.catalog>.<nuna.audit.tables.namespace>.events` (the namespace defaults to `audit` and must match
the writer's `namespace`), through its own pointer to the table's metadata, and reads the files with the audit
catalog's storage profile. A file named outside the table's location is refused. The feed holds what the writer has
committed, so it is about a minute behind. Each response's `table.committed-at` says how far it reaches.

Every route needs `select` on the audit catalog, as the tables themselves do (Paul, 2026-11-22: "Audit readers
only"). That rules out grant admins, platform operators and holders of `select` on the catalog the feed is about.
- A refusal is a 403, audited as a `Denial` on the audit catalog.
- Where `nuna.audit.tables.catalog` is unset, every route answers 503.
- A feed page, a chain lookup and a day's counts not kept from the last minute each take one of the caller's
  `nuna.governance.audit-reads-per-minute`, shared with `GET .../audit`; beyond that the answer is 429 with
  `Retry-After`.
- The feed and the counts are audited as an `AuditRead` of the catalog, and a chain lookup as one of the audit
  catalog.

`GET /management/v1/catalogs/{catalog}/audit/feed`

- Parameters, all optional:
  - `type`: record types separated by commas, such as `nuna.catalog.read,nuna.catalog.denial`;
  - `principal`;
  - `path`: namespace levels, and a table or view name, joined by 0x1F; it matches the object and everything in a
    namespace, compared as the catalog compares names;
  - `from` (inclusive) and `to` (exclusive), as instants;
  - `limit`: 1 to 200, 50 by default;
  - `pageToken`.
- Answer: `{"catalog", "records": [...], "next-page-token"?, "searched-to"?, "table": {"name", "snapshot-id",
  "committed-at"}}`.
- Each record: `{"event-id", "time", "type", "principal", "end-user", "engine", "request-id", "object": {"kind",
  "id", "catalog", "path"} | null, "operation", "privilege", "reason", "partition", "offset", "chain": {"status",
  "checked-at"}, "event"}`.
  - `event` is the record as the topic and the log carry it.
  - `chain.status` is `verified` when the archive check has verified the record's chain past its offset,
    `unverified` when it has not (yet, or the chain is broken there: see the archive status), and `no-archive` where
    no archive is configured.
- Records come newest first by time, then partition, then offset. A page token resumes after the page's last record,
  so a growing table neither repeats nor skips one.
- A page reads about 500,000 rows at most. When a narrow filter finds fewer than `limit` in that, the page comes back
  short with `searched-to`: every record newer than that time was searched. Its `next-page-token` searches further
  back. Without a `next-page-token` there is nothing older.
- 404 for an unknown catalog, 400 for a bad parameter, 503 while the table does not exist or cannot be read.

`GET /management/v1/catalogs/{catalog}/audit/summary`

- The last day's counts: `{"catalog", "from", "to", "by-type": {type: n}, "busiest": [{"kind", "id", "path",
  "records", "reads", "writer"}], "deployment-by-type": {type: n}, "table"}`.
  - `busiest` lists the ten objects with the most records. `writer` is the principal with the most commits to the
    object.
  - `deployment-by-type` counts every catalog's records and those about none.
- The counts are read once a minute at most and shared by every caller.
- They count the records in the table. These differ from the server's own counters in two ways. The writer leaves
  its own records out. A record that never reached the writer is not in the table: from the topic, one Kafka did not
  take; from the log, one lost on the way (docs/observability/audit-log.md).

`GET /management/v1/audit/archive`

- The archive check's status: `{"configured", "bucket", "prefix", "retention", "verified": [{"chain", "partition",
  "sequence", "hash", "next-offset", "segment", "at"}], "check": {"started-at", "ended-at", "intact", "chains":
  [{"chain", "partition", "records-verified", "verified", "broken", "failed"}]} | null}`.
- `verified` says how far each chain is verified, shared by every server. `check` is this server's last run, and is
  null before its first.
- Every server checks each chain a minute after it starts and hourly after that. A run reads only the segments
  written since the last head verified, and reads them up to the archive consumer's anchor. It keeps what it verified
  as the committed offsets of the consumer group `<nuna.audit.archive.consumer-group>-check`.
- A broken chain keeps its last verified head, so the break is found again on every run, and is logged as an error.

`GET /management/v1/audit/chains/{partition}/records/{offset}?event-id=`

- A feed record's place in its hash chain, from the archive: `{"chain", "partition", "offset", "event-id",
  "archived", "sequence", "hash", "previous-hash", "next-hash", "link-holds", "verified", "checked-at", "segment":
  {"key", "first-sequence", "last-sequence"}}`.
- `link-holds` says whether the stored hash is the hash of the previous one and the record. `verified` says whether
  the archive check has verified the chain past it.
- It reads the one segment that holds the record, plus the next segment's first hash when the record is its segment's
  last.
- `archived` is false until the archive consumer has written the record's segment, and for records older than the
  archive.
- 503 where no archive is configured.

## Tests

- `AuditRowsTest` (`catalog-audit`) maps every line of the golden `events-v1.jsonl` and fails for an event type with
  no mapping.
- `TableWriterTest` runs the writer against Kafka's fake consumer and Iceberg's in-memory catalog, which can lose a
  commit or its answer: every record written once with its place in the topic, resuming after the last committed
  offset, no gap and no repeat after a lost commit, a second writer, a revoked partition or a record it cannot map,
  its own records left out. `AuditTablesSettingsTest` checks the settings and the clients made of them.
- `LogWriterTest` runs the source `otlp`'s writer against the same catalog: each record once across repeats, a commit
  whose answer was lost and a restart; rows in partition -1 with rising numbers; a failed commit keeps its rows; its
  own records left out; a table the topic filled before. `OtlpLogsReceiverTest` posts OTLP requests: protobuf and
  JSON, gzip, only the `nuna.audit` scope kept, a request without the token refused and unread, rejected records
  counted. `catalog-server`'s `AuditLogToWriterTest` sends a record from the server's own log wiring to the receiver.
- `AuditTablesTest` (tagged `heavy`: PostgreSQL, Kafka and the run's S3 store) runs it against a real catalog server:
  a row for each kind of request about two intervals behind, no gap and no repeat after a writer dies mid-batch, and
  the table found only by the auditors. The server grants the auditors and the writer's group from
  `nuna.audit.tables.*`, as a deployment's does.
- `AuditCatalogGrantsTest` (`catalog-server`) checks the startup grants against a store double: both groups' grants
  made and recorded, a grant already there left as it is, none when unset, none twice after a restart, and the
  server stopped by an undeclared catalog or a grant the separation of duties forbids.
- The engine matrix (`EngineMatrixTest#auditTables`, overnight, `catalog-tests-engines`) runs the writer from its
  image and chart beside the matrix's catalog and reads `nuna_audit.audit.events` from Spark and PyIceberg as the
  readers' group.
- The feed:
  - `EventsReaderTest` pages a real table on local files: newest first, every row once whatever the page size, a
    short page with `searched-to` when the budget runs out, the filters, the counts and no file read outside the
    table's location.
  - `ArchiveCheckTest` (`catalog-audit`) checks chains on an in-memory bucket: each verified to its anchor and no
    further, a later run reading only the new segment, a changed record or a lost tail found, and a record placed
    with its neighbours.
  - `AuditFeedRoutesTest` (`catalog-server`) runs the routes with the real guard over a store double: a test for
    each refusal, the answers, the 400s and 503s, and the shared read limit.
- Bites: `bites/D23/`.
