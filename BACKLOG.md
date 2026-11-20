# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**24 open:** 6 S, 12 M, 2 L, 4 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42) | 2026-11-14 |
| [17](backlog/17.md) | M | Console sign-in against Entra ID and Ping, with an automated test | checks against Entra ID and Ping at work; ending the IdP session if Paul wants it | 2026-11-14 |
| [22](backlog/22.md) | XL | Administer storage profiles and catalogs at runtime (not now, D41) | routes, storage on four backends, audit and the startup checks; only if Paul wants it | 2026-11-14 |
| [23](backlog/23.md) | M | Per-request SSE-KMS keys (waits on SEC-8) | the per-profile key is built (2026-11-20); mapping keys to domains waits on SEC-8 (work), and its size on the answer | 2026-11-14 |
| [24](backlog/24.md) | L | Kubernetes deployment: what was not checked (at work) | five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing | 2026-11-14 |
| [25](backlog/25.md) | M | One hot table under contention: throughput and fairness (measure at work) | a measuring run on work hardware; a change only if Paul wants one | 2026-11-14 |
| [26](backlog/26.md) | XL | Branches, tags and merge (v1.x, D33) | a v1.x milestone | 2026-11-14 |
| [28](backlog/28.md) | XL | DR replication-watermark verifier (v1.x) | the verifier, the watermark on four stores, its readout and the rewind (Paul chose the rewind now, 2026-11-20); item 83 folded in | 2026-11-14 |
| [29](backlog/29.md) | XL | M9: carry nuna to work (at work) | the whole install checklist, at work | 2026-11-14 |
| [43](backlog/43.md) | L | Hook nuna into work's OpenTelemetry collector and Prometheus (at work) | findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval | 2026-11-15 |
| [44](backlog/44.md) | M | Grafana dashboards and Prometheus alert rules for nuna (at work) | at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43 | 2026-11-15 |
| [55](backlog/55.md) | S | The nightly lost two tests to one 15 s stall reaching Docker's containers | waits on an overnight run in which both tests pass | 2026-11-16 |
| [60](backlog/60.md) | S | CredentialExpiryTest's first request answered 401 | the reason was in the test's output; one timeout and a Java-only test | 2026-11-18 |
| [70](backlog/70.md) | M | A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After | two heavy Spring tests in one module, each needing its database behind a link it can cut; written, waits on an overnight heavy build in which both pass | 2026-11-19 |
| [79](backlog/79.md) | S | deploy/local runs the catalog as its own non-root S3 user, so the except delete policy is applied and checked | one S3 user, its policies and the catalog's keys in deploy/local, checked by a local install | 2026-11-20 |
| [89](backlog/89.md) | M | A compaction Job against a store behind a private CA, end to end | a TLS store with a private CA beside the existing Kubernetes compaction test, and one overnight test that runs a real Job | 2026-11-20 |
| [92](backlog/92.md) | M | DR verifier: the worker chart's values, its settings in the worker README and install-at-work, and the drill's leg with the DR store behind | the chart comments and the two docs are an hour; the drill leg is a day: a second Silo store, a replicator the test pauses, the verifier run in DR, and an overnight-only test that can be checked only overnight. | 2026-11-20 |
| [93](backlog/93.md) | S | The DR drill's appender names manifest lists that never exist, so the walk with a file check stops at its first commit | the appender writes a valid, empty manifest list (or a real manifest list and manifest) for each snapshot it adds before it commits, and the drill runs overnight; waits on item 92, which holds DrDrillTest | 2026-11-20 |
| [96](backlog/96.md) | M | A per-client S3 endpoint in the storage profile, for engines outside the cluster (Paul decides) | a storage-profile setting and the vending that chooses between two addresses, with tests; waits on Paul | 2026-11-20 |
| [97](backlog/97.md) | M | A KMS key for MinIO and Ceph storage profiles (D26) | a KMS beside the Silo and Ceph test stores, the key id each takes, and whether their STS session policies enforce the SSE condition keys; waits on Paul if on-prem keys are not wanted yet | 2026-11-20 |
| [98](backlog/98.md) | M | A storage profile's KMS key is not recorded with the profile, so a change of key is not audited | one more field on the stored profile in all four stores, with its migration, and the startup audit naming it | 2026-11-20 |
| [99](backlog/99.md) | M | A table that commits faster than the DR store replicates never gets a first DR watermark | the whole check finds the newest whole version from its one listing instead of testing only the pointer's, with tests; the choice of version is D37's, so Paul may want to see it first | 2026-11-20 |
| [100](backlog/100.md) | M | A rewind that gives up more than 100 versions leaves the table behind its DR watermark for good | one of the fixes below with its tests on the four stores' route suites; which one is D37's, so Paul decides first | 2026-11-20 |
| [101](backlog/101.md) | S | The DR runbook waits on the worker's log, which records a DR pass only at DEBUG | one log level or one runbook sentence, and a check that the line is there | 2026-11-20 |

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
- Effort: M, the per-profile key is built (2026-11-20); mapping keys to domains waits on SEC-8 (work), and its size on the answer

`build-plan.md`, M6, Open: per-request SSE-KMS keys and a KMS condition in the session policy. Today encryption at
rest is the bucket's default key and the catalog records only the key set's name (D26). Waits on SEC-8 (KMS
key-policy owners per domain), a work question in section 6.

Done when SEC-8 is answered and, if keys are per domain, vended sessions write with the catalog's key and the session
policy refuses any other.

**Decided 2026-11-19 (D26):** one KMS key per storage profile now ("Per-profile key now"): vended sessions must write with it and are refused any other. How keys map to domains still waits on SEC-8.

**Built 2026-11-20 (worktree-agent-a48507f072a64c9bf): the per-profile key, on AWS S3 profiles; open until SEC-8
is answered.**

- `nuna.storage.profiles.<name>.kms-key` names one key ARN (`StorageProfile.kmsKey`). The server refuses to start,
  naming the profile, on an alias, a bare key id, a key in another region or partition, or a key on a MinIO or Ceph
  profile. A profile with no key behaves as before, its session policy text unchanged.
