# Grafana dashboards and Prometheus alert rules

Backlog 44, drafted at home (D43): they read nuna's own meter names as Prometheus' default OTLP translation names
them. Since 2026-11-25 the demo runs them against its live series, with Prometheus' OTLP receiver (`demo/demo.sh
metrics`, backlog 233), and every name held. docs/observability/dashboards-and-alerts.md says what each shows, which
series names it assumes, and what to rename if work's Prometheus names them otherwise (item 43 finds out).

| File | What |
|---|---|
| `dashboards/nuna-catalog.json` | The catalog servers: the SLOs, loads and the 304 share, commits, credential vending, the audit's Kafka acknowledgement, nuna's alerts. |
| `dashboards/nuna-maintenance.json` | The maintenance worker: its last successful scan, failures by kind, the work it did, compaction. |
| `dashboards/nuna-client-reports.json` | What engines report about their scans and commits, by engine. |
| `rules/nuna-catalog.rules.yaml` | Burn-rate alerts on the three latency SLOs; one alert for each of nuna's alert kinds. |
| `rules/nuna-maintenance.rules.yaml` | The worker's alerts, as worker-metrics.md designs them. |

**Dashboards.** Import each JSON file into Grafana (Dashboards, New, Import) and pick a Prometheus data source in the
`datasource` variable at the top; no data source is named in the files. The `job` variable picks the deployment.

**Alert rules.** Plain Prometheus rule files: each holds a `groups:` list. List them under `rule_files:` in
Prometheus, or, with the Prometheus Operator, paste a file's `groups:` list as it is under a PrometheusRule's
`spec.groups`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: nuna-catalog
  labels: {}          # whatever labels work's Prometheus selects rules by
spec:
  groups:             # the file's groups list, unchanged
    - name: nuna-catalog-slos
      rules: [...]
```

Every rule has a `severity` label, `page` or `warning`, for Alertmanager to route on.
