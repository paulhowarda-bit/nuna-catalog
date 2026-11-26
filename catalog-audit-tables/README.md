# catalog-audit-tables: the audit table writer (D23)

A separate service that writes every audit record of the catalog to one Iceberg table, `events`, in an audit catalog of
the deployment's own, about a minute behind, so access reviewers can ask across objects, people and
months in SQL from any engine: every table a principal read last quarter, every denial in a namespace. It is an
Iceberg Java client of the catalog over REST, like the maintenance worker (D2 as amended), and signs in as a
principal of its own. The decisions are D23's amendment of 2026-11-20 and `backlog/27.md`. It takes the records from
one of two sources, `nuna.audit-tables.source`: the audit topic (`kafka`, the default), or the catalog's log
`nuna.audit`, which the catalog's OTLP log exporter sends to the writer's logs receiver (`otlp`; D23, amended
2026-11-23, backlog 195), so the console's Audit page needs no Kafka. With the source `otlp` it also writes the
hash-chained Object Lock archive, the seven-year record (D23, amended 2026-11-25, backlog 262; "The archive", below).

## The table

`<catalog>.<namespace>.events`, by default `nuna_audit.audit.events`: format v2, partitioned by `day(time)`, one row
per record of the topic. The writer creates the namespace and the table, and refuses to write to a table of that
name with other columns. A table made before `log_run` and `log_seq`, or before `branch` and `subject_ids`, is given
them when the writer opens it, a schema change that rewrites no file; its earlier rows read null there.

| Column | Type | Meaning |
|---|---|---|
| `event_id` | string | the record's CloudEvents id |
| `time` | timestamptz | when the catalog recorded it, to the microsecond |
| `type`, `source` | string | e.g. `nuna.catalog.read`, and the deployment that recorded it |
| `principal`, `end_user`, `engine`, `request_id` | string | who asked, for whom, through which engine, and the request; an authentication failure has only a request id |
| `catalog`, `object_kind`, `object_id`, `object_path` | string, string, string, list of string | what it is about: a table, view, namespace or catalog, by name as it was then and by stable id |
| `operation`, `privilege`, `reason` | string | where the type has them: a denial's operation, missing privilege and reason, a grant's privilege, a hold's reason |
| `data` | string | the whole payload as the topic carries it, JSON, so a new payload version needs no new column |
| `topic_partition`, `topic_offset` | int, long | the record's place in the topic; for a row from the log, `-1` and the writer's number for it ("From the log", below) |
| `log_run`, `log_seq` | string, long | the server run that logged the record and its number there (its `nunarun` and `nunaseq`), which place it in the run's archive chain, for a row from the log or the topic alike, since the topic's copy is the log's line (backlog 276); null for a record from a server older than the stamp, or a topic copy from one older than backlog 276 (backlog 272) |
| `branch` | string | the catalog ref a commit or a read went through, `main` or the ref's name as given, and a ref record's ref name; null for every other type (backlog 169) |
| `subject_ids` | list of string | a ref record's ref id, then the ids of the tables and views in its `tables`: those a merge moved onto main, or the one taken out of the ref (backlog 156); null for every other type (backlog 169) |

The mapping from each event type lives in `catalog-audit` (`AuditRows`), without Iceberg. The relay is at least once
(D23), so an `event_id` can repeat, at another offset; an offset is never written twice.

