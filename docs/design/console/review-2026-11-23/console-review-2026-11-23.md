# The nuna console as built: a wayfinding review

Atelier, 2026-11-23. Reviewed live in the nuna-demo stack at https://localhost:8181/ui/, signed in through Keycloak
as `ana` (most of it), `gary` (audit and grants) and `oscar` (Health), at 1440 x 900 and 390 x 844. Every
screenshot named below is in this folder. Nothing was changed: no merges, grants, governance changes, branches or tags.

**Not covered:** I could not sign in as `stella`. By then Keycloak was answering only about two requests in three
(timeouts of 10 to 30 s between them), and the console's front door twice answered `app.js` and `style.css` with
502. Governance below is reviewed as `ana`, who sees the same screens with the same buttons.

---

## 1. Verdict

The console is accurate and well written sentence by sentence. It is hard to find your way in because of five
structural causes, not because of any single screen.

1. **It opens in the wrong place, and you can't find anything by name.** Sign-in lands on `engines`, a catalog that
   is empty for every demo user. "Go to a path" searches only the namespaces you have already opened in the tree, so
   typing `rates`, or even the full `markets.fx.rates`, answers "No path matches". A newcomer has to know the
   hierarchy before they can use the search that is meant to save them from knowing it.
2. **There are two navigation systems that overlap.** The left rail sorts by kind of page (Overview, Maintenance,
   Governance, Audit, Health). The tree and each object's tabs sort by object, and the tabs reuse the rail's words:
   a table has Governance, Audit and Health tabs too. "Health" means server latency on the rail and compaction on a
   table. On the Catalog page the rail folds to unlabelled icons, so its labels vanish where people browse most.
   "Where am I?" has two answers, and the words don't tell them apart.
3. **Context is carried inconsistently.** While you read a branch or tag, the crumbs keep it, but the tree and the
   rail's Catalog link silently drop you back on `main`. The catalog picker stays in the header on Health, a
   deployment-wide page. Audit filters aren't in the URL, so Back and shared links lose them. There are also two
   kinds of "branch" on screen: the catalog's `@dev` and `@sample`, and a table's own Iceberg `main`.
4. **It shows everyone everything, and the server says no.** `ana` sees Audit and Health on the rail and an Audit tab
   on every table. Opening them gives red boxes reading `403 ForbiddenException`, for example "ana holds no privilege
   that gives manage-grants for readAudit". Each visit also writes a denial record to the audit trail. She also sees
   Compact now, Place a hold, Release and Lift. My design said `gary` should see only Catalog, Governance and Audit;
   the build shows all six rail items to everyone.
