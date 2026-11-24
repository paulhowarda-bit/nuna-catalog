# 2026-11-23, the console's browser tests share one Chrome, poll faster, and stay off a machine running the demo

Item 209 (Paul: "Why do all the console browser test take so long"). In the 2026-11-23 overnight heavy build they took
444 s of `catalog-server`'s 955 s of class time, 35 tests in seven classes, up from 334 s and 12 tests on 2026-11-19.
Beside the nuna-demo stack, a run of four of the classes went past 34 minutes, where they take about 4 overnight,
with Windows at 295 MB free, Chrome unreachable and waits running out.

`ConsoleBrowser` now keeps one Chrome for the six classes on the governance tests' server, stopped when their test
context closes, where each class started its own; its waits poll every 100 ms, not Selenium's 500; and it refuses to
start Chrome while the demo's pods run, unless `-Pnuna.demo-beside`. No test class changed.

The fast build passed. Not checked against Docker: a by-hand run of two browser classes was refused by the session's
permission check. The overnight heavy build is the first to run the change.

Item 218 (Paul: "yes, get the agent to take it down and do the test"). The refusal now tells the agent to take the demo
down under D49, run the tests and bring it back up from `main`, and CLAUDE.md says so. Then that was done: the D49
notices, the demo down from 22:46 to about 23:20, and the seven browser classes run (49 tests, 8 failed, all the
console's own expectations after S1 to S5, fixed in 6544bdea). Item 209 held against Docker: one Chrome per test
context, each stopped when the next took over, and waits polling every 100 ms. Classes sharing a Chrome ran 20 to 40%
faster than the night before; within a test the polling saves little.
