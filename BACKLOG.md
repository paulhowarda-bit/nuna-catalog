# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**26 open:** 9 S, 9 M, 4 L, 4 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42) | 2026-11-14 |
| [4](backlog/4.md) | M | Apply the AWS S3 and MinIO delete policies to a real store, and run the worker on AWS | three policies applied to real stores and the worker run on AWS; no new code expected | 2026-11-14 |
| [10](backlog/10.md) | L | DR reconcile: prove the accepted tips from the audit archive | read the audit archive, a new reconcile option, and the DR drill updated | 2026-11-14 |
| [17](backlog/17.md) | M | Console sign-in against Entra ID and Ping, with an automated test | checks against Entra ID and Ping at work; ending the IdP session if Paul wants it | 2026-11-14 |
| [19](backlog/19.md) | S | Cap the audit-read route for scripted use | a per-caller limit on one route, answering 429, with a test | 2026-11-14 |
| [21](backlog/21.md) | M | A CA bundle per storage profile | a trust store per profile, and a test store with a private CA | 2026-11-14 |
| [22](backlog/22.md) | XL | Administer storage profiles and catalogs at runtime (not now, D41) | routes, storage on four backends, audit and the startup checks; only if Paul wants it | 2026-11-14 |
| [23](backlog/23.md) | M | Per-request SSE-KMS keys (waits on SEC-8) | one KMS key per storage profile in vended sessions and their policy, tested on AWS; per-domain keys wait on SEC-8 | 2026-11-14 |
| [24](backlog/24.md) | L | Kubernetes deployment: what was not checked (at work) | five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing | 2026-11-14 |
| [25](backlog/25.md) | M | One hot table under contention: throughput and fairness (measure at work) | a measuring run on work hardware; a change only if Paul wants one | 2026-11-14 |
| [26](backlog/26.md) | XL | Branches, tags and merge (v1.x, D33) | a v1.x milestone | 2026-11-14 |
| [27](backlog/27.md) | XL | Queryable audit tables (v1.x, D23) | a v1.x milestone; scope to settle with Paul | 2026-11-14 |
| [28](backlog/28.md) | L | DR replication-watermark verifier (v1.x) | a v1.x milestone; scope to settle with Paul | 2026-11-14 |
| [29](backlog/29.md) | XL | M9: carry nuna to work (at work) | the whole install checklist, at work | 2026-11-14 |
| [43](backlog/43.md) | L | Hook nuna into work's OpenTelemetry collector and Prometheus (at work) | findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval | 2026-11-15 |
| [44](backlog/44.md) | M | Grafana dashboards and Prometheus alert rules for nuna (at work) | at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43 | 2026-11-15 |
| [55](backlog/55.md) | S | The nightly lost two tests to one 15 s stall reaching Docker's containers | waits on an overnight run in which both tests pass | 2026-11-16 |
| [60](backlog/60.md) | M | CredentialExpiryTest's first request answered 401 | the cause is unknown: the test records no reason for the 401 | 2026-11-18 |
| [68](backlog/68.md) | M | Catalog names still leak through /v1/config and the unknown-prefix 404 (Paul decides, D18) | four lookups answer before any guard, each with a test; after Paul decides | 2026-11-19 |
| [69](backlog/69.md) | S | The DR reconcile's apply waits within the retry budget when the store is down, as the commit engine does | one call in Reconcile, its construction in one route, and a test with a store double | 2026-11-19 |
| [70](backlog/70.md) | M | A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After | two heavy Spring tests in one module, each needing its database behind a link it can cut | 2026-11-19 |
| [72](backlog/72.md) | S | Oracle's self-check does not notice a journal-partition job that cannot run | one more query in one store's self-check and a heavy test; waits on Paul's answer below | 2026-11-19 |
| [75](backlog/75.md) | S | deploy/README.md and install-at-work.md still call the ETag routing choice open | two paragraphs of Markdown; no wait | 2026-11-20 |
| [76](backlog/76.md) | S | The overnight load tests expect every conditional load to be a 304, but the shared epoch changes every window | two loops in two overnight tests; no wait. Before the first overnight run after item 15 lands, or that run may fail on it | 2026-11-20 |
| [77](backlog/77.md) | S | rest/Guard keeps its own copy of the rule security/Holdings now holds; TelemetryTest and table-health.md still say an outsider gets 403 on the management API | two prepared patches to apply and a fast build; waits on items 15 and 71 releasing `rest/` and `telemetry/` | 2026-11-20 |
| [78](backlog/78.md) | S | Paul decides: whether the DR routes and the routes by object id hide names and ids from a caller that holds nothing | a guard change on six routes with tests, once Paul decides; waits on Paul | 2026-11-20 |

## Open items in full

### 1. Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks)

- Decided: 2026-11-19, D42
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42)

Paul's plan (2026-11-13, CLAUDE.md): after the install at work he runs `./heavy.sh` in a worktree of its own:
`-Pheavy` on all four backends, the Ceph leg, then every bite. Don't start it unless he asks.

Never run yet (`build-plan.md` Status, 2026-11-12 and 2026-11-13; `docs/install-at-work.md`, "Where things stand"):
- the `heavy` tests added in phases 1 and 2: `KubernetesCompactionTest`, `DeletePolicyCephTest`, `OrphanRaceTest`,
  `KillMidPurgeTest`, `KafkaDownTest`, and the store races `commitRacingTheUnregisterExactlyOneApplies` and
  `lostReviveAnswerResolvesToCommitted`;
- on CockroachDB, Oracle and MongoDB: unregister, revive, the flag and moved-pointer checks, the purge claim and
  completion, pending deletions and their dedup, the active-object scan, and their schema steps;
- the Ceph leg, including whether RGW honours the `s3:prefix` condition of the folded delete policy;
- about 70 of the 200 recorded bites.

Done when `./heavy.sh` is green, every bite bites on its assertion (read the logs: see item 3), and the run is
recorded in `build-plan.md`'s Status. Anything that fails gets an item of its own.

