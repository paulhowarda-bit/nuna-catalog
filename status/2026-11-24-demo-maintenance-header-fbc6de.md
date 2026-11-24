# 2026-11-24, the console header counts groups (229); the demo runs the maintenance worker (230)

Paul compared the live console with Atelier's prototype and found it doesn't look like it; "fix both" (the header
running off the screen, and the empty maintenance pages).

- **229.** The header reads "presenter · 5 groups · until 09:50", the names on hover and focus. Checked live on the
  demo with the UI files swapped in: at 1440 px the page is 1,407 px in a 1,422 px window (it was 1,687 px), Sign out
  on screen; 390 and 760 px do not scroll sideways. `ConsoleBrowserTest#theHeaderCountsTheGroupsAndNamesThemOnHover`
  passed with the demo down, and failed with its bite applied. Found and filed: 231 (the header is wider than the
  window at 761–1,016 and 1,241–1,297 px, as in the prototype; Atelier decides) and 232 (a frozen table's page at
  1440 px).
- **230.** `demo.sh up` turns on every job in `lakehouse`'s policy and runs the maintenance worker, its compaction
  Jobs in `nuna-demo-compaction`, the window open all day with nights turning at 12:00 New York. New worker setting
  `compaction.memory-request`, so a Job asks 64 MiB of a 1,280 MiB limit on a node 94% asked for.
  `./gradlew :catalog-maintenance:test --tests KubernetesJobsTest --tests TokenSourceTest`: 5 and 3 tests passed.
- Fast build after merging `main` (36727b08): green in 1 m 29 s, `:bitesApply` included.
- Not covered by a build: the worker in the demo itself (`demo.sh` and the chart's values). It is checked live after
  landing, below.
