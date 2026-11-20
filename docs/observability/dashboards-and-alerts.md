# Dashboards and alert rules

Grafana dashboards and Prometheus alert rules for the catalog and the maintenance worker (backlog 44), in
`deploy/grafana/` (its README says how to load them). Paul chose to draft them at home (D43, 2026-11-19), against
nuna's own meter names and Prometheus' usual OTLP translation, rather than wait for item 43 to see how the names land
in work's Prometheus. **No query has run against live data.** "Names assumed" below says which series names they
read and what to rename if work's Prometheus names them otherwise.

| File | Holds |
|---|---|
| `dashboards/nuna-catalog.json` | 31 panels: the SLOs, loads, commits, credential vending, the audit's Kafka acknowledgement, nuna's alerts |
| `dashboards/nuna-maintenance.json` | 16 panels: the worker's scans, failures, work done and compaction |
| `dashboards/nuna-client-reports.json` | 13 panels: engines' scan and commit reports, by engine |
| `rules/nuna-catalog.rules.yaml` | 9 alerts: two burn-rate alerts for each latency SLO, one for each alert kind |
| `rules/nuna-maintenance.rules.yaml` | 8 alerts: the worker's, as worker-metrics.md designs them |

Each dashboard picks its Prometheus through a `datasource` variable and its deployment through `job`; the catalog's
and the reports' dashboards also pick catalogs (`catalog`), and the reports' engines (`engine`). They use dashboard
`schemaVersion` 39 and Grafana's core panels only (time series, stat, table, text). Rates are over 5 minutes: nuna
pushes its metrics every minute, so a window much shorter than that can hold one sample and draw nothing.

## Names assumed

The meters are Micrometer's (catalog-metrics.md, worker-metrics.md, spans.md). Micrometer pushes them over OTLP/HTTP
every minute, cumulative, with times in milliseconds: an OTLP histogram with unit `milliseconds` for each timer
(`TelemetryTest` in `catalog-maintenance` checks the unit). The queries assume Prometheus' default translation of
OTLP names, which Prometheus' own OTLP receiver (`UnderscoreEscapingWithSuffixes`) and the collector's Prometheus
exporters (`add_metric_suffixes`) share:

- Dots become underscores, in names and in labels: `operation.id` is `operation_id`, and so are `error.type`,
  `requirement.type`, `report.type` and `engine.name`.
- A timer becomes `<name>_milliseconds_count`, `_sum` and `_bucket`, with `le` in milliseconds. Two timers have
  buckets other than `+Inf`: `nuna.catalog.requests` at 10, 50 and 300 ms, the SLOs, and `nuna.audit.publish` at 5,
  10, 25, 50, 100, 250 and 500 ms, 1, 2.5 and 5 s, the console health page's (item 71).
- A counter becomes `<name>_total`.
- A client report's count result (a distribution summary with no unit) becomes `<name>_count` and `_sum`. A bytes
  result keeps its name, which already ends in bytes: `nuna_catalog_report_added_files_size_bytes_sum`.
- `nuna.maintenance.scan.last.success`, a gauge in seconds, becomes `nuna_maintenance_scan_last_success_seconds`.
- `job` comes from `service.namespace` and `service.name`, which the charts set (deploy/README.md, "Telemetry"):
  `<namespace>/nuna-catalog` and `<namespace>/nuna-maintenance`. `instance` comes from `service.instance.id`, the
  pod, so each server's series stand apart and the queries sum them.

The series the files read, 56 in all from 44 meters:

| Meter | Series |
|---|---|
| `nuna.catalog.requests` | `nuna_catalog_requests_milliseconds_bucket`, `_count`, `_sum` |
| `nuna.catalog.commits` | `nuna_catalog_commits_total` |
| `nuna.catalog.credential.vends` | `nuna_catalog_credential_vends_milliseconds_count`, `_sum` |
| `nuna.catalog.metadata.cache.lookups` | `nuna_catalog_metadata_cache_lookups_total` |
| `nuna.catalog.alerts.metadata.integrity`, `.regulated.schema.change`, `.large.metadata` | `nuna_catalog_alerts_metadata_integrity_total`, `nuna_catalog_alerts_regulated_schema_change_total`, `nuna_catalog_alerts_large_metadata_total` |
| `nuna.sts`, `nuna.audit.publish` | `nuna_sts_milliseconds_count`, `_sum`; `nuna_audit_publish_milliseconds_bucket`, `_count`, `_sum` |
| `nuna.catalog.report.*` timers: `total.planning.duration`, `total.duration` | `nuna_catalog_report_total_planning_duration_milliseconds_count`, `_sum`, and the same for `total_duration` |
| `nuna.catalog.report.*` counts: `result.data.files`, `result.delete.files`, `scanned.data.manifests`, `skipped.data.manifests`, `total.file.size.in.bytes`, `attempts`, `added.records`, `removed.records`, `added.files.size.bytes`, `added.positional.delete.files`, `added.equality.delete.files`, `added.dvs` | `nuna_catalog_report_<result>_sum`, and `_count` for `result_data_files`, `result_delete_files` and `attempts` |
| `nuna.maintenance.scan`, `nuna.maintenance.part` | `nuna_maintenance_scan_milliseconds_count`, `_sum`; `nuna_maintenance_part_milliseconds_sum` |
| `nuna.maintenance.scan.last.success` | `nuna_maintenance_scan_last_success_seconds` |
| `nuna.maintenance.failures`, `.tables.needing.compaction` and the 14 counts | `nuna_maintenance_failures_total`, `nuna_maintenance_tables_needing_compaction_total`, `nuna_maintenance_<count>_total` |
| `nuna.maintenance.table.compaction.need` | `nuna_maintenance_table_compaction_need` |
| `http.client.requests` (the worker's calls) | `http_client_requests_milliseconds_count` |

**If work's Prometheus differs** (item 43 finds out), these are the places to rename:

- **Prometheus scrapes Actuator's `prometheus` endpoint** instead of receiving OTLP (item 43's fallback): Micrometer's
  own Prometheus names put timers in seconds, so `_milliseconds_` becomes `_seconds_`, the SLO bounds `le` 0.01,
  0.05 and 0.3, and the mean-time and p99 panels read seconds (unit `s`, not `ms`; the worker's scan rule compares with
  `600`, not `600 * 1000`). Counters keep `_total`.
