# The console's words, before and after (backlog 202, review S5)

Atelier, 2026-11-23. Made from the console's source in `catalog-server/src/main/resources/nuna/ui/` on branch
`claude/console-wayfinding-d47d6a`, with S2 (backlog 199) and S3 (backlog 200) applied. I read the source only; I did
not drive the console. Line numbers are this branch's and will drift, so each row also names the function or element.

Every row is a word swap you can apply in one pass, unless it is tagged:

- **TD**: the words change, and the route, status or settings key they drop moves into a `title` attribute or into
  `dom.js`'s existing `techDetails()`. No new component is needed.
- **not words**: the change needs more than a word swap (a link target, a new button, a lookup table the code doesn't
  have). File these separately; the "Now" and "New" columns give the words for when they're built.

Slots in `{braces}` are filled from data, as the code fills them today. Strings that read well are left out.

Item 188 already reworded the answer boxes (`dom.js` `failure` and `granted`, the merge verdict) and the Branches and
tags page's captions. I leave those alone, apart from two sentences inside answer boxes that still say "answers 404"
(R7 and D10). Both are marked as uncertain at the end.

---

## Rules

1. **Monospace is for names, paths and numbers only.** Names are catalog, namespace, table, view, ref, group and
   privilege names, table property keys, maintenance policy keys, record types, ids and SQL. Captions, `meta` lines,
   eyebrows, kind words ("catalog", "namespace"), units and badges that hold words use the UI font. See Y1 to Y4.
2. **Routes, HTTP statuses, exception types and deployment settings keys** (`GET …`, `/v1/config`,
   `/ui/config.json`, `200`, `304`, `403`, `404`, `409`, `nuna.*`) appear only in Technical details or a `title`. The
   page says what that means for the reader instead: "can no longer be read", not "answers 404".
3. **Table property keys and maintenance policy keys are names**, because people type them, so they may appear. In a
   sentence, use the setting's label ("set in the maintenance policy"), not the key.
4. **No internal references in copy.** No backlog items ("item 113"), no decision rows ("D23"), no component names
   ("the archive consumer", "the audit topic", "Job" with a capital J). Name products only where operators need them
   (Kafka on Service is fine).
5. **Use the words the console uses now:**
   - "may see", not "may describe", in prose. Keep `describe` where the privilege itself is named.
   - "Maintenance tab", not "health"; "Access tab", not "Governance tab" (on a namespace, table or view).
   - "across all catalogs", not "in the deployment".
   - "can no longer be read", not "gone from every route" or "answers 404".
   - "DR freeze" stays: it's the domain's own term, and the freeze dialog explains it.
6. **An empty state says what is absent, why if known, and one next step.** If the step needs a link or a button, the
   row is "not words".
7. **A footnote is one sentence** that the reader needs in order to act. If it mostly explains how the console works,
   cut it.

---

## Rail pages

### Header (every page)

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| H1 | `app.js` `showWho`, `#who` title | From the token, as the browser reads it; the server decides what it allows. The token lasts until {time}. | Signed in until {time}. The catalog checks what you may do on every request. | "Token" and "as the browser reads it" are plumbing |

### Overview (Home for analysts)

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| O1 | `overview.js` `overviewPage`, `sub` (l. 39) | Of the tables you may read, as the maintenance worker last assessed them. Each table's health is on its own page. | Of the tables you may read, as the maintenance worker last assessed them. Each table's Maintenance tab has the detail. | The "Health" tab is called Maintenance now |
| O2 | same, `sub` when maintenance is off (l. 37-38) | … the worker assesses a table only where its policy turns maintenance on. Each table's health is on its own page. | … the worker assesses a table only where its policy turns maintenance on. | Old tab name, and it repeats O1 |
| O3 | same, "Every table, one mark" panel meta (l. 45) | above: in shape, height is data files, log scale · below: queued, depth is score | above the line: in shape, taller for more data files (log scale) · below: queued, deeper for a higher score | "height is data files" doesn't read as a sentence |
| O4 | `queue.js` `iceEstate`, `.ice-cap` (l. 200-201) | Above the line: in shape, height is data files, log scale. Below: queued, depth is score. Each namespace opens its page; each queued table its Maintenance tab. | Above the line: tables in shape. Below: tables queued for tonight. Click a namespace or a queued table to open it. | The meta already gives the scale; the caption says what you can do |
| O5 | `overview.js` `auditKpi`, list row (l. 126) | In the deployment | Across all catalogs | "Deployment" is the operator's word |
| O6 | same, list row and values (l. 128-129) | Archive chain checked · {time} / broken / not yet / no archive | Archive chain · intact at {time} / broken / not checked yet / no archive | The label read as a yes/no question; the value answered with a time |
| O7 | `overview.js` `latencyKpi` (l. 146) and `health.js` `verdict` (l. 68) | none in the window | no requests in the last day | "The window" is never defined on the page |