5. **Words written for the builder, and the answer people want is buried.** Screens carry API paths (`GET …/refs`,
   `200 OK · POST …/refs/dev/merge, dry run`), request IDs, configuration keys (`nuna.refs.max-branches`) and a
   footnote paragraph under every table. Meanwhile "who changed this table last" is only in the tenth tab,
   Metadata log. In this demo the headlines on Overview and Maintenance report a failure ("17 tables not assessed
   yet: the maintenance worker has not reported"), while the policy table below shows maintenance switched off.

What already works: deep links (every page and tab has a hash route, and a deep link opens the tree to the object);
the Back button (every step I tried came back correctly); crumbs on namespace, table and ref pages; the green band
while reading a ref, with its **Read main instead** button; and the merge preview, which runs on its own when the Merge
tab opens and gives a plain verdict ("Ready: 2 tables would move to main in one transaction").

---

## 2. Findings, ranked by how much they hurt finding your way

Each finding gives what I did, what happened, the screenshot, why it hurts and the change I recommend. Sizes:
S is a day or less, M a few days, L a week or more or a new server route.

### F1. "Go to a path" can't find a table you haven't already opened (S/M to soften, L to fix)
- **What I did:** On `lakehouse` (tree folded), pressed `/` and typed `rates`, then `markets.fx.rates`, then Enter.
- **What happened:** "No path matches. Try fewer words. Go to a path finds the paths the console has listed so far:
  open a namespace in the tree to list what is in it." Enter did nothing. After I had opened markets and fx in the
  tree, `rates` found it, but `journal` (a brokerage table) still found nothing.
  `06-goto-rates.png`, `23-goto-journal.png`, `23-goto-rates-after.png`
- **Why it hurts:** This is the console's most prominent finding tool (a header box plus the `/` key), and it fails
  the most natural first move. "Try fewer words" is wrong advice for a one-word query. Users learn that search
  doesn't work and fall back on clicking down five levels.
- **Change:**
  - Quick: when the input is a dotted path, open it directly. Fetch each level on demand, as a deep link does
    already, so `markets.fx.rates` + Enter opens the table.
  - Quick: on no match, say "Not found among the N names listed so far. Open a namespace, or type the full dotted
    path." Drop "Try fewer words" when only one word was typed.
  - Structural: a catalog-wide name search route, or a background walk of the namespaces the user may describe the
    first time the box opens, with "Recent" items at the top.

### F2. Sign-in lands on an empty catalog (S)
- **What I did:** Signed in as `ana`, and later as `gary`.
- **What happened:** Both landed on `#p=overview&c=engines`: "No table in engines is yours to select.", "0 of 0",
  "— no night yet". The catalog with the data (`lakehouse`, 17 tables) is one picker away, but nothing says so.
  `01-landing-1440.png`, `41-gary-landing.png`
- **Why it hurts:** It's the first screen. It states an absence in an odd phrase ("yours to select") and gives no way
  forward. A newcomer concludes the product is empty or broken.
- **Change:**
  - Open the catalog used last (per browser); otherwise the one with the most tables the user may read; otherwise
    the first.
  - An empty catalog's headline names the alternative: "Nothing in engines you may read. lakehouse has 17 tables →".

### F3. Reading a branch or tag, the tree and the rail drop you back on main without saying so (S)
- **What I did:** Read `rates` as of `@sample` (the band appeared), then clicked `fx` in the tree.
- **What happened:** It landed on `#c=lakehouse&k=namespace&ns=markets%1Ffx` with no `r=`. The band disappeared and
  the picker said `main` again. Every tree link and the rail's Catalog link are built without the ref
  (`tree.js` uses `objectHref` without `r`). The crumbs and the tab links keep it.
  `12-rates-at-sample.png`, `13-tree-click-drops-ref.png`
- **Why it hurts:** It's the trap the band exists to prevent. Someone reading a branch who clicks the tree, which is
  the main way to move, is silently reading main and may compare or act on the wrong data.
- **Change:**
  - Carry `r=` in every tree link and in the rail's Catalog link.
  - While a ref is read, show it on the tree's root (`lakehouse @dev`) and mark the tables the branch changed.

### F4. The rail and tabs offer pages the user may not open; the refusals are in server language and get audited (S/M)
- **What I did:** As `ana`, opened Audit and Health on the rail, and the Audit tab on `rates`.
- **What happened:** Each showed a red "Refused by the catalog" box with `403 ForbiddenException` and texts such as
  "ana holds no privilege that gives select for readAuditFeed" and "only platform operators may call
  readCatalogHealth", ending "audited as a denial". `gary`'s feed then showed `ana`'s denials. As `gary` (no data
  access), the landing Overview shows only "0 of 0".
  `20-audit-ana.png`, `20-health-ana.png`, `10-rates-audit.png`, `42-gary-audit-engines.png`
- **Why it hurts:** Half the navigation is a dead end for an analyst. Exploring the menu fills the audit trail with
  denials an auditor then has to explain. The message names internal operations (`readAuditFeed`), not what the
  person can do about it.
- **Change:**
  - Hide rail items and tabs the user's groups can't use. That was the design: "gary sees only Catalog, Governance
    and Audit" (design README).
  - Land each role where its work is: `gary` on Audit, `oscar` on Health.
  - Where a refusal still happens, lead with "Audit is open to auditors. Your groups: demo-analysts." and put the
    status, exception and request ID behind "Technical details".

### F5. Rail and tabs share words, "Health" means two things, and the rail loses its labels on the Catalog page (M)
- **What I did:** Moved between Overview, Catalog, Maintenance and a table's tabs.
- **What happened:**
  - The rail shows labels on Overview, Maintenance, Governance, Audit and Health, but folds to six bare icons on
    Catalog (it does in the prototype too).
  - A table has ten tabs: Overview, Schema, Snapshots, Partitioning, Properties, Health, Governance, Grants, Audit,
    Metadata log.
  - Rail "Health" is server latency, Kafka and DR, for operators. A table's "Health" is compaction need. Rail
    "Governance" is a catalog summary, and the "Governance" tab is one object's. Rail "Audit" is the catalog feed, and
    the "Audit" tab one object's.
  - Tabs differ by level: the catalog has Contents, Properties, Governance, Grants, Audit, Branches and tags; a
    namespace has no Branches and tags.

  `02-catalog-engines.png`, `04-catalog-lakehouse.png`, `07-ns-markets.png`, `09-table-rates.png`, `61-proto-table.png`