**Decided 2026-11-19 (D42):** the overnight run covers this pass ("Overnight run covers it"): it runs the heavy build, the Ceph leg and the bites every night that code changed. This item closes the first night all three are green; there is no pass of its own.

### 4. Apply the AWS S3 and MinIO delete policies to a real store, and run the worker on AWS

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, three policies applied to real stores and the worker run on AWS; no new code expected

`build-plan.md`, follow-ups of 2026-11-13: the AWS bucket policy and the MinIO worker policy from
`GET /management/v1/maintenance/delete-policies` have never been applied to a real store, and no pass runs the
maintenance worker on AWS. Also never checked (review fixes of 2026-11-13): whether Ceph lets a bucket policy refuse
its owner a policy change.

Done when each policy has been applied as the catalog gives it (AWS: the `nuna-sandbox` account; MinIO: a store
that enforces user policies), the worker deletes and lists beneath its prefixes and nobody else can, and the Ceph
question has an answer, each recorded in `build-plan.md`.

### 10. DR reconcile: prove the accepted tips from the audit archive

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: L, read the audit archive, a new reconcile option, and the DR drill updated

`build-plan.md`, M8 follow-ups: the `accept` list of `POST /management/v1/catalogs/{catalog}/dr-reconcile` is
assembled by hand. The audit archive could prove which tips were acknowledged.

Done when the operator can have the archive supply or check the accepted tips, and the DR drill uses it.

### 17. Console sign-in against Entra ID and Ping, with an automated test

- Decided: 2026-11-19, D16
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, checks against Entra ID and Ping at work; ending the IdP session if Paul wants it

`build-plan.md`, UI v1, Open: the PKCE flow works against Keycloak, checked by hand on 2026-11-12. It is not tested
against Entra ID or Ping (work), no automated test covers it, the IdP's token endpoint must allow CORS from the
console's origin, and "Sign out" does not end the IdP's session.

Done when an automated test signs in through the PKCE flow (Keycloak at home), the flow is checked against Entra ID
and Ping at work, and sign-out ends the IdP session or Paul accepts that it does not.

Progress (2026-11-15, worktree-agent-af24fce4021e55db3): `ConsoleSignInTest` (catalog-server) signs in through the
real authorization code flow with PKCE, from Chrome in a container against Keycloak 26.8.2 in a container. The person
types the password on Keycloak's own page, and the console trades the code and its verifier for a token at the token
endpoint, across origins (CORS). The catalog accepts the token: its catalog overview and tree load. The code and state
leave the address bar, the token is kept in `sessionStorage` only, and "Sign out" clears it. The realm's client
requires S256: a request without PKCE is refused by Keycloak, and the console shows the refusal (bite
`bites/UI/console-sign-in-without-pkce.patch`, which bites). An answer whose state this tab did not send is refused
before any code is traded (bite `bites/UI/console-sign-in-ignores-state.patch`, which bites). The fast build passed
with the test in it on 2026-11-15. Still open: the flow against Entra ID and Ping (work), and "Sign out" not ending the
IdP's session (Paul's call).

**Decided 2026-11-19 (D16):** "Sign out" also ends the identity provider's session ("End the IdP session"). The checks against Entra ID and Ping still happen at work.

### 19. Cap the audit-read route for scripted use

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, a per-caller limit on one route, answering 429, with a test

`build-plan.md`, UI v1, Open: each `GET .../audit` read opens a short-lived Kafka consumer. That is fine at console
rates; it needs a cap if scripts start using it.

Done when the route limits how often one caller (or all callers) may read, answering 429 beyond it, with a test.

### 21. A CA bundle per storage profile

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, a trust store per profile, and a test store with a private CA

`build-plan.md`, M6, Open: the JVM trust store serves every storage profile today. An on-prem store with its own CA
needs it added to the JVM's.

Done when a profile can name its own CA bundle, and a test against a store with a private CA passes.

### 22. Administer storage profiles and catalogs at runtime (not now, D41)

- Decided: 2026-11-19, D41
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, routes, storage on four backends, audit and the startup checks; only if Paul wants it

`build-plan.md`, M6, Open: profiles and catalogs come only from startup declarations
(`nuna.storage.profiles`, `nuna.storage.catalogs`); nothing changes them while the server runs.

Done when Paul has said whether runtime administration is wanted. If it is: a management route, for platform
operators, audited, with the same overlap and TLS checks as startup.