### Maintenance

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| M1 | `nights.js` `lastNightPanel`, empty line (l. 49) | No compaction Job or orphan scan ran that night; the night's round-the-clock work is counted below. | No compaction or orphan scan ran that night. Work done at other hours is counted below. | "Job" is the Kubernetes object; "round-the-clock work" is unclear |
| M2 | same, footnote (l. 47-48) | Dashed line: the window's end. A job cut off there keeps every file group it finished. A job names its table only if you may read it; every catalog's jobs are drawn. | Dashed line: the end of the cleanup window. A job cut off there keeps the work it finished. Jobs on tables you may not read are drawn without a name. | Says what the reader sees, not how it's drawn |
| M3 | `nights.js` `gantt`, svg `aria-label` (l. 73) | … {n} compaction Job … | … {n} compaction jobs … | Rule 4 |
| M4 | `queue.js` `REASONS.deletes`, long form (l. 57-58) | Deletes: more delete files than the policy's share of its data files, which every read applies. | Deletes: more delete files than its policy allows for its data files; every read has to apply them. | The clause order hid the point |
| M5 | `queue.js` `emptyQueue`, assessed case (l. 344-345) | No table you may read in {catalog} has small files, deletes past its policy's share of its data files, or equality deletes, as the worker last assessed them. | As the worker last assessed them, no table you may read in {catalog} has small files, too many deletes or equality deletes. | Shorter; the policy detail is on each table |
| M6 | `health.js` `workerPanel`, empty (l. 331) | The worker has reported no scan. | The maintenance worker has not reported a scan. Check that it is running. | An empty state with a next step (operators only) |

### Governance

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| G1 | `governance.js` `summaryPage`, `sub` (l. 64-67) | Classification, owner, retention class and the regulated flag are set here in the console, on the Governance tab of a catalog, namespace, table or view, each recorded with who set it and at which level; an outside data catalogue can push them too, if one is ever connected. The catalog enforces them, with holds and freezes, on every load, commit and maintenance job. | Classification, owner, retention class and the regulated flag are set on the Access tab of each namespace, table or view, and for the catalog itself under Holds and freezes, each recorded with who set it. The catalog enforces them, with holds and freezes, on every load, commit and maintenance job. | Points to a tab that no longer exists; the "outside data catalogue" clause is speculation |
| G2 | same, regulated areas footnote (l. 87) | A table in a regulated area is refused everything but describe until it has a classification of its own. | Until a table in a regulated area has its own classification, people can find it but not read or change it. | Privilege jargon in prose |
| G3 | `governance.js` `grantsPage`, `sub` (l. 212-213) | … the catalog refuses a grant that would break that, and its refusal shows here as it answered. | … the catalog refuses a grant that would break that. | Builder voice |
| G4 | same, list footnote (l. 179) | Type a group in the form to list its grants; reading another group's grants needs manage-grants. | Type a group in the form to list its grants. | Only people with `manage-grants` see this tab (S3) |

