# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**20 open:** 4 S, 9 M, 2 L, 5 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42) | 2026-11-14 |
| [4](backlog/4.md) | M | Apply the AWS S3 and MinIO delete policies to a real store, and run the worker on AWS | three policies applied to real stores and the worker run on AWS; no new code expected | 2026-11-14 |
| [17](backlog/17.md) | M | Console sign-in against Entra ID and Ping, with an automated test | checks against Entra ID and Ping at work; ending the IdP session if Paul wants it | 2026-11-14 |
| [22](backlog/22.md) | XL | Administer storage profiles and catalogs at runtime (not now, D41) | routes, storage on four backends, audit and the startup checks; only if Paul wants it | 2026-11-14 |
| [23](backlog/23.md) | M | Per-request SSE-KMS keys (waits on SEC-8) | one KMS key per storage profile in vended sessions and their policy, tested on AWS; per-domain keys wait on SEC-8 | 2026-11-14 |
| [24](backlog/24.md) | L | Kubernetes deployment: what was not checked (at work) | five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing | 2026-11-14 |
| [25](backlog/25.md) | M | One hot table under contention: throughput and fairness (measure at work) | a measuring run on work hardware; a change only if Paul wants one | 2026-11-14 |
| [26](backlog/26.md) | XL | Branches, tags and merge (v1.x, D33) | a v1.x milestone | 2026-11-14 |
| [27](backlog/27.md) | XL | Queryable audit tables (v1.x, D23) | a v1.x milestone; scope to settle with Paul | 2026-11-14 |
| [28](backlog/28.md) | XL | DR replication-watermark verifier (v1.x) | the verifier, the watermark on four stores, its readout and the rewind (Paul chose the rewind now, 2026-11-20); item 83 folded in | 2026-11-14 |
| [29](backlog/29.md) | XL | M9: carry nuna to work (at work) | the whole install checklist, at work | 2026-11-14 |
| [43](backlog/43.md) | L | Hook nuna into work's OpenTelemetry collector and Prometheus (at work) | findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval | 2026-11-15 |
| [44](backlog/44.md) | M | Grafana dashboards and Prometheus alert rules for nuna (at work) | at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43 | 2026-11-15 |
| [55](backlog/55.md) | S | The nightly lost two tests to one 15 s stall reaching Docker's containers | waits on an overnight run in which both tests pass | 2026-11-16 |
| [60](backlog/60.md) | M | CredentialExpiryTest's first request answered 401 | the cause is unknown: the test records no reason for the 401 | 2026-11-18 |
| [70](backlog/70.md) | M | A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After | two heavy Spring tests in one module, each needing its database behind a link it can cut | 2026-11-19 |
| [79](backlog/79.md) | S | deploy/local runs the catalog as its own non-root S3 user, so the except delete policy is applied and checked | one S3 user, its policies and the catalog's keys in deploy/local, checked by a local install | 2026-11-20 |
| [86](backlog/86.md) | M | The DR reconcile's archive proof reads each audit-archive chain whole into memory | a streamed verify-and-filter pass in the archive package and a test over a large chain in memory | 2026-11-20 |
| [87](backlog/87.md) | S | The audit archive's S3 client does not trust its storage profile's CA bundle | one builder call in GovernanceWiring.archive, a handshake test with PrivateCa, and a bite | 2026-11-20 |
| [88](backlog/88.md) | M | Compaction Jobs do not trust a storage profile's CA bundle | mount the bundle into each Job and make Spark's S3 clients trust it, with a test of the Job's spec; after Paul decides | 2026-11-20 |

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