**Decided 2026-11-19 (D41):** left open and not to be built now ("lets leave it open and document it as a backlog item we will not do right now"). What it would change: storage profiles (store kind, endpoint, region, table root, allowed prefixes, STS role or static keys, the maintenance worker's identity), new catalogs (profile, KMS key set and legal entity, fixed once a catalog exists) and the vending settings are read at startup, so adding a catalog or a store, or rotating a MinIO or Ceph static key, takes a config change and a rolling restart. Nessie works the same way by default; its hidden stored mode, which only its tests can update, is described in D41.

### 23. Per-request SSE-KMS keys (waits on SEC-8)

- Decided: 2026-11-19, D26
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, one KMS key per storage profile in vended sessions and their policy, tested on AWS; per-domain keys wait on SEC-8

`build-plan.md`, M6, Open: per-request SSE-KMS keys and a KMS condition in the session policy. Today encryption at
rest is the bucket's default key and the catalog records only the key set's name (D26). Waits on SEC-8 (KMS
key-policy owners per domain), a work question in section 6.

Done when SEC-8 is answered and, if keys are per domain, vended sessions write with the catalog's key and the session
policy refuses any other.

**Decided 2026-11-19 (D26):** one KMS key per storage profile now ("Per-profile key now"): vended sessions must write with it and are refused any other. How keys map to domains still waits on SEC-8.

### 24. Kubernetes deployment: what was not checked (at work)

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: L, five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing

`build-plan.md`, Kubernetes deployment of 2026-11-11, "Not checked": an Ingress, IRSA or Pod Identity on EKS,
OpenShift, more than one node or zone, and resource sizes under load.

Done when each is checked where work runs the catalog, or Paul rules it out, with what was found in
`deploy/README.md`.

### 25. One hot table under contention: throughput and fairness (measure at work)

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, a measuring run on work hardware; a change only if Paul wants one

`build-plan.md`, M8 finding: one hot table manages about 4 to 5 commits a second under contention on the laptop,
and at 32 writers p99 time to acknowledgement passes 2 minutes. The server's own retries (P10) each write a
metadata file before losing the swap. Not a correctness problem; work hardware should measure it.

Done when it is measured on work hardware and Paul has decided whether the numbers need a change.

### 26. Branches, tags and merge (v1.x, D33)

- Decided: 2026-11-19, D33
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, a v1.x milestone

`build-plan.md`, v1.x milestones: branches, tags and merge over the v1 storage. The REST prefix selects the branch
(`trading@dev`), Nessie merge semantics per table, cleanup protects every ref, defaults from P11 (D33). Not started.

Done when the milestone's scope is built and its gate is green, as for the other v1.x phases.

**Decided 2026-11-19 (D33):** built next ("Build it next"), after the items the backlog review of 2026-11-19 chose to fix, not left for v1.x.

### 27. Queryable audit tables (v1.x, D23)

- Decided: 2026-11-19, D23
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, a v1.x milestone; scope to settle with Paul

`build-plan.md`, v1.x milestones: queryable audit tables (D23). Not started. The console's audit trail and the SIEM
cover the audit until then (UI v1).

Done when the milestone's scope is settled with Paul, built, and its gate is green.

**Decided 2026-11-19 (D23):** built next ("Build it next"), not left for v1.x; the scope is settled with Paul first.

### 28. DR replication-watermark verifier (v1.x)

- Decided: 2026-11-19, D37
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: L, a v1.x milestone; scope to settle with Paul

`build-plan.md`, v1.x milestones: a DR replication-watermark verifier. Not started.

Done when the milestone's scope is settled with Paul, built, and its gate is green.

**Decided 2026-11-19 (D37):** built next ("Build it next"), not left for v1.x; the scope is settled with Paul first.

### 29. M9: carry nuna to work (at work)

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, the whole install checklist, at work

`build-plan.md`, M9, and `docs/install-at-work.md`, which is the checklist: approved artifacts (PLT-6), RAC and
Exadata, real IdPs and object stores, a DBA review of the self-check thresholds (PLT-2), bucket naming and the
replication SLA (PLT-4), trusted engines (SEC-2, SEC-5), token exchange and workload identity (SEC-1), on-prem
MinIO and Ceph (PLT-3), and the overnight job in work CI running `./nightly.sh`.

Done when `docs/install-at-work.md` is carried out at work and M9's entry in `build-plan.md`'s Status says what
passed. Items for anything that fails.

### 43. Hook nuna into work's OpenTelemetry collector and Prometheus (at work)

- Added: 2026-11-15 by Iceberg observability review, at Paul's request
- Touches: deploy/ docs/observability/
- Effort: L, findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval

After 38, at work, and before the dashboards (44):
- Find how apps reach the collector: OTLP over gRPC or HTTP, the endpoint, the TLS CA, headers or auth. Set them in
  the Helm values 38 adds.
- Find whether metrics reach Prometheus from the collector (remote write or a collector exporter) or whether
  Prometheus scrapes pods. Nuna pushes OTLP. If work only scrapes, add `micrometer-registry-prometheus` and Actuator's
  `prometheus` endpoint instead, on a management port that is not in the clients' Service, behind a NetworkPolicy.
- Find whether the platform injects the OpenTelemetry Java agent into pods (the OTel Operator's
  `instrumentation.opentelemetry.io/inject-java` annotation, or a namespace default). If it does, nuna would be
  instrumented twice: keep the agent out of nuna's pods.
- Check how OTLP metric names land in Prometheus there (unit and `_total` suffixes, dots to underscores), and write
  the mapping into `docs/observability/`.
- Get the new artifacts (38's list in `docs/install-at-work.md`) approved in the mirror (PLT-6).
- Check whether engines carry trace context to the catalog. `iceberg-spark-runtime` is believed to relocate Apache
  HttpClient; if so, the OpenTelemetry agent in Spark adds no `traceparent`, and traces start at the catalog. Not
  verified at home.

Done when a load from a work engine shows as a trace and as metrics in work's Grafana, and the findings above are in
`docs/observability/`.

### 44. Grafana dashboards and Prometheus alert rules for nuna (at work)

- Decided: 2026-11-19, D43
- Added: 2026-11-15 by Iceberg observability review, at Paul's request
- Touches: deploy/grafana/ docs/observability/
- Effort: M, at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43

After 39, 40, 41 and 43, written against the series as they land in work's Prometheus (43 records the names).
Dashboards go in `deploy/grafana/` as JSON, and alert rules in the form work uses (PrometheusRule or plain rule
files):
- The SLOs (build plan R7, M8): load p99 < 50 ms, 304 p99 < 10 ms, commit p99 < 300 ms, at least 500 commits/s, with
  burn-rate alerts on each.
- Commits: the outcome split, and requirement failures by type (contention; item 25).
- Loads: the share answered 304 (D14; item 15 says why it is low behind a non-sticky balancer).
- Credential vending, and the audit's Kafka acknowledgement time.
- Each nuna alert kind: metadata integrity pages, the others warn.
- The worker: the last successful scan (alert after three missed intervals) and failures by kind.
- Client reports by engine.

Done when the dashboards show live data at work and each alert has fired once in a test.

**Decided 2026-11-19 (D43):** drafted at home now ("Draft at home now"), against nuna's own metric names and Prometheus' usual OTLP mapping, instead of waiting for item 43; queries may need renaming at work.

Progress (2026-11-19, worktree-agent-a393cd9f05a19bc08): drafted at home, as D43 says. Three dashboards in
`deploy/grafana/dashboards/` (the catalog, 30 panels; the worker, 16; client reports by engine, 13), each with a
`datasource` variable, and two plain rule files in `deploy/grafana/rules/` (17 alerts, each with `severity` `page` or
`warning`), which a PrometheusRule's `spec.groups` takes as they are. docs/observability/dashboards-and-alerts.md says
what each shows, the 55 series names they assume and what to rename if work's Prometheus differs. Every area above is
covered except two:
- At least 500 commits a second has a dashboard panel (the rate against a line at 500) but no burn-rate alert: it is
  the M8 SLO run's target under a given load, production demand sets the rate, and nuna has no metric of offered
  commits to burn against. Whether it gets an alert, and on what, is Paul's to decide.
- The audit's Kafka acknowledgement time is shown as a mean only: the exported timer has no buckets, so there is no
  p99 in Prometheus. Item 71.

Checked at home: every JSON and YAML file parses (Python 3.12, PyYAML 6.0.3), brackets balance in all 99 expressions,
and every series and label maps back to a meter and tag named in docs/observability/ and the main code (55 of 55, 17
of 17). Not checked: no Grafana, Prometheus or promtool here, so no import, no rule load, no PromQL parse, and the
OTLP translation itself is assumed.

Still open, at work: item 43's names (rename the queries where they differ), the dashboards showing live data, and
each alert fired once in a test. The item stays open for those, which are its done condition.

### 55. The nightly lost two tests to one 15 s stall reaching Docker's containers

- Decided: 2026-11-19, D31
- Added: 2026-11-16 by overnight run 2026-11-16
- Touches: catalog-store-api/src/test/java/nuna/catalog/store/suite/CommitSuite.java, catalog-tests-load/src/test/java/nuna/catalog/load/ConcurrentWritersTest.java
- Effort: S, waits on an overnight run in which both tests pass

The Nightly leg of the overnight run of 2026-11-16 (testing 30bab53) failed two tests, each on one request that went
unanswered between about 05:45:57 and 05:46:13 UTC, while `catalog-tests-load` started `ConcurrentWritersTest`'s stack
(two PostgreSQL, Kafka, the S3 store, two server JVMs) beside `catalog-store-sql-raft`'s CockroachDB cluster:

- `CockroachStoreSuiteTest` "1 CAS" (1,000 rounds, 339 s in): `StoreUnavailableException: commit … could not connect`,
  caused by `PSQLException: The connection attempt failed` and `SocketTimeoutException: Read timed out`: one
  connection through the link waited out its 15 s socket timeout, ending about 05:46:12.
- `ConcurrentWritersTest` "8 writers, format v2": `[5xx answers]` held two `503 GET …/tables/hot` "Storage
  credentials cannot be issued now". The server started last, `writers-b`, logged "Bootstrap broker localhost:…
  disconnected" at 05:45:59, then failed two vends at 05:46:08 and 05:46:13: "the credential issue could not be
  audited", caused by "Topic nuna.catalog.audit not present in metadata after 5000 ms". The other server's producer
  had the topic's metadata; 16 and 32 writers, and format v3, passed.

Docker Desktop's VM logs (`dockerd.log`, `containerd.log`, `init.log.0`) and Windows' System and Application logs
show nothing between 05:45:30 and 05:46:30 to explain the stall. Logs:
`C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-16\nightly.log`, `nightly-results/`, and the server logs in
`C:\Users\paulh\code\nuna-catalog\.overnight\clone\catalog-tests-load\build\m8\logs\writers-*.log` (until the next run).

Neither failure is in the code under test: the store's answer is its contract (`StoreUnavailableException`: nothing
applied, the caller repeats), and a vend that cannot be audited fails closed (D23). Each test failed because it had
no room for one transient.

Done looks like: both tests pass in an overnight run.

Fixed on 2026-11-16, not yet run; open until both pass in an overnight run:

- `CommitSuite#exactlyOneWriterWinsEachRound` commits a writer's batch again, after 1 s, when the store answers
  `StoreUnavailableException` (nothing applied: the contract says the caller repeats), up to three tries; a third such
  answer still fails the round. It prints how many commits it repeated, so the overnight log shows any.
- `ConcurrentWritersTest` warms each server up before the first case: it creates `load.warm-up.t` and loads it through
  each server until one load returns 200 (two minutes at most), printing how long each took. The measured cases still
  allow no 5xx.

Neither was run here: both are `-Pnightly` tests (`cas`, `overnight`), which run only in the overnight run unless Paul
asks. The fast build compiled both.

Run by hand on 2026-11-16 at Paul's word, on adad94c, Docker otherwise idle: `ConcurrentWritersTest` passed, 5 of 5
(the warm-up took 0.1 s on `writers-a` and 0.9 s on `writers-b`, one 200 each; no 5xx in any case), and
`CockroachStoreSuiteTest#exactlyOneWriterWinsEachRound` passed, 1,000 rounds in 342 s, repeating no commit. Neither run
met a stall, so neither shows the new handling working; the item stays open for the overnight run.

Seen again in the overnight run of 2026-11-18 (testing 2dee958, the first with both fixes): the CAS test passed on
all four backends, and `ConcurrentWritersTest` failed one case of five, "32 writers, format v2" (301 s), in a new
place. After the case's writers ran, its `SELECT deadlocks FROM pg_stat_database WHERE datname = 'catalog'`
(`ConcurrentWritersTest.java:115`) failed: `PSQLException: The connection attempt failed`, caused by
`SocketTimeoutException: Read timed out`: a read reaching a container timed out, as in the stall above, met
this time by the test's own check rather than by a writer. 8 and 16 writers at v2, and 8 and 32 at v3, passed. Logs:
`C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-18\nightly.log` and `nightly-results/` (run before the rename to nuna).

Fixed on 2026-11-19 (Paul, 2026-11-19: "Fix the check"), not yet run; open until both tests pass in an overnight run:

- `ConcurrentWritersTest` reads the deadlock count again after 1 s when the read fails, three tries at most, both
  before the writers start and after they finish; a third failure in a row still fails the case, and what it asserts
  is unchanged (the count after equals the count before). Each repeat prints a line (`[m8] deadlock count not read,
  repeating in 1 s: …` and `[m8] deadlock count read after n repeat(s)`), so the overnight log shows any. The read is
  the test's own instrument, and no writer runs while it reads, so it repeats on any failure.
- The same exposure in the CAS test (`CommitSuite#exactlyOneWriterWinsEachRound`): after each round it reads the
  pointer and the winner's outcome through the store (`readPointer`, `findCommit`: 2,000 reads in 1,000 rounds), and
  at the end the journal directly (`journalVersions`). The store's two reads are now asked again on
  `StoreUnavailableException` alone, as the commits are (one helper, `repeating`, serves all three: three tries, 1 s
  apart); the journal read, the suite's own instrument over four drivers that fail in their own types, on any
  failure. It prints how many reads it repeated beside how many commits.
- Left as they were: `ConcurrentWritersTest`'s other requests go to the catalog servers, whose answers the case
  holds to no 5xx (the item's rule), and `Stack.grantWriters` is a write in the stack's setup, not a check.

Not run: both are `-Pnightly` tests (`overnight`, `cas`), and the heavy and nightly builds were off on 2026-11-19. The
fast build compiled both.

### 60. CredentialExpiryTest's first request answered 401

- Added: 2026-11-18 by overnight run 2026-11-18
- Touches: catalog-server/
- Effort: M, the cause is unknown: the test records no reason for the 401

The heavy build of the overnight run of 2026-11-18 (testing 2dee958, the demo taken down for the run) failed
`nuna.catalog.server.CredentialExpiryTest#noNotModifiedNearExpiryAndANewEpochIsANewETag` at 05:11:22 UTC:
`AssertionFailedError: expected: 200 but was: 401` at `CredentialExpiryTest.java:66`, the test's first request
(`POST /namespaces`, right after `IssuerForTests.grant`). So the catalog refused the test issuer's token before
anything about expiry or ETags was checked. The token was not stale: `IssuerForTests` mints each one with an hour to
live. The test's output holds no line saying why; its only warnings are the audit fallback's (no Kafka in this
test). The test is not tagged heavy, so every fast build runs it, and every landing's fast build has passed it; it
also passed in the overnight run of 2026-11-16.

Evidence: `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-18\heavy.log`, and
`heavy-results\catalog-server\build\test-results\test\TEST-keel.catalog.server.CredentialExpiryTest.xml` there (run
before the rename to nuna).

Done when the cause of the 401 is found and fixed, or shown to be the environment with the evidence, and the test
passes in the overnight run.

### 68. Catalog names still leak through /v1/config and the unknown-prefix 404 (Paul decides, D18)

- Added: 2026-11-19 by agent working item 20
- Touches: catalog-server/
- Effort: M, four lookups answer before any guard, each with a test; after Paul decides

Item 20 filtered `GET /management/v1/catalogs` to the catalogs the caller holds something on (D18 as amended
2026-11-19). Other routes still tell any signed-in caller whether a catalog name exists, whatever it holds:

- `GET /v1/config?warehouse=<name>` answers 200 with the prefix for a declared catalog and 404
  NoSuchWarehouseException for any other name, with no authorization at all (`ConfigRoute.java`, `config`).
- Every route under `/v1/{prefix}/` and `/management/v1/catalogs/{catalog}/` resolves the catalog before its guard:
  404 NoSuchWarehouseException for an unknown name, then 403 for a known one the caller holds nothing on
  (`Lookup.catalog`, `governance/Management.java` line 81, `security/Securables.catalog`). For example
  `GET /v1/<name>/namespaces` as a caller in no granted group.

D18's amendment names HEAD, loads, the metrics route (item 14) and the catalog list (item 20); it does not say
whether a catalog's existence is hidden too. Hiding it in `/v1/config` changes what an engine with no grant sees:
it would fail at its first call, the config, rather than at the first listing or load.

Done when Paul has decided whether catalog names are hidden on these routes too, and, if they are, a caller who
holds nothing on a catalog gets the same answer whether or not it exists, with tests and a bite.

### 69. The DR reconcile's apply waits within the retry budget when the store is down, as the commit engine does

- Added: 2026-11-19 by the agent on item 67
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/Reconcile.java, catalog-core/src/test/, catalog-server/src/main/java/nuna/catalog/server/governance/ReconcileRoutes.java
- Effort: S, one call in Reconcile, its construction in one route, and a test with a store double

Items 8 and 67 made the commit engine wait within the retry budget (P10: at most 4 asks, none starting more than 2 s
after the request started, with the commit loop's backoff) when the store cannot be reached while it decides an
unknown outcome. `Reconcile.apply` (the DR runbook's reconcile, D37) does not: when `store.commit` throws
`CommitOutcomeUnknownException` it asks `findCommit` once (`catalog-core/src/main/java/nuna/catalog/core/commit/
Reconcile.java`, about line 185), so a store outage there throws `StoreUnavailableException` out of the apply to the
operator's route at once. Nothing is lost (a second run plans again from the pointer), but the count of steps already
applied is not reported.

Giving it the wait means passing the engine's `CommitSettings` and sleeper to `Reconcile`, which
`catalog-server`'s `ReconcileRoutes` constructs (`new Reconcile(store, files, clock)`), and sharing the engine's
asking loop (`CommitEngine.decide`, private today) rather than copying it. Item 67 left it, as its brief kept it out
of scope (`catalog-server` was held by another agent).

Done when a reconcile step whose outcome is unknown during a short outage is settled within the budget, shown by a
test with a store double that needs no database (as `UnregisterOutageTest` does), and an outage longer than the
budget answers as today.

### 70. A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After

- Added: 2026-11-19 by agent-abefa39293b4b238c, from item 7
- Touches: catalog-server/src/test/
- Effort: M, two heavy Spring tests in one module, each needing its database behind a link it can cut

Item 7 made the Oracle and MongoDB stores raise `StoreUnavailableException` for a database that cannot be reached,
is failing over or is out of connections. Its checks stop at the store: the Java-only `UnavailableFailuresTest` in
`catalog-store-oracle` and `catalog-store-mongo`, the shared suite's `CommitSuite#linkDownBeforeCommitIsACleanFailure`
(heavy, every backend) and `MongoStoreSuiteTest#commitWhileFailingOverIsUnavailable` (heavy). The routes' answer to
that exception, 503 with Retry-After, is store-independent (`catalog-server`'s error mapping, `catalog-core`'s
`CommitEngine`), but nothing runs the server against Oracle or MongoDB with the database away. The only test that
watches the routes through an outage is `catalog-tests-load`'s `KillPrimaryTest` (overnight), on PostgreSQL only.
`catalog-server` was held by other agents while item 7 was worked, so the route-level check could not go there.

Done when `catalog-server`'s `OracleBackendTest` and `MongodbBackendTest` (both `@Tag("heavy")`) each cut the link to
their database (the store tests' `OracleFree` and `MongoReplicaSet` do it with a proxy) and show a load-table and a
commit answering 503 with a Retry-After header in the Iceberg error shape, then the same requests succeeding once the
link is back.

### 72. Oracle's self-check does not notice a journal-partition job that cannot run

- Added: 2026-11-19 by agent-a3dc4bb4c858dc2c8, from item 9
- Touches: catalog-store-oracle/
- Effort: S, one more query in one store's self-check and a heavy test; waits on Paul's answer below

Oracle keeps its journal's horizon with migration V5's Scheduler job, `JOURNAL_PARTITIONS_AHEAD`, which creates
this month's partition and the next two every day (item 9 checked it; `OracleStoreSuiteTest#
schedulerJobCreatesJournalPartitionsAhead`, heavy). The job runs only while `JOB_QUEUE_PROCESSES` is not 0, and
only while it stays enabled and keeps succeeding (V5's own comment). When it does not run, nothing says so: the
store's `selfCheck()` reads `V_$PARAMETER` for other findings but not `job_queue_processes`, nor the job's
`enabled`, `state` or `failure_count` in `user_scheduler_jobs`. Interval partitioning still takes every commit, but
each month's first commit then creates its partition inside the commit, which took 0.5 to 2.6 s on Free (M2b) and
can run past the pool's read timeout, which fails that commit (docs/storage-design.md, M2b deviation 9).

Done when the self-check reports a job that cannot run (`job_queue_processes` 0, the job missing, disabled or
failing), with a heavy test that disables the job on a fresh schema and finds the report.

Paul decides: since item 6 (D8, amended 2026-11-19) the server refuses to start on any self-check finding. Should a
job that cannot run refuse the start, as the other findings do, or only be reported (the commits still land)?

### 75. deploy/README.md and install-at-work.md still call the ETag routing choice open

- Added: 2026-11-20 by agent on item 15
- Touches: deploy/README.md docs/install-at-work.md
- Effort: S, two paragraphs of Markdown; no wait

Paul chose the shared epoch over sticky routing on 2026-11-19 (D14, amended for item 15), and item 15 built it: a
table load's credential epoch is the issuance window it falls in, so every server gives the same ETag and a
conditional load answered by another server is a 304. Two documents still say the choice is open, and item 15's
claim reached neither:

- `deploy/README.md`, "ETags and routing": "M8 found that ETags do not survive a change of server ... The choice
  between sticky routing and a shared credential epoch is still open (build-plan, M8 findings)."
- `docs/install-at-work.md`, section 5: "Decide with Paul before production: sticky routing for ETags
  (`service.sessionAffinity: ClientIP`, or the Ingress's own) ..."

Done when both say the epoch is shared (D14, item 15), so the chart's default `service.sessionAffinity: None` keeps
304s across servers, and neither asks for sticky routing for ETags. The chart itself needs no change.

### 76. The overnight load tests expect every conditional load to be a 304, but the shared epoch changes every window

- Added: 2026-11-20 by agent on item 15
- Touches: catalog-tests-load/src/test/java/nuna/catalog/load/SloTest.java catalog-tests-load/src/test/java/nuna/catalog/load/TenThousandSnapshotsTest.java
- Effort: S, two loops in two overnight tests; no wait. Before the first overnight run after item 15 lands, or that run may fail on it

Item 15 (D14, amended 2026-11-19) made a table load's credential epoch the issuance window the load falls in: a
quarter of `nuna.storage.credential-lifetime`, 7.5 minutes by default, counted from the Unix epoch. Every server now
gives the same ETag within a window, and the ETag changes at each window's end, whenever that falls. Before, the
epoch was a hash of the server's own cached access key, which a fresh server kept for 22.5 minutes after its first
vend, so a test that ran its conditional loads soon after warming up never saw it change.

Two overnight tests (catalog-tests-load, tagged `overnight`) take an ETag, then assert that every conditional load
over the next tens of seconds is a 304:

- `SloTest#sloRun`: the ETags of 32 tables from each server, then `readConditional`, 16 readers x 300 loads, each
  `assertThat(answer.status()).isEqualTo(304)` (about 23 s at the M8 laptop's 77 ms p50).
- `TenThousandSnapshotsTest`: an ETag from the last full load, then 50 loads by refs and 200 conditional loads, each
  asserted 304 (about 25 s at the M8 timings), for v2 and for v3.

A window boundary inside such a stretch turns the next conditional load into a 200 with the new ETag, as D14 now
allows ("a client may lose a 304 near a boundary"), and the assertion fails. At those durations that is roughly one
night in 15 for `SloTest` and one in 18 for each format version of `TenThousandSnapshotsTest`: one night in 6
together. The heavy tests that
expect one 304 right after an ETag (`RestRoutesTest#tableLoadsAnswer304WhileThePointerHolds`,
`CatalogMetricsRoutesTest#callsAreTimedByOperationOutcomeAndErrorType`) take milliseconds, about one run in 10,000.

Done when both loops accept a 200 that carries a new ETag at most once per window boundary they cross, and carry on
with that ETag (a client does the same), still failing on any other 200 or on a second one inside a window; or start
each loop only when the current window has longer left than the loop takes. `SloTest`'s "304 across servers" line
should then read 32 of 32. The tests are `overnight`, so only the overnight run (or `./nightly.sh` on Paul's word)
shows it.

### 77. rest/Guard keeps its own copy of the rule security/Holdings now holds; TelemetryTest and table-health.md still say an outsider gets 403 on the management API

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-server/src/main/java/nuna/catalog/server/rest/Guard.java catalog-server/src/test/java/nuna/catalog/server/rest/HiddenNamesTest.java bites/D18/namespace-blind-to-grants-inside.patch catalog-server/src/test/java/nuna/catalog/server/telemetry/TelemetryTest.java docs/observability/table-health.md
- Effort: S, two prepared patches to apply and a fast build; waits on items 15 and 71 releasing `rest/` and `telemetry/`

Item 73 hid names on the management API with item 14's rule (a caller that holds nothing on a namespace, table or
view, above it or, for a namespace, inside it, is answered as for one that does not exist). The rule lived in
`rest/Guard.java` as private methods, and `rest/` was held by item 15's agent, so item 73 moved a copy into a public
`security/Holdings` (`something`, `onOrAbove`, `onAboveOrIn`) for the governance and grant routes and left `Guard`
alone. Until `Guard` asks `Holdings`, the rule is written twice and the two can drift.

Item 73 also changed what two files held by item 71's agent say:

- `TelemetryTest#withoutAClientIdTheRequestIdIsTheTraceId` (heavy) sends otto, who holds nothing, to
  `.../tables/t/governance` and expects 403; it now gets 404 `NoSuchTableException`, with the denial still audited
  under its request id. It fails in the heavy build until changed.
- `docs/observability/table-health.md`, under the routes table: "A caller without the privilege gets 403" is true
  now only for a caller that holds something on the table.

Two patches were prepared on 2026-11-20 against `main` with item 73 merged, in that session's scratchpad (handed to
the coordinator; what each does is below, should the files be gone). Each passed `git apply --check`; neither was
built:

- `guard-uses-holdings.patch`: `Guard` takes `Holdings` in place of `CatalogStore` and calls
  `holdings.onOrAbove(request.caller(), target)` and `holdings.onAboveOrIn(request.caller(), catalog.id(), id)`; its
  `holdsOnOrAbove`, `holdsOnAboveOrIn`, `below` and `parent` go, with their imports. `HiddenNamesTest` builds `Guard`
  with `new Holdings(authorizer, store, settings)`. `bites/D18/namespace-blind-to-grants-inside.patch` is recorded
  again with the same break (`if (true || groups.isEmpty())`) in `Holdings`, still naming
  `HiddenNamesTest#aGrantOnAnythingInANamespaceLetsTheCallerSeeTheNamespace`.
- `telemetry-and-docs.patch`: the test expects 404; the doc says a refusal is 403 except on a table's health to a
  caller that holds nothing on the table, which is 404 `NoSuchTableException`, and that every refusal is audited.

Done when `Guard` asks `Holdings` and keeps no copy of the rule, `HiddenNamesTest` and the fast build pass, and the
test and the doc say 404 for a caller that holds nothing.

### 78. Paul decides: whether the DR routes and the routes by object id hide names and ids from a caller that holds nothing

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/ catalog-server/src/test/java/nuna/catalog/server/governance/
- Effort: S, a guard change on six routes with tests, once Paul decides; waits on Paul

Item 73 hid namespace, table and view names on the management API (D18, amended again 2026-11-19: "Hide there too";
"Catalogs, not objects"). Two kinds of route were left as they were, because the decision does not settle them:

1. **The DR routes, which D37 gives to platform operators by name.** `PUT` and `DELETE .../dr-freeze` on a namespace,
   table or view need a platform operator and no grant, so an operator that holds nothing there still learns whether
   the name exists (200 or 204 for one that does, 404 for one that does not; a thaw of an unfrozen object answers
   `NoSuchAttributeException`, of a missing one `NoSuchNamespaceException` and the like). `POST .../dr-reconcile`
   answers with the path of every active table in the catalog. "Catalogs, not objects" says an operator sees no
   object name unless granted; D37 says the freeze and the reconcile are operators' deployment actions. Either the DR
   routes stay open to operators by name (D37 an exception to "Catalogs, not objects"), or an operator needs a grant
   on what it freezes and sees only the tables it holds something on in a reconcile.
   A caller that is not an operator and holds nothing on the target already gets the 404 since item 73.
2. **The routes by object id.** `POST .../objects/{id}/undrop`, `GET .../objects/{id}/export`, and the maintenance
   purge-claim and purge routes still answer 403 to a refused caller (one that holds nothing on the object; for the
   purge routes, anyone but a maintenance principal) and 404 for an unknown id. They show no name, only whether an
   id is in use, and an id is a random UUID the caller must already have. Either they stay as they are, or a caller
   that holds nothing gets the unknown id's 404.

Evidence: `GovernanceRoutes.freeze` and `thaw` (`guard.requireOperatorOn`, which hides only from non-operators),
`ReconcileRoutes.reconcile`, `LifecycleRoutes.undrop` and `export` (`guard.require` on an `Authorizer.Target`, 403
only), `claimPurge` and `purge` (`guard.requireMaintenance`), as of item 73's landing.

Done when Paul has decided each, the decision is in `decision-ledger.md`, and the routes answer as he decided, with
tests.

## Done and dropped

| # | Item | Status | On |
|--:|:--|:--|:--|
| [2](backlog/2.md) | First run of ./nightly.sh (only when Paul asks) | done | 2026-11-15 |
| [3](backlog/3.md) | bites/run.sh counts a setup failure as a bite | done | 2026-11-14 |
| [5](backlog/5.md) | Install the nuna-maintenance Helm chart somewhere | done | 2026-11-20 |
| [6](backlog/6.md) | The server runs the store's self-check at startup (ask Paul before wiring it) | done | 2026-11-20 |
| [7](backlog/7.md) | Oracle and MongoDB stores answer an outage with 503, not 500 | done | 2026-11-19 |
| [8](backlog/8.md) | findCommit waits within the retry budget when the store is down | done | 2026-11-19 |
| [9](backlog/9.md) | Extend the journal horizon with a job, not only at migration | done | 2026-11-19 |
| [11](backlog/11.md) | The purge rate limit is per replica | done | 2026-11-19 |
| [12](backlog/12.md) | A revive can bring an object back in a namespace it later left | done | 2026-11-19 |
| [13](backlog/13.md) | The separation-of-duties check and the grant write are not atomic | done | 2026-11-20 |
| [14](backlog/14.md) | HEAD and loads answer 404 before 403, so names leak to signed-in callers | done | 2026-11-19 |
| [15](backlog/15.md) | ETags do not survive a change of server (Paul decides) | done | 2026-11-20 |
| [16](backlog/16.md) | May the console decline vended credentials? (Paul decides, D21) | done | 2026-11-19 |
| [18](backlog/18.md) | Browser end-to-end tests for the console | done | 2026-11-15 |
| [20](backlog/20.md) | Catalog names are listed to every signed-in caller (a question for work) | done | 2026-11-19 |
| [30](backlog/30.md) | Two bites fail only by stopping the server's start, so run.sh calls them SETUP | done | 2026-11-15 |
| [31](backlog/31.md) | kafbat: ana, gary and stella administer topics and produce messages | done | 2026-11-15 |
| [32](backlog/32.md) | The Ceph leg found no Docker right after the heavy build | done | 2026-11-19 |
| [33](backlog/33.md) | The Keycloak account page fails for every demo user | done | 2026-11-15 |
| [34](backlog/34.md) | Keycloak sign-in for CloudBeaver and SingleStore Studio | done | 2026-11-15 |
| [35](backlog/35.md) | kafbat: fence off the catalog's topics and keep an audit log | done | 2026-11-15 |
| [36](backlog/36.md) | Say in the README and demo.sh what kafbat fences off and audits (after 34) | done | 2026-11-15 |
| [37](backlog/37.md) | Sanitize scan-report filters before a metrics report leaves the catalog, and keep unknown report types | done | 2026-11-15 |
| [38](backlog/38.md) | Telemetry foundation: OTLP export from the server and the worker, JSON logs, one request ID everywhere | done | 2026-11-15 |
| [39](backlog/39.md) | Catalog metrics: Iceberg operations, commits, loads, vending, alerts and client reports (after 37 and 38) | done | 2026-11-15 |
| [40](backlog/40.md) | Worker metrics: what each scan did, how long it took, when one last succeeded (after 38) | done | 2026-11-15 |
| [41](backlog/41.md) | Spans and timings for the database, object store, STS and Kafka audit calls (after 38) | done | 2026-11-15 |
| [42](backlog/42.md) | Per-table health metrics from commit summaries (Paul decides) | done | 2026-11-15 |
| [45](backlog/45.md) | A sanitized scan filter's hash can be reversed for short values such as an SSN (Paul decides) | done | 2026-11-15 |
| [46](backlog/46.md) | Compaction on need, in a nightly cleanup window (D30, item 42; after 40) | done | 2026-11-15 |
| [47](backlog/47.md) | Table health and the worker's scans, kept by the catalog (D38; after 46) | done | 2026-11-15 |
| [48](backlog/48.md) | Health dashboard in the console: catalog health and table health (D38; after 39, 40 and 47) | done | 2026-11-15 |
| [49](backlog/49.md) | The worker's scans make spans, and its calls to the catalog carry their trace (after 38 and 40) | done | 2026-11-15 |
| [50](backlog/50.md) | backlog.sh done fails on a branch name with a slash | done | 2026-11-15 |
| [51](backlog/51.md) | ConsoleBrowser.awaitText throws on a stale element instead of waiting | done | 2026-11-15 |
| [52](backlog/52.md) | backlog.sh done fails on a branch name with a slash | dropped | 2026-11-15 |
| [53](backlog/53.md) | Count credential vends served from the cache apart from new issues (after 41) | done | 2026-11-15 |
| [54](backlog/54.md) | KubernetesCompactionTest: kubectl's jsonpath filter loses its quotes on Windows | done | 2026-11-16 |
| [56](backlog/56.md) | WorkerTraceTest takes the expiry's table load for the scan's | done | 2026-11-16 |
| [57](backlog/57.md) | Publish Silo's console on localhost:8184 | done | 2026-11-16 |
| [58](backlog/58.md) | Demo: TLS at one front door, with a certificate that work's can replace | done | 2026-11-17 |
| [59](backlog/59.md) | Rename keel to nuna, short for nunatak, everywhere | done | 2026-11-18 |
| [61](backlog/61.md) | KubernetesCompactionTest: kubectl get job ran past its minute, after item 54's fix | done | 2026-11-19 |
| [62](backlog/62.md) | The worker's TelemetryTest: no metric arrived within 20 s from the standard variables alone | done | 2026-11-19 |
| [63](backlog/63.md) | The overnight folder moves inside the repo, gitignored | done | 2026-11-18 |
| [64](backlog/64.md) | CloudBeaver sign-in sometimes answers 500, then works on retry | done | 2026-11-19 |
| [65](backlog/65.md) | RollingUpgradeTest: the previous release is given nuna settings it does not read, since the rename | done | 2026-11-19 |
| [66](backlog/66.md) | The overnight run of 2026-11-19 started at 07:09, when the PC woke, not at midnight | dropped | 2026-11-19 |
| [67](backlog/67.md) | An unregister whose outcome is unknown waits within the retry budget when the store is down | done | 2026-11-19 |
| [71](backlog/71.md) | Export the audit publish timer's histogram, so Grafana can show the Kafka acknowledgement p99 | done | 2026-11-20 |
| [73](backlog/73.md) | Namespace, table and view names still leak through the management API | done | 2026-11-20 |
| [74](backlog/74.md) | HealthMeters keeps its own copy of the audit publish buckets and still says the exported timer has none | done | 2026-11-20 |