- The vended session's policy moves PutObject (which covers CreateMultipartUpload) to a statement whose condition
  requires `s3:x-amz-server-side-encryption` = `aws:kms` and `s3:x-amz-server-side-encryption-aws-kms-key-id` = the
  key ARN, and allows the key's use: `kms:GenerateDataKey` and `kms:Decrypt` for writers, `kms:Decrypt` for readers.
- The server's metadata files are written with SSE-KMS under the key; the load response's config carries
  `s3.sse.type: kms` and `s3.sse.key: <ARN>` (Iceberg's S3FileIO; the worker and its Jobs ask for vended credentials,
  so they get it). A caller that declines delegation gets no config, so it is not told the key.
- Java-only tests: `SessionPolicyTest`, `StorageProfileTest`, `KmsKeyProfileTest` (new), `NoKmsBoundaryTest` (now
  lets `MetadataFiles` and `SessionPolicy` alone name the encryption headers). Bites `kms-condition-dropped`,
  `kms-key-unchecked`, `metadata-write-without-kms-key` and `kms-key-not-in-config` (bites/M6), each seen to bite.
- On AWS (`KmsKeyAwsTest`, heavy, AWS only, Paul's allowance of 2026-11-20 with the account's `aws/s3` key; no key
  created): vended credentials write with the key in one request and in parts, and read it back; they are refused
  (AccessDenied) a write with no encryption header and with SSE-S3, and read credentials any write; the server's
  metadata write is encrypted with the key. Two things S3 does that the first runs showed: a write naming SSE-KMS
  but no key is let through, because S3 checks the condition against the key it will use (here `aws/s3`, the
  profile's key); and S3 looks a named key up before it asks the policy, so a key ARN that does not exist is refused
  by S3 (400), not the policy.

Not shown, for want of a second key (Paul allowed none): that a write naming another existing key is refused by the
policy (the condition's text is checked in `SessionPolicyTest`), and that the policy's KMS statement suffices for a
customer-managed key whose key policy defers to IAM (`aws/s3`'s policy lets every principal in the account use it
through S3). Left to items 97 (a key on MinIO and Ceph profiles) and 98 (the key is not recorded with the profile,
so a change of key is not audited). SEC-8 (keys per domain) is a work question.

**Paul, 2026-11-20 (D26, amended):** no customer-managed test key for now ("Not now"); the refusal of another
real key and the KMS grant for a customer-managed key are checked at work.

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

### 28. DR replication-watermark verifier (v1.x)

- Decided: 2026-11-20, D37
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

**Built, 2026-11-20 (worktree-agent-a84494e1252023a8f); open until item 92 lands, which holds what is left.** The
scope's "In", but the worker chart and the overnight drill leg:
- The watermark on all four stores: `recordDrWatermark` (only rises, never above the pointer, active objects only),
  `listDrBehind`, purged with its object; PostgreSQL V9, CockroachDB V8, Oracle V10, MongoDB step 8; `DrSuite` in each
  store suite (heavy).
- The routes: `GET /management/v1/maintenance/dr-work` and `PUT .../maintenance/tables/{id}/dr-watermark` for the
  maintenance principal, `GET /management/v1/dr/watermarks` for platform operators (`DrRoutes`, `DrRoutesTest`).
- The verifier in the worker (`DrVerify`, `DrVerifier`, `DrStores`): every `nuna.maintenance.dr.verify-interval`
  when `nuna.maintenance.dr.replicas.<profile>` names a DR store; every file a version adds, data included; a whole
  check in one listing for a table with no watermark (`DrVerifyTest`, `DrVerifierTest`, `DrStoresTest`).
- Meters `nuna.maintenance.dr.*`, alerts `NunaDrLagging` (past 15 minutes) and `NunaDrVerifierNotPassing`.
- The rewind (`dr-reconcile` with `"rewind": true`) and the walk stopping at the watermark (item 83):
  `ReconcileWatermarkTest`, `DrRewindRouteTest` (heavy).
- Docs: `docs/observability/dr-watermark.md`, worker-metrics.md, dashboards-and-alerts.md, the DR runbook in
  `deploy/README.md`. Bites in `bites/D37/`: data files left out, a lower watermark accepted, a rewind without a
  freeze, a rewind past the watermark, the walk past the watermark.

Left (item 92): the worker chart's values comments, the settings in `catalog-maintenance/README.md` and
`docs/install-at-work.md` (items 87 and 88 held those files), and `DrDrillTest`'s leg with the DR store behind.

Two choices made in building it, for Paul to confirm:
1. A version that adds nothing to the newest safe version (no snapshot or statistics file it lacks) is safe on its
   metadata file alone, even after a version that is not. Without it the version a rewind writes could never pass,
   since it follows the versions it gave up.
2. The walk stops at the watermark literally: a table with a watermark never walks forward (its watermark is never
   above its pointer), so commits the DR store holds whole beyond the DR database's pointer are not recovered; a
   table with no watermark walks as before. The rewind is for tables; views are neither walked nor rewound, as
   before.

**Paul's answers to the questions above, 2026-11-20 (D37, amended again).** Left for item 28, on top of what landed
at d176a3b:
1. "Walk with a file check": the reconcile walks every table forward from the DR database's pointer, watermark or
   not, only through versions whose every added file, data included, is in the DR store, checked at recovery,
   stopping at the first that is not. This replaces "stops at the watermark" and covers the tables with no
   watermark, which walk unchecked today. `bites/D37/walk-past-the-watermark` is recorded again for the new check.
2. "Accept it": the relaxed safe-version rule stays as built.
3. "Rewind views too": `dr-reconcile`'s rewind moves each frozen view back to its watermark as it does tables,
   with a Java-only test and a bite.
4. "Owner decides": a table still without a watermark is reported and left alone, as built.
Done when 1 and 3 are built with Java-only tests and bites, and the heavy and overnight tests named above pass.

**Built, 2026-11-20 (worktree-agent-a34b673c32ed6d84d): answers 1 and 3; open until the heavy and overnight tests
pass.**
- "Walk with a file check": `Reconcile.plan` walks every table as before, then checks each step in the store the
  server runs on (the DR store, in DR): every file it adds to the version before it, data included, must be there; it
  keeps the steps before the first that lacks one, takes no tip, and names the missing file. The watermark plays no
  part in the walk any more. What a version adds is counted once, by `AddedFiles` in catalog-iceberg, for the worker's
  `DrVerify` and for the walk: catalog-iceberg is the one catalog module that may import iceberg-core (D2,
  `IcebergImportBoundaryTest`), so the worker (an Iceberg client, D30) and catalog-core both use it, and no boundary
  changed. `TableFiles` gains `exists` and `bytes` (one `HEAD`; a whole read), in catalog-objectstore and the server's
  traced wrapper (spans.md lists both). Tests: `ReconcileFileCheckTest` (new, Java-only, an Iceberg client's table in
  memory), `ReconcileWatermarkTest`'s walk cases rewritten; `MetadataFilesTest#anyFileIsFoundAndRead` (heavy).
- "Rewind views too": `dr-reconcile`'s rewind lists and moves views as tables, under `views` (`views-checked`),
  reading each as its kind's metadata (`Metadata.viewFacts`). Tests: `ReconcileWatermarkTest#aFrozenViewIsRewoundToItsWatermark`
  (Java-only); `DrRewindRouteTest#aFrozenViewAboveItsWatermarkIsRewoundToIt` (heavy).
- Bites in `bites/D37/` recorded again against the moved code with the same breaks (`data-files-left-out` now in
  `AddedFiles`, `rewind-past-the-watermark`, `rewind-without-freeze`), `walk-past-the-watermark` recorded again for
  the file check, and `views-not-rewound` added.
- Docs: `docs/observability/dr-watermark.md`, the runbook in `deploy/README.md` (rewind before walking; the walk now
  covers every table), spans.md, build-plan.md.

Left: the heavy tests (`DrRewindRouteTest`, `MetadataFilesTest`, `DrSuite`, `ManagementAuthorizationTest`,
`MutatingRoutesAuditTest`, `DrReconcileRouteTest`) and the overnight `DrDrillTest` have not run; the overnight run takes them once this is on
`main`. `DrDrillTest` will fail its dry run until item 93 makes its appender write the manifest lists its snapshots
name; item 92 still holds the drill's leg with the DR store behind.

**Paul, 2026-11-20, on the walk's start (D37, amended again):** "Check from the watermark". The file check landed at
9d801ee starts at the DR database's pointer, which may sit above the watermark on a version whose own files never
reached DR. Left: the walk checks every version from the table's watermark up, those at or below the pointer
included; if any between the watermark and the pointer is missing a file, it takes nothing for that table and
reports it for the rewind. A table with no watermark walks from its pointer, as built. Java-only tests and a bite (the check starting at the pointer).

**Built, 2026-11-20 (worktree-agent-ac1ed31ffd65b1122): "Check from the watermark"; open until the heavy and
overnight tests pass.**
- `Reconcile.plan` checks a table whose pointer is above its DR watermark before walking it: each version the DR
  database's journal records above the watermark, the pointer's included, must hold every file it adds to the version
  before it (the watermark's, for the first), its own metadata file among them, counted by `AddedFiles` as the steps
  are. If one lacks a file, the plan takes nothing (no step, no tip) and `problem` names the file and says to rewind
  the table to its watermark. A table at its watermark or with none walks from its pointer, as built.
- The versions come from the journal, the records the verifier's work list and the rewind's `lost` tail read, not
  from the metadata log or the archive: the journal is written with the pointer in one transaction, so the DR
  database holds every version up to its pointer, while a metadata log is trimmed and a rewind's file repeats the
  watermark's log, which skips the versions it gave up. When a version cannot be found, nothing is taken and
  `problem` says why: a version the journal does not hold (the versions must run one by one up to the pointer), or
  the watermark's file missing from the store, "cannot be checked"; a version's metadata file missing from the store
  is a missing file like any other ("storage does not hold the pointer's file ..." for the pointer's).
- Tests, Java only, in `ReconcileFileCheckTest`: a whole stretch walks on and applies; a stretch missing a file
  (a data file a later version deletes, a manifest list, a version's metadata file, the pointer's) takes nothing and
  is reported; a pointer at its watermark, and no watermark, walk from the pointer as before; a journal gap or a
  missing watermark file takes nothing. Its watermark-blind case is replaced; in `ReconcileWatermarkTest` only the
  Javadoc and one display name change.
- Bite `bites/D37/check-from-the-pointer` (the check skipped, so it starts at the pointer again): with it applied the
  two stretch tests failed on assertions and no other catalog-core test did. The other D37 bites apply unchanged.
- Docs: the runbook in `deploy/README.md` (step 5: the check from the watermark; rewind before walking stays the
  order, no longer the only guard) and `docs/observability/dr-watermark.md`.

Left: the heavy tests named above and the overnight `DrDrillTest` have not run. `DrRewindRouteTest`'s forward walk now
stops in the new check, whose message for the pointer's own file keeps the words it asserts ("storage does not hold
the pointer's file"). Question for Paul, not decided: a table rewound in step 4 of the runbook is reported by the walk,
with nothing taken, until the verifier passes its rewind's version, since the versions it gave up lie between its
watermark and its pointer and lack files (nothing builds on the rewind's file, so there is nothing to take for it).
The runbook now says to run the verifier again before walking. Or the walk could judge the versions as the verifier
does ("Accept it": a version adding nothing beyond the newest whole one is whole), and a rewound table would walk at
once.

**Paul, 2026-11-20, after the check from the watermark landed (D37, amended again):** "Judge like the verifier":
the walk judges the versions between the watermark and the pointer as the verifier does, so a pointer version that
adds nothing beyond the newest whole one (the version a rewind writes) counts as whole and the table walks at once,
with no second verifier pass; the runbook drops that pass. Left for item 28, with item 100.

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
- Touches: catalog-security/, catalog-server/src/test/java/nuna/catalog/server/CredentialExpiryTest.java, bites/D16/
- Effort: S, the reason was in the test's output; one timeout and a Java-only test

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

Fixed 2026-11-20 on worktree-agent-a919c80972fe867d3: the cause was a JWKS fetch that Nimbus gave up on after its default 500 ms read timeout, in a test JVM slowed
by the build beside it. The test's output did say why: its one `audit.fallback` line is the auth-failure event, whose
reason is "token from https://idp.test/keel rejected: An error occurred while attempting to decode the Jwt: Couldn't
retrieve JWK set from URL: Read timed out".
- The first request of a fresh Spring context fetches the test issuer's keys from `TestIdp`, a JDK `HttpServer` in
  the same JVM (`IssuerForTests`). `TokenAuthenticator` built its JWKS source with `JWKSourceBuilder.create(url)`,
  whose retriever waits 500 ms to connect and 500 ms to read, with no retry. A fetch that times out refuses the token
  as invalid: 401, `Bearer error="invalid_token"`.
- The JVM was slow: that night `CredentialExpiryTest`'s context took 30.8 s to start (12.7 s for the web context),
  against 5.3 s (1.1 s) on 2026-11-16, and the request reached Tomcat 3.3 s after the context started. Beside it,
  `catalog-maintenance`'s `KubernetesCompactionTest` ran Spark Jobs in Docker Desktop's Kubernetes from 05:09:19 to
  05:15:09 UTC (350 s, failed), and `TelemetryTest` there missed a 20 s wait. On 2026-11-19 the two overlapped again:
  the test's context took 25.3 s and it passed. The same refusal, "Couldn't retrieve JWK set from URL: Read timed
  out", is in the nightly `LargeExpiryTest` of 2026-11-19 (catalog-maintenance's `TestCatalog`, also a `TestIdp`),
  which passed regardless.
- Fixed in `TokenAuthenticator`: a JWKS fetch waits up to 5 s for the IdP's answer (`JWKS_READ_TIMEOUT`); connecting
  keeps Nimbus's 500 ms, and the cache, refresh-ahead and rate limit are unchanged. A slow IdP at work would have had
  the same effect on the first request to each node, and on each key rotation.
- `JwksRefreshTest#aSlowJwksIsWaitedForNotTakenForAnInvalidToken` (Java only): `TestIdp.slowFetches` answers after
  1 s; the token is accepted after one fetch. Without the fix it failed with the overnight's message (fast build of
  2026-11-20, 47 tests in catalog-security, 1 failed). Bite: `bites/D16/jwks-read-timeout-500ms.patch`.
- `CredentialExpiryTest`'s status assertions now carry the response: status, `WWW-Authenticate` and body, and on a
  401 a pointer to the `nuna.audit.fallback` line, since the body never names the reason by design.
  `IssuerForTests` sends no requests, so it has nothing to report.
- Since item 90 (2026-11-20), a JWKS fetch that fails or times out answers 503 with `Retry-After`, not 401
  `invalid_token`: the 401 above is what such a fetch gave before. A 503's body says the issuer's keys could not be
  fetched.

Left: `CredentialExpiryTest` is tagged heavy since D44, so it did not run here; the overnight run's heavy build
shows whether it passes.

The item stays open until an overnight run's heavy build passes `CredentialExpiryTest`, as its done-when asks (the test is heavy since D44 and did not run on 2026-11-20).

### 70. A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After

- Added: 2026-11-19 by agent-abefa39293b4b238c, from item 7
- Touches: catalog-server/src/test/, catalog-server/build.gradle.kts, bites/M8/
- Effort: M, two heavy Spring tests in one module, each needing its database behind a link it can cut; written, waits on an overnight heavy build in which both pass

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

Written on 2026-11-20 (worktree-agent-af0dfeed53e61ec78), not yet run; open until both pass in an overnight run:

- `OracleBackendTest` and `MongodbBackendTest` put their database behind Toxiproxy on a Docker network, as `OracleFree`
  and `MongoReplicaSet` do, and the server connects through it; setup goes to the database directly.
  `LinkForTests` is that proxy for the server's tests. The store fixtures themselves could not be shared: they are
  package-private in their modules' tests, which no other module depends on (only `catalog-store-api`'s suite is a
  test jar), and those modules are item 28's. What the build does share is reused: the
  `testcontainers-toxiproxy` dependency and the pinned `toxiproxy.image`, now in `catalog-server/build.gradle.kts`.
- Each class's new test `anOutageAnswers503WithRetryAfterUntilTheLinkIsBack` runs `BackendFlow.outage`: a new table,
  then with the link cut a load and a commit (set-properties) each answer 503 `ServiceUnavailableException`,
  `Retry-After: 2`, `application/json`, `$.error.code` 503. Then, the link back, the load (repeated on Retry-After,
  two minutes at most, as a client would while the pool or driver reconnects) answers 200 without the property, so
  the 503 commit applied nothing; the commit answers 200 with it, and a reload shows it. The server's pool and driver
  keep their defaults, so each 503 can take their 30 s wait (HikariCP's connection timeout, MongoDB's server
  selection).
- `StoreOutageRoutesTest` (Java only, `catalog-server` had none): the real table routes, commit engine and
  `ErrorResponses`, served through MockMvc, over a store double that raises `StoreUnavailableException`. With the
  database away, a load and a commit answer 503 with Retry-After in the Iceberg shape at their first read; lost
  mid-request, a load meets it at its pointer read (`ErrorResponses`) and a commit inside the engine
  (`CommitReply.Unavailable`, `ObjectRoutes.reply`). Bites: `bites/M8/store-outage-escapes-routes.patch` (the
  handler gone: 500) and `bites/M8/commit-unavailable-answers-500.patch`. With both breaks applied, the fast build
  failed exactly those three tests, each with "expected: 503 but was: 500" (143 tests in `catalog-server`, 3 failed).

Left: the two heavy tests did not run (no heavy build on 2026-11-20); the overnight run's heavy build shows whether
they pass. The item stays open until it does.

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

### 89. A compaction Job against a store behind a private CA, end to end

- Decided: 2026-11-20, D21
- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-maintenance/src/test/ catalog-maintenance/README.md catalog-tests-s3/ bites/M6/
- Effort: M, a TLS store with a private CA beside the existing Kubernetes compaction test, and one overnight test that runs a real Job

Items 21 and 88 made a storage profile's `ca-bundle` reach every S3 client the server and the worker build, and
compaction Jobs: the worker copies the bundle into a ConfigMap owned by each Job, and the Job's command builds a JVM
trust store from the image's JDK CAs and the bundle and points Spark's driver at it. No test runs a compaction Job
against a store behind a private CA. `KubernetesJobsTest` checks the Job's spec only; `KubernetesCompactionTest`
(heavy) and the engine matrix's compaction leg (overnight) use stores with public or no TLS; the trust-store
command was run by hand once, on a Windows JDK, not in the Spark image (item 88's done note).

Paul, 2026-11-20, asked whether to add this: "Add it, here", on Docker Desktop with a TLS store, as an overnight
test.

Done when an overnight test (`@Tag("overnight")`) starts the S3 store with TLS from a private CA (`PrivateCa` makes
one), declares a storage profile naming that `ca-bundle`, runs a compaction Job on Docker Desktop's Kubernetes in the
real Spark image, and checks the Job finished and committed; and a bite (the bundle left out of the Job) makes it
fail on an assertion, not on fixture start-up.

Worked on 2026-11-20 (worktree-agent-a0c19fa4b1e97ee01); open until the test passes in an overnight run and its bite
bites. **Nothing of it has run**: the test is tagged overnight, Docker Desktop's Kubernetes has no room for its Jobs
beside the nuna-demo stack, and no container was started. The fast build compiles it and passes.

- `PrivateCaCompactionTest` (catalog-maintenance, `@Tag("overnight")`): Silo serving TLS from a private CA, a catalog
  of its own on it whose storage profile names the CA's bundle, the worker's profile naming it too, and the chart's
  Role as the chart renders it for a profile with a `ca-bundle` (create on ConfigMaps). First a control: the Job
  launched without the bundle must fail with `PKIX path building failed` in its log and leave the table's snapshot.
  Then the worker's own scan launches the Job with the bundle, which must succeed (`COMPACTED`, no PKIX error), with
  the bundle's ConfigMap owned by that Job, and the table must end with one data file of 60 rows in a `replace`
  snapshot.
- How the pods reach the store's TLS name: the catalog's twin for pods (`TestCatalog.twinForPods`) names the store
  `https://host.docker.internal:<port>`, which Docker's DNS resolves for pods and `HostDockerInternal` resolves to
  loopback in the test JVM. The store's certificate (`PrivateCa.issue`) holds `localhost`, `127.0.0.1`, the Docker
  host's address and `host.docker.internal` as subject alternative names, so the pod's TLS client, the twin's own STS
  and S3 clients and the test's all find their host in it.
- `TestS3.siloBehindTls(ca)`: a second Silo, for this test alone, apart from the run's store and whatever `-Ps3`
  names, with the issued certificate and key in `--certs-dir /certs` and a TLS health check. Its own S3 clients trust
  the CA; `serverProperties` leaves `require-tls` on and names the `ca-bundle`. The run's store is unchanged.
- `TestCatalog.onStore(s3)`: a catalog server on that store and on a new database in the shared PostgreSQL, so its
  work list holds this test's table alone. The writer's S3 clients and the worker's `Catalogs` now trust what the
  worker's storage trusts (`TrustedS3Clients.register`, `Storage.clientProperties`): nothing changes without a bundle.
- `KubernetesCompactionTest`'s harness (the image build, the two namespaces, the chart's service account and Role, the
  worker's service-account folder, kubectl and helm) moved unchanged into `CompactionCluster`, which both tests use;
  its `avro` moved to `TestCatalog`. `KubernetesCompactionTest` checks what it did before.
- Bite, recorded and checked with `git apply --check`, not run: `bites/M6/job-storage-ca-left-out` (`storageCa`
  finds no bundle, so the Job gets no ConfigMap, mount or trust store). The control is unaffected, and the worker's
  Job then fails as the control does, on the assertion of its status.
- Unchecked until it runs: that Silo serves TLS from `--certs-dir` with this EC key in PKCS #8 (read in Silo's
  source, `cmd/common-main.go`, not run); that the Spark image's `$JAVA_HOME/bin/keytool` and its cacerts work as item
  88's command expects; the wording of the TLS error in the Spark log.

### 92. DR verifier: the worker chart's values, its settings in the worker README and install-at-work, and the drill's leg with the DR store behind

- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: deploy/helm/nuna-maintenance/, catalog-maintenance/README.md, docs/install-at-work.md, catalog-tests-load/src/test/java/nuna/catalog/load/DrDrillTest.java, deploy/README.md, docs/observability/dr-watermark.md
- Effort: M, the chart comments and the two docs are an hour; the drill leg is a day: a second Silo store, a replicator the test pauses, the verifier run in DR, and an overnight-only test that can be checked only overnight.

Item 28 built the DR replication watermark (D37, amended 2026-11-20): the watermark on all four stores, the
verifier in the maintenance worker, its routes, readout, meters and alerts, the rewind, and the reconcile stopping at
the watermark. Three parts were left:

- **The worker chart's values** (`deploy/helm/nuna-maintenance/values.yaml`) do not describe
  `nuna.maintenance.dr.replicas.<profile>` (endpoint, region, path-style access, keys, CA bundle) or
  `nuna.maintenance.dr.verify-interval`. The chart already renders them, as any setting under `config`, with the
  secret key in `secrets.values` and a CA bundle in `storageCaConfigMap`, as `deploy/README.md`'s runbook says; items
  87 and 88 held the chart while item 28 ran. Done when the values file comments them as it does `storage`.
- **The worker's settings in `catalog-maintenance/README.md` and `docs/install-at-work.md`** (held by item 88 then):
  the DR replicas and the verify interval, the DR store's read-only keys, and that the verifier is off without a
  replica. Done when both list them.
- **The overnight drill's leg with the DR store behind** (item 28's gate): `DrDrillTest` gains a leg on two Silo
  stores and a replicator the test pauses: region lost, DR promoted, a verifier pass in DR, freeze, rewind, thaw;
  every table reads whole in DR, and the lost tail is exactly the commits whose files never arrived. Done when the
  leg is in and an overnight run has passed it.

## Progress, 2026-11-20 (worktree-agent-abb88ef3bcbfecca6), still open

- **The chart's values.** `deploy/helm/nuna-maintenance/values.yaml` comments `config.nuna.maintenance.dr` as it
  does `storage`: `verify-interval` (PT1M), and a commented `replicas.<profile>` example (endpoint, region,
  path-style-access, the read-only `access-key-id`, `ca-bundle`), the verifier off without a replica, the secret key
  in `secrets.values` as `nuna.maintenance.dr.replicas.<profile>.secret-access-key`, a DR store's CA in
  `storageCaConfigMap`. `replicas` stays commented rather than `{}`, so a release with no replica renders no empty
  map. `helm template` (helm 3 from winget) rendered the chart with and without a replica, exit 0 both; with one, the
  ConfigMap holds `dr.replicas.ceph.*` and the Secret the replica's key; `helm lint` passed.
- **The worker README.** `catalog-maintenance/README.md` has a section "The DR verifier", the `dr.*` settings in its
  table, the DR store's read-only keys under "The identity it needs" (`s3:ListBucket` on each replicated bucket,
  `s3:GetObject` beneath the prefixes), the chart's DR values and secret key, and the DR tests.
- **install-at-work.** Not changed: `docs/install-at-work.md` is held by item 23. The bullet for its section 6, for
  the session that lands this to add:

  > - The DR verifier (D37, amended 2026-11-20) is off until `dr.replicas.<profile>` names, for a storage profile,
  >   its DR store: the same buckets and paths on the DR region's endpoint (`endpoint`, `region`,
  >   `path-style-access`, `access-key-id`, `ca-bundle`; the secret key in `secrets.values` as
  >   `nuna.maintenance.dr.replicas.<profile>.secret-access-key`, a private CA's bundle in `storageCaConfigMap`). Give
  >   it keys of its own that may only read the DR store: `s3:ListBucket` on each replicated bucket and
  >   `s3:GetObject` beneath the profile's prefixes. `dr.verify-interval` sets how often it passes (1 minute by
  >   default). An AWS DR bucket under another name waits on PLT-4. `deploy/README.md` ("Recovering in the DR
  >   region") says when to run it in each region.

- **The drill's leg.** `DrDrillTest#aRewindGivesUpExactlyTheCommitsWhoseFilesNeverReachedTheDrStore` (overnight,
  Silo only): a stack with a second Silo of its own as the DR store (`Stack.withDrStore`, `TestS3.siloOfItsOwn`),
  the primary bucket copied there by `Replicator`, a thread the test pauses, and a DR user that may only read. The
  maintenance worker runs from its runnable jar (`catalog-maintenance`'s new `execJar` configuration). Tables a, b,
  c (c format 3) and a quiet one; one paced writer each, appending through `Appender` with the store client, so each
  snapshot's manifest list exists (item 93). The verifier runs in the primary region; the test waits for each
  table's first watermark before the writers start (item 99), pauses the replicator, lets the writers run on, and
  computes from what was copied which commits reached the DR store whole. It then waits until the primary's
  watermarks stop exactly at the last whole version (failing at once on one above it), loses the region, promotes
  DR, and in DR: servers on the DR store, freeze, the worker on the DR store (two passes, none failed, watermarks
  unchanged), the rewind's plan (exactly a, b, c; each `lost` tail exactly the commits whose files never arrived, in
  order, by `m8-writer`), the rewind (each table at the version after its pointer, main at the last kept commit),
  the verifier passing the rewound versions, the walk (`{"apply":false}` and `{"apply":true}`: nothing listed,
  nothing unknown, no pointer moved), thaw. Then every table reads whole from the DR store as an engine reads it
  (metadata, every manifest list, manifest, data and delete file), its history is exactly the kept commits, and a
  commit to each succeeds. Its lost tails stay under the work list's 100 versions (item 100). The expectations
  follow D37 "Check from the watermark" as landed by item 28 (5df8b5d): the walk runs after the verifier's pass
  over the rewound versions, as the runbook's step 5 says.
