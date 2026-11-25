# 2026-11-25, demo.sh builds one pinned commit (backlog 274)

`demo/demo.sh up` read the main checkout over its whole run (Gradle, Dockerfiles, `demo/k8s`, and `demo.sh` itself,
which bash reads as it goes), so a landing mid-run stopped it or mixed commits into its images (three times on
2026-11-24). Now, started in the main checkout, `up` and the other commands that read the repo (`engines`, `gen`,
`ss`, `kafka`, `cdc`, `token`, `rate`) pin the main checkout's HEAD at their start and re-execute from
`~/.nuna-demo/checkout`, a worktree detached at that commit and locked, as the overnight run keeps its own (backlog
166). Ignored files stay there, so Gradle builds incrementally; the first `up` after this builds that worktree from
the build cache. One command holds it at a time (`~/.nuna-demo/checkout.pid`); another is refused while it runs, and
a lock whose process is gone is taken over. `up` prints `building commit <sha> <subject>` and `print_access` (after
`up`, and `token`) `The demo runs commit …`, from `~/.nuna-demo/built-commit.txt`. From any other worktree demo.sh
runs in place, as before. Demo data on volumes (237) is untouched. CLAUDE.md's D49 and `demo/README.md` say so.

Checked: `demo/test-pin.sh` (new), a scratch repo whose `demo.sh` is the real preamble plus a stub `up`: a landing
during `up` leaves it at the starting commit, reading the old file both times and finishing the old script; a second
command is refused meanwhile; the lock goes at the end; the checkout is a locked worktree; a dead lock is taken over
and the next `up` runs the new tip; another worktree and `status` run in place. 7 of 7 pass. Bite: with the `exec`
disabled, the landing check fails (`marker v2`, `script v2`) and the lock outlives `up`; restored and hash-checked.

Not covered: no real `up` was run (the demo stayed up), so the first real `up` from the pinned worktree, a full
Gradle and Docker build in a new folder, is untried.
