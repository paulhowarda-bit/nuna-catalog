# Backlog

Every item in `backlog/`, in one file. `./backlog.sh log` makes it from the item files, and `land.sh` puts it
in every landing, so it is as current as `main`. Do not edit it: change the item file, `backlog/<n>.md`, and
land that. Who holds an item is not here: `./backlog.sh list` shows the claims.

Effort is the working time one agent needs to finish the item, builds and tests included, once nothing it waits
on is in the way: **S** under 2 hours, **M** up to a day, **L** 2 to 5 days, **XL** more than 5 days (a
milestone). The words after the size say why, and what the item waits on, if anything.

**20 open:** 2 S, 10 M, 4 L, 4 XL.

| # | Effort | Item | Why that size | Added |
|--:|:-:|:--|:--|:--|
| [1](backlog/1.md) | S | Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks) | closes once a night's heavy build and Ceph leg are green and every bite has bitten in its latest run (D42, amended 2026-11-25) | 2026-11-14 |
| [17](backlog/17.md) | S | Console sign-in against Entra ID and Ping, with an automated test | signing in and out against Entra ID and Ping at work (waits on work) | 2026-11-14 |
| [22](backlog/22.md) | XL | Administer storage profiles and catalogs at runtime (not now, D41) | routes, storage on four backends, audit and the startup checks; only if Paul wants it | 2026-11-14 |
| [24](backlog/24.md) | L | Kubernetes deployment: what was not checked (at work) | five checks at work: Ingress, IRSA or Pod Identity, OpenShift, zones, sizing | 2026-11-14 |
| [25](backlog/25.md) | M | One hot table under contention: throughput and fairness (measure at work) | a measuring run on work hardware; a change only if Paul wants one | 2026-11-14 |
| [26](backlog/26.md) | XL | Branches, tags and merge (v1.x, D33) | the milestone over build items 102 to 112 (seven L, four M; see the design below) | 2026-11-14 |
| [29](backlog/29.md) | XL | M9: carry nuna to work (at work) | the whole install checklist, at work | 2026-11-14 |
| [43](backlog/43.md) | L | Hook nuna into work's OpenTelemetry collector and Prometheus (at work) | findings at work, Helm values, perhaps a Prometheus endpoint, artifact approval | 2026-11-15 |
| [44](backlog/44.md) | M | Grafana dashboards and Prometheus alert rules for nuna (at work) | at work: rename to item 43's series names where they differ, import, fire each of the 17 alerts once in a test; waits on item 43 | 2026-11-15 |
| [97](backlog/97.md) | M | A KMS key for MinIO and Ceph storage profiles (D26) | a KMS beside the Silo and Ceph test stores, the key id each takes, and whether their STS session policies enforce the SSE condition keys; waits on Paul if on-prem keys are not wanted yet | 2026-11-20 |
| [109](backlog/109.md) | L | Console: the catalog's refs, the merge preview and a ref selector (item 26) | the ten screens of Atelier's design (2026-11-22) and the server reads they need beyond items 104 to 107 (below); was M before the design; waits on items 104, 106 and 107 | 2026-11-20 |
| [112](backlog/112.md) | M | Branches, tags and merge: engines on refs, the docs and the gate (item 26) | engine matrix cases and one page of docs over code already built; the heavy and overnight runs are the overnight run's; waits on items 102 to 111 | 2026-11-20 |
| [118](backlog/118.md) | XL | Untrusted engines read flagged tables through filtered copies made by a fleet, delivered by scan planning (D20) | the spec's three scan-planning routes, a fleet of filtering jobs with its own identity, temporary copies with their own credentials, lifetime and audit, a cache of copies, and a demo leg, with tests and bites on four stores; waits on item 115 | 2026-11-21 |
| [167](backlog/167.md) | M | Refs: a description given when a branch or tag is made, kept on the ref and shown in the console (item 109's gap 8) | a nullable column and its migration on PostgreSQL, CockroachDB and Oracle and a field on MongoDB's document, carried by create and every read, a `RefSuite` case on four stores, the route's field and limit, and the console's purpose line; the suite case then waits for the overnight heavy build | 2026-11-23 |
| [169](backlog/169.md) | M | Audit: records filterable by the branch they went through (item 109's gap 10) | a `branch` column (and the subject's id as a filter) on the audit events table, its schema evolved in place on existing tables, the writer filling it from each record, the feed's two filters, and the console's search; the audit table tests start containers, so they wait for the overnight heavy build | 2026-11-23 |
| [265](backlog/265.md) | M | Traces: keep every failing request's trace, by tail sampling in the collector and a 5xx answer marking its span ERROR (at work) | at work with item 43 (D54: nothing left here); was at home a test that every 5xx answer's server span ends ERROR and the fix where it does not, with a bite; at work, with item 43, the collector's two tiers and the charts' sampling; the work part waits on item 43 | 2026-11-24 |
| [317](backlog/317.md) | M | Internal documents disagree with the code | many small corrections across the internal Markdown, each checked against the code | 2026-11-27 |
| [323](backlog/323.md) | M | Platform operators list the standing DR freezes they may lift | a read route or a change to one, its authorization and audit, and the Governance page for an operator | 2026-11-27 |
| [332](backlog/332.md) | M | Sign-in screen: the identity provider's name in words, and the developer token box folded | the code is small, but the docs screenshot needs the demo rebuilt | 2026-11-27 |
| [333](backlog/333.md) | L | A brand slot for the console and the docs site: Ice line by default, an install's pack by settings | 2 to 5 days; it waits on nothing, the real pack is work's job | 2026-11-27 |

## Open items in full

### 1. Heavy pass to harden v1.x phases 1 and 2 (after the install at work, only when Paul asks)

- Decided: 2026-11-19, D42; 2026-11-25, D42 (amended)
- Added: 2026-11-14 by Change Manager, from build-plan.md at Paul's request
- Effort: S, closes once a night's heavy build and Ceph leg are green and every bite has bitten in its latest run (D42, amended 2026-11-25)

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

**Rescoped 2026-11-25 (D42, amended):** no night can run every bite: the run stops them at 06:30 and the next night
starts where it stopped, so a night runs about 560 of the 724 and every bite comes round in two nights. Paul: "Yes,
rescope it". This item closes once both hold:

- one night's heavy build and Ceph leg are both green;
- every recorded bite has bitten in its most recent run, over the nights the bites take to come round (each night's
  `bites.log` in `.overnight/runs/<date>/`), with no bite left NO BITE or SETUP.

Where it stood on 2026-11-25: no night since D42 had a green heavy build (2026-11-19 to 2026-11-25); the failures of
2026-11-25 were fixed that day (items 268 to 271), so 2026-11-26 is the first night that may.

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

**Added 2026-11-24, from the observability-audit research report** (in that agent's home,
`research/reports/Nuna observability and audit grounding.md`, "Audit lines outlive the pod only if the collector is
built to keep them" and "Nuna's series names land as drafted, with four corrections"):

- **The record of audit rides on work's collector.** Since D23's amendment of 2026-11-23 the log `nuna.audit` on
  stdout is the record of audit, and nuna ships no collector configuration (`deploy/` has no `filelog`,
  `sending_queue` or `file_storage`), so whether a record survives past stdout is a property of work's collector. Its
  defaults are at most once: the exporter's queue is in memory, acknowledges on admission and drops when full; the
  `batch` processor reports success before anything is sent (deprecated 2026-11-11); filelog without `storage`
  restarts at the end of each file and skips what was written while it was down; and OpenTelemetry's Helm
  `logsCollection` preset sets `storeCheckpoints: false`. Find what work's collector does with pods' stdout, and write
  into `docs/observability/` the recipe the `nuna.audit` lines need, asking the platform team for what it lacks:
  - filelog with `storage: file_storage` on a PersistentVolume or hostPath, `retry_on_failure` on, and the `container`
    operator, which rejoins the lines containerd splits above 16 KiB, with a deliberate `max_log_size` (1 MiB by
    default);
  - no `batch` processor; the exporter's `sending_queue` with `storage: file_storage`, sized in bytes or items,
    `block_on_overflow: true`, `retry_on_failure.max_elapsed_time: 0` (or long), and `fsync: true` if a node's power
    loss counts;
  - where the records go on to the audit table writer, `X-Nuna-Audit-Token` as a static exporter header: what an auth
    extension sets does not cross a persistent queue;
  - the end of a pod: the chart's `preStop` sleep (5 s, `deploy/helm/nuna-catalog/templates/deployment.yaml:92`) runs
    before shutdown, so it does not cover the lines written during the graceful shutdown that follows, the relay's
    last drain among them. Find whether work's collector reads a terminated container's file before the pod is
    removed, and whether Boot closes its `SdkLoggerProvider`, flushing the OTLP copy, at shutdown (neither verified).
  - how long a collector outage the node's files cover at the catalog's log rate: the kubelet keeps 5 rotated files of
    10 MiB per container, and eviction removes a pod's logs with it.
  Each at-least-once setting brings duplicates after a crash, some hours late; item 263 makes the writer leave them out
  however late they come.
- **Names.** Prometheus 3.16 and both collector Prometheus exporters name OTLP metrics with one library
  (`prometheus/otlptranslator` v1.0.0), so the drafted names should hold. Check four corrections here and write them
  into `docs/observability/dashboards-and-alerts.md`:
  - `translation_strategy` (default `UnderscoreEscapingWithSuffixes`) governs naming, not `add_metric_suffixes`, which
    the doc names (line 28) and the exporters now ignore or deprecate: pin it in Prometheus' `otlp:` block and in any
    collector Prometheus exporter.
  - Micrometer 1.17 (Boot 4.1.2's) publishes a `.max` gauge for every timer, which lands as, for one,
    `nuna_catalog_requests_max_milliseconds` (inferred) and is not in the doc's series list: list them, or set
    `management.otlp.metrics.export.publish-max-gauge-for-histograms=false`.
  - Native-histogram conversion (`otlp.convert_histograms_to_nhcb`) must stay off, or every `_bucket` query returns
    nothing, silently; item 261's absence alerts catch it.
  - Pushed series have no `up` and no staleness marker: item 261's absence alerts stand in for `up`.
  Prometheus needs `--web.enable-otlp-receiver`, version 3.15 or later (3.10 to 3.16 fixed OTLP losses and a doubled
  `+Inf` bucket), `out_of_order_time_window: 30m`, and `--enable-feature=exemplar-storage` to keep the exemplars Boot
  already sends.

Done when a load from a work engine shows as a trace and as metrics in work's Grafana, and the findings above are in
`docs/observability/`, the audit path's collector recipe and the four name checks included.

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

## Atelier's design, 2026-11-25

Relayed word for word by the Catalog UI Expert session.

**167 (a description on a branch or tag): no change to the design.** The purpose line, its two places and its sentence stand. The design never worded the make dialog's field; these are its words:
- Label: `Purpose (optional)`
- Placeholder: `why it exists, such as: before the November restatement`
- Hint: `One line, at most {max} characters. Everyone who can see {catalog}'s branches and tags reads it, and the audit keeps it.`
- When there is no description, there is no line and no filler. For a caller without `create`, who gets no `created-by` (item 106), the page's sub-line reads `{purpose}. Made from {from} on {created}.`

### Atelier's changes, 2026-11-25 (relayed by the coordinator)

1. In refs.js `purpose`, change `/[.!?…]$/u` to `/[.!?…]["'”’)\]]*$/u`, so a purpose ending in a quote or bracket
   after its stop gets no second period.
2. Keep `overflow-wrap: anywhere` on `.refs-tbl td.name .why` and on `.purpose`. If the audit feed row's text has no
   such rule, add one.
3. In refs.js `refWords`, `case 'CREATED'`: when `d.description` is present, add `. Its purpose: “{description}”` to
   the end of either form. Use typographic quotes, no period after them, and text only. With no description, the line
   is unchanged. Renewed, merged and ended lines leave the purpose out.

## Built, 2026-11-25 (branch `worktree-agent-a18b4d6ea289528fc`)

Built on the branch of items 158 and 171 (`worktree-agent-ac5aa9e6de1840a97`), which changes the same refs store
files; the two land together.

- **The record.** `Ref` gains `Optional<String> description` (`catalog-store-api/.../Ref.java`), never blank; every
  caller passes it (tests pass `Optional.empty()`).
- **The four stores.** A nullable column `description`: PostgreSQL `V18__ref_description.sql` (TEXT), CockroachDB
  `V17__ref_description.sql` (TEXT), Oracle `V19__ref_description.sql` (VARCHAR2(1000 CHAR), through the text
  codec). Existing refs read as none. MongoDB writes a `description` field, null without one, and reads a missing
  field as none. `SqlRefs`, `OracleRefs` and `MongoRefs` write it on create and read it on every read
  (`readRef`, `findRef`, `listRefs`, `listRefPointers`).
- **The route** (`RefRoutes.java`): `POST …/refs` takes an optional `description` (`description(JsonNode)`,
  ~line 1045). Absent, null or blank is none. The server bounds it so:
  - its ends are stripped of white space, no-break spaces included (`stripped`, ~line 1074);
  - at most `MAX_DESCRIPTION` = 200 characters, counted in code points, else 400 `Field description is longer than
    200 characters (n)`; the list's `limits` carries `max-description`;
  - one line of plain text (`notInALine`, ~line 1090): no control character (C0, DEL, C1, so no line break or tab), no
    line or paragraph separator, no lone surrogate, no bidirectional embedding, override or isolate
    (U+202A to U+202E, U+2066 to U+2069); else 400 naming the character's position and code point;
  - a non-string is 400.
  Every ref answer (list, read, create, renew) carries `description` to any caller who may list or read the refs
  (item 106's rule), unlike `created-by`. A renewal keeps it. The `CREATED` `nuna.catalog.ref` record carries it.
- **The audit record.** `RefChange` gains a nullable `description`. A record with one is written as
  `urn:nuna:catalog:event:ref:2`; every other ref record leaves the field out and is written as `ref:1`, exactly as
  before (the golden line is unchanged), so a server of the previous release reads every record it could read before
  (Nuna Security Expert, 2026-11-25). `EventType.REF` names the field version 2 added (`added`); `EventCodec` drops it
  when null (`payloadTree`), writes the version that matches (`versionOf`), and reads either, refusing a `ref:1` that
  carries a description and a `ref:2` without one (`readPayload`). An audit table row, which keeps no schema version,
  is read by the same rule (`EventCodec.payload`, used by `AuditRows`). The shape follows item 113's
  `payloadTree`/`versionOf` without sharing its code.
- **The console** (`ui/refs.js`, `ui/api.js`, `style.css`): the make dialog's `Purpose (optional)` field with Atelier's
  placeholder and hint (`{max}` from `limits.max-description`), sent only when not empty; the refs list's Ref cell
  shows the purpose under the chip (`fine why`, as the prototype draws it); the ref's page sub-line reads
  `{purpose}. Made from main by {created-by} on {created}.`, or without `by` for a caller without `create`. No
  description, no line. The text goes in through `h()` text nodes only. A refusal shows as the catalog answered.
  A `created` record's words, in the audit feed and on the ref's page, end `. Its purpose: “{description}”` when it
  has one (Atelier's change 3). The feed's text already wraps anywhere (`.feed-row > div`), so no rule was added.
- `docs/refs.md`: the field, its bounds and who reads it.

Tests:
- Fast, run and passing: `RefRoutesTest` (30, two new:
  `aDescriptionIsKeptOnTheRefShownToEveryReaderAndRecordedOnItsCreation`,
  `aDescriptionPastTheLimitOrNotOneLineOfTextIsRefused`); `EventCodecTest` (245, four new on the version-1 record and
  row) and `AuditRowsTest`; the ref tests that build a `Ref` in `catalog-core`, `catalog-store-api` and
  `catalog-server`.
- Heavy, written and compiled, not run: `RefSuite#aRefKeepsItsDescription` (four stores: 200 characters, one outside
  the BMP, with HTML's characters; read, find, list, the pointers' list, a renewal; not inherited by a ref made from
  it); `ConsoleBrowserTest#aRefMadeWithAPurposeShowsItAsText` (the dialog's words, the list and the page, markup shown
  as text).

Bites, recorded in `bites/D33/` and checked with `git apply --check`, not run: `ref-description-not-one-line`,
`ref-description-unbounded`, `ref-description-unaudited`, `ref-description-hidden-from-readers`,
`ref-description-lost-on-renewal`, `audit-ref-previous-version-unread`, `audit-row-previous-version-unread`,
`audit-ref-written-as-version-2` (fast);
`sql-ref-description-unstored`, `oracle-ref-description-unstored`, `mongo-ref-description-unstored` (heavy).

Choices made (Paul may overrule):
1. The limit is 200 characters, counted in code points: enough for one line of purpose, short enough for the list's
   cell. The console's `maxlength` counts UTF-16 units, so it never lets more through than the server takes.
2. Ends stripped and blank taken as none, rather than refused: the dialog sends an empty field as nothing anyway.
3. Bidirectional controls are refused besides control characters, so a purpose cannot render as other text; other
   format characters (zero-width joiners in emoji) are kept.
4. The audit carries the description only in the `CREATED` record, as the item asks; the ref's id ties the rest to it.
5. The ref record's payload has a version 2 rather than a field quietly added to version 1: the codec reads strictly,
   so a field added to version 1 would have made every stored ref record unreadable. Only a record with a description
   is version 2; the rest stay version 1, so a rolling upgrade's older servers keep reading them (security's change).
6. The migrations take the next free numbers on main (PostgreSQL V18, CockroachDB V17, Oracle V19), at the
   coordinator's word, 2026-11-25.
7. On the page's sub-line a purpose already ending in `.`, `!`, `?` or `…`, closing quotes or brackets after it
   included, gets no second period.

Left:
- The heavy cases and store bites run in the overnight run.
- Item 113's branch (`branch-grants-113`) takes the same numbers for `ref_grant` (PostgreSQL V18, CockroachDB V17,
  Oracle V19). If 167 lands first, 113 renumbers above these, as whoever lands second settles: a lower number
  landing after a higher one is out of order for a database already migrated, such as the demo's.

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

## Atelier's design, 2026-11-25

Relayed word for word by the Catalog UI Expert session, from her answer to "Items 167, 169 and 170: has my view
changed since 2026-11-22?":

**169 (audit records filtered by branch): two changes.**
1. **A reused name mixes refs.** The `branch` field of commit and read records holds the ref's name, not its id. A name used again after a ref ends would mix the two refs' records.
   - The Record tab's button becomes `Every record through @{name} since it was made`, with `from` set to the ref's `created-at`.
   - The feed's `@name` search sets `from` to the live ref's `created-at` when the reader has set no time.
   - With no live ref of that name, it shows `No live ref is named @{name}: these are records through every ref that had the name.`
2. **The subject filter must reach item 156's records.** Item 156's `OBJECT_REMOVED` records carry the table in their `tables` payload, not as their subject. So `subject=<object id>` must also return `ref` records whose `tables` name that object. Without that, no table's Audit and no state of 170 can say who took it out of a ref, or when.

## Atelier's ruling, 2026-11-25

**1. The status line** (`feed-status`). When a start applies, insert `from {time} ` before `up to`:
`{n} records shown, newest first, from {d Mon HH:MM} up to {committed}. Records arrive here about a minute after they happen.`
`{time}` is the start used: `st.from`, or the live ref's `created-at`. It uses `at()`, as `up to` does. Without a start, the line stays as it is.

**2. The note line** (the existing `refNote`, above the status line). It says why the feed starts there and offers the way out.
- `@name` searched, live ref found, its making used: `From @{name}'s making, {d Mon HH:MM}: records through an earlier ref of the same name are left out.` Button: `Include earlier refs named @{name}`
- `from` came in a link and equals the live ref's `created-at`, with `@name` searched (the Record tab's link): the same words and button as above.
- `from` came in a link, any other case: `From {d Mon HH:MM}, as the link asked.` Button: `Show earlier records too`
- No live ref of that name: keep the built words, `No live ref is named @{name}: these are records through every ref that had the name.` No button.

How the buttons work:
- Both are `btn sm ghost`, after the words, on the same line.
- Each removes the lower bound and keeps the search, Who, Up to and the kind.
- Each writes `from=any` to the hash, so the ref's making is not applied again on reload or on the next restart. `readFilters` must accept `any`.
- After a button is pressed, the note line reads: `Records through every ref that has had the name @{name}.`
- Editing the search box still clears a linked `from`, as built.

**3. One code fix.** The branch skips the ref's making whenever Up to is set (`if (r && !st.from && !st.upTo)`). Up to sets only the end (`to`), and the start exists to keep a reused name's earlier ref out, which matters just as much then. Apply the ref's making unless `st.from` is set or is `any`.

**4. Item 220's "· N records hidden".** She agrees: drop it while a ref is searched (`&& !refName()`). Keep the checkbox.

## Atelier's changes after review, 2026-11-25

1. The status line with a start is exactly `{n} records shown, newest first, from {at(since)}, as of the last commit
   at {at(committed)}.` The line without a start stays as built; the sentences after it are unchanged.
2. When the start falls after Up to's end, no request is sent and the status line stays empty; the feed says
   (`muted feed-none`), for the ref's making: `No record went through @{name} by {at(to)}: it was made on
   {at(since)}. Set Up to later, or include earlier refs named @{name}.`; for a link's start: `No record can match:
   the link's start, {at(since)}, is after the end Up to sets, {at(to)}. Set Up to later, or show earlier records
   too.` The note line above keeps its button.
3. A linked `from` with no `@name` leaves the note blank, as built.

Built in `audit.js` (`page` and `restart`); the heavy `ConsoleBrowserTest` ref-search test asserts the new status
line, the making case's words and the empty status line. No fast console rules test asserts these words.

## Built, 2026-11-25 (worktree-agent-a1a2f439df6ecefe1)

Everything above is built; the audit-table writer's end-to-end test and the console's browser test are heavy, so they
were written and compiled, not run here. Open until the overnight run passes them and bites them.

- **Two columns** on `events` (`EventsTable.java:100`): `branch` (string, id 22) and `subject_ids` (list of string, id
  23, element 24). `EventsTable.open` gives a table made without them both, in one schema change, as it gave
  `log_run` and `log_seq` (backlog 272): no file is rewritten, no snapshot made, and older rows read null. The writer
  fills them (`EventsTable.java:273`) from `AuditRow`'s two new fields, which `AuditRows.of` maps
  (`AuditRows.java:82`): `branch` is a commit's and a read's `branch` (`main` or the ref's name as given) and a ref
  record's ref name; `subject_ids` is a ref record's ref id, then the ids in its `tables` (a merge's tables, or the
  object item 156 took out of the ref). Every other type leaves both null.
- **The reader** (`EventsReader.Query`, `EventsReader.java:134`) takes `branch` (compared normalized, as the catalog
  compares names; files with no branch at all are passed over by a `notNull` filter, `:287`) and `subject` (the row's
  `object_id`, or any of its `subject_ids`, `:375`). Both are matched in the scan, inside the catalog filter and the
  page budget, so a narrow filter comes back short with `searched-to` as the others do.
- **The feed** (`AuditReadRoutes.java:182`) takes `branch=<name>` (no `@`; 400 for one with `@` or blank) and
  `subject=<id>` (a UUID, else 400). Who may read is unchanged: the route still requires `select` on the audit
  catalog first (`requireAuditReader`), and the filters only narrow the one catalog's records the caller could
  already read whole. Each row now carries `branch` (`:452`).
- **How 170 and 279 ask who took a table out of a ref, and when:**
  `GET /management/v1/catalogs/{catalog}/audit/feed?subject=<table id>&type=nuna.catalog.ref`. Each row whose
  `event.data.action` is `OBJECT_REMOVED` is one ref the table left: who is `principal` (and `event.data.actor`), when
  is `time`, which ref is `branch` (its name) with `event.data.ref` (its id) and `event.data.kind` (`BRANCH`/`TAG`).
  A `MERGED` row names the table too (a merge moved it onto main). Follow `next-page-token` while a page stops short
  with `searched-to`. The caller needs `select` on the audit catalog; the console asks only when `auditOpen` says so.
- **Console.** The feed's search field (placeholder `Table, namespace or @ref`, 6.8) reads `@name` as `branch=`
  (`audit.js:206`, `:263`). When the reader set no time (no `from` in the link, Up to at "Up to now"), it asks from
  the live ref's `created-at` (`:301`); with no live ref of that name it says `No live ref is named @{name}: these are
  records through every ref that had the name.` (`:305`); the chips show no counts beside a ref's records (6.8). The
  link keeps `from` (a time the Record tab's link set), dropped when the search changes. A ref's Record tab asks
  `subject=<ref id>` (`refs.js:751`) instead of scanning four pages of every ref's records, and links `Every record
  through @{name} since it was made` (`refs.js:762`), to the feed with `path=@name&from=<created-at>`.
- **A bug found and fixed:** the Record tab, "Ended lately" and the ended-ref note read `x.data` and `x.subject` on
  feed rows, which carry the record as `event`; they found nothing. They now read each row's `event`
  (`refs.js:339`, `:752`).
- **Tests.** Fast and passing: `AuditRowsTest#commitsReadsAndRefRecordsNameTheRefAndARefRecordListsItsRefAndTables`;
  `EventsReaderTest#filtersByTheRefARecordWentThroughOrIsAbout`, `#filtersByTheObjectOrRefARecordIsAbout`,
  `#aTableMadeBeforeTheRefColumnsIsGivenThemAndItsEarlierRowsMatchNoRefOrSubject` and the 272 table test extended
  (all four columns, no snapshot made); `TableWriterTest` (the columns, a commit's `branch`);
  `AuditFeedRoutesTest#theRefAndSubjectFiltersReachTheTableWithinTheCatalog`,
  `#theRefAndSubjectFiltersAreRefusedToWhoeverMayNotReadTheFeed` and four more 400 cases. Heavy, written and
  compiled: `AuditTablesTest#aRowForEachKindOfRequestAboutTwoIntervalsBehind` (a commit's row names `main`, a drop's
  none) and `ConsoleBrowserTest#theFeedsRefSearchReadsFromTheLiveRefsMakingAndTheRecordTabAsksByItsId`.
- **Bites, recorded, not run:** `bites/D23/` `commit-row-drops-branch`, `ref-row-drops-tables`, `table-drops-branch`,
  `old-table-not-given-ref-columns`, `feed-any-ref-for-a-ref`, `feed-subject-misses-ref-tables`,
  `feed-branch-not-asked`, `feed-subject-not-asked`, `feed-filtered-read-unguarded`; `bites/UI/`
  `console-ref-search-mixes-refs`, `console-record-tab-every-ref`. Recorded again against this code, same breaks:
  `bites/OBS-272/reader-drops-log-run`, `row-drops-stamp`, `table-drops-log-seq`.

**Choices made (Paul may overrule):**
1. A ref record's own `branch` is its ref's name, so `branch=q3` gives the commits and reads through `@q3` and `@q3`'s
   own records (6.8: "records through or about that ref") with one filter.
2. `subject_ids` is one list column (the ref, then its tables) rather than a column for the ref and another for the
   tables: one filter, `object_id` or any of the list, answers "about this object or ref".
3. (Overruled by Atelier's ruling, item 3: the making now applies with Up to set too.) When the ref was made after
   Up to's end, the feed asks nothing and shows "No record matches": the route refuses a `from` after `to`.
4. When the refs list cannot be read, the search asks with no `from` and shows no note: saying "no live ref" would be
   a guess.

**Left:**
- Records written before this change carry neither column, so `branch=` and `subject=` miss them (the item's "old rows
  read as null"); 156's `OBJECT_REMOVED` records made before it landed are not found by a table's subject.
- The overnight run: the two heavy tests and the bites above and below.
- Item 113 (branch grants, branch `branch-grants-113`): once it lands, `AuditRows` should fill `branch` for a grant
  naming a branch (`case GrantChange e -> e.branch()`), and perhaps put the branch id in `subject_ids`. Whichever of
  169 and 113 lands second adds it. Both edit the `new AuditRow(...)` call at the end of `AuditRows.of`: 113 changes
  the payload argument to `EventCodec.payload(event)`. The second to land keeps both changes (Security Expert,
  2026-11-25).

## After review, 2026-11-25

- Merged `main` (items 220 and 197): `EventsReader.Query` keeps 197's `objectId` beside `branch` and `subject`, and
  both pushdowns; the feed label keeps 220's hidden count, which is now dropped while a ref is searched (Catalog UI
  Expert; Atelier's item 4).
- Atelier's ruling, items 1 to 3, in `audit.js`: the status line says `from {time} up to {committed}` when a start
  applies; the note line gives the four cases with their `btn sm ghost` buttons (`data-act="any-time"`), which set
  `from=any` in the link (`readFilters` keeps `any`); the ref's making applies with Up to set. A start not before the
  end asks nothing and shows "No record matches", as the route would refuse it.
- Security Expert: `branch=` is normalized in the route, and a name it cannot normalize, or one with a lone
  surrogate, answers 400 (`AuditReadRoutes.branch`; new 400 case `q\uD800` in
  `AuditFeedRoutesTest#aBadlyAskedReadIs400AndReadsNothing`). Field ids checked: both old-table tests assert
  `branch` 22, `subject_ids` 23, its element 24.
- `ConsoleBrowserTest#theFeedsRefSearchReadsFromTheLiveRefsMakingAndTheRecordTabAsksByItsId` (heavy, compiled, not
  run) now covers the note's words, the status line, Up to keeping the start, the button and `from=any`, the Record
  tab's link and a link's other time. Bite recorded, not run: `bites/UI/console-ref-start-dropped-with-up-to`.

### 265. Traces: keep every failing request's trace, by tail sampling in the collector and a 5xx answer marking its span ERROR (at work)

- Decided: 2026-11-27, D54
- Added: 2026-11-24 by observability-audit, from its research report of 2026-11-24
- Touches: catalog-server/src/main/java/nuna/catalog/server/metrics/RequestMetrics.java catalog-server/src/main/java/nuna/catalog/server/metrics/MetricsWiring.java catalog-server/src/main/java/nuna/catalog/server/ErrorResponses.java catalog-server/src/test/ deploy/helm/nuna-catalog/values.yaml deploy/helm/nuna-maintenance/values.yaml docs/observability/README.md docs/observability/spans.md bites/
- Effort: M, at work with item 43 (D54: nothing left here); was at home a test that every 5xx answer's server span ends ERROR and the fix where it does not, with a bite; at work, with item 43, the collector's two tiers and the charts' sampling; the work part waits on item 43

**What is wrong.**

- **Head sampling drops most error traces.** The charts keep 10 % of new traces (`telemetry.sampling: "0.1"`,
  `deploy/helm/nuna-catalog/values.yaml:105` and `deploy/helm/nuna-maintenance/values.yaml:135`;
  `parentbased_traceidratio`, `docs/observability/README.md`, "Sampling"). The decision is made before the request
  runs, so about 90 % of failing requests leave no trace (inferred). Keeping every error trace at about today's volume
  takes a decision at the tail, in the collector: the SDK samples 100 %, a `loadbalancing` exporter tier routes spans
  by trace ID, and a `tail_sampling` tier keeps a trace with status ERROR, one slower than its SLO bound, and 10 % of
  the rest. The tail-sampling processor is Beta and holds traces in memory for `decision_wait` (30 s) up to
  `num_traces` (50,000), so traces show about 30 s late and every span crosses the network.
- **A 5xx answer may not mark its span ERROR.** Micrometer marks a span ERROR only when an exception is recorded on
  the observation (`TracingObservationHandler`), so a tail policy on ERROR misses a 5xx answered without one. nuna's
  own child spans end ERROR on a failed call (`docs/observability/spans.md`, line 40), but the request's server span
  is Spring's, and nuna's 5xx answers are built by exception handlers (`ErrorResponses`, `@ExceptionHandler`) or
  directly (`AuditUnavailableResponses`' 503). Whether those server spans end ERROR is not verified.

The sampler itself is right for now: the spec deprecates `TraceIdRatioBased` but forbids SDKs to change it before
2027-01-01, and Boot 4.1 and 4.2 offer no property for its successor. Metrics and logs are never sampled, so the audit
records never depend on it.

**Evidence.** The observability-audit research report of 2026-11-24 (in that agent's home,
`research/reports/Nuna observability and audit grounding.md`), "Spring's HTTP conventions and 10% head sampling
trail OpenTelemetry": the tail-sampling processor
(https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/processor/tailsamplingprocessor/README.md),
the load-balancing exporter
(https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/loadbalancingexporter/README.md)
and Micrometer's handler
(https://github.com/micrometer-metrics/tracing/blob/main/micrometer-tracing/src/main/java/io/micrometer/tracing/handler/TracingObservationHandler.java).

**What to build.**

- **At home:** a test that every 5xx answer's server span has status ERROR, those built without a throw included,
  with the exception's class and never its message, as spans.md requires. Where it does not, set it in one place:
  the filter that times each request already knows the status (`RequestMetrics`). Then a tail policy on ERROR alone
  keeps them, whatever the collector; an OTTL condition on the status code (`http.response.status_code >= 500`, or
  Spring's `status` tag) is the fallback. A bite in `bites/OBS-265/`.
- **At work, with item 43:** if work's collector can run the two tiers, set them up, put the deployment's
  `telemetry.sampling` at 1.0, and say in `docs/observability/README.md`, "Sampling", which mode a deployment runs and
  which traces each keeps. If work's platform has no tail-sampling tier, keep 10 % at the head and say there that most
  error traces are not kept, and that the request ID in the log line and the audit record is the way back to a failed
  call.

**Done when** the test and its bite are in and the doc says which traces each mode keeps; at work, a forced 5xx shows
as a trace in work's Grafana under tail sampling, or the doc says why the deployment keeps head sampling.

**Progress, 2026-11-26 (the home part landed; the work part is left).** Verified first: with no change, a 5xx built by
an `@ExceptionHandler` (`ErrorResponses`), by a route or by a filter without a throw left the server span's status
UNSET, and a failure that escaped to the container marked it ERROR with its message on the span. `RequestMetrics` now
marks the server span ERROR for every 5xx on an Iceberg REST route, recording `AnswerFailed`, which names the status and
the exception's class or the body's error type, never a message; it rethrows an escaped failure as an `AnswerFailed`
too, after logging it in full. A 4xx is left unset. It takes the `ObservationRegistry` from `MetricsWiring` (added to
Touches). `ServerSpanStatusTest` (untagged, Java only) covers each kind; bites `bites/OBS-265/five-hundred-not-error`
and `escaped-failure-message-on-span`; the three `bites/OBS-39/requests-*` bites recorded again, same breaks.
spans.md, "The server span's status", and README.md, "Sampling", say which traces head and tail sampling keep. Left,
at work with item 43: the collector's `loadbalancing` and `tail_sampling` tiers, `telemetry.sampling` in the charts'
values for the deployment, README.md saying which mode work runs, and a forced 5xx seen as a trace in work's Grafana
(or the doc saying why the deployment keeps head sampling). The item stays open for that.

**Progress, 2026-11-26 (secret-scanning): the tail-sampling tier, built and checked in the demo's configuration.** The
demo's collector (`demo/k8s/metrics.yaml`) now runs `tail_sampling` in its traces pipeline: every trace with status
ERROR, every one over 300 ms (the commit SLO's bound) and 10 % of the rest, with `decision_wait` 10 s and
`num_traces` 5,000; its image is the contrib build (`demo/demo.sh`, `OTEL_COLLECTOR_IMAGE`), as the core build has no
`tail_sampling`, and its memory limit is 256 MiB (memory_limiter 200). Checked by running that configuration in the
contrib collector 0.166.0 with a file exporter in place of Tempo and sending 202 single-span traces: the ERROR one and
the 500 ms one kept, 19 of the 200 others. `otelcol validate` passed a misspelt policy type, so it proves nothing about
the policies. README.md, "Sampling", points work at it. Not checked live: the demo's metrics switch was off and is
left off, and forcing a 5xx on an Iceberg route in the shared demo means breaking its database; the catalog's ERROR
marking is `ServerSpanStatusTest`'s. Left, at work with item 43: the `loadbalancing` tier in front if work runs more
than one collector, `telemetry.sampling` at 1.0 in work's values, README.md saying which mode work runs, and a forced
5xx seen in work's Grafana.

**At work, 2026-11-27 (D54).** Paul: "can we mark it as for work. Not for here". The home part is built and the demo's
tail-sampling tier checked; what is left is work's collector, its chart values, README.md's line for work's mode and a
forced 5xx in work's Grafana, all with item 43. No agent takes it at home.

### 317. Internal documents disagree with the code

- Added: 2026-11-27 by local_e8ac51d8-1582-4b16-bc65-5fa26bbd5773
- Touches: demo/README.md, deploy/README.md, deploy/helm/, deploy/local/, docs/, catalog-maintenance/README.md, catalog-tests-engines/README.md, build-plan.md
- Effort: M, many small corrections across the internal Markdown, each checked against the code

Found while writing the documentation site (backlog 301), 2026-11-27; Paul asked for each to be filed.

The documentation site was written from the code; these internal documents say otherwise:
- `demo/README.md`: a "Snapshots" tab (now History); 3.8 GB (4,210 MiB) and 1.5 GB (1,472 MiB); the pinned list
  misses metrics, and metrics are tail-sampled, not sampled at 1; ana's access from `print_access` is not "select on
  both"; sign-out sends `id_token_hint` (`auth.js`), not "no ID token".
- `deploy/README.md` and the chart's `values.yaml` comments miss the settings classes; `deploy/README.md` misses the
  `pg_monitor` and `VIEWCLUSTERSETTING` grants; `deploy/local` values comments still name Docker Desktop.
- `docs/observability/health-page.md` uses `#p=health` (now `#p=service`); `docs/observability/dashboards-and-alerts.md`
  gives old counts (29 alert rules); `catalog-maintenance/README.md` says "Health tab"; `docs/refs.md` uses old
  console words; `docs/install-at-work.md`'s image table is out of date; `build-plan.md` M8's SLO lines and
  `catalog-tests-engines/README.md` are stale.

What done looks like: each corrected from the code. The public documentation is `docs/site/`; where an internal
document only repeats it, it may point there instead.

### 323. Platform operators list the standing DR freezes they may lift

- Added: 2026-11-27 by local_6686d222-bf16-416b-a4cb-e82501941da2
- Decided: 2026-11-27, D55
- Touches: catalog-server/src/main/java/nuna/catalog/server/governance/, catalog-server/src/main/resources/nuna/ui/,
- catalog-server/src/test/java/nuna/catalog/server/governance/, docs/site/
- Effort: M, a read route or a change to one, its authorization and audit, and the Governance page for an operator

A platform operator may place a DR freeze and lift one (`requireOperatorOn`), but may not list them:
`listHoldsAndFreezes` needs `describe` (`GovernanceRoutes.java:127`), which an operator holds nowhere. So a freeze he
did not place himself, or whose path he forgot, stays in force unseen, and a freeze refuses every commit beneath it.
The 27 Nov console review (item 3) asked; Atelier recommended yes, for DR freezes only.

Paul's decision, 2026-11-27 (D55): yes.

- A platform operator reads the standing DR freezes in a catalog: each one's path, reason, who placed it and when.
- Legal holds stay behind `describe` (or `manage-tags`): their reasons are legal matter.
- Before it is built, catalog-security confirms that listing the frozen paths to an operator is acceptable (he can
  already type any path to place or lift one), and says what the read records in the audit trail.
- The console's Governance page shows an operator the freezes he may lift, with Lift beside each.

Done when the route lets an operator list the freezes and refuses him the holds, tested and audited, and the
Governance page lists them for him.

### 332. Sign-in screen: the identity provider's name in words, and the developer token box folded

- Added: 2026-11-27 by local_a0cd9c35-a22f-4dde-8b48-c0c950e2d9e0
- Touches: catalog-server/src/main/java/nuna/catalog/server/ui/, catalog-server/src/main/resources/nuna/ui/app.js, catalog-server/src/main/resources/nuna/ui/auth.js, catalog-server/src/main/resources/nuna/ui/style.css, catalog-server/src/test/, demo/k8s/catalog-values.yaml, docs/site/console/sign-in.html, docs/site/configure/server.html, docs/site/img/shots/
- Effort: M, the code is small, but the docs screenshot needs the demo rebuilt

From Atelier's brand review, made at work on 2026-11-27 (at work: item 011, "A brand slot for the console and
docs", its part 3, with an offline mock-up Paul approved there). Atelier, here, checked it against main at
a5cdf6ef and wants these two fixes everywhere, whether or not an install ever sets a brand pack (item 333). Paul
asked on 2026-11-27 to add both items.

What is wrong:

- The sign-in button reads "Sign in with " + the issuer's host and port (`signInPage` in `app.js`), so the first
  thing a room reads is a host name. The host also shows in "Going to {host}…" and in both sign-out messages
  (`auth.js`, about lines 184 and 188).
- Under "development only", the bearer-token box, its words and "Use this token" sit open beside the sign-in button
  on the demo, which sets `dev-token-login: true` (`demo/k8s/catalog-values.yaml`).

The change:

- A setting for the identity provider's name in words (suggested `nuna.ui.oidc.name`, served in `config.json`), for
  example "the demo's Keycloak". When set, it fills all four places the host shows today; unset, they keep the host,
  so `ConsoleSignInTest`'s checks for "Sign in with keycloak:8080" still pass.
- When an identity provider is set, the token box, its words and its button fold into a closed `<details>` whose
  summary reads "Developer sign-in". With no identity provider (`deploy/local`), the box is the only way in and stays
  open.
- The demo sets the name and keeps `dev-token-login: true`: `demo.sh` prints tokens for presenters and agents ("Sign
  in, or paste one of these tokens"), so switching it off would break them. `ConsoleBrowser.signIn` puts the token in
  session storage, so no browser test depends on the box being open.

Done when, with the name set, the button, "Going to…" and both sign-out messages use it, and unset they show the
host as today; with an identity provider set the token box sits closed under "Developer sign-in", and with none it
stays open; the demo sets the name; a `config.json` test covers the setting; and `docs/site/console/sign-in.html`
(about lines 40 to 61), the server settings page and the sign-in screenshots are updated.

### 333. A brand slot for the console and the docs site: Ice line by default, an install's pack by settings

- Added: 2026-11-27 by local_a0cd9c35-a22f-4dde-8b48-c0c950e2d9e0
- Touches: catalog-server/src/main/java/nuna/catalog/server/ui/, catalog-server/src/main/resources/nuna/ui/, catalog-server/src/test/, docs/site/, deploy/helm/nuna-catalog/
- Effort: L, 2 to 5 days; it waits on nothing, the real pack is work's job

From Atelier's brand review, made at work on 2026-11-27 (at work: item 011, "A brand slot for the console and
docs", with an offline mock-up of the console and sign-in screen in a work pack beside today's Ice line; Paul
approved it there). Paul, 2026-11-27: "The current branding is great. But maybe at work we need new branding", and
"I like the current style. I only want to turn it on at work." So Ice line stays the default everywhere, and only an
install that sets the brand settings looks different. The work pack (its fonts, word mark and icon) is supplied by
the work install and never enters this repository; no firm's name goes in the code, the tests or this item.

Atelier, here, checked the work item against main at a5cdf6ef. The idea is right (a switch, not a redesign; the
tokens in `style.css` make it cheap), but its mechanics do not fit this code:

1. The header mark is fixed markup in `index.html` (the inline peak, "nuna", `aria-label="nuna, overview"`), not
   drawn by `brand()`, which draws only the sign-in screen's marks. The slot needs both.
2. The docs site cannot read `config.json`: `DOCS_POLICY` is `default-src 'none'` with no `connect-src`.
3. A stylesheet added by script after `config.json` answers paints Ice line first, then flips.
4. `UiWiring.etag` hashes each file once for the server's life: right for files in the jar, wrong for a mounted
   ConfigMap that changes under a running pod.
5. The `PUBLIC` pattern allows `.woff2` fonts only; any other font type would be refused silently.
6. "nuna" is hard-coded beyond the work item's list: both sign-out messages (`auth.js`), `index.html`'s title and
   aria-label, and `app.js` at about lines 346, 348, 383, 390, 736, 745 and 801.
7. Dark mode: Ice line's dark selectors are more specific than a pack's plain `:root`, so a light-only pack in Dark
   would show Ice line's dark colours under the pack's fonts and mark, a half-borrowed look.
8. Header widths (items 231 and 297): `.nc-brand` is `flex: 0 1 180px` above 760 px, gives way first, and is hidden
   at 520 px and below, sized for the peak and "nuna". A word mark must never be squashed or cropped.

The design (Atelier):

- Settings (names suggested): `nuna.ui.brand.dir`, a folder on the server holding the pack; the product name in
  words (default "nuna"); the header mark's accessible name; whether the product name sits beside the mark; whether
  the pack is light-only. Unset, nothing changes.
- `index.html` and every docs page link a brand stylesheet at a fixed path that always exists. The jar and
  `docs/site/assets` ship it empty; when `nuna.ui.brand.dir` is set the server serves the install's file there, and
  the favicon the same way. No flash, no 404s, no change to the Content-Security-Policy, and a docs copy opened from
  disk stays Ice line. Files from the brand folder are revalidated by content, not hashed once.
- The pack sets tokens only: custom properties and `@font-face`, no rules aimed at the console's own selectors, which
  change weekly. Add the few role tokens Ice line itself then uses: header band background, ink and field colour, the
  rail's current-page background and ink, the mark's height, and one for the "requested" pill (`requests.js`, about
  line 72), the one status drawn in the accent colour. Document that list as the pack's contract.
- A light-only pack pins the light theme and hides both theme switches (header and menu); on the docs site the
  pack's stylesheet forces light itself.
- The mark gets a fixed box: fixed height, `flex: none`, at most 20 × 120 px. The product name replaces every
  hard-coded "nuna" in titles and messages; when it is out of the header, each page's eyebrow starts with it.
- A neutral test pack in `catalog-server/src/test`, with an invented name and colours and a mark at the maximum size,
  so the fast build and the header harness exercise the slot.
- A `brandConfigMap` value in the Helm chart, following `storageCaConfigMap` (a ConfigMap's 1 MiB fits a couple of
  woff2 fonts), and a docs page for the settings and the token contract.
- Item 332 does the sign-in words and the token box; this item does not repeat them.

Open at work, not here: does the firm's design system have dark-mode tokens? If it does, the work pack covers dark and
the light-only pin goes unused there.

Done when, with nothing set, the console and docs render as today and the existing tests and header harness pass
unchanged; with the test pack set, they show its colours, fonts, mark, favicon and product name from first paint,
with no console errors, and the header fits at every tier with the menus open at phone widths; a light-only pack
pins light and hides both switches; a changed pack file is served fresh without a restart; and a docs copy opened
from disk stays Ice line.

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
| [28](backlog/28.md) | DR replication-watermark verifier (v1.x) | done | 2026-11-26 |
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
| [89](backlog/89.md) | A compaction Job against a store behind a private CA, end to end | done | 2026-11-26 |
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
| [104](backlog/104.md) | Reads on a ref: the catalog@ref prefix, config, loads and listings (item 26) | done | 2026-11-25 |
| [105](backlog/105.md) | Commits on a branch write its own pointer (item 26) | done | 2026-11-25 |
| [106](backlog/106.md) | Ref management routes: create, list, renew and delete, with P11's limits and expiry (item 26) | done | 2026-11-25 |
| [107](backlog/107.md) | Merge a branch into main, per table, in one bounded transaction (item 26) | done | 2026-11-25 |
| [108](backlog/108.md) | Cleanup protects every ref, and the worker warns of and ends expired branches (item 26) | done | 2026-11-25 |
| [110](backlog/110.md) | Demo: a catalog tag, a branch with Trino on it, and a merge (item 26) | done | 2026-11-23 |
| [111](backlog/111.md) | After a regional failover, report every ref that names a file the DR store lacks (item 26, question 3) | done | 2026-11-25 |
| [113](backlog/113.md) | Grants that name a branch (D33) | done | 2026-11-25 |
| [114](backlog/114.md) | Run a table's maintenance now, from the console, outside the cleanup window too | done | 2026-11-22 |
| [115](backlog/115.md) | Row filters and column masks stored in the catalog, served to Trino (D20) | done | 2026-11-24 |
| [116](backlog/116.md) | AuditTablesTest's server refuses to start: its test PostgreSQL fails the startup self-check | done | 2026-11-21 |
| [117](backlog/117.md) | RollingUpgradeTest: the new release refuses catalog m8, bound to another storage profile id by the old one | done | 2026-11-22 |
| [119](backlog/119.md) | Atelier, a UX expert agent (agent-forge home) | done | 2026-11-21 |
| [120](backlog/120.md) | Atelier moves to user scope | done | 2026-11-21 |
| [121](backlog/121.md) | ConsoleBrowserTest could not start its browser session | done | 2026-11-22 |
| [122](backlog/122.md) | The overnight heavy build stops at its first failing module | done | 2026-11-22 |
| [123](backlog/123.md) | Heavy build: Docker-backed services stall 30 s while two heavy modules share Docker | dropped | 2026-11-25 |
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
| [135](backlog/135.md) | Windows pages the Docker VM out during the nightly: free the host's memory (Paul decides how) | dropped | 2026-11-22 |
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
| [151](backlog/151.md) | Brokerage world 2: trading and custody, the OMS in SingleStore and the stock record and CNS in CockroachDB | done | 2026-11-26 |
| [152](backlog/152.md) | Brokerage world 3: party, money movement and compliance, the CDC sources, the governance moments and live mode | done | 2026-11-27 |
| [153](backlog/153.md) | Brokerage world 4: the firm's books and reporting, the GL, margin, the reserve, tax, statements and CAT | done | 2026-11-27 |
| [154](backlog/154.md) | Brokerage world 5: volume and upkeep, streaming tables, maintenance policies and the scale setting | done | 2026-11-27 |
| [155](backlog/155.md) | The demo kit: templates and booking schemes for the events the brokerage world needs beyond ledger kit v14 (D48) | done | 2026-11-26 |
| [156](backlog/156.md) | Remove a table from refs: an audited route, gated by manage-tags, so a dropped table's data can leave tags (item 26, finding 6) | done | 2026-11-25 |
| [157](backlog/157.md) | A namespace drop is refused while a ref's cut names an object in it (item 26, security finding 7) | done | 2026-11-25 |
| [158](backlog/158.md) | A ref made from main while a table and then its namespace are dropped can name a table whose namespace is gone (item 157's race) | done | 2026-11-25 |
| [159](backlog/159.md) | Demo: the seed's sample grant goes to a group the grant admin is not in | done | 2026-11-23 |
| [160](backlog/160.md) | Heavy build: the audit gate and HealthRoutesTest miss item 114's maintenance requests | done | 2026-11-23 |
| [161](backlog/161.md) | Console: the Metadata log tab fails on a create's, register's or revive's journal entry | done | 2026-11-23 |
| [162](backlog/162.md) | ConsoleHealthTest reads the estate legend's count and words on two lines | done | 2026-11-23 |
| [163](backlog/163.md) | ConsoleSettingsBrowserTest expects zstd as a default, but iceberg-core 1.12 sets it on every new table | done | 2026-11-23 |
| [164](backlog/164.md) | One failing heavy test skips every bite, and one hung test can hold a leg until 06:30 | done | 2026-11-23 |
| [165](backlog/165.md) | An agent for each module whose tests fail in the overnight run, while the run goes on | done | 2026-11-23 |
| [166](backlog/166.md) | The overnight run tests in a worktree, not a clone | done | 2026-11-23 |
| [168](backlog/168.md) | Refs: each table's version at a ref's cut, and the cut's time for a ref made from a branch, from the server (item 109's gap 9) | done | 2026-11-25 |
| [170](backlog/170.md) | Console: a table dropped on main or made after a ref's point, read through the ref or on main, says when, by whom and which refs hold it (design 6.4's edge states) | done | 2026-11-25 |
| [171](backlog/171.md) | A hold placed above an object while a purge claim or a removal from refs runs: lock the held levels before reading holds (item 156's review, low) | done | 2026-11-25 |
| [172](backlog/172.md) | Console: export a table's metadata and log as a catalog ref reads it (item 109's review) | done | 2026-11-25 |
| [173](backlog/173.md) | Console: the tree carries the ref a page reads, listing each level through catalog@ref (item 109's departure 4) | done | 2026-11-23 |
| [174](backlog/174.md) | Demo: every UI signs in through Keycloak, Silo's console and Keycloak's admin console included | done | 2026-11-23 |
| [175](backlog/175.md) | Brokerage world: the bootstrap fixes the world's settings on a brokerage namespace the change feed made | done | 2026-11-23 |
| [176](backlog/176.md) | The fast build re-checks every bite each time, and agents run it again and again before landing | done | 2026-11-23 |
| [177](backlog/177.md) | Register and revive refuse a branch's metadata file on main (item 107's merge-base review, low) | done | 2026-11-25 |
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
| [197](backlog/197.md) | After audit goes to logs (195): a table's Audit tab reads the audit table, not the topic; the console says which rows came from the log; Health's publish figures are the Kafka copy's | done | 2026-11-25 |
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
| [220](backlog/220.md) | Console: findings from Atelier's re-walk after items 212 to 217 | done | 2026-11-25 |
| [221](backlog/221.md) | Console: branches and tags have a place in the rail, and making a branch is found there (Paul, 2026-11-23) | done | 2026-11-24 |
| [222](backlog/222.md) | Console: Compact a table now offers the catalog's tables in a dropdown, tonight's queue first (Paul, 2026-11-23) | done | 2026-11-24 |
| [223](backlog/223.md) | KafkaDownTest still expects a scan to fail while Kafka is down, which 195 changed: a Kafka outage blocks nothing | done | 2026-11-24 |
| [224](backlog/224.md) | Heavy build 2026-11-24: eight catalog-server tests disagree with items 193, 195, 206, 207 and 214 | done | 2026-11-24 |
| [225](backlog/225.md) | Console: Service's Most versions behind opens a table the operator may not read (Paul, 2026-11-23) | done | 2026-11-24 |
| [226](backlog/226.md) | Bite metadata-log-ref-page-by-version runs the server out of heap instead of failing its test, and run.sh calls it NO BITE | done | 2026-11-25 |
| [227](backlog/227.md) | Bite mongo-cut-read-outside-the-snapshot does not bite: everyTableInACutHasItsNamespace passes with the cut read outside the snapshot | done | 2026-11-25 |
| [228](backlog/228.md) | Bite store-skips-postgresql-steps is SETUP again: the server no longer starts on PostgreSQL migrated with the shared steps only | done | 2026-11-25 |
| [229](backlog/229.md) | The console header lists every group by name and runs off the screen | done | 2026-11-24 |
| [230](backlog/230.md) | The demo runs no maintenance worker, so Overview and Maintenance are empty | done | 2026-11-24 |
| [231](backlog/231.md) | Console: between 761 and 1,016 px, and 1,241 and 1,297 px, the header is wider than the window (Atelier decides) | done | 2026-11-26 |
| [232](backlog/232.md) | Console: a frozen table's page is wider than the window at 1440 px (its frozen pill runs past the edge) | done | 2026-11-26 |
| [233](backlog/233.md) | The demo's metrics switch: an OTel collector, Prometheus, Tempo and Grafana with nuna's dashboards | done | 2026-11-25 |
| [234](backlog/234.md) | Demo SingleStore refuses order writes once its pipelines' extractors fill the table-memory cap | done | 2026-11-24 |
| [236](backlog/236.md) | Presenter, who holds every duty, lands on Overview | done | 2026-11-24 |
| [237](backlog/237.md) | The demo keeps its data across down and up: volumes for PostgreSQL, Silo, Kafka and SingleStore; demo.sh wipe | done | 2026-11-24 |
| [238](backlog/238.md) | The brokerage generator routes by the demo switches: orders through Kafka while SingleStore is off, the Kafka feeds through SingleStore and a change feed per table while Kafka is off | done | 2026-11-26 |
| [239](backlog/239.md) | Governance flag only on tables: a flag above a table shows but is not enforced | done | 2026-11-24 |
| [240](backlog/240.md) | Column masks fail open when a column is renamed, dropped or misspelled | done | 2026-11-24 |
| [241](backlog/241.md) | Console: a flagged table says why it is read through Trino; the steward's flag button; audit words for rule changes | done | 2026-11-24 |
| [242](backlog/242.md) | Routes the console's row and column rules need: a flagged table's schema, a reader's own view, rule counts and flagged tables | done | 2026-11-24 |
| [243](backlog/243.md) | Console: the Row filters and column masks panel and its dialog | done | 2026-11-24 |
| [244](backlog/244.md) | Row and column rules: check expressions when set, and If-Match on set | done | 2026-11-24 |
| [245](backlog/245.md) | Console: Grants as a rail item for those who manage grants; a table or view opens on Access for someone who may manage its grants but not read it | done | 2026-11-24 |
| [246](backlog/246.md) | Grants: a preview route that says whether a grant would be admitted, already held or refused, and why, writing nothing | done | 2026-11-24 |
| [247](backlog/247.md) | Grants: a route listing the groups that hold a grant in a catalog, with their duties, and the configured grant-admin and operator groups | done | 2026-11-24 |
| [248](backlog/248.md) | Console: one Grant access dialog for the Grants page and every Access tab, with a group picker, privileges in plain words, a path search and a preview line | done | 2026-11-24 |
| [249](backlog/249.md) | Demo: say that presenter and gary cannot grant to their own groups, and which group to grant to when showing a grant | done | 2026-11-24 |
| [250](backlog/250.md) | Worker: report what storage it reaches at each scan: its profiles' prefixes, whether the store let it list each, its deletes done and refused; never a key | done | 2026-11-24 |
| [251](backlog/251.md) | Maintenance readiness: a route saying, step by step, whether the worker can maintain a catalog | done | 2026-11-24 |
| [252](backlog/252.md) | Console: "What the worker needs to maintain <catalog>" on Maintenance, and a page head that says why nothing happens | done | 2026-11-24 |
| [253](backlog/253.md) | Operators: a route giving, per catalog storage profile, the worker's coverage and exactly what to apply | done | 2026-11-24 |
| [254](backlog/254.md) | Console: "What to set up" for operators, from the Maintenance steps and a Worker storage panel on Service | done | 2026-11-24 |
| [255](backlog/255.md) | Demo: the catalog's profile names its maintenance principal, and demo.sh applies the worker policy the catalog generates | done | 2026-11-24 |
| [256](backlog/256.md) | Worker: read its storage prefixes from the catalog at each scan, keeping one identity per store in its settings (a change to D30) | done | 2026-11-24 |
| [257](backlog/257.md) | Atelier's wording review of the row and column rules in the console | done | 2026-11-24 |
| [258](backlog/258.md) | Grants: refuse write on a view, which gives only select there yet counts as a data duty | done | 2026-11-25 |
| [259](backlog/259.md) | Run DeletePolicyAwsTest on AWS: does the bucket policy alone let a worker role with no S3 policy of its own list and delete? | done | 2026-11-26 |
| [260](backlog/260.md) | Console: the Grants page lists the catalog's groups with their duties when no group is named | done | 2026-11-25 |
| [261](backlog/261.md) | Alerts: availability SLOs beside the latency SLOs, and absence alerts, so a catalog answering 500 or gone silent pages someone | done | 2026-11-25 |
| [262](backlog/262.md) | Audit archive: records written during a Kafka outage never reach it, and each chain's head is anchored by the archiver itself (Paul decides what D23 promises) | done | 2026-11-25 |
| [263](backlog/263.md) | Audit table writer: a copy arriving more than 15 minutes after the first is written again, and records answered before their commit die with the writer | done | 2026-11-26 |
| [264](backlog/264.md) | D23 cites NYDFS 500.6 for five years, but 500.6(b) asks three for audit trails (Paul decides the citation) | done | 2026-11-24 |
| [266](backlog/266.md) | Overview says a catalog's worker has not reported while the worker reports every scan | done | 2026-11-26 |
| [267](backlog/267.md) | demo.sh's Kafka Connect calls retry a 502, 503, 504 or 409 instead of stopping up | done | 2026-11-25 |
| [268](backlog/268.md) | Schema tests expect version 15 after migrations V16 and V17 (items 244, 250): SchemaAtStartupTest and RollingUpgradeTest | done | 2026-11-25 |
| [269](backlog/269.md) | MutatingRoutesAuditTest: the OPA routes (item 115) have no case | done | 2026-11-25 |
| [270](backlog/270.md) | SecurityGateTest: the grant and engine routes' audit lines never reach the log capture | done | 2026-11-25 |
| [271](backlog/271.md) | ConsoleBrowserTest: the reading slot's main-only control is not shown while reading a branch | done | 2026-11-25 |
| [272](backlog/272.md) | Audit feed: place a row in its server run's archive chain, and show a running server's tail before its run ends | done | 2026-11-25 |
| [273](backlog/273.md) | Demo: presenter may do absolutely everything | done | 2026-11-25 |
| [274](backlog/274.md) | demo.sh up builds from one pinned commit, so a landing during the run cannot stop it or mix commits | done | 2026-11-25 |
| [275](backlog/275.md) | The worker's first scan after a restart can't get a token, and readiness then says storage is missing | done | 2026-11-25 |
| [276](backlog/276.md) | Audit topic: each record's Kafka copy carries its run and number, as the log's line does | done | 2026-11-25 |
| [277](backlog/277.md) | The console says no worker has reported when its newest scan reported no storage | done | 2026-11-25 |
| [278](backlog/278.md) | A transaction on a branch holds a table it leaves unchanged and has not committed to on the branch (item 105's Left) | done | 2026-11-26 |
| [279](backlog/279.md) | Console: take a table out of refs, item 156's route, from the table page (item 156's Left) | done | 2026-11-25 |
| [280](backlog/280.md) | Credential-shaped literals in tests, docs and the Job's trust store block a push to work's GitHub | done | 2026-11-25 |
| [281](backlog/281.md) | The demo's catalog signs in to its PostgreSQL with a client certificate, and the engine matrix with it | done | 2026-11-25 |
| [282](backlog/282.md) | The demo's and the laptop runner's fixed passwords made once per machine, people's included | done | 2026-11-26 |
| [283](backlog/283.md) | Kafka Connect's REST API is published on 8186 with no sign-in | done | 2026-11-26 |
| [284](backlog/284.md) | The audit table writer stops for good when its table is dropped under it | done | 2026-11-25 |
| [285](backlog/285.md) | Paul decides: take the old test credentials out of git history, or push snapshots to work's GitHub | done | 2026-11-26 |
| [286](backlog/286.md) | demo.sh takes a deployment for gone only when kubectl says NotFound, so gen stop never stops Kafka under a running generator | done | 2026-11-25 |
| [287](backlog/287.md) | Move from Docker Desktop to Podman: kind cluster nuna for the demo, the local install and the compaction and engine tests | done | 2026-11-26 |
| [288](backlog/288.md) | Oracle reads every branch grant's ref as "?": V20's any_grant view lost its regex back-references | done | 2026-11-26 |
| [289](backlog/289.md) | Gate heavy failures: the audit table writer needs modify, the archive's written times, SecurityGateTest's poisoned outbox | done | 2026-11-26 |
| [290](backlog/290.md) | Failover and fault tests fail on Podman: a POST on a pooled pipe connection Podman closed | done | 2026-11-26 |
| [291](backlog/291.md) | Heavy tests on Podman: a Docker call made after 10 s without one fails, since Podman's API closes idle connections and Testcontainers reuses them (TwoIssuersTest) | done | 2026-11-26 |
| [292](backlog/292.md) | GitHub's push protection refuses the openssl pkcs8 command's argument order | done | 2026-11-26 |
| [293](backlog/293.md) | The CDC leg and CloudBeaver keep their data on volumes: cdc stop and down pause the leg, the next cdc carries on, cdc reset starts it anew | done | 2026-11-26 |
| [294](backlog/294.md) | kind's node caps every container at 307 tasks: SingleStore's pipelines, and the JVMs, run out of threads | done | 2026-11-26 |
| [295](backlog/295.md) | AuditTablesTest's ForbiddenException for updateTable: confirm the cause and guard the fix with a bite | done | 2026-11-26 |
| [296](backlog/296.md) | Console browser tests: the guard against the nuna-demo stack sees no demo since it moved to kind | done | 2026-11-26 |
| [297](backlog/297.md) | Console: on a phone, a branch carried onto a page that shows main only makes the header 630 px wide (Atelier decides) | done | 2026-11-26 |
| [298](backlog/298.md) | Change feeds stop once maintenance compacts their copy: the feed reads where to resume only from the copy's current snapshot | done | 2026-11-26 |
| [299](backlog/299.md) | Console prototype: bring its header up to the built console (the Reading slot, items 231 and 297) | done | 2026-11-26 |
| [300](backlog/300.md) | Console: on a phone the Reading menu may run past the window and main may end in an ellipsis (from 299) | done | 2026-11-26 |
| [301](backlog/301.md) | A documentation site: every console page and command, installation, configuration, API and operations, as static web pages | done | 2026-11-27 |
| [302](backlog/302.md) | Heavy: SingleStore's dev container never becomes healthy, so ChangeFeedTest does not start | done | 2026-11-27 |
| [303](backlog/303.md) | Nightly: the DR drill's archive does not prove every acknowledged tip within 3 minutes (DrDrillTest) | done | 2026-11-27 |
| [304](backlog/304.md) | Bite connections-reused-at-any-age judged SETUP: the failure it causes is a Docker call, read as fixtures | done | 2026-11-27 |
| [305](backlog/305.md) | Bite console-goto-lookup-overdrawn does not bite: the search coming back late never overdraws the answer in the test | done | 2026-11-27 |
| [306](backlog/306.md) | Bite console-readiness-grant-for-anyone-with-names names a test method renamed in b6c84ae9 | done | 2026-11-27 |
| [307](backlog/307.md) | The console links to the documentation, served at /docs/ from the server | done | 2026-11-27 |
| [308](backlog/308.md) | Brokerage world: trust the demo's writers and flag tax ids, birth dates, addresses and bank account numbers (D20) | done | 2026-11-27 |
| [309](backlog/309.md) | Purging a table a ref still names answers 500, not a refusal | done | 2026-11-27 |
| [310](backlog/310.md) | OPA's row-filter and column-mask answers fail when the request names no Trino catalog | done | 2026-11-27 |
| [311](backlog/311.md) | GET /management/v1/maintenance/nights with a non-numeric jobs or days answers 500, not 400 | done | 2026-11-27 |
| [312](backlog/312.md) | The console says a grants export is audited, but the export writes no audit record | done | 2026-11-27 |
| [313](backlog/313.md) | Server error messages sent to clients end in internal tags such as (D19) and (D20, backlog 240) | done | 2026-11-27 |
| [314](backlog/314.md) | Grafana dashboards and demo.sh messages name backlog items and D rows | done | 2026-11-27 |
| [315](backlog/315.md) | Console: small faults the documentation writers found | done | 2026-11-27 |
| [316](backlog/316.md) | Demo: the audit tables writer makes no commit, and demo.sh sql opens a database the feed setup drops | done | 2026-11-27 |
| [318](backlog/318.md) | Brokerage world: fixes from the live check of items 151 to 155 and 308 | done | 2026-11-27 |
| [319](backlog/319.md) | Console: a link to Grafana's charts on the Service page, when a Grafana URL is configured | done | 2026-11-27 |
| [320](backlog/320.md) | demo.sh's aml_cases hold check fails at random under pipefail | done | 2026-11-27 |
| [321](backlog/321.md) | The brokerage world runs 24x7: customers act at any hour, every day, and trading follows Nasdaq's 24x5 session | done | 2026-11-27 |
| [322](backlog/322.md) | Console quick wins from the 27 Nov drop's review: policy values in words, the Grafana link's own icon, the Service page's words, times in tables, the health figures' gap | done | 2026-11-27 |
| [324](backlog/324.md) | Console: a table closed until classified says so on its page, in the tree and in What you may do, and leads a steward to classify it | done | 2026-11-27 |
| [325](backlog/325.md) | Console: stop asking the catalog for what the caller's own grants show they cannot have, so browsing writes no audited refusals | done | 2026-11-27 |
| [326](backlog/326.md) | Console: measure the grids against the content column, not the window, so tables beside the tree are not crushed | done | 2026-11-27 |
| [327](backlog/327.md) | Maintenance: name the table each failed job of a scan failed on, and show it on Service and Maintenance | done | 2026-11-27 |
| [328](backlog/328.md) | Console: make the audit feed readable when one engine polls | done | 2026-11-27 |
| [329](backlog/329.md) | Console: the Access tab says each governance fact once and lists only the grants that reach the object | done | 2026-11-27 |
| [330](backlog/330.md) | Console: History shows data changes, not only the worker's expiry commits | done | 2026-11-27 |
| [331](backlog/331.md) | Console: the smaller polish from the 27 Nov review | done | 2026-11-27 |