- **Not run.** The leg is tagged `overnight` and has not run anywhere: it compiles in the fast build, which starts
  no container. The first leg changed only to give its appenders the store client and its stack per test.

Left: an overnight run passing both legs of `DrDrillTest`, and the install-at-work bullet above. For
`deploy/README.md` (not this branch's), once this lands: "the chart's values do not describe them yet (item 92)"
becomes "`values.yaml` comments them", and the drill sentence at the runbook's end can say the leg with the DR
store behind runs the verifier in both regions, the rewind and the walk.

### 93. The DR drill's appender names manifest lists that never exist, so the walk with a file check stops at its first commit

- Added: 2026-11-20 by agent on item 28
- Touches: catalog-tests-load/src/test/java/nuna/catalog/load/Appender.java catalog-tests-load/src/test/java/nuna/catalog/load/DrDrillTest.java
- Effort: S, the appender writes a valid, empty manifest list (or a real manifest list and manifest) for each snapshot it adds before it commits, and the drill runs overnight; waits on item 92, which holds DrDrillTest

Item 28 (D37, amended 2026-11-20: "Walk with a file check") made the DR reconcile's walk take a step only when
storage holds every file it adds: for each snapshot, its manifest list, the manifests and data files it adds. The
overnight DR drill (`DrDrillTest`, catalog-tests-load) writes its tables through `Appender`, whose commits add
snapshots naming `<table>/metadata/snap-<id>.avro` as their manifest list (`Appender.body`, the `add-snapshot`
update) without ever writing that file. So the walk now stops before the first appended version, with
"storage does not hold .../snap-<id>.avro, which version n adds", and the drill's dry run fails its
`assertThat(plan.has("problem")).isFalse()`, as do the steps it expects after.

Found while building item 28 on 2026-11-20 (worktree-agent-a34b673c32ed6d84d); not run, since the drill is an
`overnight` test, so this is read from the code, not from a failure. `Appender` is also used by the other load tests
(`ConcurrentWritersTest`, `KillPrimaryTest`, `SloTest`, `RollingUpgradeTest`, `TenThousandSnapshotsTest`), which never
call the reconcile, so they are unaffected either way.

Done when the drill's appended snapshots name manifest lists that exist and parse (an empty manifest list is enough:
it adds no manifest and no data file), and `DrDrillTest` passes in the overnight run with the item 28 walk.