- **Why it hurts:** The same word leads to different places depending on whether it's in the rail or a tab, so people
  can't predict where a click goes. The icon-only rail on the busiest page makes "how do I get to Governance" a
  hover hunt.
- **Change:**
  - Keep the rail labelled on every page. The tree can share the space: rail labels at 1440 px leave room.
  - Rename the rail's Health to **Service** (operators only), and the table's Health tab to **Maintenance**, which is
    what it shows.
  - See S2 for the tab consolidation.

### F6. "Who changed it last" is only on the tenth tab (S)
- **What I did:** Task 1: looked for who last changed `markets.fx.rates`.
- **What happened:** Overview's Current state shows "5741705045886767000 · append · 2026-11-23 12:49:34", with no
  author. Snapshots has no "by" column. Only Metadata log, the tenth tab, gives "**lake** via lake-writer-demo". The
  `fx` namespace's Contents row shows "12:49 · lake" under *Last commit*. One level up, the same column shows a table
  name instead ("12:49 · rates"), so the word next to the time changes meaning by level.
  `09-table-rates.png`, `10-rates-snapshots.png`, `10-rates-metadata-log.png`, `08-ns-fx.png`
- **Why it hurts:** "Who touched this and when" is the first question about any table. It's answered three clicks deep
  in a tab named for a file format.
- **Change:**
  - Put "by lake via lake-writer-demo" under Current snapshot on Overview. My prototype had it ("svc-fx-ingest via
    spark", `61-proto-table.png`), and the build dropped it.
  - Add a **By** column to Snapshots.
  - Label the Contents cell "12:49 by lake" for a table and "12:49 in rates" for a namespace.

### F7. Audit can't be filtered to grants, and its filters don't survive Back or a link (S/M)
- **What I did:** Task 6 as `gary`: find a grant's audit record in `lakehouse`.
- **What happened:**
  - The chips are All, Changes, Reads, Credentials, Refusals. Changes holds 1,557 records, almost all CDC commits by
    `cdc via kafka-connect-demo`.
  - The **By kind** panel lists `nuna.catalog.grant 2`, but its rows aren't links.
  - I found the two grants only by guessing the granter and typing `gary` into Who.
  - The URL stayed `#p=audit&c=lakehouse` with the filters applied.
  - The range note reads "From 22 Nov 16:33 to 16:33".
  - On `engines`, five of the seven records were the audit's own reads (`nuna.catalog.audit-read`).

  `42-gary-audit-engines.png`, `44-gary-audit-changes.png`, `45-gary-audit-who-gary.png`, `46-gary-audit-record.png`
- **Why it hurts:** An auditor's first questions ("show me grants", "show me holds") have no direct path, and the one
  clue on screen is a table that looks clickable but isn't.
- **Change:**
  - Add a Kind filter (chips for Grants, Holds and freezes, Branches and tags, Commits, Reads, Credentials, Refusals),
    and make each By kind row a link that applies it.
  - Put every filter in the hash.
  - Hide the audit's own reads by default ("Show audit reads").
  - Write the range as "22 Nov 16:33 to 23 Nov 16:33".

### F8. Two kinds of "branch" on one screen (S)
- **What I did:** Read `rates` at `@sample`.
- **What happened:** The header says "Reading @sample" and the band says the catalog as it was at 23 Nov 12:51. In the
  same view, the right column's "Iceberg refs in this table" shows `main · current snapshot` with a different icon,
  and the ref menu's `main` uses yet another.
  `12-rates-at-sample.png`, `11-ref-selector.png`
- **Why it hurts:** A reader sees "main" while being told they're not on main. Catalog branches (nuna's) and a
  table's Iceberg refs are different things, and the screen names both "refs" and "main".
- **Change:**
  - Rename the panel "Engine-level refs in this table's metadata", with one line explaining that these are Iceberg's
    own branches and tags, set by engines, not the catalog's.
  - Collapse the panel while a catalog ref is read.
  - Use one icon per concept, everywhere.