- **Names keep their dots** (a Prometheus 3 translation strategy without underscore escaping): every name and dotted
  label changes.
- **Native histograms** in place of classic ones: there are no `_bucket` series, the SLO queries become
  `histogram_fraction`, and the audit's p99 takes `histogram_quantile` over the histogram itself, summed by `event`
  without `le`.
- **`le` written as `50.0`** rather than `50`: already matched; the queries use `le=~"50([.]0)?"`.
- **A collector scraped through a ServiceMonitor** without `honorLabels: true`: the series' own `job` and `instance`
  arrive as `exported_job` and `exported_instance`, and the needy-table gauge's `namespace` as `exported_namespace`.
  Set `honorLabels`, or rename.

## The dashboards, by item 44's areas

| Item 44 asks for | Where | Read from |
|---|---|---|
| The SLOs: load p99 < 50 ms, 304 p99 < 10 ms, commit p99 < 300 ms, at least 500 commits a second | nuna catalog, "SLOs": each latency SLO's share within its bound over the time range and over 5-minute windows, each one's error-budget burn rate over the last hour, and commits a second against a line at 500 | `nuna.catalog.requests`, as catalog-metrics.md defines each SLO |
| Commits: the outcome split, and requirement failures by type | nuna catalog, "Commits": replies by outcome, requirement failures by `requirement.type`, refusals and failures by error type, commits by route, mean commit time | `nuna.catalog.commits`, `nuna.catalog.requests` |
| Loads and the share answered 304 | nuna catalog, "Loads": loads by outcome, the 304 share (3xx over 2xx and 3xx, D14), mean load time, failures by error type, the metadata cache's hit share | `nuna.catalog.requests`, `nuna.catalog.metadata.cache.lookups` |
| Credential vending | nuna catalog, "Credential vending": vends by outcome, the cache's share, mean vend time, failures by error type, STS calls and their mean time by storage profile | `nuna.catalog.credential.vends`, `nuna.sts` |
| The audit's Kafka acknowledgement time | nuna catalog, "Audit: Kafka acknowledgement": mean and p99 time and records by event, failed publishes by exception | `nuna.audit.publish` |
| Each nuna alert kind | nuna catalog, "nuna's alerts": each kind fired in the time range, and when | the three `nuna.catalog.alerts.*` counters |
| The worker: its last successful scan, failures by kind | nuna maintenance worker: time since the last success, scans by outcome and their mean time, failures by kind, where scan time goes, the worker's calls; work done; compaction and the neediest tables | worker-metrics.md's meters |
| Client reports by engine | nuna client reports: reports by engine, planning and commit time, files, pruning, bytes, records, attempts and delete files, by engine | `nuna.catalog.report.*`, by `engine.name` |

- **The SLOs are read as shares, not quantiles.** The request histogram's only buckets are the SLO bounds, so
  `histogram_quantile` would interpolate between them and invent a p99. "p99 within 50 ms" is the same statement as
  "at least 99% within 50 ms", which the buckets give exactly, as the console's health page does (health-page.md).
- **The audit's acknowledgement p99 is an estimate.** Its timer is exported with the console health page's ten
  buckets, 5 ms to 5 s (item 71), and the p99 panel takes `histogram_quantile(0.99, ...)` over them, beside the mean.
  Prometheus interpolates within the bucket the p99 falls in, so it can be off by that bucket's width (25 to 50 ms,
  say), and a p99 past 5 s reads 5 s. The health page names the bucket instead.
- **Mean times elsewhere.** The other timers have no buckets in Prometheus, so their panels show means.
- **Reports by engine.** Each report records one sample of each result it carries, so the count of a result that
  every report of its type carries counts the reports: `result-data-files` for scans, `attempts` for commits. A
  report lacking it is not counted there; the `reportMetrics` panel counts every report, without the engine.
