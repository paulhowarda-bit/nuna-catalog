# 2026-11-24, maintenance readiness: whether the worker can maintain a catalog, step by step (backlog 251)

**Built** on `worktree-agent-a573ba2593028ce26`: `GET /management/v1/catalogs/{catalog}/maintenance/readiness`
(`ReadinessRoutes`), the route item 252's checklist reads. Four steps, each `done`, `missing`, `refused` or `unknown`,
and `reported-at`, from item 250's storage reports, the catalog's grants, its storage profile and its resolved policy:
`grant` (a group of the worker's token holds `write` on the catalog itself), `storage` (every allowed prefix covered by
a reported prefix, matched as the worker matches), `store` (listing let and no delete refused; refused with the store's
code; a failed listing unknown with the client's failure), `policy` (a job on). The first three are unknown before a
scan reports storage. Needs `describe`; names only for `modify`, `manage-grants` and platform operators; refusals
audited, reads not. Shape: docs/observability/table-health.md, "Readiness".

**Checked:**
- `ReadinessRoutesTest` 7/7 (heavy, alone, Docker otherwise idle, no nuna-demo): each state of each step, a scan under
  way keeping the last ended scan's refused deletes, names to the tester, the grant admin and an operator with
  `describe`, states without names to a describer and a tag steward, 404 to an outsider, 403 to a bystander and an
  operator without `describe`, the refusal audited and the read not.
- Bite `bites/D30/readiness-uncovered-prefix-done` recorded and `git apply --check`ed, tree restored; not run.
  `bites/D18/dr-freeze-lifecycle-refusal-unaudited` recorded again with the same break: its context was
  `ManagementGuard.operator`, now package-visible.
- `./gradlew build` on the branch (main at 4bf3e6e3, nothing to merge): green, 1 m 47 s, 1,723 test results, none failed;
  `:bitesApply` passed with the new and re-recorded bites.

**Not run:** the bite (the overnight run's). The test runs on PostgreSQL only, as every `GovernanceServer` test does.

**Open:** a platform operator holding no grant (the demo's `oscar`) is refused, since the route needs `describe` as the
item says; if item 252 is to show the checklist to `oscar`, admitting operators is one line and Paul's call.