### Audit

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| A1 | `audit.js` `auditPage`, `sub` (l. 92-93) | Every read, change, credential, access refusal and file deletion in this catalog, newest first, from the audit tables. Each record opens to the event as Kafka holds it, with its request id and its place in the hash chain. | Every read, change, credential, access refusal and file deletion in this catalog, newest first. Open a record for the full event, its request id and its place in the hash chain. | F10 named this sentence |
| A2 | `audit.js` `summaryKpis`, records KPI (l. 127) | {n} in the deployment | {n} across all catalogs | Rule 5 |
| A3 | `audit.js` `countPanels`, By kind footnote (l. 176-178) | {from} to {to}, as the audit tables hold them. Each type shows its kind in the feed. The server's own counters on Service differ: the writer leaves its own records out, and a record sent to the fallback log reaches neither. | {from} to {to}. Choose a type to show its records in the feed. Service counts differently, so its figures may not match these. | The reader needs to know the figures differ, not the mechanism |
| A4 | `audit.js` `feedPanel`, status line (l. 250-252) | {n} records shown, newest first. Read from the audit tables as committed at {time}, so about a minute behind. Every record after {t} was searched. | {n} records shown, newest first, up to {time}. Records arrive here about a minute after they happen. Searched back to {t}. | Plain order: what you see, how fresh, how far back |
| A5 | `audit.js` `chainPanel`, Chains fact (l. 155) | {n} verified, one per partition of the audit topic | {n} verified | Topic partitions are plumbing; keep that detail as the value's `title` |
| A6 | `audit.js` `recordDetail`, Hash chain (l. 369) | not verified yet: acknowledged by Kafka, and the next hourly check reaches it | not verified yet: the next hourly check will reach it | Rule 4 |
| A7 | `audit.js` `chainLink` (l. 380) | Not archived yet · the archive consumer has not written its segment | Not archived yet · the archive has not received it | Rule 4 |
| A8 | `audit.js` `feedRow` badge (l. 336), By kind Type cell (l. 172-173); `panels.js` `feedRow` badge (l. 569); `refs.js` ref record badge (l. 699) | `nuna.catalog.commit` (the full record type) | `commit` (the type after `nuna.catalog.`, which `short()` already gives), with the full type as the badge's `title` | The prefix repeats on every row; the full type stays one hover away for a SIEM search |

### Service

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| S1 | `health.js` `catalogHealth`, eyebrow (l. 83, 101) | deployment · the last day, to now | all catalogs · the last day | Matches the document title ("all catalogs") |
| S2 | same, refused notice (l. 85) | Catalog health is shown to platform operators only. Your groups do not include one. The catalog answered: | Service is shown to platform operators only, and your groups include none. The catalog answered: | The page is called Service now |
| S3 | `health.js` `SLOS` labels and short labels (l. 20-22) | Loads and exports answered 200 · Loads answered 304 · Commits answered 200 · (short) Loads within · 304s within · Commits within | Table loads and exports · Loads that found no change · Commits · (short) Loads within · Unchanged loads within · Commits within | Status codes as labels (rule 2); keep each code in the row's `title` |
| S4 | `health.js` "Audit to Kafka" footnote (l. 122) | A failed publish refuses the request it audits (D23). | If an audit record cannot be published, the request it records is refused. | Decision-ledger number in copy (rule 4) |
| S5 | `health.js` `storePanel`, first footnote (l. 268-269) **TD** | Each server checks its database's durability when it starts, and starts despite a finding only when `nuna.database.allow-unsafe` names it. | Each server checks its database's durability when it starts, and starts despite a finding only when the deployment's settings allow it. (Key in the `title` of "settings".) | Settings key in body (rule 2) |
| S6 | same, PITR footnote (l. 270-274) **TD** | The point-in-time recovery window is the one this deployment declares in `nuna.governance.pitr-window`, not read from the database. The catalog relies on it: orphan removal deletes only files older than it, and a dropped table is kept longer than it, so a database restored to any point within it never names a deleted file. The database's own backups must keep at least that long; the catalog cannot check that they do. | This window is what the deployment declares, not something read from the database. Orphan removal deletes only files older than it, and dropped tables are kept longer, so a database restored within it never names a deleted file. Keep the database's own backups at least this long: the catalog cannot check. (Key in the `title` of "declares".) | Settings key in body; ends on the action |
| S7 | `health.js` `drPanel`, never-verified footnote (l. 229-230) | … They are left out of the most and the longest. | … They are left out of Most versions behind and Longest lag. | Name the figures as the panel labels them |
| S8 | same, partial-read footnote (l. 231-232) | Read the first {n} by id; the most and the longest are among them. | Only the first {n} tables were checked; Most versions behind and Longest lag are among those. | Same |

---