## Progress, 2026-11-20 (worktree-agent-abb88ef3bcbfecca6), still open

- `Appender` takes the table's store client (a fifth constructor argument). Given one, before a commit's first
  attempt it writes each snapshot's manifest list, an empty Avro manifest list (`ManifestFile.schema()`, named
  `manifest_file`, with the `snapshot-id` and `format-version` metadata Iceberg writes), at the location the
  snapshot names, `<table>/metadata/snap-<id>.avro` (`Appender.manifestList`). The snapshots add no manifest and no
  data file, so nothing else is named. Its ledger also records the metadata file each acknowledged commit made
  (`Ledger.locations`), which item 92's leg uses.
- Only `DrDrillTest` passes the client. The other users (`ConcurrentWritersTest`, `KillPrimaryTest`, `SloTest`,
  `RollingUpgradeTest`, `TenThousandSnapshotsTest`) construct it as before and write no file, so what they time and
  how fast they commit is unchanged; their only change is a map insert per acknowledged commit.
- Not run: the drill is `overnight`. Left: an overnight run in which `DrDrillTest`'s dry run walks, with the
  item 28 walk.

### 96. A per-client S3 endpoint in the storage profile, for engines outside the cluster (Paul decides)

- Added: 2026-11-20 by Paul (from the install at work)
- Touches: catalog-server/src/main/java/nuna/catalog/server/storage/, catalog-objectstore/, decision-ledger.md
- Effort: M, a storage-profile setting and the vending that chooses between two addresses, with tests; waits on Paul

