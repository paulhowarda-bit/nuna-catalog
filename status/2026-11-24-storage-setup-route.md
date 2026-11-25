# 2026-11-24, the storage setup route for operators (backlog 253)

**Built** on `worktree-agent-a77463709c73f2910`: `GET /management/v1/maintenance/storage-setup`
(`StorageSetupRoutes`), platform operators only, refusals audited. Per catalog storage profile: its catalogs and
prefixes, the worker's reach on its store from its last reports (item 250), the worker's settings only for a store
its report shows no identity on (item 256), and the delete policies with `mc` or `aws s3api put-bucket-policy`
commands, or the `maintenance-principal` setting an unprotected profile lacks. No key or secret: the worker's key is a
marked placeholder. `DeletePolicyRoutes.policies` and `ReadinessRoutes.reach` are shared with it. Docs:
docs/observability/table-health.md, "Storage setup"; deploy/README.md, "Delete rights".

**Checked:**
- `StorageSetupRoutesTest` (fast) 10/10, `StoragePrefixRoutesTest` 2/2.
- Bites `bites/D18/storage-setup-open` and `bites/D30/storage-setup-settings-for-a-reached-store`: each break made its
  test fail on an assertion when run once with `--tests`; recorded, restored, checked with `git apply --check`. The
  bites on the two refactored routes (`D30/delete-policies-open`, `D30/readiness-uncovered-prefix-done`,
  `D18/readiness-admits-every-caller`) still apply.
- `./gradlew build` with `main` merged (backlog 252 in; a conflict in table-health.md settled): green in 3 m, 1,743
  test results, none failed; `:bitesApply` passed.

**Not run:** the heavy `MaintenanceRoutesTest#theDeletePoliciesLeaveDeleteToTheWorker` and `ReadinessRoutesTest`,
which cover the two refactors through the server: the nuna-demo stack was up and Windows had about 0.8 GB free. The
overnight run.
