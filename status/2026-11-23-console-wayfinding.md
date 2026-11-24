# 2026-11-23, console wayfinding: Atelier's quick wins, batch 1 (items 183, 173, 185, 184)

From Atelier's live review (`docs/design/console/review-2026-11-23/`). One worktree holds every quick win's claim,
since they share `app.js`, `style.css` and `ConsoleBrowserTest`; `backlog.sh` now lets a session hold overlapping
claims of its own (`test-backlog.sh` checks it).

- 183: sign-in opens the catalog used last, else the one with the most readable tables; an empty one names the other.
- 173: the tree reads and links through the ref a page reads; the rail's Catalog link keeps it.
- 185: Go to a path opens a full dotted path it has not listed, and its empty answer says what it searched.
- 184: the catalog list says what the caller may do; the rail, tabs and buttons follow it, and each role lands
  where its work is.

Checked: `CatalogListTest` (11 tests, fast) and the fast build at landing. Live in the nuna-demo stack as `ana`,
through `demo-cdp-swap.mjs` (the worktree's `ui/` served to the demo page): 183, 173 and 185 as each item says. Not
checked: the new and changed `ConsoleBrowserTest`, `ConsoleLabelsBrowserTest` and `ConsoleHealthBrowserTest` cases
are heavy and run overnight (Docker is shared with the demo, and Windows had 0.5 GB free); 184 live needs the demo
rebuilt with this server.

## Batch 2 (items 192, 186, 187, 190)

- 192: the rail's Health is Service and a table's Health tab Maintenance; old `p=health` and `tab=health` links
  still open them; the rail keeps its labels on the Catalog page.
- 186: a table's Overview says who made its current snapshot, Snapshots has a By column (each paired with its commit
  through the metadata log's `add-snapshot` updates, no server change), and Contents reads "12:49 by lake" and
  "12:49 in rates".
- 187: Audit filters by kind (Grants, Holds and freezes, Branches and tags, Commits, Other changes, Reads,
  Credentials, Refusals), each By kind row applies its kind, every filter is in the link, the audit's own reads are
  hidden unless asked for, and the range names both days.
- 190: when every maintenance job is off in the policy, Overview, Maintenance and a table's tab say so first, read
  through the caller's own table so asking writes no denial.

Checked: the fast build at landing. Live in the demo through the swap: 192, 186 and 190 as `ana`; 187 as `gary` on
`lakehouse`'s real audit tables (the access the old server does not send yet was set in the page). Not checked: the
heavy browser cases (overnight); the rail's Service item and each role's rail need the demo rebuilt (`index.html` and
the catalog list's `may` come from the server). Bites recorded for each guarded check, and D18's catalog-list bite and
184's rail bite recorded again where these changes moved them.

## Batch 3 (items 191, 188, 189, 193, 194)

- 191: a table's own Iceberg refs are "engine-level refs in this table's metadata", apart from the catalog's branches
  and tags, folded while a catalog ref is read, with one icon per kind of ref.
- 188: answer boxes lead with what happened in plain words; status, route and request id sit behind Technical
  details; the refs page's captions read in words, with the setting's key in their title.
- 189: one link style (the link colour, underlined on hover and focus); a row that opens something shows a chevron
  and opens on a click anywhere on it.
- 193: the IdP's calls give up after 15 s and name the host, with Start again; sign-out sends `id_token_hint`, so
  Keycloak ends its session without asking; the settings fetch offers Try again; dates read one way; no keyboard
  hint on touch; a namespace's path only where it differs from its name; empty Contents columns dropped and phone
  cards labelled.
- 194: a latency share below the Health chart's scale points off its left edge with its value.

Checked: the fast build at landing. Live in the demo through the swap: 191, 188, 189 and 193's header, columns and
dates as `ana`; 194 as `oscar`. Not checked live: 193's sign-out and slow-IdP paths (`ConsoleSignInTest`, heavy).
The heavy browser cases run overnight. Bites recorded for each guarded check;
`console-sign-in-ignores-state` recorded again where 193 moved it.

## After the demo's rebuild (main at fe86cfe2, Paul's go-ahead)

The stack was taken down and brought up at main fe86cfe2, which carries batches 1 to 3 and items 195 and 196.
Each step was announced to every interactive session (D49). A first `up` stopped while starting the base services
(`deployments.apps "kafka-ui" not found`): the landing of 195 and 196 changed `demo/demo.sh` and its Kafka
manifests in the main checkout while that `up` was reading the script. A second `up` finished.

Checked live, in a headless Chrome signed in through Keycloak, with the demo's own files and no swap:
- 184: ana's rail is Overview, Catalog, Maintenance and Governance; oscar's is Catalog, Governance and Service, and he
  lands on Service.
- 192: the rail says Service, and an old `#p=health` link opens it.
- 193: gary's tab kept the sign-in's ID token, and Sign out came straight back to "Signed out." with no Keycloak
  confirm page.
- 194: oscar's latency rows show load at 62% and commits at 96.1% as markers off the scale's left edge, with their
  values.

Two findings:
- gary now lands on the Overview of `nuna_audit`, which arrived with 195 and 196. He may read that catalog, so
  `readsData()` is true; the design had him land on Audit. Left for Atelier's re-walk.
- The tab ran the pre-rebuild console until each file was fetched afresh. Every `/ui/` file is served with a 1980
  Last-Modified and no ETag, so a revalidation always answers 304. Filed as backlog 203.

## Atelier's re-walk after phase 1 (demo at fe86cfe2)

Atelier re-walked her six tasks live: ana for tasks 1 to 5, gary for task 6. She counted steps as her review does,
from the landing.

| # | Task | Before | Now | After S1 to S5 (her estimate) |
|---|---|---|---|---|
| 1 | `rates`: who changed it last | 7 | 3 (`/`, `markets.fx.rates`, Enter; Overview names the author) | 3, and `rates` alone finds it (S1) |
| 2 | Read `@sample`, then back to main | 3 | 3, and the tree keeps the tag | 3 |
| 3 | What `dev` changed, and its merge preview | 6 | 3 | 2 (S4: Reading, @dev, then What changed and Merge) |
| 4 | Why a table is held or regulated | 3 | 1 (Governance) | 1 |
| 5 | Tonight's maintenance for a table | 7 | 0 for the catalog (the landing says maintenance is off); 3 for `rates` | 3 |
| 6 | gary finds a grant's audit record | 6 | 5 (3 if lakehouse was his last catalog) | 2, once he lands on Audit |
| | Total | 32 | 18 | 14 |

Her findings, worst first, and what became of each:
- Snapshot ids lose precision in `JSON.parse`, so Overview shows an id that names no snapshot. Filed as backlog 204.
- gary lands on the Overview of `nuna_audit`, not on Audit. Her fix: Audit before Overview, the audit table's catalog
  left out of the landing choice, and the remembered catalog kept per user. Taken into S3 (backlog 200).
- Branches and tags asked the audit feed without checking access, so ana's browsing wrote a denial. Fixed: it asks
  only a caller who may read the feed, and the ref page's Record tab is shown only to them.
- Audit words still say Kafka after item 195. Filed as backlog 205.
- The folded engine-level refs heading was drawn at the browser's default size. Fixed.
- Go to a path's empty answer claimed "0 names" and gave the demo's path in every catalog. Fixed: no count, and an
  example from the catalog.
- With maintenance off, Overview and Maintenance gave a second, amber headline blaming the worker, and the empty
  queue blamed the worker too. Fixed: one headline, and the queue names the policy.
- Smaller (Service still shows the catalog picker, an example tag that does not exist, builder language on Branches
  and tags): left for S3 and S5.

Her design answers for S2 and S3 are in backlog 199 and 200's notes when built:
- Catalog tabs: Contents, Branches and tags, Properties; the rail pages are the catalog-wide views.
- Home order: Audit, then Service, then Overview.
- Audit: all catalogs by default, with a catalog filter.
- The ref on main-only pages: keep it with a "main only" note.
- History: one row per metadata commit, ids as strings.
- Properties editing: behind a Settings drawer.
- Access: lead with what the viewer may do.
- Phone: check six tabs at 390 px.

## Phase 2: Atelier's structural changes S4, S3, S2, S1

- **S4 (backlog 201)**, landed at 67a94d94. The Reading control sits in the header beside the catalog picker, each ref
  has a page of its own, and a branch's tree marks the tables it changed.
- **S3 (backlog 200)**, landed at 52c5a44f. The rail is chosen by role:
  - The catalog list marks the audit tables' catalog, so no sign-in lands there and reading it does not count as
    reading data.
  - Home is Audit, then Service, then Overview.
  - The remembered catalog is kept per person.
  - Service offers no catalog to pick.
  - Pages that show main only keep the ref being read, and say so.
- **S2 (backlog 199)**, landed at daca35a3. A table has six tabs: Overview, Schema, History, Access, Maintenance and
  Audit.
  - History has one row per metadata commit, with who made it and the snapshot it added.
  - Access leads with what you may do.
  - Properties change in a Settings drawer.
  - A view, a namespace and a catalog use the same names for the same things, and old tab links open the tab that now
    holds what they showed.
  - Checked live as ana with the swap script: an old Snapshots link opens History, Access leads with "What you may
    do", and an old properties link opens the drawer. The six tabs were also checked at 390 px.
- **S1 (backlog 198)**, landed at daca35a3. `GET /management/v1/names` lists every namespace, table and view whose
  name holds a fragment, among those the caller may describe, in every catalog or the one named. Go to a path shows
  what you opened lately, then what this catalog has listed, then those matches.
  - The `catalog-security` review asked for five fixes before landing, all made:
    - namespace visibility read once per catalog;
    - every object decided before its name is looked at, so a hidden match costs what no match costs;
    - a named catalog found through `Holdings.catalog`, so its refusal is audited;
    - nothing walked for a caller who holds nothing;
    - a route test per role.
  - Two findings were left as notes in the item: a forged token only moves the start point, and nothing pages past
    `more-namespaces`.
  - Four route bites were checked to bite.
- **Browser tests:** the heavy browser tests for S2 and S1 were updated but not run. Three runs failed in setup:
  Ryuk unreachable twice, and a Postgres connection timing out with the demo down. Windows had 0.4 to 0.9 GB free
  and was paging at 2,500 to 4,000 pages a second. Committed memory was 49.5 GB on 16 GB of RAM, with 35 Claude
  processes. Tonight's heavy run runs them.
- **S5 (backlog 202):** Atelier wrote the words table (`docs/design/console/words-table-2026-11-23.md`). Her four
  "not words" items became backlog 206 to 208, and T2 goes in with S5.

## Atelier's re-walk after phase 2 (demo at daca35a3, console files at a10e4348)

Atelier re-walked her six tasks live, as ana for tasks 1 to 5 and gary for task 6, counting from the landing. S5's
files were swapped in, because the demo's build predates S5 and a plain tab ran the stale console (backlog 203).

| # | Task | Before | After phase 1 | After phase 2 | Steps now |
|---|---|---|---|---|---|
| 1 | `rates`: who changed it last | 7 | 3 | 3 | `/`, `rates`, Enter; Overview names the author |
| 2 | Read `@sample`, then back to main | 3 | 3 | 3 | Reading, @sample, Read main instead |
| 3 | What `dev` changed, and its merge preview | 6 | 3 | 3 | Reading, What changed, Merge |
| 4 | Why a table is held or regulated | 3 | 1 | 1 | Governance (a regulated area's reason takes 2) |
| 5 | Tonight's maintenance for a table | 7 | 3 | 3 | `/`, `rates`, Enter; Overview says maintenance is off in the policy |
| 6 | gary finds a grant's audit record | 6 | 5 | 4, now 2 | catalog picker, lakehouse, Grants chip, Show record |
| | Total | 32 | 18 | 17, now 15 | |

She estimated 14. Two tasks missed it:
- **Task 6.** gary landed on the empty Audit page of `engines`, a regression from S3, which keeps the remembered
  catalog per person. The landing ranked catalogs by tables, which says nothing of an auditor's work. Fixed in backlog
  211: a home of Audit now lands on the catalog with the most audit records. Checked live: gary lands on lakehouse's
  Audit with 1,140 records, so task 6 is 2 steps and the total 15.
- **Task 3.** It stays at 3, because the merge verdict is only on the Merge tab. Backlog 212.

Her other findings became backlog items:
- 213: a regulated area says why and who set it.
- 214: Audit uses the chips' words everywhere. This settles the words table's Y3 question: a badge shows the chip's
  word in the UI font, and the raw record type goes in its `title`.
- 215: the monospace S5 missed.
- 216: an audit record offers an archive check without an archive.
- 217: five smaller ones.

Backlog 204 (rounded snapshot ids) and 205 (Kafka words in Audit) are still open.

She said these landed well: search by fragment with recent items first; the Reading control and its menu; Read main
instead; the six tabs; Access leading with "What you may do"; the Audit chips kept in the URL; and Settings offering
no changes through a tag.
