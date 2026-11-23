# 2026-11-23, the overnight run tests in a worktree, not a clone (backlog 166)

Paul asked why the overnight run used a clone when every session works in a worktree, and said "ok do it".
`overnight.sh` now makes `.overnight/worktree`, a detached and locked worktree of the main checkout, and resets it to
main's tip each night; `nightly.sh` and `heavy.sh` find the run's lock through `--git-common-dir` alone; CLAUDE.md names
the worktree. Waited for items 164 and 165, which held `overnight.sh`.

Checked: a dry run in a scratch home (`NUNA_OVERNIGHT_HOME=<scratch> ./overnight.sh --dry`, exit 0) made the worktree
detached at 786c98b and locked, ran each leg's plumbing in it, and the nightly left the run's lock to the run. The fast
build passed. Not checked: a real night; tonight's run is the first in the worktree.
