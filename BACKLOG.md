# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**48 open:** 12 S, 17 M, 14 L, 5 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes on the first overnight run whose heavy build, Ceph leg and bites are all green (D42) | 2026-11-14 |
| [17](backlog/17.md) | S | Console sign-in against Entra ID and Ping, with an automated test | signing in and out against Entra ID and Ping at work (waits on work) | 2026-11-14 |
| [22](backlog/22.md) | XL | Administer storage profiles and catalogs at runtime (not now, D41) | routes, storage on four backends, audit and the startup checks; only if Paul wants it | 2026-11-14 |
| [24](backlog/24.md) | L | Kubernetes deployment: what was not checked (at work) | five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing | 2026-11-14 |
| [25](backlog/25.md) | M | One hot table under contention: throughput and fairness (measure at work) | a measuring run on work hardware; a change only if Paul wants one | 2026-11-14 |
| [26](backlog/26.md) | XL | Branches, tags and merge (v1.x, D33) | the milestone over build items 102 to 112 (seven L, four M; see the design below) | 2026-11-14 |
| [28](backlog/28.md) | XL | DR replication-watermark verifier (v1.x) | the verifier, the watermark on four stores, its readout and the rewind (Paul chose the rewind now, 2026-11-20); item 83 folded in | 2026-11-14 |
| [29](backlog/29.md) | XL | M9: carry nuna to work (at work) | the whole install checklist, at work | 2026-11-14 |
| [43](backlog/43.md) | L | Hook nuna into work's OpenTelemetry collector and Prometheus (at work) | findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval | 2026-11-15 |
| [44](backlog/44.md) | M | Grafana dashboards and Prometheus alert rules for nuna (at work) | at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43 | 2026-11-15 |
| [89](backlog/89.md) | M | A compaction Job against a store behind a private CA, end to end | a TLS store with a private CA beside the existing Kubernetes compaction test, and one overnight test that runs a real Job | 2026-11-20 |
| [97](backlog/97.md) | M | A KMS key for MinIO and Ceph storage profiles (D26) | a KMS beside the Silo and Ceph test stores, the key id each takes, and whether their STS session policies enforce the SSE condition keys; waits on Paul if on-prem keys are not wanted yet | 2026-11-20 |
| [104](backlog/104.md) | L | Reads on a ref: the catalog@ref prefix, config, loads and listings (item 26) | every Iceberg read route learns the ref, with route tests and a stock client test; waits on item 102 | 2026-11-20 |
| [105](backlog/105.md) | L | Commits on a branch write its own pointer (item 26) | the engine's twelve reads and swaps of main's key become the ref's, for tables, views and transactions, with the merge cap and the unknown-outcome path on a branch; most tests need only Java; waits on items 103 and 104 | 2026-11-20 |
| [106](backlog/106.md) | L | Ref management routes: create, list, renew and delete, with P11's limits and expiry (item 26) | five routes with their rights, idempotency, limits, audit and meters, and the work list's expiry section, with route tests; waits on item 102 | 2026-11-20 |
| [107](backlog/107.md) | L | Merge a branch into main, per table, in one bounded transaction (item 26) | the merge's plan, its judgement per table, the conflict report and dry run, and the race with main, over the store's batch (item 103); the merge engine's tests need only Java; waits on items 103, 105 and 106 | 2026-11-20 |
| [108](backlog/108.md) | L | Cleanup protects every ref, and the worker warns of and ends expired branches (item 26) | the orphan scan and expiry across several metadata files per table, the recheck of files recorded before a ref was made, and the expiry section, with heavy tests on Silo; waits on items 105 and 106 | 2026-11-20 |
| [109](backlog/109.md) | L | Console: the catalog's refs, the merge preview and a ref selector (item 26) | the ten screens of Atelier's design (2026-11-22) and the server reads they need beyond items 104 to 107 (below); was M before the design; waits on items 104, 106 and 107 | 2026-11-20 |
| [111](backlog/111.md) | M | After a regional failover, report every ref that names a file the DR store lacks (item 26, question 3) | one more pass of the reconcile over the refs' metadata files, with a route test and a runbook step; waits on items 102 and 105, and on item 100, which holds `Reconcile.java` | 2026-11-20 |
| [112](backlog/112.md) | M | Branches, tags and merge: engines on refs, the docs and the gate (item 26) | engine matrix cases and one page of docs over code already built; the heavy and overnight runs are the overnight run's; waits on items 102 to 111 | 2026-11-20 |
| [113](backlog/113.md) | L | Grants that name a branch (later, D33) | a grant gains an optional branch on four stores, and every authorization check on a ref asks for it, with tests and bites | 2026-11-20 |
| [115](backlog/115.md) | L | Row filters and column masks stored in the catalog, served to Trino (D20) | a new kind of policy on four stores with a schema step, management routes and audit, a route Trino's access control calls, Trino reaching nuna as a trusted engine for its users, and a demo leg, with tests and bites | 2026-11-20 |
| [118](backlog/118.md) | XL | Untrusted engines read flagged tables through filtered copies made by a fleet, delivered by scan planning (D20) | the spec's three scan-planning routes, a fleet of filtering jobs with its own identity, temporary copies with their own credentials, lifetime and audit, a cache of copies, and a demo leg, with tests and bites on four stores; waits on item 115 | 2026-11-21 |
| [123](backlog/123.md) | M | Heavy build: Docker-backed services stall 30 s while two heavy modules share Docker | finding what stalls first (Docker Desktop's port forwarding, or CPU starved by two stacks), then either a schedule that keeps the heaviest modules apart or waits that outlast the stall; shown only by overnight runs | 2026-11-22 |
| [135](backlog/135.md) | S | Windows pages the Docker VM out during the nightly: free the host's memory (Paul decides how) | once Paul picks a remedy: applying it and reading one overnight run's numbers; waits on Paul | 2026-11-22 |
| [151](backlog/151.md) | L | Brokerage world 2: trading and custody, the OMS in SingleStore and the stock record and CNS in CockroachDB | the OMS tables in SingleStore and their change feed, the clearing domain in CockroachDB and Iceberg, and three reconciliation views; waits on item 150 | 2026-11-22 |
| [152](backlog/152.md) | L | Brokerage world 3: party, money movement and compliance, the CDC sources, the governance moments and live mode | the PostgreSQL and Oracle sources, the compliance domain, the governance set-up and a live mode over the CDC leg; waits on item 151, and on item 155 (the demo kit's templates) for wires, ACH returns, journals, fees and ACATS | 2026-11-22 |
| [153](backlog/153.md) | L | Brokerage world 4: the firm's books and reporting, the GL, margin, the reserve, tax, statements and CAT | the firm-level domains and the month-end close; waits on item 152, and on item 155 (the demo kit's templates) for dividends and splits | 2026-11-22 |
| [154](backlog/154.md) | M | Brokerage world 5: volume and upkeep, streaming tables, maintenance policies and the scale setting | two streams through the existing sink, maintenance policies per namespace and a scale setting; waits on item 153 | 2026-11-22 |
| [155](backlog/155.md) | L | The demo kit: templates and booking schemes for the events the brokerage world needs beyond ledger kit v14 (D48) | about sixteen templates as booking-scheme data, two new calculations (lots received by transfer, lots rescaled by a split) and the engine's tests; waits on item 150 (the port) | 2026-11-22 |
| [156](backlog/156.md) | M | Remove a table from refs: an audited route, gated by manage-tags, so a dropped table's data can leave tags (item 26, finding 6) | one store operation on four stores and their suite, one route with its audit event, tests and bites; waits on items 105, 106 and 108, which hold the same files | 2026-11-22 |
| [157](backlog/157.md) | S | A namespace drop is refused while a ref's cut names an object in it (item 26, security finding 7) | one read in each store's namespace drop, one route answer, a suite case, a fast route test and four bites; the suite case then waits for the overnight heavy build | 2026-11-23 |
| [158](backlog/158.md) | M | A ref made from main while a table and then its namespace are dropped can name a table whose namespace is gone (item 157's race) | a lock-order change on three stores and a check in MongoDB's ref create, each with a suite case that holds a ref create open; the cases then wait for the overnight heavy build | 2026-11-23 |
| [167](backlog/167.md) | M | Refs: a description given when a branch or tag is made, kept on the ref and shown in the console (item 109's gap 8) | a nullable column and its migration on PostgreSQL, CockroachDB and Oracle and a field on MongoDB's document, carried by create and every read, a `RefSuite` case on four stores, the route's field and limit, and the console's purpose line; the suite case then waits for the overnight heavy build | 2026-11-23 |
| [168](backlog/168.md) | M | Refs: each table's version at a ref's cut, and the cut's time for a ref made from a branch, from the server (item 109's gap 9) | a `cut-at` on the ref's answer, and the namespace listing and its totals read through a ref (each table's pointer on the ref, or at the cut, for its format, snapshots and last commit), with fast route tests and bites, then the console's columns | 2026-11-23 |
| [169](backlog/169.md) | M | Audit: records filterable by the branch they went through (item 109's gap 10) | a `branch` column (and the subject's id as a filter) on the audit events table, its schema evolved in place on existing tables, the writer filling it from each record, the feed's two filters, and the console's search; the audit table tests start containers, so they wait for the overnight heavy build | 2026-11-23 |
| [170](backlog/170.md) | M | Console: a table dropped on main or made after a ref's point, read through the ref or on main, says when, by whom and which refs hold it (design 6.4's edge states) | a read of which live refs hold an object (and the drop's actor, from the drop's journal or audit record), a fast route test with bites, and three states on the table page | 2026-11-23 |
| [171](backlog/171.md) | S | A hold placed above an object while a purge claim or a removal from refs runs: lock the held levels before reading holds (item 156's review, low) | one lock taken earlier in two operations on four stores, with a suite case that holds a hold's write open; the case waits for the overnight heavy build | 2026-11-23 |
| [172](backlog/172.md) | S | Console: export a table's metadata and log as a catalog ref reads it (item 109's review) | the export route takes `?ref=` as the metadata log does (the ref's pointer, its file checked against the hash, and the log through the ref), a fast test and a bite, and the console's button through a ref with `@ref` in the file name | 2026-11-23 |
| [177](backlog/177.md) | S | Register and revive refuse a branch's metadata file on main (item 107's merge-base review, low) | one check on the metadata location in two CommitEngine operations, a fast test and a bite | 2026-11-23 |
| [197](backlog/197.md) | M | After audit goes to logs (195): a table's Audit tab reads the audit table, not the topic; the console says which rows came from the log; Health's publish figures are the Kafka copy's | one route's source and three console pages' words, with their tests; waits on nothing | 2026-11-23 |
| [220](backlog/220.md) | S | Console: findings from Atelier's re-walk after items 212 to 217 | three small console fixes: one column's wrapping, one count's words, one button's label | 2026-11-23 |
| [226](backlog/226.md) | S | Bite metadata-log-ref-page-by-version runs the server out of heap instead of failing its test, and run.sh calls it NO BITE | re-record one bite against a test that ends on an assertion, and make the judge call an executor crash SETUP, not NO BITE | 2026-11-24 |
| [227](backlog/227.md) | M | Bite mongo-cut-read-outside-the-snapshot does not bite: everyTableInACutHasItsNamespace passes with the cut read outside the snapshot | the race is a drop landing between two reads; the suite needs a way to land it there every time on MongoDB (a hook or a second session), found and then recorded again | 2026-11-24 |
| [228](backlog/228.md) | S | Bite store-skips-postgresql-steps is SETUP again: the server no longer starts on PostgreSQL migrated with the shared steps only | finding why the context no longer loads (likely a later PostgreSQL step that assumes journal partitions) and recording the bite again so the server starts; cause not known yet | 2026-11-24 |
| [231](backlog/231.md) | S | Console: between 761 and 1,016 px, and 1,241 and 1,297 px, the header is wider than the window (Atelier decides) | a few header rules at two container widths once Atelier says what gives way; a browser test | 2026-11-24 |
| [232](backlog/232.md) | S | Console: a frozen table's page is wider than the window at 1440 px (its frozen pill runs past the edge) | find which head or panel holds the pill and let it wrap; a browser test | 2026-11-24 |
| [233](backlog/233.md) | M | The demo's metrics switch: an OTel collector, Prometheus and Grafana with nuna's dashboards | three new pods in one manifest, a fourth switch in demo.sh built like gen, ss and cdc, and fixing whatever the dashboards get wrong once they meet real series | 2026-11-24 |

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
- Effort: S, signing in and out against Entra ID and Ping at work (waits on work)

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

Progress (2026-11-20, worktree-agent-a02d3e2e626c074d5): "Sign out" ends the IdP's session, by OpenID Connect
RP-initiated logout (`auth.js` `signOut`). The console forgets its token, reads `end_session_endpoint` from the
discovery document it already reads for signing in, and sends the browser there with `client_id` and
`post_logout_redirect_uri`, its own page (the sign-in's redirect URI); back there it shows "Signed out.". The console
keeps no ID token (it stores only the access token), so it sends no `id_token_hint`, which none of Keycloak, Entra
ID, PingOne and PingFederate requires by its documentation: Keycloak (seen in its `LogoutEndpoint`) and PingFederate
then ask the person to confirm on their own page, and Entra ID shows an account picker. If the discovery document names no `end_session_endpoint`, or cannot be read, the console forgets
its token only and says the IdP's session was not ended. The console's Keycloak clients (the demo's realm, the test's)
allow the page as a post-logout redirect URI; `deploy/local`'s Keycloak has no console client (its console uses the
token box), so nothing changed there. `docs/install-at-work.md` lists what the console's client needs, Entra ID's and
Ping's settings among it. `ConsoleSignInTest#signOutEndsTheIdpSessionSoTheNextSignInAsksForThePassword` (heavy) first
shows Keycloak's session lets a new sign-in straight back in without a password, then signs out (Keycloak's
confirmation, with `client_id` and the console's page in its URL and no `id_token_hint`), finds the console signed
out with empty storage, and that the next sign-in lands on Keycloak's password page. Bite
`bites/UI/console-sign-out-keeps-idp-session.patch` (Sign out never goes to the IdP), checked with `git apply
--check`. The test and the bite have not run yet: they need Chrome and Keycloak in containers, so the overnight run
is their first. Still open: signing in and out against Entra ID and Ping (work).

**Paul, 2026-11-20 (D16, amended):** the console keeps no ID token, so the IdP's "log out?" page stays ("Keep the confirm page").

### 22. Administer storage profiles and catalogs at runtime (not now, D41)

- Decided: 2026-11-19, D41
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, routes, storage on four backends, audit and the startup checks; only if Paul wants it

`build-plan.md`, M6, Open: profiles and catalogs come only from startup declarations
(`nuna.storage.profiles`, `nuna.storage.catalogs`); nothing changes them while the server runs.

Done when Paul has said whether runtime administration is wanted. If it is: a management route, for platform
operators, audited, with the same overlap and TLS checks as startup.

**Decided 2026-11-19 (D41):** left open and not to be built now ("lets leave it open and document it as a backlog item we will not do right now"). What it would change: storage profiles (store kind, endpoint, region, table root, allowed prefixes, STS role or static keys, the maintenance worker's identity), new catalogs (profile, KMS key set and legal entity, fixed once a catalog exists) and the vending settings are read at startup, so adding a catalog or a store, or rotating a MinIO or Ceph static key, takes a config change and a rolling restart. Nessie works the same way by default; its hidden stored mode, which only its tests can update, is described in D41.

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

- Decided: 2026-11-20, D33
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: XL, the milestone over build items 102 to 112 (seven L, four M; see the design below)

`build-plan.md`, v1.x milestones: branches, tags and merge over the v1 storage. The REST prefix selects the branch
(`trading@dev`), Nessie merge semantics per table, cleanup protects every ref, defaults from P11 (D33). Not started.

Done when the milestone's scope is built and its gate is green, as for the other v1.x phases.

**Decided 2026-11-19 (D33):** built next ("Build it next"), after the items the backlog review of 2026-11-19 chose to fix, not left for v1.x.

## Design, 2026-11-20 (for Paul's review)

Three questions for Paul, all on scope, are at the end. Everything else that D33 and P11 leave open is settled below
as a numbered choice with its reason; Paul may overrule any of them. The build items, 102 to 112, are written to the
recommended answers, and each question names the items another answer would change.

### What is in place

- **Pointers and commits.** Every store keys pointers by (object, branch), and every pointer is `main`
  (`PointerKey.MAIN`). The commit core swaps and holds several pointers in one transaction, in a fixed key order, on
  all four stores (`CommitBatch`, D15); `/transactions/commit` has used it since 2026-11-12 (built, advertised, capped
  at 100 by P10). A merge's swaps on main need no new transaction shape. Every caller asks for main's key:
  `CommitEngine` in twelve places, `Reconcile`, the DR and lifecycle routes, `TableRoutes.load`.
- **The journal.** One row per swap, keyed (commit, object, branch), read per pointer by version. Its `seq` is drawn
  at insert, so a lower one can commit after a higher one, and MongoDB's journal has none (`docs/storage-design.md`,
  M1 MongoDB deviation 3). Storage-design section 8 left the tag protocol open for this milestone: a journal position
  is not a consistent cut across tables without a lock or a commit-ordered clock.
- **The ref record.** PostgreSQL, CockroachDB and Oracle have had a `ref` table since V3 (catalog id, name, kind,
  `base_position` as text in an encoding never fixed, expires-at), with no Java type and no operation. MongoDB has no
  collection for it.
- **The prefix.** `Lookup.catalog` hands the whole `{prefix}` to `Holdings.catalog`, which normalizes it as a catalog
  name; nothing parses `@`. Catalogs come from configuration (`nuna.storage.catalogs.<name>`), and nothing refuses `@`
  in their names.
- **Cleanup.** The worker loads each table on main. Its orphan scan reaches every snapshot of main's metadata, so
  every Iceberg ref inside the table counts; its expiry is Iceberg's RemoveSnapshots on main. It knows no catalog ref:
  the orphan scan would queue a branch's files once they pass the orphan age, and expiry on main would queue files
  that a tag's older metadata still reaches. A purge claim checks holds and freezes, not refs.
- **Authorization and audit.** A commit asks write (data only) or modify on each table, in one batched check (D18); a
  refusal is a `Denial`. The `Commit` and `ObjectRead` events already carry a `branch`, always `main`. No event type
  records a ref change, and a new type needs an `AuditRows` mapping or the audit tables' writer fails on it.
- **Console and demo.** The table page shows Iceberg's own refs ("Refs: branches and tags"), and audit lines read "on
  main"; the console already writes (grants). The demo's `LakeWriter` makes an Iceberg tag `eod-2026-11-09` and an
  Iceberg branch `surveillance-review` inside the trades table. No catalog ref exists anywhere.

### Iceberg's refs and D33's refs

An Iceberg branch or tag is a named snapshot inside one table's metadata file: an engine makes it with
`ALTER TABLE … CREATE TAG` or `manageSnapshots()` and reads it with `VERSION AS OF` or `table.branch_x`. A D33 ref
belongs to the catalog: it chooses which metadata file each table's pointer names, for every table at once. The two
nest. On the catalog branch `trading@dev`, each table's metadata has its own Iceberg refs, and an engine's write goes
to that metadata's Iceberg `main` (or to an Iceberg branch it names). Iceberg refs made on a catalog branch reach main
only by merge, which takes the branch's whole metadata file. The catalog never uses Iceberg's refs for its own, and
the same name in both means two unrelated things. D18's `manage-tags` privilege is about governance tags (D27), not
either kind.

### What each part needs

1. **Refs and their records on four stores** (item 102). A schema step adds an id, creator, creation time and origin
   to `ref`, and a new record, the ref's cut: each table's and view's main pointer and name as of the ref's point
   (choice 1). MongoDB gets both collections. A new store group makes, reads, lists, renews and deletes refs and
   resolves names through a cut, with a shared suite.
2. **The prefix `catalog@ref`** (item 104): parsed once beside the catalog lookup; no `@` means `main`.
3. **Reads as of a tag or a branch's base** (item 104): a tag reads its cut; a branch reads its own pointer where it
   has one and its cut elsewhere (D33). Names and listings come from the cut (question 1).
4. **Commits on a branch** (items 103 and 105): the engine reads the branch's pointer and swaps the branch's key
   (object, ref id); the first commit to a table creates it. Every branch commit holds the ref, so a merge or a
   deletion waits for it or refuses it. D6 holds: a commit still locks one pointer per table.
5. **Merge** (items 103 and 107): per table, D33's rule. Main still at the cut: main takes the branch's pointer.
   Moved or dropped on main too: a conflict, and nothing moves. All tables go in one `CommitBatch`, which also ends
   the branch.
6. **P11's limits and expiry** (items 102, 106 and 108): 50 open branches and 1,000 tags per catalog, checked in the
   create's transaction; a branch expires 30 days after its last commit unless renewed, within 180 days of its
   creation; tags never expire. Settings under `nuna.refs.*`, changeable without code like P10's.
7. **Cleanup protecting every ref** (items 103 and 108): the orphan scan and expiry reach across every ref's metadata
   for the table, and a purge waits while a ref names the table.
8. **Authorization, audit and metrics** (items 104 to 107): choices 12 and 13.
9. **Management routes** (items 106 and 107): choice 6.
10. **The console** (item 109) and **the demo** (item 110): choices 15 and 16.
11. **The gate** (item 112), as for the other v1.x phases: the fast build; the heavy build (`RefSuite` on four stores,
    the route tests, the worker's cleanup tests on Silo, the Ceph leg); the overnight run (Spark and PyIceberg on a
    branch and a tag in the engine matrix, `MutationSuite` over the ref mutations); and every bite in `bites/D33/`
    biting.

### Choices made (Paul may overrule)

1. **A ref keeps its point as a cut.** When it is made, one consistent read records each table's and view's main
   pointer (version, location, hash) and name; a branch's own pointers sit beside the cut. Why: a journal position
   would need a catalog-wide lock in every commit on four stores, and MongoDB has neither read locks nor a `seq`
   (storage-design 8), while a cut changes no commit path. The cost is a row per table per ref, about 10 million rows
   at P11's limits on a 10,000-table catalog. Overruling this means storage-design 8's option (a), the lock.
2. **Refs are made from now,** from main or from another ref's head, never at a past time. Why: the journal cannot
   give a consistent cut of the past (choice 1).
3. **A branch's pointers and journal rows are keyed by the ref's id,** never its name; main stays `main`. Why: a
   deleted branch's name can be used again without old rows answering for it.
4. **Ref names** are case-insensitive and case-preserving like every name (D8.6), without `@` or `/`; `main` is
   reserved and always there; a configured catalog name with `@` stops the server at startup. Why: the prefix must
   parse one way.
5. **A tag is read-only to every engine:** its `/v1/config` lists only the read endpoints, a write on it is refused
   with 400 naming the tag, and it vends read-only credentials. Why: Iceberg's clients check the endpoint list before
   a call, so a stock engine fails at once and plainly.