## Catalog

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| C1 | `browse.js` `catalogPage`, title row (l. 229) | `catalog` (a `<code>` beside the name) | catalog (plain muted text: `h('span', {class: 'muted'}, 'catalog')`) | Rule 1: a kind word isn't a name |
| C2 | `browse.js` `contentsTable`, empty state (l. 185-186) | Nothing in this namespace / No namespace in this catalog · Or nothing you may describe: the catalog lists only what your groups may see. | (heads unchanged) · Or nothing your groups may see. If you expected something here, ask a grant admin. | Privilege jargon; gives a next step (namespaces too) |
| C3 | same, footnote on main (l. 194, 197-198) | Only what your groups may describe is listed. Format, snapshots and last commit are what the catalog keeps of each table's current metadata; its health, governance and history are on its own page. A namespace counts the tables beneath it that you may describe. | Only what your groups may see is listed. Open a table for its maintenance, access and history. | Rule 7: one sentence the reader acts on (namespaces too) |
| C4 | same, footnote through a ref (l. 194-196) | Only what your groups may describe is listed. The catalog keeps format, snapshots and last commit for main's metadata only, so through @{ref} they are left out: open a table to read it as the ref has it. Maintenance and governance are main's, and bind the ref too. | Only what your groups may see is listed. Through @{ref}, format, snapshots and last commit are left out: open a table to see it as @{ref} has it. | The band already says what's main's |
| C5 | `browse.js` `onRefCell`, unchanged table (l. 128) | as at the point | not changed on @{ref} | "The point" is never defined on this page |
| C6 | `browse.js` `catalogProperties`, notice (l. 265-270) | **Set in the deployment's configuration.** The Iceberg REST API has no catalog properties of its own, and the console sets none. A catalog's owner and classification are on its governance, and how its tables are compacted, expired and cleaned is its maintenance policy. The profile's keys and role are never shown. | **Set when the deployment was configured; they can't be changed here.** The catalog's owner and classification are under Governance, and how its tables are compacted, expired and cleaned up is its maintenance policy. Storage keys and roles are never shown. (Links stay on "Governance" and "maintenance policy".) | API name in body; leads with what the reader can't do |
| C7 | `tree.js` filter, no match (l. 177) | No name listed so far in {c} matches. | No open namespace in {c} holds a matching name. Open more of the tree, or use Go to a path. | Gives a next step (namespaces too) |

## Namespace

The namespace page also uses C2, C3, C4 and C7, and the Access and Audit rows under Table (T8 to T13).

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| N1 | `browse.js` `propertiesTab`, `needs` (l. 344), shown by `settings.js` footnote (l. 181) | A change is the properties update an engine sends, and is audited like any other. Needs `modify` here. | A change here is checked and audited like an engine's. Needs `modify`. | Tells the reader the consequence, not the mechanism |
| N2 | `settings.js` `namespaceNote`, owner (l. 92-93) | Not the owner the catalog enforces: that is the governance owner. (link "governance owner") | Not the owner the catalog enforces: that one is on the Access tab. (link "Access tab") | Tab renamed |

