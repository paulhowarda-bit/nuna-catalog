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