### F9. In this demo the maintenance pages lead with a failure, and the real reason sits in a policy table (S)
- **What I did:** Task 5: tonight's maintenance for a table. Opened Overview, Maintenance and `rates` › Health.
- **What happened:**
  - Overview: "17 tables not assessed yet: the maintenance worker has not reported."
  - Maintenance: the same headline, then "Nothing queued for tonight".
  - `rates` › Health: "Not assessed yet · No compaction has run for this table."
  - The policy table on Maintenance shows `snapshot-expiry.enabled false`, `orphan-removal.enabled false`,
    `compaction.enabled false`, `manifest-rewrite.enabled false`, each "this built-in".
  - Health (as `oscar`) confirms "no worker has reported".
  - The "Every table, one mark" chart is a flat line.

  `20-overview-ana.png`, `20-maintenance-ana.png`, `52-oscar-health.png`
- **Why it hurts:** Task 5 is a dead end, and nothing tells the reader that the policy has maintenance switched off.
  The hero sentence of the product's sales story reports a fault. (Whether the demo should run the worker is a
  question about the demo, not the console; I raise it for Paul.)
- **Change:** When every job type is off in the policy, say so in the headline: "Maintenance is off in lakehouse's
  policy: nothing will be compacted or expired." Link Edit the catalog's policy, and keep "no worker has reported"
  as the second line.

### F10. Builder language on user screens (S)
- **What I did:** Read every page.
- **What happened (examples):**
  - "of lakehouse, as GET …/refs lists them"
  - "2 tables with pointers of its own; GET …/refs/dev"
  - "200 OK · POST …/refs/dev/merge, dry run X-Request-ID ca43… nothing moved, nothing audited"
  - "nuna.refs.max-branches" as a stat caption
  - "403 ForbiddenException"
  - "Each record opens to the event as Kafka holds it"
  - Monospace for headings' subtitles, chips and table names throughout; a long footnote paragraph under almost
    every table.

  `14-branches-tags.png`, `15-ref-dev.png`, `16-ref-dev-merge.png`, `20-audit-ana.png`
- **Why it hurts:** Every sentence is true, but the reader has to filter out the plumbing to find the point. Dense
  small text under every panel trains people to stop reading, so the notes that matter get skipped too.
- **Change:** One **Technical details** disclosure per panel for status, route and request ID; captions in plain words
  ("1 of 50 branches allowed"); cut footnotes to one sentence or move them to an "About this page" link.

### F11. Links that don't look like links (S)
- In Contents tables (`markets`, `fx`, `rates`), the Governance page's object names and the merge preview's table
  names, the name is a link but is styled as plain monospace text, with no colour or underline until hover. The
  "1 commit" and "markets" links elsewhere *are* underlined. `04-catalog-lakehouse.png`, `07-ns-markets.png`,
  `20-governance-ana.png`
- **Change:** One link style: link colour, plus underline on hover and focus. Rows that open something get a hover
  background and a chevron.

### F12. Controls offered to people who can't use them (S)
- As `ana` (no `manage-tags`), the trades Governance tab offers Change the classification, Change the owner, Set a
  retention class and Place a hold, with a small "Needs manage-tags." note. Release appears on the hold; Lift and
  Release appear on the Governance summary. Compact now and Export metadata sit on every table. `21-trades-governance.png`,
  `20-governance-ana.png`
- **Change:** Hide what the user's groups can never do. Where it depends on the object, disable the control with the
  reason as its tooltip.

### F13. Empty columns and repeated names (S)
- A namespace header repeats its name ("markets markets") at the top level.
- On namespace rows, Contents shows Format and Tonight's queue as "–", and reading through `@dev` blanks Snapshots
  and Last commit too.
- On a phone the dashes lose their headers ("– – 16:31 · ledger.journal_lines").

`07-ns-markets.png`, `17-reading-dev-catalog.png`, `33-phone-catalog.png`
- **Change:**
  - Show the dotted path only when it differs from the name.
  - Drop columns that are empty for every row.
  - On phone cards, label each value.

