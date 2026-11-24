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

Landed on main at 663cd1ae.

## Atelier's re-walk after items 212 to 217 (demo at 38651748, this branch's console swapped in)

She re-walked her six tasks live at 23:37 to 23:40, as ana for tasks 1 to 5 and gary for task 6, counting from the
landing.

| # | Task | Before | After phase 1 | After phase 2 | Now | Steps now |
|---|---|---|---|---|---|---|
| 1 | `rates`: who changed it last | 7 | 3 | 3 | 3 | `/`, `rates`, Enter; Overview names the author |
| 2 | Read `@sample`, then back to main | 3 | 3 | 3 | 3 | Reading, @sample, Read main instead |
| 3 | What `dev` changed, and its merge preview | 6 | 3 | 3 | 2 | Reading, What changed; the Changes tab opens with "Ready to merge" |
| 4 | Why a table is held or regulated | 3 | 1 | 1 | 1 | Governance, the regulated area's reason there too (see below) |
| 5 | Tonight's maintenance for a table | 7 | 3 | 3 | 3 | `/`, `rates`, Enter; Overview says maintenance is off in the policy |
| 6 | gary finds a grant's audit record | 6 | 5 | 2 | 2 | Grants chip, Show record (his remembered catalog cleared: he still lands on lakehouse's Audit) |
| | Total | 32 | 18 | 15 | 14 | her estimate was 14 |

Task 4 was counted as it is once the demo runs this branch's server: the demo she walked predates the summary's new
fields, so Governance showed "No reason recorded"; the reason ("demo-seed: compliance register", by stella) was on the
Access tab, and the summary route returns it (`GovernanceSummaryTest`).

Her findings, worst first:
- The regulated areas table scrolled sideways at 1440 (493 px in a 472 px card), from 213's new Marked column. Fixed on
  this branch: tables and closed in one cell; 436 px now, checked live.
- The merge verdict was worded two ways. Fixed: the Merge tab says "Ready to merge: …" too.
- Audit's Most recorded clips its Records column; Audit's headline (1,140) and its All chip (1,124) differ; the catalog
  page through a tag has an unlabelled "Copy name". Filed as backlog 220.

Not run: the heavy browser cases changed for these items (`ConsoleBrowserTest`, `ConsoleLabelsBrowserTest`); tonight's
heavy build runs them. Windows had 0.5 to 1.3 GB free and was paging at up to 11,000 pages a second while the demo
ran, and Docker was in use by another session's browser tests.