- **The neediest tables** are the one panel that names tables (D30, amended 2026-11-15): everyone who can open the
  worker's dashboard sees them.

## The alert rules

| Alert | Severity | Fires when |
|---|---|---|
| `NunaLoadSloBurnFast`, `NunaNotModifiedSloBurnFast`, `NunaCommitSloBurnFast` | page | The SLO's error budget burns at 14.4 times its rate over 1 h and 5 min, or 6 times over 6 h and 30 min |
| `NunaLoadSloBurnSlow`, `NunaNotModifiedSloBurnSlow`, `NunaCommitSloBurnSlow` | warning | It burns at its rate or faster over 3 days and 6 h |
| `NunaMetadataIntegrity` | page | `nuna.catalog.alerts.metadata.integrity` rose in the last 15 minutes |
| `NunaRegulatedSchemaChange`, `NunaLargeMetadata` | warning | Their counter rose in the last 15 minutes |
| `NunaWorkerNotScanning`, `NunaWorkerGone` | page | worker-metrics.md, "Alerts" |
| `NunaPurgesFailing`, `NunaDeletionsFailing`, `NunaMaintenanceJobFailing`, `NunaScansSlowerThanInterval`, `NunaCompactionFallingBehind`, `NunaFilesOutsideTable` | warning | worker-metrics.md, "Alerts" (a "ticket" there) |

- **Burn rates.** Each latency SLO allows 1% of its answers past the bound: that is its error budget. The windows and
  factors are the Google SRE workbook's multiwindow alerts for a 30-day budget: 14.4 spends 2% of the month's budget
  in an hour, 6 spends 5% in six hours, and 1 spends it all in 30 days. The short window of each pair stops the alert
  soon after the burn stops. Fast burns page and slow ones warn, as the workbook has it; change the `severity` label
  to change that. Each SLO is per deployment (`job`), summed over its servers and catalogs. A window with no answers
  of the SLO's kind (no 304s, say) gives no ratio and no alert.
- **Alert kinds.** Metadata integrity pages and the other two warn, as item 44 and catalog-metrics.md ("The alerts")
  have it. The counters are registered when the server starts, so the first alert is a rise from 0 and `increase`
  sees it. Each alert resolves 15 minutes after the counter last rose; the `nuna.alerts` log line names the table.
- **The worker's rules** are worker-metrics.md's, unchanged, with names and severities. `600` in them is the default
  `scan-interval` in seconds: use the deployment's own. `NunaWorkerNotScanning` waits an hour (`for: 1h`) so that a
  new deployment's first scan, which runs every table's orphan scan, can end; an hour is a guess to set at work.
  `NunaWorkerGone` fires only when no worker reports at all.

## Not covered

- **At least 500 commits a second has no alert.** It is the M8 SLO run's throughput target under a given load. In
  production the rate is set by demand, and nuna has no metric of offered commits to compare the served ones with, so
  a burn rate on it would fire whenever traffic is light. The dashboard shows the rate against a line at 500. Item 44
  asks for burn-rate alerts on each SLO; whether this one gets an alert, and on what, is Paul's to decide.
- **No alert on errors.** The R7 SLOs are latencies of successful answers: a 5xx is neither inside nor outside them,
  so a catalog answering every call 500 fires none of these rules. The dashboards show failures by error type.

## How they were checked

At home, with no Grafana, Prometheus or promtool (2026-11-19, item 44's status entry; the counts are as of item 71's
p99 panel, checked the same way):

- Every JSON file parses, every panel and variable query uses the `datasource` variable, and panel ids are unique.
- Every YAML file parses (PyYAML) as a rule file: a `groups:` list of rules with `alert`, `expr`, `labels` and
  `annotations`, each with `severity` `page` or `warning`. Nine of the 17 have a `for`. The other eight fire on the
  first evaluation that finds their condition (corrected by item 74; this line said every rule had one):
  - `NunaMetadataIntegrity`, `NunaRegulatedSchemaChange` and `NunaLargeMetadata`. Each fires on a single event,
    which its `increase(...[15m]) > 0` holds for 15 minutes: a `for` would only delay it, and one of 15 minutes or
    more could keep a lone event from firing at all.
  - The worker's `NunaWorkerGone`, `NunaPurgesFailing`, `NunaDeletionsFailing`, `NunaScansSlowerThanInterval` and
    `NunaFilesOutsideTable`. Their expressions already look back over a window: 15 minutes with no sample, failures
    and nothing done in an hour, an hour's mean, or a day's count.
- Brackets balance in every one of the 100 expressions; PromQL itself was not parsed.
- Every series name in an expression maps back, by the translation above, to a meter named in these docs and in the
  main code, with the unit its type gives (a timer `_milliseconds`, a counter `_total`); every label maps back to a
  documented tag. All 56 series and 17 labels did. A misspelt name and a wrong label put in by hand were both caught.

Not checked: that the translation above is what work's Prometheus does (item 43), that Grafana imports the files,
and that Prometheus loads the rules. Item 44 is done at work when the dashboards show live data and each alert has
fired once in a test.
