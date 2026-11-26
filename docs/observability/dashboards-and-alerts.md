# Dashboards and alert rules

Grafana dashboards and Prometheus alert rules for the catalog and the maintenance worker (backlog 44), in
`deploy/grafana/` (its README says how to load them). Paul chose to draft them at home (D43, 2026-11-19), against
nuna's own meter names and Prometheus' usual OTLP translation, rather than wait for item 43 to see how the names land
in work's Prometheus. Since 2026-11-25 they run against the demo's live series (the switch `metrics`, backlog 233;
"In the demo" below): every name held. "Names assumed" below says which series names they read and what to rename
if work's Prometheus names them otherwise.

| File | Holds |
|---|---|
| `dashboards/nuna-catalog.json` | 36 panels: the latency and availability SLOs, loads, commits, credential vending, the audit's Kafka acknowledgement and archive, nuna's alerts |
| `dashboards/nuna-maintenance.json` | 16 panels: the worker's scans, failures, work done and compaction |
| `dashboards/nuna-client-reports.json` | 13 panels: engines' scan and commit reports, by engine |
| `rules/nuna-catalog.rules.yaml` | 19 alerts: two burn-rate alerts for each latency and each availability SLO, four absence alerts, one for each alert kind |
| `rules/nuna-maintenance.rules.yaml` | 10 alerts: the worker's, as worker-metrics.md designs them |
| `tests/nuna-catalog.test.yaml` | promtool unit tests of the catalog's rules (backlog 261; "How they were checked") |

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
| `nuna.audit.logged`, `nuna.audit.archive.highest` | `nuna_audit_logged`, `nuna_audit_archive_highest` |
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
| The SLOs: load p99 < 50 ms, 304 p99 < 10 ms, commit p99 < 300 ms, at least 500 commits a second | nuna catalog, "SLOs": each latency SLO's share within its bound over the time range and over 5-minute windows; each availability SLO's share not answered 5xx over the time range (backlog 261); every SLO's error-budget burn rate over the last hour; and commits a second against a line at 500 | `nuna.catalog.requests`, as catalog-metrics.md defines each SLO |
| Commits: the outcome split, and requirement failures by type | nuna catalog, "Commits": replies by outcome, requirement failures by `requirement.type`, refusals and failures by error type, commits by route, mean commit time | `nuna.catalog.commits`, `nuna.catalog.requests` |
| Loads and the share answered 304 | nuna catalog, "Loads": loads by outcome, the 304 share (3xx over 2xx and 3xx, D14), mean load time, failures by error type, the metadata cache's hit share | `nuna.catalog.requests`, `nuna.catalog.metadata.cache.lookups` |
| Credential vending | nuna catalog, "Credential vending": vends by outcome, the cache's share, mean vend time, failures by error type, STS calls and their mean time by storage profile | `nuna.catalog.credential.vends`, `nuna.sts` |
| The audit's Kafka acknowledgement time | nuna catalog, "Audit: Kafka acknowledgement": mean and p99 time and records by event, failed publishes by exception | `nuna.audit.publish` |
| A running server's audit records that did not reach the archive (backlog 272) | nuna catalog, "Audit: the archive": the records each server run logged two hours ago less the highest the archive check verified in that run, and both counts by run | `nuna.audit.logged`, `nuna.audit.archive.highest` |
| Each nuna alert kind | nuna catalog, "nuna's alerts": each kind fired in the time range, and when | the three `nuna.catalog.alerts.*` counters |
| The worker: its last successful scan, failures by kind | nuna maintenance worker: time since the last success, scans by outcome and their mean time, failures by kind, where scan time goes, the worker's calls; work done; compaction and the neediest tables | worker-metrics.md's meters |
| Client reports by engine | nuna client reports: reports by engine, planning and commit time, files, pruning, bytes, records, attempts and delete files, by engine | `nuna.catalog.report.*`, by `engine.name` |

- **The SLOs are read as shares, not quantiles.** The request histogram's only buckets are the SLO bounds, so
  `histogram_quantile` would interpolate between them and invent a p99. "p99 within 50 ms" is the same statement as
  "at least 99% within 50 ms", which the buckets give exactly, as the console's health page does (health-page.md).
- **Availability is read from the request counts.** Each availability stat is one less the 5xx answers over the 2xx,
  3xx and 5xx ones, so 4xx answers count on neither side, as in the rules. A deployment that has never answered 5xx
  has no 5xx series; the panels read that as no failures, not as no data. The burn-rate panel draws every SLO against
  its own budget (1% for latency, 0.1% for availability), so the lines the rules fire at are the same for both.