Raised by the same report from work as item 94 (`for-paul/pending/002-the-demo-s-object-store-on-its-own-port-for-engine.md`):
the catalog vends one `s3.endpoint` per storage profile, the demo's `http://s3:9000`, which resolves only inside the
cluster. An engine on another machine has to set `s3.endpoint` to the public address itself, after item 94 publishes
it on 8187. The report suggests a per-client endpoint in the storage profile, one address for clients in the cluster
and one for the rest, and calls it "worth a thought for the work install".

Paul decides whether the catalog should vend a second address, and if so how it tells the two kinds of client apart
(the listener a request came in on, a header the front door sets, or the client's network). Until he does, nothing
changes: outside engines set their own `s3.endpoint`.

### 97. A KMS key for MinIO and Ceph storage profiles (D26)

- Decided: 2026-11-20, D26
- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-objectstore/src/main/java/nuna/catalog/objectstore/StorageProfile.java catalog-objectstore/src/main/java/nuna/catalog/objectstore/SessionPolicy.java catalog-objectstore/src/test/ catalog-tests-s3/ docs/install-at-work.md
- Effort: M, a KMS beside the Silo and Ceph test stores, the key id each takes, and whether their STS session policies enforce the SSE condition keys; waits on Paul if on-prem keys are not wanted yet

D26, amended 2026-11-19 (item 23): "each storage profile names one KMS key now". Item 23 built it for AWS S3
profiles only: `kms-key` is a key ARN, checked against the profile's region, and the server refuses to start when a
MinIO or Ceph profile names one (`StorageProfile.requireKmsKey`: "only an AWS S3 profile names a KMS key"). On-prem,
SSE-KMS goes through the store's own KMS (MinIO's KES or a static key, Ceph RGW with Vault, Barbican or KMIP), whose
key ids are names, not ARNs, and it is not known whether MinIO's or Ceph's STS session policies honour
`s3:x-amz-server-side-encryption` and `s3:x-amz-server-side-encryption-aws-kms-key-id`, which the vended session's
condition relies on.

