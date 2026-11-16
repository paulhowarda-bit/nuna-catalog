# 2026-11-16, Silo's console on localhost:8184 (backlog 57)

Changed: Silo starts its console, MinIO's, on the pod's 9001 (`--console-address :9001` in
`demo/k8s/store-silo.yaml`); before, it took a random port at each start and nothing published it.
`demo/k8s/ports.yaml` publishes it on localhost:8184 (Service `silo-console`, DuckDB's old port), `up` checks 8184
is free with the others, and the README and the text `demo.sh` prints say where it is and that it signs in as
Silo's root `keeltest`. The engine matrix also gets the fixed port inside the pod, and publishes nothing.

Checked: `bash -n demo/demo.sh`; the changed `store-silo.yaml` and the `silo-console` Service in a scratch
namespace, `keel-silo-check`, deleted afterwards: Silo's log showed `WebUI: http://…:9001`, the Service took
localhost:8184, and <http://localhost:8184> signed in as `keeltest` to the console's overview (buckets, access
keys, identity, monitoring, settings). The fast build.

Not applied to the running demo: its Silo keeps no volume, so restarting it with the new arguments empties the
object store under the catalog's tables. It takes effect on the next fresh `up` (the overnight run's rebuild,
or `demo/demo.sh down` then `up`).
