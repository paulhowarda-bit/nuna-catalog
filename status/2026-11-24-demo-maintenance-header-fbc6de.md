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
- Landed at 2a6b46e7. The demo was taken down and brought up from a checkout of `main` at 2a6b46e7 (D49 messages
  before and after; `up` finished, every step). Live, as `presenter` signed in through Keycloak at 1440 px:
  - Header: "presenter · 5 groups · until 11:03", the page 1,407 px in a 1,407 px client width, Sign out's right
    edge at 1,391 px.
  - The worker scanned every 5 minutes as `service-account-nuna-maintenance`, without failures. Overview: 15 of 16
    tables in shape, `brokerage.trading.orders` queued (score 7: 35 small files of 43, 27 equality deletes), and
    "Maintenance, last night": 1 table compacted, 32 small files merged into 6. Maintenance: the queue, the night of
    Mon 23 Nov (12:00 to 12:00, one slot, 1 compaction Job finished, none failed) and the policy as `up` set it.
  - Its first Job, at 14:30 UTC, failed: Silo's readiness probe timed out, so `http://s3:9000` refused the
    connection. Windows had 222 to 770 MB free and was paging the Docker VM (up to 36,000 pages in a second; about 37
    Claude processes held 3.4 GB), which also stalled Keycloak, Trino's probes and Docker's port forwarding (the
    `/ui/config.json` stall). Run again, the Job compacted `orders` in 5 minutes, peaking near 700 MiB of its 1,280.
  - Fixed after landing: `up` had started the worker straight after the sample tables, so its first scan assessed
    the change feeds' and brokerage world's tables before they had any small files, and a night assesses each table
    once: the queue stayed empty until noon. `up` now starts the worker last, after the generator; until then a
    restart of the worker (`kubectl -n nuna-demo rollout restart deployment/nuna-maintenance`) assesses again, which
    is how the queue above was made. `demo/README.md` says so.
- Seen, not changed: `GET /management/v1/maintenance/nights?jobs=true` answers 500, not 400, for a parameter that is
  not a number; the brokerage world's generator restarted 5 times in its first 13 minutes on SingleStore's
  `maximum_memory` ("Memory used by MemSQL (1740.25 Mb) has reached the 'maximum_memory' setting").
