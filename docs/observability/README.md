# Observability

The catalog server (`nuna-catalog`) and the maintenance worker (`nuna-maintenance`) send metrics, traces and logs to an
OpenTelemetry collector over OTLP/HTTP, and write their log lines to stdout as JSON. Nothing is exported until an
endpoint is named, so the tests, the demo and a laptop run need no collector. Backlog 38 laid this foundation; 39, 40
and 41 add the catalog's own meters and spans, and 49 the worker's spans.

The audit log, every audit record as a log line, and the audit table writer's OTLP logs receiver are in
audit-log.md (backlog 195). The worker's meters are in worker-metrics.md and its spans in worker-spans.md, the
catalog's in catalog-metrics.md and its spans in spans.md. What the
catalog keeps of the worker's own work, each table's assessment and the recent scans, for the console's health pages,
is in table-health.md (backlog 47), with the read that gives every table the caller may select one row for the
console's ice line (backlog 127). The console's health page, and how a server gathers catalog health from every
server, is in health-page.md (backlog 48). The Grafana dashboards and Prometheus alert rules built on these meters,
in `deploy/grafana/`, and the series names they assume, are in dashboards-and-alerts.md (backlog 44). The DR
replication watermark, its verifier in the worker, its routes and its lag, is in dr-watermark.md (item 28). Each
night's maintenance, every job the worker ran with its slot, its counts and how it ended, kept a month for the
console's overview and maintenance pages, is in maintenance-nights.md (backlog 126). The read that gives a
namespace's tables with each one's format, snapshot count and last commit, from the pointer and the commit journal,
for the console's Contents, is in namespace-tables.md (backlog 137).

## What is exported

- **Metrics.** Spring Boot's standard meters, for example `http.server.requests` (a timer per route template,
  method, status and outcome), `jvm.*`, `process.*`, `system.*` and `logback.events`. Pushed every
  minute (`management.otlp.metrics.export.step`). The catalog's own meters come with backlog 39 (the server) and 40
  (the worker).
- **Traces.** A server span for every HTTP request the catalog answers, named for its method and route template. A
  caller's W3C `traceparent` is honoured, so a client's trace continues into the catalog. Beneath it, each call the
  request makes to the database, the object store, STS and Kafka is a span of its own, with a timer of the same name
  (spans.md, backlog 41). Each of the worker's scans is a trace of its own, a span for each part of it, and its calls
  to the catalog carry the trace, so the catalog's spans for them join it (worker-spans.md, backlog 49).
- **Logs.** Every log line, to stdout as JSON and, when a logs endpoint is named, as an OTLP log record.

Each signal carries the resource attributes `service.name` (`nuna-catalog` or `nuna-maintenance`, or
`OTEL_SERVICE_NAME`) and whatever `OTEL_RESOURCE_ATTRIBUTES` adds; the Helm charts add the namespace, version, pod and
node (deploy/README.md, "Telemetry").

## Log lines

The format is Elastic Common Schema (`logging.structured.format.console: ecs` in each `application.yaml`). A line
written while a request runs carries:

- `traceId` and `spanId`, the request's span;
- `requestId`, the request ID below;
- `service.name`, `log.level`, `log.logger`, `message`, and `error.*` with the stack trace when there is one.

Over OTLP the trace and span IDs are the record's own, and `requestId` is an attribute. To use Logstash's or GELF's
format instead, set `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` (or `gelf`).

## The request ID

One key joins an audit event, an STS session (and so CloudTrail), a log line and a trace:

- A client's `X-Request-ID` of 1 to 128 characters, not blank, is kept.
- Otherwise the request ID is the request's trace ID, 32 hex characters, which is also the caller's when it sent a
  `traceparent`.
