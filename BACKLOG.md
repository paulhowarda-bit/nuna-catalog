# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**31 open:** 7 S, 14 M, 6 L, 4 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42) | 2026-11-14 |
| [4](backlog/4.md) | M | Apply the AWS S3 and MinIO delete policies to a real store, and run the worker on AWS | three policies applied to real stores and the worker run on AWS; no new code expected | 2026-11-14 |
| [5](backlog/5.md) | M | Install the nuna-maintenance Helm chart somewhere | install the chart on Docker Desktop's Kubernetes and fix what the install finds | 2026-11-14 |
| [6](backlog/6.md) | M | The server runs the store's self-check at startup (ask Paul before wiring it) | one startup call, tested on four backends with a bite; after Paul decides | 2026-11-14 |
| [7](backlog/7.md) | L | Oracle and MongoDB stores answer an outage with 503, not 500 | two store modules, each with its own driver errors and a heavy fault test | 2026-11-14 |
| [8](backlog/8.md) | M | findCommit waits within the retry budget when the store is down | one path in the commit engine and a fault test | 2026-11-14 |
| [9](backlog/9.md) | M | Extend the journal horizon with a job, not only at migration | check four backends and add a job where one is missing, with a test | 2026-11-14 |
| [10](backlog/10.md) | L | DR reconcile: prove the accepted tips from the audit archive | read the audit archive, a new reconcile option, and the DR drill updated | 2026-11-14 |
| [11](backlog/11.md) | S | The purge rate limit is per replica | per replica is kept (D30); the docs say so | 2026-11-14 |
| [12](backlog/12.md) | M | A revive can bring an object back in a namespace it later left | one namespace comparison in each of four stores, a store-suite test and a bite | 2026-11-14 |
| [13](backlog/13.md) | M | The separation-of-duties check and the grant write are not atomic | confirm it still holds, then one transaction and a race test | 2026-11-14 |
| [14](backlog/14.md) | M | HEAD and loads answer 404 before 403, so names leak to signed-in callers | three routes reordered, with tests; after Paul decides | 2026-11-14 |
| [15](backlog/15.md) | M | ETags do not survive a change of server (Paul decides) | a shared issuance window for the epoch, or sticky routing in the chart; after Paul chooses | 2026-11-14 |
| [16](backlog/16.md) | S | May the console decline vended credentials? (Paul decides, D21) | the console declines as a trusted engine does, with a test and a bite; after Paul decides | 2026-11-14 |
| [17](backlog/17.md) | M | Console sign-in against Entra ID and Ping, with an automated test | checks against Entra ID and Ping at work; ending the IdP session if Paul wants it | 2026-11-14 |
| [19](backlog/19.md) | S | Cap the audit-read route for scripted use | a per-caller limit on one route, answering 429, with a test | 2026-11-14 |
| [20](backlog/20.md) | S | Catalog names are listed to every signed-in caller (a question for work) | filter one route by the caller's grants, with a test; after work answers | 2026-11-14 |
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
| [44](backlog/44.md) | L | Grafana dashboards and Prometheus alert rules for nuna (at work) | dashboards and alert rules for six areas, each alert fired once in a test | 2026-11-15 |
| [55](backlog/55.md) | S | The nightly lost two tests to one 15 s stall reaching Docker's containers | the deadlock check needs the same room for one transient; then an overnight run | 2026-11-16 |
| [60](backlog/60.md) | M | CredentialExpiryTest's first request answered 401 | the cause is unknown: the test records no reason for the 401 | 2026-11-18 |
| [62](backlog/62.md) | S | The worker's TelemetryTest: no metric arrived within 20 s from the standard variables alone | print worker.log on failure, then fix what it shows | 2026-11-18 |

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

### 5. Install the nuna-maintenance Helm chart somewhere

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, install the chart on Docker Desktop's Kubernetes and fix what the install finds

`deploy/helm/nuna-maintenance` passes `helm lint` and renders, but has never been installed
(`build-plan.md`, maintenance worker of 2026-11-13; `docs/install-at-work.md`).

