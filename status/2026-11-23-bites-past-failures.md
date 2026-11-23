# 2026-11-23, the overnight run goes on past failing tests, and starts agents to fix them

Item 164 (Paul: "A single test failure should not stop the entire run"). `overnight.sh` runs the bites whatever the
legs' tests did, leaving out only the bites whose test class failed tonight or whose module's tests hung, and all of
them only when the heavy build failed with no failing test. Test tasks time out under `-Pheavy` (60 min), `-Pnightly`
and `-Pbites` (120 min), so a hung test fails its module and the leg goes on. The heavy build and the Ceph leg already
ran with `--continue` (item 122), and `nightly.sh` always has.

Checked in a stub Gradle project (the timeout stops and fails a hung task; `--continue` runs the rest) and with
`overnight.sh` in a stub repo, in three cases: tests failing and a module hung, the heavy build broken, a module hung
only. The fast build passed. Not checked: a real overnight run.

Item 165 (Paul: "when a test fails, an agent needs to be spawned to fix it without stopping the rest of the testing").
After each leg `overnight.sh` starts a headless Claude session for each module with a failing or hung test, at most 3
a night, in its own worktree `.claude/worktrees/overnight-fix-<date>-<module>`: it claims or adds the item and commits
a fix while the run goes on, with no build. After the run lets go of its lock, it resumes each in turn to run the fast
build and land, until 07:45. `runs/<date>/agents.md` records each; the next run removes the worktrees whose work is on
`main`. Checked in a stub repo with a stub `claude`: agents started during the run, landed after it one at a time,
pruned the next run (the one with unlanded work kept); none when the CLI is not signed in; the cap; a fix ending in an
error not resumed. The fast build passed.

Not checked: a real agent. The `claude` CLI on this PC is not signed in (`claude auth status`), so none starts until
Paul runs `claude auth login`. The 07:00 report task still files every failure itself; changing its prompt waits on
Paul's leave.
