# 2026-11-19, the management API hides namespace, table and view names as the Iceberg routes do (backlog 73)

Paul decided "Hide there too" and "Catalogs, not objects" (D18, amended again 2026-11-19): on
`/management/v1/catalogs/{catalog}/...`, a caller that holds nothing on a namespace, table or view gets the 404 a
missing one gets; one that holds something but not enough still gets 403; the refusal is audited either way.

What changed (catalog-server, `governance/` and `security/`):
- `security/Holdings` (new, public): item 14's rule, moved out of `rest/Guard`'s private methods because `rest/` was
  held by item 15. A caller holds something on a namespace, table or view when one of its groups holds a privilege on
  it or above it, or, for a namespace, on anything inside it (item 20's rule). `Guard` keeps its own copy until it
  asks `Holdings` (item 77).
- `Management.target` and `Securables.resolve` carry each target's missing answer; a table or view path whose
  namespace does not exist answers the table's or view's 404, not the namespace's.
- `ManagementGuard.require` and `GrantRoutes.require` audit a refusal, then answer 403 to a caller that holds
  something and the target's 404 to one that holds nothing. A refusal on the catalog itself stays 403 (item 68).
  The DR freeze and thaw (`requireOperatorOn`) hide only from non-operators: D37 gives operators the freeze by name.
- Routes now hiding: governance, attributes, holds, policies, freezes; the audit trail; a table's health; dropped
  objects in a namespace; grant, revoke and the grant listing on an object; and `POST .../grants/check`, which the
  item thought safe but which showed a refused object's id and reason where a missing one said "does not exist".

Checked:
- `ManagementHiddenNamesTest` (new, `governance/`, Java only, 12 tests) and `GrantHiddenNamesTest` (new,
  `security/`, Java only, 8 tests): the real routes, guard, `Holdings` and authorizer over a store double. Each
  route's answer for an existing name equals its answer once the object, then its namespace, is gone; 403 and 200
  for callers that hold something; grants inside a namespace show it; a grant admin sees every name, a platform
  operator none; the check answers a hidden object as a missing one; hidden refusals are audited with operation,
  privilege, reason, path and actor. `GrantRaceTest` builds `GrantRoutes` with `Holdings`.
- Heavy tests changed to expect the 404 for a caller that holds nothing: `SecurityGateTest`
  (`separationOfDutiesHoldsForEveryGrant`; new `theManagementApiHidesNamesAsTheIcebergRoutesDo`),
  `ManagementAuthorizationTest` (three outsider cases; new `aRefusalAnsweredAsAMissingNameIsAuditedToo`),
  `AuditReadRouteTest`, `HealthRoutesTest`, `ConsoleHealthTest`.
- Bites: eight new in `bites/D18/` (`management-names-shown-to-outsiders`, `management-table-path-names-its-namespace`,
  `holdings-blind-to-grants-inside`, `management-hidden-refusal-unaudited`, `grant-names-shown-to-outsiders`,
  `grant-table-path-names-its-namespace`, `check-shows-hidden-names`, `grant-hidden-refusal-unaudited`), each naming a
  test in the two new classes; `bites/M7/dropped-list-open`, `freeze-set-open` and `freeze-lift-open` recorded again
  with the same break against the new guard calls. Each checked with `git apply --check` and the file restored with
  `git checkout`, the hash compared. None was run.
- After merging `main` (at 702a316), the shared fast build script (`./gradlew build`): "All 325 bites apply.",
  BUILD SUCCESSFUL in 55s; `ManagementHiddenNamesTest` 12 of 12, `GrantHiddenNamesTest` 8 of 8 and item 14's
  `HiddenNamesTest` 23 of 23 passed; the test results in every module's build folder hold 986 tests in 89 classes,
  none failed or skipped.

Not covered:
- No container test ran: the heavy build is off tonight, so the five heavy classes above are changed but not run.
  `TelemetryTest#withoutAClientIdTheRequestIdIsTheTraceId` (heavy, `telemetry/`, held by item 71) still expects 403
  for an outsider on a management route and will fail until changed; `docs/observability/table-health.md` still says
  403. Both, and `Guard` asking `Holdings`, are item 77, with prepared patches not built here.
- Item 78, for Paul: the DR freeze and reconcile still show names to a platform operator that holds nothing (D37
  against "Catalogs, not objects"), and the routes by object id (undrop, export, purge) still answer 403 for an id in
  use and 404 for an unknown one.