### F14. Sign-in and sign-out are fragile when Keycloak is slow (S)
- **What I saw:**
  - Sign-out goes to Keycloak's "Do you want to log out?" page, so a second click is needed
    (`40-signout-confirm.png`).
  - When that second step failed ("Signed out here, but your session at localhost:8180 was not ended: Failed to
    fetch"), Sign in brought the previous user back without asking for a password (`50-oscar-health.png`).
  - While Keycloak was stalling, sign-in sat on an unstyled or plain "Loading…" for over a minute on three tries
    (`53-signin-stuck-loading.png`). Once the tab held a half-finished sign-in (`nuna.oidc.pending`), every reload
    stuck there until I cleared the tab's storage.
  - `auth.js`'s discovery and token fetches have no timeout.

  This was set off by the environment, but it's what a person meets when the IdP has a bad minute.
- **Change:**
  - Time out the IdP calls (for example after 15 s) with "localhost:8180 is not answering. Try again." and a
    **Start again** button that clears the pending sign-in.
  - Pass `id_token_hint` so Keycloak skips its confirmation page; the code says it keeps no ID token today.

### F15. The Health latency chart says nothing when the objective is badly missed (S; my design)
- As `oscar`, all three rows sit far below their objectives (78.00%, 1.44%, 88.91% within). The dots are drawn on a
  97–100% scale, so all three are pinned to the left edge and look the same. `52-oscar-health.png`
- **Change:**
  - Below the scale, draw an off-scale marker with the value beside it ("◀ 78%").
  - Or widen the scale to 0–100% when any row is below 97%.
  - My memory note "dot on a labelled scale for share near 100%" holds only near 100%.

### F16. Phone (S)
- **Works:** no horizontal page scroll at 390 px; crumbs shorten to "… / fx / rates"; the tab strip scrolls with the
  active tab in view; the menu opens as a drawer with Go to a path.
- **Problems:**
  - There's no tree on a phone; you browse through Contents cards.
  - The ten tabs scroll off screen, so most are invisible.
  - The menu footer says "Press / to go to a path, ? for keys" on a touch device.
  - The menu gives the session end as "until 11/23/2026, 5:24:52 PM", while every other date reads "23 Nov".
  - The Overview chart's namespace labels collide ("markets 4reference 1").

  `31-phone-rates.png`, `32-phone-menu.png`, `33-phone-catalog.png`, `34-phone-overview.png`, `35-phone-merge.png`
- **Change:**
  - Hide the keyboard hint on touch devices.
  - Use one date format.
  - Drop labels that don't fit.
  - Fewer tabs (S2) helps most here.

### F17. Smaller things
- The ref selector disappears on the catalog's Branches and tags tab and on a ref page (`14-branches-tags.png`,
  `15-ref-dev.png`).
- Header actions vary by level for no stated reason: catalog Copy name / Copy link; namespace Copy path / Copy link;
  table Copy link / Export metadata / Compact now.
- Branches and tags shows a red ✕ (delete) beside Read on every row, and Delete sits beside Renew on the ref page.
  It's one click from a navigation link, though I didn't press it, so I don't know whether it asks first.
- "Two kinds of ref" shows `@matter-2026-114`, a tag that doesn't exist in this catalog, as its example.
- On first load (my first attempt) the console said "The console could not read its settings, /ui/config.json ·
  Failed to fetch", with no retry button. A reload fixed it.

---

## 3. The six tasks: steps today and after

A step is one click or one key press (typing a name counts as one). Counts start at the landing page after sign-in,
the way a newcomer starts.

| # | Task | Today | Dead ends today | After the changes |
|---|---|---|---|---|
| 1 | Find `markets.fx.rates`, see who changed it last | **7**: catalog picker, lakehouse, Catalog, markets, fx, rates, Metadata log tab ("lake via lake-writer-demo") | "Go to a path" with `rates` or `markets.fx.rates`: No path matches (+3 wasted). Overview and Snapshots don't name the author. | **3**: (lands on lakehouse) `/`, type `rates`, Enter: author on Overview |
| 2 | Read the table as of `@sample`, then back to main | **3** from the table: Reading, @sample, Read main instead | Clicking the tree while on `@sample` silently returns to main | **3**, and the tree keeps `@sample` |
| 3 | See what `dev` changed and preview its merge | **6**: picker, lakehouse, Catalog, Branches and tags, "2 tables", Merge tab (the preview runs itself) | None, but the ref selector, the obvious place to start, links to "Branches and tags of lakehouse", not to `@dev`'s changes | **3**: Reading menu, `@dev` › What changed, Merge tab |
| 4 | Find why a table is held or regulated | **3**: picker, lakehouse, Governance: hold reason "Matter 2026-114: trade surveillance review" | Regulated areas list the namespace (`brokerage.ledger`) but no reason or who set it; the table's Governance tab shows `regulated –` | **2** (lands on lakehouse); a regulated area shows who set it and when |
| 5 | Find tonight's maintenance for a table | **7** to reach `rates` › Health: "Not assessed yet" | Can't be completed in this demo: nothing is assessed, and the headline blames the worker while the policy has maintenance off | **4** to read "Maintenance is off in lakehouse's policy" on the table's Maintenance tab; real answers need the worker running |
| 6 | As `gary`, find an audit record of a grant | **6**: Audit, picker, lakehouse, Changes, Who = `gary` (a guess), Show record | 1,557 Changes, mostly CDC commits; By kind shows `grant 2` but isn't clickable; filters not in the URL | **3**: (gary lands on Audit for lakehouse) Grants chip, Show record |

