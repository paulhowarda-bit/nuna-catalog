# Backlog

One item per file, `backlog/<n>.md`. `./backlog.sh` at the repo root lists the items, gives out numbers, and claims
and releases items. Started 2026-11-14 (Paul: agents claim an item so no other agent takes it, work it in their own
worktree, update the backlog, land, then release the claim).

## An item

```
# 7. The Ceph leg skips DeletePolicyCephTest

Status: open
Added: 2026-11-14 by Testing Agent
Touches: catalog-objectstore/, catalog-maintenance/src/test/

What is wrong or missing, with the evidence: a command and its output, or a file and line.
What done looks like: the test, build or check that will show it.
```

- `Status:` is `open`, `done` or `dropped`. A done item keeps its file: `./backlog.sh done` adds a `Done:` line with
  the date and branch, and the agent writes beneath it what was done and what, if anything, is left.
- `Touches:` names the folders and files the work will change, separated by spaces or commas; `none` when it changes
  only its own item file and a `status/` entry. Claims use it to keep two agents out of the same area.
- Only Paul drops or defers an item, and only he answers a question an item puts to him (titles say "Paul
  decides"). An agent that thinks an item should go, or reaches a choice that is his, asks him, one question at a
  time; it never drops, defers or rescopes an item itself.
- Numbers are never reused once an item is on main, and items are never deleted or renumbered.

## Recording Paul's decisions

The backlog says what is open; `decision-ledger.md` says why things are as they are, and it is where work looks for
his decisions. A decision kept only in an item file or in a chat never reaches work. So when Paul decides anything
about an item (an answer, a drop, a deferral, a change of scope):

1. Record it in `decision-ledger.md`, quoting him with the date: an amendment to the D row it touches
   (`**Amended 2026-11-15 (item 15):** …`), or a new D row when none fits.
2. In the item, beneath `Status:`, add `Decided: <date>, <D row>`, and set `Status: dropped` if he dropped it.
3. Land both in one branch.

## Adding an item

1. In your worktree, `./backlog.sh add "<title>" ["<who asked>"]`. It takes the next free number atomically, so two
   agents adding at once never get the same one, and writes the header of `backlog/<n>.md`.
2. Write the `Touches:` line and the body: what is wrong or missing, the evidence, and what done looks like.
3. Commit and land it like any branch. A branch that changes only Markdown lands without a build.

Others can claim the item once it is on main. An agent that finds something while working on another item adds it on
its own branch, and it lands with that branch.

## Working on an item

1. Start your worktree from the current `main`.
2. `./backlog.sh claim <n>` in that worktree. The claim fails if the item is already claimed, or if another agent
   holds an item whose area overlaps this one's; it prints the holder: take another item. An item with no `Touches:`
   line needs its areas named, `./backlog.sh claim <n> demo/ CLAUDE.md`. Never work on an item you have not claimed,
   and claim only what you are about to start.
3. Do the work, changing only the area you claimed, and commit it. Record what you did in `status/`
   (`status/README.md`).
4. `./backlog.sh done <n>`: it sets `Status: done` and adds the `Done:` line. Write what was done beneath it, add a new
   item for anything left over, and commit.
5. Land the branch (`CLAUDE.md`, Landing).
6. `./backlog.sh release <n>`, from the worktree or the main checkout: the claim belongs to the session, not the folder.

If you stop without finishing, release the item and say why in your report; it stays open.

A worktree subagent cannot land. The session that starts it claims the item before starting it, from its own
worktree, and releases it after landing the subagent's branch.

## How claims work

- A claim is the git ref `refs/backlog/claims/<n>`; a number given out is `refs/backlog/ids/<n>`. Each points at a
  short record: item or title, session, branch, worktree, time. Read one with `git cat-file -p refs/backlog/claims/<n>`.
- Every worktree of the clone shares these refs, and git creates a ref that must not exist yet atomically. Of twenty
  agents claiming one item at once, exactly one wins; twelve adds at once from three worktrees got twelve numbers
  (both tested 2026-11-14).
- A claim also records the item's area. Two areas overlap when one is, holds or lies inside the other (`demo/` and
  `demo/k8s/ports.yaml`), compared without case; `.` overlaps everything and `none` nothing. While claims compare
  areas they take turns through `refs/backlog/claiming`, so of six agents claiming six items that all touch `docs/`
  at once, exactly one wins (tested 2026-11-15). Claims made before areas existed record none and block nothing.
- The holder is the Claude session (`CLAUDE_CODE_HOST_SESSION_ID`, the `local_…` id the app shows); outside Claude
  Code, the branch. Only the holder can mark the item done or release it, unless `release` is given `--force`.
- Nothing pushes the refs, and a clone on another machine has its own. Claims coordinate the agents on one machine. A
  cloud session cannot see them, so don't give backlog items to cloud sessions.

## Claims left behind

`./backlog.sh claims` prints every claim with its state:

| State | Means |
|---|---|
| finished | The item is done or dropped on main: its holder landed it but did not release it. |
| active | The worktree it was claimed from is still there. |
| abandoned | That worktree is gone, and its branch is gone or holds nothing main lacks. |
| stranded | That worktree is gone, and its branch holds commits main lacks. |

Then the numbers given out whose item is not on main: `pending` while the branch that took one remains, `lost` once
that branch is gone without landing it.

`./backlog.sh sweep` releases finished and abandoned claims, forgets numbers whose item is on main or lost, and prints
the rest. Any session may run it: it releases only claims with nothing left to lose. Run it before handing out work.

What the sweep leaves needs a look:

- **active**: find the claim's session in the app's session list (archived ones included). Archiving a session
  removes its worktree, so a claim whose worktree remains has a live session, unless the worktree was made by hand or
  the claim was made in the main checkout. If the session is gone, release with `--force`. If it is idle, ask it (or
  Paul) whether it is still on the item; release with `--force` only on that answer.
- **stranded**: read the work with `git cherry -v main <branch>`. Never merge a stale branch back to recover it. Ask
  Paul whether to finish it (a new session picks the branch up) or drop it; then release with `--force`, and delete
  the branch only once he has decided.
- **lost**: tell Paul which item was lost (the report gives its title), and add it again if he wants it.
- A branch the sweep reports as holding nothing main lacks is deleted (`git branch -D <branch>`), as every branch is
  once its work is on main.
