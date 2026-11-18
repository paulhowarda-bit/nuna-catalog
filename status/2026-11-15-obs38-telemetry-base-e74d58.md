# 2026-11-15, telemetry foundation: OTLP export, JSON logs, one request ID (backlog 38)

What changed:
- The server and the worker take Spring Boot's OpenTelemetry starter and send metrics, traces and logs over
  OTLP/HTTP, over the JDK's HTTP client (`opentelemetry-exporter-sender-jdk`; the OkHttp sender is excluded from every
  module in `build.gradle.kts`). Nothing exports until an endpoint is set: Boot already holds back traces and logs, and
  `MetricsExportNeedsEndpoint` (in each module) keeps Boot's metrics push to localhost:4318 off until a metrics
  endpoint is set, through Boot's properties or the standard `OTEL_*` variables.
- Log lines go to stdout as ECS JSON (`logging.structured.format.console: ecs`), and over OTLP when a logs endpoint is
  set, through the logback appender (`opentelemetry-logback-appender-1.0` 2.28.0-alpha) that `TelemetryWiring` and the
  worker's `Telemetry` attach.
- `RequestIds` decides each request's id once, right after Boot opens the request's span: the client's
  `X-Request-ID` of 1 to 128 characters, not blank, else the trace id. Every response carries it, and it is in the MDC
  as `requestId`. `Authentication`'s refusals, `RequestContext` (REST audit events and STS sessions) and governance's
  `Management` take it from there instead of making their own.
- Both Helm charts take `telemetry.endpoint`, headers (plain or from a Secret), a CA ConfigMap, sampling (10 %,
  parent-based) and resource attributes, and always set `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`.
- New docs/observability/README.md; deploy/README.md gains "Telemetry"; docs/install-at-work.md lists the new
  artifacts.
- `bites/M5/auth-failure-not-audited.patch` recorded again, the same break, against `refuse`'s new first line.

Choices: ECS as the JSON format. `Management.REQUEST_ID` stays, now naming `RequestIds.HEADER`, because
`DrReconcileRouteTest` uses it. Item 38's Touches line gained the worker's `Telemetry.java`.

Checked:
- `catalog-server` `TelemetryTest`, 6 tests, against `OtlpReceiver`, a small OTLP/HTTP receiver in the server's test
  jar: a REST call's span and `http.server.requests` arrive; the request ID rules (trace id without a client id; 1 to
  128 characters kept; blank and 129 replaced; the same id in the response, the AuthFailure, the CredentialIssue and
  its STS session name, and a management route's Denial); every response carries it (200, 401, 403, 404, 500, the
  token route, the console's files); log lines carry the trace, span and request ids on stdout and over OTLP; no
  principal, end user or filter value in any span attribute or metric label.
- `ExportNeedsEndpointTest` (4), `FailedExportTest` (1: 300 requests against a collector that never answers, none
  slower than 2 s, with 4 s export timeouts), and the worker's `TelemetryTest` (4, one starting the worker in a JVM of
  its own with the `OTEL_*` variables alone).
- Bites, `bites/run.sh` on each: all 12 in `bites/OBS-38/` BITES, each on its intended assertion. Two were run twice:
  `principal-in-labels` was SETUP the first time (Flyway's 30 s, with the nuna-demo stack and another build in Docker)
  and `refusal-own-id` first failed at table creation on a Silo read timeout; both then bit on their checks.
  `worker-metrics-export-always` did not bite at first: the worker's tests carry the server's jar and its rule, so a
  new test, `withNoEndpointMetricsStayOff`, checks the worker's rule and its registration directly.
- `helm template` and `helm lint` on both charts, with and without an endpoint, headers Secret and CA.
- The fast build, `./gradlew build`, after merging main (4acb145): green on the second run, catalog-server's 139
  tests among it. The first run failed one test, `TwoIssuersTest#aTokenFromAnUntrustedIssuerIsRefused`, on a connection
  reset from the Keycloak container's token endpoint, before the catalog was called, with the nuna-demo stack and
  another session's build in Docker. An earlier run stopped at `:bitesApply` on the M5 bite above. After merging main
  again (4eba722, backlog 37): green on the first run, every bite applying.

Not covered:
- The worker makes no spans of its own and its calls to the catalog carry no trace context: backlog 49.
- The charts' telemetry values were rendered, never installed; nothing has been pointed at a real collector.
- With a collector that never answers, a stopping server or worker waits for its last exports, up to their timeouts
  (10 s each), within the charts' grace periods (docs/observability/README.md).
