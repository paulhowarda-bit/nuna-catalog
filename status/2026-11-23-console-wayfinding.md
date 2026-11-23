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
