# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**28 open:** 4 S, 9 M, 10 L, 5 XL.

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
| [109](backlog/109.md) | M | Console: the catalog's refs, the merge preview and a ref selector (item 26) | one panel and a selector over routes that exist, four actions, one browser test case; waits on items 104, 106 and 107 | 2026-11-20 |
| [110](backlog/110.md) | M | Demo: a catalog tag, a branch with Trino on it, and a merge (item 26) | the lake writer's new steps, one more Trino catalog and the README's walk, checked by hand in the running demo; waits on items 107 and 109 | 2026-11-20 |
| [111](backlog/111.md) | M | After a regional failover, report every ref that names a file the DR store lacks (item 26, question 3) | one more pass of the reconcile over the refs' metadata files, with a route test and a runbook step; waits on items 102 and 105, and on item 100, which holds `Reconcile.java` | 2026-11-20 |
| [112](backlog/112.md) | M | Branches, tags and merge: engines on refs, the docs and the gate (item 26) | engine matrix cases and one page of docs over code already built; the heavy and overnight runs are the overnight run's; waits on items 102 to 111 | 2026-11-20 |
| [113](backlog/113.md) | L | Grants that name a branch (later, D33) | a grant gains an optional branch on four stores, and every authorization check on a ref asks for it, with tests and bites | 2026-11-20 |
| [115](backlog/115.md) | L | Row filters and column masks stored in the catalog, served to Trino (D20) | a new kind of policy on four stores with a schema step, management routes and audit, a route Trino's access control calls, Trino reaching nuna as a trusted engine for its users, and a demo leg, with tests and bites | 2026-11-20 |
| [118](backlog/118.md) | XL | Untrusted engines read flagged tables through filtered copies made by a fleet, delivered by scan planning (D20) | the spec's three scan-planning routes, a fleet of filtering jobs with its own identity, temporary copies with their own credentials, lifetime and audit, a cache of copies, and a demo leg, with tests and bites on four stores; waits on item 115 | 2026-11-21 |
| [123](backlog/123.md) | M | Heavy build: Docker-backed services stall 30 s while two heavy modules share Docker | finding what stalls first (Docker Desktop's port forwarding, or CPU starved by two stacks), then either a schedule that keeps the heaviest modules apart or waits that outlast the stall; shown only by overnight runs | 2026-11-22 |
| [133](backlog/133.md) | L | Keep released holds and lifted DR freezes in the store, so their history is complete | a released and lifted record on all four stores with its suite in the shared test-jar, and the holds route reading it, with tests | 2026-11-22 |
| [135](backlog/135.md) | S | Windows pages the Docker VM out during the nightly: free the host's memory (Paul decides how) | once Paul picks a remedy: applying it and reading one overnight run's numbers; waits on Paul | 2026-11-22 |
| [149](backlog/149.md) | S | The audit feed leaves each replaced reader's S3FileIO unclosed, and Iceberg logs a WARN with a stack trace for each when it is collected | a close for the reader that leaves the server's shared S3 client open, and a test | 2026-11-22 |

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

### 107. Merge a branch into main, per table, in one bounded transaction (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-core/src/main/java/nuna/catalog/core/refs/ catalog-core/src/test/java/nuna/catalog/core/refs/ catalog-server/src/main/java/nuna/catalog/server/governance/RefRoutes.java catalog-server/src/test/java/nuna/catalog/server/governance/
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

### 109. Console: the catalog's refs, the merge preview and a ref selector (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: catalog-server/src/main/resources/nuna/ui/ catalog-server/src/test/java/nuna/catalog/server/ConsoleTest.java catalog-server/src/test/java/nuna/catalog/server/governance/ConsoleBrowserTest.java
- Effort: M, one panel and a selector over routes that exist, four actions, one browser test case; waits on items 104, 106 and 107

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

### 110. Demo: a catalog tag, a branch with Trino on it, and a merge (item 26)

- Added: 2026-11-20 by agent on item 26
- Touches: demo/
- Effort: M, the lake writer's new steps, one more Trino catalog and the README's walk, checked by hand in the running demo; waits on items 107 and 109

Part of item 26 (D33); its design section says why (choice 16).

What is missing: the demo's `LakeWriter` makes an Iceberg tag `eod-2026-11-09` and an Iceberg branch
`surveillance-review` inside the trades table, but no catalog ref, so none of D33's four uses can be tried there.

Build: `demo/demo.sh up` makes a catalog tag on the sample data and a branch `dev` with a change to two tables. Trino
gets a second catalog, `lakehouse_dev` (`iceberg.rest-catalog.warehouse=lakehouse@dev`), in the same Trino. The README
walks the uses: read as of the tag, change two tables on `dev`, preview and merge in the console, and the conflict
when main moved too.

Done when `up` (run only when Paul lets Docker be used for it) leaves the tag and the branch, Trino reads both, and
the README's walk works as written; checked by hand and recorded in `status/`. Waits on items 107 and 109.

### 111. After a regional failover, report every ref that names a file the DR store lacks (item 26, question 3)

- Decided: 2026-11-20, D33
- Added: 2026-11-20 by agent on item 26
- Touches: catalog-core/src/main/java/nuna/catalog/core/commit/Reconcile.java catalog-server/src/main/java/nuna/catalog/server/governance/ReconcileRoutes.java deploy/README.md
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

Done, and item 26 with it, the first morning the overnight run's heavy build, Ceph leg, `./nightly.sh` (the engine
matrix included) and bites are all green with items 102 to 111 landed. Waits on items 102 to 111.

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

