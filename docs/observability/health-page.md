# The console's health page

The console's health dashboard (D38, backlog 48): catalog health for the whole deployment, for platform operators;
the tables that need compaction, for anyone, each shown only to those who may read it; and on each table's page, its
health. The page is `health.js` in `catalog-server/src/main/resources/nuna/ui/` (the **Health** button in the top
bar, `#p=health`); the server side is `catalog-server`, package `nuna.catalog.server.health`.

Prometheus, Grafana and the alert rules (catalog-metrics.md, worker-metrics.md) stay the place for paging and long
history. The page answers "how does the catalog stand today" without them, from the catalog itself.

## Who sees what

| Part | Shown to | Read from |
|---|---|---|
| Catalog health | Platform operators (`nuna.security.platform-operator-groups`, SEC-1) | `GET /management/v1/health/catalog` and `GET /management/v1/maintenance/scans` |
| Tables that need compaction | Anyone; each table only to a caller with `select` on it | `GET /management/v1/maintenance/tables` (table-health.md) |
| A table's health, its **Health** tab | Anyone with `select` on the table | `GET .../tables/{table}/health` (table-health.md) |

Anyone else gets 403 `ForbiddenException`, audited like every refusal on a management route, and the page shows the
refusal as the catalog answered it. A non-operator sees "Catalog health is shown to platform operators only" and no
figure.

## Catalog health

Over the last day, for the whole deployment:

- **Alerts fired**, by kind: metadata integrity, regulated schema change, large metadata (catalog-metrics.md, "The
  alerts").
- **The audit's Kafka acknowledgement**: records acknowledged, their mean time and p99, and publishes that failed
  (`nuna.audit.publish`, spans.md). A failed publish refused the request it audited (D23).
- **Credential vending**: vends issued, served from the cache, declined by the caller, and failed, with the
  failures by error type (`nuna.catalog.credential.vends`).
- **Latency against the SLOs**: loads answered 200 (p99 under 50 ms), loads answered 304 (under 10 ms) and commits,
  `updateTable` and `commitTransaction`, answered 200 (under 300 ms), from `nuna.catalog.requests`. Each row gives the
  count, how many finished within the objective, and the verdict: met when at least 99 in 100 did, which is the p99
  being within it. The p99 column says which histogram bucket the p99 falls in (`≤ 10 ms`, `≤ 50 ms`, `≤ 300 ms`, or
  `> 300 ms`). A row with no request in the window has no verdict.
- **The maintenance worker**, from the scans the catalog keeps (table-health.md): when the last successful scan ended,
  the scans and failed scans started in the last day, the failed jobs of those scans by kind, the latest failed scan's
  error, and the ten latest scans. These are in the database, so every server answers them alike.
- **The servers** the figures came from, and any that did not answer, with why.

"The last day" is each server's own: the day up to now, or since the server started if that is later. A restarted
server starts counting again from zero; the figures of a server that has gone are gone with it.

## Gathered from every server

Each server holds only its own meters, so the server the browser reaches asks the others.

1. **A registry that keeps everything.** `HealthMeters` is a meter registry bean of its own. Boot records every meter
   in it too: beside the OTLP registry, through a composite, when metrics are exported, or alone when nothing exports
   (Boot's own fallback registry steps aside for it). Whatever the exporting registry does, this one keeps every count,
   sum and histogram bucket since the server started and never rolls a histogram over. (Boot's fallback,
   `SimpleMeterRegistry`, forgets a histogram's buckets within minutes.) The audit publish timer gets histogram buckets
   here (5 ms to 5 s), for the page's p99; since item 71 the exported timer has the same buckets (spans.md), and
   since item 74 both take them from one list, `ObservedPublisher.BUCKETS`. The registry gives them through a filter
   of its own, so it has them without Boot too.
2. **A reading each minute.** `HealthWindow` reads the registry every minute into flat counts that only grow, such as
   `load`, `load.le.50` or `alerts.metadata-integrity` (`Figures`). The last day's figures are now's reading less the
   newest reading a day old or older, so the window is one day and at most a minute more. About 1,440 readings are
   kept.
3. **Asking the others.** `GET /management/v1/health/server` answers this server's own counts over the last day.
   `GET /management/v1/health/catalog` adds every other server's to this one's. The others are found by DNS:
   `nuna.health.peers` lists `host:port` entries, and each host is resolved to every address it has and each address
   asked, all at once. The Helm chart sets it to its headless Service, `<release>-nuna-catalog-peers.<namespace>.svc:8181`
   (`templates/service-peers.yaml`), whose name resolves to the address of every ready server. The servers mount no
   service account token and call no Kubernetes API. A server reached at two addresses, or this server reached through
   the Service, is counted once, by the random id each server takes when it starts.
4. **The operator's own token.** Each request to another server carries the caller's own `Authorization` header, which
   that server checks as it checks any caller's. The server asking has already found the caller to be an operator, so
   no one else's token ever leaves it. No new secret or credential is needed between servers.
5. **A server that does not answer** within `nuna.health.peer-timeout` (3 s by default), refuses, or answers something
   other than a server's health is listed with why ("could not connect", "no answer in time", "answered 403
   ForbiddenException"), and the page says its figures are missing. Nothing is guessed for it.

| Setting | Default | Meaning |
|---|---|---|
| `nuna.health.peers` | empty: this server alone | `host:port` entries; the chart sets its headless Service (`NUNA_HEALTH_PEERS`) |
| `nuna.health.peer-timeout` | `3s` | How long another server has to connect and answer |

The answers, for anyone scripting against them (platform operators only):

```json
GET /management/v1/health/server
{"server": {"id": "…", "name": "nuna-catalog-6c9d…", "started-at": "…"}, "from": "…", "to": "…",
 "figures": {"alerts.metadata-integrity": 0, "load": 1203, "load.le.10": 1100, "load.le.50": 1199, …}}

GET /management/v1/health/catalog
{"alerts": {"metadata-integrity": 0, "regulated-schema-change": 1, "large-metadata": 0},
 "audit": {"acknowledged": 5120, "failed": 0, "mean-ms": 4.2, "p99": {"at-most-ms": 25}},
 "credential-vends": {"issued": 310, "cached": 2900, "none": 40, "failed": 2,
                      "failures": {"ServiceUnavailableException": 2}},
 "slos": {"load": {"objective-ms": 50, "count": 3609, "within": 3600, "met": true, "p99": {"at-most-ms": 50}},
          "not-modified": {…}, "commit": {…}},
 "to": "…", "answered": 3, "silent": 0,
 "servers": [{"id": "…", "name": "…", "answered": true, "this-server": true, "started-at": "…", "from": "…"}, …]}
```

## Tables that need compaction, and a table's health

Beneath catalog health, the neediest tables as the worker last assessed them (item 47's read route), neediest first,
50 at a time with **More** for the next page; only tables the viewer may `select` are listed. Each names its catalog,
namespace and table, why it needs compaction, its score and file counts, when it was assessed and how its last
compaction went, and links to its table's **Health** tab.

The **Health** tab of a table's page shows whether it needs compaction and why (each reason explained), the latest
assessment's counts, and the last compaction's outcome, start and end; or that the worker has not assessed it yet, or
that no compaction has run for it.

## Tests and bites

| Test | Shows |
|---|---|
| `HealthWindowTest` | The registry keeps every histogram bucket where `SimpleMeterRegistry` lets them go; the audit publish timer has the exported timer's buckets there, without Boot, where a plain registry gives it none; the last day is now's reading less the newest a day old, or since the start; the figures judged against the SLOs; vends by outcome and failures by type. |
| `PeersTest` | Every address is asked with the caller's token; a peer that cannot be reached, does not resolve, refuses, or answers something else gives no figures and says why. |
| `ConsoleHealthTest` (Selenium, P8) | In Chrome, against a server with another server standing in beside it and a third that does not answer: an operator sees each figure as the two servers' sum, the silent server named, and the worker's scans; a reader sees no catalog health and only the tables they may read, neediest first, each linking to its health (assessment, reasons, last compaction), and a table they may not read refused; someone with no grant sees neither, and their token never reaches another server. |
| `ConsoleTest` | `health.js` is served under the console's Content-Security-Policy and writes no markup. |

The bites are in `bites/D38/`: `catalog-health-for-anyone`, `server-health-for-anyone`, `one-server-only`,
`window-never-moves`, `histograms-roll-over` and `table-health-for-anyone`; and in `bites/OBS-71/`,
`health-page-buckets-of-its-own` (item 74).