---

## 4. Recommended changes

### Quick wins (wording, links, defaults)

| # | Change | Fixes | Size |
|---|---|---|---|
| Q1 | Land on the last-used catalog, else the one with the most readable tables; an empty catalog names the other | F2 | S |
| Q2 | Carry `r=` in tree and rail links; show the ref on the tree root | F3 | S |
| Q3 | Hide rail items, tabs and buttons the user's groups can't use; land gary on Audit and oscar on Health | F4, F12 | S/M |
| Q4 | "Go to a path" opens an exact dotted path, and its empty state tells the truth | F1 (part) | S/M |
| Q5 | Author on Overview's Current snapshot; By column on Snapshots | F6 | S |
| Q6 | Audit: Kind filter with Grants, By kind rows as links, filters in the hash, audit's own reads hidden | F7 | S/M |
| Q7 | Plain-words captions; routes, statuses and request IDs behind "Technical details" | F10, F4 | S |
| Q8 | One link style; hover chevron on rows that open | F11 | S |
| Q9 | Maintenance headline says when the policy has it off | F9 | S |
| Q10 | Rename the table panel "Engine-level refs"; one icon per kind of ref | F8 | S |
| Q11 | Rename rail Health to Service and the table's Health tab to Maintenance; keep rail labels on Catalog | F5 (part) | S |
| Q12 | IdP timeouts with Start again; hide keyboard hints on touch; one date format; drop empty columns | F13, F14, F16 | S |
| Q13 | Health chart: off-scale marker below 97% | F15 | S |

### Structural changes (information architecture, navigation model)

| # | Change | Size |
|---|---|---|
| S1 | **One search that knows every name.** A server route that lists every namespace, table and view the caller may describe, by name fragment, for one catalog or all. The box shows recent items first, then matches across catalogs with their path and kind. This is the biggest single gain. | L (route) + M (console) |
| S2 | **Object first, fewer tabs.** Cut the table's ten tabs to six. **Overview** (last change and by whom, size, health and governance summaries, refs). **Schema** (with partitioning and sort order). **History** (snapshots and metadata log merged, with who). **Access** (grants and governance attributes, holds). **Maintenance** (today's Health, plus the table's maintenance settings). **Audit** (only for roles that may read it). Properties folds into Overview and Maintenance. Namespaces and catalogs use the same names for the same things. | M/L |
| S3 | **The rail by role, with distinct words.** Rail: Home, Catalog, Governance, Audit, Maintenance, Service. Shown only where the role may use them, never as an icon-only strip. Home is per role: the catalog overview for analysts, the feed for auditors, Service for operators. | M |
| S4 | **The ref as global context.** One "Reading" control in the header, beside the catalog picker ("lakehouse @dev ▾"), carried by every link, with the band below. The tree marks the tables a branch changed. Branches and tags becomes a tab of Catalog and also a menu item in the Reading control. | M |
| S5 | **A copy pass across every page.** Before and after tables for every caption, empty state and refusal, using the words-table method from chapter 6 of the wireframes. Monospace only for names, paths and numbers. | M |

---

## 5. Where the build departs from my design, and what in my design I'd now change

### Departures (build differs from prototype v14 and wireframes v20)

