# The audit log

Every audit record the catalog makes is a line of its log `nuna.audit` [D23, amended 2026-11-23]. That log is the
record of audit; Kafka is optional. Paul, 2026-11-23: "Log as the main audit path", "People use Prometheus and otel not
kafka for this". Backlog 195 built it.

## What is written, and when

- Each record is written as its CloudEvent JSON, the same bytes the audit topic carries (`EventCodec`), as the message
  of one INFO line of the logger `nuna.audit` (`AuditTrail`).
- Reads, denials, authentication failures, audit reads and credential issues are written before the catalog answers
  the request they record. Nothing waits on Kafka for them, and nothing is refused for want of it: a credential issue
  is written as a read is.
- Changes (commits, drops, grants, governance and hold changes, and the rest of the outbox's types) go into the outbox
  in the change's own database transaction, as before. The outbox relay writes each to the log shortly after its
  commit (every `nuna.audit.relay-interval`, 250 ms by default), at least once, with or without Kafka: a change is in
  the database until the relay has written it, so a server that dies before writing it leaves it to the next relay.
- A notice that changes nothing in the store, such as a branch's expiry warning (D33), is written as the relay writes
  a batch, before the route answers.
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
  in a Kafka outage the topic, the change stream, the SIEM's feed and the Object Lock archive miss what happened
  meanwhile; the log has it. The archive still needs Kafka, and the server refuses to start with an archive and no
  Kafka.

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
  and finds rows by that pair. Such a row has no place in an archive chain.
- Records wait in memory a commit interval and are answered at once; a writer that dies before its commit loses them
  (they are still on the catalog's stdout), and a shutdown commits them first. catalog-audit-tables/README.md has the
  detail.

## Tests

| Test | Shows |
|---|---|
| `catalog-server` `AuditTrailTest` | Every record is in the log before the call returns; a Kafka failure refuses nothing, a credential issue included, and Kafka is skipped for the probe interval after; a relayed batch is in the log in order; the audit log off stops startup. |
| `catalog-server` `AuditRuntimeWithoutKafkaTest` | With no Kafka the relay drains the outbox to the log, and a notice is in the log before the route answers. |
| `catalog-server` `AuditLogToWriterTest` | A record written to `nuna.audit` reaches the writer's receiver through the server's log wiring and Boot's exporter, with the token header; other log lines do not. |
| `catalog-server` `KafkaOutageTest` (heavy) | With the broker paused, loads vend, commits, creates, drops and governance changes succeed, each record in the log; records reach the topic again once it is back. |
| `catalog-audit-tables` `OtlpLogsReceiverTest` | Protobuf and JSON, gzip, the scope filter, the token, and the partial success naming rejected records. |
| `catalog-audit-tables` `LogWriterTest` | Each record once, across repeats, a lost answer and a restart; rows in partition -1 with rising numbers; a failed commit keeps its rows; the writer's own records left out. |

Each guarded rule's bite is in `bites/D23/`.