Done when the chart is installed against a running catalog (Docker Desktop's Kubernetes is enough), the worker
starts with its own identity, completes a scan, and launches a compaction Job in its own namespace.

### 6. The server runs the store's self-check at startup (ask Paul before wiring it)

- Decided: 2026-11-19, D8
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, one startup call, tested on four backends with a bite; after Paul decides

The storage design says the server runs the store's `selfCheck()` at startup (D8.2). It does not: no class in
`catalog-server/src/main` calls it (checked 2026-11-14). `build-plan.md` lists it as an M8 follow-up, and
`docs/install-at-work.md` says to check the database's safety settings by hand and ask Paul before wiring it.

Done when Paul has said how a failed check should act (refuse to start, or warn), and the server does that on each
of the four backends, with a test and a bite.

**Decided 2026-11-19 (D8):** a failed self-check refuses to start the server ("Refuse to start"). Test containers and the demo run single-node with fsync off, so they need an explicit setting that allows an unsafe database.

### 7. Oracle and MongoDB stores answer an outage with 503, not 500

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: L, two store modules, each with its own driver errors and a heavy fault test

`build-plan.md`, M8 follow-ups: the Oracle and MongoDB stores do not raise `StoreUnavailableException`, so a
database outage there answers 500 where PostgreSQL answers 503 with Retry-After. Still so on 2026-11-14: neither
store module's main code names the exception.

Done when both stores raise it for a database that cannot be reached, is failing over or is out of connections,
when nothing was applied, and a heavy test on each shows a 503 with Retry-After.

### 8. findCommit waits within the retry budget when the store is down

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, one path in the commit engine and a fault test

`build-plan.md`, M8 follow-ups: `findCommit` answers "unknown" at once when the store is down, where it could wait
within the retry budget (P10: at most 4 attempts or 2 s) and often settle the outcome.

Done when a commit whose outcome is unknown during a short outage is settled within the budget, shown by a fault
test, and an outage longer than the budget still answers as today.

### 9. Extend the journal horizon with a job, not only at migration

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, check four backends and add a job where one is missing, with a test

`build-plan.md`, M8 follow-ups: the journal's monthly partitions are extended ahead only when migrations run
(`SqlCatalogStore.migrate()`, 12 months ahead). A server that runs longer than that without a migration runs out.
Oracle's V5 already adds a daily Scheduler job (M2b); check which backends still lack one.

Done when every backend that partitions the journal keeps its horizon ahead without a migration, with a test.

### 10. DR reconcile: prove the accepted tips from the audit archive

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: L, read the audit archive, a new reconcile option, and the DR drill updated

`build-plan.md`, M8 follow-ups: the `accept` list of `POST /management/v1/catalogs/{catalog}/dr-reconcile` is
assembled by hand. The audit archive could prove which tips were acknowledged.

Done when the operator can have the archive supply or check the accepted tips, and the DR drill uses it.

### 11. The purge rate limit is per replica

- Decided: 2026-11-19, D30
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, per replica is kept (D30); the docs say so

`build-plan.md`, maintenance worker, catalog side (2026-11-13): `nuna.governance.purges-per-minute` is kept per
replica, so three replicas accept three times the configured rate.

Done when the limit holds across replicas (or Paul decides the per-replica reading is what he wants and the docs
say so).

**Decided 2026-11-19 (D30):** the limit stays per replica ("Per replica, documented"). What is left: the docs say so, so operators divide it by the replica count.

### 12. A revive can bring an object back in a namespace it later left

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, one namespace comparison in each of four stores, a store-suite test and a bite

`build-plan.md`, review fixes of 2026-11-12, "Not guarded": an object undropped, renamed into another namespace and
dropped again by other callers between the engine's read and the store's revive would come back in the namespace it
first left, because the store does not compare the namespace.

Done when the store compares the namespace under its lock on all four backends, with a store-suite test and a bite.

### 13. The separation-of-duties check and the grant write are not atomic

- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, confirm it still holds, then one transaction and a race test

`build-plan.md`, M5 (2026-11-08), Open: the SoD check and the grant write are not atomic, so two grants made at once
can together break separation of duties. Not settled in the ledger since; confirm it still holds first.

Done when two concurrent grants cannot together break SoD, shown by a test that races them.

### 14. HEAD and loads answer 404 before 403, so names leak to signed-in callers

- Decided: 2026-11-19, D18
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, three routes reordered, with tests; after Paul decides

`build-plan.md`, M5 (2026-11-08), Open: HEAD and loads answer 404 before 403, so any signed-in caller can learn
which names exist. Not settled in the ledger since; confirm it still holds first. Whether names are secret is
Paul's call (see item 20).

The metrics route answers in the same order (`TableRoutes.java`, `metrics`: `objects.existing` before
`guard.onObject`), so it falls under this item too (found 2026-11-15, Iceberg observability review).

Done when Paul has decided, and the routes answer as he decided, with tests.

**Decided 2026-11-19 (D18):** names are hidden ("Hide names"): a caller without access gets the same answer whether or not the name exists, on HEAD, loads and the metrics route.

### 15. ETags do not survive a change of server (Paul decides)

- Decided: 2026-11-19, D14
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: M, a shared issuance window for the epoch, or sticky routing in the chart; after Paul chooses

`build-plan.md`, M8 finding for decision: the ETag includes the credential epoch, a hash of the vended access key.
Each server mints and caches its own credentials, so a conditional load answered by another server is a 200: 0 of
32 in the SLO run. Behind the Kubernetes Service, which is not sticky, the 304 path (D14) mostly misses. The
deployment entry of 2026-11-11 says it needs deciding before production.

Two ways, for Paul: derive the epoch from a shared issuance window so servers agree, or accept sticky routing.

Done when Paul has chosen, and two servers answer one client's conditional loads with 304s (or the chart routes
stickily), with a test.

