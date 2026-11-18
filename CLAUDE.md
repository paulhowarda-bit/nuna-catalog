# nuna-catalog: rules for every agent

At work, start with `docs/install-at-work.md`: it says which facts below describe Paul's home machine.

## Code changes go through a worktree

- Any agent that changes code works in its own git worktree on its own branch: a session started with `use_worktree: true`, or a subagent with `isolation: "worktree"`. Never edit code in the main checkout (`C:/Users/paulh/code/nuna-catalog`), except in place (below).
- **In place:** when Paul says "in place", make the change in the main checkout itself, with no worktree or branch (Paul, 2026-11-14).
  - Start only if `git status --porcelain` there prints nothing; otherwise another session is working there, so ask.
  - Run the fast build as a landing would (none for Markdown only).
  - Commit straight to `main`, ending the message with the attribution line.
- Bite runs (break a check, see its test fail, restore and hash-check) count as code changes, so they run inside the agent's own worktree, never in the main checkout, even in place.
- Never push unless Paul asks.

## Backlog and claims

- The backlog is `backlog/`, one file per item, `backlog/<n>.md`. `backlog/README.md` gives the format and the whole process; `./backlog.sh list` shows the open items and who holds each.
- Add an item with `./backlog.sh add "<title>"` in your worktree (it takes the next number atomically), write a `Touches:` line (the folders and files the work will change), what is wrong, the evidence and what done looks like, then land it.
- Claim an item before working on it: `./backlog.sh claim <n>` in the worktree that will carry the work. It fails if another agent holds the item, or holds one whose area overlaps this one's (the `Touches:` line, or the areas you name: `./backlog.sh claim <n> demo/ CLAUDE.md`), so two agents never change the same code at once: take another item. Claim only what you are about to start, and change only what you claimed.
- When the work is done: `./backlog.sh done <n>`, write what was done, commit, land, then `./backlog.sh release <n>`. If you stop without finishing, release it; it stays open.
- A worktree subagent cannot land, so the session that starts it claims the item first and releases it after landing.
- Only Paul drops, defers or rescopes an item, or answers a question it puts to him. Record each decision in `decision-ledger.md` (an amendment to the D row it touches, quoting him with the date, or a new row) and add `Decided: <date>, <row>` to the item, in one branch: work reads the ledger, never the chats.
- Record what a branch did in a file of its own, `status/<date>-<name>.md` (`status/README.md`), never in `build-plan.md`: every branch adding to one log there made most of the conflicts.
- `./backlog.sh sweep` releases claims whose item is finished or whose worktree is gone with nothing left to land, and lists the rest; `backlog/README.md` says how to judge those.

## Builds

- `./gradlew build` is the fast build, and it tests PostgreSQL only. It leaves out tests tagged `heavy`: the whole Oracle, MongoDB and CockroachDB suites, and every fault-injection test (link cuts, lost requests or answers, stalled replication, paused or killed nodes, failover), except the short Kafka ones it keeps on purpose, each under 20 s: `catalog-audit`'s `BrokerOutageTest` and `KafkaPublishingTest`'s lost-answer and slow-answer tests, and `catalog-server`'s `KafkaOutageTest`. It also leaves out the `overnight` and `cas` tests below.
- The fast build also checks that every recorded bite still applies (`:bitesApply`, about 20 s); see Bites.
- `./gradlew build -Pheavy` runs every test, all four databases included, except the `overnight` tests and the CAS test. With `-Pheavy` or `-Pnightly` every test task runs afresh, never from the build cache.
- Tests tagged `overnight` never run in a build, fast or heavy: all of `catalog-tests-load` (the M8 load, resilience and SLO runs), `EngineMatrixTest`, `catalog-maintenance`'s `LargeExpiryTest` and `LargeOrphanScanTest`, and the two `MutationSuite` sweeps that repeat a lost answer and a link cut before COMMIT for every mutation type. Paul moved them out of the heavy build on 2026-11-11 to shorten it. The heavy build keeps the single lost-answer, lost-COMMIT, in-flight and failover tests on every database.
- The CAS test (`CommitSuite#exactlyOneWriterWinsEachRound`, tagged `cas`) never runs in a build, fast or heavy, and never in a build-and-test cycle: it takes too long. Paul's rule, 2026-11-10. It runs only overnight: `./gradlew build -Pnightly` runs the `overnight` tests and that test, on all four backends at 1,000 rounds (Paul cut it from 10,000 on 2026-11-11). `./nightly.sh` runs the same tests in two steps, the engine matrix on its own, and prints how many tests each module ran, zeros included; record each run in `status/`. Don't run `-Pnightly`, `./nightly.sh` or the CAS test by hand unless Paul asks: the overnight run (below) runs them.
- Every test that needs S3 uses one store, chosen with `-Ps3=silo|ceph|aws` (default `silo`). `aws` also needs `-Pnuna.aws.profile=<CLI profile>`; the run makes its own buckets and role there and deletes them at the end.
- A new test that waits on purpose or needs a multi-node cluster gets `@Tag("heavy")`. One that runs for minutes (load, soak, SLO, a sweep over every case) gets `@Tag("overnight")`.
- `gradle.properties` sets `org.gradle.workers.max=2`: two tasks run at once, so at most two modules are under test at a time. Not more: Docker has 8 GB, and a three-node cluster plus one other stack is what fits. The CockroachDB suite lives in `catalog-store-sql-raft`, which nothing depends on; no module's tests wait for another module's tests, so it runs beside the others instead of ahead of them.
- The nuna-demo stack (`demo/README.md`) runs in the same Docker Desktop and takes about 6 GB of it, 7.5 GB with its CDC leg, which leaves a build's containers too little. Every test failure seen beside it has been a wait that ran out: the audit consumer's 30 s, Flyway's 30 s for a PostgreSQL connection, Kafka never reaching RUNNING, Ryuk unreachable. When a build fails that way, look for `nuna-demo` pods in `docker ps` before suspecting the change. Rerun once; if it fails again with the demo up, report the branch as ready and say the demo held Docker. Never stop the demo yourself: ask Paul. Only the overnight run takes it down, on Paul's word.
- **The overnight run** (Paul, 2026-11-14): `overnight.sh`, started at midnight by Windows Task Scheduler (the task `nuna overnight`, which `overnight-task.ps1 -Register` makes), when a file other than Markdown changed on `main` since its last run. In a clone of its own (`C:/Users/paulh/code/nuna-overnight/clone`) it runs the heavy build, the Ceph leg and `./nightly.sh`, then bites one at a time until 06:30, starting each night where the last stopped. If the nuna-demo stack is running, it takes it down first (`demo/demo.sh down`) and rebuilds it at the end (`demo/demo.sh up`: fresh sample data, no CDC leg; the demo keeps no volumes, so nothing in it survives). At 07:00 a Claude scheduled task reads `C:/Users/paulh/code/nuna-overnight/runs/<date>/summary.md`, records the run in `status/` and adds a backlog item for each failure.
  - While `C:/Users/paulh/code/nuna-overnight/running` exists, Docker belongs to the run: start no build. A build that fails then failed for that reason; say so and rerun it after the run ends.

