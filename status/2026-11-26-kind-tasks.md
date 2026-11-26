# 2026-11-26 kind-tasks: no task limit in kind's node (backlog 294)

The data generator session found `demo.sh ss` failing at load.sql's first Iceberg pipeline (`ERROR 1933 ... Process
died unexpectedly or didn't start`). Every container in cluster nuna was capped at 307 tasks: Podman gave the node
PidsLimit 2048, and systemd in the node gave each container scope DefaultTasksMax=15% of that. SingleStore idles at
284. The node as a whole held 1,192 of its 2,048 with the demo, SingleStore and the generator running. Docker Desktop
set no limit, so this came with the move to Podman (backlog 287).

`deploy/kind/kind.sh up` now lifts both: the node's PidsLimit to unlimited, and a systemd drop-in in the node
(DefaultTasksMax=infinity), with the scopes already running lifted one by one. On the running node every container
scope reads `max`, and a new pod's too. A restart of the node was not tried.