- **The audit's acknowledgement p99 is an estimate.** Its timer is exported with the console health page's ten
  buckets, 5 ms to 5 s (item 71), and the p99 panel takes `histogram_quantile(0.99, ...)` over them, beside the mean.
  Prometheus interpolates within the bucket the p99 falls in, so it can be off by that bucket's width (25 to 50 ms,
  say), and a p99 past 5 s reads 5 s. The health page names the bucket instead.
- **The archive panel looks two hours back.** The check runs hourly, so a run's newest hour or so of records always
  waits for it. The panel subtracts the highest number the check verified from what the server had logged two hours
  before: above zero, records the server logged then are still not in the archive. Every server runs the check, so
  the archive's gauge is taken as the highest of the servers'. A run that ended drops out of both.
- **Mean times elsewhere.** The other timers have no buckets in Prometheus, so their panels show means.
- **Reports by engine.** Each report records one sample of each result it carries, so the count of a result that
  every report of its type carries counts the reports: `result-data-files` for scans, `attempts` for commits. A
  report lacking it is not counted there; the `reportMetrics` panel counts every report, without the engine.
- **The neediest tables** are the one panel that names tables (D30, amended 2026-11-15): everyone who can open the
  worker's dashboard sees them.

## The alert rules

| Alert | Severity | Fires when |
|---|---|---|
| `NunaLoadSloBurnFast`, `NunaNotModifiedSloBurnFast`, `NunaCommitSloBurnFast` | page | The latency SLO's error budget burns at 14.4 times its rate over 1 h and 5 min, or 6 times over 6 h and 30 min |
| `NunaLoadSloBurnSlow`, `NunaNotModifiedSloBurnSlow`, `NunaCommitSloBurnSlow` | warning | It burns at its rate or faster over 3 days and 6 h, for 15 minutes |
| `NunaLoadAvailabilityBurnFast`, `NunaCommitAvailabilityBurnFast`, `NunaRequestAvailabilityBurnFast` | page | The availability SLO's error budget (0.1% of answers 5xx) burns at 14.4 times its rate over 1 h and 5 min, or 6 times over 6 h and 30 min |
| `NunaLoadAvailabilityBurnSlow`, `NunaCommitAvailabilityBurnSlow`, `NunaRequestAvailabilityBurnSlow` | warning | It burns at its rate or faster over 3 days and 6 h, for 15 minutes |
| `NunaCatalogGone` | page | No catalog server has pushed `nuna_catalog_alerts_metadata_integrity_total` for 10 minutes |
| `NunaCatalogSilent` | page | A deployment (`job`) that pushed within the last day has not for 10 minutes, while another still does |
| `NunaRequestSeriesMissing` | page | A deployment that has pushed for over an hour has had no `nuna_catalog_requests_milliseconds_count` for an hour |
| `NunaSloBucketMissing` | page | A latency SLO's answers arrive without the bucket at its bound, for 10 minutes |
| `NunaMetadataIntegrity` | page | `nuna.catalog.alerts.metadata.integrity` rose in the last 15 minutes |
| `NunaRegulatedSchemaChange`, `NunaLargeMetadata` | warning | Their counter rose in the last 15 minutes |
| `NunaWorkerNotScanning`, `NunaWorkerGone` | page | worker-metrics.md, "Alerts" |
| `NunaPurgesFailing`, `NunaDeletionsFailing`, `NunaMaintenanceJobFailing`, `NunaScansSlowerThanInterval`, `NunaCompactionFallingBehind`, `NunaFilesOutsideTable` | warning | worker-metrics.md, "Alerts" (a "ticket" there) |
| `NunaDrLagging`, `NunaDrVerifierNotPassing` | page | worker-metrics.md, "Alerts": a commit not whole in the DR store for 15 minutes, or no DR verifier pass for 15 minutes (D37, item 28) |

- **Burn rates.** Each latency SLO allows 1% of its answers past the bound, and each availability SLO 0.1% of its
  answers 5xx: that is its error budget. The windows and factors are the Google SRE workbook's multiwindow alerts for
  a 30-day budget: 14.4 spends 2% of the month's budget in an hour, 6 spends 5% in six hours, and 1 spends it all in
  30 days. The short window of each pair stops the alert soon after the burn stops. Fast burns page and slow ones
  warn, as the workbook has it; change the `severity` label to change that. Each SLO is per deployment (`job`), summed
  over its servers and catalogs. A window with no answers of the SLO's kind (no 304s, say), or with no 5xx, gives no
  ratio and no alert.