| Design | Build | Verdict |
|---|---|---|
| Rail shows only what the role may use ("gary sees only Catalog, Governance and Audit", design README) | Every user sees all six | Build should follow the design (Q3) |
| Current snapshot names its author ("svc-fx-ingest via spark", `61-proto-table.png`) | Author only in Metadata log | Build should follow the design (Q5) |
| Default catalog `lakehouse` (`#p=overview&c=lakehouse`, `60-proto-overview.png`) | Defaults to `engines`, which is empty | Build should follow the design, with Q1's rule |
| Go to a path matched any name, because the prototype holds all its data locally | Matches only listed paths | The design hid a missing server route; see below |
| Maintenance, Health and Overview drawn with a working worker and rich data (1,284 tables, "38 tables compacted") | The demo has 17 tables and no worker report, so every headline is an empty state | The empty states were built faithfully; my design gave them too little weight |

### What in my own design I'd change

1. **I designed search without its data.** The prototype's "Go to a path" worked only because the prototype holds
   every name in memory. The design README's list of server gaps doesn't include a name-search route, so the build
   had to fall back on "listed so far". That was my omission, and it's the root of F1.
2. **Two navigation systems were my choice.** The rail of page kinds, the tree, and ten tabs per table that reuse the
   rail's words are all in the prototype, as is the rail folding to icons on Catalog (`61-proto-table.png`). The build
   followed me. I'd now design object first (S2, S3).
3. **I designed for the full state and treated the empty state as an afterthought.** The "Every table, one mark"
   chart and the sentence headlines read beautifully with 1,284 assessed tables. With 17 unassessed tables the chart
   is a flat line and the headline is a fault report. Every hero panel needs a designed "nothing yet" and a designed
   "switched off".
4. **I let the plumbing into the copy.** Captions like "as GET …/refs lists them" and "200 OK · POST …, dry run"
   came from my honesty principle (show what the server answered). It belongs in a disclosure, not the body (F10).
5. **The Health dot scale (97–100%)** was mine, from a sound rule for values near 100%. It fails when the objective is
   badly missed, which is exactly when the chart matters (F15).
6. **Monospace overuse and the footnote under every panel** are mine too. Both cost scannability on every page.

The look (Ice line: palette, type, cards, the ref band) still holds. Nothing above needs a visual redesign; it needs
an information-architecture pass and a copy pass.

---

## Screenshot index

All in `C:/Users/paulh/code/nuna-catalog/.claude/scratch-atelier-review/`.

| File | Shows |
|---|---|
| 00-signin-1440.png | Sign-in page |
| 01-landing-1440.png | ana's landing on empty `engines` |
| 02-catalog-engines.png | Catalog page, icon-only rail, empty catalog |
| 03-catalog-picker-open.png | Catalog picker |
| 04-catalog-lakehouse.png | lakehouse Contents |
| 05-goto-open.png, 06-goto-rates.png | Go to a path, and "No path matches" for `rates` |
| 07-ns-markets.png, 08-ns-fx.png | Namespace pages ("markets markets"; "12:49 · lake") |
| 09-table-rates.png | Table Overview (no author) |
| 10-rates-snapshots.png, 10-rates-audit.png, 10-rates-metadata-log.png | Snapshots, Audit 403, Metadata log with "By" |
| 11-ref-selector.png, 12-rates-at-sample.png | Ref menu; reading @sample with "Iceberg refs: main" |
| 13-tree-click-drops-ref.png | Tree click returns to main |
| 14-branches-tags.png, 15-ref-dev.png, 16-ref-dev-merge.png, 17-reading-dev-catalog.png | Branches and tags, @dev, merge preview, reading @dev |
| 20-overview-ana.png, 20-maintenance-ana.png, 20-governance-ana.png, 20-audit-ana.png, 20-health-ana.png | Each rail page as ana |
| 21-trades-governance.png | Held table's Governance tab |
| 22-keys-help.png | `?` keys dialog |
| 23-goto-journal.png, 23-goto-rates-after.png | Search after opening the tree |
| 30–35-phone-*.png | 390 px views |
| 40-signout-confirm.png, 51-after-logout.png, 53-signin-stuck-loading.png | Sign-out confirmation; stuck Loading |
| 41-gary-landing.png … 48-gary-catalog.png | gary: landing, audit, filters, record, grants, catalog |
| 50-oscar-health.png | Sign-out failed to end the Keycloak session |
| 52-oscar-health.png | Health as oscar (off-scale dots) |
| 60-proto-overview.png, 61-proto-table.png | The v14 prototype for comparison |
