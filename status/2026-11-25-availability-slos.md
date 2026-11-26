# 2026-11-25, availability SLOs, absence alerts and promtool tests for the catalog's rules (backlog 261)

`deploy/grafana/rules/nuna-catalog.rules.yaml` gains two groups. `nuna-catalog-availability`: a fast page and a slow
warning each for loads, commits and every Iceberg call, at a draft 99.9% not answered 5xx (4xx, 409s included, left
out), on the latency SLOs' windows. `nuna-catalog-absence`: `NunaCatalogGone` and `NunaCatalogSilent` on the alert
counter every server pushes from its start (the request series begin only at a first call, so their absence is no sign
of a dead server), `NunaRequestSeriesMissing` and `NunaSloBucketMissing` for request series renamed, converted or
without the SLO's bucket. Every page rule has `keep_firing_for: 10m`; the latency pages lose `for: 2m`. The catalog
dashboard's SLOs row has a stat per availability SLO and their burn rates beside the latency ones. 19 catalog rules,
36 catalog panels.

Checked: `deploy/grafana/tests/nuna-catalog.test.yaml`, eight promtool cases, passes in prom/prometheus:v3.16.0, and
`promtool check rules` loads both rule files (19 and 10 rules). `AlertRulesTest` (catalog-server, heavy) runs both:
`./gradlew :catalog-server:test --tests AlertRulesTest -Pheavy`, 2 tests, 0 failures. Two bites recorded in
`bites/OBS-261/` and run through Gradle with `-Pbites`: without `keep_firing_for` the load page resolves at 385m, 10
minutes after its series stop, and the case fails (`got:[]`); counting 4xx as failures makes the 409 case page. Each
was restored from the commit. Detection measured with promtool (one-minute pushes and evaluations, six hours of
history): a total latency failure pages after 9 minutes (11 with `for: 2m`), every load answered 5xx after 2. The new
dashboard queries were evaluated by promtool against test series (not kept). Fast build green.

Not covered: the rules against the demo's live series (the demo is down), and `keep_firing_for` on the worker's page
rules.