- **Availability** (backlog 261). The latency SLOs time answers 2xx or 3xx (R7: latencies of successful answers), so
  a 5xx is neither inside nor outside them: a catalog answering every call 500 fired none of them, and calls that slow
  until they fail as 5xx made the latency look better. The availability SLOs count answers instead. A 5xx is bad; a
  2xx or 3xx is good, a 304 too; a 4xx is neither, 409 commit conflicts included, since a client's mistake or a lost
  race is not the catalog failing. nuna's 503s count bad: they say the store or the identity provider could not be
  reached, or a record that had to be audited first was not (`ErrorResponses`, `AuditUnavailableResponses`), not that
  the catalog shed load. Its one 429, the audit read routes' per-caller limit, is on no Iceberg route and a 4xx anyway.
  99.9% is a draft target, set at work as D43's names are. The R7 latency SLOs stay as written. An outage of every
  call fires the load, the commit and the every-call pages at once.
- **Few calls make big shares.** With, say, 10 commits an hour at night, one commit answered 5xx is 10% of the hour
  and more of its 5 minutes, past the 1.44% the fast page fires at: one failure pages. The latency SLOs have the same
  exposure at 14.4%, two slow commits. The SRE workbook's remedies are synthetic traffic and grouping kinds of request
  (the commit SLOs already group `updateTable` and `commitTransaction`); Grafana's SLOs add a least number of failures.
  To settle at work, with the target.
- **Detection time: no `for` on the pages.** The fast pages have no `for` (backlog 261): the SRE workbook advises
  against it on burn-rate alerts, since the short window already asks for a sustained burn and a flapping condition
  restarts a `for`. Measured with promtool, pushes and evaluations each minute and six hours of history before the
  failure: every load slower than 50 ms pages 9 minutes after it starts, when the hour's share of slow loads passes
  14.4% (11 minutes with the `for: 2m` the rules had); every load answered 5xx pages 2 minutes after the first failure,
  the two pushes a new 5xx series needs before `rate()` reads it. In production add up to a minute for the push and one
  evaluation interval. The warnings keep `for: 15m`.
- **A page outlasts its data.** Every page rule has `keep_firing_for: 10m`: when its condition clears, it fires 10
  minutes more, then resolves. Pushed series have no `up` and no staleness marker, so when a catalog stops pushing, a
  burn-rate page's 5-minute window empties within about 4 minutes and the page would resolve, a false all-clear, before
  `NunaCatalogGone` fires at 10. With `keep_firing_for` the two overlap. The cost: a page whose cause has ended also
  resolves 10 minutes late.
- **Absence** (backlog 261). A catalog server registers its alert counters as it starts and pushes them every minute
  while it runs, busy or idle, so their absence means the server stopped, or its telemetry no longer reaches
  Prometheus. The request series cannot tell that: each begins at the first call of its kind since the server started,
  so a catalog restarted into a quiet spell has none.
  - `NunaCatalogGone`: no catalog pushes the counter for 10 minutes.
  - `NunaCatalogSilent`: one deployment of several, a `job` that pushed within the last day and not for 10 minutes.
    When none pushes, `NunaCatalogGone` pages alone.
  - `NunaRequestSeriesMissing`: a deployment that has pushed for over an hour has no request series for an hour. They
    arrive under other names (native histograms, `convert_histograms_to_nhcb`; seconds), so every SLO rule reads
    nothing. A deployment restarted into an hour with no Iceberg call fires it too; where that is normal, lengthen its
    window or make it a warning.
  - `NunaSloBucketMissing`: a latency SLO's answers arrive without the bucket at its bound, for 10 minutes. The timer's
    buckets changed (`RequestMetrics.SLO`) or were dropped (percentiles configured for it), or `le` is written
    otherwise. It compares with the SLO's own count, so a deployment that never answers 304 does not fire it.