## Table

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| T1 | `table.js` `healthSummary`, through a ref (l. 314) | Maintenance is assessed and run on main only. Main's, for this table. | Maintenance runs on main only; this is main's. | The second sentence didn't parse |
| T2 | `table.js` `schemaTab`, notice (l. 423-424) **not words** | **Iceberg keeps no date for a schema.** The commit that added one is in this table's audit trail, with who made it. | **Iceberg keeps no date for a schema.** The commit that added one is on History, with who made it. (Link to the History tab, not Audit.) | Audit is hidden from most roles (S3); History has the commit and its author |
| T3 | `table.js` `historyTab`, Commits footnote (l. 611-612) | Each row is a commit that wrote one metadata file, from the catalog's commit journal, with who made it and the snapshot it added. The journal keeps every commit; the table keeps the files listed under Kept. | Each row is one commit: who made it, what it changed and the snapshot it added. The catalog keeps every commit, even after the table's own list drops its file. | Says what a row is in the reader's terms |
| T4 | same, Kept panel label (l. 643) | `write.metadata.previous-versions-max` (as the fact's label) | Earlier metadata files listed (the setting's own label), with the key as the label's `title` | Rule 3: use the label in running text |
| T5 | `table.js` `changeWords` (l. 703-705), also `panels.js` `describe` commit (l. 533-534) | add-snapshot, set-snapshot-ref, set-properties … (Iceberg's update action names) · Took @{b}'s version {n} as main's next version, in the merge of @{b} | snapshot added, properties set … from the word list under this table · Merged @{b} (its version {n}) | Engine terms as the Change column's words |
| T6 | `health.js` `tableHealthTab`, verdict line (l. 348) | Score {n}: small-files, equality-deletes (server keys) | Score {n}: small files, equality deletes (each from `REASONS[r][1]`, lower-cased) | Server keys as words |
| T7 | same, request help (l. 371) | Now, inside the cleanup window or outside it. | Runs at the worker's next scan, even outside the cleanup window. | Fragment; says when it runs |
| T8 | `table.js` `refusedLoad` notice (l. 54); also the view page | **{others} are checked on their own.** The catalog checks each tab separately, so one refusal never closes the others. | **You may still open {others}:** each tab is checked on its own. | Leads with what the reader can do |
| T9 | `panels.js` `grantsTab`, grant form footnote (l. 520-521) | Needs `manage-grants`. A group that manages grants holds no other duty, and data and tags never share a group: the catalog refuses a grant that would break that, and the refusal shows here as it answered. | A group that manages grants holds no other duty, and data and tags never share a group: the catalog refuses a grant that would break that. | The form is drawn only for grant managers; builder voice |
| T10 | `panels.js` `governanceTab`, effective facts labels (l. 145) and Attributes table's Attribute cell (l. 172) | classification · owner · retention-class · regulated · row-column-policy | Classification · Owner · Retention class · Regulated area · Row and column policy (each with its key as `title`) | Server keys used as labels |
| T11 | `refs.js` `refNote`, `access` and `grants` (l. 251, 253) | … whoever may write it on main may write it there; a grant cannot name a branch yet (item 113). · … and no one else: a grant cannot name a branch yet (item 113). | … whoever may write it on main may write it there. A grant cannot name a branch. · … and no one else: a grant cannot name a branch. | Backlog number and a promise in copy (rule 4) |
| T12 | `panels.js` `auditTab`, "What was read" panel (l. 615-621) **TD** | What was read · Topic {topic}, partition {p} · Offsets {a} to {b} · Scan limit {n} · Complete: Yes: it reaches the oldest record the topic still holds. · Read from {topic}, partition {p}, offsets {a} to {b}. | How far back this goes · Complete: Yes: every record still kept is shown. (The "No." line stays.) Topic, Offsets, Scan limit and the "Read from" line move into Technical details. | Kafka positions on an object's tab (F10) |
| T13 | same, Archive panel (l. 639-640) | … Each record's place in the chain is shown with the catalog-wide feed. | … Each record's place in the chain is on the Audit page. | "The catalog-wide feed" isn't a name the reader sees anywhere |
| T14 | Settings drawer: `table.js` `propertiesTab` `needs` (l. 273), shown by `settings.js` footnote (l. 181) | A change is a commit to the table{ on @ref}, as an engine makes it, so every commit rule applies, and is audited like any other. Needs `modify` here. | A change here is a commit to the table{ on @ref}: the same rules apply as to an engine's commit, and it is audited. Needs `modify`. | One clause per idea |
| T15 | Settings drawer: `settings.js` "Every property" meta (l. 182) | Iceberg REST, on {the table} | on {the table}, as engines read them | API name in a caption |
| T16 | Settings drawer: `settings.js` target file size, `explain` end (l. 44-45) | … Compaction aims for its own target, the maintenance policy's compaction.target-file-size-bytes. | … Compaction aims for its own target, set in the maintenance policy. | Rule 3 (the "See the policy" link follows) |
| T17 | Settings drawer: `settings.js` `change` and `reset` answers (l. 216, 226) | {key} is {value} · {key} is reset | {label} is now {value} · {label} is back to its default | The answer names what the person clicked |

**T5 words**, one per Iceberg update action. When a commit holds `add-snapshot`, leave out `set-snapshot-ref`: that
is main moving to the new snapshot. Any action not listed shows as it is.

| Action | Words |
|---|---|
| add-snapshot | snapshot added |
| remove-snapshots | snapshots expired |
| set-snapshot-ref / remove-snapshot-ref | ref moved / ref removed |
| add-schema / set-current-schema / remove-schemas | schema added / schema changed / old schemas removed |
| add-spec / set-default-spec / remove-partition-specs | partition spec added / partitioning changed / old specs removed |
| add-sort-order / set-default-sort-order | sort order added / sort order changed |
| set-properties / remove-properties | properties set / properties removed |
| set-location | location set |
| upgrade-format-version | format upgraded |
| assign-uuid | id assigned |
| set-statistics / remove-statistics / set-partition-statistics / remove-partition-statistics | statistics updated |
| create / register / revive (`changeWords`' own) | created / registered / restored |

## View

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| V1 | `table.js` `viewPage` → `refusedLoad(error, 'Access and Audit', …)` (l. 769) | **Access and Audit are checked on their own.** The catalog checks each tab separately, so one refusal never closes the others. | **You may still open Access and Audit:** each tab is checked on its own. | As T8 (one change serves both) |

## Branches and tags pages (`refs.js`)

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| R1 | `refsTab`, no-create footnote (l. 417-418) | **Making, renewing, deleting and merging need create on {c}.** Your groups hold none, so the console offers you none of them; reading through a ref needs only what reading main needs. | **Making, renewing, deleting and merging need create on {c}, which your groups don't hold.** You can still read any branch or tag. | Leads with what the reader can do |
| R2 | `endedPanel`, held row (l. 450) | Gone from every route. Its name is held until the worker ends it on its next run… | No longer readable. Its name is held until the worker ends it on its next run… | "Route" is the builder's word (rule 5) |
| R3 | `refPage`, not-live note (l. 578) | … It is gone from every route, but holds its name until the worker ends it on its next run. … | … It can no longer be read, but holds its name until the worker ends it on its next run. … | Same |
| R4 | `refsExplain`, "How engines reach a ref" (l. 477-478) | A tag lists only the read endpoints in its `/v1/config`, so an engine refuses its own write before it is sent. … | A tag offers engines only its read endpoints, so an engine refuses its own write before sending it. … | Route in body (rule 2) |
| R5 | `changesTab` (l. 599, 657, 659); merge steps (l. 821) | … on the {n} you may describe · None you may describe. · And {n} more you may not describe. · {n} more tables you may not describe … | … on the {n} you may see · None you may see. · And {n} more you may not see. · {n} more tables you may not see … | Rule 5 |
| R6 | `recordTab`, "Where these records are" (l. 680-682) | A change to a ref is one `nuna.catalog.ref` record whose subject is the ref's id, {id…}, so a deleted ref's name used again never shares its history. Commits and reads through the ref name it in their `branch` field. | Each change to @{name} is one audit record, kept under the ref's own id ({id…}), so a later ref with the same name never shares its history. Commits and reads through @{name} name it too. | Record type and field name are plumbing (A8 shows the type) |
| R7 | `mergeTab`, steps (l. 822) | … ends: its name is free and `{c}@{name}` answers 404. | … ends: its name is free and `{c}@{name}` can no longer be read. | Rule 2 |
| R8 | same, audit step (l. 824) | The audit gets one `nuna.catalog.ref` record (merged, {n} tables) and one commit record per table, on main. | The audit records the merge once, and one commit for each table, on main. | Rule 4 |
| R9 | same, rights note (l. 828-829) | **Your groups hold no write on {tables}.** The catalog would refuse the merge with 403, so the console offers none. | **Your groups hold no write on {tables}.** The catalog would refuse the merge, so the console doesn't offer it. | Rule 2 |
| R10 | same, footnote (l. 830-831) | … If main commits to one of them between this preview and the merge, the catalog plans again, and the new conflict refuses the merge with 409. | … If main commits to one of them between this preview and the merge, the catalog checks again, and a new conflict refuses the merge. | Rule 2 |

## Sign-in

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| I1 | `app.js` `signInPage`, OIDC line (l. 282-283) | Your organisation's sign-in page opens. The console keeps the token in this tab only, and the catalog checks it on every call. | Your organisation's sign-in page opens, then brings you back here. Your sign-in lasts in this tab only. | Says what happens to the person, not the token |
| I2 | same, dev token heading (l. 298) **TD** | **Paste a bearer token.** Shown because this server sets `nuna.ui.dev-token-login=true`. | **Paste a bearer token.** For development only: this server allows signing in with one. (Key as the line's `title`.) | Settings key in body |
| I3 | same, under the box (l. 299) | Kept in this tab only and sent as a bearer token; the server checks it on every call. | Kept in this tab only. | Says the one thing that matters to the person |
| I4 | same, no sign-in set up (l. 310-312) **TD** | No sign-in is configured on this server. An operator sets `nuna.ui.oidc.issuer` and `nuna.ui.oidc.client-id` (or, for local development only, `nuna.ui.dev-token-login=true`). | No sign-in is set up on this server. Ask whoever runs nuna to connect your organisation's sign-in. (The three keys go into Technical details.) | A person can't act on settings keys; an operator opens the details |
| I5 | `app.js` `boot`, failure title (l. 726) **TD** | The console could not read its settings, /ui/config.json | The console could not start: it could not load its settings. (Route into Technical details; `failure()` gives no details box when there is no status, so pass it as `note` or add it there.) | Route in title |
| I6 | `app.js` `boot` (l. 739) | The token has expired. Sign in again. | Your sign-in has expired. Sign in again. | Plain words |
| I7 | `api.js` `send`, 401 (l. 47) | The catalog did not accept the token (401). Sign in again. | Your sign-in has expired or was not accepted. Sign in again. | Status in body |
| I8 | `auth.js` IdP messages (l. 95, 129, 144) | The IdP's discovery document answered {status} · The IdP refused the sign-in: {error} {description} · The IdP's token endpoint answered {status} {error} | Your organisation's sign-in service did not answer as expected ({status}). · Your organisation's sign-in refused: {description, else error}. · Signing in did not finish: your organisation's sign-in answered {status} {error}. | "IdP", "discovery document" and "token endpoint" are protocol terms |
| I9 | `auth.js` `signOut` (l. 184-189), and `startSignIn` mismatch (l. 132) | Signed out here, but your session at {host} was not ended: {message}. Signing in again may not ask for a password. · … was not ended: its discovery document names no end_session_endpoint. Signing in again may not ask for a password. · The sign-in answer does not match a sign-in this tab started; start again. | Signed out of nuna, but {host} did not end your session there ({message}). Signing in again may not ask for a password. · Signed out of nuna. {host} offers no way to end your session there, so signing in again may not ask for a password. · This sign-in was not started in this tab, or has expired. Start again. | Protocol terms; "here" is vague |

## Dialogs

| # | File · locator | Now | New | Why |
|---|---|---|---|---|
| D1 | `index.html` `#goto` `.goto-foot` | ↑ ↓ to move, Enter to open. Matches every word you type, in any order, among the paths listed so far and tonight's queue; a full dotted path opens even when it has not been listed. | ↑ ↓ to move, Enter to open. Finds names the console has listed so far, and tonight's queue. A full path, such as markets.fx.rates, opens even if it isn't listed yet. | Shorter; the example teaches the form |
| D2 | `index.html` `#goto-h` (first paint) | Loaded so far | Listed so far | `app.js` says "listed" a moment later |
| D3 | `requests.js` `INTRO.compaction` (l. 85-87) | The worker compacts {path} as one Job at its next scan, inside its cleanup window or outside it, in the first of its compaction slots to come free, whatever its last assessment says. The Job stops at the request's deadline and keeps every file group it finished. | The worker compacts {path} at its next scan, in or out of its cleanup window, as soon as a compaction slot is free, whatever its last assessment says. It stops at the request's deadline and keeps the work it finished. | "Job" (rule 4); shorter |
| D4 | `requests.js` `INTRO['orphan-scan']` (l. 88-90) | … records each file nothing references, older than its policy's orphan-removal.older-than, for deletion once the database's PITR window has passed. A scan the request's deadline stops records nothing. | … records each file nothing refers to that is older than its policy's orphan-removal age, for deletion once the database's recovery window has passed. A scan stopped at the deadline records nothing. | Key and acronym in a sentence (rule 3) |
| D5 | `requests.js` `askDialog` footnote (l. 98-99) | Needs `modify` on the table. Refused under a DR freeze, an orphan scan under a legal hold, and a job the table's maintenance policy turns off. | Needs `modify` on the table. The catalog refuses it during a DR freeze, refuses an orphan scan while the table is on legal hold, and refuses a job the table's policy turns off. | The list read as one run-on clause |
| D6 | `panels.js` `setRetentionClass`, none (l. 250-251) **TD** | This server has no retention class to choose from. A platform operator names them in its configuration, nuna.governance.retention-classes. | No retention classes are set up on this server yet. Ask a platform operator to add them. (Key as the line's `title`.) | Settings key in body |
| D7 | `governance.js` `holdOnPath` (l. 284) | Name a namespace or a table. The hold carries its reason for as long as it stands. | Name a namespace, table or view. The hold carries its reason for as long as it stands. | The Kind field offers view too |
| D8 | `refs.js` `deleteDialog` (l. 992-994) | Engines reading `{c}@{name}` get 404 from then on. … · … Engines reading `{c}@{name}` get 404. (three variants) | Engines can no longer read `{c}@{name}`. … (same in all three variants) | Rule 2 |
| D9 | same, after delete (l. 1002) | It is gone from every route; its record stays in the audit feed. | It can no longer be read; its record stays in the audit feed. | Rule 5 |
| D10 | `refs.js` `mergeDialog`, answer's line (l. 878) | {n} tables moved in one transaction; the branch ended and {c}@{name} answers 404. | {n} tables moved in one transaction; the branch ended, and {c}@{name} can no longer be read. | Rule 2 (inside an answer box: see the end) |

## Monospace (`style.css`)

| # | Selector | Now | New | Why |
|---|---|---|---|---|
| Y1 | `.panel-h .meta, .panel > h2 .meta` (l. 205) | `var(--f-mono)` | `var(--f-ui)`, tabular figures kept | Meta lines are sentences ("neediest first, as the worker will start them") |
| Y2 | `.ph .eyebrow` (l. 187) | `var(--f-mono)` | `var(--f-ui)` | "lakehouse · maintenance", "Monday 23 November 2026" are words |
| Y3 | `.badge` (l. 236) | `var(--f-mono)` | `var(--f-ui)`, tabular figures; `.badge.ref-b` and `.badge.ref-t` (ref names) stay mono | Badges hold "namespace", "view", "look up", classifications |
| Y4 | `.kpi-v small` (l. 219) | `var(--f-mono)` | `var(--f-ui)` | Holds "records", "classified", "none assessed yet" |

`.refusal-msg` stays mono: it holds the server's own message, which item 188 kept word for word.

---

## Not words: file separately

1. **Empty queue with maintenance off links to the policy** (`queue.js` `emptyQueue`, `overview.js` "Nothing queued"):
   after "Maintenance is off in {catalog}'s policy…", add **Edit the catalog's policy**, for people who may modify
   the catalog.
2. **Schema notice links to History** (T2).
3. **Maintenance policy keys get labels** (`maintenance.js` `policyPanel` and `editPolicy`, `health.js` `policyFacts`,
   `table.js` snapshot expiry facts): "Snapshot expiry", "Keep snapshots for", "Keep at least", "Orphan removal",
   "Remove orphans older than", "Compaction", "Target file size", "Fewest input files", "Delete-file share",
   "Manifest rewrite", "Fewest manifests", each with its key as `title`. This needs an 11-entry map and the same
   choice for the edit dialog's field labels. Until then, rule 3 lets the keys stand.
4. **Tree filter empty state gets a Go to a path button** (C7 gives the words).

---

## Rows per page

| Page | Rows |
|---|---|
| Header | 1 |
| Overview | 7 |
| Maintenance | 6 |
| Governance | 4 |
| Audit | 8 |
| Service | 8 |
| Catalog | 7 |
| Namespace | 2 |
| Table (with the Settings drawer) | 17 |
| View | 1 |
| Branches and tags pages | 10 |
| Sign-in | 9 |
| Dialogs | 10 |
| Monospace (CSS) | 4 |
| **Total** | **94**, of which 7 TD and 1 not words, plus 4 not-words items to file |

## Rows I'm unsure of

- **S3** (Service's latency labels): operators read 200 and 304 fluently, so the codes may be worth keeping visible.
  I applied rule 2 and kept each code in a `title`.
- **R7, D10** (merge, "answers 404"): D10 sits inside the merge's answer box, which item 188 reworded. I included
  both for consistency with rule 2. Drop D10 if 188's box is final.
- **C5** ("not changed on @{ref}" for "as at the point"): accurate for a table the branch never touched, but it
  loses the design's idea of "the point" (chapter 6, 6.4). Keep the old words if the point is taught elsewhere on
  that page.
- **M1**: I read "the night's round-the-clock work" as work done outside the cleanup window. If it means something
  else, keep the second sentence as it is.
- **A3**: my words drop why Service's counts differ (the writer's own records, the fallback log). That reason helps
  an operator reconciling the two, so it could go in the footnote's `title`.
- **I8**: keeps the status number in parentheses in the sign-in notices, because there's no Technical details there
  and support needs the number.
- **Left alone on purpose**: the refs change list's meta "{n} tables with pointers of its own" (`refs.js` l. 655),
  which F10 quoted. It's a refs-page caption, and item 188 owns those.
