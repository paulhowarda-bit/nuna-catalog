# fidice-catalog: rules for every agent

## Code changes go through a worktree

- Any agent that changes code works in its own git worktree on its own branch: a session started with `use_worktree: true`, or a subagent with `isolation: "worktree"`. Never edit code in the main checkout (`C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog`).
- When the work is finished and its build is green, land it without asking Paul:
  1. Merge the current `main` into the branch and rerun the build.
  2. Run `git -C C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog status --porcelain`. If it prints anything, the main checkout is busy: report the branch as ready instead of merging.
  3. Otherwise run `git -C C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog merge --no-ff <branch>`. Use a "Land …" message ending with the attribution line.
  4. Check that `git cherry -v main <branch>` shows only `-` lines.
- Bite runs (break a check, see its test fail, restore and hash-check) count as code changes, so they run inside the agent's own worktree, never in the main checkout.
- Never push unless Paul asks.
- After a branch lands, the session that started the agent archives it, which removes its worktree, then deletes the branch. Neither step needs asking.
