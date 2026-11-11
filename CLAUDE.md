# fidice-catalog: rules for every agent

## Code changes go through a worktree

- Any agent that changes code works in its own git worktree on its own branch: a session started with `use_worktree: true`, or a subagent with `isolation: "worktree"`. Never edit code in the main checkout (`C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog`).
- Bite runs (break a check, see its test fail, restore and hash-check) count as code changes, so they run inside the agent's own worktree, never in the main checkout.
- Never push unless Paul asks.

## Builds

- `./mvnw install` is the fast build, and it tests PostgreSQL only. It leaves out tests tagged `heavy`: the whole Oracle, MongoDB, CockroachDB and YugabyteDB suites, and every fault-injection test (link cuts, lost requests or answers, stalled replication, paused or killed nodes, failover).
- `./mvnw install -Pheavy` runs every test, all five databases included, except the CAS test.
- The CAS test (`CommitSuite#exactlyOneWriterWinsEachRound`, tagged `cas`) never runs in a build, fast or heavy, and never in a build-and-test cycle: it takes too long. Paul's rule, 2026-11-10. It runs only overnight, on its own: `./mvnw install -Pnightly` runs just that test on all five backends at 10,000 rounds. Don't run it unless Paul asks.
- A new test that waits on purpose or needs a multi-node cluster gets `@Tag("heavy")`.
- `.mvn/maven.config` sets `-T 2`: two modules build at once. Not more: Docker has 8 GB, and a three-node cluster plus one other stack is what fits. The CockroachDB and YugabyteDB suites live in `catalog-store-sql-raft`, which nothing depends on, so they run beside the `core` → `server` → `load` chain instead of ahead of it.

## Landing

- Every landing uses the fast build, whatever the branch changes. Don't run `-Pheavy` outside the heavy pass below.
- When the work is finished and its build is green, land it without asking Paul:
  1. Merge the current `main` into the branch and run the fast build, `./mvnw install`.
  2. Run `git -C C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog status --porcelain`. If it prints anything, the main checkout is busy: report the branch as ready instead of merging.
  3. Otherwise run `git -C C:/Users/paulh/OneDrive/Desktop/code/fidice-catalog merge --no-ff <branch>`. Use a "Land …" message ending with the attribution line.
  4. Check that `git cherry -v main <branch>` shows only `-` lines.
- After a branch lands, the session that started the agent archives it, which removes its worktree, then deletes the branch. Neither step needs asking.

## Bites

- A branch that adds a guarded check records its bite instead of running it: `bites/<milestone>/<id>.patch`, made with `git diff` of the broken check, headed by a `Module:` line and a `Test:` line (the `-Dtest` selector that must fail). The two patches in `bites/M2/` show the form. Check it with `git apply --check`, then restore the file.
- `bites/run.sh bites/M*` applies each patch, runs its test with `-Pheavy`, expects a failure, reverts, and checks every tracked file's hash is restored.

## The heavy pass, once, after M8

- The pass was planned for after M9. On 2026-11-10 Paul moved it to after M8, before M9: M9 happens at work, and he wants a fully checked build to carry there.
- One run in a worktree: `./mvnw install -Pheavy`, then `bites/run.sh bites/M*` (every recorded bite). Fix anything that fails or does not bite.
- The pass leaves out the CAS test: at 10,000 rounds it alone ran past 10 hours across the five backends (YugabyteDB 2.2 s a round). It belongs to a scheduled overnight job in work CI (M9 in `build-plan.md`).