**Proposed scope, 2026-11-20 (for Paul's review; not decided):**

**What it is for:** access reviewers (D19), grant admins and the platform team asking across objects, people and
months: every table a principal read last quarter, every denial in a namespace, grants never used. Nothing answers
that today. The console's trail (`AuditReadRoutes`, `RecentAudit`) shows one object's newest 200 records from at most
the last 20,000 offsets of its partition, and a catalog's trail only records about the catalog itself. The SIEM (D23)
is searched only by its users, never in SQL beside the data, and at home there is none. The archive
(`ObjectLockArchive.events`) is read whole, chain by chain.

**In:**
- One Iceberg table, `events`, in a catalog of the deployment's own (say `nuna_audit`, declared in
  `nuna.storage.catalogs.*`), format v2 so every reader in the matrix reads it (R5), partitioned by `day(time)`, and
  queried by any engine through the catalog, as Tabular's system tables were (the plan report's v1.x list).
- Columns: `event_id`, `time`, `type`, `source`; `principal`, `end_user`, `engine`, `request_id`; `catalog`,
  `object_kind`, `object_id`, `object_path`; `operation`, `privilege`, `reason` where the type has them; `data`, the
  whole payload as JSON, so a new payload version needs no new column; `topic_partition`, `topic_offset`, which tie a
  row to its archive chain. The mapping from each `EventType` lives in `catalog-audit`, without Iceberg (D2).
- A writer, `catalog-audit-tables`: a service with its own image, Helm chart and principal, an Iceberg Java client of
  the catalog like the maintenance worker (D2 as amended). It reads the audit topic in its own consumer group and
  commits every `commit-interval` (1 min by default) with the offsets it wrote in the same Iceberg commit, resuming
  from them, so each offset is written once. The relay is at least once (D23), so an `event_id` can repeat. It leaves
  out the records of its own requests, or each commit would make a record that makes the next commit; they stay in
  the topic, the SIEM and the archive. An outage delays rows; no request waits for it. The worker's expiry and
  nightly compaction keep the table tidy (D30).
- Who reads: a group Paul names, granted `select` on the audit catalog (D19); the writer holds create and write there
  only. D18's hiding works at the catalog: anyone else gets a missing catalog's 404, `/v1/config` included (item 68),
  and platform operators see its name, not its table. Rows are not filtered: a reader sees every object and person in
  the deployment's records, as a SIEM user does, so the group must be narrow.

**Out:**
- History older than the topic's retention: a backfill from the archive, sharing item 10's archive reader.
- Rows filtered per reader: an engine reads the files, so the catalog cannot filter rows (D20); only per-catalog
  tables could. Typed tables or views per event type: `data` holds the fields.
- Deleting old rows: the archive is the five-year record, and no Iceberg table is a record's only copy (D24).
- Records that went to the fallback log in a Kafka outage (reads and denials never wait, M7): not in the topic, so
  not in the table.
- The demo, whose stack already fills Docker's memory (`demo/README.md`); the console's trail reading the table.

**The gate:**
- Fast build: `AuditRowsTest` (`catalog-audit`) maps every line of the golden `events-v1.jsonl` and fails for an
  `EventType` with no mapping; the writer against a fake topic and catalog resumes after its last committed offset,
  writes no offset twice and leaves out its own records.
- Heavy (PostgreSQL, Silo, Kafka): `AuditTablesTest` finds a row for each kind of request within two intervals; a
  writer killed mid-batch leaves no gap and no repeat; a caller without the grant gets 404, one with it reads it all.
- Overnight: `EngineMatrixTest` reads `events` from Spark and PyIceberg.
- Bites in `bites/D23/`: a type dropped from the mapping; offsets committed before their rows; the writer's own
  records kept; the audit catalog readable without the grant.

**Effort,** XL as the header says: a module, image and chart, the mapping, a heavy test and a matrix leg. It waits
on nothing. It touches `catalog-audit`, `deploy/helm`, `settings.gradle.kts`, `IcebergImportBoundaryTest` (D2) and
`catalog-tests-engines`.

**Questions for Paul:**
1. Where do the tables live? Recommended: Iceberg tables in an audit catalog, read by any engine. Or: tables in the
   catalog's database behind a search route (a schema step on four stores; every read kept in the database).
2. Who reads them? Recommended: one group you name, seeing every name. Or: a table per catalog for its grant admins
   (a writer path per catalog; auth failures belong to none).