6. **Management routes** under `/management/v1/catalogs/{catalog}/refs`: list (paged), create (`name`, `kind`,
   `from`), read (with a branch's changed tables), renew, delete, and `…/{ref}/merge` with `dry-run`; every write takes
   an Idempotency-Key (D10). Why: the Iceberg spec has no refs, and the management API holds the other catalog-scoped
   operations.
7. **A conflict lists every conflicting table** (name, the cut's version, main's, the branch's, or dropped) in a 409,
   and nothing moves; a dry run answers the same without moving anything; a merge that loses a race to a main commit
   plans again within P10's bound. Why: data CI needs the whole list at once, and the console shows it before the
   button.
8. **A merge judges each moved table as a commit on main:** main's rules (D11), holds (D24) and freezes (D37) apply to
   the change from main's metadata to the branch's. Why: a hold or freeze set after the branch's commits must still
   bind.
9. **A merge moves at most `nuna.refs.max-merge-tables` tables, default 100 (P10's cap), and a branch commit that
   would take the branch past it is refused.** Why: it is the bound the four stores' suites prove for one transaction
   (D15), and a branch too big to merge would be stranded.
10. **A merge ends the branch.** Why: P11 says merging releases it; a branch left open would pin files and drift from
    its cut.
11. **P11's 180 days is a branch's whole life:** commits and renewals set its expiry 30 days ahead, never past 180
    days from its creation. Seven days before, the catalog sends one `ref` event (expiring) on the change stream, a
    gauge counts such branches, and the console marks them; at expiry the branch is gone for every route, and the
    worker's next scan ends it. Why: the only reading that bounds how long a branch pins files, and the change stream
    is how the catalog tells people things (D23).
12. **Rights, with no new privilege:** making, renewing and deleting a ref needs `create` on the catalog; a merge
    needs that and, on every table it moves, the right a commit of that change needs on main (write for data only,
    else modify), in one batched check; reading and writing on a ref need the same rights as on main; listing refs
    needs any holding in the catalog (D18 as amended). Why: D18's privilege set stays as it is. The alternative is a
    new `manage-refs` privilege, if branch makers should not also make namespaces.
13. **Audit and metrics:** ref changes travel through the outbox as one new event type, `ref` (created, renewed,
    deleted, merged with the tables moved, expiring, expired); refusals are `Denial`s; commits and reads on a branch
    name it in the `branch` field they already carry. Meters gain a ref-kind label (main, branch, tag), never a ref's
    name, plus per-catalog gauges of open branches, tags and branches expiring within 7 days, and merges by outcome.
    Why: names as labels would grow without bound.
14. **Cleanup across refs:** the maintenance work list names, for each table, the metadata each ref reaches (a tag's
    cut, a branch's cut and head); the orphan scan and expiry reach across all of it and queue only what none
    reaches. A deletion recorded before a ref was made is checked against that ref before the file goes. A purge claim
    is refused while a ref's cut names the table, so a tag keeps a dropped table's files. Maintenance itself runs on
    main only: a branch is neither expired nor compacted, and after a merge its files are main's. Why: P11 and D33; a
    tag made between expiry's read and its commit would otherwise lose files.
15. **The console:** a catalog's refs (list, make, renew, delete, and merge with its dry run shown first), a ref
    selector on namespace and table pages, and the table page's "Refs: branches and tags" renamed "Iceberg refs in
    this table". Why: the two kinds of ref must not share a label.
16. **The demo:** `up` makes a catalog tag and a branch `dev` with a change to two tables; Trino gets a second catalog
    on `lakehouse@dev`; the README walks a merge in the console. Why: the four uses need a branch to try them on.

### Questions for Paul (scope)

1. **What does a ref keep besides each table's contents?**
   - (b) Recommended: **names too.** A ref keeps each table's and view's name as of its point, so a tag reproduces a
     table later dropped or renamed on main, and a branch shows names as of its point. Creates, drops, renames and
     namespace changes happen on main only and are refused on a branch; namespace properties are live. Why: the same
     cut gives it at little extra cost, and a tag that keeps a dropped table's files (P11) can then read them.
   - (a) **Contents only:** names and namespaces are live on every ref; a table dropped or renamed on main is gone
     from every ref, its files kept unread while a ref names it. Changes items 102 and 104.
   - (c) **Everything branches, as in Nessie:** tables, views and namespaces can be made, dropped and renamed on a
     branch, and a merge carries them; objects and namespaces gain a ref on four stores. About two more L items.
2. **Do grants that name a branch belong in this milestone?**
   - (a) Recommended: **no.** A table's grants hold on every ref (choice 12), so whoever may write a table on a branch
     may write it on main. Why: D33's four uses isolate data, not rights, and branch grants would change D18's model
     on four stores; they can follow as an item of their own.
   - (b) **Yes:** a grant can name a branch, so a group can write on `dev` without writing main. One more L item.
3. **Do refs follow a regional failover (D37) in this milestone?**
   - (a) Recommended: **a report only** (item 111). After a failover the reconcile lists every ref whose cut or branch
     head names a metadata file missing from the DR store, and the runbook says to delete those; branches are not
     walked or rewound. Why: branches are short-lived and tags mostly name older versions DR already holds, so a
     report shows the gap for an M item.
   - (b) **Yes, whole:** the walk with its file check and the rewind (item 28) cover branch heads too, and a tag naming
     a version the rewind gave up is reported. Item 111 becomes L.
   - (c) **No:** nothing for refs in DR; the runbook says refs may name missing files. Item 111 is dropped.

**Decided 2026-11-20 (D33, amended):** 1 (b), names too (Paul: "Names too"); 2 (a), no branch grants in this milestone, item 113 holds them for later (Paul: "No, later"); 3 (a), a report only, item 111 (Paul: "A report only").

### Build items, in order

Item 26 stays open as the milestone over these, and closes with item 112.

| Item | Title | Effort | Waits on |
|---|---|---|---|
| 102 | Refs in the store: the ref record and its cut on four stores | L | none |
| 103 | Branch commits and the merge transaction in the store | L | 102 |
| 104 | Reads on a ref: the catalog@ref prefix, config, loads and listings | L | 102 |
| 106 | Ref management routes: create, list, renew and delete, with P11's limits and expiry | L | 102 |
| 105 | Commits on a branch write its own pointer | L | 103, 104 |
| 107 | Merge a branch into main, per table, in one bounded transaction | L | 103, 105, 106 |
| 108 | Cleanup protects every ref, and the worker warns of and ends expired branches | L | 105, 106 |
| 109 | Console: the catalog's refs, the merge preview and a ref selector | M | 104, 106, 107 |
| 110 | Demo: a catalog tag, a branch with Trino on it, and a merge | M | 107, 109 |
| 111 | After a regional failover, report every ref that names a file the DR store lacks | M | question 3; 100, 102, 105 |
| 112 | Branches, tags and merge: engines on refs, the docs and the gate | M | 102 to 111 |

102 goes first; item 98, which held the four stores, landed its schema steps on 2026-11-20, so 102's come after
them. Then 103, 104 and 106 can run at once, since
their areas do not overlap (the stores; the Iceberg routes; the management routes and audit). 105 follows 103 and 104;
107 and 108 can then run at once; 109, 110 and 111 follow; 112 closes the milestone. Each item records its own bites
in `bites/D33/` and names its heavy and overnight tests, which the overnight run runs.

**Landing order (2026-11-20, at Paul's "can you fix these"):** items 106 and 108 land together, in one merge. Item 106's create route is the only way a ref comes to exist, and until item 108 lands the worker's orphan scan and expiry read only main, so a tag's or a branch's files could be deleted in between. Item 108's worktree starts from item 106's finished branch, and the session lands item 108's branch, which holds both. Item 105 may land on its own: without item 106 no branch exists to commit to.

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

**Built, 2026-11-20 (worktree-agent-a5f1bf763c08126f3): "Judge like the verifier", with item 100; open until the
heavy and overnight tests pass.**
- `Reconcile.plan`'s check from the watermark judges the journaled versions above it as `DrVerify` does, with the
  same `AddedFiles.firstMissing` and `addsNothing`: a version is whole when the one before it is and it adds no
  missing file, and after one that is not, only when it adds nothing to the newest whole version. The table is walked
  only when the pointer's version is whole that way; otherwise `problem` names the first file missing since the newest
  whole version. So a rewound table walks at once, its watermark at the rewind's version (item 100) or still below it.
  `planRewind` no longer refuses a pointer that holds the watermark's metadata "until the verifier passes it": after
  item 100 that pointer is the watermark, and "the pointer is at the DR watermark" answers it.
- Tests, Java only, in `ReconcileFileCheckTest`: `aRewoundTableWalksAtOnce` (rewound through `planRewind` and
  `rewind`, then walked to the next version, with the watermark at the rewind's version and at the old one) and
  `theGivenUpVersionsNeverCount` (a version built on one missing a file never counts, whether it adds files or only
  a property). The earlier stretch tests are unchanged.
- Bite `bites/D37/walk-counts-given-up-versions` (a version after one not whole counts as whole).
- The runbook (`deploy/README.md`, steps 4 and 5) and `docs/observability/dr-watermark.md` drop the second verifier
  pass. `DrDrillTest` (overnight) checks the rewinds' watermarks at once instead of waiting for a verifier pass, and
  no longer asserts that its lost tails stay under 100 versions.

Left: the heavy tests named above and the overnight `DrDrillTest` have not run. `catalog-maintenance/README.md`
(not this branch's) still says the drill's verifier "passes the rewound versions (backlog 92)" after the rewind.

**Decided 2026-11-20 (D37, amended):** both DR alerts stay severity `page`. Paul: "Both page".

Seen in the overnight run of 2026-11-22 (testing b1f073d), the first heavy build to reach `DrSuite`: on MongoDB, five
of its cases failed (`MongoStoreSuiteTest$Dr`):

- "a watermark is kept, rises, never moves back, and never passes the pointer" (`DrSuite.java:79`): [one above the
  pointer] expected `Refused[WATERMARK_AHEAD]` but was `Unchanged[]`.
- "a view keeps a watermark; a dropped table, an object with no pointer or an unknown id is Missing"
  (`DrSuite.java:133`): expected `Missing[OBJECT]` but was `Refused[WATERMARK_AHEAD]`.
- "the tables and views behind are listed by id ..." (`DrSuite.java:161`): one id more than expected in the list.
- "a journal page can start after any version ..." (`DrSuite.java:200`): expected empty but held a main journal record.
- the rewind swap's watermark (`DrSuite.java:104`): expected 103 but was 104 (item 100's case).

PostgreSQL, Oracle and CockroachDB did not run: the heavy build stopped after this module (item 122).
`C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-22\heavy.log`, `heavy-results\catalog-store-mongo\`.

2026-11-22: `DrSuite` was fixed on `worktree-heavy-fixes` (its versions now count the one creating an object commits;
item 100 says how) and passed on all four stores in two full heavy builds. The third, clean in every module, also passed
`DrRewindRouteTest`, `MetadataFilesTest`, `ManagementAuthorizationTest`, `MutatingRoutesAuditTest` and
`DrReconcileRouteTest`. Left: item 92 and the overnight `DrDrillTest`.

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

Seen again in the overnight run of 2026-11-21: its first run failed. `PrivateCaCompactionTest#aJobTrustsItsStoresPrivateCaAndCompacts`,
`AssertionError: [refused by the store's certificate]`: the control Job's Spark log holds no PKIX error but
`SdkClientException: Unable to execute HTTP request: Network is unreachable` (6 attempts) and
`CATALOG_NOT_FOUND`, so the pod did not reach the store at `host.docker.internal` at all. Log:
`C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-21\nightly-results\catalog-maintenance\`.

Fixed on `worktree-item-89` (2026-11-21), not yet shown by running. The control Job did what it should; its log
could not say so. Docker Desktop gives pods two addresses for `host.docker.internal`, `192.168.65.254` and
`fdc4:f303:9324::254`, and pods route no IPv6 (`nslookup` and `ip -6 route` in a busybox pod, 2026-11-21). The S3
client's HttpClient, 5.6.4 shaded in `iceberg-aws-bundle-1.12.0.jar` in the Spark image, handshakes TLS inside its
loop over a name's addresses (`DefaultHttpClientConnectionOperator.connect`: `upgradeToTls` lies in the try whose
`IOException` handler moves to the next address, read with `javap`), so the PKIX refusal on the IPv4 address sent it
on to the IPv6 one and it reported only `Network is unreachable`. The catalog, over HTTP, was reached on IPv4 at once.
The control now asserts that the S3 request failed on every attempt (`Unable to execute HTTP request`) and compacted
nothing; the worker's Job, the same but for the bundle, must then succeed against the same address. The README says
how a missing bundle shows on such a network. The test could not run here: the cluster has 6 GiB of 7.5 GiB
requested with the nuna-demo stack up, and the Job asks for 2 GiB. Close this item when the overnight run of
2026-11-22 passes the test and its bite bites.

Seen in the overnight run of 2026-11-22 (testing b1f073d, which holds the fix): `PrivateCaCompactionTest` passed, 1
test, 0 failures, 122 s (`C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-22\nightly-results\catalog-maintenance\`).
Its bite, `bites/M6/job-storage-ca-left-out`, did not run: the bites run only after a passing heavy build, and that
build failed (items 28, 100, 102, 103, 121). Close this item when an overnight run's bites show the bite bites.

Run in two nightlies by hand on 2026-11-22 (`status/2026-11-22-nightly-2026-11-22.md`): `PrivateCaCompactionTest`
passed in the second (257 s). In the first, its body passed and its cleanup failed: `kubectl delete namespace ...
--wait=true` lost the API server (`http2: client connection lost`) while Windows was paging the Docker VM out (item
135). The bite has still not run; the item still closes when an overnight run's bites show it biting.

Overnight run of 2026-11-23 (testing 02b22ad): the nightly passed, `catalog-maintenance` 3 tests, 0 failures:
its three overnight tests, `PrivateCaCompactionTest` among them (inferred from the count; the run keeps no nightly
results that night); the bites again did not run, the heavy build having failed (nine tests, traced
in `status/2026-11-23-agent-a9284fd56a1d18c39.md`). The item still closes when an overnight run's bites show
`bites/M6/job-storage-ca-left-out` biting.

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

### 104. Reads on a ref: the catalog@ref prefix, config, loads and listings (item 26)

- Decided: 2026-11-20, D33
- Added: 2026-11-20 by agent on item 26
- Touches: catalog-server/src/main/java/nuna/catalog/server/rest/ catalog-server/src/main/java/nuna/catalog/server/ConfigRoute.java catalog-server/src/main/java/nuna/catalog/server/AdvertisedEndpoints.java catalog-server/src/main/java/nuna/catalog/server/security/Holdings.java catalog-server/src/main/java/nuna/catalog/server/storage/CatalogStorage.java catalog-server/src/test/java/nuna/catalog/server/RestRoutesTest.java catalog-tests-conformance/src/test/java/nuna/catalog/conformance/IcebergClientConfigTest.java
- Effort: L, every Iceberg read route learns the ref, with route tests and a stock client test; waits on item 102

Part of item 26 (D33); its design section says why (choices 4 and 5, question 1). Written for question 1's
recommended answer (b): names and listings come from the ref's cut, and creates, drops and renames stay on main.

What is wrong: `Lookup.catalog` hands the whole `{prefix}` to `Holdings.catalog`, which normalizes it as a catalog
name, so `trading@dev` is a catalog that does not exist (404). Nothing refuses `@` in a configured catalog name
(`CatalogStorage.declare`). Every read takes `object.mainKey()` (`TableRoutes.load`, `CommitEngine.readCurrent`).

Build:
- The prefix `catalog@ref`, parsed once beside the catalog lookup; no `@` means `main`. The ref is read from the
  store, and a branch past its expiry is gone. The hidden-name rules stay as they are (D18 as amended). Whether a
  client sends `@` as is or as `%40`, the route takes both. A configured catalog name holding `@` stops the server at
  startup (choice 4).
- `/v1/config?warehouse=trading@dev` answers the prefix `trading@dev`; for a tag it lists only the read endpoints
  (choice 5).
- Reads on a ref: names, namespace listings and table and view listings through the cut. A table's or view's pointer
  is the branch's own where it has one, else the cut's (D33). The metadata is read by location and its SHA-256
  checked as on main; the ETag carries the ref; HEAD and the credentials route alike. A tag vends read-only
  credentials.
- A write on a tag is refused with 400 naming the tag. Creates, drops, renames, registers, unregisters and namespace
  changes on a branch are refused (question 1). Commits on a branch stay refused until item 105.
- `ObjectRead` events name the branch or tag in the `branch` field they already carry.

Done when route tests cover a load, HEAD, listing and credentials on a tag and on a branch with no commits yet, a
write on a tag (400), and a catalog name holding `@` (refused at startup); and a stock `RESTCatalog` with
`warehouse=<catalog>@<tag>` reads a table as of the tag and fails a commit at Iceberg's endpoint check (heavy). Fast
build green; bites recorded in `bites/D33/`. Waits on item 102. Items 105 and 109 wait on this one.

**Decided 2026-11-20 (D33, amended):** reads on a ref resolve names as of its cut; creates, drops, renames and namespace changes on a branch are refused; namespace properties are live (question 1 (b), "Names too").

**Progress, 2026-11-20 (worktree-agent-aa14fd77cb3da956c): built and tested in Java; open until the stock-client
test is written and the heavy build passes it.** That test needs a tag, and nothing can make one yet but the store:
`createRef` takes a `Change` whose outbox holds a catalog event, and no event type records a ref change until item 106
adds `ref` (a made-up event would stop the outbox relay). So it is written once item 106's ref routes land, making the
tag as a client would.
- The prefix: `OnRef` (the catalog and the ref a prefix names) and `Prefixes` (a component beside `Holdings`) split
  `catalog@ref` once, at the first `@`; `catalog` and `catalog@main` are main. The catalog is found as before
  (`Holdings.catalog`, D18 as amended), then the ref with `findRef`. A ref the catalog does not have, or a branch past
  its expiry, answers 404 `NoSuchWarehouseException` naming the prefix, on every route and `/v1/config`. Spring decodes
  `%40` in the path, so both forms reach the route. `CatalogStorage` refuses a configured catalog name holding `@` at
  startup, before it writes anything.
- `/v1/config?warehouse=catalog@ref` answers the prefix `catalog@ref` (the ref's normalized name) and, from
  `AdvertisedEndpoints.of`, a tag's 11 read endpoints, a branch's reads and its three commit endpoints, or main's 26.
- Reads: `Lookup` finds namespaces (`findRefNamespace`), tables and views (`resolveOnRef`) and lists them
  (`listRefNamespaces`, `listRefObjects`) through the cut. A namespace through a ref has the cut's id, parent and name,
  and main's live properties, or none once main dropped it. `Lookup.pointer` serves a branch's own pointer
  (`ref.key(id)`) where it has one, else the cut's; `CommitEngine.readAt` reads that file by location and checks its
  SHA-256 as on main (an alert, and a refusal for a regulated object). Table load, HEAD, metrics and credentials, view
  load and HEAD, and the three listings all read this way.
- The ETag takes the ref's id; main's ETags are byte for byte as before. `ObjectRead.branch` names the ref, or `main`.
  A tag vends read-only credentials, on a load and on the credentials route.
- Writes: `Lookup.catalog` (every create, drop, rename, register, unregister and namespace change, properties
  included) answers 400 on any ref; `Lookup.forCommit` (a table commit, a view replace, a multi-table commit) answers
  400 on a tag, and on a branch "commits on a branch are not served yet" until item 105. Each message names the
  operation, the tag or branch and the catalog; nothing past the ref is read.
- Tests, Java only, run in the fast build: `RefReadsTest` (14: the real routes, guard, commit engine and error mapping
  over a store double holding main, a tag's cut and three branches, and an HTTP server in the JVM serving the metadata
  files) and `CatalogNameTest` (2). `HiddenNamesTest` and `StoreOutageRoutesTest` build the routes with `Prefixes`.
- Bites in `bites/D33/`, all Java only, each seen to bite on an assertion (applied, the fast build run with its test
  class selected, reverted): `rest-prefix-ref-ignored`, `rest-ref-listing-from-main`,
  `rest-branch-own-pointer-ignored`, `rest-expired-branch-read`, `rest-ref-etag-as-main`, `rest-tag-vends-write`,
  `rest-tag-write-accepted`, `rest-branch-create-accepted`, `rest-tag-config-lists-writes` and
  `rest-ref-read-audited-as-main` on `RefReadsTest`; `catalog-name-at-sign-accepted` on `CatalogNameTest`. The hash
  check through a ref has no bite of its own: it is main's check in `CommitEngine`, and `aCutsFileIsHashChecked`
  covers it through a ref. Ten older bites this change moved are recorded again with the same breaks: the three in
  `bites/D18/` were seen to bite `HiddenNamesTest`; `M4/etag-ignores-location`, `M6/real-near-expiry-304`,
  `OBS-39/requests-raw-prefix-label`, `D13/labels-not-in-etag`, `D13/metrics-for-a-missing-table`,
  `D13/metrics-unguarded` and `D15/transaction-cap-unchecked` name heavy tests and were checked with `git apply
  --check` only.
- Left: the stock `RESTCatalog` test in `IcebergClientConfigTest` (heavy), once item 106 lands, and the heavy build
  passing it. `ConformanceServer` needs no change for it if the tag is made through item 106's route.

**Update, 2026-11-22 (item 105's branch, worktree-agent-a4085e6c5aba86c01):**
- The stock-client test is written: `IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch` makes a tag
  and a branch through item 106's route, reads a table on the tag as the tag took it, is refused a commit there (400
  naming the tag), and commits on the branch (item 105). It compiles; it is heavy and has not run.
- Security finding 1 (high, from the security review of 2026-11-22, shared with item 105): through a ref, an object
  main has unregistered answers 404 (`Lookup.seen`), on a tag and a branch; unregister is not refused while a ref
  names the object. A branch vends write credentials only while main's object is active (`Lookup.writable`), so a
  table main has dropped since loads read-only there. Tests: `RefReadsTest` `aTableMainDroppedIsReadOnlyOnABranch`
  and `aTableMainUnregisteredIsGoneFromEveryRef`. Bites: `rest-branch-vends-write-for-dropped`,
  `rest-ref-serves-unregistered`.
- Security finding 5 (medium): `RefReadsTest` cases through a tag and a branch: a caller holding nothing is answered
  404 with the denial audited; one who may only describe is refused 403; a flagged table gives an untrusted engine no
  storage access (`flagged_table`); an unclassified table in a regulated area is closed (D27). Bite:
  `rest-ref-load-unguarded`, the load's guard skipped on a ref.
- `D33/rest-tag-vends-write` was recorded again against `Lookup.writable`, with the same break.
- Left: the heavy build passing the stock-client test (the overnight run's).

Choices made (Paul may overrule):
- Through a ref, rights and governance (holds, regulated, freezes) are checked against main's record of the namespace,
  table or view, by its id. Why: rights on a ref are those on main (choice 12), and a cut keeps no grants.
- A ref the catalog does not have, and a branch past its expiry, answer 404 `NoSuchWarehouseException`, as a catalog
  that does not exist does. Why: the prefix is the client's warehouse; one answer for every missing one.
- `catalog@main` is main, and the prefix `/v1/config` answers for it is `catalog`. Why: `main` is reserved and always
  there (choice 4); one prefix per line keeps one ETag and cache key for main.
- A branch's `/v1/config` lists its three commit endpoints already, though they answer 400 until item 105. Why: it is
  choice 5's list for a branch, so item 105 changes no config; a stock engine's commit on a branch fails with 400
  naming the branch until then.
- A namespace's properties cannot be changed through a ref: that is a namespace change, refused on a branch (question
  1 (b)). Reads through a ref show main's live properties, and none for a namespace main has dropped since the cut.
  Why: "namespace properties are live" (D33, amended 2026-11-20); the dropped namespace has no live properties.
- A write refused on a ref is a 400 `BadRequestException` and is not audited as a denial. Why: it is not a refusal of
  rights; other 400s are not audited either.
- The ETag carries the ref's id only through a ref. Why: main's tags stay as they were, so no client loses its 304 when
  this lands.
- A tag takes a scan's metrics report (204). Why: it only reads, and choice 5 lists it with the reads.
- The prefix is resolved in a new component, `Prefixes`, not in `Holdings`. Why: `Holdings`' constructor is built in
  the governance tests item 106 holds; the catalog lookup itself is unchanged.
- `CommitEngine.readAt(object, pointer)` reads any pointer's file; `readCurrent` calls it with main's. Why: one read and
  hash check for main and refs; item 105 builds on it.

### 105. Commits on a branch write its own pointer (item 26)

- Added: 2026-11-20 by agent on item 26
- Decided: 2026-11-22, D33
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/CommitEngine.java catalog-core/src/test/java/nuna/catalog/core/commit/ catalog-server/src/main/java/nuna/catalog/server/rest/
- Effort: L, the engine's twelve reads and swaps of main's key become the ref's, for tables, views and transactions, with the merge cap and the unknown-outcome path on a branch; most tests need only Java; waits on items 103 and 104

Part of item 26 (D33); its design section says why (choices 3, 9 and 13).

What is wrong: `CommitEngine` reads and swaps `object.mainKey()` in twelve places (create, commit, register,
transaction, settle), so every commit goes to main.

Build:
- A commit on `catalog@branch` reads the branch's pointer (its own, else the cut's), checks the client's
  requirements against it, applies and judges as on main, and swaps the branch's key (object, ref id), holding the
  ref (item 103). The first commit to a table on the branch creates its pointer. Tables, views and
  `/transactions/commit` alike: lost swaps retried within P10's bound, unknown outcomes settled by `findCommit` on the
  branch's key, Idempotency-Keys replayed.
- A commit that would make the branch change more tables than `nuna.refs.max-merge-tables` (default 100) is refused,
  so the branch can always merge (choice 9).
- A commit to a table that main has dropped since the branch's point is refused (the store's `Inactive`).
- The `Commit` event names the branch; the commit meter gains the ref kind, main or branch (choice 13).

Done when engine tests (fast build, Java only) cover the first and a second commit on a branch, two first commits
racing, a transaction across two tables on a branch, the cap, and a commit after the branch ended; and route tests
(heavy) with a stock `RESTCatalog` on `<catalog>@<branch>` commit two tables, leave main unchanged, and read the
branch's rows back. Fast build green; bites recorded in `bites/D33/`. Waits on items 103 and 104. Items 107, 108
and 111 wait on this one.

**The rule for a DR freeze on a branch (Paul, 2026-11-22, D33 as amended: "Only the merge"):** a DR freeze does not
refuse a commit on a branch; it binds the merge (item 107), which refuses a frozen table while the freeze stands. A
branch's commit to a table under a freeze lands, and main is unchanged. Every other rule main applies binds a commit
on a branch: grants, D20, D27, D17's switch, legal holds (D24), the minimum snapshot age with the retention class's
(D24, D30), and the rules of `Rules.standard`.

**Progress, 2026-11-22 (worktree-agent-a4085e6c5aba86c01): built; the engine and route tests run in the fast build;
the stock-client test is written and compiles (heavy, not run). Open until the heavy build passes it, and for the Left
below.**
- Built for item 108, as its own commit (b0b459b): `RefStore.listRefPointers(objectId)` with the record
  `RefPointers` (each ref whose cut names the table or view: the ref, its cut entry and the branch's own pointer, if
  any), and `RefStore.countOwnPointers(refId)` (for this item's cap), on PostgreSQL/CockroachDB, Oracle and MongoDB,
  with two `RefSuite` cases (heavy).
- The engine: `CommitEngine.on(Branch)` gives an engine whose `commitTable`, `commitView` and `commitTransaction` go
  to the branch. `Branch` carries the ref, the cap and each object the request names as the branch's cut holds it. A
  commit reads the branch's own pointer (`ref.key(id)`), else the pointer the cut took; checks the client's
  requirements and applies the request to that version; judges it with every rule as on main, against main's
  governance, but with no DR freeze; and swaps the branch's key under `RefLock.Hold(ref)`. The first commit creates
  the branch's pointer at version 1 (the swap expects nothing there). A lost swap is retried within P10's bound; an
  unknown outcome is settled with `findCommit` on the branch's key; an Idempotency-Key is replayed, bound to the
  branch. A transaction holds the branch's own pointers of the tables it leaves unchanged.
- The object is main's record of it, by the cut entry's id, while main has it active: one main has dropped,
  unregistered or purged since answers 404 ("main has dropped or unregistered it since branch dev was made"), before
  anything is written; the store's `Inactive` for the object (main dropped it between the read and the swap) answers
  the same. A branch that has ended (merged, deleted, or past its expiry when the store commits) answers 404
  `NoSuchWarehouseException` ("Branch dev is gone: merged, deleted or past its expiry").
- The cap: `nuna.refs.max-merge-tables` (`RefSettings`, default 100, at least 1). A commit or transaction that would
  give the branch more own pointers than that answers 400 naming the setting, before any file is written.
- The marker (security finding 12): a file a branch writes is named `<version>-<uuid>.branch-<ref id>.metadata.json`
  (`TableFiles.fileName` and `TableFiles.BRANCH_MARK`, in catalog-core), in the table's `metadata/` directory beside
  main's (`MetadataFiles.write`, in catalog-objectstore, through `ProfileTableFiles`); main's files keep
  `<version>-<uuid>.metadata.json`. `TableFiles.onBranch(location)` tells them apart by the file's own name.
- The record: the `Commit` event (outbox and audit) names the branch it landed on, `main` or the branch's name, so
  `ArchivedCommits.read`, which keeps only commits on `main`, never takes a branch's commit as proof of main's swap.
  The commit meter `nuna.catalog.commits` gains `ref.kind` (`main` or `branch`, never a name).
- After a commit that lands, `Engines.committed` moves the branch's expiry `nuna.refs.branch-idle-expiry` ahead, never
  past its whole life, with a `ref` event (renewed) naming who committed (choice 11), once the move gains an hour.
- The routes: `OnRef.forCommit` refuses a tag only; the table commit, the view replace and `/transactions/commit`
  find each object through the branch's cut (`Lookup.seen`), authorize it on main's record (`Guard.onCommit`) and pass
  the cut entries to `Engines.forCommit` and `Engines.forTransaction`. A name the branch's cut does not hold answers
  404, whoever asks: a branch creates nothing.
- Docs: `deploy/README.md` (the cap, the renewal on commit, and, for item 108, keeping the replicas' clocks within
  well under an hour); `docs/observability/catalog-metrics.md` (`ref.kind` on commits). `application.yaml` holds no
  `nuna.refs.*` setting, so the cap is documented there, not added to it.

**The security review (Nuna Security Expert session, 2026-11-22), done:**
- Finding 1 (high; with item 104): through a ref, an object main has unregistered answers 404 (`Lookup.seen`), on the
  tag and the branch, for load, HEAD, credentials and commits; unregister is not refused while a ref names the object.
  A branch vends write credentials only while main's object is active (`Lookup.writable`), so a table main dropped
  since loads read-only on the branch. Every state but active refuses a commit on a branch (`activeOnMain`, and the
  store's `Inactive`). Tests: `RefReadsTest` `aTableMainDroppedIsReadOnlyOnABranch`,
  `aTableMainUnregisteredIsGoneFromEveryRef`; `BranchCommitTest#aTableGoneFromMain`. Bites:
  `rest-branch-vends-write-for-dropped`, `rest-ref-serves-unregistered`, `branch-commit-to-inactive`.
- Finding 4 (medium): a commit on a branch applies every check main applies, through `Guard.onCommit` and the engine's
  rules: D20 (an untrusted engine's commit to a flagged table: 403, audited), D27 (an unclassified table in a regulated
  area: closed), D17's switch, the legal hold and the minimum snapshot age. D20's maintenance exemption and snapshot
  expiry's allowance to remove schemas and partition specs hold on main only (choice 14). As corrected by Paul's
  decision the same day, a DR freeze does not refuse a commit on a branch (above); the freeze test shows the branch's
  commit landing and main unchanged, with no bite. Tests: `RefReadsTest` `aFlaggedTableRefusesAnUntrustedCommitOnABranch`,
  `theMaintenanceExemptionHoldsOnMainOnly`, `theExpiryAllowanceHoldsOnMainOnly`,
  `anUnclassifiedTableTakesNoCommitOnABranch`, `anIdentityLessWriteIsRefusedOnABranch`; `BranchCommitTest`
  `aLegalHoldHoldsABranch`, `theMinimumSnapshotAgeHoldsABranch`, `aFreezeDoesNotHoldABranch`. Bites:
  `rest-branch-commit-unguarded` (D20, D27 and D17 go through the one new call, so one bite breaks all three tests),
  `rest-branch-maintenance-exempted`, `rest-branch-expiry-allowance`, `branch-hold-ignored`,
  `branch-retention-class-ignored`.
- Finding 5 (medium; item 104's, recorded there too): `RefReadsTest` cases through a tag and a branch: a caller holding
  nothing is answered 404 and the denial audited (`aCallerHoldingNothingIsTold404`); one who may only describe is
  refused 403 (`aDescriberIsRefusedTheLoad`); a flagged table gives an untrusted engine no storage access, load or
  credentials (`aFlaggedTableRefusesAnUntrustedEngineThroughARef`); an unclassified table in a regulated area is closed
  (`anUnclassifiedTableIsClosedThroughARef`). Bite: `rest-ref-load-unguarded`, the load's guard skipped on a ref.
- Finding 11 (low): a branch's write credentials cover main's whole table prefix, so an engine on a branch could write
  main's files. Recorded in item 113 (a grant that names a branch needs credentials scoped to the branch) and item 112
  ("a tag's credentials refuse PutObject" joins the M6 adversarial set).
- Finding 12 (high; the storage half): the marker and the record above. Test: `MetadataFileNameTest`
  `aBranchsFilesAreMarked` (Java only: a branch's file carries `.branch-<ref id>`, main's never, and a directory
  named so does not count); `BranchCommitTest#firstAndSecondCommit` checks every file a branch writes is marked and
  main's are not. Bites: `branch-file-unmarked`, `rest-branch-commit-audited-as-main`, `branch-event-names-main`. The
  Reconcile half is item 111's (Left).

**How the merge (item 107) must treat a branch's files (choice made, Paul may overrule):** the merge swaps main's
pointer to the branch's marked file as it is, without copying or renaming it. Main's chain then holds marked files
only where main's pointer, or a commit on main archived with `branch: main` (the merge's swap), names them. Why: a copy
would write every merged version again and give its new file a hash the branch's commits never recorded; the marker
only has to keep a branch's files from counting as main's until main takes them.

Tests, Java only, in the fast build:
- `BranchCommitTest` (catalog-core, 11): the first and second commit, a view, two first commits racing, a transaction
  across two tables, the cap, a commit after the branch ended, a table gone from main, the freeze, the legal hold, the
  retention class's minimum, and the Idempotency-Key bound to the branch. Over a store double that judges a batch as
  the stores do (the branch held and alive at commit time, every swapped object active, every pointer as expected,
  and a branch's batch swapping only its own keys).
- `RefReadsTest` (catalog-server, 29, 15 of them new): commits, a view replace and a transaction on a branch through
  the real routes, guard and engine, writing to the in-JVM object store (which now takes PUTs), the renewal, and the
  findings' cases above. `writesOnABranchAre400` now holds for creates, drops, renames, registrations and namespace
  changes only.
- `MetadataFileNameTest#aBranchsFilesAreMarked`, `CatalogMetricsTest` (the `ref.kind` label), `RefSettingsTest` (the
  cap's default, its binding and its floor).

Heavy, written and compiled, not run (the overnight run's heavy build runs them):
`IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch` (a stock `RESTCatalog` reads a table on a tag made
through item 106's route, is refused a commit there, commits two tables in one transaction on a branch and appends a
data file, reads the branch's properties, snapshot and planned files back, and finds main unchanged; the module has no
Parquet writer, so the rows are read back as the planned data file and its record count), and `RefSuite`'s
`aBranchCountsItsOwnPointers` and `everyRefNamingATableListsItsPointers` on every store.

Bites in `bites/D33/`, 20 new: `branch-file-unmarked`, `branch-commit-swaps-main`, `branch-first-commit-reads-main`,
`branch-cap-unchecked`, `branch-commit-to-inactive`, `branch-key-unbound`, `branch-hold-ignored`,
`branch-retention-class-ignored`, `branch-commit-unheld`, `branch-ended-answered-as-table`, `branch-event-names-main`,
`rest-branch-commit-audited-as-main`, `rest-branch-commit-metered-as-main`, `rest-branch-vends-write-for-dropped`,
`rest-ref-serves-unregistered`, `rest-ref-load-unguarded`, `rest-branch-commit-unguarded`,
`rest-branch-maintenance-exempted`, `rest-branch-expiry-allowance`, `rest-branch-not-renewed`. Ten older bites this
change moved were recorded again with the same breaks: `D18/create-name-checked-first` and `D33/rest-tag-vends-write`
(fast); `D15/guard-not-held`, `D15/transaction-partial`, `D15/transaction-cap-unchecked`,
`D15/transaction-table-twice`, `D15/transaction-unguarded`, `M5/commit-unguarded`, `M6/real-near-expiry-304` and
`OBS-41/location-in-attributes` (heavy tests, checked with `git apply --check` only).
Each of the 20 new bites and the two fast re-recorded ones was run with `bites/run.sh <patch>...` and seen
to bite on an assertion, the tree restored after each: "22 bites, 0 not biting or not restored (0 of them
SETUP)".

Choices made (Paul may overrule):
- The cap counts the branch's own pointers with a store read before the swap, not inside the commit. Why: an exact
  count needs a check in the commit transaction of all four stores. First commits to different tables racing at the
  cap can each pass it, so a branch can end a few tables over; the merge (item 107) should refuse a branch over the
  cap with a message saying to split it, rather than run a transaction past P10's bound.
- A branch's own pointer starts at version 1, whatever the cut's version, and its first file is `00001-…`. Why: a swap
  that creates a pointer expects nothing and writes version 1 (`PointerSwap`); the cut's version stays in the cut
  entry and the commit event's `before`.
- The marker is the file name, `.branch-<ref id>` before `.metadata.json`, not a directory or an object tag. Why: a
  listing shows it, every store keeps it, and Iceberg reads any file name; the id, not the branch's name, since names
  can be reused once a branch ends.
- The `Commit` event's `branch` is the branch's name, as `ObjectRead.branch` is for a read (choice 13). A branch can
  never be named `main`, so `main` means main.
- The expiry moves after the commit, not in its transaction, and only once the move gains an hour
  (`Engines.RENEWAL_STEP`). Why: item 103 keeps the store's commit free of ref writes; a busy branch is not rewritten,
  nor a `ref` event sent, on every commit. A renewal that fails is logged and the commit stands.
- An Idempotency-Key used on a branch is bound to the branch's id, so the same key and body on main or another branch
  is "used for a different request" (400), as for a different table.
- A commit on a branch to a name its cut does not hold answers 404 before any authorization. Why: a branch creates
  nothing (question 1 (b)), so there is no create to authorize; asking for `create` would answer 403 for a table that
  is not there.
- Through a ref, an unregistered object answers 404, but unregister is not refused while a ref names the object. Why:
  the catalog no longer tracks it (D13), and refusing would let any ref block main's unregister.
- A transaction on a branch names each table's audit path by the object's id, since its engine finds each by its cut
  entry; on main by name, as before.

Left:
- The heavy build passing `IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch` and the two `RefSuite`
  cases: the overnight run's.
- Reconcile (item 111, finding 12), not this item's code: `Reconcile` must skip marked files
  (`TableFiles.onBranch`) when it builds main's chain, and filter the archive match on the ref, so only commits on
  `main` prove main's swaps. Tests: a branch's two commits past main's head are not taken while main's own successor
  still is; and a branch file plus main's file at the same predecessor do not count as two proven children. Bites for
  both filters. **Built with item 111 (2026-11-22, worktree-agent-a2bdec51732f81323):** the walk skips marked files,
  never the pointer it starts from (`ReconcileBranchFilesTest`, bites `D33/reconcile-takes-branch-files` and
  `D33/reconcile-skips-a-marked-pointer`); the archive's filter was already on main (`ArchivedCommits.read` keeps
  only commits on `main`; bite `M8/reconcile-archive-reads-other-branches`).
- Reconcile must skip branch files (finding 12, with item 111) before any build with this item goes to work, where a
  real failover reconcile can run. **Met once item 111's branch lands**: it carries the filter above.
- The merge (item 107) treats a branch's files as above, refuses a branch over the cap, and refuses a frozen table.
- A transaction on a branch cannot hold a table it leaves unchanged and has not committed to yet: it reads the cut's
  version, and `PointerHold` cannot say "no pointer here", so a first commit to that table on the branch landing
  meanwhile goes unseen. Holding an absent pointer needs the four stores.
- Finding 11: credentials scoped to a branch (item 113), and the M6 adversarial case (item 112).

## 2026-11-23: the overnight heavy build's two failures of this item, fixed

Branch `worktree-agent-a9284fd56a1d18c39`. The overnight run of 2026-11-23 tested main at 02b22ad.

- `PostgresStoreSuiteTest$Refs` and `CockroachStoreSuiteTest$Refs`, "every ref whose cut names a table lists the
  pointer its cut took, and a branch its own": `NullPointerException: metadataLocation`. Cause: b0b459b,
  `SqlRefs.pointers` called `row.wasNull()` after `ref(row)` had read the ref's columns, so it answered for
  `expires_at`, not `head_version`. A branch expires, so one with no pointer of its own was read as having one, and
  `RefEntry.At` refused its null location. A tag (no expiry) came out right by chance. Oracle and MongoDB read the
  heads apart and passed. Fixed: the head is judged before the ref is read. `SqlRefsRowsTest` (fast) checks the row
  on a stub `ResultSet` that answers `wasNull` as JDBC does; bite `D33/sql-ref-pointers-head-read-late` (BITES, 1 of
  3).
- `IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch` expected `BadRequestException` ("a tag is
  read-only") and got `UnsupportedOperationException`. Cause: a3876b9's test, line 176. A tag's `/v1/config` lists
  only reads (`AdvertisedEndpoints.READ`, item 104), so iceberg-core refuses the commit at its endpoint check before
  sending it, as designed. Fixed in the test, which now expects that; the server's own 400 stays covered by
  `RefReadsTest#writesOnATagAre400`.

Left: both run in the heavy build only; the next overnight run confirms them.

Seen again in the overnight run of 2026-11-23: `PostgresStoreSuiteTest$Refs` and `CockroachStoreSuiteTest$Refs` ("every ref whose cut names a table lists the pointer its cut took, and a branch its own", `NullPointerException: metadataLocation`) and `IcebergClientConfigTest#stockClientReadsATagAndCommitsOnABranch`; that run tested 02b22ad, before the fixes above (e0512ca), which the next overnight heavy build confirms.

### 106. Ref management routes: create, list, renew and delete, with P11's limits and expiry (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/main/java/nuna/catalog/server/governance/MaintenanceRoutes.java catalog-server/src/main/java/nuna/catalog/server/metrics/ catalog-server/src/main/resources/application.yaml catalog-audit/src/main/java/nuna/catalog/audit/ catalog-audit/src/test/java/nuna/catalog/audit/ catalog-server/src/test/java/nuna/catalog/server/governance/ deploy/README.md
- Effort: L, five routes with their rights, idempotency, limits, audit and meters, and the work list's expiry section, with route tests; waits on item 102

Part of item 26 (D33); its design section says why (choices 6, 11, 12 and 13).

What is missing: no route makes, lists, renews or deletes a ref. P11's limits and expiry exist only in
`build-plan.md`. No event type records a ref change (`EventType`), and a type with no `AuditRows` mapping fails there.

Build:
- `/management/v1/catalogs/{catalog}/refs`: list (paged), create (`name`, `kind`, `from`: main or a ref), read (for a
  branch, its changed tables that the caller can describe), renew, delete. Each write takes an Idempotency-Key
  (D10). The merge route is item 107's.
- Rights (choice 12): `create` on the catalog to create, renew and delete; any holding in the catalog to list and
  read. Refusals are `Denial`s.
- P11 as settings: `nuna.refs.max-branches` (50), `max-tags` (1,000), `branch-idle-expiry` (30 days),
  `branch-max-life` (180 days), `expiry-warning` (7 days). A create past a limit is refused, naming it. A commit or a
  renewal moves a branch's expiry 30 days ahead, never past 180 days from its creation (choice 11).
- The maintenance work list gains a `refs` section, the branches to warn of and those past expiry, and two
  maintenance routes, warn (once per branch) and end; the worker's side is item 108's.
- The audit event type `ref` (created, renewed, deleted, merged, expiring, expired), mapped in `AuditRows`; meters
  (choice 13). `deploy/README.md` names the settings.

Done when route tests (heavy) cover each route's rights, its idempotent replay and its audit, the limits, and a branch
past expiry answering 404 everywhere; `ManagementAuthorizationTest` and `MutatingRoutesAuditTest` include the routes;
`AuditRowsTest` maps the new type (fast). Fast build green; bites recorded in `bites/D33/`. Waits on item 102. Items
107, 108 and 109 wait on this one.

**Landing order (2026-11-20, at Paul's "can you fix these"):** items 106 and 108 land together, in one merge. Item 106's create route is the only way a ref comes to exist, and until item 108 lands the worker's orphan scan and expiry read only main, so a tag's or a branch's files could be deleted in between. Item 108's worktree starts from item 106's finished branch, and the session lands item 108's branch, which holds both. Item 105 may land on its own: without item 106 no branch exists to commit to.

**Progress, 2026-11-20 (worktree-agent-a7b98848ac0492b9e): built; not landed on its own. Items 106 and 108 land
together, in one merge: item 108's agent starts from this branch, and the session lands item 108's branch. Open until
the heavy build passes the route tests below.**
- Routes (`governance/RefRoutes.java`), under `/management/v1/catalogs/{catalog}/refs`: `GET` (paged, a branch past
  its expiry left out), `POST` (`{"name", "kind": "branch" | "tag", "from"}`, `from` defaulting to main; 201 with
  the ref), `GET …/{ref}` (for a branch, `changed`: each table and view of its cut it has its own pointer for, that
  the caller can describe, with the cut's version and the branch's), `POST …/{ref}/renew` (200) and
  `DELETE …/{ref}` (204). A ref answers `id`, `name`, `kind`, `from`, `created-at`, `created-by` and, for a branch,
  `expires-at`, `max-expires-at` and `expiring`. Create, renew and delete need `create` on the catalog, list and
  read any holding in it (choice 12), refusals audited as `Denial`s; each write takes an Idempotency-Key
  (`ManagementIdempotency`, D10) and writes its `ref` record to the outbox with the change.
- P11 as settings (`RefSettings`, `nuna.refs.*`): `max-branches` 50, `max-tags` 1000, `branch-idle-expiry` 30d,
  `branch-max-life` 180d, `expiry-warning` 7d. A create past a limit answers 409 `RefLimitException` naming the
  setting. A branch's creation and a renewal set its expiry 30 days ahead, never past 180 days from its creation
  (choice 11; `RefSettings.expiryAfter`, which item 105's commits use too). A branch past its expiry answers 404
  `NoSuchRefException` on every route and is left out of the list; its name stays taken (409, saying so) until the
  worker ends it.
- The worker's side here (item 108 calls it): the work list's `refs` section (`GET /management/v1/maintenance/work?
  section=refs`, also walked after `deletions`): items `{id, catalog, name, expires-at, action}`, `action` `end` for
  a branch past its expiry and `warn` for one within `expiry-warning` of it whose warning was not sent; one store
  page of one declared catalog per page, so a page can be empty with a token. `POST /management/v1/maintenance/refs/
  {id}/warn` sends one `ref` event (expiring) to the audit topic and the change stream, once for each expiry the
  branch reaches (200 `{"id", "expires-at", "sent"}`; 409 `RefNotExpiringException` outside the window; 503 while
  Kafka is down, the warning not marked sent). `POST …/{id}/end` deletes a branch past its expiry with a `ref` event
  (expired) naming the worker (204; 409 `RefNotExpiredException` for a tag or an open branch). Both are for a
  maintenance principal acting as itself.
- Audit and meters (choice 13): `EventType.REF` (`nuna.catalog.ref`, `RefChange`: created, renewed, deleted, merged
  with its tables, expiring, expired), mapped in `AuditRows` (the row names the catalog), on the change stream, with
  a golden line in `events-v1.jsonl`. Gauges `nuna.catalog.refs` {`catalog`, `ref.kind`} and
  `nuna.catalog.refs.expiring` {`catalog`}, read from the store at most every 30 s, the same on every server
  (`docs/observability/catalog-metrics.md`); the label `ref.kind` joins `CatalogMetrics.LABELS`.
  `deploy/README.md` ("Before production") names the settings and routes.
- Tests, Java only and run: `RefRoutesTest` (17: rights, create, limits, bad names and sources, renewal and its cap,
  a tag's renewal, delete, expiry everywhere, idempotent replay, the changed tables by describe, the worker's
  rights, warn once per expiry, a failed warning sent next time, end, the work list and its pages), `RefSettingsTest`
  (4), `RefGaugesTest` (3); `AuditRowsTest`, `EventCodecTest` (golden line), `ChangeStreamTest`,
  `CatalogMetricsTest` cover the new type and meters. Heavy, not run: `MutatingRoutesAuditTest` (a case and a
  refusal for each of the five writes) and `ManagementAuthorizationTest` (three new tests).
- Bites: 13 in `bites/D33/` (`ref-*`), and the two D13 change-stream bites recorded again (the event list they break moved). All 15 were seen to bite in the fast build, each designated test failing on an assertion with the bite applied; none was only checked with `git apply --check`. Create, renew and delete under `describe` or open to anyone, and list and read for outsiders, bite `RefRoutesTest`'s two rights tests; a renewal past the whole life bites `RefRoutesTest` and `RefSettingsTest`; unbounded limits, a branch past its expiry still served, a warning sent twice, the work list naming a warned branch again, the key ignored, an end before expiry and an end open to anyone each bite their `RefRoutesTest` test; `ref` left out of `AuditRows` bites `AuditRowsTest`, and off the change stream `ChangeStreamTest`.

Waits on the heavy build: `MutatingRoutesAuditTest#everyMutatingRouteEmitsItsAuditRecord` and
`#everyMutatingManagementRouteAuditsItsRefusals`, `ManagementAuthorizationTest`'s three ref tests, and the routes on
PostgreSQL (the store suites already cover the store side, item 102).

Beyond the `Touches:` line, the branch also changed `governance/` `AuditRuntime.java`, `GovernanceWiring.java`,
`ManagementIdempotency.java` and `RefSettings.java`, the metrics tests, `docs/observability/catalog-metrics.md`,
`build-plan.md` and `bites/` (D13 and D33); none is in items 103's or 104's areas. `application.yaml` was not changed.

Left for other items: a commit on a branch moving its expiry (item 105, with `RefSettings.expiryAfter`); the merge
route and `nuna.refs.max-merge-tables` (items 105 and 107: add it to `RefSettings`); the worker's calls (item 108).
`changed` reads every table's own pointer in the cut, one read each; a store listing of a branch's own pointers
(which the merge needs too) would make it one read.

Choices made (Paul may overrule):
- A route answering the expiry warning, rather than an outbox write: the warning changes nothing in the store, so
  the route delivers it at once through the relay's own fan-out (audit topic, then change stream) and fails closed
  503 without Kafka. Why: no store write exists that only appends to the outbox, and the store is item 103's.
- "Once" is a marker in the idempotency store, keyed by the ref's id and the expiry warned of, longer than any
  client's key (128 characters) so no request can take it, lapsing at that expiry. Why: no new store field; a
  renewed branch is warned of again as its new expiry nears.
- The worker's routes name a ref by id under `/management/v1/maintenance/refs/{id}/warn|end`, as the DR watermark's
  do. Why: the worker's lists span catalogs, and an id never names another ref.
- `ManagementIdempotency`, a copy of the Iceberg routes' protocol for the management API. Why: theirs is package-
  private in `rest/`, which item 104 holds.
- Every `ref` event goes on the change stream, not only the warning. Why: a ref made or deleted is a change to
  what engines read, as a namespace change is.
- `from` defaults to main; `kind` is required and case-insensitive; a name is at most 1,000 characters. Why: as the
  other management bodies.
- A renewal never shortens a branch's life, and one that would change nothing answers 200 with no record. A tag's
  renewal answers 400. Why: as an attribute set to its own value.
- A limit refusal is 409 `RefLimitException`. Why: it depends on what the catalog holds, as a conflict does.
- Holds and DR freezes do not stop a ref's create, renewal or deletion. Why: none changes a table on main; the
  merge (item 107) judges each moved table as a commit (choice 8).
- Defaults live in `RefSettings`, not `application.yaml`. Why: as `GovernanceSettings`.
- The gauges are read from the store and are the same on every server (take the maximum, not the sum). Why: the
  limits are per catalog, not per server.

**Security findings 8 and 9 (2026-11-22, folded in on item 108's branch, which carries this item):**
- 8: the two refusals that had tests but no bites now have them, each seen to bite:
  `bites/D33/ref-route-renew-needs-describe.patch` (a renewal asks only describe on the catalog;
  `RefRoutesTest#makingRenewingAndDeletingNeedCreateOnTheCatalog`) and `bites/D33/ref-route-changed-unfiltered.patch`
  (a branch's changed tables are listed whatever the caller can describe;
  `RefRoutesTest#readingABranchListsWhatItChangedThatTheCallerCanDescribe`).
- 9: a ref's `created-by`, a principal's id and so personal data, is shown in listings and reads only to a caller
  holding `create` on the catalog; the create and renew answers keep it, their caller holding `create`.
  `RefRoutesTest#whoMadeARefIsShownOnlyToThoseWhoMayMakeOne`; bite `bites/D33/ref-list-created-by-shown.patch`.
  Choice made (Paul may overrule): shown to `create` holders rather than dropped from the list. Why: those who may
  make and delete a catalog's refs are the ones who need to know whose a ref is, and elsewhere a principal's id is
  shown only to those who administer (audit readers, grant admins); the audit record names the maker for the rest.

### 107. Merge a branch into main, per table, in one bounded transaction (item 26)

- Added: 2026-11-20 by agent on item 26
- Decided: 2026-11-22, D33
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/Merge.java catalog-core/src/main/java/nuna/catalog/core/commit/CommitEngine.java catalog-core/src/main/java/nuna/catalog/core/commit/TableFiles.java catalog-core/src/test/java/nuna/catalog/core/commit/MergeTest.java catalog-iceberg/src/main/java/nuna/catalog/iceberg/MergeMetadata.java catalog-iceberg/src/test/java/nuna/catalog/iceberg/MergeMetadataTest.java catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/main/java/nuna/catalog/server/governance/ManagementGuard.java catalog-server/src/main/java/nuna/catalog/server/rest/Engines.java catalog-server/src/main/java/nuna/catalog/server/rest/AuditEvents.java catalog-server/src/test/java/nuna/catalog/server/governance/ catalog-tests-conformance/src/test/java/nuna/catalog/conformance/IcebergClientConfigTest.java
- Effort: L, the merge's plan, its judgement per table, the conflict report and dry run, and the race with main, over the store's batch (item 103); the merge engine's tests need only Java; waits on items 103, 105 and 106

Part of item 26 (D33); its design section says why (choices 7 to 10 and 12).

What is missing: nothing merges. D33: per table, with Nessie's semantics (changed only on the branch, main takes it;
changed on both, a conflict), all tables moving in one bounded multi-table transaction.

Build:
- `POST /management/v1/catalogs/{catalog}/refs/{ref}/merge`, with `dry-run`. For every table and view the branch
  changed: if main's pointer is still the cut's, main takes the branch's pointer (its metadata location, hash and
  projection) as its next version, journaled on main under the merge's commit id; if main moved too, or dropped the
  table, it is a conflict. Any conflict answers 409 listing every one (name, the cut's version, main's version, the
  branch's version, or dropped) and moves nothing (choice 7). Otherwise one `CommitBatch`: main's swaps, the
  branch's pointers held, the ref ended (item 103). A swap lost to a main commit plans again within P10's bound.
- Each moved table is judged as a commit on main: main's rules (D11), holds (D24) and freezes (D37) on the change
  from main's metadata to the branch's, and the caller's right for it on main, every table in one batched check,
  with `create` on the catalog (choices 8 and 12).
- At most `nuna.refs.max-merge-tables` tables (choice 9). The branch ends with the merge (choice 10). A `ref` event
  (merged, with the tables moved) and one `Commit` event per table on main.

Done when merge engine tests (fast build, Java only) cover: a table changed only on the branch moves; one changed on
both conflicts; one dropped on main conflicts; a race with a main commit; a hold set after the branch's commit; the
dry run. Route tests (heavy) with two stock writers, one on the branch and one on main: the merge moves only what
only the branch changed, main then reads the branch's rows there, and the rights and audit hold. Fast build green;
bites recorded in `bites/D33/`. Waits on items 103, 105 and 106. Item 109 waits on this one.

## Added 2026-11-22, before the build

**Decided 2026-11-22 (D33, amended):**
- Main's own maintenance does not make a conflict. Paul: "No, maintenance doesn't count". Only data, schema and
  property changes on main since the cut conflict; compaction and snapshot expiry on main do not, and the merge
  rebases over them. Say in this item how the rebase works (for example: main takes the branch's version, the next
  night compacts again, and choice 14 keeps the files), and test a branch that merges after main was compacted and
  expired.
- A DR freeze binds the merge, not commits on a branch. Paul: "Only the merge". Item 105 lets a branch commit to a
  frozen table; the merge of that table is refused while the freeze stands.

**From the security review (Nuna Security Expert session, 2026-11-22):**
- Finding 2 (medium): the merge must not reveal names the caller cannot see. Conflicts and moved tables the caller
  cannot describe are counted, not named, as item 106's read route already filters (`RefRoutes`, the branch's
  changed tables). A refusal over such a table follows Holdings (404 or 403) through `Guard.refused`. A fast test and
  a bite.
- Finding 3 (medium): the merge takes its right and its rules from the whole change between main's metadata and the
  branch's, because a merge carries no list of updates. Without that, a caller holding only write lands a schema or
  property change made on the branch with modify, and a branch that dropped old snapshots before a legal hold was set
  removes them from main while the table is held.
  - Every rule runs on that change: `drFreeze`, `pathProperties`, the `encryption.key-id` immutability rule, the D28
    file-IO property ban, `retentionHold` and `snapshotMinimumAge`.
  - D27, D20 and the D17 switch run through `Authorizer`.
  - The D11a journal entry and alert fire for a schema change on a regulated table.
  - Refusals go through `Guard.refused`.
  - Engine tests: a schema change needs modify; a hold set after the branch removed snapshots refuses the merge; a
    freeze refuses it.
  - Bites: the change classed as data-only; the hold skipped; the freeze skipped.

**What the console's design needs from the merge** (item 109's design, `docs/design/console/wireframes.html`
chapter 6, "Branches and tags"; the screens show these answers):
- The dry run's whole answer: the success shape; the 409's exception name (`RefMergeConflictException`) and message;
  for each table, the verdict of main's rules, holds and freezes (D11, D24, D37) on its change; main's commits since
  the cut (how many, by whom); and, for a table dropped on main, when and by whom.
- Before the click, whether the caller may merge: their right on each table the merge would move (write or modify).
- The merge's answer: main's new version for each table moved.
- The journal entry each merged version writes, readable as such.

## Built (2026-11-23, branch `worktree-agent-a35ef01c938c4c6c2`)

**Choice made (Paul may overrule): a merge gives main its own copy, so a DR reconcile never follows a branch's chain.**
Main does not take the branch's pointer and file. It takes its own copy of the branch's metadata, written unmarked
(`00042-<uuid>.metadata.json`) at main's next version, whose metadata log is main's log ending with main's current
file (`MergeMetadata.merged`). Its predecessor is therefore main's file, never a `.branch-` file. A reconcile after a
failover walks main's chain into a merged version as into any commit on main: a later file of main's proves it, or the
archive's `Commit` event on main for the merge's commit id does, and if neither does it fails safe as for any unproven
version. It never meets a branch's file, so item 111 can treat every marked file as outside main's chain. The cost is
one extra file per table moved; the branch's own files stay until cleanup, and its snapshots and data files are shared.
This replaces item 105's "main takes the branch's pointer as is" for the merge, and the `TableFiles.BRANCH_MARK` doc now
says so.

**How the rebase over main's maintenance works** (Paul, 2026-11-22: "maintenance doesn't count"). For each table the
plan reads three files: the cut's, main's and the branch's. If main has moved since the cut,
`MergeMetadata.beyondMaintenance(cut, main)` decides whether that was only maintenance:
- Main's definition must be the cut's: format version, location, properties, current schema, spec and sort order.
  Main may only have lost schemas and specs, as expiry removes unused ones.
- Every snapshot main added must be a `replace` (compaction, manifest rewrites).
- Every ref main has must be one the cut had, with the same retention, and reached from the cut's snapshot only
  through main's new `replace` snapshots.

Anything else (data, a property, a schema, a rollback, a new ref, a drop) is a conflict. A rebased table moves as the
branch has it: main takes the branch's version, so the snapshots main's compaction made disappear from main. That loses
no rows, because a `replace` snapshot holds no rows its parent lacked. The cut's data files are still there because
cleanup protects every file a ref names (choice 14, item 108). The files compaction wrote become orphans for the orphan
scan, and the next night compacts the table again.

What main's expiry removed since the cut stays removed (the security review's low note, 2026-11-23).
`MergeMetadata.merged` leaves out of main's copy:
- every ref of the cut that main no longer has and the branch left as the cut had it;
- then every snapshot of the cut that main no longer has and that no remaining ref names (the branch's head among
  them), as Iceberg's expiry removes ancestors.

The snapshots the branch made stay, and so does any older snapshot a ref the branch made or moved names. Time travel on
main therefore reaches no history main had expired. Without this, the copy listed the cut's snapshots again until the
next night's expiry: bounded, but main's history should stay as main's expiry left it. Tests:
`MergeMetadataTest#theCopyLeavesOutWhatMainsExpiryRemoved` and `MergeTest#aRebaseOverCompactionAndExpiry`. Bite:
`merge-copy-keeps-expired`. The rules judge the merge without main's maintenance snapshots (they are left out of the "before"
side), so the minimum snapshot age is not asked of them. A legal hold is the exception: under a hold the rules see
every snapshot main has, so a held table compacted since the cut cannot merge (`legal_hold`).

**Built:**
- `MergeMetadata` (catalog-iceberg): `beyondMaintenance`, `definitionChange` (what the branch changed beyond
  snapshots, refs and statistics, from the cut, which is main's definition once maintenance is set aside), `merged`
  and `journal`.
- `Merge` (catalog-core, from `CommitEngine.merging()`):
  - `plan(ref, cap)` is the dry run and each attempt. It counts the branch's own pointers first and stops past the
    cap. Then it walks the cut and, for each table or view with an own pointer, reads main and the cut and records:
    the conflict (changed beyond maintenance; dropped or purged on main; a view replaced on main; a regulated
    table's file failing its hash), or else `definition`, main's verdict and the D11a flag. It also records main's
    commits since the cut, counted from the versions, with who made the first 100 from the journal.
  - `merge(...)` refuses over the cap or on any conflict, then asks the caller's rights through a callback, then
    refuses on main's verdict. Only then does it write main's copies and send one `CommitBatch`: main's swaps, each
    journaled under the merge's id as one `merge-branch` update with a `Commit` event on main; every own pointer of
    the branch held; and `RefLock.End`.
  - A swap lost to a commit on main or on the branch plans again within P10's bound. A branch gone answers `Gone`,
    and an unknown outcome is settled by `findCommit`. A branch that changed nothing is ended with
    `deleteRef` and the merge's record.
- `CommitEngine`: `judge`, `load`, `committed` and `backOff` are now package-private, so the merge judges, reads,
  caches and alerts exactly as a commit does.
- The route `POST /management/v1/catalogs/{catalog}/refs/{ref}/merge` (`RefRoutes`):
  - A merge needs `create` on the catalog. It then asks, in one `Authorizer.decide` batch, `write` (data alone) or
    `modify` (a definition change, and always for a view) on each table, with storage access, so D27, D20 and D17
    apply. Refusals are audited through `ManagementGuard.refuse`; the rules' refusals (`legal_hold`, `dr_freeze`,
    `snapshot_min_age`) through `ManagementGuard.ruled`, as `Guard.refused` does for a commit.
  - Answers:
    - 200 with the ref, then each table moved: its namespace, name and kind, `cut-version`, `main-version`,
      `branch-version`, `version` (main's new one), `rebased`, `definition-change` and `metadata-location`.
    - 409 `RefMergeConflictException`, with a message naming each conflict as `ns.table (cut version 2, main
      version 3 | main dropped, branch version 1): why` and a `conflicts` array.
    - 409 `RefLimitException` past `nuna.refs.max-merge-tables`.
    - 400 for a tag; 404 for a branch gone.
  - It writes one `ref` event (MERGED, naming the tables moved) with the batch, beside each table's `Commit` event on
    main. An Idempotency-Key replays the answer.
- The dry run, `?dry-run=true`, needs any holding in the catalog and writes nothing. It answers 200 with:
  - `answer`: what the merge itself would answer now (the conflict, cap or rules error, else `{"code":200}`);
  - `may-merge`: `create` held and the right on every table that moves;
  - `tables`, each with the fields above plus `conflict` and `dropped-at`, `main-commits` (`count`, and `by` only
    for a caller holding `create`, as `created-by` is shown), `verdict` (`allowed`, and `type`, `message` and
    `reason` when refused) and `right` (`needs`, `allowed`);
  - `hidden`: how many tables the caller cannot describe. Those are counted, never named, in the tables and in the
    conflict message ("and 1 the caller cannot describe"): security finding 2.
- The journal entry each merged version writes reads as such: `[{"action":"merge-branch","branch":"dev","branch-id":
  …,"branch-version":2,"branch-location":…,"cut-version":2,"rebased-over":0,"definition-change":"properties [stage]
  changed"}]`. The same text is the `Commit` event's `updates`.

**How the security findings are met:**
- Finding 2: the real merge needs `create` on the catalog, which implies `describe` on every table in it, so its
  answers can name everything. The dry run asks `describe` per table in the same batch and counts what it may not
  name. Test: `RefMergeRoutesTest#theDryRunNamesOnlyWhatTheCallerCanDescribe`. Bite: `merge-route-names-hidden`.
- Finding 3: every rule of the profile judges the change from main's metadata to the branch's (`CommitEngine.judge`,
  main's engine, so the DR freeze binds the merge). The right comes from the whole change. D11a's flag, journal text
  and alert fire for a regulated schema change. Refusals are audited.
  - Engine tests: `MergeTest#aSchemaChangeNeedsModify`, `#aHoldSetAfterTheBranchCommit`,
    `#aHoldKeepsMainsMaintenanceSnapshots`, `#aFreezeRefusesTheMerge`, `#theRulesSeeTheWholeChange` and
    `#aRegulatedSchemaChangeIsJournaledAndAlerted`.
  - Route tests: `RefMergeRoutesTest#theRightFollowsTheChange` and `#mainsRulesRefuseAMergeAsADenial`.
  - Bites: `merge-change-as-data-only`, `merge-hold-skipped`, `merge-freeze-skipped` and
    `merge-route-rights-unchecked`.

**Tests**, all fast (Java only) unless marked:
- `MergeMetadataTest`: 12 tests.
- `MergeTest`: 15 tests. They cover a table moving, a view moving or conflicting, a branch that changed nothing,
  conflicts (both sides changed, dropped), the rebase over compaction and expiry, the race with main, a branch gone,
  the cap, rights, holds, the freeze, the rules, D11a and the dry run.
- `RefMergeRoutesTest`: 7 tests, covering a merge with its events, journal and key replay; rights; the 409; the
  rules' denial; the dry run's hiding; the dry run of a merge that would land; and a tag and the cap.
- Heavy, written and compiled, not run: `IcebergClientConfigTest#stockWritersMergeABranchIntoMain`. A stock writer
  appends on the branch while another appends to a different table on main. The merge moves only the first; main then
  plans the branch's data file from main's own (unmarked) file, and the other table keeps main's rows. A steward may
  ask the dry run but gets 403 on the merge. A second branch whose table main also changed gets 409.

**Other choices made (Paul may overrule):**
- Over the cap answers 409 `RefLimitException`, like the ref limits: it depends on the branch's state, not on the
  request.
- The dry run's `answer` is what the merge would answer whatever the caller holds. The caller's own position is shown
  separately, in `may-merge` and each table's `right`, so a viewer sees the conflicts and an author sees what they
  lack.
- A view moved on main since the cut always conflicts (main does no maintenance on views), and a view's change always
  needs `modify`, as a view replace does.
- A merge refuses the maintenance exemption (D20, D30): a maintenance principal merging a flagged table is refused as
  any engine that is not trusted, since a merge is not maintenance.
- "Data alone" means snapshots, refs and statistics. A branch that removed unused schemas or specs changed the
  definition, since maintenance runs on main only (choice 14).
- A refusal by the right releases the Idempotency-Key (403s are not recorded, as on every management route); a 409 is
  recorded.

Left:
- The heavy build running `IcebergClientConfigTest#stockWritersMergeABranchIntoMain`: the overnight run's.
- Who dropped a table on main. The store keeps only `droppedAt`, which the dry run shows as `dropped-at`; "by whom"
  needs the audit's `Drop` event or a store column.
- Item 111: Reconcile can rely on main's chain never naming a marked file, per the choice above.
- Statistics are not compared: statistics main set since the cut are replaced by the branch's, and the next analyze
  sets them again.
- A held table compacted on main since the cut cannot merge while the hold stands. Cutting a new branch, or ending the
  hold, lets it through.
- A flagged table (D20) merges only for a trusted engine. The console is not one, so for such a table the console can
  show the dry run but cannot merge it.
- The console's screens (item 109).

## 2026-11-23: the merge route's cases in the audit gate

Branch `worktree-agent-a9284fd56a1d18c39`. This item's landing (7990cf7) added `POST
/management/v1/catalogs/{catalog}/refs/{ref}/merge` without cases in `MutatingRoutesAuditTest`, so both of its tests
would have listed it as a route without a case at the next heavy build. Added: a branch that changed nothing merges by
ending, with its `ref` record; the steward, who holds manage-tags but not create, is refused and audited as a denial.

Left: those two tests still fail until item 114's `POST .../tables/{table}/maintenance/requests` gets its cases too
(the overnight run of 2026-11-23 found that gap; it is not this item's). The heavy build runs them.

## 2026-11-23: a branch made from a branch is judged against main's own pointer at the cut

Branch `worktree-agent-a23997304de169b82`.

**What was wrong.** A branch's own pointer numbers its versions from 1 (item 105), and a ref made from a branch takes
that branch's own pointer where it has one (`RefStore#createRef`), so such a cut's version is on the source branch's
line, not main's. `Merge.table()` judged whether main had moved by `onMain.version() != cut.version()`, and
`since()` read main's journal after `cut.version`. For a branch made from main that is right; for a branch made from a
branch it compared numbers from two sequences:
- Main moved, the numbers coincide: main looked unmoved and the merge would put the branch's metadata over main's
  commits, silently.
- Main unmoved, the numbers differ: main looked moved, and the cut's file (the source branch's) was taken as main's,
  so the merge was refused as a conflict ("main moved ref main …").
The same cut file also fed `beyondMaintenance`, `definitionChange`, the rebase and `MergeMetadata.merged`, all of
which need main's metadata at the cut, not the source branch's.

**What was done.** `Merge.base` (`catalog-core/src/main/java/nuna/catalog/core/commit/Merge.java`, line 379) finds main's pointer at the
cut, and `table()` (line 303) judges everything against it:
- For a ref made from main it is the cut, as before.
- For a ref made from another ref it walks down the `fromRef` chain (`listRefPointers` of the object). Each step must
  be proved: the source's cut is the pointer above, or the source's own journal entry at that version wrote that file
  (the source's own pointer then). Either way main was where it was at the source's cut, so the walk goes on until a
  ref made from main, whose cut is main's.
- Where a step cannot be proved (a source deleted, merged or no longer naming the object), the pointer reached is
  taken as main's only if main's own journal entry at its version wrote its file. Otherwise the table conflicts, so
  the merge answers 409 `RefMergeConflictException`: "where main was when the cut was taken cannot be known: the cut
  took version N of dev's own line, and a ref it came from has ended, …; make a branch from main and commit the
  change there".
- Main moved when its pointer's file or version differs from that base pointer's (line 328), never by a version
  number compared across lines. `beyondMaintenance`, `definitionChange`, the rebase's facts and `merged` read the base
  pointer's file; `since()` counts and reads main's journal after the base's version.
- `Merge.Table` gains `base`, main's pointer at the cut, empty when unknown; `cut` stays the pointer the ref's cut
  took. The merge's journal entry's `cut-version` is the base's version, in main's numbering.

Choice made (Paul may overrule): main's position at the cut is found by walking the ref chain with each step proved,
falling back to main's journal by file, and a merge that cannot place main refuses the table rather than guess. Why:
the store keeps every live ref's cut unchanged, and a branch's journal rows by its id, so a live chain proves main's
position exactly. Once a source branch has ended, its cut is gone; its own file appears in no main journal, and a
guess (by version, or by the nearest main file) is exactly what let a branch overwrite main. The fallback by file is
exact, since a main file is written at one version only, and keeps a branch merging whose cut came from main through
a source that has since ended.

**The dry run's fields, now:** `cut-version` is main's version when the cut was taken, in main's numbering, the same
line `main-version` is on, so `main-version` minus `cut-version` is main's commits since. For a branch made from main
it is the cut's version, as before. It is left out when main's position at the cut cannot be known (a conflict, whose
message says "cut version unknown"). `main-version` is main's version now; `branch-version` is the branch's own
version, on its own line from 1. `RefRoutes.table` and `.conflict` read `Table.base`.

**Tests** (`MergeTest`, 18, all pass, Java only):
- `aBranchOfABranchSeesMainMovedWhenTheNumbersCoincide`: `fix` made from `dev` made from main; `dev` committed three
  times (its own version 3), main appended once (version 3). The merge conflicts on main's append, names main's version
  2 as the base and one commit since by alice, and main keeps its append.
- `aBranchOfABranchMergesWhenMainIsUnmoved`: `fix` cut from `dev`'s own version 1, main unmoved at 2. It merges,
  carrying `dev`'s append and `fix`'s property, not rebased, journaled with `"cut-version":2`.
- `aBranchOfAnEndedBranch`: `dev` deleted after `fix` and `qa` were made from it. `fix`, cut from `dev`'s own file,
  conflicts with "cannot be known"; `qa`, whose cut `dev` took from main, merges by main's journal.
- A branch made from main is unchanged: the fifteen earlier tests pass as they were, and `theDryRun` checks that its
  base is its cut.

**Bites** in `bites/D33/`, recorded and checked with `git apply --check` (the fast build's `:bitesApply`), not run:
- New: `merge-base-by-version` (main moved judged by the cut's version; `#aBranchOfABranchSeesMainMovedWhenTheNumbersCoincide`)
  and `merge-base-unwalked` (the walk skipped, the cut taken as main's; `#aBranchOfABranchMergesWhenMainIsUnmoved`).
- Recorded again with the same breaks, since this change moved their lines: `merge-both-changed-moves`,
  `merge-change-as-data-only`, `merge-dropped-on-main-moves` and `merge-hold-skipped`.

Fast build (`./gradlew build`, with `:bitesApply`): "BUILD SUCCESSFUL in 5m 34s".

Left:
- The bites' run: the overnight run's.
- `MergeMetadata.journal`'s javadoc still calls its argument "the cut's version"; it is main's version at the cut.
- The console (item 109) reads `cut-version`; for a table whose base is unknown it is now absent.
- Status (`status/` is claimed by item 109's fixer, so it is here): fix made on 2026-11-23 in
  `worktree-agent-a23997304de169b82`; files `Merge.java`, `MergeTest.java`, `RefRoutes.java`, six bite patches and this
  item; not landed.

### 108. Cleanup protects every ref, and the worker warns of and ends expired branches (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-maintenance/src/main/java/nuna/maintenance/OrphanScan.java catalog-maintenance/src/main/java/nuna/maintenance/TableJobs.java catalog-maintenance/src/main/java/nuna/maintenance/Worker.java catalog-maintenance/src/main/java/nuna/maintenance/Management.java catalog-maintenance/src/main/java/nuna/maintenance/WorkerMetrics.java catalog-maintenance/src/test/java/nuna/maintenance/ catalog-maintenance/README.md catalog-server/src/main/java/nuna/catalog/server/governance/MaintenanceRoutes.java
- Effort: L, the orphan scan and expiry across several metadata files per table, the recheck of files recorded before a ref was made, and the expiry section, with heavy tests on Silo; waits on items 105 and 106

Part of item 26 (D33, P11); its design section says why (choices 11 and 14).

What is wrong: the worker loads each table on main (`Catalogs.loadTable`). Its orphan scan reaches every snapshot of
main's metadata (`OrphanScan.Reach`), which covers every Iceberg ref inside the table but no catalog ref, and its
expiry is Iceberg's RemoveSnapshots on main (`TableJobs.expire`). A branch's commits write files main's metadata never
reaches, which the orphan scan would queue once they are older than the orphan age; expiry on main queues files that
a tag's older metadata still reaches. P11: a branch or tag blocks file cleanup for everything it references.

Build (choice 14):
- The work list's table entries name the metadata location each ref reaches for the table (a tag's cut, a branch's
  cut and its own head). The orphan scan reaches across all of them, and expiry's candidates go through the same
  reach before they are recorded.
- The worker sends, with each recorded deletion, the time it read those locations; the catalog refuses the record
  (409) if a ref was made in the catalog since, and the worker reads again. Before deleting a due file, the worker
  checks it against every ref made after it was recorded, and completes it without deleting it if one reaches it.
- Maintenance stays on main: branches are neither expired nor compacted nor rewritten.
- The worker's side of expiry: from the work list's `refs` section (item 106) it sends each branch's warning once
  and ends each branch past its expiry.
- A meter of files kept for a ref.

Done when worker tests (heavy, Silo) show: a branch's files survive the orphan scan past the orphan age, and are
queued once the branch is deleted; a tag's files survive expiry on main; a tag made between expiry's read and its
commit keeps its files (the recheck); a merged branch's files are main's; an expired branch is ended and warned of
once. Fast build green; bites recorded in `bites/D33/` (main's reach alone; the recheck skipped). Waits on items 105
and 106.

**Landing order (2026-11-20, at Paul's "can you fix these"):** items 106 and 108 land together, in one merge. Item 106's create route is the only way a ref comes to exist, and until item 108 lands the worker's orphan scan and expiry read only main, so a tag's or a branch's files could be deleted in between. Item 108's worktree starts from item 106's finished branch, and the session lands item 108's branch, which holds both. Item 105 may land on its own: without item 106 no branch exists to commit to.

## Built (2026-11-22, branch `worktree-agent-a563f7f74bc5ac1ad`)

On item 106's branch, with item 105's store read `RefStore.listRefPointers` merged in (b0b459b; both land together).

- **The work list** names, in each table entry (the tables and requests sections), every ref whose cut names the
  table: `{"id", "kind", "metadata-locations"}`, a tag's cut, a branch's cut and its own head once it has one
  (`MaintenanceRoutes.refs`).
- **The worker** keeps every file a ref reaches (`RefReach`): before expiry, a manifest rewrite or an orphan scan
  records files left unreferenced on main, it reads each ref's metadata as the orphan scan reads main's, skipping the
  snapshots main still has, branches before tags, and records only the files no ref reaches. The others are counted
  in `nuna.maintenance.files.kept`, labelled `ref.kind`.
- **The record** (`POST .../objects/{id}/pending-deletions`) now requires `refs`, the ids of the refs the files were
  checked against. When a ref now naming the table is not among them, the catalog answers 409
  `RefsChangedException` with `refs`, the table's refs now as the work list shows them. The worker checks the files
  again against those, up to three reads (`Worker.REF_READS`), then leaves the table to the next scan as a failure of
  its job.
- **The recheck:** each due deletion in the work list names the refs created after it was recorded, less an hour
  (`MaintenanceRoutes.RECHECK_MARGIN`), and, when there are some and its table is active, the table's namespace and
  name. The worker loads the table, reads those refs with the table's access, and completes a file one reaches without
  deleting it. A dropped table's such files wait.
- **Expiry of branches:** the worker reads the refs section first in each scan, sends each warning
  (`nuna.maintenance.refs.warned`) and ends each branch past its expiry (`nuna.maintenance.refs.ended`); a failure is
  of kind `refs`.
- Maintenance stays on main: nothing in the worker expires, rewrites or compacts a branch.
- Tests: `RefReachTest` (5), `WorkerRefsTest` (4) and `MaintenanceRefsTest` (3), fast; `WorkerMetricsTest` covers the
  new meters and label. `RefCleanupTest` (heavy, Silo, 7 tests, written, not run) covers what "Done when" names and
  security finding 10. `MaintenanceRoutesTest` and `MutatingRoutesAuditTest` (heavy) send `refs` and check that a
  record without it is refused.
- Bites in `bites/D33/`, each seen to bite on an assertion in a fast test: `worker-main-reach-only` (main's reach
  alone), `worker-deletion-recheck-skipped` (the recheck skipped), `worker-refs-not-read-again`,
  `worker-expired-branch-not-ended`, `ref-record-refs-unchecked`, `ref-work-list-later-refs-omitted` and
  `ref-work-list-head-omitted`.
- The work list has five sections now, so a scan that cannot read it counts five `work-list` failures (`TelemetryTest`
  and the heavy `ScanMetricsTest`; item 114 left the heavy one at three). Eight bites the change moved were recorded
  again with the same break: `bites/D30/pending-deletion-before-window`, `pending-deletion-outside-table` and
  `pending-deletions-open` (heavy tests, `git apply --check`), `bites/OBS-40/last-success-on-a-failed-scan` (heavy,
  `git apply --check`), and `failure-counters-registered-late`, `failure-message-in-labels`,
  `files-deleted-counts-recorded` and `meter-not-in-docs`, each seen to bite in `WorkerMetricsTest`.

Choice made (Paul may overrule): the record names the ids of the refs the worker read, not the time it read them.
Why: a ref's `createdAt` is taken before its create transaction commits (on MongoDB before its cut is written in
batches), so a time check misses a ref whose create was under way while the worker read the work list; a list of ids
cannot.

Choice made (Paul may overrule): the recheck covers refs created up to an hour before the file was recorded. Why: it
covers a create under way while the catalog checked the record, which the id check cannot see, and clocks that
differ between replicas.

Choice made (Paul may overrule): a due file of a dropped table that a ref made since names waits, rather than being
checked or deleted. Why: a dropped table cannot be loaded to read the ref's metadata with its access, and the ref
also stops the table's purge (item 103), which deletes the file with the rest once the ref is gone.

Choice made (Paul may overrule): every ref the store holds protects its files, a branch past its expiry included,
until the worker ends it. Why: P11 says a ref blocks cleanup for everything it references, and the branch still
exists until it is ended; the refs section is read before the tables, so an ended branch's files are free at the same
scan's jobs.

Choice made (Paul may overrule): a file both a branch and a tag reach counts as kept for the branch. Why: a file is
counted once, and each kind is read in a pass that skips what the passes before it read.

Choice made (Paul may overrule): the worker reads a table's refs up to three times for one record, then leaves it.
Why: a table whose refs change at every read is rare, and the next scan tries again; expiry's files left unrecorded
are found by the orphan scan.

Choice made (Paul may overrule): `RefCleanupTest` makes commits on a branch and the merge through the server's store,
since items 105 and 107 have no routes on this branch. A branch's own head is a metadata file main once had.

Left: the heavy tests have not run. The overnight heavy build must run `RefCleanupTest` on Silo, the Ceph leg's
`:catalog-maintenance:test` (which runs it on Ceph), `MaintenanceRoutesTest`, `MutatingRoutesAuditTest` and
`ScanMetricsTest`, and the bites run must see the four heavy bites above bite. Once
item 105 lands, `RefCleanupTest` could commit on a branch through `lake@<branch>` instead of the store.

### 109. Console: the catalog's refs, the merge preview and a ref selector (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-server/src/main/resources/nuna/ui/ catalog-server/src/test/java/nuna/catalog/server/ConsoleTest.java catalog-server/src/test/java/nuna/catalog/server/governance/ConsoleBrowserTest.java
- Effort: L, the ten screens of Atelier's design (2026-11-22) and the server reads they need beyond items 104 to 107 (below); was M before the design; waits on items 104, 106 and 107

Part of item 26 (D33); its design section says why (choice 15).

What is missing: the console shows Iceberg's in-table refs on the table page ("Refs: branches and tags",
`detail.js`) and nothing of the catalog's refs; its audit lines read "on main".

Build: on a catalog, a refs panel: the list (kind, made by, made at, made from, expiry, and a branch's changed
tables), create, renew, delete, and merge, with the dry run's moves and conflicts shown before the merge button. A
ref selector on the namespace and table pages, reading through `catalog@ref`. The table page's section renamed
"Iceberg refs in this table". Branches near expiry marked. Only the actions the caller's rights allow are offered.

Done when `ConsoleTest` (fast) covers the new files' rules (no inline code, no markup written), and
`ConsoleBrowserTest` (heavy, Chrome) makes a branch, commits on it through a stock client, previews and merges it,
and reads a table as of a tag. Waits on items 104, 106 and 107. Item 110 waits on this one.

## The design, 2026-11-22

Atelier's design is the spec: `docs/design/console/prototype.html` (open with `?user=presenter` for every action,
`?user=gary` for the refusals, `&refs=full` for a limit reached) and `docs/design/console/wireframes.html`, chapter 6,
"Branches and tags": ten screens, each with a figure, its layout and a table of element, words, when shown and data,
every data source marked "106 built", "107 to build" or "gap". It settles the two labels (a catalog ref is a rounded
`@name` chip under "Branches and tags"; a table's own Iceberg ref is a square badge under "Iceberg refs in this
table"; the table tab "Snapshots and refs" becomes "Snapshots"), the refs tab and its limit, expiring, empty and ended
states, which ref a page reads (top chip, crumb `c@ref`, the "Reading @x" selector, a band; tags read-only with the
server's 400 text), contents through a ref, a ref's own page, the merge (dry run, conflicts, confirm, result, a race,
over the cap, a hold or freeze), make, renew and delete with every refusal `RefRoutes` sends, audit, and a phone at
390 px. Kept as drawn: an "Ended lately" panel for the last day.

**What the design needs beyond items 104 to 107** (the "gap" rows), built here unless the item named does it:
1. The limits: max branches, max tags and `nuna.refs.max-merge-tables`, from a route (or the list's answer).
2. Each branch's changed-tables count in the list.
3. The caller's rights before the click: whether they may create (on the catalog); for a merge, item 107 answers.
4. The dry run's whole answer (item 107).
5. The merge's answer with main's new version per table (item 107).
6. A branch's own commits, not only versions (the read route gives versions only).
7. Ended refs, and an expired branch still holding its name: a state on the list, or the `ref` audit records by
   catalog and subject.
8. A description on create. It needs a column on the `ref` record on four stores; if that proves larger than this
   item can carry, add it as an item of its own and leave the field out.
9. The cut time of a ref made from a ref, and the cut's version of each unchanged table.
10. Audit records filterable by their `branch` field.
11. The journal entry for a merged version (item 107).

## Built, 2026-11-23 (branch `worktree-agent-a1b1c1fe3dc725e19`)

The console, in the order the UI expert asked for (labels and the refs tab, then reading through a ref, then the
merge). New module `ui/refs.js`; `ui/route.js` carries the ref as `r=` on the catalog, namespace, table and view
pages, and their links keep it.

- **6.1, the two labels.** A catalog ref is the rounded `@name` chip (`cref`, the prototype's `--cref-*` and
  `--ctag-*` colours, ported into `style.css`); a table's own refs are "Iceberg refs": the tab "Snapshots", the
  panels "Iceberg refs in this table" and "History and Iceberg refs", the column "Iceberg refs".
- **6.2, the Branches and tags tab** on the catalog page, with its count: the four figures with the limits, the
  limit notice, the ref line, the list with its filter chips and actions, the empty state, the no-create line, Ended
  lately, and the three explanations.
- **6.3, which ref a page reads.** The top chip `trading@dev`, the first crumb `trading@dev`, the "Reading main ▾"
  selector with its menu (arrow keys, Escape), the band (branch, expiring, tag), the document title
  `{page} · {catalog}@{ref} · nuna`, and on a table: the notes on Snapshots, Properties, Health, Governance, Grants
  and Audit, "as @x has them" on the Snapshots figure, the overview's Health panel marked main's, no Compact now, the
  metadata log through the ref, Properties through a branch committing on it and through a tag read-only with the
  server's 400 text.
- **6.4, namespaces and tables through a ref.** Contents lists through `catalog@ref` with "Last commit here" and the
  "On @ref" column (changed here with the branch's version, as at the point, a namespace's count); namespace
  Properties with the note and the server's 400 text; a link naming a ref that is not live shows the catalog's 404
  for the warehouse and why (held after expiry, or ended, from the audit).
- **6.5, a ref's own page**: crumbs, title, sub-line, actions, the four figures, Changes (with each table's commits
  linked to its metadata log through the ref, and main since), What it keeps, Record, and the 404 of an unknown,
  ended or held ref.
- **6.6, the merge**: the dry run's verdict (ready, conflict, over the cap, refused by main's rules), table by table
  with each row's picture of point, branch and main, "Show the catalog's answer", In one transaction, the rules line,
  the button (hidden without create, disabled on a conflict or without `may-merge`), the missing-write line, What you
  can do; the confirmation (Main now, Main after); the result (Main was, Main is, its file) on the ended branch's page;
  a 409 race shown as the catalog answered, in the dialog, which stays open with its button gone; closing it reads
  the preview again.
- **6.7, make, renew and delete** with every refusal `RefRoutes` sends shown as the catalog answers it, and the
  design's added words for the limit, a name taken and a name held by an expired branch.
- **6.8, audit lines**: a `nuna.catalog.ref` record in the design's words (each action), commits "on @dev" and reads
  "of @dev" when the record's branch is a ref, and `ref` among the feed's Changes kinds.
- **6.9, 390 px**: the prototype's phone rules for the band, the selector's menu, the merge rows and the refs list are
  ported. Not looked at in a browser (below).

The server reads, for the gaps (fast tests in `RefRoutesTest` and `MetadataLogRefTest`):
- Gap 1: `GET …/refs` carries `limits` (`max-branches`, `max-tags`, `max-merge-tables`, and `branch-idle-expiry`,
  `branch-max-life` and `expiry-warning` as ISO durations).
- Gap 2: each branch in the list carries `changed-count`.
- Gap 3: the list and the read carry `may: {create}`. For a merge, the dry run's `may-merge` and each table's `right`
  (item 107) are used.
- Gap 6: `GET …/objects/{id}/metadata-log?ref=` reads the log as the ref has it: the branch's own journal above the
  cut, main's at and below it; through a tag, main's below the cut. Through a ref made from a branch, the versions
  between the two cuts are that branch's own while it is live, and main's below its cut. When the ref it was made from
  has ended, main's journal is read below the cut only if main's entry at the cut's version is the cut's file;
  otherwise the log stops at the cut and says so (`ref.log-stops-at-cut`), and the console words it. 404 for a ref
  that is not live or does not hold the object. The Changes tab links each table's commits to it.
- Gap 7: `GET …/refs?expired=true` also lists branches past their expiry, with `expired: true`; the console asks for
  them and shows the held name. Ended lately also reads `nuna.catalog.ref` records from the audit feed, for audit
  readers (D23).
- Gaps 4, 5 and 11 are item 107's, used here as built.

Tests: `ConsoleRulesTest` (new, fast) holds the console's file rules, moved from the heavy `ConsoleTest`, which now
serves the same list: no inline script, style or handler in the page, no module writing markup or styles, `refs.js`
among them, and a check that every module in the folder is in the list. `ConsoleBrowserTest` (heavy, written, not
run): `aBranchIsMadeCommittedOnPreviewedAndMerged` (the make dialog, a commit through `trading@<branch>` as an engine
sends it, the band, chip, crumb and selector through the branch, the merge tab against the dry run, the confirmation
and the result, main's property after) and `aTableIsReadAsOfATag` (the tag's properties, not main's later ones, the
band, chip and crumb, the read-only note equal to the catalog's own 400 for a commit through the tag, and "Read main
instead").

Bites in `bites/D33/`, each BITES and restored with `bites/run.sh`: `ref-list-limits`, `ref-list-may-create`,
`ref-list-changed-count`, `ref-list-expired-unasked`, `metadata-log-ref-own-journal`, `metadata-log-ref-cut`,
`metadata-log-ref-from-branch`, `metadata-log-ref-from-ended`, `console-refs-writes-markup` (new), and
`ref-route-list-for-outsiders` (recorded again against the new list).

### Choices made (Paul may overrule)

1. `changed-count` counts every table the branch changed, not only those the caller may describe; the design (6.10)
   proposed counting only the describable ones. The dry run already tells the same callers how many tables they cannot
   describe (`hidden`), so the total is not new to them, and counting by right would mean a check per table per branch
   on every list. So the list's "none you may describe" never shows; the ref's Changes tab counts the total and lists
   the tables the caller may describe, then "And {n} more you may not describe".
2. An expired branch that still holds its name comes from the list (`?expired=true`), not from the audit: the held
   state is the store's, and an audit read needs an audit reader.
3. The metadata log through a ref joins the branch's journal and main's at the cut, rather than a new commits route:
   it is the history of the file the ref reads, and it answers gap 6 with an existing route.
4. Contents through a ref leaves format, snapshots and last commit blank, with a line saying why, rather than
   loading each table through the ref (one load per row). Item 168 asks the server for them.
5. The file rules moved to a fast class of their own, `ConsoleRulesTest`: `ConsoleTest` starts Spring and PostgreSQL
   (heavy), and the item asks for the rules in the fast build.
6. Ended lately also lists merged branches (their `MERGED` record), since a merge also takes a branch off the list.
7. A dialog hides its button once its change is made: `dialogForm` enables it again after every answer, so a second
   click would have sent the same make, renew or delete again.

### Where this differs from the design

1. **The server's words win.** The design's 400 text names `commitTable` and `updateNamespaceProperties`; the server
   names the operations `updateTable` (`Guard.WRITE_DATA`) and `updateProperties` (`Guard.UPDATE_PROPERTIES`), so the
   console quotes those.
2. A rebased table (main moved by maintenance alone; Paul, 2026-11-22) gets its own row words, "Moves, over main's
   maintenance", and the rules line leaves out "A compaction or a snapshot expiry on main is a commit on main too",
   which the server no longer does.
3. No purpose line: the server keeps no description (gap 8, item 167).
4. The tree's links read main; the tree does not carry the ref (item 173).
5. The ref line has no dots for each commit: the list gives no commit times.
6. Changes, "Its commits": a count, linked to the table's metadata log through the ref, where each commit is listed,
   not each commit's time, operation and rows in the row.
7. Contents through a ref: format, snapshots and last commit blank, and "as at the point" without the cut's version
   (gap 9, item 168). The column the design calls Health keeps today's name, "Tonight's queue".
8. The band's and the ref page's point is worked out in the console from `from` back to main (`cutOf`); for a ref
   made from a deleted ref it says "the point @x was made from" (gap 9, item 168).
9. Record: no "Every record through @name in the last day" button, and no `@name` search in the feed (gap 10, item
   169); the tab scans at most four pages of `nuna.catalog.ref` records for the ref's id.
10. Ended lately and the held-name words give no time for the worker's next run: no route gives it.
11. A tag made from a branch shows "as @x had them" in the Changed cell, without a count; What it keeps has no
    counts of tables, changed or dropped since (the design marks both a gap).
12. A dropped table in the merge says when it was dropped, not by whom: the dry run gives `dropped-at` only.
13. The 6.4 edge states (dropped on main, made after the point) show the catalog's 404 as it answers, without the
    design's words after it (item 170).
14. Refusals other than 403 (the 409 race, a limit, a name taken) use the console's shared box, headed "The catalog
    answered with an error"; the design heads them "Refused by the catalog".
15. A read through a ref reads "loaded version {n} of @dev", the built console's words with the ref (design 6.8's
    "Built console today" row), not "Loaded the table through @dev".
16. Through a ref, the table page offers no "Export metadata": the export is main's metadata and journal (item 172).
17. A rebased table on the Changes tab shows a plain "Main maintained {n} times", not the warning "Main moved", since it
    still moves.

### The review, 2026-11-23

The coordinator's review of the UI and the metadata log, fixed on this branch:
- The metadata log through a ref made from a branch read main's journal below the ref's own cut; it now reads the
  source branch's (above). New cases in `MetadataLogRefTest`, and bites `metadata-log-ref-from-branch` and
  `metadata-log-ref-from-ended`; `metadata-log-ref-own-journal` and `metadata-log-ref-cut` recorded again.
- Health's Requests: no request buttons through a ref (requests are main's).
- The 409 race: `render()` closed the dialog, so the refusal was never seen. The dialog stays open with the refusal;
  closing it reads the preview again.
- The merge and delete dialogs open on Cancel; after a merge or a delete the result box takes the focus.
- A link naming a ref that is not live: the selector reads "Reading" with the name struck through, no item checked,
  and says so to a screen reader. The selector is a disclosure of links (`aria-current`), not a menu.
- A table read through a ref that is not live showed the 404 twice, and main's Health, Governance, Grants and Audit
  under the notes; the band alone shows it now, and those tabs show nothing.
- "Export metadata" is hidden through a ref (item 172 asks for an export through one).
- The ref line scrolls to now once it has a width (a `ResizeObserver`), and is 1,100 px wide on a phone.
- Smaller fixes: `cutOf` stops at a ref made after the one it walks from (a name used again); the make dialog's Kind
  is a labelled group; the `@` of a ref chip is easier to read; a merge row's icon follows the caller's right as well
  as main's rules, without a stray comma; Record says when the ref's records are older than the four pages it reads;
  the Snapshots note names a tag as a tag; a branch's settings say the change commits on it; a `nuna.catalog.ref`
  record in the audit feed links to its ref while that ref (by id) is live; Ended lately says "The name is taken
  again" when a live ref has it.
- `RefRefusalWordsTest` (fast) pins the console's 400 text for a change through a ref to `OnRef`'s.

### Left

- The two `ConsoleBrowserTest` cases have not run; the overnight heavy build runs them. No screen was looked at in a
  browser, at any width.
- Gaps 8, 9 and 10: items 167, 168 and 169. The 6.4 edge states: item 170. Export through a ref: item 172. The
  tree through a ref: item 173.

### 2026-11-23: a branch's own versions, the log's crash, the phone

Paul's look at the console found three faults, fixed on `worktree-agent-a0c6fea4baca09005`:
- **A branch's own versions number from 1** (item 105; `CommitEngine` `Base.nextVersion`), but 109's reads took them
  to go on from the cut's. The ref page counted a branch's commits as its version less the cut's ("0 commits on the
  branch", "0 commits" on each Changes row); it counts the branch's own version now (`refs.js`), and the merge words
  say "@dev's version N" and "the point's version C". The log through a ref (`LifecycleRoutes` `stretches`,
  `refLogPage`) read the branch's journal only above the cut's version, so a branch's commits dropped out and main's
  below the cut came out short. It reads the branch's own journal from its head down to 1, then each source's at and
  below its cut, as before for a ref made from a branch or from one that has ended. Each entry names its line
  (`branch`: the ref's name or `main`), shown on each row of the console's log; the page token names the line and the
  version (`main` and the version, say), and a token from main's own log is refused (400). `MetadataLogRefTest` and
  `RefRoutesTest` number a branch's journal from 1; `RefMergeRoutesTest#aBranchsOwnVersionsNumberFromOne` commits on
  a branch through `CommitEngine` and reads the ref and its log.
- **The log through @dev crashed** ("(updates || []).map is not a function"): with the old arithmetic it skipped the
  branch's journal and showed main's version 1, a create, whose updates are an object (main guards that since item
  161). A branch's first commit is a commit: its journal entry holds the request's updates, read by their actions.
  The crash was headed "The request did not reach the catalog"; that heading is kept for a request that did not
  reach it (`api.js` marks it), and any other exception in the console reads "The console failed to show this"
  (`dom.js` `failure`).
- **The ref line opened at its oldest end on a phone**: the `ResizeObserver` placed it once and stopped, so a line
  laid out first at another width (a page loaded wide, then narrowed, or a width that settles late) kept the first
  place. It places at today on every width until the reader scrolls it.
- Nits: the ref chip in the band now has its own outline, so no space before the punctuation after it is needed to
  read it; the tabs row on a phone scrolls with a fade at the edge it clips and keeps the current tab in view.
- Bites: `metadata-log-ref-own-journal`, `-cut`, `-from-branch`, `-from-ended` and D45's `metadata-log-open`
  recorded again with the same breaks; new `metadata-log-ref-own-above-cut` (the old numbering) and
  `metadata-log-ref-page-by-version` (a token's line ignored). Recorded and checked with `git apply --check`; the
  overnight run runs them.
- Item 107's merge (`catalog-core` `Merge`) compares main's version with the ref's cut version (`moved`, `since`).
  For a branch made from main that is right; for one made from another branch the cut is the source's own version,
  of another sequence. Not changed here; reported. Fixed the same day under item 107 (d179f353): the merge judges
  main against main's own pointer at the cut, walked down the refs a branch was made from, and moved by file, not
  by version number; `MergeTest`'s branch-of-a-branch cases and the bites `merge-base-by-version` and
  `merge-base-unwalked` hold it.
- Checked in a browser at 390 px against canned answers (the console's own code): counts, the log's lines, both
  failure headings, the band, the tabs. Not checked against a running catalog.

### 2026-11-23: the click-test of a branch made from a branch

As `ana` in the demo, through Keycloak: `@ct-from-dev` made from `@dev` in the console, one commit on
`markets.fx.rates` through `lakehouse@ct-from-dev`, and its merge preview: "point 5, main 5, @ct-from-dev 1", ready,
main takes the branch's version 1 as its version 6. Item 107's walk to main's pointer at `@dev`'s cut holds live.
The ref page's Delete said "It has no changes of its own" for that branch: `deleteDialog` read only `changed-count`,
which the list's refs carry and one ref's own answer (its page) does not; it counts the answer's `changed` too now,
checked live with the fixed `refs.js` served to the page. `ConsoleBrowserTest#aBranchIsMadeCommittedOnPreviewedAndMerged`
opens the page's Delete before merging (heavy; overnight); bite `D33/ref-page-delete-counts-nothing`.

### 111. After a regional failover, report every ref that names a file the DR store lacks (item 26, question 3)

- Decided: 2026-11-20, D33
- Added: 2026-11-20 by agent on item 26
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/Reconcile.java catalog-server/src/main/java/nuna/catalog/server/governance/ReconcileRoutes.java deploy/README.md catalog-core/src/test/java/nuna/catalog/core/commit/ReconcileRefsTest.java catalog-core/src/test/java/nuna/catalog/core/commit/ReconcileBranchFilesTest.java catalog-server/src/test/java/nuna/catalog/server/dr/DrRefReportRouteTest.java bites/D33/reconcile-takes-branch-files.patch bites/D33/reconcile-skips-a-marked-pointer.patch bites/D33/reconcile-ref-heads-unchecked.patch bites/D33/dr-reconcile-refs-unreported.patch backlog/105.md
- Effort: M, one more pass of the reconcile over the refs' metadata files, with a route test and a runbook step; waits on items 102 and 105, and on item 100, which holds `Reconcile.java`

Part of item 26 (D33, D37). Written for question 3's recommended answer (a), report only; another answer changes it,
and only Paul decides that.

What is missing: the DR reconcile and its rewind (D37, item 28) walk and rewind main's pointers only. After a regional
failover, a branch's head or a tag's cut can name a metadata file the DR store never got, and nothing says so.

Build: the reconcile's answer lists every ref whose cut or branch head names a metadata file missing from the DR
store, with the tables concerned. The runbook in `deploy/README.md` says to delete those refs, or keep them for the
primary's return. Branches are not walked or rewound.

Done when a route test (heavy) with a DR store missing one branch head's metadata file reports that branch and no
other ref. Fast build green; bite recorded in `bites/D33/`. Waits on Paul's answer to question 3, then on items 102,
105 and 100.

**Decided 2026-11-20 (D33, amended):** a report only, as this item says (question 3 (a), "A report only"); it no longer waits on that answer.

**Reconcile skips branch files (finding 12) before any build with item 105 goes to work, where a real failover
reconcile can run.** Security finding 12's Reconcile half is built here with the report, so this branch meets that
condition once it lands; item 105's Left says so too.

**Progress, 2026-11-22 (worktree-agent-a2bdec51732f81323): built; the Java tests run in the fast build; the route
test is written and compiles (heavy, not run). Open until the heavy build passes it.** Started from the uncommitted
work a session left on 2026-11-20 (a patch against 9c067cc and two test files): neither source file had changed since,
so its report and both tests were kept, the tests brought to item 105's marked files and item 106's routes.
- The report: `Reconcile.checkRefs(catalogId)` reads the catalog's refs (`listRefs`), walks each one's cut
  (`listRefNamespaces`, `listRefObjects`, tables and views) and, for a branch, reads its own pointer for each object
  of the cut (`readPointer(ref.key(id))`). It asks the DR store whether it holds each metadata file a ref names
  (`TableFiles.exists`), once a file however many refs name it, and answers every ref checked with the files it
  lacks (`RefFile`: the object's id and kind, its path in the cut, `CUT` or `HEAD`, the version and the location).
  Nothing moves.
- The route: `POST .../dr-reconcile` without `rewind` answers `refs` (each ref naming a missing file, or whose
  check had a `problem`: id, name, kind and `missing`) and `refs-checked`, with or without `apply`. The refs are
  read before any table is walked.
- The chain filter (finding 12): `Reconcile`'s walk skips every file `TableFiles.onBranch` marks when it builds the
  files that follow main's, so a branch's file is never a step, a tip or a proof of another file; the pointer the
  walk starts from is never judged so. Item 107 decided that main never takes a branch's pointer or a `.branch-`
  file: a merge writes main's own unmarked copy of the branch's metadata at main's version + 1, whose metadata log
  ends in main's previous file. So every marked file lies off main's chain, a merge is walked as any commit on main
  is (proven by a later file, the archive or `accept`), and only a `register` can start main's pointer on a marked
  file; the walk then follows main's unmarked files from it. The archive half was already on main:
  `ArchivedCommits.read` keeps only commits whose `branch` is `main` (`ArchivedCommits.java:67`,
  `ArchivedCommitsTest#readsEachTablesSwapsFromEveryChain`, bite `M8/reconcile-archive-reads-other-branches`);
  nothing changed there.
- The runbook: `deploy/README.md`'s DR steps gain step 6, "Check the refs": what `refs` lists, and to delete each
  listed ref or keep it for the primary's return; the thaw is now step 7.

Tests, Java only, in the fast build:
- `ReconcileRefsTest` (catalog-core, 7): a branch whose own pointer names a missing file is listed with that table
  and no other ref, though main's pointers name missing files too; nothing listed when the DR store holds every file;
  a missing cut file listed for every ref naming it, with its namespaces' names, the store asked once a file; a
  branch listed for its cut's file and its own, each named so; a view checked as a table is; a branch past its
  expiry left out; a file the store cannot answer for stops that ref's check with a problem and no other. Over a
  store double that pages one entry at a time.
- `ReconcileBranchFilesTest` (catalog-core, 3): a branch's two commits past main's head are not taken while main's
  own successor, proven by the archive, still is; a branch's file and main's at one predecessor, each with a file
  built on it, are not two proven files (main's is taken); a pointer on a branch's file, as a register can start it,
  still walks main's files after it.

Heavy, written and compiled, not run: `DrRefReportRouteTest` (catalog-server, `dr` package; the governance tests'
package is item 107's claim): a tag and two branches made through the refs route, one branch's commit through
`trading@<branch>`, its file taken out of the S3 store; the reconcile lists that branch with the table, `head`,
version 1 and the file, and no other ref naming a file of the test's table; main's pointer and the branch's are
unchanged; once the branch is deleted through the route it is gone from the report.

Bites in `bites/D33/`: `reconcile-takes-branch-files` (the filter removed; both first tests of
`ReconcileBranchFilesTest`), `reconcile-skips-a-marked-pointer` (the pointer judged by the mark too; the third),
`reconcile-ref-heads-unchecked` (a branch's own pointers not read; `ReconcileRefsTest`), and
`dr-reconcile-refs-unreported` (the route lists a ref only with a problem; `DrRefReportRouteTest`, heavy, checked with
`git apply --check` only). The three fast ones were run with `bites/run.sh <patch>...` and each bit on an
assertion, the tree restored: "3 bites, 0 not biting or not restored (0 of them SETUP)". Every recorded bite still
applies against this branch (538, `git apply --check`, and the fast build's `:bitesApply`).

Fast build (`./gradlew build`, 2026-11-23 after the overnight run released Docker): BUILD SUCCESSFUL in 6m 50s, 1462
tests, 0 failures.

Choices made (Paul may overrule):
- The report walks each ref's cut and reads a branch's own pointer for each object of it, not `listRefPointers` for
  each of main's objects. Why: a ref's cut names tables main has dropped since, whose files cleanup keeps for the ref
  (item 108), and gives each table's path as the ref has it. It costs one pointer read for each object of each
  branch's cut.
- Only the metadata file each pointer names is checked, as this item says, not the manifests and data its version
  adds. Why: the item asks for that, and checking a version whole as the walk does would read every manifest of
  every ref's tables. A ref whose metadata file reached the DR store but whose data did not is not listed.
- A branch past its expiry is left out: it is gone for every route, and the maintenance worker ends it.
- The refs are listed with the walk (`{}` or `{"apply": true}`), not with the rewind: the rewind's answer is about
  main's pointers, and the runbook reads the refs once, after the walk.
- The refs are read before any table is walked, so a store that fails them fails the request with nothing applied.
- A file the DR store cannot be asked about stops that ref's check, with a `problem` naming the file; the other refs
  are checked.

Left:
- The heavy build passing `DrRefReportRouteTest` (the overnight run's), and with it the bite
  `dr-reconcile-refs-unreported` run.

### 112. Branches, tags and merge: engines on refs, the docs and the gate (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-tests-engines/ docs/refs.md build-plan.md
- Effort: M, engine matrix cases and one page of docs over code already built; the heavy and overnight runs are the overnight run's; waits on items 102 to 111

Part of item 26 (D33); its design section gives the gate.

What is missing: once items 102 to 111 have landed, nothing has yet shown stock engines on refs, engine users have no
page saying how to name a ref, and `build-plan.md` still lists the milestone as not started.

Build:
- The engine matrix (`EngineMatrixTest`, overnight): Spark and PyIceberg read a table as of a catalog tag, commit to
  two tables on a branch, and are refused a write on a tag; the branch is merged and both read main.
- `docs/refs.md`: how an engine names a ref (`warehouse=<catalog>@<ref>`), what a branch and a tag allow, merge and
  its conflicts, P11's limits and expiry, and how catalog refs differ from Iceberg's refs inside a table.
- `build-plan.md`: the v1.x milestone line marked done, naming the run that closed it.
- From the security review of 2026-11-22 (finding 11, recorded by item 105): "a tag's credentials refuse PutObject"
  joins the M6 adversarial set, against a real store: a client holding a tag's vended credentials tries to write the
  table's files and is refused.

Done, and item 26 with it, the first morning the overnight run's heavy build, Ceph leg, `./nightly.sh` (the engine
matrix included) and bites are all green with items 102 to 111 landed. Waits on items 102 to 111.

## Built, 2026-11-23 (branch `worktree-agent-a5e5f6e6ecd0db4ce`)

Main merged in at 7d7609b7 (items 109 and 156 landed).

- **The engine matrix** (`catalog-tests-engines`, overnight, written and compiled, not run): `EngineMatrixTest#refs`,
  `@Order(6)`, between the audit table case and SingleStore (now 7). Two scripts:
  `images/python/refs_matrix.py` (setup, branch, merge, cleanup) and `images/spark/refs_matrix.py` (branch, main),
  baked into their images. Six Jobs:
  1. PyIceberg makes `m8.refs.a` (three rows) and `m8.refs.b` (one row) on main, tag `m8-tag`, a fourth row in `a`,
     then branch `m8-dev`, through the management API (`201 201`).
  2. Spark, on catalogs `tag` and `dev` (fid's settings, warehouse `engines@m8-tag` and `engines@m8-dev`):
     - reads the tag as cut (`3 6 1 1`: rows and id sums of a and b);
     - is refused an INSERT on the tag by the store (`refused store`);
     - is refused `ALTER TABLE … SET TBLPROPERTIES` there by Iceberg's REST client (`refused endpoint`);
     - inserts a row into both tables on the branch (`5 20 2 11`);
     - reads main, untouched (`4 10 1 1`).
  3. PyIceberg does the same: `append` refused by the store, a property change refused by the client, a row appended
     to each table on the branch (`6 40 3 31`), and main untouched.
  4. The merge through `POST …/refs/m8-dev/merge`:
     - the dry run moves a and b, and the caller may merge (`200 200 2 true a,b`);
     - the merge moves both (`200 a,b`);
     - the branch reads 404 after it;
     - PyIceberg reads both engines' rows on main (`6 40 3 31`).
  5. Spark reads the same on main, and the tag unchanged (`3 6 1 1`).
  6. The cleanup deletes the tag (204; the branch is already gone, 404), purges both tables and drops the namespace.
- **The M6 adversarial set** (`catalog-server`, heavy, written and compiled, not run):
  `VendingRouteTest#aTagsCredentialsRefusePutObject`, beside `selectOnlyCallersGetReadOnlyCredentials`, with real STS
  credentials on the run's S3 store. A caller holding create and modify makes a tag. Through `trading@<tag>` both the
  load and the credentials route vend read-only credentials (only READ sets audited): they read the table's file, and
  the store refuses PutObject of a data file and of a metadata file, CreateMultipartUpload and DeleteObject (403). The
  same caller's credentials on main write. The tag is deleted at the end (204). Bite
  `bites/D33/rest-tag-vends-write-on-a-real-store.patch`: `Lookup.writable` ignores the tag, so the tag's credentials
  write; checked with `git apply --check` (a heavy test), and applies again after the merge of main.
- **`docs/refs.md`**, written from the code. Its sections:
  - Naming a ref;
  - What a tag and a branch allow;
  - Making, reading and deleting refs, with the console's Branches and tags tab as it now is;
  - Merging a branch: maintenance not counting, conflicts, rules and rights, limits, what main gets, the dry run;
  - Limits and expiry (P11);
  - Files, purges and namespaces;
  - Taking a table out of refs;
  - After a regional failover;
  - Catalog refs and Iceberg's refs inside a table.

  There is no docs index (no README at the root or in `docs/`), so `deploy/README.md`'s "Branches and tags" bullet
  links it.
- **`build-plan.md`**: the v1.x line for branches, tags and merge says what is built, item by item, and that it closes on
  the first green overnight run with items 102 to 111 landed. Not marked done.

Checked: `./gradlew build` (fast) green after merging main: "All 571 bites apply.", "BUILD SUCCESSFUL". It compiles
both new tests; neither runs in it.

### Choices made (Paul may overrule)

1. **The adversarial case sits in `VendingRouteTest`, not `catalog-objectstore`'s `VendingTest`.** Whether a tag
   vends write is the server's decision (`Lookup.writable`); the vendor below it knows nothing of refs. The case also
   checks the same caller's credentials on main write, so it is the tag that refuses, not the caller's rights.
2. **The matrix asserts who refuses a write on a tag, not only that it fails.** Data writes are refused by the store,
   because both engines write data files before any commit, with the tag's read-only credentials. Metadata-only
   commits are refused by the client, because a tag's `/v1/config` leaves out the commit endpoints and both clients
   check them. Neither engine reaches the catalog's own 400, which the fast route tests cover. A change in either
   layer fails the case, rather than passing it through the other.
3. **Setup, the merge and the cleanup are PyIceberg Jobs calling the management API with `requests`** (PyIceberg's own
   dependency), not calls from the harness. The other cases reach the catalog only from inside the cluster, as Service
   `nuna-catalog`, and this keeps every step and every answer (CALL lines) in the Jobs' logs.
4. **Order 6, after the audit table case.** It stays out of that case's reads of the audit topic, and its Spark Jobs
   run before SingleStore takes its memory. It also runs against a stack already up (`-Pengines.stack`): it needs only
   the engine group's `create` on catalog `engines`. Its setup first removes what an interrupted run left.
5. **Main moves past the tag but not past the branch's cut.** The fourth row lands after the tag and before the
   branch. So the merge has nothing to conflict with, and the tag's reads differ from main's, which shows the merge
   left the tag alone.

### Where the code differs from this item's plan

1. **`build-plan.md`.** The plan says to mark the milestone done, naming the run that closed it. No such run has
   happened, so the line says what is built and what closes it.
2. **"Refused a write on a tag."** With stock engines the catalog never sees the write: the store refuses the data and
   the client refuses the commit. Iceberg Java 1.12 throws `UnsupportedOperationException` and PyIceberg 0.12
   `NotImplementedError`, both "Server does not support endpoint: POST
   /v1/{prefix}/namespaces/{namespace}/tables/{table}". The catalog's 400 ("updateTable is refused on tag eod of
   catalog trading: a tag is read-only") is what a client that does not check gets. The page says all three.
3. **Merge conflicts.** D33 says changed on both sides is a conflict. The code, after Paul's amendment of 2026-11-22,
   rebases over main's maintenance (compaction, manifest rewrites, snapshot expiry). Main's other changes conflict:
   data, the definition, and an Iceberg ref made or moved. So do a view main replaced, a table main dropped,
   unregistered or purged, and a regulated table whose file fails its hash. A held table compacted on main since the
   cut cannot merge while the hold stands.
4. **Topics the plan does not list.** The plan's list of topics leaves out the dry run, taking a table out of refs, the
   DR ref report, the purge and namespace rules, and the console. They were built or decided after the plan (items 107,
   111, 156, 157 and 109), and the page covers them.
5. **Naming a ref.** The plan says `warehouse=<catalog>@<ref>`. The code also takes the prefix in the path, treats
   `catalog@main` as main and matches names without regard to case. For a missing ref or an expired branch it answers
   404 `NoSuchWarehouseException` on `/v1/config` and every other route.
6. **P11 as `build-plan.md` words it.** It says "a branch expires 30 days after its last commit". In the code a commit
   moves the expiry only once the move gains an hour (`Engines.RENEWAL_STEP`). A branch past its expiry keeps its name
   until the worker ends it. The merge cap, `nuna.refs.max-merge-tables` (100), also bounds a branch's commits. The
   page follows the code; P11's row is left as Paul set it.

### Left

- Not yet run: the refs engine case (the overnight run's `./nightly.sh` runs the engine matrix) and
  `aTagsCredentialsRefusePutObject` (its heavy build). The new bite was checked with `git apply --check` only.
- The first run will test what could not be checked here:
  - the error text each engine gives for the store's refusal: Spark's failed task carrying the S3 403, and pyarrow's
    "access denied" or 403;
  - that both clients send `engines@<ref>` in the path as the catalog answers it.
- This item, and item 26 with it, closes the first morning the overnight run's heavy build, Ceph leg, `./nightly.sh`
  and bites are all green with items 102 to 111 landed. Item 110 (the demo) is still being built.

## Overnight run 2026-11-24: the refs case's first run failed in setup

`EngineMatrixTest#refs` failed in its first Job, `refs-setup`, after 11 s: PyIceberg's first `append` to `m8.refs.a`
raised `ValueError: Cannot write manifest for table version: 3`. The setup made `a` and `b` without a format version,
so the catalog made them v3 (`Metadata.DEFAULT_TABLE_FORMAT_VERSION = 3`), and PyIceberg 0.12 writes no v3 manifest
(the matrix's own `pyiceberg v3.write-refused` line shows the same). Nothing after the setup ran, so the store's and
the client's refusals, the merge and Spark's reads on refs are still unchecked.

Fixed on branch `worktree-overnight-fix-2026-11-24-catalog-tests-engines`: `images/python/refs_matrix.py` makes both
tables with `format-version` 2, as `pyiceberg_matrix.py` does for the tables PyIceberg writes. Not run: the engine
matrix is overnight only, so the next overnight run checks it.

### 113. Grants that name a branch (later, D33)

- Decided: 2026-11-20, D33
- Added: 2026-11-20 by local_505e1ac6-ef25-415d-a419-30d734018bd1
- Touches: catalog-store-api/ catalog-store-sql/ catalog-store-oracle/ catalog-store-mongo/ catalog-security/ catalog-server/src/main/java/nuna/catalog/server/governance/
- Effort: L, a grant gains an optional branch on four stores, and every authorization check on a ref asks for it, with tests and bites

Item 26's design (2026-11-20) builds branches with a table's grants holding on every ref (its choice 12), so whoever
may write a table on a branch may also write it on main. A grant that names a branch would let a group write on `dev`
without writing main, which changes D18's model (grants to groups on catalog objects) on all four stores.

Paul put it off on 2026-11-20 (D33, amended: "No, later"): it is not part of the branches milestone. Start it only
when Paul asks.

Done when a grant can name a branch, a write on a branch is allowed by a grant naming it or by the table's grant on
every ref, the governance routes and the console show the branch, and a test on each store shows it.

From the security review of 2026-11-22 (finding 11, low, recorded by item 105): a branch's vended write credentials
cover the table's whole prefix, main's files included, so a group allowed to write only on `dev` could still write
main's files with them. A grant that names a branch needs credentials scoped to the branch rather than the table's
whole prefix; the catalog marks only a branch's metadata files (`<version>-<uuid>.branch-<ref id>.metadata.json`), so
the engines' data and manifest files would need a place of the branch's own too.

### 115. Row filters and column masks stored in the catalog, served to Trino (D20)

- Decided: 2026-11-20, D20
- Added: 2026-11-20 by Paul
- Touches: catalog-store-api/ catalog-store-sql/ catalog-store-oracle/ catalog-store-mongo/ catalog-security/ catalog-server/ demo/
- Effort: L, a new kind of policy on four stores with a schema step, management routes and audit, a route Trino's access control calls, Trino reaching nuna as a trusted engine for its users, and a demo leg, with tests and bites

Paul chose row-level security through trusted engines on 2026-11-20 (D20, amended): nuna holds the row filters and
column masks, and Trino applies them. Today nuna has only half of it. A table flagged with a row/column policy gives
storage access only to registered trusted engines (`Authorizer`, rule D20, in `catalog-security`), but the rules
themselves are nowhere in nuna, and the demo's Trino sets none (`demo/k8s/trino.yaml` configures no access control).

What is missing:

1. **The rules, in nuna.** A row filter (an SQL predicate) and column masks (an SQL expression per column) on a table,
   for a group, stored on all four stores beside the grants, set, listed and removed through the management API, and
   audited as grants are (D23). Who may set them under D18's separation of duties (the grant admin, the tag steward,
   or a duty of its own) is Paul's to decide; ask before building the routes.
2. **A route Trino asks.** Trino's OPA access control asks an outside service for each table's row filters and column
   masks (`opa.policy.row-filters-uri`, `opa.policy.column-masking-uri`; check both against the demo's Trino version).
   nuna answers that request itself, or feeds an OPA server that does; the work picks one and says why.
3. **Trino reaching nuna as a trusted engine.** The demo's Trino sends each user's own token
   (`iceberg.rest-catalog.session=USER`, `demo/k8s/trino.yaml`), so nuna sees the user, not the engine, and a flagged
   table refuses storage access through Trino. Trino must come as itself and name the end user (`X-Nuna-End-User`,
   `catalog-server/.../security/Authentication.java`), or nuna must accept another form Trino can send, such as a
   token exchange with Trino as the actor. Not checked by running.

Out of scope: SingleStore. Its pipelines read as their own principal (`demo/singlestore/load.sql`), copy whole tables
into SingleStore, and have no hook to fetch rules from nuna, so registering it as a trusted engine would hand every
row of a flagged table to SingleStore's own access control.

Done when a group's row filter and column masks on a table are stored on all four stores and managed and audited
through the management API; in the demo, two users in different groups run the same Trino query on a flagged table
and get different rows and masked columns; a client that is no trusted engine still gets 403 on that table's storage
access; and a bite that drops the filter from nuna's answer to Trino makes a test fail.

### 118. Untrusted engines read flagged tables through filtered copies made by a fleet, delivered by scan planning (D20)

- Decided: 2026-11-21, D20
- Added: 2026-11-21 by Paul
- Touches: catalog-server/ catalog-security/ catalog-store-api/ catalog-store-sql/ catalog-store-oracle/ catalog-store-mongo/ catalog-objectstore/ catalog-maintenance/ deploy/helm/ demo/
- Effort: XL, the spec's three scan-planning routes, a fleet of filtering jobs with its own identity, temporary copies with their own credentials, lifetime and audit, a cache of copies, and a demo leg, with tests and bites on four stores; waits on item 115

Paul decided on 2026-11-21 (D20, amended) that clients which are no trusted engine will read tables flagged with a
row/column policy the way Databricks Unity Catalog serves them: the engine never gets the table's files. A fleet nuna
controls reads them, keeps only the rows and columns the caller may see, and writes filtered temporary files, and
the engine gets credentials for those alone. It is a separate item from 115 because Paul may build 115 first.
Today such a client gets 403 on a flagged table's storage access (`Authorizer`, rule D20).

The delivery is the spec's server-side scan planning (`spec/rest-catalog-open-api.yaml`: `planTableScan`,
`fetchPlanningResult`, `cancelPlanning`):

1. A flagged table's config tells a client that is no trusted engine to plan on the server
   (`scan-planning-mode=server`), and nuna advertises the plan routes.
2. The plan request carries the query's `filter` and `select`, so the fleet copies only what the query needs.
3. nuna answers `submitted` with a plan id and starts a job. The job reads the real files, applies the caller's row
   filter and column masks (item 115's rules, so this item waits on it), and writes Parquet files with the table's
   field ids to a temporary prefix.
4. When the client polls, the answer lists only the temporary files, with a credential scoped to that prefix.

What the work settles:

- **The fleet.** Its own identity, which alone reads flagged tables for this purpose and is audited each time (as the
  maintenance exemption is, D20/D30). The maintenance worker already launches Spark as Kubernetes Jobs
  (`catalog-maintenance/.../KubernetesJobs.java`); at work that presumes Kubernetes compute for Spark (PLT-7, open).
- **The copies.** Written with the table's storage profile KMS key (D26), deleted once their credentials expire,
  audited (D23), read-only: an untrusted engine still gets 403 on a write to a flagged table.
- **Latency.** A cold copy takes seconds to minutes. Cache copies by snapshot and by the caller's set of rules, so
  callers in the same groups share one.
- **Clients.** Per `research_notes/Enterprise Iceberg REST catalog plan/rest_catalog_spec.md` (client matrix): Java
  1.12 and Spark, PyIceberg, DuckDB and iceberg-go plan on the server; Trino 484 and Snowflake do not; SingleStore
  depends on the Iceberg Java version it carries. A client that cannot plan on the server still gets 403, unless Paul
  asks for a fallback (`loadTable` answering with a filtered copy of the whole table).

Done when, in the demo, a client that is no trusted engine (PyIceberg or Spark with a user's own token) reads a
flagged table and gets only the rows and unmasked columns that user's groups allow, never a credential for the
table's own files; two users in different groups get different results; the copies are gone after their credentials
expire; each copy is audited; and bites show a test fails when the filter is skipped or the credential covers the
table's own prefix.

### 123. Heavy build: Docker-backed services stall 30 s while two heavy modules share Docker

- Added: 2026-11-22 by heavy runs 2026-11-22
- Touches: catalog-server/src/test/java/nuna/catalog/server/ catalog-server/src/test/resources/spring.properties CLAUDE.md
- Effort: M, finding what stalls first (Docker Desktop's port forwarding, or CPU starved by two stacks), then either a schedule that keeps the heaviest modules apart or waits that outlast the stall; shown only by overnight runs

Of three full heavy builds run on 2026-11-22 with the nuna-demo stack down (`./gradlew --continue build -Pheavy`, two
modules under test at a time by `org.gradle.workers.max=2`), the second lost five tests to waits that ran out, and
the first and third lost none of them. Each failed beside another Docker-heavy module and passed when its classes
were run alone straight after (`AuditReadRouteTest` 6/6, `ManagementAuthorizationTest` 23/23, `WorkerTraceTest` 1/1).

- `AuditReadRouteTest` (three cases, 16:00 to 16:02 UTC, beside `catalog-store-mongo`) and
  `ManagementAuthorizationTest#aRefusalIsAuditedAsADenialOfTheRoute` (16:06, beside `catalog-store-oracle`): the test
  JVM's Kafka clients sent requests to the shared broker that went unanswered for the whole 30 s request timeout ("due
  to node -1 being disconnected (elapsed time since send: 30017ms ... request timeout: 30000ms)", and the same for the
  group coordinator). The audit route answered 503 "The audit topic cannot be read now" (`RecentAudit`'s 10 s
  metadata wait: "Timeout expired while fetching topic metadata"), and the test's own consumer read no record in its
  30 s (`Api.awaitRecord`: "no matching audit record among 0 read").
- `WorkerTraceTest#aScanAndTheCatalogsSpansForItsCallsAreOneTrace` (15:53, beside `catalog-server`): the worker's scan
  loaded the test's empty table at 15:53:37 and logged nothing more until the test gave up at 15:54:07, 30 s
  (`ARRIVAL`) later; its expiry of a table with no snapshots should have taken milliseconds.

Item 55 is the same kind in the nightly (one 15 s stall reaching Docker's containers, beside the CockroachDB cluster),
and the overnight run of 2026-11-19 lost `TelemetryTest#theStandardVariablesAloneTurnExportOn` to a 20 s metric wait.
The logs were in the session's scratch folder and the worktree's build folders, which are gone; the quotes above are
from them.

Done looks like: what stalls is known, and either the heavy build keeps such modules apart or the waits that meet it
outlast it; ten overnight heavy builds in a row lose no test to a wait running out.

**2026-11-22, what stalls: Docker's memory.** The same four test tasks as the second run (`:catalog-server:test`,
`:catalog-maintenance:test`, `:catalog-store-mongo:test`, `:catalog-store-oracle:test` with `-Pheavy --continue`, two at
a time, the demo down) ran twice with a sampler logging, each second, the Docker VM's pressure-stall counters
(`/proc/pressure`), free memory and swap, and the time of a fresh request to a container through a mapped port.

- Before the fix (30m41s): two tests failed, `TwoIssuersTest#aTokenForAnotherAudienceIsRefused` (Keycloak sent no
  bytes for 30 s) and `DrRewindRouteTest#aFrozenTableAboveItsWatermarkIsRewoundToIt` ("no matching audit record among 0
  read", the second run's failure). Both fell in minutes when the VM's 2 GB of swap was filling, from 0.4 GB to 2.0 GB,
  and every task in it stalled on memory for 10 to 50% of each second (312 s at 10% or more). The mapped-port probe
  answered within about 1 s throughout: the port forwarding did not stall; the processes behind it did. The memory
  was catalog-server's test JVM's: it held every container it had started until it exited, an Oracle (2.5 GB limit),
  a MongoDB, two Keycloaks (one with 471 MB swapped out), a Kafka for each governance context and a PostgreSQL for
  each of its contexts (twelve by mid-run), about 4 GB idle beside the Mongo and Oracle suites. Once that JVM exited,
  swap fell to 0.4 GB and 4.5 GB was free with the Oracle suite still running.
- The fix: catalog-server's tests keep one Spring context at a time (`spring.properties`, `maxSize=1`), so Spring
  closes the last context, and its PostgreSQL and Kafka, when a class needs another; and the four initializers that
  start containers for one class (`OracleBackendTest`, `MongodbBackendTest`, `TwoIssuersTest`, `ConsoleSignInTest`)
  register their stop with that context (`StopWithContext`), after its beans. Neither keeps modules apart nor
  lengthens a wait: it removes the overcommit. CLAUDE.md (Builds) gives the rule for new tests.
- After the fix (20m50s): every test passed (catalog-server 343, maintenance 89, Mongo 223, Oracle 247 with 3
  skipped as always). Swap stayed at 0.4 GB, at least 2.7 GB was free, memory stalls fell from 77 s to 20 s, and
  catalog-server held at most two PostgreSQLs, one Keycloak and one Kafka at a time; its tests took 14.8 min instead of
  23.6. The short stalls left came with Oracle starting.

The item stays open for its last test: ten overnight heavy builds in a row that lose no test to a wait running out.

A second cause, found on 2026-11-22 by two nightlies run by hand (`status/2026-11-22-nightly-2026-11-22.md`): Windows
out of RAM. With other sessions' fast builds running, Windows had 241 to 760 MB available and paged in a median 22,700
pages a second, and the Docker VM was resident at as little as 991 MB of its 8 GB; containers took 44 to 95 s to
start, a Kafka never did, and the VM's own pressure stalls stayed near 0, since a guest stalled on a host page fault
counts it as CPU. This hits the heavy build as it hit the nightly, so a heavy build that loses a wait should be
read beside Windows' numbers, not only the VM's. Item 135 is the host's memory (Paul decides the remedy); item 136
keeps other sessions' builds off a run by hand.

### 135. Windows pages the Docker VM out during the nightly: free the host's memory (Paul decides how)

- Added: 2026-11-22 by nightly by hand 2026-11-22
- Decided: 2026-11-22, D46
- Touches: none
- Effort: S, once Paul picks a remedy: applying it and reading one overnight run's numbers; waits on Paul

The machine has 15.7 GB, and during both nightlies of 2026-11-22 Windows was paging the Docker VM's memory out
(`status/2026-11-22-nightly-2026-11-22.md`). A container whose memory is in Windows' page file stalls on every touch
of it, and inside the VM that shows as CPU, never as memory pressure, so the VM's own numbers look healthy.

Evidence, from `Get-Counter` and `Get-Process` every 10 s:

- With other sessions' fast builds running (nightly 1, 16:10 to 16:30): 241 to 760 MB available, a median 22,700
  page-ins a second, peak 160,000; the VM resident at 991 MB of 8 GB at worst. Thirteen tests lost a wait: PostgreSQL
  and Kafka not started in 60 to 90 s, CockroachDB statements past their 15 s, the CAS on PostgreSQL and CockroachDB.
- With no other build (nightly 2, 16:30 to 17:58): 65 to 1,700 MB available (median 655), a median 16,800 page-ins a
  second, peak 136,000; the VM resident at a median 2.9 GB. Every test passed, but the CockroachDB CAS took 1,277 s
  for 1,000 rounds, against 342 s on an idle host on 2026-11-16.
- Who holds the memory: the Docker VM commits 8 GB, 27 Claude processes 5.8 GB, Edge WebView and ChatGPT 2 GB
  each, VS Code 1.1 GB, Chrome 0.8 GB. Windows' non-paged pool was 1.76 GB (a few hundred MB is usual) and WUDFHost,
  a host for user-mode drivers, had 3.2 GB committed: both look like a driver leaking, which a restart clears.

The remedies are changes to Paul's machine, so Paul picks:

1. Before midnight, close what the run does not need (ChatGPT, Chrome, VS Code, idle Claude sessions), and restart
   Windows now and then to clear the non-paged pool and WUDFHost.
2. Let WSL give the VM's idle memory back to Windows: WSL's `autoMemoryReclaim` setting in
   `%UserProfile%\.wslconfig` (none exists now; check the installed WSL version's documentation for its section),
   which needs `wsl --shutdown` once, so Docker Desktop restarts.
3. Fewer containers at once in the nightly's step 1 (`--max-workers=1`): a smaller peak for the VM, and a longer run.

Done looks like: the remedy Paul picks is in place, and an overnight run's nightly passes with Windows above 1.5 GB
available and the VM resident at its working size throughout (sampled as `status/2026-11-22-nightly-2026-11-22.md`
says).

Paul, 2026-11-22: "Lets do 1 and 2 and 3". Done that day (worktree-host-memory-135):

1. Closing ChatGPT, Chrome, VS Code and idle Claude sessions before midnight, and restarting Windows now and then,
   are Paul's to do: nothing in the repo does them.
2. `%UserProfile%\.wslconfig` now sets `autoMemoryReclaim=dropCache` under `[experimental]`. WSL 3.0.5 is installed,
   and Microsoft's page on `.wslconfig` (dated 2026-10-16) gives `dropCache` as the default already: the VM's cached
   memory goes back to Windows as soon as the VM is idle. So the file pins the behaviour rather than changing it, and
   WSL was not restarted for it. It returns nothing while the VM is busy, which is the whole of a nightly.
3. `nightly.sh` runs its first step with `--max-workers=1`: one module's containers and one test JVM at a time. From
   the second nightly's module times, step 1 takes about 2 h 15 min instead of 1 h 15 min, which still leaves the
   overnight run hours for its bites before 06:30. `./nightly.sh --test-dry-run` passes. CLAUDE.md (Builds) says so,
   and says to check Windows' memory when a container-backed test loses a wait.

The decision is D46 in `decision-ledger.md`. The item stays open until an overnight run's nightly passes with
Windows above 1.5 GB available throughout; overnight.sh does not sample Windows yet, so that run's numbers have to
be taken by hand.

**Seen in the overnight run of 2026-11-23 (sampled by hand every 10 s, 20:10 on 2026-11-22 to 07:00): not met.**
The nightly passed (91 min), but Windows was below 1.5 GB available in 449 of the nightly's 490 samples (00:44 to
02:17), at 200 MB at its lowest (02:00:31), and paging in over 1,000 pages a second in 350 of them; the VM's working
set swung between 1.3 and 5.5 GB, so it was being paged out. The heavy build and Ceph leg before it (00:00 to 00:44)
were worse: below 1.5 GB in 228 of 232 samples, 47 MB at the lowest. The host stayed short after the run too (21 MB
at 02:41, 1.15 GB at 07:00), so something besides the run held Windows' memory overnight. The remedies of D46 are in
place; they are not enough. The log is the session's scratch file, not kept in the repo.

### 151. Brokerage world 2: trading and custody, the OMS in SingleStore and the stock record and CNS in CockroachDB

- Decided: 2026-11-22, D48
- Added: 2026-11-22 by local_0484717c-9adc-4b18-af93-2db236201f4a
- Touches: demo-datagen/ demo/singlestore/ demo/cdc/cockroach.sql demo/cdc/connectors/ demo/demo.sh demo/README.md
- Effort: L, the OMS tables in SingleStore and their change feed, the clearing domain in CockroachDB and Iceberg, and three reconciliation views; waits on item 150

Second phase of D48 (`docs/design/brokerage-world.md`). The ledger's fills come from somewhere: an order management
system and a clearing house.

What to build:
- In SingleStore `ops`, replacing the toy `ops.orders`: orders, order events, routes and executions, consistent with
  the ledger's fills (same fill ids, prices, quantities), copied to `brokerage.trading` by the change feed; securities
  and closing prices loaded back from Iceberg by Iceberg pipelines.
- `brokerage.clearing`: CNS obligations per security and settlement date, settlements, fails, the stock record by
  location, locates (the kit's `locateId` joins them to short sales). Backfilled to Iceberg, and loaded into
  CockroachDB `custody` for the CDC leg in place of the toy `positions.holdings`.
- `brokerage.recon` views for ties 1, 3 and 4 of the design.
- Seeded break: one fail to deliver aged into a Reg SHO 204 close-out.

What done looks like: on a clean world the three views return no rows, and with the seeded break exactly the seeded
rows, read in Trino as `ana`; an `UPDATE` to an order in SingleStore reaches `brokerage.trading.orders` as before.

### 152. Brokerage world 3: party, money movement and compliance, the CDC sources, the governance moments and live mode

- Decided: 2026-11-22, D48
- Added: 2026-11-22 by local_0484717c-9adc-4b18-af93-2db236201f4a
- Touches: demo-datagen/ demo/cdc/ demo/k8s/cdc.yaml demo/k8s/keycloak-realm.json demo/k8s/demo-values.yaml demo/CloudBeaverUsers.java demo/demo.sh demo/README.md
- Effort: L, the PostgreSQL and Oracle sources, the compliance domain, the governance set-up and a live mode over the CDC leg; waits on item 151, and on item 155 (the demo kit's templates) for wires, ACH returns, journals, fees and ACATS

Third phase of D48 (`docs/design/brokerage-world.md`).

What to build:
- PostgreSQL `backoffice`, replacing the toy `settlement.instructions`: parties, accounts, roles, CIP checks,
  suitability profiles, beneficial owners, trusted contacts, payments, ACH returns, ACATS transfers, complaints, AML
  alerts and cases. Oracle `treasury`: bank payments, replacing the toy `PAYMENTS`. Their histories land in
  `brokerage.party`, `brokerage.money_movement` and `brokerage.compliance` through Debezium and the Iceberg sink, with
  current-state views; the backfill writes the same histories in the sink's shape.
- Sanctions screening against a synthetic list in OFAC's SDN layout; surveillance alerts; seeded structuring deposits
  and one sanctions match.
- Governance: the classifications and regulated namespaces of the design's table; tax ids, birth dates, addresses and
  bank account numbers flagged (D20); a legal hold on `compliance.aml_cases`; `party.beneficial_owners` landing
  unclassified, so the catalog refuses it (D27) until a steward classifies it; a new person `cora` (password
  `cora-demo`) in a new group `demo-compliance`, the only group that reads `compliance`, in Keycloak, CloudBeaver and
  the catalog's grants.
- `demo.sh live`: with the CDC leg up, the sources loaded with the month's end state, Debezium started without
  snapshotting those rows, the next business day played at a compressed clock; the ledger's transactions and postings
  on Kafka topics the Iceberg sink writes to `brokerage.ledger`, visible in kafbat.
- The events the demo kit adds (item 155), as its templates land.

What done looks like: a live day reaches every history table with no gap and no duplicate against the backfill (a
count per table and key); `ana` is refused `compliance` and `cora` reads it; a snapshot expiry on `aml_cases` is
refused while the hold stands; `stella` classifying `beneficial_owners` ends its refusal.

Choice made (Paul may overrule): one new person, `cora`, for compliance.

### 153. Brokerage world 4: the firm's books and reporting, the GL, margin, the reserve, tax, statements and CAT

- Decided: 2026-11-22, D48
- Added: 2026-11-22 by local_0484717c-9adc-4b18-af93-2db236201f4a
- Touches: demo-datagen/ demo/cdc/oracle.sql demo/demo.sh demo/README.md
- Effort: L, the firm-level domains and the month-end close; waits on item 152, and on item 155 (the demo kit's templates) for dividends and splits

Fourth phase of D48 (`docs/design/brokerage-world.md`).

What to build:
- The General Ledger in Oracle (`brokerage.finance`) and its tie to the sub-ledgers (ControlOfTotal); margin
  (`brokerage.margin`: house requirements, account snapshots with the intraday margin level of FINRA 4210 as amended
  from 2026-06-04, calls); the 15c3-3 reserve computation and net capital (`brokerage.regulatory`).
- Tax lots and realizations with wash sales, 1099-B lines (`brokerage.tax`); confirms and month-end statements
  (`brokerage.client_reporting`); best-execution reviews; CAT order events and a Rule 606 report.
- Asset servicing (`brokerage.reference.corporate_actions`, `brokerage.asset_servicing`) once the demo kit books it (item 155).
- The month-end close as one multi-table commit (`/transactions/commit`) with a tag `month-end-YYYY-MM`; a trade bust
  after the close corrected on a branch and merged, while the tag keeps what the statements said.
- Spark as the month-end batch where the demo's memory allows (it needs Trino stopped); the Java writer otherwise.
- The remaining ties of the design (2 and 5 to 9) as `brokerage.recon` views.

What done looks like: every tie holds on a clean world and returns exactly the seeded rows otherwise; reading the
statements at the month-end tag after the bust's correction gives what they said before it.

### 154. Brokerage world 5: volume and upkeep, streaming tables, maintenance policies and the scale setting

- Decided: 2026-11-22, D48
- Added: 2026-11-22 by local_0484717c-9adc-4b18-af93-2db236201f4a
- Touches: demo-datagen/ demo/demo.sh demo/README.md demo/k8s/ docs/install-at-work.md
- Effort: M, two streams through the existing sink, maintenance policies per namespace and a scale setting; waits on item 153

Fifth phase of D48 (`docs/design/brokerage-world.md`).

What to build: five-minute bars and live quotes (`brokerage.markets`) and sessions and click events
(`brokerage.digital`) on Kafka through the Iceberg sink in live mode; maintenance policies per namespace (short
snapshot retention and frequent compaction on the streaming tables, D30); the scale setting (accounts, days), with a
note in `docs/install-at-work.md` on sizing it there.

What done looks like: after a live day the health dashboard (D38) ranks the streaming tables among the neediest, and a
maintenance run fixes them without touching the held `compliance.aml_cases`.

### 155. The demo kit: templates and booking schemes for the events the brokerage world needs beyond ledger kit v14 (D48)

- Decided: 2026-11-22, D48 (amended)
- Added: 2026-11-22 by local_0484717c-9adc-4b18-af93-2db236201f4a
- Touches: demo-datagen/
- Effort: L, about sixteen templates as booking-scheme data, two new calculations (lots received by transfer, lots rescaled by a split) and the engine's tests; waits on item 150 (the port)

Rescoped by Paul, 2026-11-22 (D48 as amended): "the kit chat is for building the sequencer. This kit is just for
filling iceberg with data. So they are 2 separate kits." This item first read as kit v15 and v16 of the sequencer's
ledger kit. It is the demo's own kit instead: item 150 forks ledger kit v14 into `demo-datagen`, and this item extends
that fork. It never follows the ledger kit's later versions, and needs none of its workbook or reviews.

A month at a brokerage has events v14 cannot book, and the generator produces none until the demo kit can:

- Money and fees (for item 152): wire deposit received; wire withdrawal initiated, settled and cancelled; an ACH
  deposit returned before and after settlement, with its Nacha return code; a cash journal between two brokerage
  accounts; an account fee charged and reversed (wire, ACATS-out, hard-to-borrow on shorts); short sell and buy to
  cover cancelled.
- Transfers and asset servicing (for items 152 and 153): ACATS received (cash, and positions whose lots carry the
  delivering firm's cost and acquisition date) and delivered (lots out at remaining cost, no gain); a cash dividend on
  a long position; a payment in lieu charged to a short position; forward and reverse splits (lots rescaled, cost
  unchanged, cash in lieu of fractions).

The bar: every template books on all four sub-ledgers with the engine's self-checks holding after every Posting
(each journal entry balanced in its unit, lots tied to their controls, mirrors, J16 floors), and reads as believable to
a brokerage person.

What done looks like: for each template, a lifecycle on a cash and a margin account runs through the engine in the
fast build with every self-check passing, and the generator produces those events.

### 156. Remove a table from refs: an audited route, gated by manage-tags, so a dropped table's data can leave tags (item 26, finding 6)

- Decided: 2026-11-22, D33 (amended)
- Added: 2026-11-22 by local_c54f9c3c-94cf-4eea-9919-cdad09127ccb
- Touches: catalog-store-api/src/main/java/nuna/catalog/store/RefStore.java catalog-store-api/src/test/java/nuna/catalog/store/suite/RefSuite.java catalog-store-api/src/test/java/nuna/catalog/store/suite/MutationSuite.java catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlRefs.java catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlCatalogStore.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleRefs.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleCatalogStore.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoRefs.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoCatalogStore.java catalog-audit/src/main/java/nuna/catalog/audit/RefChange.java catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/RefRoutesTest.java catalog-server/src/test/java/nuna/catalog/server/governance/MutatingRoutesAuditTest.java bites/D33/ref-remove-route-rights-unchecked.patch bites/D33/ref-remove-route-hold-unchecked.patch bites/D33/ref-remove-route-freeze-unchecked.patch bites/D33/sql-ref-remove-hold-unchecked.patch bites/D33/sql-ref-remove-keeps-the-entry.patch bites/D33/oracle-ref-remove-keeps-the-entry.patch bites/D33/mongo-ref-remove-keeps-the-entry.patch status/2026-11-23-agent-a7f30215399c9d241.md
- Effort: M, one store operation on four stores and their suite, one route with its audit event, tests and bites; waits on items 105, 106 and 108, which hold the same files

## What is wrong

Data dropped on main can stay readable forever and can never be purged. The security review of branches (item 26,
2026-11-22, finding 6) found the protection a ref gives a table is all or nothing:

- A purge claim is refused while any ref's cut names the object (`SqlObjects.claimPurge`, the `ref_entry` check).
- Tags never expire, and a ref made from another copies its cut (`SqlRefs`), so the protection can be carried on
  indefinitely.
- Any holder of `create` on the catalog can make or delete a tag (`RefRoutes`). The table's owners cannot take their
  table out of a tag; the only way out is to delete the whole tag, which may be someone else's point-in-time record.

So personal data loaded by mistake and dropped on main stays readable on every older tag to anyone still holding
select on it, and its files are never purged.

## The decision

Paul, 2026-11-22, asked whether table owners should be able to remove a dropped table from tags or whether to accept
that dropping does not remove data: "Yes, add the route" (D33, amended).

## What done looks like

- A management route removes one table or view from refs: from every ref of the catalog, or from the refs it names.
  It takes the object by id, so it reaches an object main has dropped or unregistered.
- It needs `manage-tags` on the object (inherited as every privilege is). That right is in the TAGS duty, which D18's
  separation of duties keeps apart from DATA, so a data writer cannot use it to erase history. A refusal is audited
  through `Guard.refused` and follows Holdings (404 for a caller holding nothing, 403 otherwise).
- It is refused while the object is under a legal hold (D24) or a DR freeze (D37), with a denial.
- In one transaction on each of the four stores it deletes the object's `ref_entry` rows and any branch pointer for it
  in the named refs. Once no ref names a dropped object, its purge claim goes through as for any dropped object.
- It publishes a `ref` audit event naming the object and each ref it left, through the outbox.
- Reads of the object through a ref it left answer 404, as for a name the cut never held.
- Tests: `RefSuite` on all four stores (heavy); `RefRoutesTest` (fast) for the right, the hold and freeze refusals, the
  audit event and the 404 afterwards. Bites in `bites/D33/`: the right check removed; the hold check removed; the
  `ref_entry` delete skipped.

**Progress, 2026-11-23 (worktree-agent-a7f30215399c9d241): built; open until the heavy build passes the suite cases
on all four stores and the store bites are run.**
- Route: `POST /management/v1/catalogs/{catalog}/objects/{id}/refs/remove` (`RefRoutes.remove`), with an
  Idempotency-Key as every management write. The body is `{"refs": [name, ...]}` (1 to 1,000 names) or
  `{"all-refs": true}`, exactly one of them, else 400. It finds the catalog as Holdings does, then the object by id in
  that catalog in any state but purged (400 for an id that is no UUID, 404 `NoSuchObjectException` for one the catalog
  does not hold), and needs `manage-tags` on the object through `ManagementGuard.require`: 403 with an audited denial,
  or 404 (also audited) for a caller holding nothing on it. Then a legal hold on the object, a namespace above it or
  the catalog refuses it (403, denial `legal_hold`), and so does a DR freeze (403, denial `dr_freeze`), before any ref
  is read. A named ref that is gone, or a branch past its expiry, answers 404 `NoSuchRefException`; a ref whose cut
  does not name the object answers 404 `NoSuchObjectException` "No table or view <id> on ref <name> of <catalog>",
  which is also what a second removal through the same ref gets. It answers 200 with the object (id, namespace, name,
  kind, state) and the refs it left; with `all-refs` and no ref naming the object, 200 with no refs, writing nothing.
- Audit: one `ref` event per ref left, with the new action `RefChange.Action.OBJECT_REMOVED` and the object in
  `tables` (kind, id, catalog, path), written to the outbox in the store's transaction.
- Store: `RefStore.removeFromRefs(objectId, refIds, every, change)` answers, in this order once its locks are held,
  `Missing(OBJECT)` (none, or purged), `Refused(HELD)`, `Refused(FROZEN)`, `Missing(REF)` (a ref of the set is not the
  catalog's or does not name the object) and `Refused(REFERENCED)` (`every`, and a ref outside the set names it), else
  deletes each ref's `ref_entry` row for the object and each branch's own pointer for it with its projection pieces.
  PostgreSQL and CockroachDB (`SqlRefs.remove`): the catalog's row for share (a create holds it for update), each ref
  for update in id order (as a deletion and a merge do), the object for update (as a purge claim does), then
  `Guards.held` and `Guards.frozen`. Oracle (`OracleRefs.remove`): the same, the catalog's row for update. MongoDB
  (`MongoRefs.remove`): one transaction that reads the holds and freeze through `Guards`, counts for `every` only refs
  whose document exists, sees then marks the object and each ref, sets the catalog's clock and a new `trimmed` stamp,
  and deletes. `MongoRefs.create` compares the catalog's `trimmed` its snapshot read with the one in its transaction,
  and cuts again when a removal landed between, so a ref copied from a branch never brings back what was taken out.
- When the store answers `Missing(REF)` or `REFERENCED` (a ref deleted or made between the route's read and the
  transaction), the route reads the refs again, three times at most, then answers 409 `RefsChangedException`.
- Tests: `RefRoutesTest` (fast, 8 new cases, all 28 pass): the right (create, describe on the catalog and describe on
  the namespace refused 403; nothing on the object 404; nothing in the catalog 404; `manage-tags` on the namespace
  reaches its tables), the named refs (entry and own pointer gone, other refs and tables kept, one event per ref, the
  404 afterwards), every ref on a dropped table with an expired branch, unregistered and purged objects, the hold and
  freeze refusals at the route and from the store, the re-read and the 409, the body's 400s, and the key's replay.
  `RefSuite` (heavy, 4 new cases on all four stores): `takingATableOutOfRefs`, `removalOutcomesComeInOrder`,
  `whatNoRefNamesCanBePurged` (purge claim and namespace drop go through once no ref names the object) and
  `aRemovalFromEveryRefHoldsAgainstRefsMadeMeanwhile` (a writer copying a branch while the removal runs).
  `MutationSuite` gains `removeFromRefs`; `MutatingRoutesAuditTest` gains the route's `ref` record (a steward takes a
  dropped table out of a tag) and its refused case (the tester, 403, a denial).
- Bites in `bites/D33/`: `ref-remove-route-rights-unchecked`, `ref-remove-route-hold-unchecked` and
  `ref-remove-route-freeze-unchecked` (fast: run, BITES); `sql-ref-remove-hold-unchecked`,
  `sql-ref-remove-keeps-the-entry`, `oracle-ref-remove-keeps-the-entry` and `mongo-ref-remove-keeps-the-entry` (suite
  cases: checked with `git apply --check` only).
- Choice made (Paul may overrule): the route lives under the object's management path, by id, as undrop and the purge
  claim do, so it reaches a dropped or unregistered object. The body names the refs or says `all-refs`, never both, so
  an empty or mistaken body never takes the object out of every ref.
- Choice made (Paul may overrule): `all-refs` includes a branch past its expiry, since it still names the object and
  keeps its files until the worker ends it; a ref named by name must be live, as on every other ref route.
- Choice made (Paul may overrule): an active table or view can be taken out too, not only a dropped one. The item says
  "a table or view", and taking a live table out of a tag erases the same history under the same right.
- Choice made (Paul may overrule): one event per ref, so each ref's events keep their order on its subject; its audit
  row names the catalog, as every `ref` record's does (`AuditRows` is unchanged), and the object is in the payload.
- Choice made (Paul may overrule): the hold and freeze are read twice, by the route before any work (a denial, as for
  the right) and by the store under the object's lock, as a purge claim reads them, so a hold placed in between still
  refuses it, with the same denial.
- Choice made (Paul may overrule): a branch's journal rows for the object stay, as a ref deletion leaves them: they
  hold the metadata locations committed, not data, and the audit trail records the same.
- Choice made (Paul may overrule): no new store answers. `Missing(REF)` and `REFERENCED` both mean the refs changed
  since the route read them, so the route reads again; after three tries it answers 409.
- Choice made (Paul may overrule): the answer shows each ref without `created-by`, since a steward need not hold
  `create` (item 106).
- Choice made (Paul may overrule): a removal and a ref create take turns on the catalog's row (SQL) or its `trimmed`
  stamp (MongoDB), so with `every` no ref can still name the object once the removal is done. Without it a tag copied
  from a branch while the removal ran could keep the table, and its purge would stay refused.

Left: the heavy build running the four `RefSuite` cases, the `MutationSuite` case and `MutatingRoutesAuditTest` on
all four stores, and the four store bites run against them (the overnight run). No bite is recorded for MongoDB's
re-cut (`MongoRefs.create`'s `trimmed` check): the only case that reaches it, the writer racing the removal, need
not fail on every run. A hold placed on the object itself while a removal runs waits for it (SQL: the object's row)
or conflicts with it (MongoDB: both write the object), but one placed on a namespace above it or on the catalog can
land after the removal read the holds, on every store, as for a purge claim. The console has no control for the route yet (item 109's screens are where it would go).

### 157. A namespace drop is refused while a ref's cut names an object in it (item 26, security finding 7)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlNamespaces.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleNamespaces.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoNamespaces.java catalog-store-api/src/main/java/nuna/catalog/store/NamespaceStore.java catalog-store-api/src/main/java/nuna/catalog/store/WriteResult.java catalog-store-api/src/test/java/nuna/catalog/store/suite/RefSuite.java catalog-server/src/main/java/nuna/catalog/server/rest/NamespaceRoutes.java catalog-server/src/test/java/nuna/catalog/server/rest/GovernanceRefusalTest.java docs/storage-design.md bites/D33/sql-namespace-drop-ignores-refs.patch bites/D33/oracle-namespace-drop-ignores-refs.patch bites/D33/mongo-namespace-drop-ignores-refs.patch bites/D33/rest-namespace-drop-referenced-unmapped.patch bites/D18/legal-hold-namespace-drop-refusal-unaudited.patch backlog/158.md status/2026-11-23-agent-a4896cd1ad5f1a1e9.md
- Effort: S, one read in each store's namespace drop, one route answer, a suite case, a fast route test and four bites; the suite case then waits for the overnight heavy build

Part of item 26 (D33). The security review of 2026-11-22, finding 7 (low).

What is wrong: a namespace drop checks only for child namespaces and ACTIVE objects before it deletes the namespace
with its grants and governance attributes (`SqlNamespaces.drop`, catalog-store-sql, :130-141 before this item;
`OracleNamespaces.drop` and `MongoNamespaces.drop` the same). A table dropped on main that a tag still keeps (a ref's
cut names it, and its purge is refused while it does, item 103) is no ACTIVE object, so its namespace can be dropped
under it. A read through the tag builds the table's governance chain from the table's record on main
(`GovernanceChain.of`: its namespace id, then that namespace's parents), which stops at the dropped namespace: the
namespace's `regulated=true` no longer applies, so a hash mismatch on a read through the tag only raises an alert
instead of failing closed (D5), and the D17 switch stops applying to those reads.

Evidence: `NamespaceSuite#dropRefusesANonEmptyNamespace` shows a namespace with only dropped objects drops; no check
anywhere reads `ref_entry` (or MongoDB's `ref_entry` collection) on a namespace drop.

Done looks like: each store's drop answers `Refused(REFERENCED)` while any ref's cut names a table or view, in any
state, whose namespace on main is the one being dropped, read inside the drop's transaction as its emptiness check
is; the REST drop answers it 409 `NamespaceNotEmptyException`; a `RefSuite` case on all four stores (heavy) and a
fast route test, with bites in `bites/D33/`.

**Progress, 2026-11-23 (worktree-agent-a4896cd1ad5f1a1e9): built; open until the heavy build passes the suite case
on all four stores.**
- Stores: `SqlNamespaces.drop` (PostgreSQL and CockroachDB) and `OracleNamespaces.drop` answer `Refused(REFERENCED)`
  when `SELECT 1 FROM catalog_object o JOIN ref_entry e ON e.object_id = o.id WHERE o.namespace_id = ?` finds a row,
  read after the existing emptiness check under the namespace's lock. `MongoNamespaces.drop` reads the ids of every
  object of the namespace and asks `MongoRefs.referenced`, as the purge claim does, in the drop's snapshot (an entry
  counts only while its ref exists). Javadoc of `NamespaceStore.dropNamespace` and `WriteResult.Reason.REFERENCED`;
  storage-design section 8 gains a bullet.
- Route: `NamespaceRoutes.drop` answers `REFERENCED` with 409 `NamespaceNotEmptyException`, "Namespace <ns> is not
  empty: a branch or tag names a table or view in it".
- Tests: `RefSuite#aRefKeepsItsObjectsNamespace` (heavy, all four stores): a namespace with a dropped table a tag and
  a branch name is refused and writes nothing, its `regulated` flag stays; a namespace with a dropped view likewise;
  a namespace the refs name only as a namespace (its table made after them) drops; refused while an expired branch,
  then a tag copied from it, names the table; drops once every ref is deleted, taking the flag with it.
  `RefSuite#everyTableInACutHasItsNamespace`'s writer now accepts `REFERENCED` for a namespace whose table a cut
  caught. `GovernanceRefusalTest#aNamespaceDropARefStillNeedsIsNotEmpty` (fast): the route's 409, and no denial.
- Bites in `bites/D33/`: `sql-namespace-drop-ignores-refs`, `oracle-namespace-drop-ignores-refs`,
  `mongo-namespace-drop-ignores-refs` (suite cases: checked with `git apply --check` only), and
  `rest-namespace-drop-referenced-unmapped` (fast: run, BITES). `bites/D18/legal-hold-namespace-drop-refusal-unaudited`
  recorded again with the same break, its context having moved.
- Choice made (Paul may overrule): the answer is the existing `Refused(REFERENCED)`, clearer than `NOT_EMPTY`; the
  route maps it to 409 `NamespaceNotEmptyException`, the only 409 the REST spec gives the drop, so Iceberg clients
  raise their usual exception, with a message that names the refs. Like `NOT_EMPTY` it is no denial: a state the
  caller can change.
- Choice made (Paul may overrule): an object counts when its namespace on main (`catalog_object.namespace_id`) is the
  one dropped, not the namespace the cut recorded for it, because a read through a ref builds the object's governance
  from its record on main (`GovernanceChain.of`). A cut that names the namespace itself, but no table or view of it,
  does not keep it: the route already serves such a namespace through the ref with no properties (D33 as amended
  2026-11-20).
- Choice made (Paul may overrule): any state, as the item says, unregistered and purged included; a purged object
  cannot be named (its purge is refused while a ref names it) and an unregistered one is not served through a ref,
  so they cost only the read.
- Choice made (Paul may overrule): the answers come in the order `Missing(NAMESPACE)`, `NOT_EMPTY`, `REFERENCED`,
  `HELD`.
- Choice made (Paul may overrule): a plain read in the drop's transaction, as the emptiness check is, with no new
  lock. It does not see a ref create from main still in flight; that race is item 158, which says how to close it.

Left: the heavy build running `RefSuite#aRefKeepsItsObjectsNamespace` and the changed
`everyTableInACutHasItsNamespace` on all four stores, and the three store bites run against them (the overnight run).

### 158. A ref made from main while a table and then its namespace are dropped can name a table whose namespace is gone (item 157's race)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlNamespaces.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleNamespaces.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoNamespaces.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoRefs.java catalog-store-api/src/test/java/nuna/catalog/store/suite/RefSuite.java docs/storage-design.md bites/D33/
- Effort: M, a lock-order change on three stores and a check in MongoDB's ref create, each with a suite case that holds a ref create open; the cases then wait for the overnight heavy build

Found while building item 157 (security finding 7). Item 157 refuses a namespace drop while a ref's cut names a
table or view of it, read in the drop's transaction as its emptiness check is. That read sees only refs that have
committed, and nothing makes a namespace drop wait for a ref create in flight:

- PostgreSQL and CockroachDB (`SqlRefs.create`): the create locks the catalog's row `FOR UPDATE`, reads its cut in
  one `INSERT ... SELECT`, then commits. The namespace drop locks only the namespace. So: the cut's statement
  snapshot sees table T active in namespace N; T's drop commits; N's drop commits, seeing no active object and no
  committed entry naming T; the ref commits, naming T, whose namespace main no longer has. The window is the cut
  statement and the commit, longer on a large catalog. Oracle (`OracleRefs.create`) is the same at one SCN.
- MongoDB (`MongoRefs.create`): the cut is read in a snapshot session, its entries are written in batches outside
  any transaction, then one transaction inserts the ref, so the window also holds every batch. The drop marks its
  parent (the catalog, for a top-level namespace), which the ref's transaction marks too, so only drops that overlap
  that last transaction conflict with it.

The result is the state finding 7 describes (a read through the ref loses the namespace's `regulated` flag), reached
by a race instead of in sequence. It needs a ref create from main in flight across both drops.

Done looks like:
- SQL stores: the namespace drop locks the catalog's row before the namespace, `FOR SHARE` on PostgreSQL and
  CockroachDB, for update on Oracle (no shared lock), the order `putGrantIf` takes (catalog, then securable), so a
  drop waits for a ref create in flight and then reads its entries, and a create waits for a drop in flight and then
  cuts without the namespace. Check every write that locks a namespace and then the catalog first: none did when this
  item was written.
- MongoDB: the ref's insert transaction refuses or retries the create when a table or view of its cut no longer has
  its namespace on main, or another way to the same end that does not read the whole cut in one transaction.
- A `RefSuite` case on all four stores (heavy): a ref create held open before its COMMIT (the commit hook, as
  `NamespaceSuite`'s `duringAnOpenDrop` holds a drop) while a table of the cut and then its namespace are dropped; the
  namespace drop answers `Refused(REFERENCED)` or the ref's cut leaves the table out, never both applied. Bites in
  `bites/D33/`.

**From the security review (Nuna Security Expert session, 2026-11-23):** item 158 may follow item 157; it is no
go-to-work condition. The race needs a ref create in flight across two drops, by a caller already holding drop rights
on the namespace, and its harm is finding 7's low one (the namespace's `regulated` flag and the D17 switch stop
applying to reads through that ref); it grants no new access. A second way to close it, the builder's choice:
`GovernanceChain.of` now stops when the object's namespace is gone; refusing a ref read whose object's namespace is
missing (fail closed, audited as a denial) closes this race and any other path to the same state, without the lock
order change on three stores or MongoDB's check. Item 157's check stays either way, so reads do not start failing.

### 167. Refs: a description given when a branch or tag is made, kept on the ref and shown in the console (item 109's gap 8)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-store-api/src/main/java/nuna/catalog/store/Ref.java catalog-store-api/src/main/java/nuna/catalog/store/RefStore.java catalog-store-api/src/test/java/nuna/catalog/store/suite/RefSuite.java catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlRefs.java catalog-store-sql/src/main/resources/nuna/store/sql/ catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleRefs.java catalog-store-oracle/src/main/resources/nuna/store/oracle/migration/ catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoRefs.java catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/RefRoutesTest.java catalog-server/src/main/resources/nuna/ui/refs.js bites/D33/
- Effort: M, a nullable column and its migration on PostgreSQL, CockroachDB and Oracle and a field on MongoDB's document, carried by create and every read, a `RefSuite` case on four stores, the route's field and limit, and the console's purpose line; the suite case then waits for the overnight heavy build

Atelier's design for the console's branches and tags (`docs/design/console/wireframes.html`, chapter 6, "A purpose
line") gives each ref a line saying why it exists: under its chip in the refs list, and as the first words of its
page's sub-line, "{purpose}. Made from main by {created-by} on {created}." The make dialog has a field for it.

`RefRoutes` stores no description: `POST …/refs` takes `name`, `kind` and `from`, and the `ref` record on the four
stores has no column for one. Item 109 said to add it there unless it proved larger than that item could carry; a
column on four stores with their migrations and suite cases is, so item 109 left the field out and the console shows
no purpose line (recorded in `backlog/109.md`).

Done looks like:
- `POST …/refs` takes an optional `description` (one line of plain text, at most a length the route states, refused
  with 400 beyond it); the list and the read answer it; renew and merge keep it; the `nuna.catalog.ref` record of a
  create carries it.
- The `ref` record keeps it on PostgreSQL, CockroachDB, Oracle and MongoDB, with a migration on each SQL store that
  leaves existing refs without one, and a `RefSuite` case (heavy) that makes a ref with and without one and reads it
  back. A `RefRoutesTest` case (fast) for the route, with a bite in `bites/D33/`.
- The console: the make dialog's Description field, the purpose line in the refs list's Ref cell and on the ref's
  page, as the design words them.

### 168. Refs: each table's version at a ref's cut, and the cut's time for a ref made from a branch, from the server (item 109's gap 9)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/RefRoutesTest.java catalog-server/src/main/java/nuna/catalog/server/governance/NamespaceTablesRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/NamespaceTablesRoutesTest.java catalog-server/src/main/resources/nuna/ui/browse.js catalog-server/src/main/resources/nuna/ui/refs.js bites/D33/
- Effort: M, a `cut-at` on the ref's answer, and the namespace listing and its totals read through a ref (each table's pointer on the ref, or at the cut, for its format, snapshots and last commit), with fast route tests and bites, then the console's columns

Atelier's design (`docs/design/console/wireframes.html`, chapter 6, 6.10) asks for "the cut's time for a ref made from
a ref, and a table's cut-version for unchanged tables", shown in the band and in a namespace's Contents read through
a ref ("Last commit here", and "as at the point" with the version).

What item 109 built instead:
- The cut's time is worked out in the console (`cutOf` in `refs.js`): a ref made from main was cut when it was made;
  one made from a branch or tag takes that ref's cut, following `from` back to main. That is right for the tables the
  parent had not changed, but a ref made from a branch takes the branch's own changes as they were at the making, so
  it has no single cut time; and once the parent is deleted, the chain breaks and the band says "the point @x was
  made from".
- `GET …/namespaces/{ns}/tables` and `…/namespaces` read main's metadata only, so a namespace's Contents read through
  a ref leaves format, snapshots and last commit blank, with a line saying so. The "On @ref" column comes from the
  ref's `changed` list ("changed here" with the branch's version), and every other table says "as at the point"
  without a version.

Done looks like:
- The ref's list and read answers carry `cut-at`, the time its cut was taken from main, and for a ref made from a
  branch, that it also holds the branch's changes as of its making.
- The namespace listing and its totals take `?ref=`: each table as the ref has it (its own pointer on a branch, else
  the cut's), with its version, format, snapshots and last commit; only what the caller may describe, as today.
  Fast route tests with bites in `bites/D33/`.
- The console fills the Contents columns through a ref, shows "as at the point, v{n}" for an unchanged table, and
  takes the band's and the page's point from `cut-at`.

### 169. Audit: records filterable by the branch they went through (item 109's gap 10)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-audit-tables/src/main/java/nuna/audittables/EventsTable.java catalog-audit-tables/src/main/java/nuna/audittables/EventsReader.java catalog-audit-tables/src/main/java/nuna/audittables/TableWriter.java catalog-audit-tables/src/test/ catalog-server/src/main/java/nuna/catalog/server/governance/AuditReadRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/AuditReadRouteTest.java catalog-server/src/main/resources/nuna/ui/audit.js catalog-server/src/main/resources/nuna/ui/refs.js bites/
- Effort: M, a `branch` column (and the subject's id as a filter) on the audit events table, its schema evolved in place on existing tables, the writer filling it from each record, the feed's two filters, and the console's search; the audit table tests start containers, so they wait for the overnight heavy build

Atelier's design (`docs/design/console/wireframes.html`, chapter 6, 6.8 and 6.10) has the audit feed's search take
`@name`: every record through a catalog ref (commits and reads name it in their `branch` field) and the ref's own
`nuna.catalog.ref` records. A ref's Record tab has "Every record through @{name} in the last day".

The feed (`GET …/audit/feed`) filters by type, principal, path and time only. The audit events table
(`EventsTable.SCHEMA`) has no `branch` column: the branch is inside each record's `data`, which the reader does not
parse to filter. Item 109 left the `@name` search and the button out; its Record tab asks the feed for
`nuna.catalog.ref` records and keeps those whose subject is the ref's id, scanning at most four pages.

Done looks like:
- The events table gains a nullable `branch` column, filled from each record's `branch` (null for records with none,
  such as grants); existing tables gain the column without a rewrite, and their old rows read as null.
- The feed takes `branch=<name>` (records through that ref) and `subject=<id>` (records about one object or ref),
  each with tests and bites.
- The console's feed search reads `@name` as the branch filter, and a ref's Record tab uses the subject filter and
  links "Every record through @{name} in the last day".

### 170. Console: a table dropped on main or made after a ref's point, read through the ref or on main, says when, by whom and which refs hold it (design 6.4's edge states)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/RefRoutesTest.java catalog-server/src/main/resources/nuna/ui/table.js catalog-server/src/main/resources/nuna/ui/refs.js bites/D33/
- Effort: M, a read of which live refs hold an object (and the drop's actor, from the drop's journal or audit record), a fast route test with bites, and three states on the table page

Atelier's design (`docs/design/console/wireframes.html`, chapter 6, 6.4) draws three edge states of a table page
that item 109's eleven gaps do not cover, each marked "gap" in the design:

- Dropped on main, read on main: the catalog's `404 NoSuchTableException`, then "{actor} dropped it on {time}. Main
  has no such table. A ref made before then still reads it: @{ref}, …", or "No ref holds it." Needs which refs hold
  a path, and the drop's time and actor.
- Dropped on main, read through a ref: a "Dropped on main {d Mon}" pill and "{actor} dropped {path} on main on
  {time}. @{ref} was made before then, so it still reads the table as it was; main answers 404 for it." Needs the
  drop's time and actor.
- Made after the ref's point, read through the ref: the catalog's 404, then "{path} was made on {time}, after
  @{ref}'s point, {cut}. A ref holds only what main held then." Needs the table's making time and the cut's time
  (item 168).

Item 109 shows the catalog's 404 as it answers, verbatim, in each case, without the words after it. The merge's dry
run already gives a dropped table's `dropped-at` (item 107), but not who dropped it.

Done looks like: a route (or a field on an existing answer, the builder's choice) that names the live refs holding an
object by id or path, and the drop's actor and time; fast tests with bites in `bites/D33/`; the three states on the
table page with the design's words.

### 171. A hold placed above an object while a purge claim or a removal from refs runs: lock the held levels before reading holds (item 156's review, low)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlObjects.java catalog-store-sql/src/main/java/nuna/catalog/store/sql/SqlRefs.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleObjects.java catalog-store-oracle/src/main/java/nuna/catalog/store/oracle/OracleRefs.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoObjects.java catalog-store-mongo/src/main/java/nuna/catalog/store/mongo/MongoRefs.java catalog-store-api/src/test/java/nuna/catalog/store/suite/ bites/
- Effort: S, one lock taken earlier in two operations on four stores, with a suite case that holds a hold's write open; the case waits for the overnight heavy build

Low, from the security review of item 156 (Nuna Security Expert session, 2026-11-23). Not a go-to-work condition.

What is wrong: a purge claim and a removal from refs (item 156) read the holds and freezes on the object, its
namespaces and the catalog inside their transaction, but lock only the object's row. A legal hold placed on a
namespace above the object, or on the catalog, while one of them runs can commit after its read. The result is the
same as the operation finishing first and the hold arriving a moment later (placing a hold reads nothing either
writes, and the hold then stands, so `Rules.retentionHold` keeps main's snapshots and later purge claims are
refused), so nothing is lost; only the audit trail can show the hold's record a little earlier than the operation's.

Done when the purge claim and the removal lock the held levels (or the catalog row) before reading holds, on all four
stores; a store suite case holds a namespace hold's write open while each runs and sees them serialized; bites
recorded.

### 172. Console: export a table's metadata and log as a catalog ref reads it (item 109's review)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/LifecycleRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/MetadataLogRefTest.java catalog-server/src/main/resources/nuna/ui/panels.js catalog-server/src/main/resources/nuna/ui/table.js bites/D33/
- Effort: S, the export route takes `?ref=` as the metadata log does (the ref's pointer, its file checked against the hash, and the log through the ref), a fast test and a bite, and the console's button through a ref with `@ref` in the file name

The table page's "Export metadata" (`panels.js`, `exportDialog`) calls `GET …/objects/{id}/export`, which reads main's
pointer, main's metadata file and main's whole journal. Read through a catalog ref, the button exported main's, with no
`@ref` in the file name, so the UI review of item 109 found it misleading; item 109 hides the button through a ref.

Done looks like:
- `GET …/objects/{id}/export?ref=<name>`: the ref's pointer (its own head on a branch, else its cut), that file checked
  against its recorded hash, and the journal as the metadata log reads it through the ref (backlog 109: the ref's own
  versions, then its source's, then main's at and below the cut, stopping at a cut from a ref that has ended), with
  `ref {name, kind, cut-version}`; 404 as the log answers for a ref that is not live or does not hold the object.
  The read is audited on the ref (`through @name`). A fast test with a bite in `bites/D33/`.
- The console offers the button through a ref again, exporting through it, the file named `{path}@{ref}`.

### 177. Register and revive refuse a branch's metadata file on main (item 107's merge-base review, low)

- Added: 2026-11-23 by local_d6cc62d1-6c9d-46f4-88db-623c74725e78
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/CommitEngine.java catalog-core/src/test/java/nuna/catalog/core/commit/ bites/D33/
- Effort: S, one check on the metadata location in two CommitEngine operations, a fast test and a bite

Low, from the security review of item 107's merge-base fix (Nuna Security Expert session, 2026-11-23). Not a
go-to-work condition.

What is wrong: `CommitEngine.register` and `revive` take the client's `metadataLocation` and never check
`TableFiles.onBranch`. So main's pointer, and main's journal, can be set to a branch's metadata file
(`<v>-<uuid>.branch-<refid>.metadata.json`). Reconcile skips marked files when it builds main's chain (item 111), so
after a regional failover a main pointer at a branch file is invisible to recovery. The merge's base walk (item 107)
also accepts main's journal entry for such a file as main's position at a cut. That is true (main really was at that
file), so no commit is overwritten, but it is a file main should never hold. Doing it needs create and drop on main.

Done when register and revive refuse a location `TableFiles.onBranch` marks, with 400 `BadRequestException`, on main
and through a branch alike; a fast `CommitEngine` test for each; a bite recorded (`git diff` of the check removed,
checked with `git apply --check`, not run).

### 197. After audit goes to logs (195): a table's Audit tab reads the audit table, not the topic; the console says which rows came from the log; Health's publish figures are the Kafka copy's

- Added: 2026-11-23 by Paul
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/RecentAudit.java, catalog-server/src/main/resources/nuna/ui/audit.js, catalog-server/src/main/resources/nuna/ui/app.js, catalog-server/src/main/resources/nuna/ui/health.js, catalog-server/src/test/java/nuna/catalog/server/governance/RecentAudit, catalog-server/src/test/java/nuna/catalog/server/governance/ConsoleBrowserTest.java, docs/observability/
- Effort: M, one route's source and three console pages' words, with their tests; waits on nothing

What is wrong: item 195 (D23, amended 2026-11-23) made the log `nuna.audit` the record of audit and Kafka optional,
and the demo runs without Kafka unless `gen` or `cdc` is on (D50). Item 195's agent left the console alone, since its
files were claimed, and reported:
- `RecentAudit` (`GET .../audit`, a table's or namespace's Audit tab) reads the audit topic, so without Kafka it
  answers 503 ("needs Kafka"). In the demo, with `gen` and `cdc` off, every object's Audit tab fails.
- Rows the audit table writer takes over OTLP have `topic_partition` -1 and no hash chain; with an archive configured
  the console shows them as "unverified", and a chain lookup for partition -1 answers 400.
- `audit.js` says "event as Kafka holds it", and `app.js` describes the archive as Kafka's.
- The Health page's "Publishes failed" and the acknowledgement p99 (`ObservedPublisher`'s timer) now measure only
  the best-effort Kafka copy, and are empty without Kafka.

Done looks like:
- A table's or namespace's Audit tab answers from the audit table (`nuna_audit.audit.events`, as the catalog-wide feed
  does, D45) when there is no Kafka, under the same rules as today (audit readers, D45), with a test without Kafka.
- Log rows show "from the log, no hash chain" rather than "unverified", and no chain lookup is offered for them.
- The console's words say what is true without Kafka; Health labels the publish figures as the Kafka copy's and hides
  or explains them when Kafka is not configured.
- The console checked in the demo with every switch off, signed in as gary.

### 220. Console: findings from Atelier's re-walk after items 212 to 217

- Added: 2026-11-23 by local_6867d144-54ac-41a5-b94a-192a4c66770b
- Touches: catalog-server/src/main/resources/nuna/ui/audit.js catalog-server/src/main/resources/nuna/ui/browse.js catalog-server/src/main/resources/nuna/ui/style.css catalog-server/src/test/java/nuna/catalog/server/governance/
- Effort: S, three small console fixes: one column's wrapping, one count's words, one button's label

Atelier's re-walk after items 212 to 217 (2026-11-23, `status/2026-11-23-console-rewalk-fixes.md`), live in the demo
at 1440 × 900 with that branch's console. Her two other findings were fixed on that branch (the regulated table's
width, the merge verdict's one sentence). Left:
- Audit's "Most recorded" side table clips its Records column (gary on lakehouse's Audit): the header reads "Reco" and
  the numbers are hidden behind the long paths. Let the paths wrap or truncate with a title, so the number columns stay
  in view.
- Audit's headline and "Records in lakehouse" say 1,140 while the All chip and By kind's All say 1,124. Her guess, not
  checked: the 16 are the audit's own reads, hidden while "Show audit reads" is off. Count the same records in both,
  or say so ("1,140 records · 16 audit reads hidden").
- The catalog page read through @sample has a "Copy name" button that does not say what it copies. Name it as 217 did
  the ref page's ("Copy lakehouse@sample", aria-label "Copy the warehouse name lakehouse@sample").

Done looks like: each as above, checked in the demo at 1440 × 900 (no column clipped: each table's scrollWidth within
its card); the heavy browser cases that read the headline or the button updated.

### 226. Bite metadata-log-ref-page-by-version runs the server out of heap instead of failing its test, and run.sh calls it NO BITE

- Added: 2026-11-24 by overnight run 2026-11-24
- Touches: bites/D33/metadata-log-ref-page-by-version.patch catalog-server/src/test/ bites/run.sh bites/test-run.sh
- Effort: S, re-record one bite against a test that ends on an assertion, and make the judge call an executor crash SETUP, not NO BITE

What failed: in the overnight run of 2026-11-24, `bites/D33/metadata-log-ref-page-by-version.patch`
(`MetadataLogRefTest#throughABranchThePagesCrossTheCutWithoutAGapOrARepeat`, item 109) came out
`NO BITE (1 passed)`. Its log shows the break did bite, but not on an assertion: with a token's line ignored, the
pages repeat without end, and after 47 min 49 s the test JVM died with `Gradle Test Executor 310 FAILED`,
`java.lang.OutOfMemoryError at LifecycleRoutes.java:429` ("Java heap space").

Two things are wrong:
- The test follows the pages with no bound, so a paging bug hangs it for most of an hour and then crashes it,
  instead of failing it on an assertion (for example, more pages than the log has lines).
- `bites/run.sh` judged a run whose test executor crashed as NO BITE, counting one test as passed. An executor
  failure proves neither verdict; it should be SETUP at least.

Evidence: `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites\metadata-log-ref-page-by-version.log`, `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites.log` line 734.

Done when the test bounds its paging and fails on an assertion under this break, `bites/run.sh` on the bite reports
BITES in under a few minutes, the judge no longer reports NO BITE for an executor crash (with a case in
`bites/test-run.sh`), and the overnight run shows the bite biting.

### 227. Bite mongo-cut-read-outside-the-snapshot does not bite: everyTableInACutHasItsNamespace passes with the cut read outside the snapshot

- Added: 2026-11-24 by overnight run 2026-11-24
- Touches: catalog-store-api/src/test/ catalog-store-mongo/src/test/ bites/D33/mongo-cut-read-outside-the-snapshot.patch
- Effort: M, the race is a drop landing between two reads; the suite needs a way to land it there every time on MongoDB (a hook or a second session), found and then recorded again

What failed: in the overnight run of 2026-11-24, `bites/D33/mongo-cut-read-outside-the-snapshot.patch` came out
`NO BITE (1 passed)`: with `MongoRefs` reading a ref's tables outside the cut's snapshot session,
`MongoStoreSuiteTest$Refs#everyTableInACutHasItsNamespace` still passed (BUILD SUCCESSFUL in 30 s). The test
does not make a namespace go between the cut's namespace read and its table read, so it cannot see the break.
Recorded under item 102; related races in items 157 and 158.

Evidence: `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites\mongo-cut-read-outside-the-snapshot.log`, `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites.log` line 743.

Done when the test (or a new one in the shared refs suite) lands a drop between the two reads every time, fails
on an assertion with the patch applied, and the overnight run shows the bite biting.

### 228. Bite store-skips-postgresql-steps is SETUP again: the server no longer starts on PostgreSQL migrated with the shared steps only

- Added: 2026-11-24 by overnight run 2026-11-24
- Touches: bites/M8/store-skips-postgresql-steps.patch catalog-store-sql/ catalog-server/src/test/
- Effort: S, finding why the context no longer loads (likely a later PostgreSQL step that assumes journal partitions) and recording the bite again so the server starts; cause not known yet

What failed: in the overnight run of 2026-11-24, `bites/M8/store-skips-postgresql-steps.patch`
(`SchemaAtStartupTest`) came out `SETUP: 1 of 1 failed in setup`. The Spring context did not load:
`StoreException at Transactions.java:222` caused by `PSQLException at QueryExecutorImpl.java:2993`. Item 30
fixed exactly this on 2026-11-15 (the server started and the test failed on its assertion that the journal is
partitioned); something landed since makes the store fail on a PostgreSQL migrated without V4. Candidates: schema
steps added by items 114, 126 and 133.

Evidence: `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites\store-skips-postgresql-steps.log`, `C:\Users\paulh\code\nuna-catalog\.overnight\runs\2026-11-24\bites.log` line 1475.

Done when the server starts with the patch applied and `SchemaAtStartupTest` fails on its assertion:
`bites/run.sh` on the bite reports BITES, and so does the overnight run.

### 231. Console: between 761 and 1,016 px, and 1,241 and 1,297 px, the header is wider than the window (Atelier decides)

- Added: 2026-11-24 by Paul
- Touches: catalog-server/src/main/resources/nuna/ui/style.css, catalog-server/src/test/java/nuna/catalog/server/governance/ConsoleBrowserTest.java
- Effort: S, a few header rules at two container widths once Atelier says what gives way; a browser test

Found while fixing item 229 (the header naming every group). With the groups counted, the header fits at 1440 px and
on a phone (390 and 760 px), but the header's parts have fixed widths (brand 180 px, catalog picker 164, Reading
control 156, theme switch 184, Sign out 90), so the page scrolls sideways at two ranges of window width. Measured on
the demo on 2026-11-24 as presenter, `document.documentElement.scrollWidth` against `innerWidth`:

| Window | Page | Why |
|---|---|---|
| 1,260 px | 1,297 px | the who-text ("presenter · 5 groups · until 09:50", 226 px) shows above 1,240 px |
| 1,000 px | 1,016 px | the who-text is hidden, but the rest is 1,016 px wide |
| 800 px | 1,016 px | the same |

Atelier's prototype (`docs/design/console/prototype.html?user=presenter`) does the same with its own parts (1,275 px
at 1,260; 1,001 px at 1,000 and 800), so what gives way at those widths (the theme switch's words, Go to a path as
its icon, the brand's width, the who-text's breakpoint) is her design call.

Done when, at every window width from 761 to 1,440 px, the page is no wider than the window, as Atelier decides, and
a browser test checks it at widths in both ranges.

### 232. Console: a frozen table's page is wider than the window at 1440 px (its frozen pill runs past the edge)

- Added: 2026-11-24 by Paul
- Touches: catalog-server/src/main/resources/nuna/ui/, catalog-server/src/test/java/nuna/catalog/server/governance/ConsoleBrowserTest.java
- Effort: S, find which head or panel holds the pill and let it wrap; a browser test

Found while testing item 229 (the header). In `ConsoleBrowserTest`, a Chrome window of 1440 px (client width 1,417
px) on the page of `trades`, a table in a frozen namespace held by a namespace above, scrolls sideways: the page is
1,473 px wide while the body and the header are 1,417 px. The one element past the edge is a `span.pill.frozen`, its
right edge at 1,473 px. It shows only once the page's governance has loaded, so a check made too early passes.

Done when a frozen (and held) table's page is no wider than the window at 1440 px, and a browser test checks it after
the page has loaded its governance.

### 233. The demo's metrics switch: an OTel collector, Prometheus and Grafana with nuna's dashboards

- Added: 2026-11-24 by local_d697e3a0-9dcf-4401-b754-2b759dcc6b79
- Touches: demo/demo.sh, demo/k8s/metrics.yaml, demo/k8s/demo-values.yaml, demo/README.md, deploy/grafana/
- Effort: M, three new pods in one manifest, a fourth switch in demo.sh built like gen, ss and cdc, and fixing whatever the dashboards get wrong once they meet real series

Paul, 2026-11-24, asked whether Grafana is part of the demo; it is not, and he asked for this item.

**What is missing.** The demo runs no metrics stack. The catalog exports its metrics and traces over OTLP (D43), but in
the demo only its logs go anywhere (`telemetry.logsEndpoint`, to the audit table writer; `demo/k8s/demo-values.yaml`
says "metrics and traces go nowhere"). The dashboards and alert rules in `deploy/grafana/` (backlog 44) read nuna's
meter names as Prometheus' default OTLP translation names them, and have never run against live data
(`deploy/grafana/README.md`).

**What to build.** A fourth demo switch, `metrics`, beside `gen`, `ss` and `cdc` (D50): `demo.sh metrics` turns it on,
`demo.sh metrics stop` off, kept in `~/.nuna-demo/switches` and put back by `up`, off on a new machine.

- `demo/k8s/metrics.yaml`: an OpenTelemetry collector (OTLP in; metrics out to Prometheus by remote write or its OTLP
  receiver), Prometheus with the rule files in `deploy/grafana/rules/`, and Grafana with a provisioned Prometheus data
  source and the three dashboards in `deploy/grafana/dashboards/` loaded. Grafana signs in through Keycloak like the
  demo's other UIs (backlog 174) and is listed by `print_access` while it runs.
- The catalog and the maintenance worker send metrics (and traces, if the collector can keep them cheaply) to the
  collector only while the switch is on, the way the audit writer's switches already change what runs; with it off,
  they export nothing and log no export failures beyond what they do today.
- Memory: the three pods should ask well under 1 GB. The README's memory figures say a stack with `gen` and `ss` on
  leaves about 200 MB of Docker Desktop's 7.5 GB, so measure the switch's requests and say in `demo/README.md` which
  switches it fits beside; if it does not fit beside `gen` and `ss`, `demo.sh metrics` says so and refuses, as
  `engines` and `sql` refuse today.

**Done when** `demo.sh metrics` brings up the three pods, Grafana opens signed in through Keycloak, and each of the
three dashboards shows live series from the demo's catalog and maintenance worker. Every panel that shows nothing
because a series name differs from what the dashboards assume is fixed in `deploy/grafana/` (and
`docs/observability/dashboards-and-alerts.md`) or listed as not produced in the demo. `demo.sh metrics stop` removes
the pods and the catalog stops exporting to them, and `up` puts the switch back as it was.

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
| [23](backlog/23.md) | Per-request SSE-KMS keys | done | 2026-11-20 |
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
| [55](backlog/55.md) | The nightly lost two tests to one 15 s stall reaching Docker's containers | done | 2026-11-22 |
| [56](backlog/56.md) | WorkerTraceTest takes the expiry's table load for the scan's | done | 2026-11-16 |
| [57](backlog/57.md) | Publish Silo's console on localhost:8184 | done | 2026-11-16 |
| [58](backlog/58.md) | Demo: TLS at one front door, with a certificate that work's can replace | done | 2026-11-17 |
| [59](backlog/59.md) | Rename keel to nuna, short for nunatak, everywhere | done | 2026-11-18 |
| [60](backlog/60.md) | CredentialExpiryTest's first request answered 401 | done | 2026-11-22 |
| [61](backlog/61.md) | KubernetesCompactionTest: kubectl get job ran past its minute, after item 54's fix | done | 2026-11-19 |
| [62](backlog/62.md) | The worker's TelemetryTest: no metric arrived within 20 s from the standard variables alone | done | 2026-11-19 |
| [63](backlog/63.md) | The overnight folder moves inside the repo, gitignored | done | 2026-11-18 |
| [64](backlog/64.md) | CloudBeaver sign-in sometimes answers 500, then works on retry | done | 2026-11-19 |
| [65](backlog/65.md) | RollingUpgradeTest: the previous release is given nuna settings it does not read, since the rename | done | 2026-11-19 |
| [66](backlog/66.md) | The overnight run of 2026-11-19 started at 07:09, when the PC woke, not at midnight | dropped | 2026-11-19 |
| [67](backlog/67.md) | An unregister whose outcome is unknown waits within the retry budget when the store is down | done | 2026-11-19 |
| [68](backlog/68.md) | Catalog names still leak through /v1/config and the unknown-prefix 404 (Paul decides, D18) | done | 2026-11-20 |
| [69](backlog/69.md) | The DR reconcile's apply waits within the retry budget when the store is down, as the commit engine does | done | 2026-11-20 |
| [70](backlog/70.md) | A route-level heavy test that the server on Oracle and on MongoDB answers a database outage with 503 and Retry-After | done | 2026-11-22 |
| [71](backlog/71.md) | Export the audit publish timer's histogram, so Grafana can show the Kafka acknowledgement p99 | done | 2026-11-20 |
| [72](backlog/72.md) | Oracle's self-check does not notice a journal-partition job that cannot run | done | 2026-11-20 |
| [73](backlog/73.md) | Namespace, table and view names still leak through the management API | done | 2026-11-20 |
| [74](backlog/74.md) | HealthMeters keeps its own copy of the audit publish buckets and still says the exported timer has none | done | 2026-11-20 |
| [75](backlog/75.md) | deploy/README.md and install-at-work.md still call the ETag routing choice open | done | 2026-11-20 |
| [76](backlog/76.md) | The overnight load tests expect every conditional load to be a 304, but the shared epoch changes every window | done | 2026-11-20 |
| [77](backlog/77.md) | rest/Guard keeps its own copy of the rule security/Holdings now holds; TelemetryTest and table-health.md still say an outsider gets 403 on the management API | done | 2026-11-20 |
| [78](backlog/78.md) | Paul decides: whether the DR routes and the routes by object id hide names and ids from a caller that holds nothing | done | 2026-11-20 |
| [79](backlog/79.md) | deploy/local runs the catalog as its own non-root S3 user, so the except delete policy is applied and checked | done | 2026-11-20 |
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
| [92](backlog/92.md) | DR verifier: the worker chart's values, its settings in the worker README and install-at-work, and the drill's leg with the DR store behind | done | 2026-11-22 |
| [93](backlog/93.md) | The DR drill's appender names manifest lists that never exist, so the walk with a file check stops at its first commit | done | 2026-11-22 |
| [94](backlog/94.md) | The demo's object store on its own port, 8187, for engines on other machines | done | 2026-11-20 |
| [95](backlog/95.md) | Change feed: opt-in singlestore.append-only for insert-only tables | done | 2026-11-20 |
| [96](backlog/96.md) | A per-client S3 endpoint in the storage profile, for engines outside the cluster (Paul decides) | dropped | 2026-11-20 |
| [98](backlog/98.md) | A storage profile's KMS key is not recorded with the profile, so a change of key is not audited | done | 2026-11-22 |
| [99](backlog/99.md) | A table that commits faster than the DR store replicates never gets a first DR watermark | done | 2026-11-20 |
| [100](backlog/100.md) | A rewind that gives up more than 100 versions leaves the table behind its DR watermark for good | done | 2026-11-22 |
| [101](backlog/101.md) | The DR runbook waits on the worker's log, which records a DR pass only at DEBUG | done | 2026-11-20 |
| [102](backlog/102.md) | Refs in the store: the ref record and its cut on four stores (item 26) | done | 2026-11-22 |
| [103](backlog/103.md) | Branch commits and the merge transaction in the store (item 26) | done | 2026-11-22 |
| [110](backlog/110.md) | Demo: a catalog tag, a branch with Trino on it, and a merge (item 26) | done | 2026-11-23 |
| [114](backlog/114.md) | Run a table's maintenance now, from the console, outside the cleanup window too | done | 2026-11-22 |
| [116](backlog/116.md) | AuditTablesTest's server refuses to start: its test PostgreSQL fails the startup self-check | done | 2026-11-21 |
| [117](backlog/117.md) | RollingUpgradeTest: the new release refuses catalog m8, bound to another storage profile id by the old one | done | 2026-11-22 |
| [119](backlog/119.md) | Atelier, a UX expert agent (agent-forge home) | done | 2026-11-21 |
| [120](backlog/120.md) | Atelier moves to user scope | done | 2026-11-21 |
| [121](backlog/121.md) | ConsoleBrowserTest could not start its browser session | done | 2026-11-22 |
| [122](backlog/122.md) | The overnight heavy build stops at its first failing module | done | 2026-11-22 |
| [124](backlog/124.md) | Console: rebuild every page to the redesign prototype (D45) | done | 2026-11-22 |
| [125](backlog/125.md) | Audit: a catalog-wide feed with filters, each record's place in the hash chain, and the last day's counts (D45) | done | 2026-11-22 |
| [126](backlog/126.md) | Maintenance: each night's summary and each job's run on its worker slot (D45) | done | 2026-11-22 |
| [127](backlog/127.md) | Maintenance: one row per table for the overview's ice line (D45) | done | 2026-11-22 |
| [128](backlog/128.md) | Governance: a summary per catalog, and every hold and freeze with its history (D45) | done | 2026-11-22 |
| [129](backlog/129.md) | Grants: the effective grants on an object, inherited ones included (D45) | done | 2026-11-22 |
| [130](backlog/130.md) | A table's metadata log, a page at a time (D45) | done | 2026-11-22 |
| [131](backlog/131.md) | Catalog and namespace properties for the console (D45) | done | 2026-11-22 |
| [132](backlog/132.md) | A refusal under a DR freeze is not audited | done | 2026-11-22 |
| [133](backlog/133.md) | Keep released holds and lifted DR freezes in the store, so their history is complete | done | 2026-11-23 |
| [134](backlog/134.md) | Refusals by a commit's DR freeze, an unregister's DR freeze and a legal hold are not audited | done | 2026-11-22 |
| [136](backlog/136.md) | A nightly or heavy run by hand does not hold other sessions' builds | done | 2026-11-22 |
| [137](backlog/137.md) | Console: each table's snapshots and last commit in a namespace's Contents | done | 2026-11-22 |
| [138](backlog/138.md) | Console Health: the DR replication panel, from the DR readout, for platform operators | done | 2026-11-22 |
| [139](backlog/139.md) | Console Health: the Store panel, the catalog database's backend, version and self-check | done | 2026-11-22 |
| [140](backlog/140.md) | Console: set owner, retention class and regulated, as it sets classification | done | 2026-11-22 |
| [141](backlog/141.md) | Console: a settings editor with only the keys that do something; every property shown read-only | done | 2026-11-22 |
| [142](backlog/142.md) | Three more 403s publish no denial: a namespace drop under a legal hold, a snapshot removal younger than the minimum age, an unregister refused as flagged | done | 2026-11-22 |
| [143](backlog/143.md) | Console: Overview and Maintenance call unassessed tables in shape; Governance says labels come from a data catalogue | done | 2026-11-22 |
| [144](backlog/144.md) | The demo configures no audit tables, so the console's Audit page answers 503 | done | 2026-11-22 |
| [145](backlog/145.md) | Console Health: the Store panel shows the point-in-time recovery window the deployment declares | done | 2026-11-22 |
| [146](backlog/146.md) | Console: a Governance tab on the view page, to see and set a view's labels and holds | done | 2026-11-22 |
| [147](backlog/147.md) | Console: a child namespace's totals in Contents: its tables, their snapshots and the newest commit | done | 2026-11-22 |
| [148](backlog/148.md) | The audit feed and summary answer 500 where audit tables are configured: the reader loads Iceberg's AWS client factory, which needs the KMS SDK the server leaves out | done | 2026-11-22 |
| [149](backlog/149.md) | The audit feed leaves each replaced reader's S3FileIO unclosed, and Iceberg logs a WARN with a stack trace for each when it is collected | done | 2026-11-22 |
| [150](backlog/150.md) | Brokerage world 1: the ledger world streaming into Iceberg, booked by the kit's posting engine ported to Java | done | 2026-11-23 |
| [159](backlog/159.md) | Demo: the seed's sample grant goes to a group the grant admin is not in | done | 2026-11-23 |
| [160](backlog/160.md) | Heavy build: the audit gate and HealthRoutesTest miss item 114's maintenance requests | done | 2026-11-23 |
| [161](backlog/161.md) | Console: the Metadata log tab fails on a create's, register's or revive's journal entry | done | 2026-11-23 |
| [162](backlog/162.md) | ConsoleHealthTest reads the estate legend's count and words on two lines | done | 2026-11-23 |
| [163](backlog/163.md) | ConsoleSettingsBrowserTest expects zstd as a default, but iceberg-core 1.12 sets it on every new table | done | 2026-11-23 |
| [164](backlog/164.md) | One failing heavy test skips every bite, and one hung test can hold a leg until 06:30 | done | 2026-11-23 |
| [165](backlog/165.md) | An agent for each module whose tests fail in the overnight run, while the run goes on | done | 2026-11-23 |
| [166](backlog/166.md) | The overnight run tests in a worktree, not a clone | done | 2026-11-23 |
| [173](backlog/173.md) | Console: the tree carries the ref a page reads, listing each level through catalog@ref (item 109's departure 4) | done | 2026-11-23 |
| [174](backlog/174.md) | Demo: every UI signs in through Keycloak, Silo's console and Keycloak's admin console included | done | 2026-11-23 |
| [175](backlog/175.md) | Brokerage world: the bootstrap fixes the world's settings on a brokerage namespace the change feed made | done | 2026-11-23 |
| [176](backlog/176.md) | The fast build re-checks every bite each time, and agents run it again and again before landing | done | 2026-11-23 |
| [179](backlog/179.md) | The demo catalog is OOMKilled under the brokerage world's load: 768Mi with a 75% heap leaves too little outside the heap | done | 2026-11-23 |
| [180](backlog/180.md) | Demo: ana and presenter browse Silo's console, read-only | done | 2026-11-23 |
| [181](backlog/181.md) | The brokerage world on the real clock: each event at its own time of day, quiet off-hours, its sink restarted when it fails | done | 2026-11-23 |
| [183](backlog/183.md) | Console: sign-in opens the catalog used last, or the one with the most tables the user may read; an empty catalog names the other (Atelier's review Q1) | done | 2026-11-23 |
| [184](backlog/184.md) | Console: rail items, tabs and buttons a user's groups can never use are hidden, and each role lands where its work is (Atelier's review Q3) | done | 2026-11-23 |
| [185](backlog/185.md) | Console: Go to a path opens an exact dotted path, and its empty answer says what it searched (Atelier's review Q4) | done | 2026-11-23 |
| [186](backlog/186.md) | Console: who made a table's last change on its Overview, and a By column on Snapshots (Atelier's review Q5) | done | 2026-11-23 |
| [187](backlog/187.md) | Console: Audit filters by kind, its By kind rows apply that filter, its filters live in the link, and its own reads are hidden (Atelier's review Q6) | done | 2026-11-23 |
| [188](backlog/188.md) | Console: plain-words captions, with routes, statuses and request IDs behind Technical details (Atelier's review Q7) | done | 2026-11-23 |
| [189](backlog/189.md) | Console: one link style, and rows that open something look it (Atelier's review Q8) | done | 2026-11-23 |
| [190](backlog/190.md) | Console: the maintenance headline says when the catalog's policy has maintenance off (Atelier's review Q9) | done | 2026-11-23 |
| [191](backlog/191.md) | Console: a table's Iceberg refs named as engine-level refs, apart from the catalog's branches and tags, one icon per kind (Atelier's review Q10) | done | 2026-11-23 |
| [192](backlog/192.md) | Console: the rail's Health becomes Service and a table's Health tab Maintenance; the rail keeps its labels on Catalog (Atelier's review Q11) | done | 2026-11-23 |
| [193](backlog/193.md) | Console: sign-in survives a slow IdP, sign-out ends the IdP session in one step, one date format, no empty columns, no keyboard hints on touch (Atelier's review Q12) | done | 2026-11-23 |
| [194](backlog/194.md) | Console: the Health latency chart marks a share below its scale with its value (Atelier's review Q13) | done | 2026-11-23 |
| [195](backlog/195.md) | Audit goes to logs: the catalog serves and vends without Kafka, and the audit table writer takes the audit records over OTLP (D23) | done | 2026-11-23 |
| [196](backlog/196.md) | Demo switches: demo.sh gen and ss turn the brokerage generator and SingleStore on and off, as cdc does its leg; Kafka runs only while cdc or gen is on | done | 2026-11-23 |
| [198](backlog/198.md) | Console: one search that knows every name, a route listing what the caller may describe (Atelier's review S1) | done | 2026-11-23 |
| [199](backlog/199.md) | Console: object first, a table's ten tabs cut to six, with the same names at every level (Atelier's review S2) | done | 2026-11-23 |
| [200](backlog/200.md) | Console: the rail by role, with distinct words and a Home per role (Atelier's review S3) | done | 2026-11-23 |
| [201](backlog/201.md) | Console: the ref as global context, one Reading control in the header carried by every link (Atelier's review S4) | done | 2026-11-23 |
| [202](backlog/202.md) | Console: a copy pass across every page, by the words-table method (Atelier's review S5) | done | 2026-11-23 |
| [203](backlog/203.md) | Console: a browser keeps the old console after an upgrade, as every file answers 304 by a 1980 Last-Modified | done | 2026-11-23 |
| [204](backlog/204.md) | Console: snapshot ids lose precision in JSON.parse, so a copied id names no snapshot | done | 2026-11-23 |
| [205](backlog/205.md) | Console: audit words still say Kafka after audit moved to the log (item 195) | done | 2026-11-23 |
| [206](backlog/206.md) | Console: with maintenance off, the empty queue offers Edit the catalog's policy | done | 2026-11-23 |
| [207](backlog/207.md) | Console: maintenance policy keys get labels, each key in its title | done | 2026-11-23 |
| [208](backlog/208.md) | Console: the tree filter's empty answer offers Go to a path | done | 2026-11-23 |
| [209](backlog/209.md) | Console browser tests: one Chrome per server, faster polling, and none beside the demo | done | 2026-11-23 |
| [211](backlog/211.md) | Console: a first sign-in whose home is Audit lands on the catalog with the most records, not the first name | done | 2026-11-23 |
| [212](backlog/212.md) | Console: a ref's merge verdict on its Changes tab, so what a branch changed and whether it merges is one step | done | 2026-11-23 |
| [213](backlog/213.md) | Console: a regulated area says why and who set it, on Governance, and Access says Yes, not true | done | 2026-11-23 |
| [214](backlog/214.md) | Console: Audit uses the chips' words everywhere, badges and By kind included, so its counts add up | done | 2026-11-23 |
| [215](backlog/215.md) | Console: the monospace S5 missed, the Overview chart caption, number headers, kind words, dates | done | 2026-11-23 |
| [216](backlog/216.md) | Console: an audit record with no archive offers no archive check, and its labels read as words | done | 2026-11-23 |
| [217](backlog/217.md) | Console: smaller findings from Atelier's re-walk after phase 2 | done | 2026-11-23 |
| [218](backlog/218.md) | Console browser tests beside the demo: the agent takes the demo down, runs them and brings it back up (D49) | done | 2026-11-23 |
| [219](backlog/219.md) | Console: eight browser tests disagree with the pages S2, S3 and S5 made | done | 2026-11-23 |
| [221](backlog/221.md) | Console: branches and tags have a place in the rail, and making a branch is found there (Paul, 2026-11-23) | done | 2026-11-24 |
| [222](backlog/222.md) | Console: Compact a table now offers the catalog's tables in a dropdown, tonight's queue first (Paul, 2026-11-23) | done | 2026-11-24 |
| [223](backlog/223.md) | KafkaDownTest still expects a scan to fail while Kafka is down, which 195 changed: a Kafka outage blocks nothing | done | 2026-11-24 |
| [224](backlog/224.md) | Heavy build 2026-11-24: eight catalog-server tests disagree with items 193, 195, 206, 207 and 214 | done | 2026-11-24 |
| [225](backlog/225.md) | Console: Service's Most versions behind opens a table the operator may not read (Paul, 2026-11-23) | done | 2026-11-24 |
| [229](backlog/229.md) | The console header lists every group by name and runs off the screen | done | 2026-11-24 |
| [230](backlog/230.md) | The demo runs no maintenance worker, so Overview and Maintenance are empty | done | 2026-11-24 |
