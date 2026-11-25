# The audit log

Every audit record the catalog makes is a line of its log `nuna.audit` [D23, amended 2026-11-23]. That log is the
record of audit; Kafka is optional. Paul, 2026-11-23: "Log as the main audit path", "People use Prometheus and otel not
kafka for this". Backlog 195 built it. The Object Lock archive is fed from the same log [D23, amended 2026-11-25; Paul:
"(c) Log path"]; backlog 262 built it.

## What is written, and when

- Each record is written as its CloudEvent JSON, the same bytes the audit topic carries (`EventCodec`), as the message
  of one INFO line of the logger `nuna.audit` (`AuditTrail`), plus two CloudEvent extensions that only the log
  carries: `nunarun`, the server run's id, a UUID the server picks when it starts, and `nunaseq`, the line's number in
  that run, from 1. The archive check uses them to find records that never arrived ("The archive", below).
- Reads, denials, authentication failures, audit reads and credential issues are written before the catalog answers
  the request they record. Nothing waits on Kafka for them, and nothing is refused for want of it: a credential issue
  is written as a read is.
- Changes (commits, drops, grants, governance and hold changes, and the rest of the outbox's types) go into the outbox
  in the change's own database transaction, as before. The outbox relay writes each to the log shortly after its
  commit (every `nuna.audit.relay-interval`, 250 ms by default), at least once, with or without Kafka: a change is in
  the database until the relay has written it, so a server that dies before writing it leaves it to the next relay.
- A notice that changes nothing in the store, such as a branch's expiry warning (D33), is written as the relay writes
  a batch, before the route answers.
- A server that stops writes a run-end record (`nuna.catalog.run-end`, naming its host) as its run's last line, in the
  last phase of stopping, after every other part has stopped. It goes to the log only, not to Kafka.
- `logging.level.nuna.audit` is INFO in `application.yaml`, so raising the root level leaves audit on. The server
  refuses to start with it coarser than INFO.

## Where the lines go

Like every log line (README.md, "Log lines"): to stdout as ECS JSON, whose `message` is the CloudEvent and whose
`log.logger` is `nuna.audit`; and, where a logs endpoint is set, over OTLP/HTTP as a log record whose instrumentation
scope name is `nuna.audit` (OpenTelemetry's logback appender names the scope after the logger) and whose body is the
CloudEvent. A collector can route on either.

The logs endpoint can be set on its own, so the logs go somewhere the metrics and traces do not:

| Setting | Meaning |
|---|---|
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` (`management.opentelemetry.logging.export.otlp.endpoint`) | The full URL logs go to, with `/v1/logs`, e.g. the audit table writer's `http://nuna-audit-tables:4318/v1/logs`. Takes precedence over `OTEL_EXPORTER_OTLP_ENDPOINT` for logs. |
| `OTEL_EXPORTER_OTLP_LOGS_HEADERS` (`management.opentelemetry.logging.export.otlp.headers.<name>`) | Headers for the log exports only, e.g. `X-Nuna-Audit-Token=<token>` for the writer. |
| `management.opentelemetry.logging.export.max-queue-size` | The exporter's queue, 2,048 records by default. |

The catalog chart sets the first two from `telemetry.logsEndpoint` and `telemetry.logsTokenSecret` (deploy/README.md,
"Audit").

## What can be lost

The log is at most once past the line being written. Paul accepted this in choosing the log: a record can be lost if a
pod dies before the collector takes its last lines.

- **Stdout.** A line is on stdout when the call returns; the node's log collection takes it from there. A pod that dies
  before its lines are collected loses them.
- **OTLP.** The exporter queues records in memory (2,048 by default) and sends them in batches from a thread of its
  own, so a slow or dead endpoint never slows a request (README.md, "A failed export never fails or slows a request").
  A full queue drops records, and a pod that dies loses what is queued. Stdout still has them.
- **Kafka.** Where `nuna.audit.kafka-bootstrap-servers` is set, every record also goes to the audit topic, and the
  relay's object changes to the change stream, best effort: what Kafka does not acknowledge within the delivery
  timeout is in the log only, and for `nuna.audit.probe-interval` (10 s) after a failure every record skips Kafka. So
  in a Kafka outage the topic, the change stream and the SIEM's feed miss what happened meanwhile; the log, and the
  archive fed from it, have it.
- **The archive** is fed by the OTLP leg, so it can miss what that leg loses. Every such miss shows in the archive
  check ("What the archive can miss, and how a miss shows", below).

## The audit table writer's source `otlp`

The console's Audit page reads the queryable audit table `nuna_audit.audit.events` (D23, D45). Its writer
(`catalog-audit-tables`) takes the records from the audit topic (`nuna.audit-tables.source: kafka`, the default) or
from the catalog's log (`otlp`), so the page needs no Kafka:

- An OTLP/HTTP logs receiver, `POST /v1/logs` on `nuna.audit-tables.otlp.port` (4318), protobuf or JSON, gzipped or
  not. It keeps the log records whose scope name is `nuna.audit` and ignores the rest, so the catalog's whole log can
  be pointed at it, directly or through a collector that forwards the `nuna.audit` records.
- Every request carries the shared token `nuna.audit-tables.otlp.token` in `X-Nuna-Audit-Token`, or is refused with 401
  and nothing in it read: without it anything that reaches the port could add rows to the console's Audit page.
- Rows are deduplicated by CloudEvent id: an id seen in the last `nuna.audit-tables.otlp.dedup-window` (15 minutes) is
  left out, whether the exporter sent its batch twice or the relay wrote a change twice.
- A row from the log is in no topic partition: its `topic_partition` is `-1` and its `topic_offset` a number the writer
  gives it, the time in microseconds or one more than the last, which rises and never repeats. The feed's reader orders
  and finds rows by that pair. Its archive chain is its server run's, which the table does not record, so the feed
  cannot place a row there yet (backlog 272).
- Records wait in memory a commit interval and are answered at once; a writer that dies before its commit loses them
  (they are still on the catalog's stdout), and a shutdown commits them first. catalog-audit-tables/README.md has the
  detail.

## The archive

The seven-year record (D24; seven years since item 264) is a hash-chained archive in an S3 bucket created with Object
Lock. The audit table writer with the source `otlp` writes it, from the records its receiver takes; the catalog
servers only read it. Neither needs Kafka for it.

- **Set up.** On the writer, `nuna.audit-tables.archive.bucket` and the rest of `nuna.audit-tables.archive.*`
  (catalog-audit-tables/README.md); on the catalog, `nuna.audit.archive.bucket`, `.profile` (the storage profile that
  reaches the bucket) and `.prefix` (the writer's, `audit` by default). The writer refuses an archive with the source
  `kafka`.
- **Chains.** Each server run's records form one hash chain, `<prefix>/runs/<run>/`, each record hashed with the one
  before. The writer archives every audit record byte for byte as it arrives, before the table's own filters (the
  writer's own records, a record it cannot map), so the archive holds what the table leaves out. Records with no
  stamp, from a server older than it, go to a chain of the writer's own, `unstamped-<writer>`.
- **Segments.** A chain is written in segments of at most `segment-events` records (10,000) or `segment-age` of
  waiting (1 minute), and a run's last segment as soon as its run-end record arrives. Each is written once
  (`If-None-Match`) and locked in COMPLIANCE mode for `retention`, at least 2,557 days; the writer refuses less. A
  writer that restarts continues each chain from its last segment.
- **Checkpoints.** After every round of segments, and every `checkpoint-interval` (5 minutes) when it wrote none, the
  writer writes a checkpoint, locked the same way, `<prefix>/checkpoints/<yyyyMMdd>/<HHmmss.SSS>Z.json`: each chain's
  head (its last number and hash) and the key and SHA-256 of the checkpoint before. The checkpoints anchor each
  chain's head in the bucket, where no one can change it, rather than in the writer's memory.
- **Answers.** When the archive cannot take a request's records, because more than `max-waiting` records wait or the
  store cannot be reached, the receiver answers 503 with `Retry-After`, the exporter retries, and the table takes none
  of that request either. A body that is no CloudEvent is refused and logged as an error.
- **The check.** Every catalog server with an archive checks it a minute after it starts and hourly after that
  (`ArchiveCheck`). It follows the checkpoints written since the last it verified, each naming the one before; verifies
  each chain up to the newest head a checkpoint gave it, reading only the segments written since; and follows each
  run's numbers. What it verified is kept in the bucket, `<prefix>/check/state.json`, so every server shares it and a
  restart does not read the whole archive again. A run that ended with every number archived and verified is done, and
  is not read again. `GET /management/v1/audit/archive` gives the last result (catalog-audit-tables/README.md).
- **Reads.** The DR reconcile proves each object's tip from the archive (D37). `GrantReplay` (a library, no route yet)
  replays grant changes from it; a grant two runs changed has changes in both chains, so its caller merges the chains
  by record time.

### What the archive can miss, and how a miss shows

| What is lost | How it shows |
|---|---|
| Records lost between a server and the writer: the exporter's queue full, a pod that dies with records queued, a 503 the exporter stopped retrying, a collector that drops | Numbers missing from the run's chain once later records of the run arrive: the check lists each range in `missing`, after twice the checkpoint interval, which leaves a late batch time to land. Stdout still has them. |
| The last lines of a server that is killed, crashes or loses its node | No run-end record: the run is `unended` once its highest number has stood still for a day. |
| Records waiting in the writer when it dies (up to a minute's segment, at most `max-waiting`) | Missing numbers, when the runs write on; an unended run, when it stopped too. The writer's checkpoints stop as well, so the newest is `late`, older than twice the interval. |
| The writer down, or unable to reach the bucket | Checkpoints `late`. The exporters retry within their limits and then drop, which shows as missing numbers. |
| A segment or checkpoint changed or replaced | Locked objects cannot be changed or deleted before their retention ends. A delete marker or a second version on a key of a chain the check reads, or on a checkpoint, is a problem; so is a chain whose hashes do not link, or that does not reach the head a checkpoint gave it. |
| A record the writer archived twice (a restart while the exporter retried) | Nothing is lost; the check counts it in `repeats`. |

The limits of what shows:

- A server that has logged nothing for a day reads as unended until it logs again: the archive cannot tell an idle
  server from a dead one.
- Records lost at the end of a server that is still running show only when it logs again, ends, or reaches that day
  (backlog 272 adds the server's own count beside the archive's).
- Unstamped records, from a server older than the stamp, can show a broken chain but not a missing record.
- A run the check has marked done is not read again, so a delete marker put on one of its keys afterwards is not
  reported; the locked versions beneath it stay.
- When two servers' checks overlap, the later one's state is kept. That can delay a finding to the next run; it loses
  none.

## Tests

| Test | Shows |
|---|---|
| `catalog-server` `AuditTrailTest` | Every record is in the log before the call returns; a Kafka failure refuses nothing, a credential issue included, and Kafka is skipped for the probe interval after; a relayed batch is in the log in order; each logged line carries its run and number and the Kafka copy does not; the run ends with one run-end record, in the last phase of stopping; the audit log off stops startup. |
| `catalog-server` `AuditRuntimeWithoutKafkaTest` | With no Kafka the relay drains the outbox to the log, and a notice is in the log before the route answers; an archive needs no Kafka. |
| `catalog-server` `AuditLogToWriterTest` | A record written to `nuna.audit` reaches the writer's receiver through the server's log wiring and Boot's exporter, with the token header; other log lines do not. |
| `catalog-server` `KafkaOutageTest` (heavy) | With the broker paused, loads vend, commits, creates, drops and governance changes succeed, each record in the log; records reach the topic again once it is back. |
| `catalog-server` `AuditFeedRoutesTest` | The archive status: the last check's checkpoints and counts, and only the chains with something to show. |
| `catalog-audit-tables` `OtlpLogsReceiverTest` | Protobuf and JSON, gzip, the scope filter, the token, and the partial success naming rejected records; every audit record archived as it came, those the table leaves out included; a request the archive cannot take turned away whole. |
| `catalog-audit-tables` `LogWriterTest` | Each record once, across repeats, a lost answer and a restart; rows in partition -1 with rising numbers; a failed commit keeps its rows; the writer's own records left out. |
| `catalog-audit` `ArchiveSinkTest` | Segments sealed by count, age and run end; refused bodies; the unstamped chain; a busy writer refusing; an unreachable store and a lost answer; a second writer; checkpoints linked across a restart and written when idle; a repeat left out. |
| `catalog-audit` `ArchiveCheckTest` | Missing numbers, late arrivals, unended runs, repeats, late and broken checkpoints, strays and broken chains found; a later run reading only what is new. |
| `catalog-audit` `LockedArchiveTest` (heavy) | On the run's S3 store, segments and checkpoints locked in COMPLIANCE mode, a rewrite refused, and the check reading the writer's chains intact. |
| `catalog-tests-load` `DrDrillTest` (overnight) | The DR reconcile proving each tip from an archive the servers' logs fed. |

Each guarded rule's bite is in `bites/D23/` or `bites/OBS-262/`.
