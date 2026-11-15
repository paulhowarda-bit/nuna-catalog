# 2026-11-15, merge conflicts designed out

Paul: "merge conflicts should not be possible". Of 77 landings on main, 45 changed `build-plan.md`, nearly all to add
a Status entry at the same spot, and that file was in 8 of the 13 merges that conflicted. The other 5 came from
parallel work in one area (three demo branches; M5, M6 and M7 in `catalog-audit` and the server routes).

- Status entries now go in `status/`, one file per branch and day. `build-plan.md`'s section 8 is history to today.
  `CLAUDE.md`, `nightly.sh` and `overnight.sh` point there.
- `land.sh` merges a branch into main only while main is an ancestor of it, so the merge cannot conflict, and two
  landings take turns through `refs/backlog/landing`. A conflict can only meet the branch when it merges main, in
  its own worktree.
- `./backlog.sh claim` refuses an item whose area (`Touches:`, or the areas given) overlaps an item someone holds.
- Checked in scratch repos: 25 checks for areas and landing (six overlapping claims at once: one wins; five landings
  at once: main never mid-merge or conflicted, the rest land after merging main) and the earlier 28 backlog checks.
- Not covered: work started without a claim has no area, so only `land.sh` guards it; the 30 open items have no
  `Touches:` line yet, so their claims must name areas.
- An earlier design, per-file locks taken by an edit hook, was dropped before landing: it queued every agent on
  `build-plan.md`, held files for hours and cost about 2 s per edit.