3. Which records? Recommended: all, since a review needs reads. Or: changes and denials only, far smaller.
4. What writes them? Recommended: a service with its own principal. Or: a job in the maintenance worker (no new
   image, but the maintenance principal then writes audit data).
5. Where do rows come from? Recommended: the audit topic, a minute behind, history from its retention on. Or: the
   archive, hash-checked and five years back, but up to 5 minutes behind and only where one is configured (the demo
   has none).

**Paul's answers on the proposed scope, 2026-11-20 (D23, amended):** all five as recommended: Iceberg tables in an audit catalog ("Iceberg audit catalog"); one group Paul names reads them ("One named group"); every record, reads included ("All, reads included"); a service with its own principal writes them ("Own service"); rows from the audit topic ("The audit topic"). The proposed scope above is settled; build it.

### 28. DR replication-watermark verifier (v1.x)

- Decided: 2026-11-19, D37
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, the verifier, the watermark on four stores, its readout and the rewind (Paul chose the rewind now, 2026-11-20); item 83 folded in

`build-plan.md`, v1.x milestones: a DR replication-watermark verifier. Not started.

Done when the milestone's scope is settled with Paul, built, and its gate is green.

**Decided 2026-11-19 (D37):** built next ("Build it next"), not left for v1.x; the scope is settled with Paul first.