Done when a MinIO and a Ceph profile can name a key in the form its store takes, vended credentials for their tables
are refused a write with no key or another key on each store (a heavy test per store, as `KmsKeyAwsTest` on AWS),
and the server's metadata files are written with it; or Paul decides on-prem profiles name no key for now.

**Decided 2026-11-20 (D26, amended):** at work, once its on-prem store is known (PLT-3): "98 now, 97 at work".

### 98. A storage profile's KMS key is not recorded with the profile, so a change of key is not audited

- Decided: 2026-11-20, D26
- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-server/src/main/java/nuna/catalog/server/storage/CatalogStorage.java catalog-store-api/ catalog-store-sql/ catalog-store-oracle/ catalog-store-mongo/ catalog-audit/src/main/java/nuna/catalog/audit/StorageSetup.java
- Effort: M, one more field on the stored profile in all four stores, with its migration, and the startup audit naming it

At startup `CatalogStorage.record` writes each configured profile to the store and audits a `storage-setup`
PROFILE_SET event when the stored profile differs: kind, endpoint, STS role and allowed prefixes. Item 23 added a
profile's `kms-key` (D26), which decides the key every write to the profile's tables uses, but the stored profile
(`nuna.catalog.store.StorageProfile`) has no field for it. So a server started with a new key, or none, records and
audits nothing, and the store cannot say which key a profile used when.