- **Naming the deployments you expect.** `absent_over_time` gives an alert only the labels its selector sets with
  `=`, so `NunaCatalogGone` names no deployment, and `NunaCatalogSilent` forgets one after a day of silence and never
  knew one that never reported (its collector never wired, its `service.namespace` misspelt). A Prometheus that must
  always hear from given deployments names each in a rule of its own, beside these files, with the `job` the charts
  give it (`<namespace>/nuna-catalog`, "Names assumed"):

  ```yaml
  - alert: NunaCatalogDeploymentGone
    expr: absent_over_time(nuna_catalog_alerts_metadata_integrity_total{job="prod/nuna-catalog"}[10m])
    keep_firing_for: 10m
    labels:
      severity: page
  ```

  It pages with `job="prod/nuna-catalog"` however long the deployment has been gone, or if it never came.
- **Alert kinds.** Metadata integrity pages and the other two warn, as item 44 and catalog-metrics.md ("The alerts")
  have it. The counters are registered when the server starts, so the first alert is a rise from 0 and `increase`
  sees it. Each warning resolves 15 minutes after the counter last rose, and the page 25 (`keep_firing_for`); the
  `nuna.alerts` log line names the table.
- **The worker's rules** are worker-metrics.md's, unchanged, with names and severities. `600` in them is the default
  `scan-interval` in seconds, and `20m` two of them: use the deployment's own. `NunaWorkerNotScanning` waits an hour
  (`for: 1h`) so that a new deployment's first scan, which runs every table's orphan scan, can end; an hour is a guess
  to set at work. `NunaPurgesFailing` and `NunaDeletionsFailing` wait two scan intervals (`for: 20m`; D43, item 80),
  so a failure the next scan's retry clears opens no ticket. `NunaWorkerGone` fires only when no worker reports at
  all.
- **The DR rules** (item 28) fire only for a worker with a DR replica named. `NunaDrLagging`'s 15 minutes is S3
  Replication Time Control's 99.9% bound, until PLT-4 gives the replication SLA; its `for: 5m` rides out one slow
  pass.

## Not covered

- **At least 500 commits a second has no alert.** It is the M8 SLO run's throughput target under a given load. In
  production the rate is set by demand, and nuna has no metric of offered commits to compare the served ones with, so
  a burn rate on it would fire whenever traffic is light. The dashboard shows the rate against a line at 500. Item 44
  asked for burn-rate alerts on each SLO; Paul kept this one a panel with no alert (D43, amended 2026-11-20: "Panel
  only").
- **The worker's pages do not outlast their data.** Backlog 261 gave `keep_firing_for` to the catalog's page rules
  only. `NunaDrLagging` reads a gauge, so a worker that stops pushing lets it resolve once Prometheus' 5-minute
  lookback passes, about 10 minutes before `NunaWorkerGone` pages (inferred from the rules).

## How they were checked

At home, with no Grafana, Prometheus or promtool (2026-11-19, item 44's status entry; the counts are as of item 71's
p99 panel, checked the same way):

- Every JSON file parses, every panel and variable query uses the `datasource` variable, and panel ids are unique.
- Every YAML file parses (PyYAML) as a rule file: a `groups:` list of rules with `alert`, `expr`, `labels` and
  `annotations`, each with `severity` `page` or `warning`. Eleven of the 17 have a `for`, each a valid Prometheus
  duration. The other six fire on the first evaluation that finds their condition (corrected by item 74, which found
  eight; item 80 gave `NunaPurgesFailing` and `NunaDeletionsFailing` a `for` of two scan intervals):
  - `NunaMetadataIntegrity`, `NunaRegulatedSchemaChange` and `NunaLargeMetadata`. Each fires on a single event,
    which its `increase(...[15m]) > 0` holds for 15 minutes: a `for` would only delay it, and one of 15 minutes or
    more could keep a lone event from firing at all.
  - The worker's `NunaWorkerGone`, `NunaScansSlowerThanInterval` and `NunaFilesOutsideTable`. Their expressions
    already look back over a window: 15 minutes with no sample, an hour's mean, or a day's count.
- Brackets balance in every one of the 100 expressions; PromQL itself was not parsed.
- Every series name in an expression maps back, by the translation above, to a meter named in these docs and in the
  main code, with the unit its type gives (a timer `_milliseconds`, a counter `_total`); every label maps back to a
  documented tag. All 56 series and 17 labels did. A misspelt name and a wrong label put in by hand were both caught.

Item 28 added `NunaDrLagging` and `NunaDrVerifierNotPassing` (2026-11-20), 19 rules in all: both parse as rule
files with a `severity` of `page`, `NunaDrLagging` has a `for: 5m`, and their two series map back to the DR
verifier's meters in worker-metrics.md.

Not checked at home before 2026-11-25: that Grafana imports the files, and that Prometheus loads the rules (both
now do, in the demo, below). Still not checked: that the translation above is what work's Prometheus does (item 43).
Item 44 is done at work when the dashboards show live data and each alert has fired once in a test.

Backlog 261 (2026-11-25) put promtool to work. `AlertRulesTest` in `catalog-server`, tagged heavy so the heavy build
and the overnight run run it, starts the Prometheus image the demo runs (`prometheus.image`, prom/prometheus:v3.16.0)
and runs in it:

- `promtool check rules` on both rule files: 19 and 10 rules, every expression parsed.
- `promtool test rules` on `tests/nuna-catalog.test.yaml`, eight cases. Every load answered 503 pages 2 minutes after
  the first failure and still at 15, while the latency page stays quiet. 409 conflicts on half the commits, and loads
  answered 404, page nothing. The only catalog falling silent pages at 10 minutes, `NunaCatalogGone` alone; one
  deployment of two falling silent pages `NunaCatalogSilent` for it. A page whose series stop keeps firing until
  `NunaCatalogGone` has taken over, then resolves. Loads with no 50 ms bucket page `NunaSloBucketMissing`, while a
  commit bucket written `300.0` does not. Request series that stop while the server reports page after an hour, and a
  new catalog with no calls yet waits that hour.

Run them with `./gradlew :catalog-server:test --tests AlertRulesTest -Pheavy`, or the `docker run` line at the top of
the test file. Two bites in `bites/OBS-261/` break what the tests guard: one drops `keep_firing_for` from a page, one
counts 4xx answers as failures. Note that promtool compares annotations exactly, so a test's `exp_annotations` carry
each description as it renders, `$value` included: a changed description changes its test.

The availability panels' queries were evaluated by promtool against test series too, with `$job` and `$catalog` as
`.*` (not kept): with no 5xx series the stats read 100% and the burn rates 0; with one load in a hundred answered
5xx beside 404s, loads read 99%, every call 1 less 1/110 (the 404s left out), and the load burn rate 10.

Not checked: the 261 rules against the demo's live series (item 233's switch `metrics`).