Not in it: from the topic, history older than its retention and records Kafka did not take in an outage (they are in
the catalog's log only); from the log, what was lost before it reached the writer (docs/observability/audit-log.md);
and the records of the writer's own requests (below). Rows are never deleted by the writer: the archive is
the seven-year record (D24).

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
  `Retry-After` while twice `max-rows` wait, or while the archive cannot take the request's records, which the
  exporter retries.
- **Each record once.** The log has no offsets, and a record can arrive twice: the exporter retries a batch whose
  answer it lost, and the catalog's relay is at least once. Rows are deduplicated by CloudEvent id: an id seen in the
  last `otlp.dedup-window` (15 minutes) is left out. On start and after a failed commit the writer reads the ids of the
  table's rows of that window back, so a commit whose answer was lost, or a restart, writes nothing twice.
- **Partition and offset.** A row from the log is in no topic: its `topic_partition` is `-1`, and its `topic_offset` a
  number the writer gives it, the current time in microseconds or one more than the last it gave, whichever is higher.
  The numbers rise, never repeat while the writer's clock does not go back, and keep rows apart where the feed's reader
  orders and finds them by partition and offset. Each commit records the next one in its snapshot summary, under
  `nuna.audit-tables.offsets.otlp:nuna.audit`, which a restart starts above. The record's stamp, the server run that
  logged it and its number there, goes in `log_run` and `log_seq` (backlog 272); of a record that arrives twice, the
  first copy's.
- **Waiting rows.** Records wait in memory and are committed as from the topic, every `commit-interval` or at
  `max-rows`. A commit that fails keeps them for the next. A writer that dies before its commit loses them (they are
  still on the catalog's stdout); a shutdown stops the receiver and commits them first.
- A table the topic filled before can be written from the log: the two sources' offsets are kept apart.

### The archive

With `archive.bucket` set (source `otlp` only; the writer refuses it with `kafka`), every audit record the receiver
takes is archived first, byte for byte and before the table's filters, so the archive holds the writer's own records
and any it cannot map (`ArchiveSink`). If the archive cannot take a request's records, the receiver answers 503 with
`Retry-After` and the table takes none of them, so the table never holds a record the archive refused.
docs/observability/audit-log.md, "The archive", says what the catalog stamps on each line, how the check finds a
record that never arrived, and what the archive can miss.

- **Chains.** One hash chain per catalog server run, `<prefix>/runs/<run>/<n>.log`, named by the run id each line
  carries (`nunarun`), `<n>` being the segment's first record's place in the chain, 19 digits; records with no stamp go to `<prefix>/runs/unstamped-<writer>/`, a chain of
  this writer process's own. A record whose run and number this writer has already taken is left out; one taken
  before a restart is archived again, and the check counts it as a repeat.
- **Segments.** A chain's records wait in memory until `segment-events` of them wait, the first has waited
  `segment-age`, or the run's run-end record arrives. Each segment is written with `If-None-Match` and locked in
  COMPLIANCE mode until `retention` from now; a writer that restarts reads each chain's last segment and continues
  from it. A segment that fails is tried again every second; a lost answer is found written on the retry.
- **Checkpoints.** After every round of segments, and every `checkpoint-interval` when there were none, a locked
  checkpoint `<prefix>/checkpoints/<yyyyMMdd>/<HHmmss.SSS>Z.json`: the writer, the time, the interval, each chain's
  head written since the last, and the key and SHA-256 of the checkpoint before (on a restart, of the last one in the
  bucket). Each is keyed after the one before, by at least a millisecond.
- **Waiting records** are lost if the writer dies, at most a minute's worth or `max-waiting`; the archive check shows
  the loss (audit-log.md). A shutdown stops the receiver and writes every waiting segment and a last checkpoint.
- **One replica.** A second writer's segment for a chain the first has written is refused (`If-None-Match`) and
  logged as a second archive writer, and its checkpoints do not name the first's, which the check reports as a broken
  link. The chart runs one.

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
| `archive.bucket` | none | the archive's bucket, created with Object Lock; none, no archive. For `otlp` only |
| `archive.prefix` | `audit` | the key prefix in it; the catalog's `nuna.audit.archive.prefix` must match |
| `archive.region`, `archive.endpoint` | `us-east-1`, none | the store's region, and its URL for an on-prem store (path-style addressing) |
| `archive.access-key-id`, `archive.secret-access-key` | none | both or neither; without them, the SDK's default credentials |
| `archive.ca-bundle` | none | a PEM file of the CAs the store's certificate chains to, beside the JVM's, for a private CA |
| `archive.retention` | `2557d` | how long each segment and checkpoint is locked; seven years at least (D23) |
| `archive.segment-events`, `archive.segment-age` | `10000`, `1m` | when a chain's waiting records are written as a segment |
| `archive.checkpoint-interval` | `5m` | how often a checkpoint is written when no segment was; the check calls the newest late past twice this |
| `archive.max-waiting` | `100000` | records waiting for their segment before the receiver answers 503 |

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
- A feed page and a day's counts not kept from the last minute each take one of the caller's
  `nuna.governance.audit-reads-per-minute`, shared with `GET .../audit`; beyond that the answer is 429 with
  `Retry-After`.
- The feed and the counts are audited as an `AuditRead` of the catalog; a record's place in its chain as an
  `AuditRead` of the audit catalog.

`GET /management/v1/catalogs/{catalog}/audit/feed`

- Parameters, all optional:
  - `type`: record types separated by commas, such as `nuna.catalog.read,nuna.catalog.denial`;
  - `principal`;
  - `path`: namespace levels, and a table or view name, joined by 0x1F; it matches the object and everything in a
    namespace, compared as the catalog compares names;
  - `branch`: a branch's or tag's name without its `@`, or `main`: the commits and reads through it and the ref records
    of refs of that name, compared as the catalog compares names (backlog 169). A name is not an id: records of a
    deleted ref whose name a later ref took match too, so ask with `from` at the live ref's `created-at` for that
    ref's alone;
  - `subject`: an id, as a UUID: the records about that table, view, namespace or catalog (`object.id`), and the
    `nuna.catalog.ref` records whose ref is that id or whose `tables` name it (backlog 169). With
    `type=nuna.catalog.ref` and a table's id it gives every ref record that names the table: a merge that moved it
    onto main, and each time it was taken out of a ref (`event.data.action` `OBJECT_REMOVED`, backlog 156), with
    who (`principal`, and `event.data.actor`), when (`time`), and which ref (`branch`, and `event.data.ref`,
    `.name` and `.kind`). Records written before backlog 169 carry neither column and match neither filter;
  - `from` (inclusive) and `to` (exclusive), as instants;
  - `limit`: 1 to 200, 50 by default;
  - `pageToken`.
- Answer: `{"catalog", "records": [...], "next-page-token"?, "searched-to"?, "table": {"name", "snapshot-id",
  "committed-at"}}`.
- Each record: `{"event-id", "time", "type", "principal", "end-user", "engine", "request-id", "branch", "object":
  {"kind", "id", "catalog", "path"} | null, "operation", "privilege", "reason", "partition", "offset", "log-run",
  "log-seq", "chain": {"status", "checked-at"?, "missing"?}, "event"}`.
  - `event` is the record as the topic and the log carry it: its `data` is the payload, its `subject` a ref
    record's ref id.
  - `branch` is the table's `branch` column (above), null where the record names no ref.
  - `log-run` and `log-seq`: the server run that logged the record and its number there, whether the row came from
    the log or the topic (backlog 276); null for a record from a server older than the stamp (backlog 272), or a
    topic copy from one older than backlog 276.
  - `chain.status` is `no-archive` where no archive is configured; `no-place` for a record without a run, which no
    chain can be searched for; `verified` when the archive check has verified every number of its run up to the
    record's; `unverified` when it has not. `checked-at` is when the check last looked at the run, null when no check
    has reached it.
  - `chain.missing`, `{"from", "to"}`, on an unverified record whose run the last check reported missing records at
    or below its number: the range holding its own number, else the lowest below it. The record stays unverified
    until those arrive, which may be never.
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

`GET /management/v1/audit/chains/{run}/records/{number}?event-id=`

- A logged record's place in its server run's hash chain (backlog 272), for a feed record's `log-run`, `log-seq` and
  `event-id`; the id keeps another record logged under that number from being taken for it.
- Answer: `{"chain", "log-run", "log-seq", "event-id", "archived"}`, and when it is archived, `"position", "hash",
  "previous-hash", "next-hash", "link-holds", "verified", "checked-at", "segment": {"key", "first-position",
  "last-position"}`.
  - `position` is its place in the chain, which can differ from its number: the chain holds records as they arrived,
    a repeat included.
  - `link-holds`: its stored hash is the SHA-256 of the hash before it and its content, as the archive writer made it.
  - `verified`: the check has verified the chain through it. `next-hash` is null for the newest record in its chain.
- It is looked for in the segment its number puts it in and the one on each side, and across the whole chain when the
  check has verified every number of the run up to it. `archived` false: not found there.
- 400 for a run that is not a UUID, a number that is not a whole number from 1, or an event id that is not a UUID;
  503 where no archive is configured or it cannot be read. It takes one of the caller's audit reads.

`GET /management/v1/audit/archive`

- The archive check's status: `{"configured", "bucket", "prefix", "check": {...} | null}`; only `configured` where no
  archive is configured, and `check` null before this server's first run.
- `check`: `{"started-at", "ended-at", "intact", "complete", "checkpoints": {"latest", "at", "interval", "late",
  "problems"}, "runs", "runs-done", "records-verified", "chains": [...]}`.
  - `intact`: no checkpoint late or broken, and no chain with a problem or a failure. `complete`: no run missing a
    record or stopped without ending.
  - `checkpoints`: the newest the check followed, its time, the writer's interval, whether it is late (older than
    twice the interval, or none at all) and what was wrong with the checkpoints (a broken link, a head that went
    back, a stray version).
  - `runs` and `runs-done` count the server runs' chains, and those whose run ended with every record archived and
    verified. `records-verified` adds up the chains' verified heads.
  - `chains` lists only the chains with something to show: not done, or with records missing, a problem or a
    failure. Each: `{"chain", "run", "records-verified", "sequence", "hash", "segment", "highest", "through",
    "repeats", "ended", "done", "advanced-at", "missing": [{"from", "to"}], "unended", "problems", "failed"}`.
    `highest` is the highest number archived, `through` the number below which none is missing, `missing` the
    numbers missing for longer than twice the checkpoint interval, `unended` a run with no run-end record whose
    highest number has stood still for a day.
- Every server checks a minute after it starts and hourly after that, and keeps what it verified in the bucket,
  `<prefix>/check/state.json`, so the servers share it. A run reads only the segments written since the head it last
  verified, up to the newest head a checkpoint gave each chain.
- A chain that is broken keeps its last verified head, so the break is found again on every run; a check that is not
  intact, or not complete, is logged as an error.

## Tests

- `AuditRowsTest` (`catalog-audit`) maps every line of the golden `events-v1.jsonl` and fails for an event type with
  no mapping.
- `TableWriterTest` runs the writer against Kafka's fake consumer and Iceberg's in-memory catalog, which can lose a
  commit or its answer: every record written once with its place in the topic and the run and number it was logged
  with, resuming after the last committed
  offset, no gap and no repeat after a lost commit, a second writer, a revoked partition or a record it cannot map,
  its own records left out. `AuditTablesSettingsTest` checks the settings and the clients made of them.
- `ArchiveSinkTest` (`catalog-audit`) runs the archive writer on an in-memory bucket that can fail or lose an answer:
  segments by count, age and run end, refused bodies, the unstamped chain, a busy writer, an unreachable store, a
  lost answer, a second writer, checkpoints linked across a restart and written when idle, and repeats left out.
  `LockedArchiveTest` (tagged `heavy`) writes and checks on the run's S3 store, with real Object Lock.
- `LogWriterTest` runs the source `otlp`'s writer against the same catalog: each record once across repeats, a commit
  whose answer was lost and a restart; rows in partition -1 with rising numbers; a failed commit keeps its rows; its
  own records left out; a table the topic filled before. `OtlpLogsReceiverTest` posts OTLP requests: protobuf and
  JSON, gzip, only the `nuna.audit` scope kept, a request without the token refused and unread, rejected records
  counted, every audit record archived before the table's filters, and a request the archive cannot take turned away
  whole. `catalog-server`'s `AuditLogToWriterTest` sends a record from the server's own log wiring to the receiver.
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
    short page with `searched-to` when the budget runs out, the filters (the ref and subject filters too, backlog
    169), the counts, no file read outside the table's location, each row's run and number, and a table made before
    the log columns, or before the ref columns, given them without a file rewritten.
  - `PlacementTest` (`catalog-audit`) places records in chains the archive writer made: by number and id, across
    segments, late or far from their number, another record's id not taken, and a changed record's hash not matching.
  - `ArchiveCheckTest` (`catalog-audit`) checks an in-memory bucket the writer filled: each chain verified to the
    newest checkpoint's head and no further, a later run reading only the new segment, missing numbers after their
    grace, late arrivals, repeats, unended runs, late and broken checkpoints, stray versions, and a changed record.
  - `AuditFeedRoutesTest` (`catalog-server`) runs the routes with the real guard over a store double: a test for
    each refusal, the answers, the 400s and 503s, and the shared read limit; each row's chain status, a record's place
    and the logged and archived gauges.
- Bites: `bites/D23/`, `bites/OBS-262/` and `bites/OBS-272/`.