Done when the stored profile carries the key, a change of key alone is written and audited as PROFILE_SET (with the
key in the event's settings), and a test on each store shows it.

**Decided 2026-11-20 (D26, amended):** done now: "98 now, 97 at work".

### 99. A table that commits faster than the DR store replicates never gets a first DR watermark

- Decided: 2026-11-20, D37
- Added: 2026-11-20 by agent on item 92
- Touches: catalog-maintenance/src/main/java/nuna/maintenance/DrVerify.java catalog-maintenance/src/test/java/nuna/maintenance/DrVerifyTest.java docs/observability/dr-watermark.md
- Effort: M, the whole check finds the newest whole version from its one listing instead of testing only the pointer's, with tests; the choice of version is D37's, so Paul may want to see it first

The DR verifier (D37, amended 2026-11-20; item 28) checks a table **whole** when it has no watermark, when its
journaled versions above the watermark do not follow it one by one, or when the watermark's metadata file is gone
from the DR store. The whole check (`DrVerify.whole`, catalog-maintenance) lists the table's location in the DR
store once and passes only if every file the **pointer's** version reaches is there. If not, the table's whole
check is deferred for `nuna.maintenance.scan-interval` (10 minutes by default; `DrVerifier.verify`, `wholeFailed`),
and the next one tests the pointer of that moment.

A table that commits more often than its newest files reach the DR store (a streaming table: the SingleStore change
feed commits every 5 seconds by default, `commit.interval.ms`) has, at each whole check, a pointer whose newest manifest list or metadata file is
still in flight, so the check fails every time and the table never gets a first watermark. It stays in the readout
with `watermark-version: null`, `NunaDrLagging` pages for it 15 minutes after its first commit, and after a disaster
the rewind cannot move it ("the table has no DR watermark"). The same holds for a busy table whose journal has a gap
above its watermark or whose watermark's file is gone, which are also checked whole.

Read from the code on 2026-11-20 while writing item 92's drill leg, which waits for each table's first watermark
before its writers start so that it does not meet this; not seen in a run.

Done when a whole check reports the newest version its one listing shows whole, not only the pointer's: for
instance the pointer's version, then each older version in its metadata log, newest first, until one is whole
(each a parse of a metadata file, the listing reused), so a table whose last few commits are in flight gets the
watermark of the newest that arrived. `DrVerifyTest` shows a table with no watermark whose pointer lacks a file
getting the version before it, and dr-watermark.md says so.

**Decided 2026-11-20 (D37, amended again):** "Newest whole version": the whole check takes the newest version
whose files are all in the DR store, from its one listing, as the table's first watermark.

### 100. A rewind that gives up more than 100 versions leaves the table behind its DR watermark for good

- Decided: 2026-11-20, D37
- Added: 2026-11-20 by agent on item 92
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/Reconcile.java catalog-server/src/main/java/nuna/catalog/server/governance/DrRoutes.java catalog-maintenance/src/main/java/nuna/maintenance/DrVerify.java docs/observability/dr-watermark.md deploy/README.md
- Effort: M, one of the fixes below with its tests on the four stores' route suites; which one is D37's, so Paul decides first

The DR rewind (D37, amended 2026-11-20; `Reconcile.rewind`) moves a table back to its watermark in one commit: the
next version, `pointer + 1`, holding the watermark's metadata again. The commits it gives up stay in the journal. The
verifier's work list (`GET /management/v1/maintenance/dr-work`, `DrRoutes`) names at most 100 journaled versions
above the watermark, oldest first (`DrRoutes.MAX_VERSIONS`). So when a rewind gives up 100 versions or more, every
version the work list names is a lost one, none of them whole in the DR store, and the rewound version is never
named. The table is not checked whole either, since its versions follow the watermark one by one (`DrVerify.Item.whole`).
Each pass checks the same lost versions, and the table stays behind its watermark for good:

- the readout lists it and `NunaDrLagging` pages for it from 15 minutes after its oldest lost commit, which is
  already older than that;
- the walk (D37 "Check from the watermark", item 28) finds the lost versions between its watermark and its pointer
  not whole and takes nothing for it, saying to rewind it;
- the rewind refuses: "the pointer ... already holds the DR watermark's metadata ...; there is nothing to rewind until
  the verifier passes it" (`Reconcile.planRewind`).

A table that lost fewer than 100 versions is fine: the verifier's next pass reaches the rewound version, which adds
nothing beyond the watermark and passes on its metadata file. Item 92's drill leg keeps its lost tails under 100 for
this reason.

Read from the code on 2026-11-20 while writing item 92's drill leg; not seen in a run. A region lost behind a
store that is minutes behind a busy table loses more than 100 commits easily.

Ways out, each Paul's to choose since each touches D37: the rewind records the watermark at the version it writes,
in the same transaction (the version is the watermark's metadata, whole by construction); or the work list names
the versions after the last rewind only, or always the pointer's too; or the verifier checks a version with no
journaled commit of its own after a rewind against the watermark directly. Done when a table that gave up more than
100 versions is at its watermark after the rewind and the next pass, in a route test on each store, and the runbook
in `deploy/README.md` says nothing about a limit.

**Decided 2026-11-20 (D37, amended again):** "Rewind sets watermark": the rewind records the watermark at the
version it writes, in the same transaction.

### 101. The DR runbook waits on the worker's log, which records a DR pass only at DEBUG

- Added: 2026-11-20 by agent on item 92
- Touches: catalog-maintenance/src/main/java/nuna/maintenance/DrVerifier.java deploy/README.md
- Effort: S, one log level or one runbook sentence, and a check that the line is there

Step 2 of the runbook in `deploy/README.md` ("Recovering in the DR region", "Verify") says to start the maintenance
worker in DR and "wait for one DR pass to end after the start (`nuna.maintenance.dr.pass.last.success`, or the
worker's log)". At the worker's default level, INFO, its log records no DR pass: `DrVerifier` logs its tally,
`DR pass done: Pass[objects=.., reported=.., verified=.., behind=.., maxLag=.., failed=.., deferred=..]`, at DEBUG,
and a failed check at WARN. In DR, where the operator may not have a metrics backend yet, the log is the easy thing
to watch. Item 92's drill leg reads that line, so its worker runs with `logging.level.nuna.maintenance=DEBUG`.

Done when either the worker logs each pass's tally at INFO (one line a minute by default, or only when the pass
reported or failed something), or the runbook names the meter alone, or the DEBUG level to set; whichever, the
runbook and what the worker logs agree.

## Done and dropped

| # | Item | Status | On |
|--:|:--|:--|:--|
| [2](backlog/2.md) | First run of ./nightly.sh (only when Paul asks) | done | 2026-11-15 |
| [3](backlog/3.md) | bites/run.sh counts a setup failure as a bite | done | 2026-11-14 |
| [4](backlog/4.md) | Apply the AWS S3 and MinIO delete policies to a real store, and run the worker on AWS | done | 2026-11-20 |
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
| [27](backlog/27.md) | Queryable audit tables (v1.x, D23) | done | 2026-11-20 |
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
| [85](backlog/85.md) | Audit tables, the server half: the readers' group as a setting, the Iceberg boundary and the engine leg (item 27's rest) | done | 2026-11-20 |
| [86](backlog/86.md) | The DR reconcile's archive proof reads each audit-archive chain whole into memory | done | 2026-11-20 |
| [87](backlog/87.md) | The audit archive's S3 client does not trust its storage profile's CA bundle | done | 2026-11-20 |
| [88](backlog/88.md) | Compaction Jobs do not trust a storage profile's CA bundle | done | 2026-11-20 |
| [90](backlog/90.md) | An issuer whose keys cannot be fetched answers 503 with Retry-After, not 401 | done | 2026-11-20 |
| [91](backlog/91.md) | On AWS, the worker's tests that build their own Storage sign in as the run's admin, which the delete policy refuses | done | 2026-11-20 |
| [94](backlog/94.md) | The demo's object store on its own port, 8187, for engines on other machines | done | 2026-11-20 |
| [95](backlog/95.md) | Change feed: opt-in singlestore.append-only for insert-only tables | done | 2026-11-20 |
