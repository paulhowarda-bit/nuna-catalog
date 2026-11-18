# 2026-11-18, one backlog log with effort

Paul (2026-11-18): "I need a backlog that I can review. It needs to be in a single log, it also needs each item
tagged with the estimated effort to fix."

- `BACKLOG.md` at the repo root is the whole backlog in one file: the open items in a table (number, effort, title,
  why that size, date added), then each open item in full, then the done and dropped ones with their dates.
  `./backlog.sh log` makes it from `backlog/<n>.md` in about half a second.
- `land.sh` merges with `--no-commit`, writes `BACKLOG.md` from the merged item files, and commits both as the one
  merge, so the log is as current as `main` and nobody edits it by hand. The item files stay the source: one
  hand-edited shared file would bring back the merge conflicts that `status/` and `land.sh` removed on 2026-11-15. If
  the log cannot be made, the merge is aborted and `main` and its checkout are left as they were. A branch with
  nothing `main` lacks is now refused.
- Every item has an `Effort:` line: S (under 2 hours), M (up to a day), L (2 to 5 days) or XL (more than 5 days), then
  why, and what it waits on. The 33 open items were estimated from their text: 6 S, 14 M, 9 L, 4 XL. `add` writes
  `Effort: ?`, which the log counts as not estimated.
- `backlog/README.md` defines the scale and the line; `CLAUDE.md` says where the log is, that items need `Effort:`,
  and that an in-place change to `backlog/` regenerates `BACKLOG.md` itself.
- Checked in a throwaway repo: 34 checks on the log and the landing (the log's table, counts, escaping, full text and
  done list; one merge commit holding `BACKLOG.md`; a hand-edited copy replaced; a failing log leaving `main` and
  its checkout untouched, before and after `BACKLOG.md` exists; the turn, refusals and five landings at once), and
  the earlier claim and landing suites (25 and 28 checks) again.
- Not done: the overnight-report scheduled task does not yet write an `Effort:` line in the items it adds; that
  changes its prompt, which waits on Paul's word.
