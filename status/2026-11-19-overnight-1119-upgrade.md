# 2026-11-19, RollingUpgradeTest gives the previous release its own setting names (backlog 65)

Since the rename to nuna, `Stack.server` gave the previous release (2b1af36, from before the rename) its settings under
`nuna.`, which it does not read, and it stopped at startup with no token issuer. A server started from another jar than
this release's now gets them through `PreviousRelease.settings`: `--nuna.` and `--logging.level.nuna.` become
`--keel.` and `--logging.level.keel.`, and it is told this release's audit and change topics, whose defaults the
rename also changed, so old and new servers write one stream of each.

Checked: the mapped names are exactly the 13 `Stack` used before the rename (`git show a60daba^1`), with which the
test passed on 2026-11-18, plus `TestS3`'s `keel.storage.` names; `audit-topic` and `change-topic` are the old
release's `AuditSettings` (2b1af36). The fast build passes. Not run: `RollingUpgradeTest` itself, an `overnight` test;
the next overnight run is its check.

Also recorded the overnight run of 2026-11-19 (`2026-11-19-overnight.md`), added its TelemetryTest failure to item 62,
and added item 66, the run's late start.
