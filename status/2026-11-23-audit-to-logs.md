# 2026-11-23, audit goes to the log `nuna.audit`; the audit table writer takes it over OTLP (backlog 195)

D23 amended (Paul, 2026-11-23: "Log as the main audit path"). Every audit record is written as its CloudEvent JSON to
logger `nuna.audit`; Kafka is optional and best effort. The server starts, serves and vends without Kafka, and the
relay drains the outbox to the log. The audit table writer gains the source `otlp`, a token-guarded OTLP/HTTP logs
receiver on 4318 that writes the `nuna.audit` records deduplicated by CloudEvent id, in partition -1. The charts,
`deploy/README.md`, `docs/observability/audit-log.md` and `catalog-audit-tables/README.md` say how to wire it.

Checked:
- `./gradlew :catalog-audit-tables:test` and `:catalog-server:test` for `AuditTrailTest`, `AuditRuntimeWithoutKafkaTest`
  and `AuditLogToWriterTest`: 41 and 10 tests, none failed.
- `./gradlew build` after the last change: BUILD SUCCESSFUL in 2m 26s, 1,618 tests, none failed, `:bitesApply` green.
- `helm template` of both charts with the source `kafka` and `otlp`, with and without a logs endpoint.
- Every bite with `git apply --check`: 14 new in `bites/D23/`, `OBS-41/kafka-not-observed` recorded again,
  `M7/credentials-not-fail-closed` removed with the check it broke.

Not covered: the heavy tests touched (`KafkaOutageTest` rewritten to the new behaviour, `CredentialExpiryTest`'s
message, the load tests' `Stack`), and the new bites, which have not been run; the overnight run runs both.
