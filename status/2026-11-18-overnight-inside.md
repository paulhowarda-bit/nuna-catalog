# 2026-11-18, the overnight folder moves inside the repo

The overnight run's folder is now `.overnight/` in the main checkout, gitignored, instead of `nuna-overnight` beside
it (backlog 63; Paul: "Move it inside", and "Scripts are enough" when asked whether the run's output should be
tracked). `overnight.sh` and `overnight-task.ps1` default to it (`NUNA_OVERNIGHT_HOME` still overrides), and
CLAUDE.md names its paths relative to the main checkout, so the same text holds at work. The registered Windows task
needs no change: it runs `overnight-task.ps1` from the main checkout, which finds the folder each time.

Checked: from this branch's worktree, `overnight.sh`'s two lines give `C:/Users/paulh/code/nuna-catalog/.overnight`
and the task script's give `C:\Users\paulh\code\nuna-catalog\.overnight`. A clone inside the main checkout has a
50-character prefix, shorter than any worktree's under `.claude/worktrees/`.