### 133. Keep released holds and lifted DR freezes in the store, so their history is complete

- Added: 2026-11-22 by item 128
- Touches: catalog-store-api/, catalog-store-sql/, catalog-store-sql-raft/, catalog-store-oracle/, catalog-store-mongo/, catalog-server/src/main/java/nuna/catalog/server/governance/GovernanceSummary.java, catalog-server/src/main/java/nuna/catalog/server/governance/HoldHistory.java, catalog-server/src/main/java/nuna/catalog/server/governance/GovernanceRoutes.java
- Effort: L, a released and lifted record on all four stores with its suite in the shared test-jar, and the holds route reading it, with tests

What is missing: `GET /management/v1/catalogs/{catalog}/governance/holds` (item 128) lists the holds released and
DR freezes lifted from the audit topic, because the store deletes a hold's row when it is released
(`GovernanceStore.releaseHold`) and a freeze's attribute when it is lifted (`removeAttribute`). The read is bounded as
`GET .../audit` is: the last 20,000 offsets of each partition. Every load and denial is audited to the same topic,
so in a busy deployment those offsets cover minutes, not the 30 days the console's prototype shows ("Lifted in the
last 30 days"). The route says how far back it read (`history.since`), so it never claims more than it holds, but the
console's list of lifted holds will usually stop short at work.

Item 128 took the audit topic because keeping the history in the store is a schema step on all four stores, and
item 126 held those modules when 128 was built; the queryable audit tables (D23) are read by one named group and
need item 125's reader.

What done looks like: a hold's release and a freeze's lift recorded in the store with who and when (a released and
lifted record, or the row kept with `released_at` and `released_by`), on all four stores with a suite in the shared
test-jar; the holds route reading its lifted list from the store for a window it names (30 days, say), complete; and
its tests. The audit topic read goes.

**Progress, 2026-11-22 (worktree-agent-a776179e2fcd3c868): built; open until the heavy build passes `GovernanceSuite`
on all four stores.**
- The record: `GovernanceLift` (kind `HOLD` or `DR_FREEZE`, the hold's id, the target, reason, placed by and at,
  lifted by and at). `releaseHold` and `removeAttribute` take who lifts; a release, and a DR freeze's removal, write
  one in the change's own transaction, with the catalog the level was in, so it outlives the level.
  `liftedIn(catalog, since)` lists a catalog's lifts at or after `since`, the most recent first.
- Schema steps, additions only: PostgreSQL `V14__governance_lifts.sql`, CockroachDB `V13__governance_lifts.sql`,
  Oracle `V15__governance_lifts.sql`, MongoDB step 13 ("governance lifts"): a `governance_lift` table or collection
  indexed by catalog and lift time. A separate table, not the hold row kept with `released_at`: a server of the
  previous release would still read a kept row as a standing hold. Its releases during a rolling upgrade are kept
  only in the audit record of the change.
- Shared suite: `GovernanceSuite#releasesAndLiftsAreKept` (who and when, repeats `Missing`, the window, one catalog
  only, the change rows) and `#releasesAndLiftsOutliveTheirLevel` (a dropped namespace, a purged table).
- The route: `HoldHistory` reads the store for the last 30 days; the audit-topic read is gone. `history` is
  `{days, since}`; a lift on a level dropped since has a null `path`, and the console names it by kind and id.
- Not covered: dropping a namespace removes its attributes, a DR freeze among them, without keeping a lift (a frozen
  namespace's drop is not refused today).

Left: the overnight heavy build's run of the store suites on the four stores.

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

### 149. The audit feed leaves each replaced reader's S3FileIO unclosed, and Iceberg logs a WARN with a stack trace for each when it is collected

- Added: 2026-11-22 by the agent on item 148
- Touches: catalog-audit-tables/src/main/java/nuna/audittables/EventsReader.java, catalog-server/src/main/java/nuna/catalog/server/governance/AuditFeed.java
- Effort: S, a close for the reader that leaves the server's shared S3 client open, and a test

`AuditFeed.reader()` opens a new `EventsReader` whenever the audit table's pointer has moved (the writer commits about
once a minute) and drops the old one without closing it. `EventsReader.open` makes an Iceberg `S3FileIO`, and Iceberg
logs a WARN from the finalizer for every one collected unclosed. The nuna-demo's server log, 2026-11-22, three of them
in the minutes after Paul's click-through:

```
{"log":{"level":"WARN","logger":"org.apache.iceberg.aws.s3.S3FileIO"},"process":{"thread":{"name":"Finalizer"}},
 "message":"Unclosed S3FileIO instance created by:\n\torg.apache.iceberg.aws.s3.S3FileIO.<init>(S3FileIO.java:146)
 ...\n\tnuna.audittables.EventsReader.open(EventsReader.java:198)\n\tnuna.catalog.server.governance.AuditFeed.reader(
 AuditFeed.java:139) ...
```

`EventsReaderOnTheServerTest` (item 148) prints the same warning. With item 148 fixed the feed works, so a server
whose Audit page is open logs one such WARN for about each commit of the writer.

Closing the `S3FileIO` as it stands would close the S3 client it was handed (Iceberg 1.12's `PrefixedS3Client.close`
closes it), which is the server's client for the audit catalog (`AuditFeed.s3`), shared by every reader after it.

What done looks like: a replaced reader is closed without closing the shared S3 client (for example, the reader hands
`S3FileIO` a client whose `close` does nothing), and a test shows a replaced reader is closed and the next one still
reads.

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