## Landing

- Every landing uses the fast build, whatever the branch changes, except a branch whose every changed file is Markdown: it lands without a build (Paul, 2026-11-14). Nothing the build compiles, tests or applies is a `.md` file. Check with `git diff --name-only main...<branch>`. Don't run `-Pheavy` by hand: the overnight run does, every night that code changed.
- When the work is finished and its build is green (or it changes only Markdown), land it without asking Paul:
  1. Merge the current `main` into the branch and run the fast build, `./gradlew build` (skipped for a Markdown-only branch). If that merge conflicts, settle it there, in the branch, as the agent that did the work, and run the build again. Never resolve a conflict in the main checkout.
  2. Run `./land.sh <branch> "<message>"`, with a "Land …" message ending with the attribution line. It takes its turn with any other landing, merges into `main` with `--no-ff` only if `main` has not moved since step 1, so the merge cannot conflict, and checks `git cherry`. If `main` has moved, go back to step 1. If the main checkout has changes, another session is working there: report the branch as ready instead.
  3. Release the backlog items the branch finished: `./backlog.sh release <n>`.
- After a branch lands, the session that started the agent archives it, which removes its worktree, then deletes the branch. Neither step needs asking.

## Bites

- A branch that adds a guarded check records its bite instead of running it: `bites/<milestone>/<id>.patch`, made with `git diff` of the broken check, headed by a `Module:` line and a `Test:` line (the test that must fail: `Class`, `Class#method` or `Class#method+method`). The two patches in `bites/M2/` show the form. Check it with `git apply --check`, then restore the file.
- The fast build fails when any recorded bite no longer applies (`:bitesApply`). A branch whose change moves the code a bite breaks records that bite again, with the same break, against its own code.
- `bites/run.sh` with no arguments runs every bite, in every folder under `bites/`: it applies each patch, runs its test with `-Pbites` (every test but CAS, so `overnight` tests can be selected too), expects a failure, reverts, and checks every tracked file's hash is restored.
- Its verdicts: BITES when a selected test fails on its own (an assertion, or the code under test throwing); NO BITE when they pass; SETUP when the run proves neither: the patch did not apply or compile, no test ran, or every failure came from starting the tests' fixtures (Gradle's `initializationError` or `executionError`, a Spring context that did not load, Testcontainers on the stack). SETUP fails the run as NO BITE does, and the last line counts it among the failures: "`<n> bites, <f> not biting or not restored (<s> of them SETUP)`". Read a SETUP bite's log in `build/bites/`, fix what stopped the fixtures (often the nuna-demo stack holding Docker) and run that bite again. A break that stops a Spring context loading is SETUP too: record such a bite against a test that fails on an assertion. `bites/test-run.sh` checks the verdicts against recorded result files, without Gradle or Docker; run it after changing `judge` or `tally`.

## The heavy pass, once, after M8

- The pass was planned for after M9. On 2026-11-10 Paul moved it to after M8, before M9: M9 happens at work, and he wants a fully checked build to carry there.
- One run in a worktree: `./heavy.sh` (`./gradlew build -Pheavy`, then a Ceph leg, `./gradlew :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph`, then every recorded bite; it stops if either build fails). The `overnight` tests are not part of it. Fix anything that fails or does not bite.
- The next pass, to harden v1.x phases 1 and 2, comes after the install at work (Paul, 2026-11-13).
- From 2026-11-15 the overnight run (Builds) runs the heavy build, the Ceph leg and the bites every night that code changed.
- The pass leaves out the CAS test: at the old 10,000 rounds it alone ran past 10 hours across the five backends of that day (YugabyteDB, dropped on 2026-11-11, took 2.2 s a round). It belongs to a scheduled overnight job in work CI (M9 in `build-plan.md`); at home the overnight run runs it, at 1,000 rounds, in `./nightly.sh`.