- Every response carries `X-Request-ID`, errors included (401, 403, 404, 500, the token route and the console's files).
- The audit events record it as `actor.requestId`. Credential vending puts it in the STS session's `nuna-request` tag
  and its session name, cleaned for STS as before.

`RequestIds` (catalog-server, `telemetry`) decides it once per request, right after Boot opens the request's span.

## Pointing it at a collector

The standard OpenTelemetry variables are enough; Spring Boot reads them:

| Variable | Meaning |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | The collector's OTLP/HTTP base URL, e.g. `http://otel-collector:4318`. Switches on all three signals; `/v1/metrics`, `/v1/traces` and `/v1/logs` are added to it. |
| `OTEL_EXPORTER_OTLP_{METRICS,TRACES,LOGS}_ENDPOINT` | One signal's full URL instead; `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` alone sends the logs, and so the audit log, somewhere with no metrics or traces (audit-log.md). |
| `OTEL_EXPORTER_OTLP_LOGS_HEADERS` | Headers for the log exports only, e.g. the audit table writer's `X-Nuna-Audit-Token=<token>`. |
| `OTEL_EXPORTER_OTLP_HEADERS` | `name=value` pairs, separated by commas, sent with every export. |
| `OTEL_EXPORTER_OTLP_CERTIFICATE` | A PEM file with the CA that signed an https collector's certificate. |
| `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` | The resource the signals come from. |
| `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` | Which traces are kept, e.g. `parentbased_traceidratio` and `0.1`. Without them Boot keeps 10 % of new traces (`management.tracing.sampling.probability`). |

The Helm charts set these from their `telemetry` values (deploy/README.md, "Telemetry"). Outside Kubernetes, Boot's own
properties do the same: `management.otlp.metrics.export.url`, `management.opentelemetry.tracing.export.otlp.endpoint`,
`management.opentelemetry.logging.export.otlp.endpoint`, and their `headers`.

- **OTLP/HTTP with protobuf only.** OTLP over gRPC (port 4317) is not supported.
- **Off until an endpoint is named.** Boot exports traces and logs only then. For metrics Boot alone would push to
  `localhost:4318` every minute; `MetricsExportNeedsEndpoint` (in each module) keeps metrics off until a metrics
  endpoint is set. `management.otlp.metrics.export.enabled` or `OTEL_METRICS_EXPORTER`, when set, decides instead.
- **Sampling.** The charts keep 10 % of new traces (`telemetry.sampling`), and every request whose caller's trace was
  sampled. Metrics and logs are never sampled. Which traces each mode keeps (backlog 265):
  - *Head sampling*, what the charts run today: the decision is made before the request runs, so a failed call's trace
    is kept only if its trace was among the 10 %; about 9 in 10 failed calls leave no trace (inferred). The way back to
    one is its request ID, in its log line and its audit record ("Log lines").
  - *Tail sampling*, with a collector that runs it: the SDK keeps every trace (`telemetry.sampling: "1.0"`), a
    `loadbalancing` exporter tier routes spans by trace ID, and a `tail_sampling` tier keeps every trace with status
    ERROR, those slower than their SLO bound and 10 % of the rest. Traces then show about 30 s late (`decision_wait`).
    Every 5xx answer's server span ends with status ERROR (spans.md, "The server span's status"), so a policy on
    status ERROR alone keeps every failed call; a 4xx leaves it unset. Whether a deployment can run the two tiers is
    found where it is installed (backlog 43), and until then it keeps head sampling. The demo's collector
    (`demo/k8s/metrics.yaml`, the switch `metrics`) runs the `tail_sampling` tier as a deployment would: policies
    `errors` (status ERROR), `slow` (over 300 ms, the commit SLO's bound) and `a-tenth-of-the-rest`, on the contrib
    image (the core image has no `tail_sampling`). It has one collector, so it needs no `loadbalancing` tier; a
    deployment with more than one copies the processor and adds that tier in front. Checked 2026-11-26 by running
    that configuration with a file exporter and sending it 202 single-span traces: the ERROR one and the 500 ms one
    were kept, and 19 of the 200 others. `otelcol validate` passes a misspelt policy type, so a change to the
    policies is checked by running them.
- The OTLP exporter sends over the JDK's HTTP client (`opentelemetry-exporter-sender-jdk`); OkHttp is excluded from
  every module (`build.gradle.kts`).

## Audit is a log

Every audit record is a line of the log `nuna.audit`, as its CloudEvent JSON (D23, amended 2026-11-23; backlog 195):
reads, denials and credential issues before the catalog answers, changes as the outbox relay drains them. That log, on stdout and over OTLP like every other line, is the record of audit; Kafka
is optional, and where it is configured it takes a copy, best effort. Unlike the other signals it is never sampled, and
the server refuses to start with it switched off. What can be lost, and how the audit table writer takes the records
over OTLP for the console's Audit page, is in audit-log.md.

## A failed export never fails or slows a request

A request only hands its span and log records to bounded in-memory queues (2,048 each by default); a background thread
sends them in batches, and drops what does not fit when the collector is slow or down. Metrics are read and pushed on a
thread of their own. `FailedExportTest` points all three at a collector that takes connections and never answers, with
queues of 8 and exports timing out after 4 s: 300 requests in a row all answer, none slower than 2 s, where a blocking
export would hold one for the export's timeout.

What a dead collector does cost: each failed export logs a warning or an error, and a server or worker that stops waits
for its last exports, up to their timeouts (10 s each), before it exits. Within the charts' grace periods (45 s and
70 s) that delays a shutdown but loses no request.

## What labels and attributes never hold

No metric label or span attribute names a person (the principal, the end user in `X-Nuna-End-User`) or holds a filter
value. Boot's request meters and spans label by route template (`/v1/{prefix}/namespaces/{namespace}/...`), method,
status and outcome; the span also records the request's path, which names namespaces and tables but never a person or a
filter (scan filters travel in request bodies). `TelemetryTest#noPersonOrFilterValueInLabelsOrAttributes` checks this.
New meters and spans (39-41, 49) must keep to it. The worker's spans name no table or namespace at all; their calls'
`uri` is a route (worker-spans.md).

## Prometheus later

Catalog code uses Micrometer's API (Observation, MeterRegistry), never the OpenTelemetry SDK, so scraping by
Prometheus instead of pushing OTLP is a dependency and a setting, not a code change. Only the wiring that sends log
lines over OTLP (`TelemetryWiring`, the worker's `Telemetry`) touches OpenTelemetry's API.

## Tests and bites

| Test | Shows |
|---|---|
| `catalog-server` `TelemetryTest` | A REST call's span and metric arrive at a small OTLP receiver; the request ID rules; every response carries the ID; log lines carry the IDs on stdout and over OTLP; no person or filter value in labels or attributes. |
| `catalog-server` `ExportNeedsEndpointTest` | With no endpoint nothing exports; each endpoint switches its own signal on; the standard variables count. |
| `catalog-server` `FailedExportTest` | A collector that never answers fails and slows no request. |
| `catalog-maintenance` `TelemetryTest` | The worker's metrics, a span and its log lines arrive, also from the standard variables alone; with no endpoint nothing exports. |
| `catalog-maintenance` `WorkerTraceTest` | A scan's span and the catalog's spans for its calls arrive in one trace (worker-spans.md). |

Each guarded rule's bite is in `bites/OBS-38/`; those of the worker's spans in `bites/OBS-49/`.
