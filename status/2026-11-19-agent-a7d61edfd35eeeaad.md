# 2026-11-19, rest/Guard asks security/Holdings; an outsider's 404 on a management route in TelemetryTest and table-health.md (backlog 77)

Item 73 hid names on the management API with item 14's rule, copied into a public `security/Holdings` because `rest/`
was held by item 15. This branch finishes it with the two patches item 73's agent prepared, both applied as they were.

What changed:
- `rest/Guard` asks `Holdings` (`onOrAbove`, `onAboveOrIn`) and keeps no copy of the rule: its `holdsOnOrAbove`,
  `holdsOnAboveOrIn`, `below` and `parent` are gone. Same calls, same arguments, same code behind them, so the Iceberg
  routes answer as before (item 14). `HiddenNamesTest` builds `Guard` with `Holdings`.
- `bites/D18/namespace-blind-to-grants-inside.patch` recorded again with the same break, now in `Holdings`, still
  naming `HiddenNamesTest#aGrantOnAnythingInANamespaceLetsTheCallerSeeTheNamespace`.
- `TelemetryTest#withoutAClientIdTheRequestIdIsTheTraceId` (heavy) expects 404 for an outsider on a table's governance;
  `docs/observability/table-health.md` says a table's health answers 404 `NoSuchTableException` to a caller that holds
  nothing on the table, 403 to any other refused caller, and that every refusal is audited.

Checked:
- `git apply --check` of both patches on `main` at 53fef22, then of the re-recorded bite and the eight other bites on
  `Guard` or `Holdings`. No bite was run.
- Every heavy and overnight test that calls the management API, in `catalog-server`, `catalog-maintenance`,
  `catalog-tests-load`, `catalog-tests-engines` and `catalog-tests-conformance`, read for a 403 expected from a
  caller that holds nothing on a namespace, table or view: none is left besides `TelemetryTest`. The list is in
  `backlog/77.md`.
- After merging `main` (already up to date), the shared fast build script (`./gradlew build`): "All 325 bites
  apply.", BUILD SUCCESSFUL in 53s; `HiddenNamesTest` 23 of 23, `ManagementHiddenNamesTest` 12 of 12,
  `GrantHiddenNamesTest` 8 of 8; the test results in every module's build folder hold 986 tests in 89 classes, none
  failed or skipped.

Not covered: no container test ran (the heavy build is off tonight), so `TelemetryTest`'s change is not run; the
overnight heavy build runs it.