**Proposed scope, 2026-11-20 (for Paul's review; not decided):**

**What it is for:** platform operators running the DR runbook (D37; the DR routes are theirs, D18). Storage and the
database both replicate to DR asynchronously (D37), so after losing the primary region the DR database can name files
the DR store never got. Nothing says today how far behind DR storage is, or which tables DR could serve whole. The
reconcile covers only a database behind its storage: when the pointer's own file is missing it stops ("storage is
behind the database", `Reconcile.plan`), and nothing moves the table back. The research behind D37 recommended this
verifier, the rewind being the minimum (`research_notes/Enterprise Iceberg REST catalog plan/persistence_scale_ha.md`).

**In:**
- A DR-safe watermark per table: the newest journaled version such that every file it and each earlier version
  added is in the DR store. It lives in the catalog database (a new record, a schema step on all four stores, as
  D38's table assessment was), because it must reach DR with the database. It is written after the version it names,
  so a replica that keeps commit order never holds a watermark above its pointer.
- The verifier, a job in the maintenance worker (D30), which already reads every manifest for its orphan scan and
  holds a storage identity per store. Every `dr-verify-interval` (1 min by default) it reads a new work list,
  `GET /management/v1/maintenance/dr-work` (each table's watermark and the journaled versions above it), and checks
  in the DR store each version's metadata file, manifest list, new manifests, the data and delete files they add, and
  statistics files; for a view, the metadata file. A table with no watermark is checked once, whole, by listing its
  location. It reports with `PUT /management/v1/maintenance/tables/{id}/dr-watermark` as a maintenance principal
  acting as itself; a version below the stored one or above the pointer is refused. As with D38's reports, only
  refusals are audited. The DR store: each worker storage profile names a read-only replica (endpoint, region, keys),
  bucket and paths unchanged, as on-prem site replication keeps them.
- The readout, for platform operators by name (D18 as amended 2026-11-20, item 78):
  `GET /management/v1/dr/watermarks`, the tables behind, each with pointer, watermark, versions behind and lag (time
  since its oldest unverified commit); table-free worker meters (tables behind, largest lag), and an alert in
  `deploy/grafana` past 15 minutes (S3 Replication Time Control's 99.9% bound) until PLT-4 gives the SLA.
- The rewind, `dr-reconcile` with `"rewind": true`: a table under a DR freeze whose pointer is above its watermark
  moves back to it in one journaled commit, published like the fast-forward. It writes the watermark's metadata again
  as the next version, because the walk stops where a file's version is not the pointer's. The answer lists the lost
  tail (versions, commit ids, times) for business reconciliation. The runbook runs one verifier pass in DR first, so
  the watermark goes as far as the DR store allows; the runbook is written down in `deploy/README.md`, which has none.

**Out:**
- The forward walk on async storage: `Reconcile.plan` reads metadata files only, so it can step to a proven file
  whose manifests or data never reached DR. Checking there needs files the journal lacks; worth an item of its own.
- Serving DR-region readers the watermark before any failover (the research's full design).
- Renamed buckets: S3 bucket names are global, so an AWS DR bucket has another name while every path in the metadata
  names the primary's. Rewriting locations, or Multi-Region Access Points, waits on PLT-4 at work.
- A console panel, and the demo, which has one store.

**The gate:**
- Fast build: `DrVerifyTest` (`catalog-maintenance`), two in-memory stores: the watermark stops before the first
  version missing any file, never moves back, and passes a commit that adds no snapshot on its metadata file alone.
- Heavy (PostgreSQL, two Silo stores, a replicator the test pauses): the store suite's watermark cases on all four
  backends; the routes in `ManagementAuthorizationTest` and `MutatingRoutesAuditTest`; `DrRewindRouteTest`: frozen
  tables only, the lost tail reported, every file of the new version present.
- Overnight: `DrDrillTest` gains a leg with the DR store behind: region lost, DR promoted, verifier pass, freeze,
  rewind, thaw; every table reads whole in DR, and the lost tail is exactly the commits whose files never arrived.
- Bites in `bites/D37/`: data files left out of the check; a lower watermark accepted; a rewind without a freeze; a
  rewind past the watermark.

**Effort,** XL as proposed, more than five days: a schema step on four stores, three routes, a worker job, the rewind
and a drill leg; L without the rewind. It touches `Reconcile.java` and `ReconcileRoutes.java`, as items 69 and 10 do,
so their claims overlap and they go one at a time, 69 (S) first.

**Questions for Paul:**
1. What must reach DR before a version is safe? Recommended: every file it adds, data included, so a table at its
   watermark reads whole. Or: metadata and manifests only, data trusted to the SLA (fewer checks; a data file can
   still be missing at failover).
2. Build the rewind now? Recommended: yes. Or: verifier and readout now, rewind later (then L).
3. Where does the verifier run? Recommended: in the maintenance worker. Or: in the catalog server, which reads no
   manifests today and would need the DR store's keys.
4. Model the DR store as the same bucket on another endpoint (on-prem site replication) and leave renamed AWS
   buckets to PLT-4 at work? Recommended: yes.

**Paul's answers on the proposed scope, 2026-11-20 (D37, amended):** all four as recommended: every file, data included, must reach DR ("Every file, data too"); the rewind is built now ("Yes, now"), so the item is XL; the verifier runs in the maintenance worker ("Maintenance worker"); the DR store is the same bucket on another endpoint, renamed AWS buckets wait on PLT-4 ("Yes"). Item 83 is folded in ("Fold into 28"): the reconcile stops at the watermark, so it never moves a table to a version whose files are not all in the DR store. The proposed scope above is settled; build it after items 69 and 10, which edit the same reconcile code.

### 29. M9: carry nuna to work (at work)

- Decided: 2026-11-20, D8
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, the whole install checklist, at work

`build-plan.md`, M9, and `docs/install-at-work.md`, which is the checklist: approved artifacts (PLT-6), RAC and
Exadata, real IdPs and object stores, a DBA review of the self-check thresholds (PLT-2), bucket naming and the
replication SLA (PLT-4), trusted engines (SEC-2, SEC-5), token exchange and workload identity (SEC-1), on-prem
MinIO and Ceph (PLT-3), and the overnight job in work CI running `./nightly.sh`.

Done when `docs/install-at-work.md` is carried out at work and M9's entry in `build-plan.md`'s Status says what
passed. Items for anything that fails.

**Added 2026-11-20 (D8, amended):** the DR runbook (PLT-4) tells operators to add `pg.no-sync-standby` to `nuna.database.allow-unsafe` while serving from the DR region, and to remove it once a synchronous standby is back; otherwise the servers there refuse to start (item 6).

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

**Paul's answer, 2026-11-20 (D43, amended):** the 500 commits a second target stays a panel with no alert ("Panel only").

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

### 79. deploy/local runs the catalog as its own non-root S3 user, so the except delete policy is applied and checked

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Decided: 2026-11-20, D21
- Touches: deploy/local/ deploy/README.md
- Effort: S, one S3 user, its policies and the catalog's keys in deploy/local, checked by a local install


Item 5 (2026-11-19) installed the catalog and the maintenance worker on Docker Desktop's Kubernetes with `deploy/local`. The catalog there signs in to the local S3 store (Silo) as its root user, which no user policy binds, so the delete policy with `except` (D21: deny deletes except to maintenance principals; `deploy/README.md`, the `user-policy` lines) is never applied or checked locally, only on a real store in item 4.

Paul, 2026-11-20 (D21, amended): "Yes, non-root".

Done when `deploy/local run.sh up` makes the catalog its own S3 user with the profile's policies, the `except` one included, the catalog runs as that user, and `run.sh smoke` plus a delete check show a delete refused to the catalog's vended credentials and allowed to the maintenance worker, run on Docker Desktop's Kubernetes. Uninstall what the check installs.

Built 2026-11-20 on worktree-agent-ac20c2b80fb2ae021, not yet run live, so the item stays open: what it is done
when needs the run on Docker Desktop's Kubernetes.

What changed in `deploy/local`:
- `run.sh up` makes the catalog's own Silo user, `nuna-catalog` (secret `nuna-catalog-secret-key`), before the
  chart, with policy `nuna-catalog`: get, put, the multipart actions and delete beneath `warehouse/tables/*`, list on
  `warehouse`. Delete is in it on purpose, as another grant might give it at work, so that the `except` policy is
  what refuses the catalog's delete. `values.yaml` signs the catalog in as `nuna-catalog`, no longer `nunademo`.
- Once the catalog is up, `up` reads `GET /management/v1/maintenance/delete-policies` as `oscar` and applies the
  MinIO pair: `except` as policy `nuna-no-delete` on `nuna-catalog`, `to` as policy `nuna-maintenance` on the worker's
  user `nuna-maintenance`. `up` now makes that user; `run.sh worker` used to, and still uses it.
- `run.sh smoke` adds a delete check. It loads the table as `lake` with vended credentials and puts
  `<location>/data/smoke-probe` with them (200). It then expects the vended credentials' DELETE to be 403,
  `nuna-catalog`'s DELETE 403, a vended GET 200 (the file is still there), and `nuna-maintenance`'s DELETE 204. curl
  signs each request (`--aws-sigv4`) through a port-forward to Silo. `mc rm` would not do: it stats first (a HEAD,
  which the worker's delete-only policy refuses) and exits 0 when a delete is refused.
- `deploy/README.md` ("Delete rights", "Trying it locally") says so. `docs/install-at-work.md` says nothing about the
  local install's S3 user, so it is unchanged.

Checked without an install: `bash -n deploy/local/run.sh`; `helm template` of the catalog chart with `values.yaml`
and of the worker chart with `run.sh worker`'s settings; a scratch harness with curl stubbed, over the new helpers
(`user_policy` on a delete-policies answer shaped as the catalog writes it, `field` on a load answer, `s3`'s curl
arguments and its failure on a wrong status). From the sources: MinIO's AssumeRole needs no `sts:` right of a
non-root parent, only readable policies; re-adding a MinIO user keeps its policies; curl signs every `-H` header.

Why not live: Docker Desktop's node had 1.5 GiB of memory requests free beside the nuna-demo stack (6 GiB of
7.5 GiB). `run.sh up` asks 3 GiB, and even one server, PostgreSQL and Silo ask 1.6 GiB. Nothing was installed.

Left, when Docker has room (the demo down, or 3 GiB of requests free):
1. `NUNA_K8S_NAMESPACE=nuna-item79 NUNA_K8S_HOME=<scratch> deploy/local/run.sh up`, then `... run.sh smoke`: the
   five new `ok` lines, and "smoke passed".
2. That the `except` policy is what refuses the catalog: detach it (in the Silo pod, after
   `mc alias set local http://localhost:9000 nunademo nunademo-secret-key`,
   `mc admin policy detach local nuna-no-delete --user nuna-catalog`), run `smoke` again and see it fail on
   "nuna-catalog DELETE ... answered 204, expected 403"; attach it again and see `smoke` pass.
3. `run.sh down` with the same namespace, then `helm list -A` and `kubectl get ns`.
4. Then the MinIO part of item 4 is done on Silo: both policies applied as the catalog gives them, on a store that
   enforces user policies, with only the worker deleting (item 5 showed it lists as its own user). Item 4 should
   then say so.

### 86. The DR reconcile's archive proof reads each audit-archive chain whole into memory

- Added: 2026-11-20 by the agent on item 10
- Touches: catalog-audit/src/main/java/nuna/catalog/audit/archive/, catalog-audit/src/test/java/nuna/catalog/audit/archive/
- Effort: M, a streamed verify-and-filter pass in the archive package and a test over a large chain in memory

Item 10 gave `dr-reconcile` the option `accept-archived`, which reads every chain of the audit archive through
`ArchivedCommits.read`. That calls `ObjectLockArchive.events(chain, anchor)`, which joins every segment of the chain
into one byte array (`concatenate`) and decodes every record into one list before it returns. The audit topic carries
every record, reads, denials and credential issues included, and the archive keeps them at least five years (D23), so
a production chain will not fit in a server's heap. The drill and the tests read archives of a few thousand records.

Done when the reconcile's read verifies and filters each chain in one streamed pass, segment by segment
(`ArchiveVerifier` already reads an `InputStream`), keeping only the commit records, with a test over an archive in
memory (`MemoryS3`) whose chain is larger than the reader holds at once.

### 87. The audit archive's S3 client does not trust its storage profile's CA bundle

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/GovernanceWiring.java catalog-server/src/test/java/nuna/catalog/server/governance/ bites/M7/
- Effort: S, one builder call in GovernanceWiring.archive, a handshake test with PrivateCa, and a bite

Item 21 lets a storage profile name a CA bundle (`nuna.storage.profiles.<name>.ca-bundle`), which the profile's
`MetadataFiles` and `CredentialVendor` clients trust beside the JVM's CAs. The audit archive builds a third S3
client from a storage profile (`nuna.audit.archive.profile`) in `GovernanceWiring.archive`, and that one still trusts
only the JVM's CAs: an archive bucket on an on-prem store with a private CA fails its startup `checkBucket` with a
TLS handshake error. Item 21 left it alone because item 19 held `governance/` that night.

Done when `GovernanceWiring.archive` reads the profile's bundle (`CaBundle.read`, refusing an unreadable one with
the setting's name, as `CatalogStorage.caBundle` does) and applies it to its `S3ClientBuilder`
(`CaBundle.applyTo`); a Java-only test against `PrivateCa.serve` (catalog-tests-s3) shows the archive's client
refused without the bundle and accepted with it; and a bite is recorded.

### 88. Compaction Jobs do not trust a storage profile's CA bundle

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-maintenance/src/main/java/nuna/maintenance/KubernetesJobs.java catalog-maintenance/src/test/java/nuna/maintenance/ deploy/helm/nuna-maintenance/ docs/install-at-work.md
- Effort: M, mount the bundle into each Job and make Spark's S3 clients trust it, with a test of the Job's spec; after Paul decides

Item 21 makes the worker's own S3 clients trust a profile's `ca-bundle`. A compaction Job (`KubernetesJobs`) is
Spark in its own pod, reading and writing the table with the credentials the catalog vends it, and it gets no
bundle: on an on-prem store whose certificate a private CA signed, it fails its first S3 call with a TLS
handshake error. Item 21 treats the Job as an engine, which trusts the store through its own setup, and
`docs/install-at-work.md` (section 6) says its image must trust the CA itself.

The choices, Paul's:

- Leave it to the image, as item 21's docs say: whoever builds the Spark image at work adds the CA to its JVM's
  trust store.
- Have the worker carry it: mount `storageCaConfigMap` into each Job's pod and point Spark's S3 clients at it, for
  example with `s3.client-factory-impl` and item 21's `TrustedS3Clients` on the image's classpath, or a JVM trust
  store built from the PEM at the pod's start.

Done when Paul has chosen and a compaction Job on a store with a private CA can read and write its table, or the
docs say plainly that the image must trust the CA.

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
| [10](backlog/10.md) | DR reconcile: prove the accepted tips from the audit archive | done | 2026-11-20 |
| [11](backlog/11.md) | The purge rate limit is per replica | done | 2026-11-19 |
| [12](backlog/12.md) | A revive can bring an object back in a namespace it later left | done | 2026-11-19 |
| [13](backlog/13.md) | The separation-of-duties check and the grant write are not atomic | done | 2026-11-20 |
| [14](backlog/14.md) | HEAD and loads answer 404 before 403, so names leak to signed-in callers | done | 2026-11-19 |
| [15](backlog/15.md) | ETags do not survive a change of server (Paul decides) | done | 2026-11-20 |
| [16](backlog/16.md) | May the console decline vended credentials? (Paul decides, D21) | done | 2026-11-19 |
| [18](backlog/18.md) | Browser end-to-end tests for the console | done | 2026-11-15 |
| [19](backlog/19.md) | Cap the audit-read route for scripted use | done | 2026-11-20 |
| [20](backlog/20.md) | Catalog names are listed to every signed-in caller (a question for work) | done | 2026-11-19 |
| [21](backlog/21.md) | A CA bundle per storage profile | done | 2026-11-20 |
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
| [68](backlog/68.md) | Catalog names still leak through /v1/config and the unknown-prefix 404 (Paul decides, D18) | done | 2026-11-20 |
| [69](backlog/69.md) | The DR reconcile's apply waits within the retry budget when the store is down, as the commit engine does | done | 2026-11-20 |
| [71](backlog/71.md) | Export the audit publish timer's histogram, so Grafana can show the Kafka acknowledgement p99 | done | 2026-11-20 |
| [72](backlog/72.md) | Oracle's self-check does not notice a journal-partition job that cannot run | done | 2026-11-20 |
| [73](backlog/73.md) | Namespace, table and view names still leak through the management API | done | 2026-11-20 |
| [74](backlog/74.md) | HealthMeters keeps its own copy of the audit publish buckets and still says the exported timer has none | done | 2026-11-20 |
| [75](backlog/75.md) | deploy/README.md and install-at-work.md still call the ETag routing choice open | done | 2026-11-20 |
| [76](backlog/76.md) | The overnight load tests expect every conditional load to be a 304, but the shared epoch changes every window | done | 2026-11-20 |
| [77](backlog/77.md) | rest/Guard keeps its own copy of the rule security/Holdings now holds; TelemetryTest and table-health.md still say an outsider gets 403 on the management API | done | 2026-11-20 |
| [78](backlog/78.md) | Paul decides: whether the DR routes and the routes by object id hide names and ids from a caller that holds nothing | done | 2026-11-20 |
| [80](backlog/80.md) | NunaPurgesFailing and NunaDeletionsFailing wait two scan intervals before firing | done | 2026-11-20 |
| [81](backlog/81.md) | The chart's values.yaml still says only the minting server answers a 304 | done | 2026-11-20 |
| [82](backlog/82.md) | /v1/config without a warehouse names the default catalog to a caller that holds nothing on it (Paul decides, D18) | done | 2026-11-20 |
| [83](backlog/83.md) | The DR reconcile moves a table to metadata whose manifests or data may not have reached the DR store | done | 2026-11-20 |
| [84](backlog/84.md) | An engine whose own principal holds nothing can no longer read /v1/config: the demo's Trino among them (Paul decides, D17, D18) | done | 2026-11-20 |