**Decided 2026-11-19 (D14):** a shared epoch ("Shared epoch"): servers derive the credential epoch from a shared issuance window, so they agree on the ETag; not sticky routing.

### 16. May the console decline vended credentials? (Paul decides, D21)

- Decided: 2026-11-19, D21
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, the console declines as a trusted engine does, with a test and a bite; after Paul decides

`build-plan.md`, UI v1, Open: D21 vends to every caller who loads a table; only a trusted engine may decline. The
console is vended to and throws the credentials away in the browser. Should a non-engine caller be allowed to
decline, or get no credentials at all?

Done when Paul has decided, and the console's loads carry no credentials if that is his choice, with a test and a
bite.

**Decided 2026-11-19 (D21):** any caller that asks for no credentials gets none ("Honour none from anyone"), not only trusted engines; the console already asks with `X-Iceberg-Access-Delegation: none`.

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

### 20. Catalog names are listed to every signed-in caller (a question for work)

- Decided: 2026-11-19, D18
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, filter one route by the caller's grants, with a test; after work answers

`build-plan.md`, UI v1: `GET /management/v1/catalogs` lists the declared catalog names to any signed-in caller,
unfiltered. Filter them if a deployment treats names as secret.

Done when work has answered whether names are secret, and, if they are, the route lists only catalogs the caller
holds something on, with a test.

**Decided 2026-11-19 (D18):** catalog names are filtered now ("Filter them now"), as namespace and table names are (item 14), without waiting for work's answer: the route lists only the catalogs the caller holds something on.

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
- Effort: L, dashboards and alert rules for six areas, each alert fired once in a test

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

### 55. The nightly lost two tests to one 15 s stall reaching Docker's containers

- Added: 2026-11-16 by overnight run 2026-11-16
- Touches: catalog-store-api/src/test/java/nuna/catalog/store/suite/CommitSuite.java, catalog-tests-load/src/test/java/nuna/catalog/load/ConcurrentWritersTest.java
- Effort: S, the deadlock check needs the same room for one transient; then an overnight run

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

### 62. The worker's TelemetryTest: no metric arrived within 20 s from the standard variables alone

- Added: 2026-11-18 by overnight run 2026-11-18
- Touches: catalog-maintenance/src/test/java/nuna/maintenance/TelemetryTest.java
- Effort: S, print worker.log on failure, then fix what it shows

The heavy build of the overnight run of 2026-11-18 (testing 2dee958, the demo taken down for the run) failed
`nuna.maintenance.TelemetryTest#theStandardVariablesAloneTurnExportOn` at about 05:16 UTC:
`AssertionError: no matching metric arrived within PT20S` (`TelemetryTest.java:181`, from 102 and 124). The test
starts the worker's main in a JVM of its own with only the `OTEL_*` variables set, and waits 20 s (`ARRIVAL`) for a
`jvm.*` metric. Those 20 s cover starting that JVM and its Spring context while the heavy build runs two modules'
tests at once. The worker's own output went to `worker.log` in the test's temporary folder, which is gone, so the
run does not show whether it started late or never exported. The other three TelemetryTest cases passed. The test is
not tagged heavy, so every fast build runs it and every landing's has passed it; it also passed in the overnight run
of 2026-11-16.

Evidence: `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-18\heavy.log`, and
`heavy-results\catalog-maintenance\build\test-results\test\TEST-keel.maintenance.TelemetryTest.xml` there (run
before the rename to nuna).

Done when a failure tells a slow start from export that never begins (for example: the test prints `worker.log`
when it fails), the cause is fixed, and the test passes in the overnight run.

**Again on 2026-11-19:** the heavy build of that night's run (testing d34547f, after the rename; it started at 07:16,
item 66) failed the same test the same way, 21.1 s, `OtlpReceiver.await(OtlpReceiver.java:181)` from
`TelemetryTest.java:124`. It was the heavy build's only failure; the build stops at the first failing module, so only seven modules' tests
ran. Evidence:
`.overnight\runs\2026-11-19\heavy-results\catalog-maintenance\build\test-results\test\TEST-nuna.maintenance.TelemetryTest.xml`.

## Done and dropped

| # | Item | Status | On |
|--:|:--|:--|:--|
| [2](backlog/2.md) | First run of ./nightly.sh (only when Paul asks) | done | 2026-11-15 |
| [3](backlog/3.md) | bites/run.sh counts a setup failure as a bite | done | 2026-11-14 |
| [18](backlog/18.md) | Browser end-to-end tests for the console | done | 2026-11-15 |
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
| [63](backlog/63.md) | The overnight folder moves inside the repo, gitignored | done | 2026-11-18 |
| [64](backlog/64.md) | CloudBeaver sign-in sometimes answers 500, then works on retry | done | 2026-11-19 |
| [65](backlog/65.md) | RollingUpgradeTest: the previous release is given nuna settings it does not read, since the rename | done | 2026-11-19 |
| [66](backlog/66.md) | The overnight run of 2026-11-19 started at 07:09, when the PC woke, not at midnight | dropped | 2026-11-19 |
