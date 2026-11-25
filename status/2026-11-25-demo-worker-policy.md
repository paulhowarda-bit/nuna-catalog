# 2026-11-25, the demo applies the worker's Silo policy the catalog generates (backlog 255)

- `demo/demo.sh` `worker_policy`: `up` (and `cdc stop`) reads `GET /management/v1/maintenance/storage-setup` as
  `oscar` once the catalog serves, makes each `to` policy for `nuna-maintenance` (`nuna-worker-s3`) in Silo and
  attaches it to that user, then removes the hand-written `nuna-maintenance` policy earlier ups left on Silo's volume.
  Run as `worker_policy || say …` inside `maintenance_worker`, after the user is made and before the worker's chart:
  a failure is reported and `up` goes on. `demo/k8s/silo-maintenance.json` is gone. Profile `s3` already named
  `maintenance-principal: nuna-maintenance` (item 256).
- `demo/README.md`, "Maintenance": the policy's source, why the `except` policy is not applied (the demo's catalog is
  Silo's root, `nunatest`), and the console's path for a second catalog's bucket (items 252, 254).
- Backlog 266 added: Overview still says the worker "has not reported" where readiness says why.
- Checked: `bash -n demo/demo.sh`; `worker_policy` with `tls_curl` and `kubectl` stubbed against a canned answer in
  the route's shape (policy text byte for byte; an unprotected answer fails the step and applies nothing). Not run
  against the stack: the demo is shared, and its running catalog predates `/storage-setup` (404). No build: the branch
  changes Markdown, shell and YAML that no Gradle task reads.
- Not covered: readiness 4 of 4 for `lakehouse` after `demo/demo.sh up`, which Paul checks after landing.
