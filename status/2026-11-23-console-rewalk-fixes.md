# 2026-11-23, console: Atelier's re-walk after phase 2 built (items 212 to 217)

Atelier's findings from her re-walk after phase 2 (`status/2026-11-23-console-wayfinding.md`), built in one worktree
since they share the console's files. She settled the design questions first (one consultation, 2026-11-23); her
changes to the items' proposals: the Audit chip "Holds and freezes" became Governance, the regulated mark asks for a
reason, a ref's Record tab lost its badge, and the copy button reads "Copy lakehouse@dev".

- 212: a branch's Changes tab opens with whether it merges, linked to the Merge tab.
- 213: each regulated area on Governance shows its reason, who marked it and when; the governance summary carries the
  flag's provenance, setter and time; marking and clearing ask for the reason; Access says Yes or No.
- 214: one map from record type to the chips' words for badges, chips and By kind, so the counts add up.
- 215: the UI font for the chart caption, number headers, kind words, the Audit fields and a ref's dates.
- 216: no archive check without an archive; labels as words. Merged with 205's "Read from" (items 203 to 208 landed
  first, D51): a record read from the log offers no check either.
- 217: home first in the rail, one "main's" through a ref, "Copy lakehouse@dev", the catalog named on every Go to
  match, paths in the merge panel breaking at dots.

Checked:
- Fast: `GovernanceSummaryTest` (10), `ConsoleRulesTest` (2), `CatalogListTest` (12); the fast build at landing.
- `catalog-security` reviewed the summary's new fields and the free-text reason: nothing blocking, nothing newly
  visible (describe on a level already returns the same fields).
- Live in the nuna-demo stack at main 38651748, with this branch's `ui/` swapped in (`demo-cdp-swap.mjs`, a headless
  Chrome of my own on port 9344, since another session's tests had used 9333): as ana, 212's line on lakehouse@dev,
  213's columns and Access's "Yes", 215's computed fonts, 217's Go to, merge panel and tag notes; as gary, the rail
  (Audit first), By kind adding up to the All chip's 1,076, the badges, and a log record with no archive check.
- Bites recorded for each guarded check (12 new or re-recorded, `git apply --check` on all of them).

Not run: the heavy browser cases changed for these items (`ConsoleBrowserTest`, `ConsoleLabelsBrowserTest`); tonight's
heavy build runs them. Windows had 0.5 to 1.3 GB free and was paging at up to 11,000 pages a second while the demo
ran, and Docker was in use by another session's browser tests.
