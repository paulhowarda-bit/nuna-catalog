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