## In the demo

Backlog 233 (2026-11-25) runs the files in the demo (demo/README.md, "Metrics and traces"): Prometheus 3.16.0 with
its OTLP receiver and `translation_strategy: UnderscoreEscapingWithSuffixes`, fed by an OpenTelemetry collector
0.166.0, and Grafana 13.3.2 with the three dashboards provisioned and the rules loaded. Each panel's query ran
against the live series, with `$job`, `$catalog` and `$engine` as `.*`, `$__range` an hour and `$__rate_interval`
5 minutes:

- Every series name and label above landed as assumed: `job` is `nuna-demo/nuna-catalog` and
  `nuna-demo/nuna-maintenance`, the labels carry underscores, timers are `_milliseconds_*`, and the SLO buckets are
  `le` 10, 50 and 300. Grafana loaded the three dashboards, and Prometheus all 19 rules, healthy. No file changed.
- Panels with data: all but those below.
- Empty until something fails: failed loads, refused, unknown and failed commits, requirement failures, failed
  vends, failed scans and jobs. Three loads of a missing table showed in "Failed loads" as `4xx`
  `NoSuchTableException`.
- Not produced in the demo: the audit's Kafka acknowledgement row (`nuna_audit_publish_milliseconds_*`): the demo's
  catalog audits to its log, not to Kafka (D23, amended 2026-11-23). The client reports' records removed and delete
  files added (`removed_records`, `added_positional_delete_files`, `added_equality_delete_files`, `added_dvs`): the
  demo's engines only append, and Iceberg leaves a result out of a report when nothing happened. The archive's
  `nuna_audit_archive_highest`: the demo configures no audit archive, so its check never runs; `nuna_audit_logged`
  arrives (checked after the rebuild at c173aa42), so "Records logged and archived" draws the logged line alone,
  and "still not archived" stays empty.
- Micrometer's `.max` gauges arrive too, as item 43 expected: `nuna_catalog_requests_max_milliseconds`,
  `nuna_store_max_milliseconds` and the like. No panel reads them.
- The six SLO burn-rate alerts fired in the demo: 75% of loads answered 200 within 50 ms in the half hour checked,
  against 99%. They were right: Windows was paging, with about 700 MB free. The rules fire as written.
