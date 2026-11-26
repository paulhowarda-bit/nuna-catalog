# 2026-11-26, the CDC leg's databases and CloudBeaver keep their data on volumes (backlog 293)

- Paul chose that the CDC leg keep its state like the rest of the demo (D52, amended 2026-11-26). `demo/k8s/cdc.yaml`
  gives backoffice, oracle and cockroach volume claims (label `nuna-demo/cdc-data`); `cloudbeaver.yaml` gives
  CloudBeaver's workspace claim `cloudbeaver-workspace`.
- Oracle: its image keeps its ready-made database in `/opt/oracle/oradata`, which the volume hides, so an init
  container copies it onto the empty volume (what the folder holds, not the folder: the volume's own folder belongs
  to root). `oracle.sql` is an initialization script now, run once per volume; a startup script the pod writes
  makes `/tmp/cdc-ready` at every start.
- CockroachDB: data and the certificates its entrypoint makes are on the volume (two subPaths); the readiness probe
  takes `init_success` at the first start and an open SQL port at a later one.
- `demo.sh`: `cdc stop` and `down` pause the leg (connectors stopped with their offsets, pods deleted; claims,
  topics, consumer groups and history tables kept). `cdc` carries on when configmap `cdc-begun` (written once a
  first run's changes reach Iceberg) exists, and otherwise starts anew, deleting what an unfinished first run left.
  `cdc reset` deletes the leg as `cdc stop` did before, claims too. `down` keeps `cdc-begun`; `wipe` deletes it all.
- `CloudBeaverUsers.java` updates a kept connection (token, password) instead of skipping it.
- Checked live on kind cluster nuna, from this branch: a first `cdc` (4 min) with Oracle copied onto its volume;
  `cdc stop` kept the claims and `cdc-begun`; `cdc` again (1 min 49 s) resumed all six connectors. PostgreSQL's and
  Oracle's history tables gained no rows on resume, and one change in each then arrived as one row (8 to 9, 7 to 8).
  CockroachDB's gained 3 rows on resume, the three changes made before the stop, then 1 for the new change: its
  connector repeats changes after its changefeed's last resolved point (README says so). `cdc reset` deleted the
  claims and restarted Trino and the writer. CloudBeaver's pod replaced on its kept workspace: the hook updated
  every connection, none doubled, 8185 answered.
- Two earlier tries failed on Windows' memory (0.4 GB free of 15.7 GB, the disk stalling while Windows paged):
  Oracle's background process did not start in 120 s and CockroachDB stopped on a disk stall. They passed once
  memory was free; SingleStore was off.
- Not checked: `down` then `up` with the leg paused, Oracle's resume after its 4 GB recovery area fills.
