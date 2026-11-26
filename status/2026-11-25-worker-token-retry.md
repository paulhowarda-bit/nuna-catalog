# 2026-11-25, the worker's token request retried, and a scan that could not read its prefixes shown as unknown (backlog 275)

- `TokenSource.ClientCredentials`: a token request that cannot reach the identity provider (IOException) or gets a
  5xx is sent again after 1, 2, 4, then 8 s pauses, for at most 30 s in all, each try's timeout what is left of the 30
  s; any other answer but 200, a 4xx above all, fails at once as before. The pause is injectable, so the tests take no
  real time. Each retry is logged at WARN.
- A scan that could not read its storage prefixes now reports no `storage` at all, at its start and its end
  (`Worker.reach` returns the prefixes or none, `HealthReports` and `Management.reportScan` take the storage as
  optional); a scan that read them and reached none still reports `storage: []`. No schema change: every store
  already keeps an absent `storage` apart from an empty one (item 250's `HealthSuite` checks it), and the scan's
  `failures` still count the `storage-prefixes` failure.
- `ReadinessRoutes.Reported`, shared with `StorageSetupRoutes`: the worker's reach is judged by the newest scan, and
  one that reported no storage leaves it unknown instead of being skipped for an older scan. Readiness then gives
  grant, storage and store `unknown` and no `reported-at`, as before any scan; storage setup gives the worker's
  identity `state: unknown` and no `worker-settings`. Before any scan at all, storage setup is unchanged
  (`not-reported`, settings for every store). A newest scan from a worker of an earlier release, which reports no
  storage, is now unknown too rather than judged by an older scan.
- Console: unchanged. With no `reported-at`, readiness.js draws the storage and store steps "Not known" with its
  before-the-first-scan line ("No maintenance worker has reported, so the catalog can't check this."), and storage.js
  shows "No report yet" and a dialog without the worker's settings. The line is slightly wrong for this case (a worker
  did report, just not its reach); left for Atelier.
- Docs: `docs/observability/table-health.md` (The scans, Readiness, Storage setup, tests and bites),
  `catalog-maintenance/README.md` (storage prefixes, token settings, tests).
- Tests, all fast: `TokenSourceTest` +3 (unreachable then 503 then a token; 401 asked once; never answering or 502
  given up after 6 tries and 1+2+4+8+8 s of pauses); `WorkerPrefixesTest` checks both scan reports carry no `storage`
  after a failed read and `[]` after an empty one; new `ReadinessUnreadPrefixesTest` (3) runs the real readiness and
  storage-setup routes and guard over a stand-in store. `:catalog-maintenance:test` and
  `:catalog-server:test --tests 'nuna.catalog.server.governance.*'` green while working.
- Bites, recorded and checked with `git apply --check`: new `bites/D30/token-4xx-asked-again`,
  `token-not-asked-again`, `worker-unread-prefixes-reported-as-none`, `readiness-unread-scan-skipped`,
  `storage-setup-settings-after-unread-scan`; recorded again against the new code with the same breaks:
  `bites/D18/readiness-admits-every-caller`, `bites/D18/storage-setup-open`,
  `bites/D30/storage-setup-settings-for-a-reached-store`, `bites/D30/worker-failed-prefix-read-still-purges`. Each new
  bite was also applied by hand in the worktree and its test failed on an assertion (the no-retry one with the code
  under test throwing), then restored; `bites/run.sh` was not run.
- Fast build (`./gradlew build`) after merging `main` at 3a4e8ed1, no overnight run holding the lock: BUILD
  SUCCESSFUL in 1m 4s (50 tasks executed, the rest from cache), `:bitesApply` green.
- **Not checked:** live on the demo (the item's last line: the first scan after `demo/demo.sh up` never reads
  `missing`); the heavy `ReadinessRoutesTest` and `ConsoleReadinessBrowserTest`, which the overnight run takes; the
  re-recorded heavy bite `readiness-admits-every-caller` against its test.
